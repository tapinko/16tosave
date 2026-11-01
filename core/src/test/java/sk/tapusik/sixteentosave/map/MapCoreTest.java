package sk.tapusik.sixteentosave.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.tapusik.sixteentosave.storage.RegionHeader;

class MapCoreTest {
	@TempDir
	Path tmp;

	private static ChunkSummary flatChunk(int y, int mapColor, String biome, long writtenAt) {
		ChunkSummary.Builder b = new ChunkSummary.Builder();

		for (int c = 0; c < ChunkSummary.COLUMNS; c++) {
			b.set(c, mapColor, ChunkSummary.TINT_NONE, y, 0, biome);
		}

		return b.build(writtenAt);
	}

	@Test
	void summaryPacksColorAndTint() {
		byte packed = ChunkSummary.packColor(61, ChunkSummary.TINT_WATER);
		assertEquals(61, ChunkSummary.mapColorId(packed));
		assertEquals(ChunkSummary.TINT_WATER, ChunkSummary.tint(packed));

		ChunkSummary s = new ChunkSummary.Builder()
				.set(ChunkSummary.column(3, 4), 12, ChunkSummary.TINT_GRASS, -60, 300, "minecraft:plains")
				.set(ChunkSummary.column(0, 0), 1, ChunkSummary.TINT_NONE, 70, 0, "minecraft:desert")
				.build(42);
		assertEquals(-60, s.height()[ChunkSummary.column(3, 4)]);
		assertEquals(255, s.waterDepth()[ChunkSummary.column(3, 4)] & 0xFF);
		assertEquals("minecraft:desert", s.biomeAt(0));
		assertEquals(ChunkSummary.NO_BLOCK, s.height()[5]);
		assertEquals(List.of("minecraft:plains", "minecraft:desert"), s.biomePalette());
	}

	@Test
	void regionSummaryRoundTrip() throws IOException {
		RegionSummary region = new RegionSummary(-2, 7);
		region.put(0, flatChunk(64, 1, "minecraft:plains", 100));
		region.put(1023, flatChunk(-10, 12, "minecraft:ocean", 200));
		region.put(5, new ChunkSummary.Builder().build(300)); // no columns: empty palette

		Path file = tmp.resolve("map").resolve(RegionSummaryCodec.fileName(-2, 7));
		RegionSummaryCodec.save(file, region);
		RegionSummary loaded = RegionSummaryCodec.load(file, -2, 7);

		assertEquals(3, loaded.count());
		assertEquals(region.get(0), loaded.get(0));
		assertEquals(region.get(1023), loaded.get(1023));
		assertEquals(region.get(5), loaded.get(5));
		assertNull(loaded.get(1));
	}

	@Test
	void unreadableSummaryIsEmpty() throws IOException {
		Path garbage = Files.write(tmp.resolve("r.0.0.cps"), new byte[] {1, 2, 3});
		assertEquals(0, RegionSummaryCodec.load(garbage, 0, 0).count());
		assertEquals(0, RegionSummaryCodec.load(tmp.resolve("missing.cps"), 0, 0).count());

		RegionSummary other = new RegionSummary(1, 1);
		other.put(0, flatChunk(1, 1, "a", 1));
		byte[] bytes = RegionSummaryCodec.encode(other);
		assertThrows(IOException.class, () -> RegionSummaryCodec.decode(bytes, 0, 0));
	}

	@Test
	void currentnessUsesRegionTimestampsWithTolerance() {
		RegionSummary region = new RegionSummary(0, 0);
		region.put(0, flatChunk(1, 1, "a", 1000));
		java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(8192);
		header.putInt(0, 2 << 8 | 1);
		header.putInt(4096, 1003);
		header.putInt(4, 3 << 8 | 1);
		header.putInt(4096 + 4, 1);
		RegionHeader h = RegionHeader.parse(header);

		assertTrue(region.isCurrent(0, h, 5));
		assertFalse(region.isCurrent(0, h, 2));
		assertFalse(region.isCurrent(1, h, 5)); // no summary
	}

	@Test
	void rasterShadesReliefAndLeavesGapsTransparent() {
		RegionSummary region = new RegionSummary(0, 0);
		ChunkSummary.Builder b = new ChunkSummary.Builder();

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				// a step up at z = 8
				b.set(ChunkSummary.column(x, z), 1, ChunkSummary.TINT_NONE, z < 8 ? 64 : 70, 0, "p");
			}
		}

		region.put(0, b.build(1));
		TileRasterizer.RegionRaster r = TileRasterizer.raster(region, (chunk, column, bx, bz) -> 0xFFFFFF, true);

		int flat = r.lod0()[2 + 4 * 512];
		int step = r.lod0()[2 + 8 * 512];
		int gap = r.lod0()[100 + 100 * 512];
		assertEquals(0xFF000000 | TileRasterizer.NORMAL << 16 | TileRasterizer.NORMAL << 8 | TileRasterizer.NORMAL, flat);
		assertEquals(0xFFFFFFFF, step);
		assertEquals(0, gap);
		assertEquals(1, r.chunkCount());
		assertTrue((r.lod2()[0] >>> 24) != 0);
		assertEquals(0, r.lod2()[1]);
		assertTrue((r.averageArgb() >>> 24) != 0);
	}

	@Test
	void waterGetsDarkerWithDepth() {
		assertEquals(TileRasterizer.HIGH, TileRasterizer.waterBrightness(1, 0, 0));
		assertEquals(TileRasterizer.NORMAL, TileRasterizer.waterBrightness(6, 0, 0));
		assertEquals(TileRasterizer.LOW, TileRasterizer.waterBrightness(20, 0, 0));
	}

	@Test
	void downsampleAveragesOnlyOpaquePixels() {
		int[] src = new int[16];
		src[0] = 0xFF0000FF;
		src[1] = 0xFF0000FF;
		src[5] = 0xFF000003;
		int[] out = TileRasterizer.downsample(src, 4, 2);
		assertArrayEquals(new int[] {0xFF0000AB, 0, 0, 0}, out);
	}

	@Test
	void lodSelectionWithHysteresis() {
		assertEquals(Lod.BLOCKS, Lod.forScale(4));
		assertEquals(Lod.BLOCKS, Lod.forScale(0.5));
		assertEquals(Lod.FOUR, Lod.forScale(0.49));
		assertEquals(Lod.CHUNKS, Lod.forScale(0.1));
		assertEquals(Lod.REGIONS, Lod.forScale(0.01));

		assertEquals(Lod.BLOCKS, Lod.select(0.47, Lod.BLOCKS));
		assertEquals(Lod.FOUR, Lod.select(0.44, Lod.BLOCKS));
		assertEquals(Lod.FOUR, Lod.select(0.53, Lod.FOUR));
		assertEquals(Lod.BLOCKS, Lod.select(0.56, Lod.FOUR));
		assertEquals(Lod.REGIONS, Lod.CHUNKS.coarser());
		assertEquals(Lod.REGIONS, Lod.REGIONS.coarser());
	}

	@Test
	void viewZoomKeepsCursorBlockFixed() {
		MapView view = new MapView();
		view.setViewport(0, 20, 400, 300);
		view.setCenter(-1000.5, 250);
		view.setScale(2);

		double bx = view.blockX(37);
		double bz = view.blockZ(211);
		view.zoomAt(1.7, 37, 211);
		assertEquals(bx, view.blockX(37), 1e-9);
		assertEquals(bz, view.blockZ(211), 1e-9);
		assertEquals(37, view.screenX(bx), 1e-9);

		view.setScale(1000);
		assertEquals(MapView.MAX_SCALE, view.scale());
		view.setScale(0);
		assertEquals(MapView.MIN_SCALE, view.scale());
	}

	@Test
	void visibleRegionsAtNegativeCoordinates() {
		MapView view = new MapView();
		view.setViewport(0, 0, 100, 100);
		view.setCenter(-1, -1);
		view.setScale(1);
		assertArrayEquals(new int[] {-1, -1, 0, 0}, view.visibleRegions());

		view.setCenter(-300, -300);
		assertArrayEquals(new int[] {-1, -1, -1, -1}, view.visibleRegions());

		view.pan(100, 0);
		assertEquals(-400, view.centerX(), 1e-9);
	}

	@Test
	void tileQueueOrdersByPriorityAndDeduplicates() throws InterruptedException {
		TileQueue<String> q = new TileQueue<>();
		q.request("far", 100);
		q.request("near", 1);
		q.request("mid", 10);
		q.request("far", 0.5); // re-prioritised
		assertEquals(3, q.size());
		assertEquals("far", q.take(0, TimeUnit.MILLISECONDS));
		assertEquals("near", q.take(0, TimeUnit.MILLISECONDS));
		assertTrue(q.contains("mid"));
		q.clear();
		assertNull(q.take(1, TimeUnit.MILLISECONDS));
	}

	@Test
	void incompleteContainerFlagSurvivesTheCodecAndReachesTheRaster() throws IOException {
		RegionSummary region = new RegionSummary(3, -1);
		ChunkSummary.Builder b = new ChunkSummary.Builder();
		b.set(0, 1, ChunkSummary.TINT_NONE, 64, 0, "minecraft:plains");
		region.put(33, b.incompleteContainers(true).build(10));
		region.put(34, flatChunk(64, 1, "minecraft:plains", 10));

		Path file = tmp.resolve(RegionSummaryCodec.fileName(3, -1));
		RegionSummaryCodec.save(file, region);
		RegionSummary back = RegionSummaryCodec.load(file, 3, -1);
		assertTrue(back.get(33).incompleteContainers());
		assertFalse(back.get(34).incompleteContainers());
		assertEquals(region.get(33), back.get(33));

		TileRasterizer.RegionRaster raster = TileRasterizer.raster(back, (chunk, column, x, z) -> 0x00FF00, false);
		long[] bits = raster.incomplete();
		assertEquals(16, bits.length);
		assertEquals(1L << 33, bits[0]);
	}

	@Test
	void oldSummaryFormatIsRebuilt() throws IOException {
		// a version-1 file is ignored: the map rebuilds it (and so learns the new flag)
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

		try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(bytes))) {
			out.writeInt(RegionSummaryCodec.MAGIC);
			out.writeInt(1);
			out.writeInt(0);
			out.writeInt(0);
			out.writeShort(0);
		}

		Path file = tmp.resolve(RegionSummaryCodec.fileName(0, 0));
		Files.write(file, bytes.toByteArray());
		assertEquals(0, RegionSummaryCodec.load(file, 0, 0).count());
	}
}


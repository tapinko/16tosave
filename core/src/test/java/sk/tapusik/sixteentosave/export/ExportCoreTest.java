package sk.tapusik.sixteentosave.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionFileReader;
import sk.tapusik.sixteentosave.storage.RegionHeader;

class ExportCoreTest {
	@TempDir
	Path tmp;

	/** "key:type" for every entry of a compound, recursively ("a.b:int"); list elements as "[]". */
	private static Set<String> shape(Map<String, Object> compound, String prefix) {
		Set<String> out = new HashSet<>();

		for (Map.Entry<String, Object> e : compound.entrySet()) {
			String key = prefix + e.getKey();
			out.add(key + ":" + Nbt.typeOf(e.getValue()));

			if (e.getValue() instanceof Map<?, ?> m) {
				out.addAll(shape(cast(m), key + "."));
			}
		}

		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object o) {
		return (Map<String, Object>) o;
	}

	private static WorldFiles.LevelSettings settings() {
		return new WorldFiles.LevelSettings("My export", WorldFiles.GameMode.CREATIVE, true,
				new WorldFiles.Spawn(WorldFiles.OVERWORLD, 100, 70, -200, 90f), 5023, "26.3", false, 1_770_000_000_000L);
	}

	@Test
	void nbtRoundTrip() throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("b", (byte) -1);
		root.put("s", (short) 300);
		root.put("i", 7);
		root.put("l", Long.MIN_VALUE);
		root.put("f", 1.5f);
		root.put("d", -2.25);
		root.put("str", "žltý kôň");
		root.put("ba", new byte[] { 1, 2 });
		root.put("ia", new int[] { 3, -4 });
		root.put("la", new long[] { 5L });
		root.put("empty", List.of());
		root.put("strings", List.of("a", "b"));
		root.put("nested", Map.of("x", 1));

		Path file = tmp.resolve("t.dat");
		Nbt.writeCompressed(file, root);
		Map<String, Object> back = Nbt.readCompressed(file);

		assertEquals(root.keySet(), back.keySet());
		assertEquals((byte) -1, back.get("b"));
		assertEquals("žltý kôň", back.get("str"));
		assertArrayEquals(new int[] { 3, -4 }, (int[]) back.get("ia"));
		assertEquals(List.of(), back.get("empty"));
		assertEquals(List.of("a", "b"), back.get("strings"));
		assertEquals(Map.of("x", 1), back.get("nested"));

		assertThrows(IllegalArgumentException.class, () -> Nbt.write(new java.io.DataOutputStream(java.io.OutputStream.nullOutputStream()),
				Map.of("mixed", List.of(1, "x"))));
	}

	@Test
	void levelDatHasTheShapeOfA26xServerWorld() {
		Map<String, Object> level = WorldFiles.levelDat(settings());

		// level.dat written by a 26.1 server (MCA Selector generator template), minus ServerBrands
		// (vanilla adds its own brand on load)
		Set<String> reference = Set.of(
				"Data:10",
				"Data.difficulty_settings:10", "Data.difficulty_settings.difficulty:8",
				"Data.difficulty_settings.hardcore:1", "Data.difficulty_settings.locked:1",
				"Data.Time:4", "Data.GameType:3", "Data.version:3", "Data.LastPlayed:4",
				"Data.spawn:10", "Data.spawn.pos:11", "Data.spawn.pitch:5", "Data.spawn.dimension:8", "Data.spawn.yaw:5",
				"Data.Version:10", "Data.Version.Snapshot:1", "Data.Version.Series:8", "Data.Version.Id:3", "Data.Version.Name:8",
				"Data.LevelName:8", "Data.initialized:1", "Data.WasModded:1", "Data.DataVersion:3", "Data.allowCommands:1",
				"Data.DataPacks:10", "Data.DataPacks.Enabled:9", "Data.DataPacks.Disabled:9");
		assertEquals(reference, shape(level, ""));

		Map<String, Object> data = cast(level.get("Data"));
		assertEquals(19133, data.get("version"));
		assertEquals(1, data.get("GameType"));
		assertEquals((byte) 1, data.get("allowCommands"));
		assertEquals((byte) 1, data.get("initialized"));
		assertEquals(5023, data.get("DataVersion"));
		assertEquals(5023, cast(data.get("Version")).get("Id"));
		assertEquals("26.3", cast(data.get("Version")).get("Name"));
		assertArrayEquals(new int[] { 100, 70, -200 }, (int[]) cast(data.get("spawn")).get("pos"));
		assertEquals(List.of("vanilla"), cast(data.get("DataPacks")).get("Enabled"));
	}

	@Test
	void worldGenSettings() {
		Map<String, Object> vanilla = WorldFiles.worldGenSettings(
				WorldFiles.generators(WorldFiles.Generator.VANILLA, WorldFiles.VANILLA_DIMENSIONS), 5023);
		assertEquals(5023, vanilla.get("DataVersion"));
		Map<String, Object> data = cast(vanilla.get("data"));
		assertEquals(0L, data.get("seed"));
		assertEquals((byte) 0, data.get("generate_structures"));
		assertEquals((byte) 0, data.get("bonus_chest"));

		Map<String, Object> dims = cast(data.get("dimensions"));
		assertEquals(List.of(WorldFiles.OVERWORLD, WorldFiles.NETHER, WorldFiles.END), List.copyOf(dims.keySet()));

		// Nether and End exactly as in the 26.1 server's world_gen_settings.dat
		Map<String, Object> nether = cast(dims.get(WorldFiles.NETHER));
		assertEquals("minecraft:the_nether", nether.get("type"));
		Map<String, Object> netherGen = cast(nether.get("generator"));
		assertEquals("minecraft:noise", netherGen.get("type"));
		assertEquals("minecraft:nether", netherGen.get("settings"));
		assertEquals(new TreeMap<>(Map.of("type", "minecraft:multi_noise", "preset", "minecraft:nether")),
				new TreeMap<>(cast(netherGen.get("biome_source"))));
		Map<String, Object> endGen = cast(cast(dims.get(WorldFiles.END)).get("generator"));
		assertEquals("minecraft:end", endGen.get("settings"));
		assertEquals(Map.of("type", "minecraft:the_end"), cast(endGen.get("biome_source")));

		// void everywhere when everything is exported with "void"
		Map<String, WorldFiles.Generator> allVoid = WorldFiles.generators(WorldFiles.Generator.VOID, WorldFiles.VANILLA_DIMENSIONS);
		assertTrue(WorldFiles.experimental(allVoid));
		Map<String, Object> voidDims = cast(cast(WorldFiles.worldGenSettings(allVoid, 1).get("data")).get("dimensions"));

		for (String id : WorldFiles.VANILLA_DIMENSIONS) {
			Map<String, Object> gen = cast(cast(voidDims.get(id)).get("generator"));
			assertEquals("minecraft:flat", gen.get("type"));
			Map<String, Object> flat = cast(gen.get("settings"));
			assertEquals("minecraft:the_void", flat.get("biome"));
			assertEquals((byte) 0, flat.get("features"));
			assertEquals(List.of(Map.of("block", "minecraft:air", "height", 1)), flat.get("layers"));
		}
	}

	@Test
	void regionWriterRoundTrip() throws IOException {
		Path file = tmp.resolve("region").resolve("r.-1.0.mca");
		Random random = new Random(1);
		byte[] small = "hello chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] medium = new byte[100_000];
		random.nextBytes(medium);
		byte[] huge = new byte[1_200_000]; // incompressible, over 255 sectors → .mcc
		random.nextBytes(huge);

		try (RegionFileWriter w = new RegionFileWriter(file)) {
			w.write(-1, 0, small, 1000);
			w.write(-32, 31, medium, 2000);
			w.write(-5, 7, huge, 3000);
			assertThrows(IllegalStateException.class, () -> w.write(-1, 0, small, 1));
			assertEquals(3, w.chunkCount());
		}

		assertEquals(0, Files.size(file) % RegionHeader.SECTOR_BYTES);
		assertTrue(Files.exists(file.resolveSibling("c.-5.7.mcc")));

		RegionHeader header = RegionHeader.read(file);
		assertEquals(3, header.presentCount());
		assertEquals(1000, header.timestampSeconds(RegionHeader.localIndex(-1, 0)));
		assertArrayEquals(small, RegionFileReader.read(file, header, -1, 0));
		assertArrayEquals(medium, RegionFileReader.read(file, header, -32, 31));
		assertArrayEquals(huge, RegionFileReader.read(file, header, -5, 7));
		assertNull(RegionFileReader.read(file, header, -2, 0));
	}

	@Test
	void spawnPicker() {
		ChunkSelection s = new ChunkSelection();
		s.applyRect(0, 0, 2, 2, ChunkSelection.Mode.ADD, null); // 9 chunks
		s.applyRect(100, 100, 109, 101, ChunkSelection.Mode.ADD, null); // 20 chunks: the larger area
		s.set(50, 50, true);
		assertEquals(ChunkKey.pack(104, 100), SpawnPicker.pick(s), "nearest to (104.5, 100.5), ties → smaller key");

		ChunkSelection l = new ChunkSelection(); // L shape: centre is outside the area
		l.applyRect(0, 0, 9, 0, ChunkSelection.Mode.ADD, null);
		l.applyRect(0, 1, 0, 9, ChunkSelection.Mode.ADD, null);
		long chosen = SpawnPicker.pick(l);
		assertTrue(l.contains(ChunkKey.x(chosen), ChunkKey.z(chosen)));

		assertThrows(IllegalArgumentException.class, () -> SpawnPicker.pick(new ChunkSelection()));
	}

	@Test
	void columnHeight() {
		// 384-high overworld: 9 bits, 7 per long, 37 longs
		assertEquals(9, ColumnHeight.heightmapBits(37));
		long[] heightmap = new long[37];
		int index = 5 + 3 * 16;
		int raw = 64 + 70 + 1; // first free Y = 71 with minY -64
		heightmap[index / 7] |= (long) raw << ((index % 7) * 9);
		assertEquals(71, ColumnHeight.fromHeightmap(heightmap, -64, 5, 3));
		assertEquals(ColumnHeight.UNKNOWN, ColumnHeight.fromHeightmap(heightmap, -64, 0, 0));
		assertEquals(ColumnHeight.UNKNOWN, ColumnHeight.fromHeightmap(new long[5], 0, 0, 0));

		List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
		long[] data = new long[256]; // 4 bits, 16 per long
		int stone = (2 << 8) | (4 << 4) | 1; // y 2, z 4, x 1
		data[stone / 16] |= 1L << ((stone % 16) * 4);
		assertEquals(3 * 16 + 2, ColumnHeight.topInSection(3, palette, data, 1, 4));
		assertEquals(ColumnHeight.UNKNOWN, ColumnHeight.topInSection(3, palette, data, 0, 0));
		assertEquals(-1 * 16 + 15, ColumnHeight.topInSection(-1, List.of("minecraft:stone"), null, 0, 0));
		assertEquals(ColumnHeight.UNKNOWN, ColumnHeight.topInSection(0, List.of("minecraft:air"), null, 0, 0));
	}

	@Test
	void layout() {
		Set<String> taken = Set.of("Base", "Base (2)", ".Other" + ExportLayout.STAGING_SUFFIX);
		assertEquals("Base (3)", ExportLayout.folderName("Base", taken::contains));
		assertEquals("a_b_c_", ExportLayout.folderName("a/b:c?", taken::contains));
		assertEquals("World", ExportLayout.folderName(" ... ", taken::contains));
		assertEquals("_con", ExportLayout.folderName("CON", n -> false).toLowerCase(java.util.Locale.ROOT));
		assertEquals("Other (2)", ExportLayout.folderName("Other", taken::contains), "a staging folder of that name counts");
		assertEquals("hidden", ExportLayout.folderName(".hidden", n -> false));

		assertTrue(ExportLayout.isStaging(ExportLayout.stagingName("x")));
		assertFalse(ExportLayout.isStaging("x"));

		Path world = tmp.resolve("w");
		assertEquals(world.resolve("dimensions").resolve("minecraft").resolve("overworld"),
				ExportLayout.dimensionDir(world, WorldFiles.OVERWORLD));
		assertEquals(world.resolve("dimensions").resolve("ns").resolve("a").resolve("b"), ExportLayout.dimensionDir(world, "ns:a/b"));
		assertEquals(world.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"), ExportLayout.worldGenSettingsFile(world));
	}

	@Test
	void report() {
		ExportReport r = new ExportReport();
		ExportReport.Dimension o = r.dimension(WorldFiles.OVERWORLD);
		o.written = 10;
		o.skip(ExportReport.Skip.CORRUPT);
		o.skip(ExportReport.Skip.CORRUPT);
		r.dimension(WorldFiles.NETHER).skip(ExportReport.Skip.NOT_CACHED);
		assertEquals(10, r.written());
		assertEquals(3, r.skipped());
		assertEquals(2, o.skipped.get(ExportReport.Skip.CORRUPT));
		assertEquals(o, r.dimension(WorldFiles.OVERWORLD));
	}

	@Test
	void netherAndEndAreVanillaUnlessExported() {
		// only the overworld exported: no reason for a void Nether/End, so no "experimental" warning
		Map<String, WorldFiles.Generator> overworldOnly = WorldFiles.generators(WorldFiles.Generator.VOID, List.of(WorldFiles.OVERWORLD));
		assertEquals(WorldFiles.Generator.VOID, overworldOnly.get(WorldFiles.OVERWORLD));
		assertEquals(WorldFiles.Generator.VANILLA, overworldOnly.get(WorldFiles.NETHER));
		assertEquals(WorldFiles.Generator.VANILLA, overworldOnly.get(WorldFiles.END));
		assertFalse(WorldFiles.experimental(overworldOnly));

		Map<String, WorldFiles.Generator> withNether = WorldFiles.generators(WorldFiles.Generator.VOID,
				List.of(WorldFiles.OVERWORLD, WorldFiles.NETHER));
		assertEquals(WorldFiles.Generator.VOID, withNether.get(WorldFiles.NETHER));
		assertEquals(WorldFiles.Generator.VANILLA, withNether.get(WorldFiles.END));
		assertTrue(WorldFiles.experimental(withNether));

		Map<String, Object> dims = cast(cast(WorldFiles.worldGenSettings(overworldOnly, 1).get("data")).get("dimensions"));
		assertEquals("minecraft:flat", cast(cast(dims.get(WorldFiles.OVERWORLD)).get("generator")).get("type"));
		assertEquals("minecraft:noise", cast(cast(dims.get(WorldFiles.NETHER)).get("generator")).get("type"));
		assertEquals("minecraft:noise", cast(cast(dims.get(WorldFiles.END)).get("generator")).get("type"));
	}

	@Test
	void pngDecodesToTheSamePixels() throws IOException {
		int[] argb = new int[4 * 3];

		for (int i = 0; i < argb.length; i++) {
			argb[i] = 0xFF000000 | (i * 20) << 16 | (255 - i * 20) << 8 | 7;
		}

		argb[5] = 0x80123456;
		java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(PngWriter.encode(4, 3, argb)));
		assertEquals(4, image.getWidth());
		assertEquals(3, image.getHeight());

		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 4; x++) {
				assertEquals(argb[x + y * 4], image.getRGB(x, y), "pixel " + x + "," + y);
			}
		}

		assertThrows(IllegalArgumentException.class, () -> PngWriter.encode(2, 2, new int[3]));
	}

	@Test
	void worldIconShowsTheSelectedArea() {
		ChunkSelection s = new ChunkSelection();
		s.applyRect(-2, -2, 1, 1, ChunkSelection.Mode.ADD, null); // 4×4 chunks = 64×64 blocks: 1 px per block
		s.set(50, 50, true); // a smaller area elsewhere is not shown

		sk.tapusik.sixteentosave.map.ChunkSummary.Builder b = new sk.tapusik.sixteentosave.map.ChunkSummary.Builder();

		for (int c = 0; c < sk.tapusik.sixteentosave.map.ChunkSummary.COLUMNS; c++) {
			b.set(c, 1, sk.tapusik.sixteentosave.map.ChunkSummary.TINT_NONE, 64, 0, "minecraft:plains");
		}

		sk.tapusik.sixteentosave.map.ChunkSummary flat = b.build(1);
		int[] icon = WorldIcon.render(s, ChunkKey.pack(0, 0), (rx, rz) -> {
			sk.tapusik.sixteentosave.map.RegionSummary region = new sk.tapusik.sixteentosave.map.RegionSummary(rx, rz);

			for (int i = 0; i < 1024; i++) {
				int cx = rx * 32 + (i & 31);
				int cz = rz * 32 + (i >> 5);

				if (cx != -2 || cz != -2) { // chunk (-2,-2) has no summary
					region.put(i, flat);
				}
			}

			return region;
		}, (chunk, column, x, z) -> 0x40C040);

		assertEquals(WorldIcon.SIZE * WorldIcon.SIZE, icon.length);
		int green = icon[40 + 40 * WorldIcon.SIZE];
		assertEquals(0xFF, green >>> 24);
		assertTrue(((green >> 8) & 0xFF) > ((green >> 16) & 0xFF), "green-ish map colour");
		assertEquals(WorldIcon.BACKGROUND, icon[0], "the chunk without a summary stays dark");
	}
}


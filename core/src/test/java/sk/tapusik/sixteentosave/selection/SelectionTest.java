package sk.tapusik.sixteentosave.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionHeader;

class SelectionTest {
	@TempDir
	Path tmp;

	/** Brute-force reference: the set of chunk keys in a rectangle. */
	private static Set<Long> rect(int x0, int z0, int x1, int z1) {
		Set<Long> out = new HashSet<>();

		for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
			for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
				out.add(ChunkKey.pack(x, z));
			}
		}

		return out;
	}

	private static Set<Long> keys(ChunkSelection s) {
		Set<Long> out = new HashSet<>();
		s.forEach((x, z) -> out.add(ChunkKey.pack(x, z)));
		return out;
	}

	private static ChunkSelection of(Set<Long> keys) {
		ChunkSelection s = new ChunkSelection();

		for (long k : keys) {
			s.set(ChunkKey.x(k), ChunkKey.z(k), true);
		}

		return s;
	}

	/** A region header whose chunks {@code present} each use {@code sectors} sectors. */
	private static RegionHeader header(int sectors, int... present) {
		ByteBuffer buffer = ByteBuffer.allocate(2 * RegionHeader.SECTOR_BYTES);
		int next = 2;

		for (int index : present) {
			buffer.putInt(4 * index, next << 8 | sectors);
			next += sectors;
		}

		return RegionHeader.parse(buffer);
	}

	@Test
	void setToggleAndCount() {
		ChunkSelection s = new ChunkSelection();
		assertTrue(s.isEmpty());

		s.set(-1, -1, true);
		s.set(-1, -1, true); // already set: count unchanged
		s.set(31, 0, true);
		s.set(32, 0, true);
		assertEquals(3, s.count());
		assertTrue(s.contains(-1, -1));
		assertFalse(s.contains(-2, -1));
		assertEquals(3, s.regionKeys().size());
		assertEquals(1, s.countInRegion(-1, -1));

		assertFalse(s.toggle(-1, -1));
		assertTrue(s.toggle(-33, 5));
		assertEquals(3, s.count());
		assertNull(s.regionBits(-1, -1), "empty region is dropped");
		assertEquals(1, s.countInRegion(-2, 0));

		ChunkSelection copy = s.copy();
		assertEquals(s, copy);
		copy.set(0, 0, true);
		assertFalse(s.contains(0, 0), "copy is independent");

		s.clear();
		assertEquals(0, s.count());
		assertTrue(s.regionKeys().isEmpty());
	}

	@Test
	void rectMatchesBruteForceAcrossRegionsAndNegatives() {
		int[][] cases = {
				{ 0, 0, 0, 0 },
				{ 0, 0, 31, 31 }, // exactly one region, full rows
				{ -1, -1, 0, 0 }, // four regions
				{ -40, 3, 70, 3 }, // one long row, full middle region rows
				{ 5, -100, -5, -30 }, // corners in reverse order
				{ -64, -64, -33, -1 }, // two whole negative regions
		};

		for (int[] c : cases) {
			ChunkSelection s = new ChunkSelection();
			s.applyRect(c[0], c[1], c[2], c[3], ChunkSelection.Mode.ADD, null);
			Set<Long> expected = rect(c[0], c[1], c[2], c[3]);
			assertEquals(expected, keys(s), () -> java.util.Arrays.toString(c));
			assertEquals(expected.size(), s.count());
		}
	}

	@Test
	void rectModes() {
		ChunkSelection s = new ChunkSelection();
		s.applyRect(0, 0, 9, 9, ChunkSelection.Mode.ADD, null);
		s.applyRect(5, 5, 14, 14, ChunkSelection.Mode.ADD, null);
		assertEquals(100 + 100 - 25, s.count());

		s.applyRect(0, 0, 4, 9, ChunkSelection.Mode.SUBTRACT, null);
		assertEquals(175 - 50, s.count());
		assertFalse(s.contains(0, 0));
		assertTrue(s.contains(5, 0));

		s.applyRect(100, 100, 101, 101, ChunkSelection.Mode.REPLACE, null);
		assertEquals(rect(100, 100, 101, 101), keys(s));

		s.applyRect(-500, -500, -400, -400, ChunkSelection.Mode.SUBTRACT, null); // nothing there
		assertEquals(4, s.count());
	}

	@Test
	void universeLimitsEveryOperation() {
		ChunkSelection universe = of(rect(0, 0, 3, 3)); // 16 cached chunks
		universe.set(40, 40, true);

		ChunkSelection s = new ChunkSelection();
		s.applyRect(-10, -10, 1, 1, ChunkSelection.Mode.ADD, universe);
		assertEquals(rect(0, 0, 1, 1), keys(s));

		s.applyCircle(40, 40, 3, ChunkSelection.Mode.ADD, universe);
		assertEquals(5, s.count());
		assertTrue(s.contains(40, 40));

		s.selectAll(universe);
		assertEquals(17, s.count());
		assertEquals(universe, s);

		s.applyRect(0, 0, 3, 1, ChunkSelection.Mode.SUBTRACT, universe);
		s.invert(universe);
		assertEquals(rect(0, 0, 3, 1), keys(s));

		s.set(1000, 1000, true); // not cached
		assertEquals(9, s.count());
		assertEquals(8, s.countWithin(universe));
		s.retainAll(universe);
		assertEquals(8, s.count());
		assertFalse(s.contains(1000, 1000));
	}

	@Test
	void circle() {
		ChunkSelection s = new ChunkSelection();
		s.applyCircle(-1, 0, 0, ChunkSelection.Mode.ADD, null);
		assertEquals(Set.of(ChunkKey.pack(-1, 0)), keys(s));

		s.applyCircle(31, 31, 2, ChunkSelection.Mode.REPLACE, null);
		int expected = 0;

		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				if (dx * dx + dz * dz <= 4) {
					expected++;
					assertTrue(s.contains(31 + dx, 31 + dz));
				}
			}
		}

		assertEquals(expected, s.count());
		assertEquals(4, s.regionKeys().size(), "crosses into four regions");
	}

	@Test
	void toggleRegion() {
		ChunkSelection universe = new ChunkSelection();
		universe.set(-32, 0, true); // region (-1, 0)
		universe.set(-1, 31, true);
		universe.set(0, 0, true); // region (0, 0)

		ChunkSelection s = new ChunkSelection();
		s.set(-1, 31, true); // partly selected → select the rest
		s.toggleRegion(-1, 0, universe);
		assertEquals(2, s.count());
		assertTrue(s.contains(-32, 0));

		s.toggleRegion(-1, 0, universe); // all selected → clear
		assertTrue(s.isEmpty());

		s.toggleRegion(5, 5, universe); // nothing cached there
		assertTrue(s.isEmpty());

		s.toggleRegion(2, 2, null); // no universe: whole region
		assertEquals(1024, s.count());
		s.toggleRegion(2, 2, null);
		assertTrue(s.isEmpty());
	}

	@Test
	void setRegionBitsKeepsCount() {
		ChunkSelection s = new ChunkSelection();
		s.set(0, 0, true);
		long[] bits = new long[ChunkSelection.WORDS];
		bits[0] = 0b1110;
		bits[15] = Long.MIN_VALUE; // chunk (31, 31)
		s.setRegionBits(0, 0, bits);
		assertEquals(4, s.count());
		assertFalse(s.contains(0, 0));
		assertTrue(s.contains(31, 31));

		s.setRegionBits(0, 0, new long[ChunkSelection.WORDS]);
		assertEquals(0, s.count());
		assertTrue(s.regionKeys().isEmpty());

		s.setRegionBits(0, 0, new long[3]); // wrong length: ignored
		assertEquals(0, s.count());
	}

	@Test
	void codecRoundTripAndBadEntries() {
		ChunkSelection s = new ChunkSelection();
		s.applyRect(-40, -40, 40, 40, ChunkSelection.Mode.ADD, null);
		s.set(-1_000_000, 999_999, true);

		Map<String, String> encoded = SelectionCodec.encode(s);
		assertEquals(s.regionKeys().size(), encoded.size());
		assertTrue(encoded.containsKey("-31250,31249"));
		assertEquals(s, SelectionCodec.decode(encoded));

		Map<String, String> bad = new HashMap<>(encoded);
		bad.put("x,1", encoded.get("0,0"));
		bad.put("1", encoded.get("0,0"));
		bad.put("7,7", "not base64!");
		bad.put("8,8", "AAAA");
		bad.put("9,9", null);
		assertEquals(s, SelectionCodec.decode(bad));

		assertTrue(SelectionCodec.decode(null).isEmpty());
	}

	@Test
	void universeAndSizeEstimate() {
		Map<Long, RegionHeader> chunks = new HashMap<>();
		chunks.put(ChunkKey.pack(0, 0), header(2, 0, 1, 33));
		chunks.put(ChunkKey.pack(-1, 0), header(1, RegionHeader.localIndex(-1, 0)));
		Map<Long, RegionHeader> entities = Map.of(ChunkKey.pack(0, 0), header(1, 1));

		ChunkSelection universe = SelectionStats.universe(chunks);
		assertEquals(4, universe.count());
		assertTrue(universe.contains(1, 1)); // index 33
		assertTrue(universe.contains(-1, 0));

		ChunkSelection s = new ChunkSelection();
		s.applyRect(-1, 0, 1, 0, ChunkSelection.Mode.ADD, null); // (-1,0) (0,0) (1,0)
		s.set(500, 500, true); // no header: 0 bytes
		long sector = RegionHeader.SECTOR_BYTES;
		assertEquals(sector + 2 * sector + (2 * sector + sector), SelectionStats.estimateBytes(s, chunks, entities));

		SelectionStats.addRegion(universe, 0, 0, header(1, 5));
		assertEquals(2, universe.count(), "addRegion replaces the region");
	}

	@Test
	void addRegionBitsIsUnion() {
		ChunkSelection s = new ChunkSelection();
		s.set(-32, -32, true); // region (-1, -1), index 0
		long[] bits = new long[ChunkSelection.WORDS];
		bits[0] = 0b11;
		s.addRegionBits(-1, -1, bits);
		assertEquals(2, s.count());
		assertTrue(s.contains(-31, -32));
		s.addRegionBits(-1, -1, new long[ChunkSelection.WORDS]);
		assertEquals(2, s.count());
	}

	@Test
	void selectionPaths() throws IOException {
		Path dim = tmp.resolve("srv").resolve("minecraft").resolve("overworld");
		assertEquals(dim.resolve("selection.json"), SelectionPaths.current(dim));
		assertEquals(dim.resolve("selections").resolve("my_base.json"), SelectionPaths.named(dim, " My Base "));

		assertEquals(dim, SelectionPaths.dimensionDirOf(SelectionPaths.current(dim)));
		assertEquals(dim, SelectionPaths.dimensionDirOf(SelectionPaths.named(dim, "x")));
		assertNull(SelectionPaths.dimensionDirOf(dim.resolve("region").resolve("r.0.0.mca")));
		assertNull(SelectionPaths.dimensionDirOf(dim.resolve("selections").resolve("notes.txt")));

		Files.createDirectories(dim.resolve("selections"));
		Files.writeString(SelectionPaths.current(dim), "{}");
		Files.writeString(SelectionPaths.named(dim, "a"), "{}");
		Files.writeString(dim.resolve("selections").resolve("notes.txt"), "");
		Files.createDirectories(dim.resolve("region"));
		Files.writeString(dim.resolve("region").resolve("r.0.0.mca"), "");

		assertEquals(List.of(SelectionPaths.current(dim), SelectionPaths.named(dim, "a")),
				SelectionPaths.findAll(tmp.resolve("srv")));
		assertTrue(SelectionPaths.findAll(tmp.resolve("missing")).isEmpty());
	}

	@Test
	void cacheHeadersFromFiles() throws IOException {
		Path region = Files.createDirectories(tmp.resolve("region"));
		Path entities = tmp.resolve("entities"); // missing folder

		ByteBuffer header = ByteBuffer.allocate(2 * RegionHeader.SECTOR_BYTES);
		header.putInt(4 * RegionHeader.localIndex(-1, 2), 2 << 8 | 1);
		Files.write(region.resolve("r.-1.0.mca"), header.array());
		Files.write(region.resolve("r.5.5.mca"), new byte[0]); // empty: no chunks
		Files.writeString(region.resolve("readme.txt"), "x");

		CacheHeaders headers = CacheHeaders.load(region, entities);
		assertEquals(2, headers.chunks().size());
		assertTrue(headers.entities().isEmpty());

		ChunkSelection universe = headers.universe();
		assertEquals(Set.of(ChunkKey.pack(-1, 2)), keys(universe));
		assertEquals(1, CacheHeaders.readOne(region, -1, 0).presentCount());
		assertNull(CacheHeaders.readOne(region, 9, 9));
	}
}

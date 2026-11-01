package sk.tapusik.sixteentosave.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StorageBasicsTest {
	@Test
	void chunkKeyRoundTripsIncludingNegatives() {
		int[][] cases = {{0, 0}, {1, -1}, {-1, 1}, {Integer.MIN_VALUE, Integer.MAX_VALUE}, {-30_000_000 >> 4, 29_999_999 >> 4}};

		for (int[] c : cases) {
			long key = ChunkKey.pack(c[0], c[1]);
			assertEquals(c[0], ChunkKey.x(key));
			assertEquals(c[1], ChunkKey.z(key));
		}
	}

	@Test
	void chunkKeyMatchesVanillaLayout() {
		// vanilla ChunkPos packs x into the low and z into the high 32 bits
		assertEquals(0x0000000200000001L, ChunkKey.pack(1, 2));
		assertEquals(0xFFFFFFFFFFFFFFFFL, ChunkKey.pack(-1, -1));
	}

	@Test
	void regionAndChunkMathFloors() {
		assertEquals(0, ChunkKey.chunkToRegion(31));
		assertEquals(1, ChunkKey.chunkToRegion(32));
		assertEquals(-1, ChunkKey.chunkToRegion(-1));
		assertEquals(-1, ChunkKey.chunkToRegion(-32));
		assertEquals(-2, ChunkKey.chunkToRegion(-33));
		assertEquals(-1, ChunkKey.blockToChunk(-1));
		assertEquals(-1, ChunkKey.blockToChunk(-16));
		assertEquals(-2, ChunkKey.blockToChunk(-17));
		assertEquals(ChunkKey.pack(-1, 0), ChunkKey.regionKeyOfChunk(ChunkKey.pack(-5, 31)));
	}

	@Test
	void safeSegmentHandlesHostileInput() {
		assertEquals("play.example.com_25565", PathNames.safeSegment("Play.Example.com_25565"));
		assertEquals("a_b_c", PathNames.safeSegment("a/b\\c"));
		assertEquals("_", PathNames.safeSegment(""));
		assertEquals("_", PathNames.safeSegment(null));
		assertEquals("_._", PathNames.safeSegment("."));
		assertEquals("_.._", PathNames.safeSegment(".."));
		assertEquals("_.hidden", PathNames.safeSegment(".hidden"));
		assertEquals("name._", PathNames.safeSegment("name."));
		assertEquals("_con", PathNames.safeSegment("CON"));
		assertEquals("_nul.txt", PathNames.safeSegment("nul.txt"));
		String longName = "x".repeat(500);
		assertTrue(PathNames.safeSegment(longName).length() <= PathNames.MAX_SEGMENT_LENGTH);
		assertEquals(PathNames.safeSegment(longName), PathNames.safeSegment(longName));
	}

	@Test
	void dimensionDirsStayInsideBase() {
		Path base = Path.of("cache", "server");
		assertEquals(base.resolve("minecraft").resolve("overworld"), PathNames.dimensionDir(base, "minecraft:overworld"));
		assertEquals(base.resolve("minecraft").resolve("the_nether"), PathNames.dimensionDir(base, "the_nether"));
		assertEquals(base.resolve("myserver").resolve("worlds").resolve("resource"), PathNames.dimensionDir(base, "myserver:worlds/resource"));

		Path sneaky = PathNames.dimensionDir(base, "x:../../../etc").normalize();
		assertTrue(sneaky.startsWith(base), sneaky.toString());
	}

	@Test
	void regionFileNames() {
		assertEquals("r.-1.2.mca", RegionFileName.of(-1, 2));
		assertEquals(Optional.of(new RegionFileName.Coords(-1, 2)), RegionFileName.parse("r.-1.2.mca"));
		assertTrue(RegionFileName.parse("r.1.2.mcc").isEmpty());
		assertTrue(RegionFileName.parse("r.1.mca").isEmpty());
		assertTrue(RegionFileName.parse("r.99999999999.0.mca").isEmpty());
	}
}

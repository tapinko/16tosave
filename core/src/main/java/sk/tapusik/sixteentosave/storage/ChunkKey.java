package sk.tapusik.sixteentosave.storage;

/**
 * Packs a pair of chunk (or region) coordinates into one {@code long}: x in the low 32 bits,
 * z in the high 32 bits. This is the same layout vanilla uses for chunk positions.
 */
public final class ChunkKey {
	private ChunkKey() {
	}

	public static long pack(int x, int z) {
		return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
	}

	public static int x(long key) {
		return (int) key;
	}

	public static int z(long key) {
		return (int) (key >>> 32);
	}

	/** Region coordinate (32×32 chunks) of a chunk coordinate; floors for negative values. */
	public static int chunkToRegion(int chunkCoord) {
		return chunkCoord >> 5;
	}

	/** Chunk coordinate of a block coordinate; floors for negative values. */
	public static int blockToChunk(int blockCoord) {
		return blockCoord >> 4;
	}

	public static long regionKeyOfChunk(long chunkKey) {
		return pack(chunkToRegion(x(chunkKey)), chunkToRegion(z(chunkKey)));
	}
}

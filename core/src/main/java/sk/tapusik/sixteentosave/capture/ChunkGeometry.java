package sk.tapusik.sixteentosave.capture;

/** Horizontal distance helpers between a point (block coordinates) and a chunk. */
public final class ChunkGeometry {
	private ChunkGeometry() {
	}

	/** Squared horizontal distance from (x, z) to the farthest corner of the chunk. */
	public static double farthestCornerDistanceSq(double x, double z, int chunkX, int chunkZ) {
		double minX = chunkX * 16.0;
		double minZ = chunkZ * 16.0;
		double dx = Math.max(Math.abs(x - minX), Math.abs(x - (minX + 16.0)));
		double dz = Math.max(Math.abs(z - minZ), Math.abs(z - (minZ + 16.0)));
		return dx * dx + dz * dz;
	}

	/** Whether every column of the chunk is within {@code range} blocks of (x, z). */
	public static boolean isChunkWithin(double x, double z, int chunkX, int chunkZ, double range) {
		return farthestCornerDistanceSq(x, z, chunkX, chunkZ) <= range * range;
	}
}

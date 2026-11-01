package sk.tapusik.sixteentosave.export;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sk.tapusik.sixteentosave.map.Lod;
import sk.tapusik.sixteentosave.map.RegionSummary;
import sk.tapusik.sixteentosave.map.TileRasterizer;
import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * The exported world's 64×64 icon in the world list: the map view of the largest selected area,
 * drawn like the map screen from the cache's summary index. Chunks outside the selection are
 * dark (they are void in the new world).
 */
public final class WorldIcon {
	public static final int SIZE = 64;
	static final int BACKGROUND = 0xFF15151A;
	private static final int MIN_CHUNKS = 4;
	/** Keeps the work bounded (at most 5×5 regions are drawn). */
	private static final int MAX_CHUNKS = 128;

	/** A region's chunk summaries; an empty summary if there are none. */
	@FunctionalInterface
	public interface Summaries {
		RegionSummary load(int regionX, int regionZ);
	}

	private WorldIcon() {
	}

	/**
	 * @param centerChunk where to centre the view if the area is larger than the icon can show
	 *                    (e.g. the spawn chunk)
	 * @return {@link #SIZE}² ARGB pixels, row by row
	 */
	public static int[] render(ChunkSelection selection, long centerChunk, Summaries summaries, TileRasterizer.ColumnColors colors) {
		List<Long> area = SpawnPicker.largestArea(selection);
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (long key : area) {
			minX = Math.min(minX, ChunkKey.x(key));
			maxX = Math.max(maxX, ChunkKey.x(key));
			minZ = Math.min(minZ, ChunkKey.z(key));
			maxZ = Math.max(maxZ, ChunkKey.z(key));
		}

		int side = Math.max(MIN_CHUNKS, Math.max(maxX - minX + 1, maxZ - minZ + 1));
		int startX;
		int startZ;

		if (side > MAX_CHUNKS) {
			side = MAX_CHUNKS;
			startX = ChunkKey.x(centerChunk) - side / 2;
			startZ = ChunkKey.z(centerChunk) - side / 2;
		} else {
			startX = minX - (side - (maxX - minX + 1)) / 2;
			startZ = minZ - (side - (maxZ - minZ + 1)) / 2;
		}

		double blocksPerPixel = side * 16.0 / SIZE;
		Lod lod = blocksPerPixel >= 16 ? Lod.CHUNKS : blocksPerPixel >= 4 ? Lod.FOUR : Lod.BLOCKS;
		Map<Long, TileRasterizer.RegionRaster> rasters = new HashMap<>();
		int[] out = new int[SIZE * SIZE];

		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				int bx = (int) Math.floor(startX * 16 + (px + 0.5) * blocksPerPixel);
				int bz = (int) Math.floor(startZ * 16 + (py + 0.5) * blocksPerPixel);
				int pixel = BACKGROUND;

				if (selection.contains(bx >> 4, bz >> 4)) {
					int rx = bx >> 9;
					int rz = bz >> 9;
					TileRasterizer.RegionRaster raster = rasters.computeIfAbsent(ChunkKey.pack(rx, rz),
							k -> TileRasterizer.raster(summaries.load(rx, rz), colors, true));
					int scale = lod.blocksPerTexel;
					int tile = lod.tileSize;
					int p = raster.pixels(lod)[((bx & 511) / scale) + ((bz & 511) / scale) * tile];

					if ((p >>> 24) != 0) {
						pixel = p;
					}
				}

				out[px + py * SIZE] = pixel;
			}
		}

		return out;
	}
}

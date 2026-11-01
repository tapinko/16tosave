package sk.tapusik.sixteentosave.map;

import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Turns a region's chunk summaries into map pixels (design §7.3): one ARGB pixel per block at
 * LOD 0, then averaged down for the coarser levels. Relief shading follows vanilla maps: a column
 * higher than its northern neighbour is drawn brighter, a lower one darker; water gets darker
 * with depth. Transparent pixels (alpha 0) mean "not cached".
 */
public final class TileRasterizer {
	public static final int REGION_BLOCKS = 512;
	private static final int CHUNKS_PER_SIDE = 32;

	// vanilla map brightness steps (MapColor.Brightness)
	static final int LOW = 180;
	static final int NORMAL = 220;
	static final int HIGH = 255;

	/** Base colour of one column as {@code 0xRRGGBB}, or a negative value for "draw nothing". */
	@FunctionalInterface
	public interface ColumnColors {
		int rgb(ChunkSummary chunk, int column, int blockX, int blockZ);
	}

	/**
	 * @param lod0         512×512 pixels, one per block; {@code null} if not kept
	 * @param lod1         128×128, one per 4×4 blocks
	 * @param lod2         32×32, one per chunk
	 * @param averageArgb  one colour for the whole region (LOD 3); alpha 0 if nothing is cached
	 * @param chunkCount   chunks with data in this region
	 * @param incomplete   chunks with containers whose contents were never seen, as region bits
	 *                     ({@code (x & 31) + (z & 31) * 32}, 16 longs)
	 */
	public record RegionRaster(int regionX, int regionZ, int[] lod0, int[] lod1, int[] lod2, int averageArgb, int chunkCount,
			long[] incomplete) {
		public int[] pixels(Lod lod) {
			return switch (lod) {
				case BLOCKS -> lod0;
				case FOUR -> lod1;
				case CHUNKS -> lod2;
				case REGIONS -> null;
			};
		}
	}

	private TileRasterizer() {
	}

	public static RegionRaster raster(RegionSummary region, ColumnColors colors, boolean keepLod0) {
		int[] lod0 = lod0(region, colors);
		int[] lod1 = downsample(lod0, REGION_BLOCKS, 4);
		int[] lod2 = downsample(lod1, REGION_BLOCKS / 4, 4);
		int[] lod3 = downsample(lod2, CHUNKS_PER_SIDE, CHUNKS_PER_SIDE);
		return new RegionRaster(region.regionX(), region.regionZ(), keepLod0 ? lod0 : null, lod1, lod2, lod3[0], region.count(),
				incompleteBits(region));
	}

	/** Chunks flagged {@link ChunkSummary#incompleteContainers()}, as region bits. */
	static long[] incompleteBits(RegionSummary region) {
		long[] bits = new long[RegionHeader.CHUNKS / 64];

		for (int i = 0; i < RegionHeader.CHUNKS; i++) {
			ChunkSummary s = region.get(i);

			if (s != null && s.incompleteContainers()) {
				bits[i >> 6] |= 1L << (i & 63);
			}
		}

		return bits;
	}

	static int[] lod0(RegionSummary region, ColumnColors colors) {
		int[] out = new int[REGION_BLOCKS * REGION_BLOCKS];
		int baseX = region.regionX() * REGION_BLOCKS;
		int baseZ = region.regionZ() * REGION_BLOCKS;

		for (int cz = 0; cz < CHUNKS_PER_SIDE; cz++) {
			for (int cx = 0; cx < CHUNKS_PER_SIDE; cx++) {
				ChunkSummary chunk = region.get(cx + cz * CHUNKS_PER_SIDE);

				if (chunk == null) {
					continue;
				}

				ChunkSummary north = cz > 0 ? region.get(cx + (cz - 1) * CHUNKS_PER_SIDE) : null;

				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int column = ChunkSummary.column(x, z);
						int h = chunk.height()[column];

						if (h == ChunkSummary.NO_BLOCK) {
							continue;
						}

						int px = cx * 16 + x;
						int pz = cz * 16 + z;
						int rgb = colors.rgb(chunk, column, baseX + px, baseZ + pz);

						if (rgb < 0) {
							continue;
						}

						int depth = chunk.waterDepth()[column] & 0xFF;
						int brightness;

						if (depth > 0) {
							brightness = waterBrightness(depth, px, pz);
						} else {
							int northHeight = h;

							if (z > 0) {
								northHeight = chunk.height()[ChunkSummary.column(x, z - 1)];
							} else if (north != null) {
								northHeight = north.height()[ChunkSummary.column(x, 15)];
							}

							if (northHeight == ChunkSummary.NO_BLOCK) {
								northHeight = h;
							}

							brightness = landBrightness(h, northHeight, px, pz);
						}

						out[px + pz * REGION_BLOCKS] = shade(rgb, brightness);
					}
				}
			}
		}

		return out;
	}

	/** Vanilla map formula for land relief. */
	static int landBrightness(int height, int northHeight, int x, int z) {
		double d = (height - northHeight) * 0.8 + (((x + z) & 1) - 0.5) * 0.4;
		return d > 0.6 ? HIGH : d < -0.6 ? LOW : NORMAL;
	}

	/** Vanilla map formula for water depth. */
	static int waterBrightness(int depth, int x, int z) {
		double f = depth * 0.1 + ((x + z) & 1) * 0.2;
		return f < 0.5 ? HIGH : f > 0.9 ? LOW : NORMAL;
	}

	static int shade(int rgb, int brightness) {
		int r = ((rgb >> 16) & 0xFF) * brightness / 255;
		int g = ((rgb >> 8) & 0xFF) * brightness / 255;
		int b = (rgb & 0xFF) * brightness / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/**
	 * Averages {@code factor × factor} blocks of opaque pixels into one. A block with no opaque
	 * pixel stays transparent; a partly covered one is opaque with the average of what is there.
	 */
	public static int[] downsample(int[] src, int srcSize, int factor) {
		int size = srcSize / factor;
		int[] out = new int[size * size];

		for (int oy = 0; oy < size; oy++) {
			for (int ox = 0; ox < size; ox++) {
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;

				for (int y = oy * factor; y < (oy + 1) * factor; y++) {
					int row = y * srcSize;

					for (int x = ox * factor; x < (ox + 1) * factor; x++) {
						int p = src[row + x];

						if ((p >>> 24) != 0) {
							r += (p >> 16) & 0xFF;
							g += (p >> 8) & 0xFF;
							b += p & 0xFF;
							n++;
						}
					}
				}

				if (n > 0) {
					out[ox + oy * size] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
				}
			}
		}

		return out;
	}

	/** Chunks of a region that have a summary, as a 1024-entry mask (for coverage overlays). */
	public static boolean[] coverage(RegionSummary region) {
		boolean[] out = new boolean[RegionHeader.CHUNKS];

		for (int i = 0; i < RegionHeader.CHUNKS; i++) {
			out[i] = region.get(i) != null;
		}

		return out;
	}
}

package sk.tapusik.sixteentosave.map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * Draws the selection over the map (design §7.4): runs of selected chunks per row, identical
 * rows merged into one rectangle; when chunks are tiny, one shaded square per region.
 */
final class SelectionOverlay {
	private static final double MIN_CHUNK_PIXELS = 1.5;
	private static final int CHUNKS = 32;
	private static final int HATCH_MIN_PIXELS = 8;

	private SelectionOverlay() {
	}

	/** {@code visible} = {@link MapView#visibleRegions()}; {@code argb} = colour of a fully selected area. */
	static void draw(GuiGraphicsExtractor graphics, MapView view, ChunkSelection selection, int[] visible, int argb) {
		boolean coarse = 16 * view.scale() < MIN_CHUNK_PIXELS;

		for (long key : selection.regionKeys()) {
			int rx = ChunkKey.x(key);
			int rz = ChunkKey.z(key);

			if (rx < visible[0] || rz < visible[1] || rx > visible[2] || rz > visible[3]) {
				continue;
			}

			if (coarse) {
				int count = selection.countInRegion(rx, rz);
				int alpha = (argb >>> 24) * Math.max(count, 256) / 1024;
				fillChunks(graphics, view, rx * CHUNKS, rz * CHUNKS, CHUNKS, CHUNKS, (alpha << 24) | (argb & 0xFFFFFF));
				continue;
			}

			long[] bits = selection.regionBits(rx, rz);
			int lz = 0;

			while (lz < CHUNKS) {
				long row = row(bits, lz);
				int end = lz + 1;

				while (end < CHUNKS && row(bits, end) == row) {
					end++;
				}

				for (long r = row; r != 0; ) {
					int start = Long.numberOfTrailingZeros(r);
					int length = Long.numberOfTrailingZeros(~(r >>> start));
					fillChunks(graphics, view, rx * CHUNKS + start, rz * CHUNKS + lz, length, end - lz, argb);
					r &= ~(((1L << length) - 1) << start);
				}

				lz = end;
			}
		}
	}

	/** The 32 bits of row {@code lz} of a region, as an unsigned value. */
	private static long row(long[] bits, int lz) {
		return (bits[lz >> 1] >>> ((lz & 1) * 32)) & 0xFFFFFFFFL;
	}

	/** Fills {@code width × height} chunks starting at chunk ({@code chunkX}, {@code chunkZ}). */
	static void fillChunks(GuiGraphicsExtractor graphics, MapView view, int chunkX, int chunkZ, int width, int height, int argb) {
		int x0 = (int) Math.round(view.screenX(chunkX * 16.0));
		int y0 = (int) Math.round(view.screenY(chunkZ * 16.0));
		int x1 = (int) Math.round(view.screenX((chunkX + width) * 16.0));
		int y1 = (int) Math.round(view.screenY((chunkZ + height) * 16.0));

		if (x1 == x0) {
			x1++;
		}

		if (y1 == y0) {
			y1++;
		}

		graphics.fill(x0, y0, x1, y1, argb);
	}

	/**
	 * Diagonal stripes over one chunk (used for "containers never opened"). Each stripe is one
	 * rotated fill, cut to the chunk exactly, so no scissor is needed; small chunks get a tint.
	 */
	static void hatch(GuiGraphicsExtractor graphics, MapView view, int chunkX, int chunkZ, int argb) {
		int x0 = (int) Math.round(view.screenX(chunkX * 16.0));
		int y0 = (int) Math.round(view.screenY(chunkZ * 16.0));
		int x1 = (int) Math.round(view.screenX((chunkX + 1) * 16.0));
		int y1 = (int) Math.round(view.screenY((chunkZ + 1) * 16.0));
		int size = Math.min(x1 - x0, y1 - y0);

		if (size < HATCH_MIN_PIXELS) {
			graphics.fill(x0, y0, Math.max(x1, x0 + 1), Math.max(y1, y0 + 1), (argb & 0xFFFFFF) | 0x70000000);
			return;
		}

		int step = Math.max(4, size / 5);

		// lines x - y = c inside the square [0, size]²
		for (int c = -size + step / 2; c < size; c += step) {
			int startX = Math.max(c, 0);
			int startY = Math.max(-c, 0);
			int length = (int) Math.ceil((size - Math.abs(c)) * Math.sqrt(2));
			graphics.pose().pushMatrix();
			graphics.pose().translate(x0 + startX, y0 + startY);
			graphics.pose().rotate((float) (Math.PI / 4));
			graphics.fill(0, 0, length, 1, argb);
			graphics.pose().popMatrix();
		}
	}

	/** Outline of a chunk rectangle (corners inclusive, any order). */
	static void outline(GuiGraphicsExtractor graphics, MapView view, int x0, int z0, int x1, int z1, int fillArgb, int lineArgb) {
		int minX = Math.min(x0, x1);
		int minZ = Math.min(z0, z1);
		int w = Math.abs(x1 - x0) + 1;
		int h = Math.abs(z1 - z0) + 1;
		fillChunks(graphics, view, minX, minZ, w, h, fillArgb);

		int sx0 = (int) Math.round(view.screenX(minX * 16.0));
		int sy0 = (int) Math.round(view.screenY(minZ * 16.0));
		int sx1 = Math.max(sx0 + 1, (int) Math.round(view.screenX((minX + w) * 16.0)));
		int sy1 = Math.max(sy0 + 1, (int) Math.round(view.screenY((minZ + h) * 16.0)));
		graphics.fill(sx0, sy0, sx1, sy0 + 1, lineArgb);
		graphics.fill(sx0, sy1 - 1, sx1, sy1, lineArgb);
		graphics.fill(sx0, sy0, sx0 + 1, sy1, lineArgb);
		graphics.fill(sx1 - 1, sy0, sx1, sy1, lineArgb);
	}
}

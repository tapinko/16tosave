package sk.tapusik.sixteentosave.export;

import java.util.List;
import java.util.Set;

/**
 * Finds the height of one block column in raw chunk data, for placing the spawn: from the packed
 * {@code MOTION_BLOCKING} heightmap, or by scanning the sections' block palettes.
 */
public final class ColumnHeight {
	public static final int UNKNOWN = Integer.MIN_VALUE;
	private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

	private ColumnHeight() {
	}

	/**
	 * First free Y above the column from a heightmap ({@code minY} = bottom of the dimension);
	 * {@link #UNKNOWN} if the array doesn't look like a heightmap or the column is empty.
	 */
	public static int fromHeightmap(long[] data, int minY, int localX, int localZ) {
		int bits = heightmapBits(data.length);

		if (bits < 0) {
			return UNKNOWN;
		}

		int index = localX + localZ * 16;
		int perLong = 64 / bits;
		long word = data[index / perLong];
		int raw = (int) ((word >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
		return raw == 0 ? UNKNOWN : minY + raw;
	}

	/** Bits per entry for a 256-entry heightmap of {@code longs} longs (entries never span two longs). */
	static int heightmapBits(int longs) {
		for (int bits = 1; bits <= 32; bits++) {
			int perLong = 64 / bits;

			if ((256 + perLong - 1) / perLong == longs) {
				return bits;
			}
		}

		return -1;
	}

	/**
	 * Y of the highest non-air block of the column within one section, or {@link #UNKNOWN}.
	 *
	 * @param palette block names of the section's {@code block_states.palette}
	 * @param data    its {@code block_states.data}, {@code null} for a single-entry palette
	 */
	public static int topInSection(int sectionY, List<String> palette, long[] data, int localX, int localZ) {
		if (palette.isEmpty()) {
			return UNKNOWN;
		}

		if (palette.size() == 1 || data == null) {
			return AIR.contains(palette.get(0)) ? UNKNOWN : sectionY * 16 + 15;
		}

		int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		int perLong = 64 / bits;

		for (int y = 15; y >= 0; y--) {
			int index = (y << 8) | (localZ << 4) | localX;
			int word = index / perLong;

			if (word >= data.length) {
				return UNKNOWN;
			}

			int id = (int) ((data[word] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));

			if (id < palette.size() && !AIR.contains(palette.get(id))) {
				return sectionY * 16 + y;
			}
		}

		return UNKNOWN;
	}
}

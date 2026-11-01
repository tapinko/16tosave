package sk.tapusik.sixteentosave.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What the map needs from one chunk (design §7.3): for each of its 256 columns, the top visible
 * block as a map colour, the height, the water depth above the ground, and the biome there.
 * Derived from the cached Anvil data; can always be rebuilt from it.
 *
 * <p>Columns are indexed {@code x + z * 16} (x, z = 0..15 inside the chunk).
 *
 * @param writtenAtSeconds when the chunk data this was built from was written (epoch seconds)
 * @param color            per column: bits 0–5 the vanilla map colour id (0 = nothing visible),
 *                         bits 6–7 the {@link #TINT_NONE tint class}
 * @param height           per column: Y of the top visible block, or {@link #NO_BLOCK}
 * @param waterDepth       per column: water blocks above the ground (0–255, unsigned)
 * @param biome            per column: index into {@code biomePalette}
 * @param biomePalette     biome ids used in this chunk
 * @param incompleteContainers the chunk has containers whose contents were never seen (they
 *                         export empty); marked on the map
 */
public record ChunkSummary(long writtenAtSeconds, byte[] color, short[] height, byte[] waterDepth, byte[] biome,
		List<String> biomePalette, boolean incompleteContainers) {
	public static final int COLUMNS = 256;
	public static final short NO_BLOCK = Short.MIN_VALUE;

	public static final int TINT_NONE = 0;
	public static final int TINT_GRASS = 1;
	public static final int TINT_FOLIAGE = 2;
	public static final int TINT_WATER = 3;

	public ChunkSummary {
		Objects.requireNonNull(biomePalette);

		if (color.length != COLUMNS || height.length != COLUMNS || waterDepth.length != COLUMNS || biome.length != COLUMNS) {
			throw new IllegalArgumentException("a chunk summary needs 256 columns");
		}

		if (biomePalette.size() > 256) {
			throw new IllegalArgumentException("too many biomes in one chunk: " + biomePalette.size());
		}
	}

	public ChunkSummary(long writtenAtSeconds, byte[] color, short[] height, byte[] waterDepth, byte[] biome,
			List<String> biomePalette) {
		this(writtenAtSeconds, color, height, waterDepth, biome, biomePalette, false);
	}

	public static int column(int x, int z) {
		return (x & 15) + (z & 15) * 16;
	}

	public static byte packColor(int mapColorId, int tint) {
		return (byte) ((mapColorId & 0x3F) | ((tint & 3) << 6));
	}

	public static int mapColorId(byte packed) {
		return packed & 0x3F;
	}

	public static int tint(byte packed) {
		return (packed >> 6) & 3;
	}

	public String biomeAt(int column) {
		int i = biome[column] & 0xFF;
		return i < biomePalette.size() ? biomePalette.get(i) : "";
	}

	/** Builds a summary column by column. */
	public static final class Builder {
		private final byte[] color = new byte[COLUMNS];
		private final short[] height = new short[COLUMNS];
		private final byte[] water = new byte[COLUMNS];
		private final byte[] biome = new byte[COLUMNS];
		private final List<String> palette = new ArrayList<>();
		private boolean incompleteContainers;

		public Builder() {
			Arrays.fill(height, NO_BLOCK);
		}

		public Builder set(int column, int mapColorId, int tint, int y, int waterDepth, String biomeId) {
			color[column] = packColor(mapColorId, tint);
			height[column] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
			water[column] = (byte) Math.max(0, Math.min(255, waterDepth));
			int index = palette.indexOf(biomeId);

			if (index < 0) {
				if (palette.size() >= 256) {
					index = 0;
				} else {
					index = palette.size();
					palette.add(biomeId);
				}
			}

			biome[column] = (byte) index;
			return this;
		}

		public Builder incompleteContainers(boolean value) {
			incompleteContainers = value;
			return this;
		}

		public ChunkSummary build(long writtenAtSeconds) {
			return new ChunkSummary(writtenAtSeconds, color.clone(), height.clone(), water.clone(), biome.clone(), List.copyOf(palette),
					incompleteContainers);
		}
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ChunkSummary s
				&& writtenAtSeconds == s.writtenAtSeconds
				&& Arrays.equals(color, s.color)
				&& Arrays.equals(height, s.height)
				&& Arrays.equals(waterDepth, s.waterDepth)
				&& Arrays.equals(biome, s.biome)
				&& biomePalette.equals(s.biomePalette)
				&& incompleteContainers == s.incompleteContainers;
	}

	@Override
	public int hashCode() {
		return Objects.hash(writtenAtSeconds, Arrays.hashCode(color), Arrays.hashCode(height), biomePalette);
	}

	@Override
	public String toString() {
		return "ChunkSummary[writtenAt=" + writtenAtSeconds + ", biomes=" + biomePalette + "]";
	}
}

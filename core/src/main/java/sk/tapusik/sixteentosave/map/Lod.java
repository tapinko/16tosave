package sk.tapusik.sixteentosave.map;

/**
 * Map levels of detail (design §7.2). Every level draws one tile per region file; at
 * {@link #REGIONS} a whole region is a single coloured quad and clicks act on regions.
 */
public enum Lod {
	/** one texel per block, 512×512 per region */
	BLOCKS(1, 512, 0.5),
	/** one texel per 4×4 blocks, 128×128 per region */
	FOUR(4, 128, 0.125),
	/** one texel per chunk, 32×32 per region */
	CHUNKS(16, 32, 1.0 / 32),
	/** one quad per region */
	REGIONS(512, 1, 0);

	/** How far past a switch point the current level is kept, so it doesn't flicker. */
	static final double HYSTERESIS = 0.1;

	public final int blocksPerTexel;
	public final int tileSize;
	/** Smallest zoom (GUI pixels per block) at which this level is used. */
	final double minPixelsPerBlock;

	Lod(int blocksPerTexel, int tileSize, double minPixelsPerBlock) {
		this.blocksPerTexel = blocksPerTexel;
		this.tileSize = tileSize;
		this.minPixelsPerBlock = minPixelsPerBlock;
	}

	/** The level for a zoom without history. */
	public static Lod forScale(double pixelsPerBlock) {
		for (Lod lod : values()) {
			if (pixelsPerBlock >= lod.minPixelsPerBlock) {
				return lod;
			}
		}

		return REGIONS;
	}

	/** The level for a zoom, staying on {@code current} while within {@link #HYSTERESIS} of its range. */
	public static Lod select(double pixelsPerBlock, Lod current) {
		Lod plain = forScale(pixelsPerBlock);

		if (current == null || plain == current) {
			return plain;
		}

		double lower = current.minPixelsPerBlock * (1 - HYSTERESIS);
		double upper = current.ordinal() == 0 ? Double.MAX_VALUE : values()[current.ordinal() - 1].minPixelsPerBlock * (1 + HYSTERESIS);
		return pixelsPerBlock >= lower && pixelsPerBlock < upper ? current : plain;
	}

	/** One step coarser, or this level if it is already the coarsest. */
	public Lod coarser() {
		return this == REGIONS ? REGIONS : values()[ordinal() + 1];
	}
}

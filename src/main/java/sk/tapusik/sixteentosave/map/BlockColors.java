package sk.tapusik.sixteentosave.map;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Block state → vanilla map colour and tint class, cached. Thread-safe: used by the writer thread
 * and the tile workers.
 */
public final class BlockColors {
	private static final Map<BlockState, Byte> CACHE = new ConcurrentHashMap<>();

	private BlockColors() {
	}

	/** Packed {@link ChunkSummary} colour byte; map colour id 0 means "invisible on maps" (glass, air…). */
	public static byte packed(BlockState state) {
		Byte cached = CACHE.get(state);

		if (cached != null) {
			return cached;
		}

		byte value = compute(state);
		CACHE.put(state, value);
		return value;
	}

	private static byte compute(BlockState state) {
		MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

		if (color == null || color == MapColor.NONE) {
			return ChunkSummary.packColor(0, ChunkSummary.TINT_NONE);
		}

		int tint = ChunkSummary.TINT_NONE;

		if (color == MapColor.WATER) {
			tint = ChunkSummary.TINT_WATER;
		} else if (color == MapColor.GRASS) {
			tint = ChunkSummary.TINT_GRASS;
		} else if (state.is(BlockTags.LEAVES)) {
			tint = ChunkSummary.TINT_FOLIAGE;
		}

		return ChunkSummary.packColor(color.id, tint);
	}

	/** {@code 0xRRGGBB} of a vanilla map colour id, or -1 for "nothing". */
	public static int rgb(int mapColorId) {
		if (mapColorId <= 0) {
			return -1;
		}

		MapColor color = MapColor.byId(mapColorId);
		return color == null || color == MapColor.NONE ? -1 : color.col & 0xFFFFFF;
	}
}

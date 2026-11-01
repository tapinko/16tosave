package sk.tapusik.sixteentosave.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import sk.tapusik.sixteentosave.storage.ChunkCodecs;
import sk.tapusik.sixteentosave.storage.ChunkTagAssembler;

/**
 * Reads a cached chunk tag and finds, for every column, the top block a vanilla map would show:
 * the highest block at or below {@code clipY} whose map colour is not "none". Water is followed
 * down to measure its depth. Sections are decoded top-down and only as far as needed.
 * Thread-safe.
 */
public final class ChunkSummarizer {
	/** No height limit. */
	public static final int NO_CLIP = Integer.MAX_VALUE;
	private static final int MAX_WATER_DEPTH = 255;

	private ChunkSummarizer() {
	}

	private record Section(int y, CompoundTag tag) {
	}

	public static ChunkSummary summarize(CompoundTag chunk, int clipY, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
			long writtenAtSeconds) {
		List<Section> sections = new ArrayList<>();
		chunk.getListOrEmpty("sections").compoundStream().forEach(s -> sections.add(new Section(s.getByteOr("Y", (byte) 0), s)));
		sections.sort(Comparator.comparingInt(Section::y).reversed());

		ChunkSummary.Builder out = new ChunkSummary.Builder();
		// per column: 0 = still searching, 1 = found water, counting depth, 2 = done
		byte[] phase = new byte[ChunkSummary.COLUMNS];
		int[] topY = new int[ChunkSummary.COLUMNS];
		byte[] topColor = new byte[ChunkSummary.COLUMNS];
		int[] depth = new int[ChunkSummary.COLUMNS];
		String[] topBiome = new String[ChunkSummary.COLUMNS];
		int done = 0;

		for (Section section : sections) {
			if (done == ChunkSummary.COLUMNS) {
				break;
			}

			int minY = section.y() * 16;

			if (minY > clipY) {
				continue;
			}

			PalettedContainer<BlockState> states = blocks(section.tag());

			if (states == null || !states.maybeHas(state -> !state.isAir())) {
				continue;
			}

			PalettedContainerRO<Holder<Biome>> biomes = null;
			int startY = (int) Math.min(15, (long) clipY - minY);

			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int column = ChunkSummary.column(x, z);

					if (phase[column] == 2) {
						continue;
					}

					for (int y = startY; y >= 0; y--) {
						BlockState state = states.get(x, y, z);

						if (state.isAir()) {
							if (phase[column] == 1) {
								phase[column] = 2; // water ends above air (e.g. an air pocket): stop counting
								done++;
								break;
							}

							continue;
						}

						boolean water = state.getFluidState().is(FluidTags.WATER);

						if (phase[column] == 1) {
							if (water && depth[column] < MAX_WATER_DEPTH) {
								depth[column]++;
								continue;
							}

							phase[column] = 2;
							done++;
							break;
						}

						byte packed = BlockColors.packed(state);

						if (ChunkSummary.mapColorId(packed) == 0) {
							continue; // invisible on maps (glass, barrier…): look further down
						}

						if (biomes == null) {
							biomes = biomes(section.tag(), biomeCodec);
						}

						topY[column] = minY + y;
						topBiome[column] = biomes == null ? "" : biomes.get(x >> 2, y >> 2, z >> 2).getRegisteredName();

						if (water) {
							topColor[column] = ChunkSummary.packColor(ChunkSummary.mapColorId(packed), ChunkSummary.TINT_WATER);
							depth[column] = 1;
							phase[column] = 1;
							continue;
						}

						topColor[column] = packed;
						phase[column] = 2;
						done++;
						break;
					}
				}
			}
		}

		for (int column = 0; column < ChunkSummary.COLUMNS; column++) {
			if (phase[column] != 0) {
				out.set(column, ChunkSummary.mapColorId(topColor[column]), ChunkSummary.tint(topColor[column]), topY[column],
						depth[column], topBiome[column] == null ? "" : topBiome[column]);
			}
		}

		// written by capture: containers in this chunk whose contents the client never saw
		boolean incomplete = chunk.getCompoundOrEmpty(ChunkTagAssembler.META_KEY).getLongArray("incomplete")
				.map(positions -> positions.length > 0).orElse(false);
		return out.incompleteContainers(incomplete).build(writtenAtSeconds);
	}

	private static PalettedContainer<BlockState> blocks(CompoundTag section) {
		return section.getCompound("block_states")
				.flatMap(tag -> ChunkCodecs.BLOCKS.parse(NbtOps.INSTANCE, tag).promotePartial(error -> { }).result())
				.orElse(null);
	}

	private static PalettedContainerRO<Holder<Biome>> biomes(CompoundTag section, Codec<PalettedContainerRO<Holder<Biome>>> codec) {
		return section.getCompound("biomes")
				.flatMap(tag -> codec.parse(NbtOps.INSTANCE, tag).promotePartial(error -> { }).result())
				.orElse(null);
	}
}

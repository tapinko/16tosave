package sk.tapusik.sixteentosave.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.ContentTracker;
import sk.tapusik.sixteentosave.capture.EntityMerger;
import sk.tapusik.sixteentosave.capture.UuidCodec;

/**
 * Builds Anvil chunk and entity-chunk tags from snapshots, using vanilla codecs for the palettes
 * (design §5.3). Light is left out ({@code isLightOn: false}): the game relights on load.
 */
public final class ChunkTagAssembler {
	/** Mod-private data inside each cached chunk tag; removed on export. */
	public static final String META_KEY = SixteenToSave.NAME + ":meta";

	private ChunkTagAssembler() {
	}

	/**
	 * Encodes every section (air sections too: they still carry biomes). A section that fails to
	 * encode is left out and logged; the chunk is still written.
	 */
	public static ListTag encodeSections(DimensionStore store, int minSectionY, LevelChunkSection[] sections) {
		ListTag list = new ListTag();

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];

			if (section == null) {
				continue;
			}

			try {
				CompoundTag tag = new CompoundTag();
				tag.putByte("Y", (byte) (minSectionY + i));
				tag.put("block_states", ChunkCodecs.BLOCKS.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow());
				tag.put("biomes", store.biomeCodec().encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow());
				list.add(tag);
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.debug("Skipping section {} of a chunk in {}: {}", minSectionY + i, store, e.toString());
			}
		}

		return list;
	}

	/** Whether building the tag needs the previously cached tag (to carry over container contents). */
	static boolean needsPrevious(ChunkSnapshot s) {
		if (s.contentPositions().length == 0) {
			return false;
		}

		Set<Long> now = new HashSet<>();

		for (long p : s.observedNow()) {
			now.add(p);
		}

		for (long p : s.contentPositions()) {
			if (!now.contains(p)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * @param previous the cached tag of the same chunk, or {@code null}
	 */
	static CompoundTag chunkTag(ChunkSnapshot s, ListTag sections, CompoundTag previous) {
		CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
		tag.putInt("xPos", s.chunkX());
		tag.putInt("yPos", s.minSectionY());
		tag.putInt("zPos", s.chunkZ());
		tag.putString("Status", "minecraft:full");
		tag.putBoolean("isLightOn", false);
		tag.put("sections", sections);
		tag.put("Heightmaps", s.heightmaps());

		Map<Long, CompoundTag> previousBlockEntities = previous == null ? Map.of() : blockEntitiesByPos(previous);
		long[] previouslyObserved = previous == null ? new long[0] : previous.getCompoundOrEmpty(META_KEY)
				.getLongArray("observed").orElse(new long[0]);
		Map<Long, CompoundTag> current = blockEntitiesByPos(s.blockEntities());

		ContentTracker.Resolution content = ContentTracker.resolve(
				s.contentPositions(),
				s.observedNow(),
				previouslyObserved,
				pos -> {
					CompoundTag before = previousBlockEntities.get(pos);
					CompoundTag now = current.get(pos);
					return before != null && now != null && before.getStringOr("id", "").equals(now.getStringOr("id", ""));
				});

		for (long pos : content.carryOver()) {
			CompoundTag now = current.get(pos);
			CompoundTag before = previousBlockEntities.get(pos);

			if (now == null || before == null) {
				continue;
			}

			// Keys the client doesn't know now (e.g. "Items") are taken from the earlier observation.
			for (String key : before.keySet()) {
				if (!now.contains(key)) {
					now.put(key, before.get(key).copy());
				}
			}
		}

		tag.put("block_entities", s.blockEntities());

		CompoundTag meta = new CompoundTag();
		meta.putInt("format", 1);
		meta.putLong("capturedAt", s.capturedAtMillis());
		meta.put("observed", new LongArrayTag(content.observed()));
		meta.put("incomplete", new LongArrayTag(content.incomplete()));
		tag.put(META_KEY, meta);
		return tag;
	}

	private static Map<Long, CompoundTag> blockEntitiesByPos(CompoundTag chunk) {
		return blockEntitiesByPos(chunk.getListOrEmpty("block_entities"));
	}

	private static Map<Long, CompoundTag> blockEntitiesByPos(ListTag list) {
		Map<Long, CompoundTag> out = new HashMap<>();

		list.compoundStream().forEach(be -> out.put(
				BlockPos.asLong(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0)), be));

		return out;
	}

	static List<EntityMerger.Entry<CompoundTag>> readEntities(CompoundTag entityChunk) {
		List<EntityMerger.Entry<CompoundTag>> out = new ArrayList<>();

		if (entityChunk == null) {
			return out;
		}

		entityChunk.getListOrEmpty("Entities").compoundStream().forEach(e -> out.add(new EntityMerger.Entry<>(
				e.getIntArray("UUID").map(UuidCodec::fromIntArray).orElse(""),
				e.getStringOr("id", ""),
				e)));

		return out;
	}

	static CompoundTag entitiesTag(ChunkPos pos, List<EntityMerger.Entry<CompoundTag>> entities) {
		CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
		ListTag list = new ListTag();

		for (EntityMerger.Entry<CompoundTag> e : entities) {
			list.add(e.data());
		}

		tag.put("Entities", list);
		tag.store("Position", ChunkPos.CODEC, pos);
		return tag;
	}
}

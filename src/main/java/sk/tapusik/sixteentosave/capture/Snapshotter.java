package sk.tapusik.sixteentosave.capture;

import java.util.Map;
import java.util.stream.LongStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.storage.ChunkSnapshot;
import sk.tapusik.sixteentosave.storage.ChunkTagAssembler;
import sk.tapusik.sixteentosave.storage.DimensionStore;

/** Takes chunk snapshots on the client thread (design §5.2). */
final class Snapshotter {
	private static final long BASE_BYTES = 2048;
	private static final long BYTES_PER_SECTION = 6144;
	private static final long BYTES_PER_TAG = 1024;

	private Snapshotter() {
	}

	/**
	 * @param isFinal the chunk is being dropped: keep its section objects and encode them later
	 *                off-thread; otherwise the sections are encoded now
	 */
	static ChunkSnapshot take(DimensionStore store, LevelChunk chunk, boolean isFinal, long[] observedNow,
			ChunkSnapshot.EntityCapture entities, long nowMillis) {
		RegistryAccess registries = chunk.getLevel().registryAccess();
		LevelChunkSection[] sections = chunk.getSections().clone();
		int minSectionY = chunk.getMinSectionY();
		ListTag encoded = isFinal ? null : ChunkTagAssembler.encodeSections(store, minSectionY, sections);

		ListTag blockEntities = new ListTag();
		LongStream.Builder contentPositions = LongStream.builder();

		for (BlockPos pos : chunk.getBlockEntitiesPos()) {
			CompoundTag tag;

			try {
				tag = chunk.getBlockEntityNbtForSaving(pos, registries);
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.debug("Could not save block entity at {}: {}", pos, e.toString());
				continue;
			}

			if (tag == null) {
				continue;
			}

			blockEntities.add(tag);

			if (ContentTracker.hasServerOnlyContents(tag.getStringOr("id", ""))) {
				contentPositions.add(pos.asLong());
			}
		}

		CompoundTag heightmaps = new CompoundTag();

		for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
			if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
				heightmaps.put(entry.getKey().getSerializationKey(), new LongArrayTag(entry.getValue().getRawData().clone()));
			}
		}

		long nonEmptySections = 0;

		for (LevelChunkSection section : sections) {
			if (section != null && !section.hasOnlyAir()) {
				nonEmptySections++;
			}
		}

		long entityCount = entities == null ? 0 : entities.list().size();
		long estimate = BASE_BYTES + nonEmptySections * BYTES_PER_SECTION + (blockEntities.size() + entityCount) * BYTES_PER_TAG;

		return new ChunkSnapshot(
				store,
				chunk.getPos().x(),
				chunk.getPos().z(),
				minSectionY,
				isFinal ? sections : null,
				encoded,
				blockEntities,
				heightmaps,
				contentPositions.build().toArray(),
				observedNow,
				entities,
				nowMillis,
				isFinal,
				estimate);
	}

	/** Rough size of a live snapshot before taking it, for the queue-room check. */
	static long typicalLiveEstimate() {
		return BASE_BYTES + 8 * BYTES_PER_SECTION;
	}
}

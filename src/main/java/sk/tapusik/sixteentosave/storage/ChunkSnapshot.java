package sk.tapusik.sixteentosave.storage;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.LevelChunkSection;

import sk.tapusik.sixteentosave.capture.EntityMerger;

/**
 * Everything the writer thread needs to write one chunk, taken on the client thread.
 *
 * <p>Sections come in one of two forms. An unload ("final") snapshot holds the chunk's own section
 * objects ({@code detachedSections}): the chunk has been dropped, nothing changes them any more,
 * and encoding them later off-thread is safe. A live snapshot of a chunk that stays loaded
 * encodes its sections right away on the client thread ({@code encodedSections}).
 *
 * @param contentPositions packed positions of block entities whose contents are server-side only
 * @param observedNow      packed positions whose contents were observed in this session
 * @param entities         entities to store, or {@code null} to leave the entity record alone
 */
public record ChunkSnapshot(
		DimensionStore store,
		int chunkX,
		int chunkZ,
		int minSectionY,
		LevelChunkSection[] detachedSections,
		ListTag encodedSections,
		ListTag blockEntities,
		CompoundTag heightmaps,
		long[] contentPositions,
		long[] observedNow,
		EntityCapture entities,
		long capturedAtMillis,
		boolean isFinal,
		long estimatedBytes) {
	/**
	 * @param authoritative the whole chunk was within entity tracking range: replace, don't merge
	 */
	public record EntityCapture(List<EntityMerger.Entry<CompoundTag>> list, boolean authoritative) {
	}

	public long chunkKey() {
		return ChunkKey.pack(chunkX, chunkZ);
	}
}

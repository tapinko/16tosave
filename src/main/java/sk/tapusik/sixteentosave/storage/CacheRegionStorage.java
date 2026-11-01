package sk.tapusik.sixteentosave.storage;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * Vanilla Anvil storage for one folder of {@code r.X.Z.mca} files, with vanilla's own I/O thread
 * ({@link IOWorker}) doing compression and disk writes. Same construction as Bobby on 26.3.
 */
final class CacheRegionStorage extends SimpleRegionStorage {
	CacheRegionStorage(RegionStorageInfo info, Path folder, DataFixTypes type) {
		super(info, folder, Minecraft.getInstance().getFixerUpper(), false, type);
	}

	/** Queues a write; {@code tag == null} deletes the chunk from its region file. */
	CompletableFuture<?> store(ChunkPos pos, CompoundTag tag) {
		return ((IOWorker) chunkScanner()).store(pos, tag);
	}

	CompletableFuture<Optional<CompoundTag>> load(ChunkPos pos) {
		return read(pos);
	}
}

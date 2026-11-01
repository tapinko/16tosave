package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import sk.tapusik.sixteentosave.SixteenToSave;

/**
 * The cache of one dimension on one server: chunk and entity region storages plus the last-seen
 * index. Created on the client thread; the storages are opened, used and closed only on the
 * writer thread.
 */
public final class DimensionStore {
	private final String serverId;
	private final String dimensionId;
	private final ResourceKey<Level> dimension;
	private final Path dir;
	private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;

	/** Work items queued for this store and not yet processed (any thread). */
	final AtomicInteger pending = new AtomicInteger();
	/** Regions written during this session; never evicted at the end of it (any thread). */
	final Set<Long> touchedRegions = ConcurrentHashMap.newKeySet();

	// writer thread only
	private CacheRegionStorage chunks;
	private CacheRegionStorage entities;
	private RegionLastSeen lastSeen;

	public DimensionStore(String serverId, ResourceKey<Level> dimension, Path dir, RegistryAccess registries) {
		this.serverId = serverId;
		this.dimension = dimension;
		this.dimensionId = dimension.identifier().toString();
		this.dir = dir;
		this.biomeCodec = ChunkCodecs.biomes(registries);
	}

	public String serverId() {
		return serverId;
	}

	public String dimensionId() {
		return dimensionId;
	}

	public Path dir() {
		return dir;
	}

	public Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec() {
		return biomeCodec;
	}

	boolean isOpen() {
		return chunks != null;
	}

	void ensureOpen() {
		if (chunks == null) {
			chunks = new CacheRegionStorage(new RegionStorageInfo(SixteenToSave.NAME, dimension, "chunk"),
					dir.resolve(CacheLayout.REGION_FOLDER), DataFixTypes.CHUNK);
			entities = new CacheRegionStorage(new RegionStorageInfo(SixteenToSave.NAME, dimension, "entities"),
					dir.resolve(CacheLayout.ENTITIES_FOLDER), DataFixTypes.ENTITY_CHUNK);
			lastSeen = RegionLastSeen.load(dir);
		}
	}

	CacheRegionStorage chunks() {
		ensureOpen();
		return chunks;
	}

	CacheRegionStorage entities() {
		ensureOpen();
		return entities;
	}

	void touch(int regionX, int regionZ, long millis) {
		ensureOpen();
		lastSeen.touch(regionX, regionZ, millis);
		touchedRegions.add(ChunkKey.pack(regionX, regionZ));
	}

	/** Flushes and closes the storages (waits for vanilla's I/O thread) and saves the last-seen index. */
	void close() {
		if (chunks == null) {
			return;
		}

		closeQuietly(chunks);
		closeQuietly(entities);

		try {
			lastSeen.save(dir);
		} catch (IOException e) {
			SixteenToSave.LOGGER.warn("Could not save {} in {}: {}", RegionLastSeen.FILE_NAME, dir, e.toString());
		}

		chunks = null;
		entities = null;
		lastSeen = null;
	}

	private void closeQuietly(CacheRegionStorage storage) {
		try {
			storage.close();
		} catch (IOException | RuntimeException e) {
			SixteenToSave.LOGGER.warn("Could not close cache storage in {}: {}", dir, e.toString());
		}
	}

	@Override
	public String toString() {
		return serverId + "/" + dimensionId;
	}
}

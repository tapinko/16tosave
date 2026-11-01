package sk.tapusik.sixteentosave.map;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.nbt.CompoundTag;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.storage.CacheWriter;
import sk.tapusik.sixteentosave.storage.DimensionStore;
import sk.tapusik.sixteentosave.storage.RateLimitedLog;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * The derived summary index (design §7.3, Q2): {@code <dimension>/map/r.X.Z.cps}. Updated on the
 * writer thread every time a chunk is written, so it never lags behind the cache; files are
 * written in batches. Everything here runs on the writer thread except listener registration.
 */
public final class MapIndex implements CacheWriter.WriteHook {
	public static final String FOLDER = "map";
	private static final long FLUSH_INTERVAL_NANOS = 15_000_000_000L;
	private static final int MAX_LOADED_REGIONS = 32;

	/** Told about every new chunk summary (on the writer thread; keep it cheap). */
	@FunctionalInterface
	public interface Listener {
		void onSummary(Path dimensionDir, int chunkX, int chunkZ, ChunkSummary summary);
	}

	private record Key(Path dimensionDir, int regionX, int regionZ) {
	}

	private final LinkedHashMap<Key, RegionSummary> loaded = new LinkedHashMap<>(16, 0.75f, true);
	private final Set<Key> dirty = new HashSet<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private long lastFlush = System.nanoTime();

	public static Path file(Path dimensionDir, int regionX, int regionZ) {
		return dimensionDir.resolve(FOLDER).resolve(RegionSummaryCodec.fileName(regionX, regionZ));
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	@Override
	public void onChunkWritten(DimensionStore store, int chunkX, int chunkZ, CompoundTag tag) {
		try {
			ChunkSummary summary = ChunkSummarizer.summarize(tag, ChunkSummarizer.NO_CLIP, store.biomeCodec(),
					System.currentTimeMillis() / 1000);
			put(store.dir(), chunkX, chunkZ, summary);

			for (Listener listener : listeners) {
				listener.onSummary(store.dir(), chunkX, chunkZ, summary);
			}
		} catch (RuntimeException e) {
			RateLimitedLog.warn("summary", "{}: could not summarise chunk {},{} for the map: {}", SixteenToSave.NAME, chunkX, chunkZ, e.toString());
		}
	}

	/** Adds summaries the map workers rebuilt from region files; never replaces a newer one. */
	public void putRebuilt(Path dimensionDir, int regionX, int regionZ, Map<Integer, ChunkSummary> summaries) {
		Key key = new Key(dimensionDir, regionX, regionZ);
		RegionSummary region = region(key);
		boolean changed = false;

		for (Map.Entry<Integer, ChunkSummary> e : summaries.entrySet()) {
			ChunkSummary existing = region.get(e.getKey());

			if (existing == null || existing.writtenAtSeconds() < e.getValue().writtenAtSeconds()) {
				region.put(e.getKey(), e.getValue());
				changed = true;
			}
		}

		if (changed) {
			dirty.add(key);
		}

		trim();
	}

	private void put(Path dimensionDir, int chunkX, int chunkZ, ChunkSummary summary) {
		Key key = new Key(dimensionDir, chunkX >> 5, chunkZ >> 5);
		region(key).put(RegionHeader.localIndex(chunkX, chunkZ), summary);
		dirty.add(key);
		trim();
	}

	private RegionSummary region(Key key) {
		return loaded.computeIfAbsent(key, k -> RegionSummaryCodec.load(file(k.dimensionDir, k.regionX, k.regionZ), k.regionX, k.regionZ));
	}

	private void trim() {
		Iterator<Map.Entry<Key, RegionSummary>> it = loaded.entrySet().iterator();

		while (loaded.size() > MAX_LOADED_REGIONS && it.hasNext()) {
			Map.Entry<Key, RegionSummary> eldest = it.next();
			flush(eldest.getKey(), eldest.getValue());
			it.remove();
		}
	}

	private void flush(Key key, RegionSummary region) {
		if (!dirty.remove(key)) {
			return;
		}

		try {
			RegionSummaryCodec.save(file(key.dimensionDir, key.regionX, key.regionZ), region);
		} catch (IOException e) {
			RateLimitedLog.warn("summary-save", "{}: could not save map index {}: {}", SixteenToSave.NAME, key, e.toString());
		}
	}

	private void flushAll() {
		for (Map.Entry<Key, RegionSummary> e : loaded.entrySet()) {
			flush(e.getKey(), e.getValue());
		}

		lastFlush = System.nanoTime();
	}

	@Override
	public void onIdle() {
		if (!dirty.isEmpty() && System.nanoTime() - lastFlush > FLUSH_INTERVAL_NANOS) {
			flushAll();
		}
	}

	/** Writes everything pending now (e.g. when the map opens, so it reads current files). */
	public void flushNow() {
		flushAll();
	}

	@Override
	public void onStoreClosed(DimensionStore store) {
		Iterator<Map.Entry<Key, RegionSummary>> it = loaded.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Key, RegionSummary> e = it.next();

			if (e.getKey().dimensionDir.equals(store.dir())) {
				flush(e.getKey(), e.getValue());
				it.remove();
			}
		}
	}

	@Override
	public void onServerCleared(Path serverDir) {
		Iterator<Map.Entry<Key, RegionSummary>> it = loaded.entrySet().iterator();

		while (it.hasNext()) {
			Key key = it.next().getKey();

			if (key.dimensionDir.startsWith(serverDir)) {
				dirty.remove(key);
				it.remove();
			}
		}
	}
}

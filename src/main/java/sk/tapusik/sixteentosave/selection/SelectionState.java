package sk.tapusik.sixteentosave.selection;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * The map's selection for one dimension (design §8.1): the selection, the "universe" of cached
 * chunks with their region headers (for the size estimate), undo, and saving one second after
 * the last change. Render thread only; files are read and written by {@link SelectionFiles}.
 */
public final class SelectionState {
	private static final long SAVE_DELAY_NANOS = 1_000_000_000L;
	private static final int UNDO_LIMIT = 32;

	private final Path dimensionDir;
	private final String dimensionId;
	private ChunkSelection selection = new ChunkSelection();
	private final Deque<ChunkSelection> undo = new ArrayDeque<>();
	private ChunkSelection universe;
	private final Map<Long, RegionHeader> chunkHeaders = new HashMap<>();
	private final Map<Long, RegionHeader> entityHeaders = new HashMap<>();
	private final List<Long> writtenDuringScan = new ArrayList<>();
	private CompletableFuture<ChunkSelection> loading;
	private CompletableFuture<CacheHeaders> scanning;
	private boolean edited;
	private boolean dirty;
	private long changedAt;
	private long estimate;
	private int cachedCount;
	private boolean estimateStale = true;

	public SelectionState(Path dimensionDir, String dimensionId) {
		this.dimensionDir = dimensionDir;
		this.dimensionId = dimensionId;
		this.loading = SelectionFiles.loadCurrent(dimensionDir);
		rescan();
	}

	public Path dimensionDir() {
		return dimensionDir;
	}

	public String dimensionId() {
		return dimensionId;
	}

	/** Re-reads the region headers, e.g. when the map comes back (the cache may have been cleared meanwhile). */
	public void rescan() {
		Path region = dimensionDir.resolve(CacheLayout.REGION_FOLDER);
		Path entities = dimensionDir.resolve(CacheLayout.ENTITIES_FOLDER);
		writtenDuringScan.clear();
		scanning = CompletableFuture.supplyAsync(() -> CacheHeaders.load(region, entities));
	}

	/** Call once per frame: takes finished background work, saves, updates the estimate. */
	public void tick() {
		if (loading != null && loading.isDone()) {
			ChunkSelection loaded = result(loading);
			loading = null;

			// a change made in the few milliseconds before the file was read wins
			if (loaded != null && !edited) {
				selection = loaded;
				estimateStale = true;
			}
		}

		if (scanning != null && scanning.isDone()) {
			CacheHeaders headers = result(scanning);
			scanning = null;

			if (headers != null) {
				chunkHeaders.clear();
				chunkHeaders.putAll(headers.chunks());
				entityHeaders.clear();
				entityHeaders.putAll(headers.entities());
				universe = headers.universe();

				for (long chunk : writtenDuringScan) {
					universe.set(ChunkKey.x(chunk), ChunkKey.z(chunk), true);
				}

				estimateStale = true;
			}

			writtenDuringScan.clear();
		}

		if (dirty && System.nanoTime() - changedAt > SAVE_DELAY_NANOS) {
			save();
		}

		if (estimateStale) {
			estimate = SelectionStats.estimateBytes(selection, chunkHeaders, entityHeaders);
			cachedCount = universe == null ? selection.count() : selection.countWithin(universe);
			estimateStale = false;
		}
	}

	private static <T> T result(CompletableFuture<T> future) {
		try {
			return future.getNow(null);
		} catch (CompletionException | CancellationException e) {
			return null;
		}
	}

	/** The selection; change it only through {@link #edit}. */
	public ChunkSelection selection() {
		return selection;
	}

	/** Chunks present in the cache, or {@code null} while the region headers are still being read. */
	public ChunkSelection universe() {
		return universe;
	}

	/** Selected chunks that are in the cache (a loaded selection may name chunks that aren't). */
	public int cachedCount() {
		return cachedCount;
	}

	/** Bytes the selected chunks take in the cache (≈ export size). */
	public long estimateBytes() {
		return estimate;
	}

	/** Applies a change; returns whether anything changed (only then is there an undo step). */
	public boolean edit(Consumer<ChunkSelection> change) {
		ChunkSelection before = selection.copy();
		change.accept(selection);

		if (before.equals(selection)) {
			return false;
		}

		undo.push(before);

		while (undo.size() > UNDO_LIMIT) {
			undo.removeLast();
		}

		changed();
		return true;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean undo() {
		if (undo.isEmpty()) {
			return false;
		}

		selection = undo.pop();
		changed();
		return true;
	}

	private void changed() {
		edited = true;
		dirty = true;
		changedAt = System.nanoTime();
		estimateStale = true;
	}

	/**
	 * A map tile was rebuilt and read the region's headers again. {@code null} (no file, or it
	 * couldn't be read just now) keeps what is known; files don't disappear while the map is open.
	 */
	public void regionScanned(int regionX, int regionZ, RegionHeader chunks, RegionHeader entities) {
		long key = ChunkKey.pack(regionX, regionZ);

		if (chunks != null) {
			chunkHeaders.put(key, chunks);

			if (universe != null) {
				// union: a chunk written after the header was read is already in the universe
				universe.addRegionBits(regionX, regionZ, SelectionStats.presentBits(chunks));
			}
		}

		if (entities != null) {
			entityHeaders.put(key, entities);
		}

		estimateStale |= chunks != null || entities != null;
	}

	/** A chunk was just written to the cache. */
	public void chunkWritten(int chunkX, int chunkZ) {
		if (universe != null) {
			universe.set(chunkX, chunkZ, true);
		}

		estimateStale |= selection.contains(chunkX, chunkZ);

		if (scanning != null) {
			writtenDuringScan.add(ChunkKey.pack(chunkX, chunkZ));
		}
	}

	/** Saves now if there are unsaved changes (the map closes or switches dimension). */
	public void flush() {
		if (dirty) {
			save();
		}
	}

	private void save() {
		dirty = false;
		SelectionFiles.saveCurrent(dimensionDir, dimensionId, selection.copy());
	}
}

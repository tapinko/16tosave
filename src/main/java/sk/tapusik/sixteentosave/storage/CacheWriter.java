package sk.tapusik.sixteentosave.storage;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.ByteBoundedCoalescingQueue;
import sk.tapusik.sixteentosave.capture.ByteBoundedCoalescingQueue.OfferResult;
import sk.tapusik.sixteentosave.capture.EntityMerger;
import sk.tapusik.sixteentosave.selection.SelectionFiles;

/**
 * The writer thread (design §5.6): turns snapshots into NBT and hands them to vanilla's
 * {@code IOWorker}. The client thread only ever calls the non-blocking methods of this class.
 */
public final class CacheWriter {
	/** Writes handed to IOWorker and not finished yet; beyond this the writer waits, the queue fills. */
	private static final int MAX_IN_FLIGHT = 256;
	private static final long STUCK_PENDING_NANOS = 5_000_000_000L;

	public enum SubmitResult {
		ACCEPTED,
		/** queue full; the snapshot was not taken in (live snapshots: retry later) */
		BUSY
	}

	public record Stats(int queued, long queuedBytes, long chunksWritten, long entityChunksWritten, long dropped, long failed) {
	}

	/** Called on the writer thread; used by the map's summary index. */
	public interface WriteHook {
		/** A chunk tag was handed to the I/O thread for {@code store}. */
		void onChunkWritten(DimensionStore store, int chunkX, int chunkZ, CompoundTag tag);

		/** The store was closed; flush anything derived from it. */
		void onStoreClosed(DimensionStore store);

		/** The server's cache folder is about to be deleted; forget it, don't write it back. */
		void onServerCleared(Path serverDir);

		/** Nothing to do right now; a good moment for batched writes. */
		void onIdle();
	}

	private static final WriteHook NO_HOOK = new WriteHook() {
		@Override
		public void onChunkWritten(DimensionStore store, int chunkX, int chunkZ, CompoundTag tag) {
		}

		@Override
		public void onStoreClosed(DimensionStore store) {
		}

		@Override
		public void onServerCleared(Path serverDir) {
		}

		@Override
		public void onIdle() {
		}
	};

	private sealed interface Work permits ChunkWork, PatchWork {
		DimensionStore store();
	}

	private record ChunkWork(ChunkSnapshot snapshot) implements Work {
		@Override
		public DimensionStore store() {
			return snapshot.store();
		}
	}

	/** Removes tombstoned entities from a chunk that is not loaded (and so gets no snapshot). */
	private record PatchWork(DimensionStore store, long chunkKey) implements Work {
	}

	private record Key(DimensionStore store, long chunkKey, boolean patch) {
	}

	private final ByteBoundedCoalescingQueue<Key, Work> queue;
	private final ConcurrentLinkedQueue<Runnable> control = new ConcurrentLinkedQueue<>();
	private final Map<Key, Set<String>> tombstones = new ConcurrentHashMap<>();
	private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
	private final LongSupplier capBytes;
	private final WriteHook hook;
	private final Thread thread;
	private volatile boolean running = true;

	// writer thread only
	private final Set<DimensionStore> openStores = new HashSet<>();

	private final LongAdder chunksWritten = new LongAdder();
	private final LongAdder entityChunksWritten = new LongAdder();
	private final LongAdder dropped = new LongAdder();
	private final LongAdder failed = new LongAdder();

	/**
	 * @param capBytes current per-server cache size cap (read when a session closes)
	 */
	public CacheWriter(long queueCapacityBytes, LongSupplier capBytes, WriteHook hook) {
		this.capBytes = capBytes;
		this.hook = hook == null ? NO_HOOK : hook;
		this.queue = new ByteBoundedCoalescingQueue<>(queueCapacityBytes, evicted -> evicted.store().pending.decrementAndGet());
		this.thread = new Thread(this::run, SixteenToSave.NAME + "-writer");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.NORM_PRIORITY - 1);
		this.thread.start();
	}

	// ---- client-thread API (never blocks) -------------------------------------------------

	public boolean hasRoomFor(long bytes) {
		return queue.hasRoomFor(bytes);
	}

	public SubmitResult submit(ChunkSnapshot snapshot) {
		Key key = new Key(snapshot.store(), snapshot.chunkKey(), false);
		OfferResult result = queue.offer(key, new ChunkWork(snapshot), snapshot.estimatedBytes(), !snapshot.isFinal());

		switch (result) {
			case ACCEPTED -> snapshot.store().pending.incrementAndGet();
			case REPLACED -> {
			}
			case REJECTED -> {
				if (snapshot.isFinal()) {
					dropped.increment();
					RateLimitedLog.warn("dropped", "{}: capture queue full, a chunk at {},{} in {} was not saved",
							SixteenToSave.NAME, snapshot.chunkX(), snapshot.chunkZ(), snapshot.store());
				}

				return SubmitResult.BUSY;
			}
		}

		return SubmitResult.ACCEPTED;
	}

	/**
	 * Marks entities as gone from a chunk's stored record. They are applied with the chunk's next
	 * snapshot; with {@code chunkLoaded == false} (no snapshot coming) a small patch job is queued.
	 */
	public void addTombstones(DimensionStore store, long chunkKey, Collection<String> uuids, boolean chunkLoaded) {
		if (uuids.isEmpty()) {
			return;
		}

		Key key = new Key(store, chunkKey, true);
		tombstones.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(uuids);

		if (!chunkLoaded && queue.offer(key, new PatchWork(store, chunkKey), 256, false) == OfferResult.ACCEPTED) {
			store.pending.incrementAndGet();
		}
	}

	/** Closes the stores once everything queued for them is written, then runs {@code then} on the writer thread. */
	public void closeWhenDrained(Collection<DimensionStore> stores, Runnable then) {
		List<DimensionStore> list = List.copyOf(stores);
		control.add(new Runnable() {
			private long waitingSince = -1;

			@Override
			public void run() {
				boolean busy = list.stream().anyMatch(s -> s.pending.get() > 0);

				if (busy && queue.size() == 0) {
					// Nothing left to process yet pending > 0: an accounting slip. Don't hang forever.
					long now = System.nanoTime();

					if (waitingSince < 0) {
						waitingSince = now;
					} else if (now - waitingSince > STUCK_PENDING_NANOS) {
						list.forEach(s -> s.pending.set(0));
						busy = false;
					}
				}

				if (busy) {
					control.add(this);
					return;
				}

				for (DimensionStore s : list) {
					s.close();
					openStores.remove(s);
					hook.onStoreClosed(s);
				}

				then.run();
			}
		});
	}

	/**
	 * Closes a session's stores, then evicts least recently seen regions if the server is over the
	 * cap. Regions of this session and regions holding a selected chunk are kept.
	 */
	public void closeSession(Path serverDir, Collection<DimensionStore> stores) {
		closeWhenDrained(stores, () -> {
			Set<String> pinned = new HashSet<>();

			for (DimensionStore s : stores) {
				String dimension = CacheMaintenance.dimensionLabel(serverDir, s.dir());

				for (long region : s.touchedRegions) {
					pinned.add(EvictionPlanner.RegionUsage.id(dimension, ChunkKey.x(region), ChunkKey.z(region)));
				}
			}

			pinned.addAll(SelectionFiles.pinnedRegionIds(serverDir));
			CacheMaintenance.evictIfOverCap(serverDir, capBytes.getAsLong(), pinned);
		});
	}

	/** Closes the stores, deletes the server's cache except its selections, then runs {@code then} on the writer thread. */
	public void clearServer(Path serverDir, Collection<DimensionStore> stores, Runnable then) {
		control.add(() -> {
			hook.onServerCleared(serverDir);

			for (DimensionStore s : stores) {
				s.close();
				openStores.remove(s);
				s.touchedRegions.clear();
			}

			CacheMaintenance.clearKeepingSelections(serverDir);
			then.run();
		});
	}

	/** Runs a small background job on the writer thread (e.g. measuring the cache size). */
	public void execute(Runnable task) {
		control.add(task);
	}

	/** Drains the queue, closes everything and stops the thread, waiting at most {@code timeoutMillis}. */
	public void shutdown(long timeoutMillis) {
		control.add(new Runnable() {
			@Override
			public void run() {
				if (queue.size() > 0) {
					control.add(this);
					return;
				}

				running = false;
			}
		});

		try {
			thread.join(timeoutMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (thread.isAlive()) {
			SixteenToSave.LOGGER.warn("{}: writer did not finish within {} ms; {} chunks were still queued",
					SixteenToSave.NAME, timeoutMillis, queue.size());
		}
	}

	public Stats stats() {
		return new Stats(queue.size(), queue.usedBytes(), chunksWritten.sum(), entityChunksWritten.sum(), dropped.sum(), failed.sum());
	}

	// ---- writer thread -------------------------------------------------------------------

	private void run() {
		while (running) {
			try {
				runControlTasks();
				Work work = queue.poll(100, TimeUnit.MILLISECONDS);

				if (work != null) {
					try {
						process(work);
					} finally {
						work.store().pending.decrementAndGet();
					}
				} else {
					hook.onIdle();
				}
			} catch (InterruptedException e) {
				break;
			} catch (Throwable t) {
				failed.increment();
				RateLimitedLog.warn("writer", "{}: failed to write a cached chunk: {}", SixteenToSave.NAME, t.toString());
			}
		}

		for (DimensionStore s : openStores) {
			s.close();
			hook.onStoreClosed(s);
		}

		openStores.clear();
	}

	private void runControlTasks() {
		// Tasks may re-queue themselves; run each one at most once per round.
		int n = control.size();

		for (int i = 0; i < n; i++) {
			Runnable task = control.poll();

			if (task == null) {
				break;
			}

			try {
				task.run();
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.warn("{}: background task failed: {}", SixteenToSave.NAME, e.toString());
			}
		}
	}

	private void open(DimensionStore store) {
		if (!store.isOpen()) {
			store.ensureOpen();
			openStores.add(store);
		}
	}

	private void process(Work work) throws InterruptedException {
		switch (work) {
			case ChunkWork(ChunkSnapshot s) -> processChunk(s);
			case PatchWork(DimensionStore store, long chunkKey) -> processPatch(store, chunkKey);
		}
	}

	private void processChunk(ChunkSnapshot s) throws InterruptedException {
		DimensionStore store = s.store();
		open(store);
		ChunkPos pos = new ChunkPos(s.chunkX(), s.chunkZ());

		ListTag sections = s.encodedSections() != null
				? s.encodedSections()
				: ChunkTagAssembler.encodeSections(store, s.minSectionY(), s.detachedSections());
		CompoundTag previous = ChunkTagAssembler.needsPrevious(s) ? load(store.chunks(), pos) : null;
		CompoundTag tag = ChunkTagAssembler.chunkTag(s, sections, previous);
		write(store.chunks(), pos, tag);
		chunksWritten.increment();
		hook.onChunkWritten(store, s.chunkX(), s.chunkZ(), tag);

		if (s.entities() != null) {
			Set<String> dead = drainTombstones(store, s.chunkKey());
			CompoundTag previousEntities = load(store.entities(), pos);
			List<EntityMerger.Entry<CompoundTag>> merged = EntityMerger.merge(
					ChunkTagAssembler.readEntities(previousEntities),
					s.entities().list(),
					s.entities().authoritative(),
					dead,
					EntityMerger.DEFAULT_TRANSIENT_TYPES);
			writeEntities(store, pos, merged, previousEntities != null);
		}

		store.touch(ChunkKey.chunkToRegion(s.chunkX()), ChunkKey.chunkToRegion(s.chunkZ()), s.capturedAtMillis());
	}

	private void processPatch(DimensionStore store, long chunkKey) throws InterruptedException {
		Set<String> dead = drainTombstones(store, chunkKey);

		if (dead.isEmpty()) {
			return;
		}

		open(store);
		ChunkPos pos = new ChunkPos(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
		CompoundTag previousEntities = load(store.entities(), pos);

		if (previousEntities == null) {
			return;
		}

		List<EntityMerger.Entry<CompoundTag>> before = ChunkTagAssembler.readEntities(previousEntities);
		List<EntityMerger.Entry<CompoundTag>> after = EntityMerger.merge(before, List.of(), false, dead, Set.of());

		if (after.size() != before.size()) {
			writeEntities(store, pos, after, true);
		}
	}

	private void writeEntities(DimensionStore store, ChunkPos pos, List<EntityMerger.Entry<CompoundTag>> entities,
			boolean hadRecord) throws InterruptedException {
		if (entities.isEmpty()) {
			if (hadRecord) {
				write(store.entities(), pos, null);
				entityChunksWritten.increment();
			}

			return;
		}

		write(store.entities(), pos, ChunkTagAssembler.entitiesTag(pos, entities));
		entityChunksWritten.increment();
	}

	private Set<String> drainTombstones(DimensionStore store, long chunkKey) {
		Set<String> dead = tombstones.remove(new Key(store, chunkKey, true));
		return dead == null ? Set.of() : new HashSet<>(dead);
	}

	/** Reads a cached tag; a missing or unreadable chunk gives {@code null} (logged, never thrown). */
	private CompoundTag load(CacheRegionStorage storage, ChunkPos pos) {
		try {
			Optional<CompoundTag> tag = storage.load(pos).join();
			return tag.orElse(null);
		} catch (CompletionException | IllegalStateException e) {
			RateLimitedLog.warn("read", "{}: could not read cached chunk {},{}: {}", SixteenToSave.NAME, pos.x(), pos.z(), e.toString());
			return null;
		}
	}

	private void write(CacheRegionStorage storage, ChunkPos pos, CompoundTag tag) throws InterruptedException {
		inFlight.acquire();

		try {
			storage.store(pos, tag).whenComplete((ignored, error) -> {
				inFlight.release();

				if (error != null) {
					failed.increment();
					RateLimitedLog.warn("write", "{}: could not write cached chunk {},{}: {}", SixteenToSave.NAME, pos.x(), pos.z(), error.toString());
				}
			});
		} catch (RuntimeException e) {
			inFlight.release();
			throw e;
		}
	}
}

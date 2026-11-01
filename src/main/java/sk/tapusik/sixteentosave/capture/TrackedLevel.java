package sk.tapusik.sixteentosave.capture;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * Client-thread state for one {@link ClientLevel}: our own references to its loaded chunks (so
 * they can still be saved after vanilla drops the level), capture timing, observed containers,
 * and where each captured entity was last stored.
 */
final class TrackedLevel {
	static final class Tracked {
		final LevelChunk chunk;
		final long loadedAtNanos;
		long lastCapturedNanos;
		boolean captured;
		boolean dirty = true;

		Tracked(LevelChunk chunk, long loadedAtNanos) {
			this.chunk = chunk;
			this.loadedAtNanos = loadedAtNanos;
		}
	}

	final ClientLevel level;
	final CaptureService.Session session;
	final String dimensionId;

	private final Map<Long, Tracked> chunks = new HashMap<>();
	private final ArrayDeque<Long> roundRobin = new ArrayDeque<>();
	/** chunks to capture before anything else (a container was just seen, an export waits) */
	private final ArrayDeque<Long> urgent = new ArrayDeque<>();
	private final Set<Long> urgentKeys = new HashSet<>();
	/** chunk → (block pos → the block entity whose contents we observed) */
	private final Map<Long, Map<Long, WeakReference<BlockEntity>>> observed = new HashMap<>();
	/** entity uuid → chunk its latest capture was stored in (this session only) */
	final Map<String, Long> entityChunk = new HashMap<>();
	/** entities of a level that is no longer current; they no longer change, so one pass is enough */
	Map<Long, List<Entity>> frozenEntities;
	/** set when the level stopped being current; its remaining chunks are then saved over a few ticks */
	boolean finalizing;

	TrackedLevel(ClientLevel level, CaptureService.Session session) {
		this.level = level;
		this.session = session;
		this.dimensionId = level.dimension().identifier().toString();
	}

	static long key(LevelChunk chunk) {
		return ChunkKey.pack(chunk.getPos().x(), chunk.getPos().z());
	}

	void onLoad(LevelChunk chunk, long nowNanos) {
		long key = key(chunk);

		if (chunks.put(key, new Tracked(chunk, nowNanos)) == null) {
			roundRobin.addLast(key);
		}

		// Fresh data from the server: block entities were re-created, earlier observations are gone.
		observed.remove(key);

		if (roundRobin.size() > 2 * chunks.size() + 64) {
			roundRobin.clear();
			roundRobin.addAll(chunks.keySet());
		}
	}

	Tracked remove(long key) {
		return chunks.remove(key);
	}

	boolean isLoaded(long key) {
		return chunks.containsKey(key);
	}

	int size() {
		return chunks.size();
	}

	void markDirty(long key) {
		Tracked t = chunks.get(key);

		if (t != null) {
			t.dirty = true;
		}
	}

	/** Forget everything captured, so every loaded chunk is captured again (after "clear cache"). */
	void resetCaptureState() {
		for (Tracked t : chunks.values()) {
			t.captured = false;
			t.dirty = true;
		}

		entityChunk.clear();
	}

	/** Next chunk in round-robin order, or {@code null} if none is tracked. */
	Tracked next() {
		int n = roundRobin.size();

		for (int i = 0; i < n; i++) {
			Long key = roundRobin.pollFirst();
			Tracked t = chunks.get(key);

			if (t != null) {
				roundRobin.addLast(key);
				return t;
			}
		}

		return null;
	}

	/** Removes and returns any remaining chunk (for saving a level that is no longer current). */
	Tracked pollAny() {
		Iterator<Map.Entry<Long, Tracked>> it = chunks.entrySet().iterator();

		if (!it.hasNext()) {
			return null;
		}

		Tracked t = it.next().getValue();
		it.remove();
		return t;
	}

	void recordObserved(BlockPos pos, BlockEntity blockEntity) {
		long chunkKey = ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4);
		observed.computeIfAbsent(chunkKey, k -> new HashMap<>()).put(pos.asLong(), new WeakReference<>(blockEntity));
		markDirty(chunkKey);
		// what was just seen in a container should reach the cache now, not after the live interval
		markUrgent(chunkKey);
	}

	/** Queues a loaded chunk to be captured before the regular round-robin, regardless of the live interval. */
	void markUrgent(long key) {
		if (chunks.containsKey(key) && urgentKeys.add(key)) {
			urgent.addLast(key);
		}
	}

	/** Loaded chunks of {@code selection} that changed since their last capture (or were never captured) become urgent. */
	int markChangedUrgent(java.util.function.LongPredicate selection) {
		int n = 0;

		for (Map.Entry<Long, Tracked> e : chunks.entrySet()) {
			Tracked t = e.getValue();

			if (selection.test(e.getKey()) && (!t.captured || t.dirty || t.chunk.isUnsaved())) {
				markUrgent(e.getKey());
				n++;
			}
		}

		return n;
	}

	/** Next urgent chunk that is still loaded, or {@code null}. */
	Tracked nextUrgent() {
		while (!urgent.isEmpty()) {
			long key = urgent.pollFirst();
			urgentKeys.remove(key);
			Tracked t = chunks.get(key);

			if (t != null) {
				return t;
			}
		}

		return null;
	}

	/** Puts a chunk back at the front (it could not be captured this tick). */
	void retryUrgent(Tracked t) {
		long key = key(t.chunk);

		if (urgentKeys.add(key)) {
			urgent.addFirst(key);
		}
	}

	boolean hasUrgent() {
		return !urgent.isEmpty();
	}

	/** Observed positions whose block entity is still the one we observed (not replaced since). */
	long[] observedPositions(LevelChunk chunk) {
		Map<Long, WeakReference<BlockEntity>> inChunk = observed.get(key(chunk));

		if (inChunk == null) {
			return new long[0];
		}

		Map<BlockPos, BlockEntity> current = chunk.getBlockEntities();
		LongStream.Builder out = LongStream.builder();

		for (Map.Entry<Long, WeakReference<BlockEntity>> e : inChunk.entrySet()) {
			BlockEntity seen = e.getValue().get();

			if (seen != null && current.get(BlockPos.of(e.getKey())) == seen) {
				out.add(e.getKey());
			}
		}

		return out.build().toArray();
	}

	void forgetObserved(long chunkKey) {
		observed.remove(chunkKey);
	}
}

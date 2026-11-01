package sk.tapusik.sixteentosave.capture;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * FIFO work queue between the client thread and the writer thread.
 *
 * <ul>
 * <li>Bounded by an estimate of the bytes it holds, not by item count.</li>
 * <li>One entry per key: offering a key that is already queued replaces its value but keeps its
 * place in line.</li>
 * <li>Entries are <em>evictable</em> (can be retried later, e.g. a live snapshot of a chunk that is
 * still loaded) or not (an unload snapshot: the last chance to save that state).</li>
 * <li>When full, a non-evictable offer evicts the oldest evictable entries; an evictable offer is
 * rejected.</li>
 * <li>The producer never blocks; only {@link #poll} waits.</li>
 * </ul>
 */
public final class ByteBoundedCoalescingQueue<K, V> {
	public enum OfferResult {
		ACCEPTED,
		REPLACED,
		REJECTED
	}

	private record Entry<V>(V value, long bytes, boolean evictable) {
	}

	private final long capacityBytes;
	private final Consumer<V> onEvicted;
	private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();
	private long usedBytes;

	/**
	 * @param onEvicted called (under the queue lock, so keep it cheap) for every entry pushed out
	 *                  to make room
	 */
	public ByteBoundedCoalescingQueue(long capacityBytes, Consumer<V> onEvicted) {
		if (capacityBytes <= 0) {
			throw new IllegalArgumentException("capacityBytes must be positive");
		}

		this.capacityBytes = capacityBytes;
		this.onEvicted = Objects.requireNonNull(onEvicted);
	}

	public OfferResult offer(K key, V value, long bytes, boolean evictable) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		long size = Math.max(0, bytes);

		lock.lock();

		try {
			Entry<V> old = entries.get(key);
			long othersBytes = usedBytes - (old != null ? old.bytes : 0);

			if (othersBytes + size > capacityBytes) {
				if (evictable) {
					return OfferResult.REJECTED;
				}

				Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();

				while (othersBytes + size > capacityBytes && it.hasNext()) {
					Map.Entry<K, Entry<V>> e = it.next();

					if (e.getValue().evictable && !e.getKey().equals(key)) {
						it.remove();
						usedBytes -= e.getValue().bytes;
						othersBytes -= e.getValue().bytes;
						onEvicted.accept(e.getValue().value);
					}
				}

				// An item bigger than the whole queue is still accepted when nothing else is
				// waiting; otherwise it could never be processed.
				if (othersBytes + size > capacityBytes && othersBytes > 0) {
					return OfferResult.REJECTED;
				}
			}

			usedBytes = othersBytes + size;
			entries.put(key, new Entry<>(value, size, evictable));
			notEmpty.signal();
			return old != null ? OfferResult.REPLACED : OfferResult.ACCEPTED;
		} finally {
			lock.unlock();
		}
	}

	/** Takes the oldest entry, waiting up to {@code timeout}; returns {@code null} on timeout. */
	public V poll(long timeout, TimeUnit unit) throws InterruptedException {
		lock.lock();

		try {
			long nanos = unit.toNanos(timeout);

			while (entries.isEmpty()) {
				if (nanos <= 0) {
					return null;
				}

				nanos = notEmpty.awaitNanos(nanos);
			}

			Iterator<Entry<V>> it = entries.values().iterator();
			Entry<V> first = it.next();
			it.remove();
			usedBytes -= first.bytes;
			return first.value;
		} finally {
			lock.unlock();
		}
	}

	public boolean hasRoomFor(long bytes) {
		lock.lock();

		try {
			return usedBytes + bytes <= capacityBytes;
		} finally {
			lock.unlock();
		}
	}

	public long usedBytes() {
		lock.lock();

		try {
			return usedBytes;
		} finally {
			lock.unlock();
		}
	}

	public int size() {
		lock.lock();

		try {
			return entries.size();
		} finally {
			lock.unlock();
		}
	}

	public long capacityBytes() {
		return capacityBytes;
	}
}

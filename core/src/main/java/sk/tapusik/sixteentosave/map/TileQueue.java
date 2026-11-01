package sk.tapusik.sixteentosave.map;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Work queue for the tile workers: each tile at most once, lowest priority value first (the
 * distance from the view centre). Asking for a tile again updates its priority. {@link #clear}
 * drops everything still waiting, e.g. when the view changes completely.
 */
public final class TileQueue<K> {
	private record Item<K>(K key, double priority, long seq) {
	}

	private final PriorityQueue<Item<K>> heap = new PriorityQueue<>((a, b) -> {
		int c = Double.compare(a.priority, b.priority);
		return c != 0 ? c : Long.compare(a.seq, b.seq);
	});
	private final Map<K, Item<K>> waiting = new HashMap<>();
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();
	private long seq;

	public void request(K key, double priority) {
		lock.lock();

		try {
			Item<K> old = waiting.get(key);

			if (old != null) {
				if (old.priority == priority) {
					return;
				}

				heap.remove(old);
			}

			Item<K> item = new Item<>(key, priority, seq++);
			waiting.put(key, item);
			heap.add(item);
			notEmpty.signal();
		} finally {
			lock.unlock();
		}
	}

	/** The most urgent tile, waiting up to {@code timeout}; {@code null} on timeout. */
	public K take(long timeout, TimeUnit unit) throws InterruptedException {
		lock.lock();

		try {
			long nanos = unit.toNanos(timeout);

			while (heap.isEmpty()) {
				if (nanos <= 0) {
					return null;
				}

				nanos = notEmpty.awaitNanos(nanos);
			}

			Item<K> item = heap.poll();
			waiting.remove(item.key);
			return item.key;
		} finally {
			lock.unlock();
		}
	}

	public boolean contains(K key) {
		lock.lock();

		try {
			return waiting.containsKey(key);
		} finally {
			lock.unlock();
		}
	}

	public void clear() {
		lock.lock();

		try {
			heap.clear();
			waiting.clear();
		} finally {
			lock.unlock();
		}
	}

	public int size() {
		lock.lock();

		try {
			return waiting.size();
		} finally {
			lock.unlock();
		}
	}
}

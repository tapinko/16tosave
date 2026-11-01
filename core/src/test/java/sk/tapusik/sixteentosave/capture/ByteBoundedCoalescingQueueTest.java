package sk.tapusik.sixteentosave.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import sk.tapusik.sixteentosave.capture.ByteBoundedCoalescingQueue.OfferResult;

class ByteBoundedCoalescingQueueTest {
	private final List<String> evicted = new ArrayList<>();
	private final ByteBoundedCoalescingQueue<String, String> queue = new ByteBoundedCoalescingQueue<>(100, evicted::add);

	@Test
	void fifoOrder() throws InterruptedException {
		queue.offer("a", "A", 10, false);
		queue.offer("b", "B", 10, false);
		assertEquals("A", queue.poll(0, TimeUnit.MILLISECONDS));
		assertEquals("B", queue.poll(0, TimeUnit.MILLISECONDS));
		assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
		assertEquals(0, queue.usedBytes());
	}

	@Test
	void sameKeyReplacesValueButKeepsPlace() throws InterruptedException {
		queue.offer("a", "A1", 10, true);
		queue.offer("b", "B", 10, true);
		assertEquals(OfferResult.REPLACED, queue.offer("a", "A2", 30, false));
		assertEquals(2, queue.size());
		assertEquals(40, queue.usedBytes());
		assertEquals("A2", queue.poll(0, TimeUnit.MILLISECONDS));
	}

	@Test
	void evictableOfferIsRejectedWhenFull() {
		assertEquals(OfferResult.ACCEPTED, queue.offer("a", "A", 90, false));
		assertEquals(OfferResult.REJECTED, queue.offer("b", "B", 20, true));
		assertTrue(evicted.isEmpty());
		assertFalse(queue.hasRoomFor(20));
		assertTrue(queue.hasRoomFor(10));
	}

	@Test
	void finalOfferEvictsOldestEvictableEntries() throws InterruptedException {
		queue.offer("live1", "L1", 40, true);
		queue.offer("final1", "F1", 30, false);
		queue.offer("live2", "L2", 30, true);
		assertEquals(OfferResult.ACCEPTED, queue.offer("final2", "F2", 40, false));
		assertEquals(List.of("L1"), evicted);
		assertEquals(100, queue.usedBytes());
		assertEquals("F1", queue.poll(0, TimeUnit.MILLISECONDS));
	}

	@Test
	void finalOfferRejectedWhenOnlyFinalEntriesFill() {
		queue.offer("f1", "F1", 60, false);
		queue.offer("f2", "F2", 40, false);
		assertEquals(OfferResult.REJECTED, queue.offer("f3", "F3", 10, false));
		assertEquals(2, queue.size());
	}

	@Test
	void oversizedItemAcceptedIntoEmptyQueue() {
		assertEquals(OfferResult.ACCEPTED, queue.offer("big", "BIG", 500, false));
		assertEquals(OfferResult.REJECTED, queue.offer("small", "S", 1, true));
	}

	@Test
	void pollWaitsForProducer() throws Exception {
		Thread producer = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}

			queue.offer("x", "X", 1, false);
		});
		producer.start();
		assertEquals("X", queue.poll(5, TimeUnit.SECONDS));
		producer.join();
	}
}

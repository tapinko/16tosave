package sk.tapusik.sixteentosave.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class CaptureBasicsTest {
	@Test
	void contentTrackerCarriesOverOnlyMatchingEarlierObservations() {
		long chestSeenNow = 1;
		long chestSeenBefore = 2;
		long chestReplacedByBarrel = 3;
		long chestNeverSeen = 4;

		ContentTracker.Resolution r = ContentTracker.resolve(
				new long[] {chestNeverSeen, chestSeenBefore, chestSeenNow, chestReplacedByBarrel},
				new long[] {chestSeenNow, 99 /* no longer a container: ignored */},
				new long[] {chestSeenBefore, chestReplacedByBarrel},
				pos -> pos != chestReplacedByBarrel);

		assertArrayEquals(new long[] {chestSeenNow, chestSeenBefore}, r.observed());
		assertArrayEquals(new long[] {chestSeenBefore}, r.carryOver());
		assertArrayEquals(new long[] {chestReplacedByBarrel, chestNeverSeen}, r.incomplete());
		assertTrue(r.isIncomplete());
	}

	@Test
	void contentTrackerCompleteWhenNothingServerOnly() {
		ContentTracker.Resolution r = ContentTracker.resolve(new long[0], new long[] {5}, new long[] {6}, pos -> true);
		assertFalse(r.isIncomplete());
		assertEquals(0, r.observed().length);
		assertTrue(ContentTracker.hasServerOnlyContents("minecraft:chest"));
		assertFalse(ContentTracker.hasServerOnlyContents("minecraft:sign"));
	}

	@Test
	void uuidRoundTrip() {
		UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
		assertEquals(uuid.toString(), UuidCodec.fromIntArray(UuidCodec.toIntArray(uuid)));
		assertEquals("", UuidCodec.fromIntArray(new int[3]));
		assertEquals("", UuidCodec.fromIntArray(null));
		// vanilla stores the most significant int first
		assertEquals(0x069a79f4, UuidCodec.toIntArray(uuid)[0]);
	}

	@Test
	void chunkGeometryUsesFarthestCorner() {
		// standing in the middle of chunk (0,0): farthest corner is 8√2 away
		assertEquals(128.0, ChunkGeometry.farthestCornerDistanceSq(8, 8, 0, 0), 1e-9);
		assertTrue(ChunkGeometry.isChunkWithin(8, 8, 0, 0, 12));
		assertFalse(ChunkGeometry.isChunkWithin(8, 8, 0, 0, 11));
		// chunk (-1, 0) spans x -16..0
		assertEquals(16.0 * 16.0 + 16.0 * 16.0, ChunkGeometry.farthestCornerDistanceSq(0, 0, -1, 0), 1e-9);
	}

	@Test
	void tickBudgetAlwaysAllowsFirstUnit() {
		AtomicLong now = new AtomicLong();
		TickBudget budget = new TickBudget(1_000, now::get).start();
		now.set(5_000);
		assertTrue(budget.tryUse());
		assertFalse(budget.tryUse());

		budget.start();
		assertTrue(budget.tryUse());
		now.addAndGet(500);
		assertTrue(budget.tryUse());
		now.addAndGet(600);
		assertFalse(budget.tryUse());
	}
}

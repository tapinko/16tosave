package sk.tapusik.sixteentosave.capture;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.LongStream;

/**
 * Tracks which block entities have contents the server never sends to the client, and whether
 * those contents were observed (design §5.4). Positions are opaque packed block positions.
 */
public final class ContentTracker {
	/**
	 * Block entity types whose contents (items, bees, book, record) are server-side only: the
	 * client sees them only when the player opens the container, if at all.
	 */
	public static final Set<String> SERVER_ONLY_CONTENT_TYPES = Set.of(
			"minecraft:chest",
			"minecraft:trapped_chest",
			"minecraft:barrel",
			"minecraft:shulker_box",
			"minecraft:hopper",
			"minecraft:dispenser",
			"minecraft:dropper",
			"minecraft:furnace",
			"minecraft:blast_furnace",
			"minecraft:smoker",
			"minecraft:brewing_stand",
			"minecraft:crafter",
			"minecraft:beehive",
			"minecraft:lectern",
			"minecraft:chiseled_bookshelf",
			"minecraft:jukebox");

	/**
	 * @param observed   positions whose contents are known (now, or carried over from an earlier capture)
	 * @param incomplete positions whose contents are unknown
	 * @param carryOver  positions whose contents must be copied from the previous capture
	 */
	public record Resolution(long[] observed, long[] incomplete, long[] carryOver) {
		public boolean isIncomplete() {
			return incomplete.length > 0;
		}
	}

	private ContentTracker() {
	}

	public static boolean hasServerOnlyContents(String blockEntityType) {
		return SERVER_ONLY_CONTENT_TYPES.contains(blockEntityType);
	}

	/**
	 * @param contentPositions      positions in this snapshot that hold a server-only-contents block entity
	 * @param observedNow           positions whose contents were observed in this session
	 * @param previouslyObserved    positions marked observed in the previous capture of this chunk
	 * @param previousHasSameType   whether the previous capture has a block entity of the same type
	 *                              at that position (a chest replaced by a barrel loses its contents)
	 */
	public static Resolution resolve(long[] contentPositions, long[] observedNow, long[] previouslyObserved,
			LongPredicate previousHasSameType) {
		Set<Long> content = toSet(contentPositions);
		Set<Long> now = toSet(observedNow);
		Set<Long> before = toSet(previouslyObserved);

		LongStream.Builder observed = LongStream.builder();
		LongStream.Builder incomplete = LongStream.builder();
		LongStream.Builder carry = LongStream.builder();

		for (long pos : sorted(content)) {
			if (now.contains(pos)) {
				observed.add(pos);
			} else if (before.contains(pos) && previousHasSameType.test(pos)) {
				observed.add(pos);
				carry.add(pos);
			} else {
				incomplete.add(pos);
			}
		}

		return new Resolution(observed.build().toArray(), incomplete.build().toArray(), carry.build().toArray());
	}

	private static Set<Long> toSet(long[] values) {
		Set<Long> set = new HashSet<>(values.length * 2);

		for (long v : values) {
			set.add(v);
		}

		return set;
	}

	private static long[] sorted(Set<Long> set) {
		long[] out = set.stream().mapToLong(Long::longValue).toArray();
		Arrays.sort(out);
		return out;
	}
}

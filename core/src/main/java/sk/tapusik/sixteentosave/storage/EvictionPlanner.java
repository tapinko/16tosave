package sk.tapusik.sixteentosave.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Picks which cached regions to delete when a server's cache is over its size cap. */
public final class EvictionPlanner {
	/**
	 * Disk usage of one region in one dimension (chunk + entity + map files together).
	 *
	 * @param dimension any stable label of the dimension folder (e.g. its path relative to the server folder)
	 */
	public record RegionUsage(String dimension, int regionX, int regionZ, long bytes, long lastSeenMillis) {
		public String id() {
			return id(dimension, regionX, regionZ);
		}

		public static String id(String dimension, int regionX, int regionZ) {
			return dimension + "|" + regionX + "," + regionZ;
		}
	}

	private EvictionPlanner() {
	}

	/**
	 * Least recently seen regions first (ties: larger first) until the total is at most
	 * {@code capBytes}. Pinned regions are never chosen, even if that leaves the cache over the cap.
	 */
	public static List<RegionUsage> plan(Collection<RegionUsage> regions, long capBytes, Set<String> pinnedIds) {
		long total = 0;

		for (RegionUsage r : regions) {
			total += r.bytes();
		}

		List<RegionUsage> evict = new ArrayList<>();

		if (total <= capBytes) {
			return evict;
		}

		List<RegionUsage> candidates = new ArrayList<>(regions);
		candidates.sort(Comparator.comparingLong(RegionUsage::lastSeenMillis)
				.thenComparing(Comparator.comparingLong(RegionUsage::bytes).reversed())
				.thenComparing(RegionUsage::id));

		for (RegionUsage r : candidates) {
			if (total <= capBytes) {
				break;
			}

			if (pinnedIds.contains(r.id())) {
				continue;
			}

			evict.add(r);
			total -= r.bytes();
		}

		return evict;
	}
}

package sk.tapusik.sixteentosave.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * Where an exported world's spawn goes when the player isn't standing in the selection
 * (design §8.4): the largest connected area of selected chunks (edge neighbours), and in it the
 * chunk nearest to that area's centre.
 */
public final class SpawnPicker {
	private SpawnPicker() {
	}

	/** Chunk keys of the largest edge-connected area of the selection; it must not be empty. */
	public static List<Long> largestArea(ChunkSelection selection) {
		if (selection.isEmpty()) {
			throw new IllegalArgumentException("empty selection");
		}

		Set<Long> remaining = new HashSet<>(selection.count() * 2);
		selection.forEach((x, z) -> remaining.add(ChunkKey.pack(x, z)));
		List<Long> best = List.of();

		// iterate in a fixed order so equal-sized areas give a stable answer
		List<Long> order = new ArrayList<>(remaining);
		order.sort(null);

		for (long start : order) {
			if (!remaining.remove(start)) {
				continue;
			}

			List<Long> area = new ArrayList<>();
			Deque<Long> queue = new ArrayDeque<>();
			queue.add(start);

			while (!queue.isEmpty()) {
				long key = queue.poll();
				area.add(key);
				int x = ChunkKey.x(key);
				int z = ChunkKey.z(key);

				for (long next : new long[] { ChunkKey.pack(x + 1, z), ChunkKey.pack(x - 1, z), ChunkKey.pack(x, z + 1), ChunkKey.pack(x, z - 1) }) {
					if (remaining.remove(next)) {
						queue.add(next);
					}
				}
			}

			if (area.size() > best.size()) {
				best = area;
			}
		}

		return best;
	}

	/** Chunk key ({@link ChunkKey#pack}) of the chosen chunk; the selection must not be empty. */
	public static long pick(ChunkSelection selection) {
		List<Long> best = largestArea(selection);
		double cx = 0;
		double cz = 0;

		for (long key : best) {
			cx += ChunkKey.x(key);
			cz += ChunkKey.z(key);
		}

		cx /= best.size();
		cz /= best.size();
		long chosen = best.get(0);
		double bestDistance = Double.MAX_VALUE;

		for (long key : best) {
			double dx = ChunkKey.x(key) - cx;
			double dz = ChunkKey.z(key) - cz;
			double d = dx * dx + dz * dz;

			if (d < bestDistance || (d == bestDistance && key < chosen)) {
				bestDistance = d;
				chosen = key;
			}
		}

		return chosen;
	}
}

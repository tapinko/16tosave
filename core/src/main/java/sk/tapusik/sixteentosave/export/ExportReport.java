package sk.tapusik.sixteentosave.export;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an export did, per dimension (design §8.5): chunks selected, written and skipped (by
 * reason), entity chunks written, and chunks whose containers were not all seen. Filled by the
 * export thread; read when it is done.
 */
public final class ExportReport {
	public enum Skip {
		/** selected, but not in the cache (e.g. a loaded selection from before "clear cache") */
		NOT_CACHED,
		/** the cache file could not be read (I/O error, being written, unknown compression) */
		UNREADABLE,
		/** the chunk data is damaged or not a chunk */
		CORRUPT,
		/** the stored chunk says it is somewhere else */
		WRONG_POSITION,
		/** writing it into the new world failed */
		WRITE_FAILED
	}

	public static final class Dimension {
		public final String id;
		public int selected;
		public int written;
		public int entityChunks;
		public final Map<Skip, Integer> skipped = new EnumMap<>(Skip.class);
		/** chunk keys of written chunks with containers whose contents were never seen */
		public final List<Long> incomplete = new ArrayList<>();

		Dimension(String id) {
			this.id = id;
		}

		public void skip(Skip reason) {
			skipped.merge(reason, 1, Integer::sum);
		}

		public int skippedTotal() {
			int n = 0;

			for (int v : skipped.values()) {
				n += v;
			}

			return n;
		}
	}

	private final Map<String, Dimension> dimensions = new LinkedHashMap<>();

	public Dimension dimension(String id) {
		return dimensions.computeIfAbsent(id, Dimension::new);
	}

	public List<Dimension> dimensions() {
		return List.copyOf(dimensions.values());
	}

	public int written() {
		int n = 0;

		for (Dimension d : dimensions.values()) {
			n += d.written;
		}

		return n;
	}

	public int skipped() {
		int n = 0;

		for (Dimension d : dimensions.values()) {
			n += d.skippedTotal();
		}

		return n;
	}
}

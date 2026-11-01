package sk.tapusik.sixteentosave.selection;

import java.util.Map;

import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * The cache "universe" from region headers, and the export size estimate: the bytes the selected
 * chunks occupy in the cache's region and entity files (whole 4 KiB sectors, compressed), which
 * is close to what the export writes.
 */
public final class SelectionStats {
	private SelectionStats() {
	}

	/** Every chunk present in the given region headers (keys: region keys). */
	public static ChunkSelection universe(Map<Long, RegionHeader> headers) {
		ChunkSelection universe = new ChunkSelection();

		for (Map.Entry<Long, RegionHeader> e : headers.entrySet()) {
			addRegion(universe, ChunkKey.x(e.getKey()), ChunkKey.z(e.getKey()), e.getValue());
		}

		return universe;
	}

	/** Replaces one region of {@code universe} with what {@code header} says is present. */
	public static void addRegion(ChunkSelection universe, int regionX, int regionZ, RegionHeader header) {
		universe.setRegionBits(regionX, regionZ, presentBits(header));
	}

	/** The chunks a region header says are present, as region bits ({@link ChunkSelection#bitIndex}). */
	public static long[] presentBits(RegionHeader header) {
		long[] bits = new long[ChunkSelection.WORDS];

		for (int i = 0; i < RegionHeader.CHUNKS; i++) {
			if (header.isPresent(i)) {
				bits[i >> 6] |= 1L << (i & 63);
			}
		}

		return bits;
	}

	/** Bytes of the selected chunks in the chunk and entity region files; regions without a header count 0. */
	public static long estimateBytes(ChunkSelection selection, Map<Long, RegionHeader> chunkHeaders,
			Map<Long, RegionHeader> entityHeaders) {
		long total = 0;

		for (long region : selection.regionKeys()) {
			RegionHeader chunks = chunkHeaders.get(region);
			RegionHeader entities = entityHeaders.get(region);

			if (chunks == null && entities == null) {
				continue;
			}

			long[] bits = selection.regionBits(ChunkKey.x(region), ChunkKey.z(region));

			for (int w = 0; w < bits.length; w++) {
				for (long word = bits[w]; word != 0; word &= word - 1) {
					// bit index == RegionHeader.localIndex
					int index = (w << 6) + Long.numberOfTrailingZeros(word);
					total += (chunks == null ? 0 : chunks.sizeBytes(index)) + (entities == null ? 0 : entities.sizeBytes(index));
				}
			}
		}

		return total;
	}
}

package sk.tapusik.sixteentosave.map;

import java.util.concurrent.atomic.AtomicReferenceArray;

import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Summaries of the chunks of one region (32×32 chunks). Thread-safe for single-chunk reads and
 * writes. Stored per region as {@code map/r.X.Z.cps} by {@link RegionSummaryCodec}.
 */
public final class RegionSummary {
	private final int regionX;
	private final int regionZ;
	private final AtomicReferenceArray<ChunkSummary> chunks = new AtomicReferenceArray<>(RegionHeader.CHUNKS);

	public RegionSummary(int regionX, int regionZ) {
		this.regionX = regionX;
		this.regionZ = regionZ;
	}

	public int regionX() {
		return regionX;
	}

	public int regionZ() {
		return regionZ;
	}

	/** @param index local chunk index, {@code (x & 31) + (z & 31) * 32} */
	public ChunkSummary get(int index) {
		return chunks.get(index);
	}

	public void put(int index, ChunkSummary summary) {
		chunks.set(index, summary);
	}

	public void remove(int index) {
		chunks.set(index, null);
	}

	public int count() {
		int n = 0;

		for (int i = 0; i < RegionHeader.CHUNKS; i++) {
			if (chunks.get(i) != null) {
				n++;
			}
		}

		return n;
	}

	/**
	 * Whether the summary of chunk {@code index} matches the region file: present there, and not
	 * older than the file's write time minus {@code toleranceSeconds} (the file's timestamp is set
	 * a moment after the summary, when vanilla's I/O thread writes).
	 */
	public boolean isCurrent(int index, RegionHeader header, long toleranceSeconds) {
		ChunkSummary s = chunks.get(index);
		return s != null && header.isPresent(index) && s.writtenAtSeconds() + toleranceSeconds >= header.timestampSeconds(index);
	}
}

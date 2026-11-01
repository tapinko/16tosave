package sk.tapusik.sixteentosave.selection;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * Selection ↔ {@code {"<regionX>,<regionZ>": "<base64 of the region's 1024 bits>"}}, the
 * {@code regions} object of a selection file (design §8.1). Bad entries are skipped on decode.
 */
public final class SelectionCodec {
	private static final int BYTES = ChunkSelection.WORDS * Long.BYTES;

	private SelectionCodec() {
	}

	public static Map<String, String> encode(ChunkSelection selection) {
		Map<String, String> out = new TreeMap<>();

		for (long key : selection.regionKeys()) {
			int rx = ChunkKey.x(key);
			int rz = ChunkKey.z(key);
			long[] bits = selection.regionBits(rx, rz);
			ByteBuffer buffer = ByteBuffer.allocate(BYTES);

			for (long word : bits) {
				buffer.putLong(word);
			}

			out.put(rx + "," + rz, Base64.getEncoder().encodeToString(buffer.array()));
		}

		return out;
	}

	public static ChunkSelection decode(Map<String, String> regions) {
		ChunkSelection selection = new ChunkSelection();

		if (regions == null) {
			return selection;
		}

		for (Map.Entry<String, String> e : regions.entrySet()) {
			try {
				String[] parts = e.getKey().split(",");

				if (parts.length != 2 || e.getValue() == null) {
					continue;
				}

				int rx = Integer.parseInt(parts[0].trim());
				int rz = Integer.parseInt(parts[1].trim());
				byte[] data = Base64.getDecoder().decode(e.getValue().trim());

				if (data.length != BYTES) {
					continue;
				}

				ByteBuffer buffer = ByteBuffer.wrap(data);
				long[] bits = new long[ChunkSelection.WORDS];

				for (int w = 0; w < bits.length; w++) {
					bits[w] = buffer.getLong();
				}

				selection.setRegionBits(rx, rz, bits);
			} catch (IllegalArgumentException ignored) {
				// not a number or not base64: skip this region, keep the rest
			}
		}

		return selection;
	}
}

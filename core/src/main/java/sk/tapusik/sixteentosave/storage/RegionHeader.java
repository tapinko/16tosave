package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The 8 KiB header of an Anvil region file: for each of the 1024 chunks, where its data starts
 * (in 4 KiB sectors), how many sectors it uses, and when it was last written (epoch seconds).
 * Read-only; never modifies the file.
 */
public final class RegionHeader {
	public static final int SECTOR_BYTES = 4096;
	public static final int CHUNKS = 1024;

	private final int[] offsets;
	private final int[] timestamps;

	private RegionHeader(int[] offsets, int[] timestamps) {
		this.offsets = offsets;
		this.timestamps = timestamps;
	}

	/** Index of a chunk inside its region file: {@code (x & 31) + (z & 31) * 32}. */
	public static int localIndex(int chunkX, int chunkZ) {
		return (chunkX & 31) + (chunkZ & 31) * 32;
	}

	public static RegionHeader read(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate(2 * SECTOR_BYTES);

			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					break;
				}
			}

			buffer.flip();
			return parse(buffer);
		}
	}

	/** A file shorter than 8 KiB (empty or cut off) gives the chunks it has room for, the rest absent. */
	public static RegionHeader parse(ByteBuffer buffer) {
		int[] offsets = new int[CHUNKS];
		int[] timestamps = new int[CHUNKS];

		for (int i = 0; i < CHUNKS && 4 * i + 4 <= buffer.limit(); i++) {
			offsets[i] = buffer.getInt(4 * i);
		}

		for (int i = 0; i < CHUNKS && SECTOR_BYTES + 4 * i + 4 <= buffer.limit(); i++) {
			timestamps[i] = buffer.getInt(SECTOR_BYTES + 4 * i);
		}

		return new RegionHeader(offsets, timestamps);
	}

	public boolean isPresent(int index) {
		return sectorOffset(index) >= 2 && sectorCount(index) > 0;
	}

	public int sectorOffset(int index) {
		return offsets[index] >>> 8;
	}

	public int sectorCount(int index) {
		return offsets[index] & 0xFF;
	}

	/** Bytes the chunk occupies in the file (whole sectors). */
	public long sizeBytes(int index) {
		return isPresent(index) ? (long) sectorCount(index) * SECTOR_BYTES : 0;
	}

	/** Last write, in epoch seconds (0 if unknown). */
	public long timestampSeconds(int index) {
		return timestamps[index] & 0xFFFFFFFFL;
	}

	public int presentCount() {
		int n = 0;

		for (int i = 0; i < CHUNKS; i++) {
			if (isPresent(i)) {
				n++;
			}
		}

		return n;
	}
}

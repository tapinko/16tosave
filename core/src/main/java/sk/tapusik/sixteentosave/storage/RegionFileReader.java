package sk.tapusik.sixteentosave.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads one chunk's raw (uncompressed) NBT bytes from an Anvil region file, read-only.
 *
 * <p>The file may be written by vanilla's I/O thread at the same time; a read that races with a
 * write can see garbage and then fails with an {@link IOException}. Callers treat that as
 * "not available right now" and try again later.
 */
public final class RegionFileReader {
	/** Compression ids in the chunk header (vanilla {@code RegionFileVersion}). */
	static final int GZIP = 1;
	static final int ZLIB = 2;
	static final int NONE = 3;
	static final int LZ4 = 4;
	static final int EXTERNAL_FLAG = 0x80;

	private static final int MAX_CHUNK_BYTES = 256 * RegionHeader.SECTOR_BYTES;

	/** Thrown for a chunk stored with a compression this reader can't decode (LZ4 or custom). */
	public static final class UnsupportedCompressionException extends IOException {
		private static final long serialVersionUID = 1L;

		UnsupportedCompressionException(int type) {
			super("unsupported chunk compression type " + type);
		}
	}

	private RegionFileReader() {
	}

	/**
	 * @return the chunk's NBT bytes, or {@code null} if the chunk is not in the file
	 */
	public static byte[] read(Path regionFile, RegionHeader header, int chunkX, int chunkZ) throws IOException {
		int index = RegionHeader.localIndex(chunkX, chunkZ);

		if (!header.isPresent(index)) {
			return null;
		}

		long start = (long) header.sectorOffset(index) * RegionHeader.SECTOR_BYTES;
		int maxBytes = header.sectorCount(index) * RegionHeader.SECTOR_BYTES;

		try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
			ByteBuffer head = ByteBuffer.allocate(5);
			readFully(channel, head, start);
			head.flip();
			int length = head.getInt();
			int type = head.get() & 0xFF;

			if ((type & EXTERNAL_FLAG) != 0) {
				Path external = regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
				return decompress(type & ~EXTERNAL_FLAG, Files.readAllBytes(external));
			}

			if (length <= 1 || length - 1 > Math.min(maxBytes, MAX_CHUNK_BYTES)) {
				throw new IOException("bad chunk length " + length + " at " + chunkX + "," + chunkZ);
			}

			ByteBuffer data = ByteBuffer.allocate(length - 1);
			readFully(channel, data, start + 5);
			return decompress(type, data.array());
		}
	}

	static byte[] decompress(int type, byte[] data) throws IOException {
		switch (type) {
			case GZIP -> {
				try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
					return in.readAllBytes();
				}
			}
			case ZLIB -> {
				try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
					return in.readAllBytes();
				}
			}
			case NONE -> {
				return data;
			}
			default -> throw new UnsupportedCompressionException(type);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		long pos = position;

		while (buffer.hasRemaining()) {
			int n = channel.read(buffer, pos);

			if (n < 0) {
				throw new IOException("unexpected end of region file");
			}

			pos += n;
		}
	}
}

package sk.tapusik.sixteentosave.export;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Writes a new Anvil region file from scratch (zlib, like vanilla): chunks are appended one after
 * another, the 8 KiB header is written on {@link #close()}. A chunk over 255 sectors goes to a
 * {@code c.X.Z.mcc} file next to it, as vanilla does. Not thread-safe; one writer per file.
 */
public final class RegionFileWriter implements Closeable {
	static final int ZLIB = 2;
	static final int EXTERNAL_FLAG = 0x80;
	private static final int MAX_SECTORS = 255;

	private final Path file;
	private final FileChannel channel;
	private final int[] offsets = new int[RegionHeader.CHUNKS];
	private final int[] timestamps = new int[RegionHeader.CHUNKS];
	private int nextSector = 2;
	private int chunks;

	public RegionFileWriter(Path file) throws IOException {
		this.file = file;
		Files.createDirectories(file.getParent());
		this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE, StandardOpenOption.READ);
	}

	/** Adds one chunk; {@code nbt} is the uncompressed NBT. Writing the same chunk twice is an error. */
	public void write(int chunkX, int chunkZ, byte[] nbt, long timestampSeconds) throws IOException {
		int index = RegionHeader.localIndex(chunkX, chunkZ);

		if (offsets[index] != 0) {
			throw new IllegalStateException("chunk " + chunkX + "," + chunkZ + " written twice");
		}

		byte[] compressed = deflate(nbt);
		ByteBuffer payload;

		if (compressed.length + 5 > MAX_SECTORS * RegionHeader.SECTOR_BYTES) {
			Path external = file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
			Files.write(external, compressed);
			payload = ByteBuffer.allocate(5);
			payload.putInt(1);
			payload.put((byte) (ZLIB | EXTERNAL_FLAG));
		} else {
			payload = ByteBuffer.allocate(5 + compressed.length);
			payload.putInt(compressed.length + 1);
			payload.put((byte) ZLIB);
			payload.put(compressed);
		}

		payload.flip();
		int sectors = (payload.remaining() + RegionHeader.SECTOR_BYTES - 1) / RegionHeader.SECTOR_BYTES;
		long position = (long) nextSector * RegionHeader.SECTOR_BYTES;

		while (payload.hasRemaining()) {
			position += channel.write(payload, position);
		}

		offsets[index] = nextSector << 8 | sectors;
		timestamps[index] = (int) timestampSeconds;
		nextSector += sectors;
		chunks++;
	}

	public int chunkCount() {
		return chunks;
	}

	/** Writes the header and pads the file to whole sectors. */
	@Override
	public void close() throws IOException {
		try {
			ByteBuffer header = ByteBuffer.allocate(2 * RegionHeader.SECTOR_BYTES);

			for (int i = 0; i < RegionHeader.CHUNKS; i++) {
				header.putInt(4 * i, offsets[i]);
				header.putInt(RegionHeader.SECTOR_BYTES + 4 * i, timestamps[i]);
			}

			long position = 0;

			while (header.hasRemaining()) {
				position += channel.write(header, position);
			}

			long end = (long) nextSector * RegionHeader.SECTOR_BYTES;

			if (channel.size() < end) {
				channel.write(ByteBuffer.wrap(new byte[] { 0 }), end - 1);
			}

			channel.force(true);
		} finally {
			channel.close();
		}
	}

	private static byte[] deflate(byte[] data) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
		Deflater deflater = new Deflater();

		try (DeflaterOutputStream z = new DeflaterOutputStream(out, deflater)) {
			z.write(data);
		} finally {
			deflater.end();
		}

		return out.toByteArray();
	}
}

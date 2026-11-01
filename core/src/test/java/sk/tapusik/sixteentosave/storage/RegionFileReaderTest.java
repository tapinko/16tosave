package sk.tapusik.sixteentosave.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFileReaderTest {
	@TempDir
	Path tmp;

	/** Writes a minimal region file the way vanilla lays it out. */
	private static final class RegionBuilder {
		private final ByteBuffer file = ByteBuffer.allocate(64 * RegionHeader.SECTOR_BYTES);
		private int nextSector = 2;

		void put(int chunkX, int chunkZ, int compression, byte[] compressed, int timestamp) {
			int index = RegionHeader.localIndex(chunkX, chunkZ);
			int length = compressed.length + 1;
			int sectors = (length + 4 + RegionHeader.SECTOR_BYTES - 1) / RegionHeader.SECTOR_BYTES;
			file.putInt(4 * index, nextSector << 8 | sectors);
			file.putInt(RegionHeader.SECTOR_BYTES + 4 * index, timestamp);
			int pos = nextSector * RegionHeader.SECTOR_BYTES;
			file.putInt(pos, length);
			file.put(pos + 4, (byte) compression);
			file.put(pos + 5, compressed);
			nextSector += sectors;
		}

		Path write(Path path) throws IOException {
			Files.write(path, java.util.Arrays.copyOf(file.array(), nextSector * RegionHeader.SECTOR_BYTES));
			return path;
		}
	}

	private static byte[] zlib(byte[] data) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try (OutputStream z = new DeflaterOutputStream(out)) {
			z.write(data);
		}

		return out.toByteArray();
	}

	private static byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try (OutputStream z = new GZIPOutputStream(out)) {
			z.write(data);
		}

		return out.toByteArray();
	}

	@Test
	void readsAllVanillaCompressions() throws IOException {
		byte[] a = "chunk a".getBytes(StandardCharsets.UTF_8);
		byte[] b = "chunk b".getBytes(StandardCharsets.UTF_8);
		byte[] c = "chunk c".getBytes(StandardCharsets.UTF_8);

		RegionBuilder region = new RegionBuilder();
		region.put(-32, 0, RegionFileReader.ZLIB, zlib(a), 1000);
		region.put(-31, 0, RegionFileReader.GZIP, gzip(b), 1001);
		region.put(-30, 5, RegionFileReader.NONE, c, 1002);
		Path file = region.write(tmp.resolve("r.-1.0.mca"));

		RegionHeader header = RegionHeader.read(file);
		assertEquals(3, header.presentCount());
		assertEquals(1001, header.timestampSeconds(RegionHeader.localIndex(-31, 0)));
		assertEquals(RegionHeader.SECTOR_BYTES, header.sizeBytes(RegionHeader.localIndex(-32, 0)));

		assertArrayEquals(a, RegionFileReader.read(file, header, -32, 0));
		assertArrayEquals(b, RegionFileReader.read(file, header, -31, 0));
		assertArrayEquals(c, RegionFileReader.read(file, header, -30, 5));
		assertNull(RegionFileReader.read(file, header, -29, 0));
	}

	@Test
	void readsExternalChunkFile() throws IOException {
		byte[] big = "huge chunk".getBytes(StandardCharsets.UTF_8);
		RegionBuilder region = new RegionBuilder();
		region.put(3, 4, RegionFileReader.ZLIB | RegionFileReader.EXTERNAL_FLAG, new byte[0], 5);
		Path file = region.write(tmp.resolve("r.0.0.mca"));
		Files.write(tmp.resolve("c.3.4.mcc"), zlib(big));

		assertArrayEquals(big, RegionFileReader.read(file, RegionHeader.read(file), 3, 4));
	}

	@Test
	void corruptOrUnsupportedDataFails() throws IOException {
		RegionBuilder region = new RegionBuilder();
		region.put(0, 0, RegionFileReader.ZLIB, "not zlib at all".getBytes(StandardCharsets.UTF_8), 1);
		region.put(1, 0, RegionFileReader.LZ4, new byte[] {1, 2, 3}, 1);
		Path file = region.write(tmp.resolve("r.0.0.mca"));
		RegionHeader header = RegionHeader.read(file);

		assertThrows(IOException.class, () -> RegionFileReader.read(file, header, 0, 0));
		assertThrows(RegionFileReader.UnsupportedCompressionException.class, () -> RegionFileReader.read(file, header, 1, 0));
	}

	@Test
	void shortOrEmptyFileHasNoChunks() throws IOException {
		Path empty = Files.write(tmp.resolve("r.5.5.mca"), new byte[0]);
		assertEquals(0, RegionHeader.read(empty).presentCount());

		Path cut = Files.write(tmp.resolve("r.6.6.mca"), new byte[100]);
		RegionHeader header = RegionHeader.read(cut);
		assertFalse(header.isPresent(0));
		assertEquals(0, header.timestampSeconds(0));
	}

	@Test
	void localIndexMatchesVanilla() {
		assertEquals(0, RegionHeader.localIndex(0, 0));
		assertEquals(31, RegionHeader.localIndex(-1, 0));
		assertEquals(31 * 32, RegionHeader.localIndex(0, -1));
		assertTrue(RegionHeader.localIndex(33, 33) == 1 + 32);
	}
}

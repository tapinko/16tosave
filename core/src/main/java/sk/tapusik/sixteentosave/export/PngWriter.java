package sk.tapusik.sixteentosave.export;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** A minimal PNG encoder (8-bit RGBA, no filtering), for the exported world's {@code icon.png}. */
public final class PngWriter {
	private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private PngWriter() {
	}

	/** {@code argb}: {@code width × height} pixels, row by row. */
	public static byte[] encode(int width, int height, int[] argb) throws IOException {
		if (argb.length != width * height) {
			throw new IllegalArgumentException("pixel count does not match the size");
		}

		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);

		try (DeflaterOutputStream z = new DeflaterOutputStream(raw, deflater)) {
			byte[] row = new byte[1 + width * 4];

			for (int y = 0; y < height; y++) {
				row[0] = 0; // filter: none

				for (int x = 0; x < width; x++) {
					int p = argb[x + y * width];
					row[1 + x * 4] = (byte) (p >> 16);
					row[2 + x * 4] = (byte) (p >> 8);
					row[3 + x * 4] = (byte) p;
					row[4 + x * 4] = (byte) (p >>> 24);
				}

				z.write(row);
			}
		} finally {
			deflater.end();
		}

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(png);
		out.write(SIGNATURE);

		ByteArrayOutputStream header = new ByteArrayOutputStream();
		DataOutputStream h = new DataOutputStream(header);
		h.writeInt(width);
		h.writeInt(height);
		h.writeByte(8); // bit depth
		h.writeByte(6); // colour type: RGBA
		h.writeByte(0); // compression
		h.writeByte(0); // filter method
		h.writeByte(0); // no interlace
		chunk(out, "IHDR", header.toByteArray());
		chunk(out, "IDAT", raw.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return png.toByteArray();
	}

	public static void write(Path file, int width, int height, int[] argb) throws IOException {
		Files.write(file, encode(width, height, argb));
	}

	private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data);
		out.writeInt(data.length);
		out.write(name);
		out.write(data);
		out.writeInt((int) crc.getValue());
	}
}

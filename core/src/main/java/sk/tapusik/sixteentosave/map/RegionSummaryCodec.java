package sk.tapusik.sixteentosave.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import sk.tapusik.sixteentosave.storage.AtomicFiles;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Binary format of {@code map/r.X.Z.cps}: deflate-compressed; magic {@code 16TS}, format
 * version, region coordinates, then one record per summarised chunk. A file with a different
 * version is ignored (rebuilt from the region files). Version 2 added a flags byte per chunk
 * (bit 0: containers with unseen contents).
 */
public final class RegionSummaryCodec {
	public static final String EXTENSION = ".cps";
	static final int MAGIC = 0x31365453; // "16TS"
	static final int VERSION = 2;
	private static final int FLAG_INCOMPLETE = 1;

	private RegionSummaryCodec() {
	}

	public static String fileName(int regionX, int regionZ) {
		return "r." + regionX + "." + regionZ + EXTENSION;
	}

	public static byte[] encode(RegionSummary region) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeInt(region.regionX());
			out.writeInt(region.regionZ());
			out.writeShort(region.count());

			for (int i = 0; i < RegionHeader.CHUNKS; i++) {
				ChunkSummary s = region.get(i);

				if (s == null) {
					continue;
				}

				out.writeShort(i);
				out.writeLong(s.writtenAtSeconds());
				out.writeByte(s.incompleteContainers() ? FLAG_INCOMPLETE : 0);
				out.writeShort(s.biomePalette().size());

				for (String biome : s.biomePalette()) {
					out.writeUTF(biome);
				}

				out.write(s.color());

				for (short h : s.height()) {
					out.writeShort(h);
				}

				out.write(s.waterDepth());
				out.write(s.biome());
			}
		}

		return bytes.toByteArray();
	}

	/**
	 * @throws IOException if the data is not a summary of the expected version and region
	 */
	public static RegionSummary decode(byte[] data, int regionX, int regionZ) throws IOException {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("not a 16tosave summary file");
			}

			int version = in.readInt();

			if (version != VERSION) {
				throw new IOException("summary format " + version + " is not supported");
			}

			if (in.readInt() != regionX || in.readInt() != regionZ) {
				throw new IOException("summary file belongs to another region");
			}

			RegionSummary region = new RegionSummary(regionX, regionZ);
			int count = in.readUnsignedShort();

			for (int n = 0; n < count; n++) {
				int index = in.readUnsignedShort();

				if (index >= RegionHeader.CHUNKS) {
					throw new IOException("bad chunk index " + index);
				}

				long writtenAt = in.readLong();
				int flags = in.readUnsignedByte();
				int paletteSize = in.readUnsignedShort();

				if (paletteSize > 256) {
					throw new IOException("bad biome palette size " + paletteSize);
				}

				List<String> palette = new ArrayList<>(paletteSize);

				for (int p = 0; p < paletteSize; p++) {
					palette.add(in.readUTF());
				}

				byte[] color = readBytes(in, ChunkSummary.COLUMNS);
				short[] height = new short[ChunkSummary.COLUMNS];

				for (int c = 0; c < ChunkSummary.COLUMNS; c++) {
					height[c] = in.readShort();
				}

				byte[] water = readBytes(in, ChunkSummary.COLUMNS);
				byte[] biome = readBytes(in, ChunkSummary.COLUMNS);
				region.put(index, new ChunkSummary(writtenAt, color, height, water, biome, palette, (flags & FLAG_INCOMPLETE) != 0));
			}

			return region;
		}
	}

	/** Missing or unreadable file → empty summary (it is only a cache). */
	public static RegionSummary load(Path file, int regionX, int regionZ) {
		if (!Files.isRegularFile(file)) {
			return new RegionSummary(regionX, regionZ);
		}

		try (InputStream in = Files.newInputStream(file)) {
			return decode(in.readAllBytes(), regionX, regionZ);
		} catch (IOException | RuntimeException e) {
			return new RegionSummary(regionX, regionZ);
		}
	}

	public static void save(Path file, RegionSummary region) throws IOException {
		Path parent = file.toAbsolutePath().getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, encode(region));
		AtomicFiles.replace(tmp, file);
	}

	private static byte[] readBytes(DataInputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		in.readFully(b);
		return b;
	}
}

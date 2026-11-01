package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * When each region of one dimension was last written by capture, stored as
 * {@code last_seen.properties} ({@code r.X.Z=<epoch millis>}). Used to evict the least recently
 * seen regions first. Not thread-safe; owned by the writer thread.
 */
public final class RegionLastSeen {
	public static final String FILE_NAME = "last_seen.properties";

	private final Map<Long, Long> lastSeen = new HashMap<>();

	public void touch(int regionX, int regionZ, long millis) {
		lastSeen.merge(ChunkKey.pack(regionX, regionZ), millis, Math::max);
	}

	/** Last-seen time, or {@code fallback} if unknown. */
	public long get(int regionX, int regionZ, long fallback) {
		return lastSeen.getOrDefault(ChunkKey.pack(regionX, regionZ), fallback);
	}

	public void remove(int regionX, int regionZ) {
		lastSeen.remove(ChunkKey.pack(regionX, regionZ));
	}

	public int size() {
		return lastSeen.size();
	}

	/** Reads the file in {@code dimensionDir}; a missing or unreadable file gives an empty index. */
	public static RegionLastSeen load(Path dimensionDir) {
		RegionLastSeen index = new RegionLastSeen();
		Path file = dimensionDir.resolve(FILE_NAME);

		if (!Files.isRegularFile(file)) {
			return index;
		}

		Properties props = new Properties();

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			return index;
		}

		for (String key : props.stringPropertyNames()) {
			RegionFileName.parse(key + ".mca").ifPresent(c -> {
				try {
					index.touch(c.regionX(), c.regionZ(), Long.parseLong(props.getProperty(key).trim()));
				} catch (NumberFormatException ignored) {
					// skip the broken line, keep the rest
				}
			});
		}

		return index;
	}

	/** Writes the file atomically (temp file + move). */
	public void save(Path dimensionDir) throws IOException {
		Files.createDirectories(dimensionDir);
		Map<String, String> sorted = new TreeMap<>();

		for (Map.Entry<Long, Long> e : lastSeen.entrySet()) {
			sorted.put("r." + ChunkKey.x(e.getKey()) + "." + ChunkKey.z(e.getKey()), Long.toString(e.getValue()));
		}

		Path tmp = dimensionDir.resolve(FILE_NAME + ".tmp");

		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write("# 16tosave: last time each region was captured (epoch millis)\n");

			for (Map.Entry<String, String> e : sorted.entrySet()) {
				w.write(e.getKey() + "=" + e.getValue() + "\n");
			}
		}

		AtomicFiles.replace(tmp, dimensionDir.resolve(FILE_NAME));
	}
}

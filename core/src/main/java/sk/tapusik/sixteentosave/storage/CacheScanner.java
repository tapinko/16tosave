package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds the cached regions of one server folder and how much disk they use. A dimension folder
 * is any folder that contains {@code region/}, {@code entities/} or {@code map/}; files of the
 * same region in those three folders count as one region.
 */
public final class CacheScanner {
	/** Folders inside a dimension folder that hold per-region files. */
	public static final Set<String> REGION_FOLDERS = Set.of("region", "entities", "map");

	private static final int MAX_DEPTH = 8;

	/**
	 * @param dimensionDir the dimension folder holding the region folders
	 * @param files        every file belonging to this region (region, entities, map)
	 */
	public record CachedRegion(Path dimensionDir, int regionX, int regionZ, long bytes, long newestModifiedMillis,
			List<Path> files) {
	}

	private CacheScanner() {
	}

	public static List<CachedRegion> scan(Path serverDir) throws IOException {
		Map<String, Builder> regions = new LinkedHashMap<>();

		if (!Files.isDirectory(serverDir)) {
			return List.of();
		}

		try (Stream<Path> walk = Files.walk(serverDir, MAX_DEPTH)) {
			walk.forEach(file -> {
				Path folder = file.getParent();

				if (folder == null || folder.getParent() == null || !REGION_FOLDERS.contains(folder.getFileName().toString())) {
					return;
				}

				String name = file.getFileName().toString();
				// map/ uses its own extension; compare by the r.X.Z part only
				String asMca = name.endsWith(".mca") ? name : name.replaceFirst("\\.[a-z]+$", ".mca");

				RegionFileName.parse(asMca).ifPresent(coords -> {
					try {
						BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);

						if (!attrs.isRegularFile()) {
							return;
						}

						Path dimensionDir = folder.getParent();
						String key = dimensionDir + "|" + coords.regionX() + "," + coords.regionZ();
						regions.computeIfAbsent(key, k -> new Builder(dimensionDir, coords.regionX(), coords.regionZ()))
								.add(file, attrs.size(), attrs.lastModifiedTime().toMillis());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		List<CachedRegion> out = new ArrayList<>(regions.size());

		for (Builder b : regions.values()) {
			out.add(b.build());
		}

		return out;
	}

	/** Total size of all regular files below {@code dir}; 0 if it does not exist. */
	public static long totalBytes(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return 0;
		}

		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		}
	}

	private static final class Builder {
		private final Path dimensionDir;
		private final int regionX;
		private final int regionZ;
		private final List<Path> files = new ArrayList<>();
		private long bytes;
		private long newest;

		Builder(Path dimensionDir, int regionX, int regionZ) {
			this.dimensionDir = dimensionDir;
			this.regionX = regionX;
			this.regionZ = regionZ;
		}

		void add(Path file, long size, long modified) {
			files.add(file);
			bytes += size;
			newest = Math.max(newest, modified);
		}

		CachedRegion build() {
			return new CachedRegion(dimensionDir, regionX, regionZ, bytes, newest, List.copyOf(files));
		}
	}
}

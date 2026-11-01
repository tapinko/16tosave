package sk.tapusik.sixteentosave.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionFileName;

/** Finds what is cached for a server: its dimensions and, per dimension, its region files. Disk I/O; call off the render thread. */
final class MapDimensions {
	private static final int MAX_DEPTH = 6;

	private MapDimensions() {
	}

	/** Dimension ids that have a {@code region/} folder, e.g. {@code minecraft:overworld}; sorted. */
	static List<String> list(Path serverDir) {
		List<String> out = new ArrayList<>();

		if (serverDir == null || !Files.isDirectory(serverDir)) {
			return out;
		}

		try (Stream<Path> walk = Files.walk(serverDir, MAX_DEPTH)) {
			walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(CacheLayout.REGION_FOLDER) && Files.isDirectory(p))
					.map(p -> serverDir.relativize(p.getParent()))
					.filter(rel -> rel.getNameCount() >= 2)
					.forEach(rel -> {
						StringBuilder id = new StringBuilder(rel.getName(0).toString()).append(':');

						for (int i = 1; i < rel.getNameCount(); i++) {
							if (i > 1) {
								id.append('/');
							}

							id.append(rel.getName(i));
						}

						out.add(id.toString());
					});
		} catch (IOException | RuntimeException e) {
			return out;
		}

		out.sort(null);
		return out;
	}

	/** Region keys ({@link ChunkKey#pack} of region coordinates) that have a region file. */
	static Set<Long> regions(Path dimensionDir) {
		Set<Long> out = new HashSet<>();
		Path dir = dimensionDir.resolve(CacheLayout.REGION_FOLDER);

		if (!Files.isDirectory(dir)) {
			return out;
		}

		try (Stream<Path> files = Files.list(dir)) {
			files.forEach(f -> RegionFileName.parse(f.getFileName().toString())
					.ifPresent(c -> out.add(ChunkKey.pack(c.regionX(), c.regionZ()))));
		} catch (IOException | RuntimeException e) {
			return out;
		}

		return out;
	}
}

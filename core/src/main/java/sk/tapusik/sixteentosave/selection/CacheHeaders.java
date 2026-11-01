package sk.tapusik.sixteentosave.selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.RegionFileName;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * The region headers of one dimension's cache (keys: {@link ChunkKey#pack} of region
 * coordinates): which chunks exist and how big they are. Disk I/O (8 KiB per region file); call
 * off the render thread. Unreadable files are left out.
 */
public record CacheHeaders(Map<Long, RegionHeader> chunks, Map<Long, RegionHeader> entities) {
	public static CacheHeaders load(Path regionFolder, Path entitiesFolder) {
		return new CacheHeaders(read(regionFolder), read(entitiesFolder));
	}

	/** One region's header, or {@code null} if the file is missing or unreadable. */
	public static RegionHeader readOne(Path folder, int regionX, int regionZ) {
		Path file = folder.resolve(RegionFileName.of(regionX, regionZ));

		try {
			return Files.isRegularFile(file) ? RegionHeader.read(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Every chunk that has data in the chunk region files. */
	public ChunkSelection universe() {
		return SelectionStats.universe(chunks);
	}

	private static Map<Long, RegionHeader> read(Path folder) {
		Map<Long, RegionHeader> out = new HashMap<>();

		if (!Files.isDirectory(folder)) {
			return out;
		}

		try (Stream<Path> files = Files.list(folder)) {
			files.forEach(f -> RegionFileName.parse(f.getFileName().toString()).ifPresent(c -> {
				try {
					out.put(ChunkKey.pack(c.regionX(), c.regionZ()), RegionHeader.read(f));
				} catch (IOException e) {
					// being replaced or unreadable: leave it out
				}
			}));
		} catch (IOException | RuntimeException e) {
			return out;
		}

		return out;
	}
}

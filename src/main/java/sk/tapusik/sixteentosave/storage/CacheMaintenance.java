package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.selection.SelectionPaths;
import sk.tapusik.sixteentosave.storage.CacheScanner.CachedRegion;
import sk.tapusik.sixteentosave.storage.EvictionPlanner.RegionUsage;

/** Size cap and "clear cache" (design §6). Runs on the writer thread, after the stores are closed. */
public final class CacheMaintenance {
	private CacheMaintenance() {
	}

	/** Label of a dimension folder used in eviction ids: its path relative to the server folder. */
	public static String dimensionLabel(Path serverDir, Path dimensionDir) {
		return serverDir.relativize(dimensionDir).toString().replace('\\', '/');
	}

	static void evictIfOverCap(Path serverDir, long capBytes, Set<String> pinnedIds) {
		try {
			List<CachedRegion> regions = CacheScanner.scan(serverDir);
			Map<Path, RegionLastSeen> lastSeen = new HashMap<>();
			List<RegionUsage> usages = new ArrayList<>(regions.size());
			Map<String, CachedRegion> byId = new HashMap<>();

			for (CachedRegion r : regions) {
				RegionLastSeen index = lastSeen.computeIfAbsent(r.dimensionDir(), RegionLastSeen::load);
				RegionUsage usage = new RegionUsage(dimensionLabel(serverDir, r.dimensionDir()), r.regionX(), r.regionZ(),
						r.bytes(), index.get(r.regionX(), r.regionZ(), r.newestModifiedMillis()));
				usages.add(usage);
				byId.put(usage.id(), r);
			}

			List<RegionUsage> evict = EvictionPlanner.plan(usages, capBytes, pinnedIds);

			if (evict.isEmpty()) {
				return;
			}

			long freed = 0;

			for (RegionUsage usage : evict) {
				CachedRegion r = byId.get(usage.id());

				for (Path file : r.files()) {
					Files.deleteIfExists(file);
				}

				lastSeen.get(r.dimensionDir()).remove(r.regionX(), r.regionZ());
				freed += r.bytes();
			}

			for (Map.Entry<Path, RegionLastSeen> e : lastSeen.entrySet()) {
				e.getValue().save(e.getKey());
			}

			SixteenToSave.LOGGER.info("{}: cache for {} was over {} MiB; removed {} least recently seen regions ({} MiB)",
					SixteenToSave.NAME, serverDir.getFileName(), capBytes >> 20, evict.size(), freed >> 20);
		} catch (IOException | RuntimeException e) {
			SixteenToSave.LOGGER.warn("{}: cache size check for {} failed: {}", SixteenToSave.NAME, serverDir, e.toString());
		}
	}

	/** Deletes a server's cache but keeps its selection files (and the folders that hold them). */
	static void clearKeepingSelections(Path serverDir) {
		if (!Files.exists(serverDir)) {
			return;
		}

		try (Stream<Path> walk = Files.walk(serverDir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (Files.isDirectory(p)) {
					try (Stream<Path> children = Files.list(p)) {
						if (children.findAny().isPresent()) {
							continue; // holds selections
						}
					}
				} else if (SelectionPaths.dimensionDirOf(p) != null) {
					continue;
				}

				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					SixteenToSave.LOGGER.warn("{}: could not delete {}: {}", SixteenToSave.NAME, p, e.toString());
				}
			}
		} catch (IOException | RuntimeException e) {
			SixteenToSave.LOGGER.warn("{}: could not clear {}: {}", SixteenToSave.NAME, serverDir, e.toString());
		}
	}

	/** Disk use of one server's cache, in bytes; -1 if it could not be measured. */
	public static long sizeOf(Path serverDir) {
		try {
			return CacheScanner.totalBytes(serverDir);
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}
}

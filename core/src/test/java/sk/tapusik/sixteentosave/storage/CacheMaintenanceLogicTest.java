package sk.tapusik.sixteentosave.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sk.tapusik.sixteentosave.storage.EvictionPlanner.RegionUsage;

class CacheMaintenanceLogicTest {
	@TempDir
	Path tmp;

	@Test
	void nothingEvictedUnderCap() {
		List<RegionUsage> regions = List.of(new RegionUsage("o", 0, 0, 50, 1), new RegionUsage("o", 1, 0, 50, 2));
		assertEquals(List.of(), EvictionPlanner.plan(regions, 100, Set.of()));
	}

	@Test
	void evictsLeastRecentlySeenFirstAndSkipsPinned() {
		RegionUsage oldest = new RegionUsage("o", 0, 0, 40, 1);
		RegionUsage pinnedOld = new RegionUsage("o", 1, 0, 40, 2);
		RegionUsage middle = new RegionUsage("n", 0, 0, 40, 3);
		RegionUsage newest = new RegionUsage("o", 2, 0, 40, 4);

		List<RegionUsage> evict = EvictionPlanner.plan(List.of(newest, middle, pinnedOld, oldest), 90, Set.of(pinnedOld.id()));
		assertEquals(List.of(oldest, middle), evict);
	}

	@Test
	void pinnedCanLeaveCacheOverCap() {
		RegionUsage a = new RegionUsage("o", 0, 0, 100, 1);
		assertEquals(List.of(), EvictionPlanner.plan(List.of(a), 10, Set.of(a.id())));
	}

	@Test
	void lastSeenRoundTripKeepsMaximum() throws IOException {
		RegionLastSeen index = new RegionLastSeen();
		index.touch(-1, 2, 100);
		index.touch(-1, 2, 50);
		index.touch(3, 4, 7);
		index.save(tmp);

		RegionLastSeen loaded = RegionLastSeen.load(tmp);
		assertEquals(100, loaded.get(-1, 2, 0));
		assertEquals(7, loaded.get(3, 4, 0));
		assertEquals(-5, loaded.get(9, 9, -5));
		assertEquals(2, loaded.size());
	}

	@Test
	void lastSeenToleratesGarbage() throws IOException {
		Files.writeString(tmp.resolve(RegionLastSeen.FILE_NAME), "r.1.2=notanumber\nr.3.4=10\nweird line\n");
		RegionLastSeen loaded = RegionLastSeen.load(tmp);
		assertEquals(10, loaded.get(3, 4, 0));
		assertEquals(0, loaded.get(1, 2, 0));
		assertEquals(0, RegionLastSeen.load(tmp.resolve("missing")).size());
	}

	@Test
	void scannerGroupsRegionEntityAndMapFilesPerDimension() throws IOException {
		Path server = tmp.resolve("server");
		Path overworld = server.resolve("minecraft").resolve("overworld");
		Path nether = server.resolve("minecraft").resolve("the_nether");
		write(overworld.resolve("region").resolve("r.0.0.mca"), 100);
		write(overworld.resolve("entities").resolve("r.0.0.mca"), 20);
		write(overworld.resolve("map").resolve("r.0.0.cps"), 5);
		write(overworld.resolve("region").resolve("r.-1.0.mca"), 10);
		write(overworld.resolve("region").resolve("not-a-region.txt"), 999);
		write(overworld.resolve(RegionLastSeen.FILE_NAME), 999);
		write(nether.resolve("region").resolve("r.0.0.mca"), 1);

		List<CacheScanner.CachedRegion> regions = CacheScanner.scan(server).stream()
				.sorted(Comparator.comparing((CacheScanner.CachedRegion r) -> r.dimensionDir().toString())
						.thenComparingInt(CacheScanner.CachedRegion::regionX))
				.toList();

		// sorted: overworld r.-1.0, overworld r.0.0, the_nether r.0.0
		assertEquals(3, regions.size());
		assertEquals(overworld, regions.get(0).dimensionDir());
		assertEquals(-1, regions.get(0).regionX());
		assertEquals(10, regions.get(0).bytes());
		assertEquals(125, regions.get(1).bytes());
		assertEquals(3, regions.get(1).files().size());
		assertEquals(nether, regions.get(2).dimensionDir());
		assertEquals(1, regions.get(2).bytes());
		assertEquals(0, CacheScanner.scan(tmp.resolve("nothing")).size());
		assertTrue(CacheScanner.totalBytes(server) >= 125 + 10 + 1);
	}

	private static void write(Path file, int bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, new byte[bytes]);
	}
}

package sk.tapusik.sixteentosave.map;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.selection.CacheHeaders;
import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.storage.RegionFileName;
import sk.tapusik.sixteentosave.storage.RegionFileReader;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Background threads that build region rasters (design §7.3). For the full-height view they
 * start from the summary index and only decode chunks whose summary is missing or older than
 * the region file; with a height limit they decode every chunk. Results go to a queue the map
 * screen drains each frame.
 */
final class TileWorkers {
	/** The region file timestamp is set a moment after the summary; allow for that. */
	private static final long TIMESTAMP_TOLERANCE_SECONDS = 10;

	record Job(int regionX, int regionZ, int clipY, long generation) {
	}

	/** The headers (null if there is no such file) feed the selection's universe and size estimate. */
	record Result(Job job, TileRasterizer.RegionRaster raster, int failedChunks, RegionHeader chunkHeader, RegionHeader entityHeader) {
	}

	private final MapSession session;
	private final CaptureService capture;
	private final TileQueue<Job> queue = new TileQueue<>();
	private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
	private final AtomicInteger busy = new AtomicInteger();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean running = true;

	TileWorkers(MapSession session, CaptureService capture, int threadCount) {
		this.session = session;
		this.capture = capture;

		for (int i = 0; i < Math.max(1, threadCount); i++) {
			Thread t = new Thread(this::run, SixteenToSave.NAME + "-map-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			threads.add(t);
		}
	}

	void request(Job job, double priority) {
		queue.request(job, priority);
	}

	void clearQueue() {
		queue.clear();
	}

	Result poll() {
		return results.poll();
	}

	/** Jobs waiting or running. */
	int pending() {
		return queue.size() + busy.get();
	}

	void shutdown() {
		running = false;
		queue.clear();

		for (Thread t : threads) {
			t.interrupt();
		}
	}

	private void run() {
		while (running) {
			Job job;

			try {
				job = queue.take(250, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				return;
			}

			if (job == null || job.generation() != session.generation()) {
				continue;
			}

			busy.incrementAndGet();

			try {
				Result result = build(job);

				if (result != null) {
					results.add(result);
				}
			} catch (RuntimeException | LinkageError e) {
				SixteenToSave.LOGGER.debug("Map tile {},{} failed: {}", job.regionX(), job.regionZ(), e.toString());
			} finally {
				busy.decrementAndGet();
			}
		}
	}

	private Result build(Job job) {
		int rx = job.regionX();
		int rz = job.regionZ();
		Path regionFile = session.dimensionDir.resolve(CacheLayout.REGION_FOLDER).resolve(RegionFileName.of(rx, rz));
		boolean full = job.clipY() == ChunkSummarizer.NO_CLIP;
		RegionSummary summary = full
				? RegionSummaryCodec.load(MapIndex.file(session.dimensionDir, rx, rz), rx, rz)
				: new RegionSummary(rx, rz);
		RegionHeader header = CacheHeaders.readOne(regionFile.getParent(), rx, rz);
		RegionHeader entityHeader = CacheHeaders.readOne(session.dimensionDir.resolve(CacheLayout.ENTITIES_FOLDER), rx, rz);

		if (header == null) {
			return new Result(job, TileRasterizer.raster(new RegionSummary(rx, rz), session.tints, true), 0, null, entityHeader);
		}

		Map<Integer, ChunkSummary> live = full ? session.live(rx, rz) : Map.of();
		Map<Integer, ChunkSummary> rebuilt = new HashMap<>();
		int failed = 0;

		for (int i = 0; i < RegionHeader.CHUNKS; i++) {
			if (!running || job.generation() != session.generation()) {
				return null;
			}

			if (!header.isPresent(i)) {
				summary.remove(i);
				continue;
			}

			ChunkSummary newest = live.get(i);

			if (newest != null) {
				summary.put(i, newest);
				continue;
			}

			if (full && summary.isCurrent(i, header, TIMESTAMP_TOLERANCE_SECONDS)) {
				continue;
			}

			int chunkX = rx * 32 + (i & 31);
			int chunkZ = rz * 32 + (i >> 5);

			try {
				byte[] nbt = RegionFileReader.read(regionFile, header, chunkX, chunkZ);

				if (nbt == null) {
					summary.remove(i);
					continue;
				}

				CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)));
				ChunkSummary s = ChunkSummarizer.summarize(tag, job.clipY(), session.biomeCodec, header.timestampSeconds(i));
				summary.put(i, s);

				if (full) {
					rebuilt.put(i, s);
				}
			} catch (IOException | RuntimeException e) {
				// Being written right now, or damaged: keep what we had (if anything) and try again next time.
				failed++;
			}
		}

		if (!rebuilt.isEmpty()) {
			capture.persistRebuiltSummaries(session.dimensionDir, rx, rz, rebuilt);
		}

		return new Result(job, TileRasterizer.raster(summary, session.tints, true), failed, header, entityHeader);
	}
}

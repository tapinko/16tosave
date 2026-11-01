package sk.tapusik.sixteentosave.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.map.MapIndex;
import sk.tapusik.sixteentosave.map.RegionSummaryCodec;
import sk.tapusik.sixteentosave.selection.CacheHeaders;
import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.ChunkTagAssembler;
import sk.tapusik.sixteentosave.storage.PathNames;
import sk.tapusik.sixteentosave.storage.RegionFileName;
import sk.tapusik.sixteentosave.storage.RegionFileReader;
import sk.tapusik.sixteentosave.storage.RegionHeader;

/**
 * Builds a singleplayer world from the cache (design §8.3) on its own thread: flush the cache,
 * copy the selected chunks (and their entities) into a hidden staging folder, write the world
 * files, then rename the folder into {@code saves/}. One bad chunk is skipped and reported, never
 * fatal. One export at a time.
 */
public final class WorldExporter {
	public enum State {
		FLUSHING, EXPORTING, FINISHING, DONE, CANCELLED, FAILED;

		public boolean finished() {
			return this == DONE || this == CANCELLED || this == FAILED;
		}
	}

	private static final long FLUSH_TIMEOUT_SECONDS = 60;
	private static final long RETRY_DELAY_MILLIS = 100;

	private static volatile WorldExporter current;

	private final ExportRequest request;
	private final CompletableFuture<Void> flushed;
	private final ExportReport report = new ExportReport();
	private final AtomicInteger done = new AtomicInteger();
	private volatile int total;
	private volatile State state = State.FLUSHING;
	private volatile String dimension;
	private volatile boolean cancelled;
	private volatile String error;
	private volatile Path worldDir;
	private final Consumer<WorldExporter> onFinished;

	// export thread only
	private long spawnChunk = Long.MIN_VALUE;
	private String spawnDimension;
	private int spawnY = ColumnHeight.UNKNOWN;
	private RegionFileWriter entityWriter;
	private boolean endIslandExported;
	private boolean endExitPortalActive;
	private volatile String endDragon = "not exported";

	private WorldExporter(ExportRequest request, CompletableFuture<Void> flushed, Consumer<WorldExporter> onFinished) {
		this.request = request;
		this.flushed = flushed;
		this.onFinished = onFinished;
	}

	/** The running or last finished export, or {@code null}. */
	public static WorldExporter current() {
		return current;
	}

	public static boolean isRunning() {
		WorldExporter e = current;
		return e != null && !e.state.finished();
	}

	/**
	 * Starts an export. {@code flushed} completes once the cache files are complete on disk;
	 * {@code onFinished} runs on the export thread at the end, whatever the outcome.
	 */
	public static synchronized WorldExporter start(ExportRequest request, CompletableFuture<Void> flushed,
			Consumer<WorldExporter> onFinished) {
		if (isRunning()) {
			throw new IllegalStateException("an export is already running");
		}

		WorldExporter exporter = new WorldExporter(request, flushed, onFinished);
		current = exporter;
		Thread thread = new Thread(exporter::run, SixteenToSave.NAME + "-export");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
		return exporter;
	}

	// ---- progress (any thread) ------------------------------------------------------------

	public State state() {
		return state;
	}

	public String dimension() {
		return dimension;
	}

	public int done() {
		return done.get();
	}

	public int total() {
		return total;
	}

	/** Valid once {@link #state()} is finished. */
	public ExportReport report() {
		return report;
	}

	public String error() {
		return error;
	}

	/** The new world's folder once the export is done. */
	public Path worldDir() {
		return worldDir;
	}

	public String worldName() {
		return request.worldName();
	}

	public void cancel() {
		cancelled = true;
	}

	// ---- the export -----------------------------------------------------------------------

	private static final class Cancelled extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private void checkCancelled() {
		if (cancelled) {
			throw new Cancelled();
		}
	}

	private void run() {
		Path staging = null;

		try {
			for (ChunkSelection s : request.selections().values()) {
				total += s.count();
			}

			removeStaleStaging(request.savesDir());
			waitForFlush();
			checkCancelled();

			String folder = ExportLayout.folderName(request.worldName(), name -> Files.exists(request.savesDir().resolve(name)));
			staging = request.savesDir().resolve(ExportLayout.stagingName(folder));
			Files.createDirectories(staging);
			chooseSpawnChunk();
			state = State.EXPORTING;

			for (Map.Entry<String, ChunkSelection> e : request.selections().entrySet()) {
				exportDimension(staging, e.getKey(), e.getValue());
			}

			state = State.FINISHING;
			writeWorldFiles(staging);
			checkCancelled();
			Path target = request.savesDir().resolve(folder);
			move(staging, target);
			staging = null;
			worldDir = target;
			state = State.DONE;
			SixteenToSave.LOGGER.info("{}: exported {} chunks ({} skipped) to {}", SixteenToSave.NAME, report.written(),
					report.skipped(), target);
		} catch (Cancelled c) {
			state = State.CANCELLED;
		} catch (IOException | RuntimeException | LinkageError e) {
			error = e.toString();
			state = State.FAILED;
			SixteenToSave.LOGGER.error("{}: export failed", SixteenToSave.NAME, e);
		} finally {
			if (staging != null) {
				deleteRecursively(staging);
			}

			try {
				onFinished.accept(this);
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.warn("{}: export notification failed: {}", SixteenToSave.NAME, e.toString());
			}
		}
	}

	private void waitForFlush() throws IOException {
		try {
			flushed.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			// export what is on disk
			SixteenToSave.LOGGER.warn("{}: the cache did not finish writing within {} s; exporting what is on disk",
					SixteenToSave.NAME, FLUSH_TIMEOUT_SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Cancelled();
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IOException("flushing the cache failed", e.getCause());
		}
	}

	/** The player's chunk if they stand in an exported chunk, else the middle of the largest selected area. */
	private void chooseSpawnChunk() {
		ExportRequest.PlayerPosition p = request.player();

		if (p != null) {
			ChunkSelection s = request.selections().get(p.dimension());
			int cx = (int) Math.floor(p.x()) >> 4;
			int cz = (int) Math.floor(p.z()) >> 4;

			if (s != null && s.contains(cx, cz)) {
				spawnDimension = p.dimension();
				spawnChunk = ChunkKey.pack(cx, cz);
				spawnY = (int) Math.floor(p.y());
				return;
			}
		}

		ChunkSelection overworld = request.selections().get(WorldFiles.OVERWORLD);
		String dim = overworld != null && !overworld.isEmpty() ? WorldFiles.OVERWORLD
				: request.selections().entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey)
						.findFirst().orElseThrow(() -> new IllegalStateException("nothing selected"));
		spawnDimension = dim;
		spawnChunk = SpawnPicker.pick(request.selections().get(dim));
	}

	private void exportDimension(Path staging, String dimensionId, ChunkSelection selection) {
		dimension = dimensionId;
		ExportReport.Dimension stats = report.dimension(dimensionId);
		stats.selected = selection.count();
		Path cacheDir = PathNames.dimensionDir(request.serverDir(), dimensionId);
		Path regionFolder = cacheDir.resolve(CacheLayout.REGION_FOLDER);
		Path entityFolder = cacheDir.resolve(CacheLayout.ENTITIES_FOLDER);
		Path worldDimension = ExportLayout.dimensionDir(staging, dimensionId);
		List<Long> regions = new ArrayList<>(selection.regionKeys());
		regions.sort(null);

		for (long region : regions) {
			int rx = ChunkKey.x(region);
			int rz = ChunkKey.z(region);
			long[] bits = selection.regionBits(rx, rz);
			List<long[]> chunks = new ArrayList<>();

			for (int w = 0; w < bits.length; w++) {
				for (long word = bits[w]; word != 0; word &= word - 1) {
					int i = (w << 6) + Long.numberOfTrailingZeros(word);
					chunks.add(new long[] { rx * 32 + (i & 31), rz * 32 + (i >> 5) });
				}
			}

			exportRegion(rx, rz, chunks, regionFolder, entityFolder, worldDimension, stats);
		}
	}

	private void exportRegion(int rx, int rz, List<long[]> chunks, Path regionFolder, Path entityFolder, Path worldDimension,
			ExportReport.Dimension stats) {
		RegionHeader chunkHeader = CacheHeaders.readOne(regionFolder, rx, rz);
		RegionHeader entityHeader = request.entities() ? CacheHeaders.readOne(entityFolder, rx, rz) : null;
		Path chunkFile = regionFolder.resolve(RegionFileName.of(rx, rz));
		Path entityFile = entityFolder.resolve(RegionFileName.of(rx, rz));
		Path outChunks = worldDimension.resolve("region").resolve(RegionFileName.of(rx, rz));
		Path outEntities = worldDimension.resolve("entities").resolve(RegionFileName.of(rx, rz));
		long now = System.currentTimeMillis() / 1000;
		RegionFileWriter chunkWriter = null;
		entityWriter = null;

		try {
			for (long[] c : chunks) {
				checkCancelled();
				int cx = (int) c[0];
				int cz = (int) c[1];

				try {
					if (chunkHeader == null) {
						stats.skip(ExportReport.Skip.NOT_CACHED);
						continue;
					}

					CompoundTag tag = readChunk(chunkFile, chunkHeader, cx, cz, stats);

					if (tag == null) {
						continue;
					}

					try {
						byte[] bytes = toBytes(tag);

						if (chunkWriter == null) {
							chunkWriter = new RegionFileWriter(outChunks);
						}

						chunkWriter.write(cx, cz, bytes, now);
						stats.written++;
					} catch (IOException | RuntimeException e) {
						if (e instanceof Cancelled cancel) {
							throw cancel;
						}

						stats.skip(ExportReport.Skip.WRITE_FAILED);
						SixteenToSave.LOGGER.warn("{}: could not export chunk {},{}: {}", SixteenToSave.NAME, cx, cz, e.toString());
						continue;
					}

					if (entityHeader != null) {
						exportEntities(entityFile, entityHeader, cx, cz, now, outEntities, stats);
					}
				} catch (RuntimeException e) {
					if (e instanceof Cancelled cancel) {
						throw cancel;
					}

					// a tag that parsed but isn't what a chunk should be
					stats.skip(ExportReport.Skip.CORRUPT);
					SixteenToSave.LOGGER.warn("{}: skipped chunk {},{}: {}", SixteenToSave.NAME, cx, cz, e.toString());
				} finally {
					done.incrementAndGet();
				}
			}
		} finally {
			IllegalStateException failure = close(chunkWriter, outChunks, null);
			failure = close(entityWriter, outEntities, failure);
			entityWriter = null;

			if (failure != null) {
				throw failure;
			}
		}
	}

	/** An entity chunk failing never costs the block chunk; it is only logged. */
	private void exportEntities(Path file, RegionHeader header, int cx, int cz, long now, Path out, ExportReport.Dimension stats) {
		try {
			CompoundTag entities = readEntities(file, header, cx, cz);

			if (entities != null) {
				byte[] bytes = toBytes(entities);

				if (entityWriter == null) {
					entityWriter = new RegionFileWriter(out);
				}

				entityWriter.write(cx, cz, bytes, now);
				stats.entityChunks++;
			}
		} catch (IOException | RuntimeException e) {
			if (e instanceof Cancelled cancel) {
				throw cancel;
			}

			SixteenToSave.LOGGER.warn("{}: could not export the entities of chunk {},{}: {}", SixteenToSave.NAME, cx, cz, e.toString());
		}
	}

	/** Closes {@code writer}; returns {@code earlier}, or the failure if this is the first one. */
	private static IllegalStateException close(RegionFileWriter writer, Path file, IllegalStateException earlier) {
		if (writer == null) {
			return earlier;
		}

		try {
			writer.close();
			return earlier;
		} catch (IOException e) {
			return earlier != null ? earlier : new IllegalStateException("could not finish " + file, e);
		}
	}

	/** The chunk prepared for the new world, or {@code null} (skipped, counted in {@code stats}). */
	private CompoundTag readChunk(Path file, RegionHeader header, int cx, int cz, ExportReport.Dimension stats) {
		if (!header.isPresent(RegionHeader.localIndex(cx, cz))) {
			stats.skip(ExportReport.Skip.NOT_CACHED);
			return null;
		}

		CompoundTag tag;

		try {
			tag = readTag(file, header, cx, cz);
		} catch (RegionFileReader.UnsupportedCompressionException e) {
			stats.skip(ExportReport.Skip.UNREADABLE);
			return null;
		} catch (IOException e) {
			stats.skip(e instanceof CorruptChunk ? ExportReport.Skip.CORRUPT : ExportReport.Skip.UNREADABLE);
			return null;
		}

		if (tag == null) {
			stats.skip(ExportReport.Skip.NOT_CACHED);
			return null;
		}

		if (tag.getIntOr("xPos", Integer.MIN_VALUE) != cx || tag.getIntOr("zPos", Integer.MIN_VALUE) != cz) {
			stats.skip(ExportReport.Skip.WRONG_POSITION);
			return null;
		}

		if (!tag.contains("sections")) {
			stats.skip(ExportReport.Skip.CORRUPT);
			return null;
		}

		CompoundTag meta = tag.getCompoundOrEmpty(ChunkTagAssembler.META_KEY);

		if (meta.getLongArray("incomplete").map(a -> a.length > 0).orElse(false)) {
			stats.incomplete.add(ChunkKey.pack(cx, cz));
		}

		tag.remove(ChunkTagAssembler.META_KEY);
		tag.putString("Status", "minecraft:full");
		putIfMissing(tag, "LastUpdate", () -> tag.putLong("LastUpdate", 0L));
		putIfMissing(tag, "InhabitedTime", () -> tag.putLong("InhabitedTime", 0L));
		putIfMissing(tag, "block_ticks", () -> tag.put("block_ticks", new ListTag()));
		putIfMissing(tag, "fluid_ticks", () -> tag.put("fluid_ticks", new ListTag()));
		putIfMissing(tag, "PostProcessing", () -> tag.put("PostProcessing", new ListTag()));

		if (stats.id.equals(WorldFiles.END) && cx >= -1 && cx <= 0 && cz >= -1 && cz <= 0) {
			// the four chunks around 0,0 hold the exit portal of the main island
			endIslandExported = true;
			endExitPortalActive |= containsBlock(tag, "minecraft:end_portal");
		}

		if (stats.id.equals(spawnDimension) && ChunkKey.pack(cx, cz) == spawnChunk && spawnY == ColumnHeight.UNKNOWN) {
			spawnY = spawnHeight(tag);
		}

		return tag;
	}

	private static void putIfMissing(CompoundTag tag, String key, Runnable put) {
		if (!tag.contains(key)) {
			put.run();
		}
	}

	/** Entity chunk for the new world, or {@code null} if there is none (or it is unusable). */
	private static CompoundTag readEntities(Path file, RegionHeader header, int cx, int cz) {
		if (!header.isPresent(RegionHeader.localIndex(cx, cz))) {
			return null;
		}

		try {
			CompoundTag tag = readTag(file, header, cx, cz);
			int[] position = tag == null ? null : tag.getIntArray("Position").orElse(null);

			if (position == null || position.length != 2 || position[0] != cx || position[1] != cz) {
				return null;
			}

			return tag;
		} catch (IOException e) {
			return null;
		}
	}

	private static final class CorruptChunk extends IOException {
		private static final long serialVersionUID = 1L;

		CorruptChunk(Throwable cause) {
			super(cause);
		}
	}

	/** Reads and parses a cached tag; one retry, as the cache may be written at this moment. */
	private static CompoundTag readTag(Path file, RegionHeader header, int cx, int cz) throws IOException {
		IOException last = null;
		RegionHeader h = header;

		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				byte[] nbt = RegionFileReader.read(file, h, cx, cz);

				if (nbt == null) {
					return null;
				}

				try {
					return NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)));
				} catch (IOException | RuntimeException e) {
					throw new CorruptChunk(e);
				}
			} catch (RegionFileReader.UnsupportedCompressionException e) {
				throw e;
			} catch (IOException e) {
				last = e;

				try {
					Thread.sleep(RETRY_DELAY_MILLIS);
					h = RegionHeader.read(file);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new Cancelled();
				} catch (IOException ignored) {
					// keep the old header
				}
			}
		}

		throw last;
	}

	private static byte[] toBytes(CompoundTag tag) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			NbtIo.write(tag, out);
		}

		return bytes.toByteArray();
	}

	private static boolean containsBlock(CompoundTag chunk, String block) {
		return chunk.getListOrEmpty("sections").compoundStream()
				.flatMap(section -> section.getCompoundOrEmpty("block_states").getListOrEmpty("palette").compoundStream())
				.anyMatch(state -> block.equals(state.getStringOr("Name", "")));
	}

	/** First free Y above the middle of the chunk: heightmap, else a scan of the sections. */
	private static int spawnHeight(CompoundTag chunk) {
		int minY = chunk.getIntOr("yPos", -4) * 16;
		long[] heightmap = chunk.getCompoundOrEmpty("Heightmaps").getLongArray("MOTION_BLOCKING").orElse(null);

		if (heightmap != null) {
			int y = ColumnHeight.fromHeightmap(heightmap, minY, 8, 8);

			if (y != ColumnHeight.UNKNOWN) {
				return y;
			}
		}

		List<CompoundTag> sections = new ArrayList<>(chunk.getListOrEmpty("sections").compoundStream().toList());
		sections.sort(Comparator.comparingInt((CompoundTag s) -> s.getByteOr("Y", (byte) 0)).reversed());

		for (CompoundTag section : sections) {
			CompoundTag states = section.getCompoundOrEmpty("block_states");
			List<String> palette = states.getListOrEmpty("palette").compoundStream().map(b -> b.getStringOr("Name", "")).toList();
			int top = ColumnHeight.topInSection(section.getByteOr("Y", (byte) 0), palette,
					states.getLongArray("data").orElse(null), 8, 8);

			if (top != ColumnHeight.UNKNOWN) {
				return top + 1;
			}
		}

		return ColumnHeight.UNKNOWN;
	}

	private Map<String, WorldFiles.Generator> generators() {
		return WorldFiles.generators(request.netherAndEnd(), request.selections().keySet());
	}

	/**
	 * Whether the exported End should start as "dragon killed" (design §8.4): a void End without
	 * the main island would otherwise get a dragon flying over nothing, and an exported island
	 * whose exit portal is active (the dragon was killed on the server) should stay that way. An
	 * island with an inactive portal (dragon alive on the server) and a vanilla-terrain End are
	 * left to the game: there the fight is real.
	 */
	private boolean endDragonKilled() {
		if (!request.selections().containsKey(WorldFiles.END)) {
			return false;
		}

		boolean voidEnd = generators().get(WorldFiles.END) == WorldFiles.Generator.VOID;
		return endIslandExported ? endExitPortalActive : voidEnd;
	}

	private void writeWorldFiles(Path staging) throws IOException {
		Files.createDirectories(ExportLayout.worldGenSettingsFile(staging).getParent());
		Nbt.writeCompressed(ExportLayout.worldGenSettingsFile(staging),
				WorldFiles.worldGenSettings(generators(), request.dataVersion()));

		if (request.selections().containsKey(WorldFiles.END)) {
			if (endDragonKilled()) {
				endDragon = DragonFightState.writeKilled(ExportLayout.dimensionDir(staging, WorldFiles.END), request.dataVersion())
						? "killed" : "game decides (could not write the state)";
			} else {
				endDragon = "game decides";
			}
		}

		writeIcon(staging);
		ExportMetadata.write(staging.resolve(ExportLayout.METADATA_FILE), request, generators(), endDragon, report);

		int cx = ChunkKey.x(spawnChunk);
		int cz = ChunkKey.z(spawnChunk);
		ExportRequest.PlayerPosition p = request.player();
		boolean atPlayer = p != null && p.dimension().equals(spawnDimension)
				&& ChunkKey.pack((int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4) == spawnChunk;
		int x = atPlayer ? (int) Math.floor(p.x()) : cx * 16 + 8;
		int z = atPlayer ? (int) Math.floor(p.z()) : cz * 16 + 8;
		// no height known (chunk skipped or empty): high up; the game looks for ground near the spawn
		int y = spawnY == ColumnHeight.UNKNOWN ? 128 : spawnY;
		WorldFiles.LevelSettings settings = new WorldFiles.LevelSettings(request.worldName(), request.gameMode(),
				request.allowCommands(), new WorldFiles.Spawn(spawnDimension, x, y, z, atPlayer ? p.yaw() : 0f),
				request.dataVersion(), request.versionName(), request.snapshot(), System.currentTimeMillis());
		// last: without level.dat the folder is not a world
		Nbt.writeCompressed(staging.resolve("level.dat"), WorldFiles.levelDat(settings));
	}

	/** {@code icon.png} for the world list; a failure only costs the icon. */
	private void writeIcon(Path staging) {
		try {
			ChunkSelection selection = request.selections().get(spawnDimension);
			Path cacheDir = PathNames.dimensionDir(request.serverDir(), spawnDimension);
			int[] pixels = WorldIcon.render(selection, spawnChunk,
					(rx, rz) -> RegionSummaryCodec.load(MapIndex.file(cacheDir, rx, rz), rx, rz), request.iconColors());
			PngWriter.write(staging.resolve("icon.png"), WorldIcon.SIZE, WorldIcon.SIZE, pixels);
		} catch (IOException | RuntimeException | LinkageError e) {
			SixteenToSave.LOGGER.warn("{}: could not draw the world icon: {}", SixteenToSave.NAME, e.toString());
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to);
		}
	}

	/** Staging folders left by an export that was interrupted (game closed or crashed). */
	private static void removeStaleStaging(Path saves) {
		if (!Files.isDirectory(saves)) {
			return;
		}

		try (Stream<Path> list = Files.list(saves)) {
			for (Path p : list.filter(p -> ExportLayout.isStaging(p.getFileName().toString())).toList()) {
				deleteRecursively(p);
			}
		} catch (IOException e) {
			SixteenToSave.LOGGER.warn("{}: could not look for old export folders in {}: {}", SixteenToSave.NAME, saves, e.toString());
		}
	}

	private static void deleteRecursively(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		} catch (IOException e) {
			SixteenToSave.LOGGER.warn("{}: could not delete {}: {}", SixteenToSave.NAME, dir, e.toString());
		}
	}
}

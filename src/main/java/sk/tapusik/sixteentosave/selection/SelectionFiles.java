package sk.tapusik.sixteentosave.selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.storage.AtomicFiles;
import sk.tapusik.sixteentosave.storage.CacheMaintenance;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.EvictionPlanner;

/**
 * Selection files (design §8.1):
 * {@code {"format":1,"dimension":…,"name":…,"savedAt":…,"chunks":N,"regions":{"rx,rz":"<base64>"}}}.
 * All reads and writes run in order on one background thread; the futures complete there.
 */
public final class SelectionFiles {
	public static final int FORMAT = 1;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, SixteenToSave.NAME + "-selection");
		t.setDaemon(true);
		return t;
	});

	/** A named selection as listed in the load dialog. */
	public record Named(String name, Path file, int chunks, long savedAtMillis) {
	}

	private SelectionFiles() {
	}

	// ---- async API (render thread) --------------------------------------------------------

	/** The current selection of a dimension; empty if there is none or it can't be read. */
	public static CompletableFuture<ChunkSelection> loadCurrent(Path dimensionDir) {
		return load(SelectionPaths.current(dimensionDir));
	}

	public static CompletableFuture<ChunkSelection> load(Path file) {
		return CompletableFuture.supplyAsync(() -> {
			ChunkSelection s = read(file);
			return s == null ? new ChunkSelection() : s;
		}, IO);
	}

	/** Saves the current selection; an empty one removes the file. {@code snapshot} must not be changed afterwards. */
	public static CompletableFuture<Void> saveCurrent(Path dimensionDir, String dimensionId, ChunkSelection snapshot) {
		Path file = SelectionPaths.current(dimensionDir);
		return CompletableFuture.runAsync(() -> {
			try {
				if (snapshot.isEmpty()) {
					Files.deleteIfExists(file);
				} else {
					write(file, dimensionId, null, snapshot);
				}
			} catch (IOException e) {
				SixteenToSave.LOGGER.warn("{}: could not save the selection to {}: {}", SixteenToSave.NAME, file, e.toString());
			}
		}, IO);
	}

	/** Saves a named selection; completes exceptionally if it could not be written. */
	public static CompletableFuture<Path> saveNamed(Path dimensionDir, String dimensionId, String name, ChunkSelection snapshot) {
		Path file = SelectionPaths.named(dimensionDir, name);
		return CompletableFuture.supplyAsync(() -> {
			try {
				write(file, dimensionId, name.strip(), snapshot);
				return file;
			} catch (IOException e) {
				SixteenToSave.LOGGER.warn("{}: could not save selection {}: {}", SixteenToSave.NAME, file, e.toString());
				throw new IllegalStateException(e);
			}
		}, IO);
	}

	/** Whether a named selection with this name (or one that maps to the same file) exists. */
	public static CompletableFuture<Boolean> namedExists(Path dimensionDir, String name) {
		Path file = SelectionPaths.named(dimensionDir, name);
		return CompletableFuture.supplyAsync(() -> Files.isRegularFile(file), IO);
	}

	/** Named selections of a dimension, newest first. Unreadable files are listed with -1 chunks. */
	public static CompletableFuture<List<Named>> listNamed(Path dimensionDir) {
		return CompletableFuture.supplyAsync(() -> {
			List<Named> out = new ArrayList<>();
			Path folder = SelectionPaths.namedFolder(dimensionDir);

			if (!Files.isDirectory(folder)) {
				return out;
			}

			try (Stream<Path> files = Files.list(folder)) {
				for (Path f : files.filter(p -> SelectionPaths.dimensionDirOf(p) != null && Files.isRegularFile(p)).toList()) {
					out.add(describe(f));
				}
			} catch (IOException | RuntimeException e) {
				SixteenToSave.LOGGER.warn("{}: could not list {}: {}", SixteenToSave.NAME, folder, e.toString());
			}

			out.sort(Comparator.comparingLong(Named::savedAtMillis).reversed().thenComparing(Named::name));
			return out;
		}, IO);
	}

	public static CompletableFuture<Void> delete(Path file) {
		return CompletableFuture.runAsync(() -> {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				SixteenToSave.LOGGER.warn("{}: could not delete {}: {}", SixteenToSave.NAME, file, e.toString());
			}
		}, IO);
	}

	// ---- blocking (background threads) ----------------------------------------------------

	/**
	 * Eviction ids ({@link EvictionPlanner.RegionUsage#id}) of every region that holds a chunk of
	 * the current or a named selection of any dimension of this server. Blocking.
	 */
	public static Set<String> pinnedRegionIds(Path serverDir) {
		Set<String> out = new HashSet<>();

		try {
			for (Path file : SelectionPaths.findAll(serverDir)) {
				ChunkSelection s = read(file);

				if (s == null) {
					continue;
				}

				String dimension = CacheMaintenance.dimensionLabel(serverDir, SelectionPaths.dimensionDirOf(file));

				for (long region : s.regionKeys()) {
					out.add(EvictionPlanner.RegionUsage.id(dimension, ChunkKey.x(region), ChunkKey.z(region)));
				}
			}
		} catch (IOException | RuntimeException e) {
			SixteenToSave.LOGGER.warn("{}: could not read the selections of {}: {}", SixteenToSave.NAME, serverDir, e.toString());
		}

		return out;
	}

	private static JsonObject readJson(Path file) throws IOException {
		JsonElement root = JsonParser.parseString(Files.readString(file));

		if (!root.isJsonObject()) {
			throw new JsonParseException("not an object");
		}

		JsonObject o = root.getAsJsonObject();
		JsonElement format = o.get("format");

		if (format == null || !format.isJsonPrimitive() || format.getAsInt() != FORMAT) {
			throw new JsonParseException("unknown format " + format);
		}

		return o;
	}

	/** {@code null} if the file is missing or unreadable (logged). */
	private static ChunkSelection read(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}

		try {
			return decode(readJson(file));
		} catch (IOException | RuntimeException e) {
			SixteenToSave.LOGGER.warn("{}: could not read selection {}: {}", SixteenToSave.NAME, file, e.toString());
			return null;
		}
	}

	private static ChunkSelection decode(JsonObject o) {
		Map<String, String> regions = new LinkedHashMap<>();
		JsonElement r = o.get("regions");

		if (r != null && r.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : r.getAsJsonObject().entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					regions.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}

		return SelectionCodec.decode(regions);
	}

	private static Named describe(Path file) {
		String fallback = file.getFileName().toString().replaceFirst("\\.json$", "");

		try {
			JsonObject o = readJson(file);
			JsonElement name = o.get("name");
			JsonElement savedAt = o.get("savedAt");
			int chunks = decode(o).count();
			long millis;

			try {
				millis = savedAt == null ? Files.getLastModifiedTime(file).toMillis() : Instant.parse(savedAt.getAsString()).toEpochMilli();
			} catch (RuntimeException e) {
				millis = Files.getLastModifiedTime(file).toMillis();
			}

			return new Named(name != null && name.isJsonPrimitive() ? name.getAsString() : fallback, file, chunks, millis);
		} catch (IOException | RuntimeException e) {
			return new Named(fallback, file, -1, 0);
		}
	}

	private static void write(Path file, String dimensionId, String name, ChunkSelection snapshot) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMAT);
		o.addProperty("dimension", dimensionId);

		if (name != null) {
			o.addProperty("name", name);
		}

		o.addProperty("savedAt", Instant.now().toString());
		o.addProperty("chunks", snapshot.count());
		JsonObject regions = new JsonObject();
		SelectionCodec.encode(snapshot).forEach(regions::addProperty);
		o.add("regions", regions);
		Files.createDirectories(file.getParent());
		AtomicFiles.writeString(file, GSON.toJson(o));
	}
}

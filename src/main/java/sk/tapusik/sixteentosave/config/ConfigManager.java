package sk.tapusik.sixteentosave.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.storage.AtomicFiles;

/**
 * Loads and saves {@link ModConfig} as plain, hand-editable JSON. A missing or broken file means
 * defaults (and a warning); keys this version doesn't know are written back untouched.
 * Saving happens on a background thread.
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;
	private final ExecutorService io;
	private final ModConfig config;
	/** The file as last read, so unknown keys survive a save. Only touched on the client thread. */
	private JsonObject original;

	private ConfigManager(Path file, ModConfig config, JsonObject original) {
		this.file = file;
		this.config = config;
		this.original = original;
		this.io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, SixteenToSave.NAME + "-config");
			t.setDaemon(true);
			return t;
		});
	}

	public static ConfigManager load(Path file) {
		JsonObject root = new JsonObject();
		ModConfig config = new ModConfig();
		boolean writeDefaults = !Files.exists(file);

		if (!writeDefaults) {
			try {
				JsonElement parsed = JsonParser.parseString(Files.readString(file));

				if (parsed.isJsonObject()) {
					root = parsed.getAsJsonObject();
					ModConfig read = GSON.fromJson(root, ModConfig.class);

					if (read != null) {
						config = read;
					}
				} else {
					SixteenToSave.LOGGER.warn("{} is not a JSON object; using defaults", file);
				}
			} catch (IOException | JsonParseException | IllegalStateException e) {
				SixteenToSave.LOGGER.warn("Could not read {} ({}); using defaults", file, e.toString());
				root = new JsonObject();
				config = new ModConfig();
			}
		}

		config.sanitize();
		ConfigManager manager = new ConfigManager(file, config, root);

		if (writeDefaults) {
			manager.save();
		}

		return manager;
	}

	public ModConfig get() {
		return config;
	}

	/** Serialises on the calling thread (cheap) and writes the file in the background. */
	public void save() {
		JsonObject merged = original.deepCopy();
		mergeInto(merged, GSON.toJsonTree(config).getAsJsonObject());
		original = merged;
		String json = GSON.toJson(merged);

		io.execute(() -> {
			try {
				AtomicFiles.writeString(file, json + System.lineSeparator());
			} catch (IOException e) {
				SixteenToSave.LOGGER.warn("Could not save {}: {}", file, e.toString());
			}
		});
	}

	/** Copies every key of {@code source} into {@code target}, recursing into objects. */
	private static void mergeInto(JsonObject target, JsonObject source) {
		for (Map.Entry<String, JsonElement> e : source.entrySet()) {
			JsonElement existing = target.get(e.getKey());

			if (existing != null && existing.isJsonObject() && e.getValue().isJsonObject()) {
				mergeInto(existing.getAsJsonObject(), e.getValue().getAsJsonObject());
			} else {
				target.add(e.getKey(), e.getValue());
			}
		}
	}
}

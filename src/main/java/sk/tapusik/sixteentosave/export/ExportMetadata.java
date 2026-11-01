package sk.tapusik.sixteentosave.export;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sk.tapusik.sixteentosave.storage.AtomicFiles;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/** {@code saves/<world>/16tosave-export.json} (design §8.5): where the world came from and what was skipped. */
final class ExportMetadata {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private ExportMetadata() {
	}

	static void write(Path file, ExportRequest request, Map<String, WorldFiles.Generator> generators, String endDragon,
			ExportReport report) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("format", 1);
		root.addProperty("exportedAt", Instant.now().toString());

		JsonObject source = new JsonObject();
		source.addProperty("serverId", request.serverId());
		source.addProperty("serverName", request.serverName());
		root.add("source", source);

		JsonObject game = new JsonObject();
		game.addProperty("minecraft", request.versionName());
		game.addProperty("dataVersion", request.dataVersion());
		game.addProperty("mod", request.modVersion());
		root.add("version", game);

		JsonObject options = new JsonObject();
		JsonObject generatorOptions = new JsonObject();

		for (Map.Entry<String, WorldFiles.Generator> e : generators.entrySet()) {
			generatorOptions.addProperty(e.getKey(), e.getValue().name().toLowerCase(Locale.ROOT));
		}

		options.add("generators", generatorOptions);
		options.addProperty("endDragon", endDragon);
		options.addProperty("gameMode", request.gameMode().name().toLowerCase(Locale.ROOT));
		options.addProperty("allowCommands", request.allowCommands());
		options.addProperty("entities", request.entities());
		root.add("options", options);

		JsonObject dimensions = new JsonObject();

		for (ExportReport.Dimension d : report.dimensions()) {
			JsonObject o = new JsonObject();
			o.addProperty("selected", d.selected);
			o.addProperty("written", d.written);
			o.addProperty("entityChunks", d.entityChunks);
			JsonObject skipped = new JsonObject();

			for (Map.Entry<ExportReport.Skip, Integer> e : d.skipped.entrySet()) {
				skipped.addProperty(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue());
			}

			o.add("skipped", skipped);
			// chunks with containers whose contents the client never saw (they are empty in this world)
			JsonArray incomplete = new JsonArray();

			for (long key : d.incomplete) {
				JsonArray pos = new JsonArray();
				pos.add(ChunkKey.x(key));
				pos.add(ChunkKey.z(key));
				incomplete.add(pos);
			}

			o.add("incompleteContainerChunks", incomplete);
			dimensions.add(d.id, o);
		}

		root.add("dimensions", dimensions);
		AtomicFiles.writeString(file, GSON.toJson(root));
	}
}

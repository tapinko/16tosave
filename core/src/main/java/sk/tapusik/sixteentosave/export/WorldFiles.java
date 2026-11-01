package sk.tapusik.sixteentosave.export;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The world-level files of an exported world (design §8.4), built as plain {@link Nbt} trees.
 * The structure follows vanilla 26.x: {@code level.dat} without world-gen settings or game rules
 * (since 26.1), and {@code data/minecraft/world_gen_settings.dat} as saved data
 * ({@code {data: …, DataVersion}}). Reference: a {@code level.dat} and
 * {@code world_gen_settings.dat} written by a 26.1 server (MCA Selector's generator templates).
 */
public final class WorldFiles {
	/** {@code level.dat} "version": the Anvil format id vanilla checks. */
	public static final int ANVIL_VERSION = 19133;
	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";
	public static final String END = "minecraft:the_end";
	public static final List<String> VANILLA_DIMENSIONS = List.of(OVERWORLD, NETHER, END);

	/** What unselected chunks of a dimension become. */
	public enum Generator {
		/** nothing: superflat with one layer of air, biome {@code the_void}, no features */
		VOID,
		/** the vanilla terrain generator of that dimension (Nether and End only) */
		VANILLA
	}

	public enum GameMode {
		SURVIVAL(0), CREATIVE(1), ADVENTURE(2), SPECTATOR(3);

		public final int id;

		GameMode(int id) {
			this.id = id;
		}
	}

	/** World spawn (26.x: a position in any dimension, with a facing). */
	public record Spawn(String dimension, int x, int y, int z, float yaw) {
	}

	/** {@code versionName}/{@code snapshot} describe the running game, e.g. "26.3", false. */
	public record LevelSettings(String levelName, GameMode gameMode, boolean allowCommands, Spawn spawn,
			int dataVersion, String versionName, boolean snapshot, long lastPlayedMillis) {
	}

	private WorldFiles() {
	}

	/** {@code level.dat}: {@code {Data: {…}}}. */
	public static Map<String, Object> levelDat(LevelSettings s) {
		Map<String, Object> difficulty = Nbt.compound();
		difficulty.put("difficulty", "normal");
		difficulty.put("hardcore", Nbt.bool(false));
		difficulty.put("locked", Nbt.bool(false));

		Map<String, Object> spawn = Nbt.compound();
		spawn.put("pos", new int[] { s.spawn().x(), s.spawn().y(), s.spawn().z() });
		spawn.put("dimension", s.spawn().dimension());
		spawn.put("yaw", s.spawn().yaw());
		spawn.put("pitch", 0.0f);

		Map<String, Object> version = Nbt.compound();
		version.put("Id", s.dataVersion());
		version.put("Name", s.versionName());
		version.put("Series", "main");
		version.put("Snapshot", Nbt.bool(s.snapshot()));

		Map<String, Object> dataPacks = Nbt.compound();
		dataPacks.put("Enabled", List.of("vanilla"));
		dataPacks.put("Disabled", List.of());

		Map<String, Object> data = Nbt.compound();
		data.put("LevelName", s.levelName());
		data.put("DataVersion", s.dataVersion());
		data.put("Version", version);
		data.put("version", ANVIL_VERSION);
		data.put("initialized", Nbt.bool(true));
		data.put("GameType", s.gameMode().id);
		data.put("allowCommands", Nbt.bool(s.allowCommands()));
		data.put("difficulty_settings", difficulty);
		data.put("spawn", spawn);
		data.put("Time", 0L);
		data.put("LastPlayed", s.lastPlayedMillis());
		data.put("WasModded", Nbt.bool(false));
		data.put("DataPacks", dataPacks);

		Map<String, Object> root = Nbt.compound();
		root.put("Data", data);
		return root;
	}

	/**
	 * The generator of each vanilla dimension. The overworld is always void. The Nether and the
	 * End get {@code chosen} only if they are exported, else vanilla terrain: vanilla treats any
	 * other Nether/End generator as "experimental settings" and warns when the world is opened
	 * (the overworld may be customised freely), so void is used only where it is wanted.
	 */
	public static Map<String, Generator> generators(Generator chosen, Collection<String> exportedDimensions) {
		Map<String, Generator> out = new LinkedHashMap<>();
		out.put(OVERWORLD, Generator.VOID);
		out.put(NETHER, exportedDimensions.contains(NETHER) ? chosen : Generator.VANILLA);
		out.put(END, exportedDimensions.contains(END) ? chosen : Generator.VANILLA);
		return out;
	}

	/** Whether vanilla will show its "experimental settings" warning for these generators. */
	public static boolean experimental(Map<String, Generator> generators) {
		return generators.get(NETHER) != Generator.VANILLA || generators.get(END) != Generator.VANILLA;
	}

	/**
	 * {@code data/minecraft/world_gen_settings.dat} with the given generators (see
	 * {@link #generators}). Seed 0, no structures, no bonus chest.
	 */
	public static Map<String, Object> worldGenSettings(Map<String, Generator> generators, int dataVersion) {
		Map<String, Object> dimensions = Nbt.compound();
		dimensions.put(OVERWORLD, stem(OVERWORLD, voidGenerator()));
		dimensions.put(NETHER, stem(NETHER, generators.get(NETHER) == Generator.VANILLA
				? noiseGenerator("minecraft:nether", multiNoise("minecraft:nether"))
				: voidGenerator()));
		dimensions.put(END, stem(END, generators.get(END) == Generator.VANILLA
				? noiseGenerator("minecraft:end", single("minecraft:the_end"))
				: voidGenerator()));

		Map<String, Object> data = Nbt.compound();
		data.put("seed", 0L);
		data.put("generate_structures", Nbt.bool(false));
		data.put("bonus_chest", Nbt.bool(false));
		data.put("dimensions", dimensions);
		return savedData(data, dataVersion);
	}

	/** The saved-data file envelope: {@code {data: …, DataVersion: …}}. */
	public static Map<String, Object> savedData(Map<String, Object> data, int dataVersion) {
		Map<String, Object> root = Nbt.compound();
		root.put("data", data);
		root.put("DataVersion", dataVersion);
		return root;
	}

	private static Map<String, Object> stem(String dimensionType, Map<String, Object> generator) {
		Map<String, Object> stem = Nbt.compound();
		stem.put("type", dimensionType);
		stem.put("generator", generator);
		return stem;
	}

	/** Like vanilla's "The Void" preset, but without its features (no stone platform at 0,0). */
	static Map<String, Object> voidGenerator() {
		Map<String, Object> layer = Nbt.compound();
		layer.put("block", "minecraft:air");
		layer.put("height", 1);

		Map<String, Object> settings = Nbt.compound();
		settings.put("biome", "minecraft:the_void");
		settings.put("features", Nbt.bool(false));
		settings.put("lakes", Nbt.bool(false));
		settings.put("layers", List.of(layer));
		settings.put("structure_overrides", List.of());

		Map<String, Object> generator = Nbt.compound();
		generator.put("type", "minecraft:flat");
		generator.put("settings", settings);
		return generator;
	}

	private static Map<String, Object> noiseGenerator(String settings, Map<String, Object> biomeSource) {
		Map<String, Object> generator = Nbt.compound();
		generator.put("type", "minecraft:noise");
		generator.put("settings", settings);
		generator.put("biome_source", biomeSource);
		return generator;
	}

	private static Map<String, Object> multiNoise(String preset) {
		Map<String, Object> source = Nbt.compound();
		source.put("type", "minecraft:multi_noise");
		source.put("preset", preset);
		return source;
	}

	private static Map<String, Object> single(String type) {
		Map<String, Object> source = Nbt.compound();
		source.put("type", type);
		return source;
	}
}

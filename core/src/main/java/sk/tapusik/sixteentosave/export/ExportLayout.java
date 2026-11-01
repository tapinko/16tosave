package sk.tapusik.sixteentosave.export;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Folder names and paths of an exported world (design §8.3): the world folder under
 * {@code saves/}, the hidden staging folder it is built in, and the 26.x dimension folders.
 */
public final class ExportLayout {
	public static final String STAGING_SUFFIX = ".16tosave-tmp";
	public static final String METADATA_FILE = "16tosave-export.json";
	private static final int MAX_NAME = 64;
	private static final Set<String> WINDOWS_RESERVED = Set.of(
			"con", "prn", "aux", "nul",
			"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
			"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private ExportLayout() {
	}

	/**
	 * A folder name for a world called {@code displayName}: characters that aren't allowed in file
	 * names become {@code _}, and {@code " (2)"}, {@code " (3)"}… is added until {@code taken} says no.
	 */
	public static String folderName(String displayName, Predicate<String> taken) {
		StringBuilder out = new StringBuilder();
		String raw = displayName == null ? "" : displayName.strip();

		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean bad = c < 32 || c == 127 || "/\\:*?\"<>|".indexOf(c) >= 0;
			out.append(bad ? '_' : c);
		}

		String base = out.toString();

		while (base.endsWith(".") || base.endsWith(" ")) {
			base = base.substring(0, base.length() - 1);
		}

		while (base.startsWith(".")) {
			base = base.substring(1);
		}

		if (base.length() > MAX_NAME) {
			base = base.substring(0, MAX_NAME).strip();
		}

		if (base.isEmpty()) {
			base = "World";
		}

		int dot = base.indexOf('.');
		String stem = (dot < 0 ? base : base.substring(0, dot)).toLowerCase(Locale.ROOT);

		if (WINDOWS_RESERVED.contains(stem)) {
			base = "_" + base;
		}

		String name = base;

		for (int n = 2; taken.test(name) || taken.test(stagingName(name)); n++) {
			name = base + " (" + n + ")";
		}

		return name;
	}

	/** Hidden folder the world is built in; it never has a level.dat until it is complete. */
	public static String stagingName(String folderName) {
		return "." + folderName + STAGING_SUFFIX;
	}

	public static boolean isStaging(String folderName) {
		return folderName.startsWith(".") && folderName.endsWith(STAGING_SUFFIX);
	}

	/** {@code minecraft:the_nether} → {@code world/dimensions/minecraft/the_nether} (26.x layout). */
	public static Path dimensionDir(Path world, String dimensionId) {
		int colon = dimensionId.indexOf(':');
		String namespace = colon < 0 ? "minecraft" : dimensionId.substring(0, colon);
		Path dir = world.resolve("dimensions").resolve(namespace);

		for (String segment : (colon < 0 ? dimensionId : dimensionId.substring(colon + 1)).split("/")) {
			if (!segment.isEmpty()) {
				dir = dir.resolve(segment);
			}
		}

		return dir;
	}

	public static Path worldGenSettingsFile(Path world) {
		return world.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
	}
}

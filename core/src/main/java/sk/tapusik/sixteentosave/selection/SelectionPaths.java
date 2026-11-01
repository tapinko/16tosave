package sk.tapusik.sixteentosave.selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import sk.tapusik.sixteentosave.storage.PathNames;

/**
 * Where selections live inside a dimension's cache folder (design §6): {@code selection.json}
 * (the current one) and {@code selections/<name>.json} (named ones).
 */
public final class SelectionPaths {
	public static final String CURRENT_FILE = "selection.json";
	public static final String NAMED_FOLDER = "selections";
	private static final String SUFFIX = ".json";
	private static final int MAX_DEPTH = 8;

	private SelectionPaths() {
	}

	public static Path current(Path dimensionDir) {
		return dimensionDir.resolve(CURRENT_FILE);
	}

	public static Path namedFolder(Path dimensionDir) {
		return dimensionDir.resolve(NAMED_FOLDER);
	}

	/** File of a named selection; names that differ only in case or punctuation share a file. */
	public static Path named(Path dimensionDir, String name) {
		return namedFolder(dimensionDir).resolve(PathNames.safeSegment(name.strip()) + SUFFIX);
	}

	/** The dimension folder a selection file belongs to, or {@code null} if {@code file} is not a selection file. */
	public static Path dimensionDirOf(Path file) {
		Path name = file.getFileName();
		Path parent = file.getParent();

		if (name == null || parent == null) {
			return null;
		}

		if (name.toString().equals(CURRENT_FILE)) {
			return parent;
		}

		Path parentName = parent.getFileName();

		if (name.toString().endsWith(SUFFIX) && parentName != null && parentName.toString().equals(NAMED_FOLDER)) {
			return parent.getParent();
		}

		return null;
	}

	/** Every selection file under a server's cache folder. */
	public static List<Path> findAll(Path serverDir) throws IOException {
		if (!Files.isDirectory(serverDir)) {
			return List.of();
		}

		try (Stream<Path> walk = Files.walk(serverDir, MAX_DEPTH)) {
			return walk.filter(p -> dimensionDirOf(p) != null && Files.isRegularFile(p)).sorted().toList();
		}
	}
}

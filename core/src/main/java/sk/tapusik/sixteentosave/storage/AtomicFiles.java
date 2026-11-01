package sk.tapusik.sixteentosave.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small file helpers shared by the config, the last-seen index and later the exporter. */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	/** Moves {@code source} over {@code target}, atomically where the file system supports it. */
	public static void replace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Writes {@code content} to {@code target} via a sibling temp file, so readers never see half a file. */
	public static void writeString(Path target, String content) throws IOException {
		Path parent = target.toAbsolutePath().getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, content);
		replace(tmp, target);
	}
}

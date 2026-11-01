package sk.tapusik.sixteentosave.storage;

import java.nio.file.Path;

/**
 * Where the cache lives (design §6):
 * {@code .minecraft/16tosave/<server-id>/<namespace>/<path>/{region,entities}/r.X.Z.mca}.
 */
public final class CacheLayout {
	public static final String REGION_FOLDER = "region";
	public static final String ENTITIES_FOLDER = "entities";

	private final Path root;

	public CacheLayout(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	/** {@code serverId} must already be a safe folder name (see {@code ServerIdSanitizer}). */
	public Path serverDir(String serverId) {
		return root.resolve(PathNames.safeSegment(serverId));
	}

	public Path dimensionDir(String serverId, String dimensionId) {
		return PathNames.dimensionDir(serverDir(serverId), dimensionId);
	}
}

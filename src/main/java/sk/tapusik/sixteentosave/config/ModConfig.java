package sk.tapusik.sixteentosave.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings stored in {@code config/16tosave.json}. Field names are the JSON keys; keep them
 * stable. Unknown keys in the file are preserved by {@link ConfigManager}.
 */
public final class ModConfig {
	public int configVersion = 1;
	public Capture capture = new Capture();
	/** Per-server overrides, keyed by server id (the cache folder name). */
	public Map<String, ServerSettings> servers = new LinkedHashMap<>();
	public Cache cache = new Cache();
	public MapSettings map = new MapSettings();

	public static final class Capture {
		public boolean enabled = true;
		public boolean entities = true;
		public boolean observeContainers = true;
		/** Dimension id → enabled. The key {@code "*"} is the default for dimensions not listed. */
		public Map<String, Boolean> dimensions = new LinkedHashMap<>(Map.of("*", true));
		public int liveIntervalSeconds = 30;
		public int tickBudgetMicros = 1500;
		public int queueMiB = 64;
		/**
		 * A snapshot replaces a chunk's stored entities only if the whole chunk was this close
		 * (blocks). Kept below Spigot/Paper's default tracking range for item frames and
		 * paintings (32), so entities the server didn't send are never deleted.
		 */
		public int entityReplaceRange = 24;
	}

	public static final class ServerSettings {
		public boolean capture = true;
	}

	public static final class MapSettings {
		/** Background threads that draw map tiles. */
		public int tileWorkers = 2;
		/** GPU memory for map tiles; least recently used tiles are dropped above it. */
		public int vramMiB = 192;
	}

	public static final class Cache {
		/** Size cap per server; least recently seen regions are evicted above it. */
		public long maxSizeMiB = 4096;
	}

	public boolean isDimensionEnabled(String dimensionId) {
		Boolean explicit = capture.dimensions.get(dimensionId);

		if (explicit != null) {
			return explicit;
		}

		return capture.dimensions.getOrDefault("*", true);
	}

	public boolean isServerEnabled(String serverId) {
		ServerSettings s = servers.get(serverId);
		return s == null || s.capture;
	}

	public ServerSettings server(String serverId) {
		return servers.computeIfAbsent(serverId, k -> new ServerSettings());
	}

	/** Replaces missing sections (null after a hand edit) and clamps numbers to sane ranges. */
	void sanitize() {
		if (capture == null) {
			capture = new Capture();
		}

		if (capture.dimensions == null) {
			capture.dimensions = new LinkedHashMap<>(Map.of("*", true));
		}

		if (servers == null) {
			servers = new LinkedHashMap<>();
		}

		servers.values().removeIf(v -> v == null);

		if (cache == null) {
			cache = new Cache();
		}

		capture.liveIntervalSeconds = clamp(capture.liveIntervalSeconds, 5, 3600);
		capture.tickBudgetMicros = clamp(capture.tickBudgetMicros, 100, 20_000);
		capture.queueMiB = clamp(capture.queueMiB, 8, 1024);
		capture.entityReplaceRange = clamp(capture.entityReplaceRange, 0, 512);
		cache.maxSizeMiB = Math.max(64, cache.maxSizeMiB);

		if (map == null) {
			map = new MapSettings();
		}

		map.tileWorkers = clamp(map.tileWorkers, 1, 8);
		map.vramMiB = clamp(map.vramMiB, 32, 4096);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

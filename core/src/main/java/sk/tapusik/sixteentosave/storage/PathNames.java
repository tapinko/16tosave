package sk.tapusik.sixteentosave.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/** Turns server addresses and dimension ids into folder names that are safe on every OS. */
public final class PathNames {
	static final int MAX_SEGMENT_LENGTH = 80;

	private static final Set<String> WINDOWS_RESERVED = Set.of(
			"con", "prn", "aux", "nul",
			"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
			"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private PathNames() {
	}

	/**
	 * Lower-cases the input and replaces everything outside {@code [a-z0-9._-]} with {@code _}.
	 * The result is never empty, never "." or "..", never hidden (leading dot), never a reserved
	 * Windows device name, never ends with a dot, and is at most {@value #MAX_SEGMENT_LENGTH} chars.
	 */
	public static String safeSegment(String raw) {
		String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(lower.length());

		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
			out.append(allowed ? c : '_');
		}

		String s = out.toString();

		if (s.isEmpty()) {
			return "_";
		}

		if (s.charAt(0) == '.') {
			s = "_" + s;
		}

		if (s.endsWith(".")) {
			s = s + "_";
		}

		int dot = s.indexOf('.');
		String stem = dot < 0 ? s : s.substring(0, dot);

		if (WINDOWS_RESERVED.contains(stem)) {
			s = "_" + s;
		}

		if (s.length() > MAX_SEGMENT_LENGTH) {
			CRC32 crc = new CRC32();
			crc.update(s.getBytes(StandardCharsets.UTF_8));
			String suffix = "-" + Long.toHexString(crc.getValue());
			s = s.substring(0, MAX_SEGMENT_LENGTH - suffix.length()) + suffix;
		}

		return s;
	}

	/**
	 * {@code "minecraft:overworld"} → {@code base/minecraft/overworld}. A path with slashes
	 * ({@code "ns:a/b"}) becomes nested folders; every segment goes through {@link #safeSegment}.
	 */
	public static Path dimensionDir(Path base, String dimensionId) {
		int colon = dimensionId.indexOf(':');
		String namespace = colon < 0 ? "minecraft" : dimensionId.substring(0, colon);
		String path = colon < 0 ? dimensionId : dimensionId.substring(colon + 1);

		Path result = base.resolve(safeSegment(namespace));
		boolean any = false;

		for (String segment : path.split("/")) {
			if (!segment.isEmpty()) {
				result = result.resolve(safeSegment(segment));
				any = true;
			}
		}

		return any ? result : result.resolve("_");
	}
}

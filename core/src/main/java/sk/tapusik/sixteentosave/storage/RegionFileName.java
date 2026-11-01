package sk.tapusik.sixteentosave.storage;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Region file names: {@code r.<x>.<z>.mca}. */
public final class RegionFileName {
	private static final Pattern PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

	public record Coords(int regionX, int regionZ) {
	}

	private RegionFileName() {
	}

	public static String of(int regionX, int regionZ) {
		return "r." + regionX + "." + regionZ + ".mca";
	}

	public static Optional<Coords> parse(String fileName) {
		Matcher m = PATTERN.matcher(fileName);

		if (!m.matches()) {
			return Optional.empty();
		}

		try {
			return Optional.of(new Coords(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
}

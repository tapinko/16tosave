package sk.tapusik.sixteentosave.capture;

import java.util.Locale;

import sk.tapusik.sixteentosave.storage.PathNames;

/**
 * Builds the {@code <server-id>} folder name. The same server must always map to the same id,
 * whether the player typed {@code example.com} or {@code Example.com:25565}.
 */
public final class ServerIdSanitizer {
	public static final int DEFAULT_PORT = 25565;

	private ServerIdSanitizer() {
	}

	public static String fromAddress(String address) {
		String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);

		if (a.isEmpty()) {
			return "unknown";
		}

		String host = a;
		int port = DEFAULT_PORT;

		if (a.startsWith("[")) {
			// [IPv6]:port
			int end = a.indexOf(']');

			if (end > 0) {
				host = a.substring(1, end);
				String rest = a.substring(end + 1);

				if (rest.startsWith(":")) {
					port = parsePort(rest.substring(1));
				}
			}
		} else {
			int colon = a.lastIndexOf(':');

			// Exactly one colon means host:port; more than one is a bare IPv6 address.
			if (colon > 0 && a.indexOf(':') == colon) {
				host = a.substring(0, colon);
				port = parsePort(a.substring(colon + 1));
			}
		}

		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}

		if (host.isEmpty()) {
			host = "unknown";
		}

		return PathNames.safeSegment(host + "_" + port);
	}

	public static String realm(String realmName) {
		String name = realmName == null || realmName.isBlank() ? "unknown" : realmName.trim();
		return PathNames.safeSegment("realms_" + name);
	}

	private static int parsePort(String s) {
		try {
			int port = Integer.parseInt(s.trim());
			return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
		} catch (NumberFormatException e) {
			return DEFAULT_PORT;
		}
	}
}

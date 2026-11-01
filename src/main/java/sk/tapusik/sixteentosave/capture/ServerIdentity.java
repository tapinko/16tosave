package sk.tapusik.sixteentosave.capture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Which server the client is connected to.
 *
 * @param id          stable cache folder name, e.g. {@code play.example.com_25565}
 * @param displayName the server-list name, for the UI
 * @param address     what the player connected to, for the export metadata
 */
public record ServerIdentity(String id, String displayName, String address) {
	/** The current multiplayer server, or {@code null} in singleplayer or when not connected. */
	public static ServerIdentity current(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			return null;
		}

		ClientPacketListener connection = client.getConnection();

		if (connection == null) {
			return null;
		}

		ServerData data = connection.getServerData();

		if (data == null) {
			return new ServerIdentity("unknown", "unknown", "");
		}

		if (data.isRealm()) {
			return new ServerIdentity(ServerIdSanitizer.realm(data.name), data.name, "realms");
		}

		return new ServerIdentity(ServerIdSanitizer.fromAddress(data.ip), data.name, data.ip);
	}
}

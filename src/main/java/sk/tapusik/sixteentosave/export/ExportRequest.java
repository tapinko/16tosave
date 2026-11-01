package sk.tapusik.sixteentosave.export;

import java.nio.file.Path;
import java.util.Map;

import sk.tapusik.sixteentosave.map.TileRasterizer;
import sk.tapusik.sixteentosave.selection.ChunkSelection;

/**
 * Everything an export needs, collected on the render thread before it starts (design §8.2).
 *
 * @param selections chunks to export per dimension id (copies; not changed afterwards)
 * @param netherAndEnd outside the selection in an exported Nether/End (not exported ones are always vanilla)
 * @param player     where the player is, for the spawn; {@code null} if unknown
 * @param iconColors column colours for the world icon (biome tints of the server's registries)
 */
public record ExportRequest(
		String worldName,
		Path savesDir,
		Path serverDir,
		String serverId,
		String serverName,
		Map<String, ChunkSelection> selections,
		WorldFiles.Generator netherAndEnd,
		WorldFiles.GameMode gameMode,
		boolean allowCommands,
		boolean entities,
		PlayerPosition player,
		TileRasterizer.ColumnColors iconColors,
		int dataVersion,
		String versionName,
		boolean snapshot,
		String modVersion) {

	public record PlayerPosition(String dimension, double x, double y, double z, float yaw) {
	}
}

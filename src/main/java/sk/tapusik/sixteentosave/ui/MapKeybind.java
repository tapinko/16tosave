package sk.tapusik.sixteentosave.ui;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.map.MapScreen;

/** "Open map" key, unbound by default (Options → Controls → Key Binds → 16tosave). */
public final class MapKeybind {
	private static KeyMapping openMap;

	private MapKeybind() {
	}

	public static void register(ConfigManager config, CaptureService capture) {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SixteenToSave.MOD_ID, "main"));
		openMap = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.sixteentosave.open_map",
				InputConstants.Type.KEYBOARD,
				InputConstants.UNKNOWN.getValue(),
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMap.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new MapScreen(null, config, capture));
				}
			}
		});
	}
}

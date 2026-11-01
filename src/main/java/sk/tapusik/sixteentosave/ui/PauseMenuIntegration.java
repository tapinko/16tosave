package sk.tapusik.sixteentosave.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.map.MapScreen;

/**
 * Adds a "16tosave" button (opens the map) to the top-right corner of the pause menu. It is placed in the corner
 * rather than inside the menu's own layout, so a changed pause-menu layout cannot break it.
 */
public final class PauseMenuIntegration {
	private static final int WIDTH = 90;
	private static final int MARGIN = 4;

	private PauseMenuIntegration() {
	}

	public static void register(ConfigManager config, CaptureService capture) {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof PauseScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("sixteentosave.pause.button"),
								button -> client.gui.setScreen(new MapScreen(screen, config, capture)))
						.pos(scaledWidth - WIDTH - MARGIN, MARGIN)
						.size(WIDTH, 20)
						.build());
			}
		});
	}
}

package sk.tapusik.sixteentosave;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.ui.MapKeybind;
import sk.tapusik.sixteentosave.ui.PauseMenuIntegration;

public final class SixteenToSave implements ClientModInitializer {
	/** Fabric mod id; must start with a letter, so it can't be "16tosave". */
	public static final String MOD_ID = "sixteentosave";
	/** Display name, and the name of the data folders. */
	public static final String NAME = "16tosave";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private static ConfigManager config;
	private static CaptureService capture;

	@Override
	public void onInitializeClient() {
		FabricLoader loader = FabricLoader.getInstance();
		Path gameDir = loader.getGameDir();

		config = ConfigManager.load(loader.getConfigDir().resolve(NAME + ".json"));
		capture = new CaptureService(config, new CacheLayout(gameDir.resolve(NAME)));
		capture.register();
		PauseMenuIntegration.register(config, capture);
		MapKeybind.register(config, capture);

		LOGGER.info("{} initialised; cache folder {}", NAME, gameDir.resolve(NAME));
	}

	public static ConfigManager config() {
		return config;
	}

	public static CaptureService capture() {
		return capture;
	}
}

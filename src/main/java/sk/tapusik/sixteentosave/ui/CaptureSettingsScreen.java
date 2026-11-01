package sk.tapusik.sixteentosave.ui;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.config.ModConfig;
import sk.tapusik.sixteentosave.storage.CacheWriter;

/**
 * Capture settings and status, opened from the map's "Settings" button.
 */
public final class CaptureSettingsScreen extends Screen {
	private static final int ROW = 24;
	private static final int WIDTH = 260;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final long MEASURING = -2;

	private final Screen parent;
	private final ConfigManager config;
	private final CaptureService capture;
	private volatile long cacheBytes = MEASURING;
	private int statusTop;

	public CaptureSettingsScreen(Screen parent, ConfigManager config, CaptureService capture) {
		super(Component.translatable("sixteentosave.settings.title"));
		this.parent = parent;
		this.config = config;
		this.capture = capture;
	}

	@Override
	protected void init() {
		ModConfig c = config.get();
		CaptureService.Status status = capture.status();
		int x = this.width / 2 - WIDTH / 2;
		int y = 32;

		this.addRenderableWidget(toggle("sixteentosave.settings.capture", "",
				() -> c.capture.enabled, v -> c.capture.enabled = v, x, y));
		y += ROW;

		String serverId = status.serverId();
		Button server = toggle("sixteentosave.settings.server", serverId == null ? "-" : status.serverName(),
				() -> serverId != null && c.isServerEnabled(serverId), v -> c.server(serverId).capture = v, x, y);
		server.active = serverId != null;
		this.addRenderableWidget(server);
		y += ROW;

		String dimensionId = status.dimensionId();
		Button dimension = toggle("sixteentosave.settings.dimension", dimensionId == null ? "-" : dimensionId,
				() -> dimensionId != null && c.isDimensionEnabled(dimensionId), v -> c.capture.dimensions.put(dimensionId, v), x, y);
		dimension.active = dimensionId != null;
		this.addRenderableWidget(dimension);
		y += ROW;

		this.addRenderableWidget(toggle("sixteentosave.settings.entities", "",
				() -> c.capture.entities, v -> c.capture.entities = v, x, y));
		y += ROW;

		this.addRenderableWidget(toggle("sixteentosave.settings.containers", "",
				() -> c.capture.observeContainers, v -> c.capture.observeContainers = v, x, y));
		y += ROW;

		Button clear = Button.builder(Component.translatable("sixteentosave.settings.clear"), button -> confirmClear(status))
				.pos(x, y)
				.size(WIDTH, 20)
				.build();
		clear.active = serverId != null;
		this.addRenderableWidget(clear);
		y += ROW + 6;

		statusTop = y;

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.pos(this.width / 2 - 100, this.height - 26)
				.size(200, 20)
				.build());

		cacheBytes = MEASURING;
		capture.measureCurrentServer(bytes -> cacheBytes = bytes);
	}

	private Button toggle(String key, String argument, BooleanSupplier get, Consumer<Boolean> set, int x, int y) {
		return Button.builder(label(key, argument, get.getAsBoolean()), button -> {
					boolean value = !get.getAsBoolean();
					set.accept(value);
					config.save();
					button.setMessage(label(key, argument, value));
				})
				.pos(x, y)
				.size(WIDTH, 20)
				.build();
	}

	private static Component label(String key, String argument, boolean on) {
		Component state = on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
		return argument.isEmpty() ? Component.translatable(key, state) : Component.translatable(key, argument, state);
	}

	private void confirmClear(CaptureService.Status status) {
		this.minecraft.gui.setScreen(new ConfirmScreen(
				yes -> {
					if (yes) {
						cacheBytes = MEASURING;
						capture.clearCurrentServer(() -> cacheBytes = 0);
					}

					this.minecraft.gui.setScreen(this);
				},
				Component.translatable("sixteentosave.settings.clear.title"),
				Component.translatable("sixteentosave.settings.clear.message", status.serverName(), status.serverId()),
				Component.translatable("sixteentosave.settings.clear.yes"),
				CommonComponents.GUI_CANCEL));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title.getString(), this.width / 2, 12, WHITE);

		CaptureService.Status status = capture.status();
		CacheWriter.Stats stats = status.writer();
		int x = this.width / 2 - WIDTH / 2;
		int y = statusTop;
		int line = this.font.lineHeight + 3;

		String where = status.serverId() == null
				? tr("sixteentosave.status.no_server")
				: tr("sixteentosave.status.server", status.serverName(), status.serverId());
		graphics.text(this.font, where, x, y, WHITE);
		y += line;

		if (status.dimensionId() != null) {
			graphics.text(this.font, tr(status.active() ? "sixteentosave.status.capturing" : "sixteentosave.status.not_capturing",
					status.dimensionId(), status.trackedChunks()), x, y, status.active() ? WHITE : GREY);
			y += line;
		}

		graphics.text(this.font, tr("sixteentosave.status.queue", stats.queued(), mib(stats.queuedBytes())), x, y, GREY);
		y += line;
		graphics.text(this.font, tr("sixteentosave.status.written", stats.chunksWritten(), stats.entityChunksWritten(),
				stats.dropped(), stats.failed()), x, y, GREY);
		y += line;

		long bytes = cacheBytes;
		String size = bytes == MEASURING ? tr("sixteentosave.status.measuring") : bytes < 0 ? "?" : mib(bytes);
		graphics.text(this.font, tr("sixteentosave.status.cache", size, config.get().cache.maxSizeMiB), x, y, GREY);
		y += line;

		if (status.serverId() != null) {
			graphics.text(this.font, ".minecraft/" + SixteenToSave.NAME + "/" + status.serverId(), x, y, GREY);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private static String mib(long bytes) {
		return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
	}
}

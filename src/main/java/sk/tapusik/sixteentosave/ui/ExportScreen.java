package sk.tapusik.sixteentosave.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.export.ExportLayout;
import sk.tapusik.sixteentosave.export.ExportReport;
import sk.tapusik.sixteentosave.export.ExportRequest;
import sk.tapusik.sixteentosave.export.WorldExporter;
import sk.tapusik.sixteentosave.export.WorldFiles;
import sk.tapusik.sixteentosave.map.BiomeTints;
import sk.tapusik.sixteentosave.map.BlockColors;
import sk.tapusik.sixteentosave.map.ChunkSummary;
import sk.tapusik.sixteentosave.map.TileRasterizer;
import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.selection.SelectionFiles;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.PathNames;

/** Export options (design §8.2): world name, dimensions, Nether/End generator, game mode, commands, entities. */
public final class ExportScreen extends Screen {
	private static final int WIDTH = 280;
	private static final int ROW = 24;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;

	private final Screen parent;
	private final CaptureService capture;
	private final Path serverDir;
	private final String serverId;
	private final String serverName;
	private final Map<String, ChunkSelection> selections = new LinkedHashMap<>();
	private final Map<String, Boolean> chosen = new LinkedHashMap<>();
	private boolean loaded;
	private String name;
	private String folder = "";
	private WorldFiles.Generator generator = WorldFiles.Generator.VOID;
	private WorldFiles.GameMode gameMode = WorldFiles.GameMode.CREATIVE;
	private boolean allowCommands = true;
	private boolean entities = true;
	private Button exportButton;
	private Button generatorButton;

	public ExportScreen(Screen parent, CaptureService capture) {
		super(Component.translatable("sixteentosave.export.title"));
		this.parent = parent;
		this.capture = capture;
		CaptureService.Status status = capture.status();
		this.serverDir = capture.currentServerDir();
		this.serverId = status.serverId();
		this.serverName = status.serverName() == null ? "" : status.serverName();
		this.name = (serverName.isBlank() ? SixteenToSave.NAME : serverName) + " " + java.time.LocalDate.now();
		loadSelections();
	}

	/** The saved current selection of each vanilla dimension (the map saved its own before opening this). */
	private void loadSelections() {
		if (serverDir == null) {
			loaded = true;
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		List<CompletableFuture<ChunkSelection>> futures = WorldFiles.VANILLA_DIMENSIONS.stream()
				.map(id -> SelectionFiles.loadCurrent(PathNames.dimensionDir(serverDir, id)))
				.toList();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> minecraft.execute(() -> {
			for (int i = 0; i < futures.size(); i++) {
				ChunkSelection s = futures.get(i).getNow(null);

				if (s != null && !s.isEmpty()) {
					String id = WorldFiles.VANILLA_DIMENSIONS.get(i);
					selections.put(id, s);
					chosen.put(id, true);
				}
			}

			loaded = true;

			if (minecraft.gui.screen() == this) {
				this.rebuildWidgets();
			}
		}));
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = 34;

		EditBox nameBox = new EditBox(this.font, x, y, WIDTH, 20, Component.translatable("sixteentosave.export.name"));
		nameBox.setMaxLength(64);
		nameBox.setValue(name);
		nameBox.setResponder(text -> {
			name = text;
			updateFolder();
		});
		this.addRenderableWidget(nameBox);
		y += ROW + 12;

		for (Map.Entry<String, ChunkSelection> e : selections.entrySet()) {
			String id = e.getKey();
			int count = e.getValue().count();
			this.addRenderableWidget(Button.builder(dimensionLabel(id, count), button -> {
				chosen.put(id, !chosen.get(id));
				button.setMessage(dimensionLabel(id, count));
				updateButtons();
			}).pos(x, y).size(WIDTH, 20).build());
			y += ROW;
		}

		generatorButton = this.addRenderableWidget(Button.builder(generatorLabel(), button -> {
			generator = generator == WorldFiles.Generator.VOID ? WorldFiles.Generator.VANILLA : WorldFiles.Generator.VOID;
			button.setMessage(generatorLabel());
		}).pos(x, y).size(WIDTH, 20).build());
		y += ROW;

		this.addRenderableWidget(Button.builder(gameModeLabel(), button -> {
			gameMode = WorldFiles.GameMode.values()[(gameMode.ordinal() + 1) % WorldFiles.GameMode.values().length];
			button.setMessage(gameModeLabel());
		}).pos(x, y).size(WIDTH / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(onOff("sixteentosave.export.commands", allowCommands), button -> {
			allowCommands = !allowCommands;
			button.setMessage(onOff("sixteentosave.export.commands", allowCommands));
		}).pos(x + WIDTH / 2 + 2, y).size(WIDTH / 2 - 2, 20).build());
		y += ROW;

		this.addRenderableWidget(Button.builder(onOff("sixteentosave.export.entities", entities), button -> {
			entities = !entities;
			button.setMessage(onOff("sixteentosave.export.entities", entities));
		}).pos(x, y).size(WIDTH, 20).build());

		int bottom = this.height - 28;
		exportButton = this.addRenderableWidget(Button.builder(Component.translatable("sixteentosave.export.start"), button -> start())
				.pos(x, bottom).size(WIDTH / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.pos(x + WIDTH / 2 + 2, bottom).size(WIDTH / 2 - 2, 20).build());
		updateFolder();
		updateButtons();
	}

	private Component dimensionLabel(String id, int count) {
		Component state = chosen.getOrDefault(id, false) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
		return Component.translatable("sixteentosave.export.dimension", id, count, state);
	}

	private Component generatorLabel() {
		return Component.translatable("sixteentosave.export.generator",
				Component.translatable("sixteentosave.export.generator." + (generator == WorldFiles.Generator.VOID ? "void" : "vanilla")));
	}

	private Component gameModeLabel() {
		return Component.translatable("sixteentosave.export.game_mode",
				Component.translatable("sixteentosave.export.game_mode." + gameMode.name().toLowerCase(java.util.Locale.ROOT)));
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key, on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	/** The folder the world would get; checking which names are taken is disk I/O, so off-thread. */
	private void updateFolder() {
		Minecraft minecraft = Minecraft.getInstance();
		Path saves = minecraft.getLevelSource().getBaseDir();
		String wanted = name;

		if (wanted.isBlank()) {
			folder = "";
			updateButtons();
			return;
		}

		CompletableFuture.supplyAsync(() -> ExportLayout.folderName(wanted, n -> Files.exists(saves.resolve(n))))
				.thenAccept(result -> minecraft.execute(() -> {
					if (wanted.equals(name)) {
						folder = result;
					}
				}));
		updateButtons();
	}

	private Map<String, ChunkSelection> chosenSelections() {
		Map<String, ChunkSelection> out = new LinkedHashMap<>();

		for (Map.Entry<String, ChunkSelection> e : selections.entrySet()) {
			if (chosen.getOrDefault(e.getKey(), false)) {
				out.put(e.getKey(), e.getValue());
			}
		}

		return out;
	}

	private void updateButtons() {
		if (exportButton != null) {
			exportButton.active = loaded && !name.isBlank() && !chosenSelections().isEmpty() && !WorldExporter.isRunning();
		}

		if (generatorButton != null) {
			// only exported Nether/End can be void; the others always get vanilla terrain
			Map<String, ChunkSelection> chosenNow = chosenSelections();
			generatorButton.active = chosenNow.containsKey(WorldFiles.NETHER) || chosenNow.containsKey(WorldFiles.END);
		}
	}

	/** Whether Minecraft will warn about "experimental settings" when the world is opened. */
	private boolean experimental() {
		return WorldFiles.experimental(WorldFiles.generators(generator, chosenSelections().keySet()));
	}

	private void start() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		String playerDimension = capture.currentDimensionId();
		ExportRequest.PlayerPosition position = player == null || playerDimension == null ? null
				: new ExportRequest.PlayerPosition(playerDimension, player.getX(), player.getY(), player.getZ(), player.getYRot());
		String modVersion = FabricLoader.getInstance().getModContainer(SixteenToSave.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");

		// biome tints need the server's registries, so they are taken now, on the render thread
		TileRasterizer.ColumnColors iconColors = minecraft.level != null
				? new BiomeTints(minecraft.level.registryAccess())
				: (chunk, column, x, z) -> BlockColors.rgb(ChunkSummary.mapColorId(chunk.color()[column]));

		ExportRequest request = new ExportRequest(name.strip(), minecraft.getLevelSource().getBaseDir(), serverDir, serverId, serverName,
				chosenSelections(), generator, gameMode, allowCommands, entities, position, iconColors,
				SharedConstants.getCurrentVersion().dataVersion().version(), SharedConstants.getCurrentVersion().name(),
				!SharedConstants.getCurrentVersion().stable(), modVersion);
		ChunkSelection here = request.selections().get(playerDimension);
		WorldExporter exporter = WorldExporter.start(request,
				capture.flushCurrentServer(here == null ? null : key -> here.contains(ChunkKey.x(key), ChunkKey.z(key))),
				finished -> minecraft.execute(() -> toast(minecraft, finished)));
		minecraft.gui.setScreen(new ExportProgressScreen(parent, exporter));
	}

	/** Result notice, also when the progress screen was closed. */
	private static void toast(Minecraft minecraft, WorldExporter exporter) {
		ExportReport report = exporter.report();
		Component title;
		Component message;

		switch (exporter.state()) {
			case DONE -> {
				title = Component.translatable("sixteentosave.export.toast.done", exporter.worldName());
				message = Component.translatable("sixteentosave.export.toast.counts", report.written(), report.skipped());
			}
			case CANCELLED -> {
				title = Component.translatable("sixteentosave.export.toast.cancelled");
				message = Component.literal(exporter.worldName());
			}
			default -> {
				title = Component.translatable("sixteentosave.export.toast.failed");
				message = Component.literal(String.valueOf(exporter.error()));
			}
		}

		SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int x = this.width / 2 - WIDTH / 2;
		graphics.centeredText(this.font, this.title.getString(), this.width / 2, 12, WHITE);
		graphics.text(this.font, tr("sixteentosave.export.folder", folder.isEmpty() ? "-" : "saves/" + folder), x, 34 + 22, GREY);

		if (!loaded) {
			graphics.centeredText(this.font, tr("sixteentosave.selection.load.reading"), this.width / 2, this.height - 44, GREY);
		} else if (selections.isEmpty()) {
			graphics.centeredText(this.font, tr("sixteentosave.export.nothing"), this.width / 2, this.height - 44, GREY);
		} else if (WorldExporter.isRunning()) {
			graphics.centeredText(this.font, tr("sixteentosave.export.running"), this.width / 2, this.height - 44, GREY);
		} else if (experimental()) {
			graphics.centeredText(this.font, tr("sixteentosave.export.experimental"), this.width / 2, this.height - 44, 0xFFFFC040);
		} else {
			graphics.centeredText(this.font, tr("sixteentosave.export.note"), this.width / 2, this.height - 44, GREY);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}
}

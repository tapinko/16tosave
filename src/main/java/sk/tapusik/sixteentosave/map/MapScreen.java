package sk.tapusik.sixteentosave.map;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import sk.tapusik.sixteentosave.capture.CaptureService;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.export.WorldExporter;
import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.selection.SelectionState;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.PathNames;
import sk.tapusik.sixteentosave.ui.CaptureSettingsScreen;
import sk.tapusik.sixteentosave.ui.ExportProgressScreen;
import sk.tapusik.sixteentosave.ui.ExportScreen;
import sk.tapusik.sixteentosave.ui.SelectionListScreen;
import sk.tapusik.sixteentosave.ui.SelectionNameScreen;

/**
 * The map of the cache (design §7): right-drag to pan, wheel to zoom, left-drag to select
 * chunks, four levels of detail, a height limit on the right edge. Everything slow happens in
 * {@link TileWorkers}; a frame only blits textures that are ready and uploads a few new ones.
 */
public final class MapScreen extends Screen {
	private static final int TOP = 52;
	private static final int ROW_2 = 28;
	private static final int BOTTOM = 14;
	private static final int SLIDER_WIDTH = 16;
	private static final long CLIP_DEBOUNCE_NANOS = 350_000_000L;
	private static final long LIVE_REFRESH_NANOS = 1_000_000_000L;
	private static final long UPLOAD_PIXELS_PER_FRAME = 600_000;
	private static final double ZOOM_STEP = 1.2;
	private static final double CLICK_SLOP = 4;
	private static final int BUTTON_PADDING = 16;
	private static final int[] RADII = { 4, 8, 16, 32, 64, 128 };

	private static final int BACKGROUND = 0xFF0E0E10;
	private static final int TOOLBAR = 0xC0000000;
	private static final int GRID_CHUNK = 0x28FFFFFF;
	private static final int GRID_REGION = 0x60FFFFFF;
	private static final int SLIDER_TRACK = 0xC0202020;
	private static final int SLIDER_FILL = 0xC0406080;
	private static final int SLIDER_HANDLE = 0xFFE0E0E0;
	private static final int PLAYER = 0xFFFF4040;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int SELECTION = 0x6040A0FF;
	private static final int DRAG_REPLACE = 0x30FFFFFF;
	private static final int DRAG_ADD = 0x3040FF40;
	private static final int DRAG_SUBTRACT = 0x40FF4040;
	private static final int HINT_BACKGROUND = 0xA0000000;
	private static final int INCOMPLETE = 0xD0FFA020;

	private record Live(Path dimensionDir, int chunkX, int chunkZ, ChunkSummary summary) {
	}

	private final Screen parent;
	private final ConfigManager config;
	private final CaptureService capture;

	// kept while the screen object lives (also across a visit to the settings screen)
	private final MapView view = new MapView();
	private Lod lod;
	private boolean viewPlaced;
	private Path serverDir;
	private String dimensionId;
	private List<String> dimensions = List.of();
	private final Map<Long, TileRasterizer.RegionRaster> fullRasters = new HashMap<>();
	private final Map<Long, TileRasterizer.RegionRaster> clipRasters = new HashMap<>();
	private Set<Long> regions = new HashSet<>();
	private int minY = -64;
	private int maxY = 319;
	private Integer roofY;
	private int clipY = ChunkSummarizer.NO_CLIP;
	private int sliderY = ChunkSummarizer.NO_CLIP;
	private long sliderChangedAt;
	private SelectionState selection;
	private int radiusIndex = 2;

	// only while shown
	private MapSession session;
	private TileWorkers workers;
	private MapTextures textures;
	private MapIndex.Listener listener;
	private final ConcurrentLinkedQueue<Live> liveUpdates = new ConcurrentLinkedQueue<>();
	private volatile Set<Long> listedRegions;
	private volatile List<String> listedDimensions;
	private final Set<Long> requested = new HashSet<>();
	private final Map<Long, Long> dirtySince = new HashMap<>();
	private boolean panning;
	private boolean draggingSlider;
	private boolean selecting;
	private boolean dragMoved;
	private ChunkSelection.Mode dragMode = ChunkSelection.Mode.REPLACE;
	private double dragStartX;
	private double dragStartY;
	private double dragEndX;
	private double dragEndY;
	private Button dimensionButton;
	private Button centerButton;
	private Button roofButton;
	private Button selectAllButton;
	private Button invertButton;
	private Button clearButton;
	private Button undoButton;
	private Button radiusButton;
	private Button aroundMeButton;
	private Button aroundSpawnButton;
	private Button saveButton;
	private Button loadButton;
	private Button exportButton;

	public MapScreen(Screen parent, ConfigManager config, CaptureService capture) {
		super(Component.translatable("sixteentosave.map.title"));
		this.parent = parent;
		this.config = config;
		this.capture = capture;
	}

	// ---- lifecycle ------------------------------------------------------------------------

	@Override
	protected void init() {
		if (serverDir == null) {
			serverDir = capture.currentServerDir();
		}

		if (dimensionId == null) {
			dimensionId = capture.currentDimensionId();
			updateHeights();
		}

		if (session == null) {
			openSession();
		}

		int x = 4;
		Component settings = Component.translatable("sixteentosave.map.settings");
		int w = buttonWidth(settings);
		this.addRenderableWidget(Button.builder(settings,
						button -> this.minecraft.gui.setScreen(new CaptureSettingsScreen(this, config, capture)))
				.pos(x, 4).size(w, 20).build());
		x += w + 4;

		w = Math.max(170, buttonWidth(dimensionLabel()));
		dimensionButton = this.addRenderableWidget(Button.builder(dimensionLabel(), button -> cycleDimension())
				.pos(x, 4).size(w, 20).build());
		dimensionButton.active = dimensions.size() > 1;
		x += w + 4;

		Component center = Component.translatable("sixteentosave.map.center");
		w = buttonWidth(center);
		centerButton = this.addRenderableWidget(Button.builder(center, button -> centerOnPlayer())
				.pos(x, 4).size(w, 20).build());
		centerButton.active = isCurrentDimension();
		x += w + 4;

		// wide enough for both of its labels
		w = Math.max(buttonWidth(Component.translatable("sixteentosave.map.below_roof")),
				buttonWidth(Component.translatable("sixteentosave.map.full_height")));
		roofButton = this.addRenderableWidget(Button.builder(roofLabel(), button -> toggleRoof())
				.pos(x, 4).size(w, 20).build());
		roofButton.active = session != null;
		x += w + 4;

		Component export = Component.translatable("sixteentosave.map.export");
		w = buttonWidth(export);
		exportButton = this.addRenderableWidget(Button.builder(export, button -> openExport())
				.pos(x, 4).size(w, 20).build());
		exportButton.active = session != null;

		addSelectionRow();
		updateSelectionButtons();

		view.setViewport(0, TOP, this.width - SLIDER_WIDTH, this.height - TOP - BOTTOM);

		if (!viewPlaced) {
			view.setScale(1);
			viewPlaced = centerOnPlayer();
		}
	}

	@Override
	public void removed() {
		closeSession();
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private void openSession() {
		ClientLevel level = Minecraft.getInstance().level;

		if (serverDir == null || dimensionId == null || level == null) {
			return;
		}

		Path dimensionDir = PathNames.dimensionDir(serverDir, dimensionId);
		session = new MapSession(dimensionDir, dimensionId, level.registryAccess());
		textures = new MapTextures((long) config.get().map.vramMiB << 20);
		workers = new TileWorkers(session, capture, config.get().map.tileWorkers);
		listener = (dir, chunkX, chunkZ, summary) -> {
			if (dir.equals(dimensionDir)) {
				liveUpdates.add(new Live(dir, chunkX, chunkZ, summary));
			}
		};
		capture.addMapListener(listener);
		capture.flushMapIndex();
		requested.clear();

		if (selection == null || !selection.dimensionDir().equals(dimensionDir)) {
			if (selection != null) {
				selection.flush();
			}

			selection = new SelectionState(dimensionDir, dimensionId);
		} else {
			selection.rescan();
		}

		Path server = serverDir;
		CompletableFuture.runAsync(() -> {
			listedDimensions = MapDimensions.list(server);
			listedRegions = MapDimensions.regions(dimensionDir);
		});
	}

	private void closeSession() {
		if (selection != null) {
			selection.flush();
		}

		selecting = false;
		panning = false;

		if (session == null) {
			return;
		}

		capture.removeMapListener(listener);
		workers.shutdown();
		textures.close();
		liveUpdates.clear();
		requested.clear();
		dirtySince.clear();
		session = null;
		workers = null;
		textures = null;
		listener = null;
	}

	// ---- buttons --------------------------------------------------------------------------

	private boolean isCurrentDimension() {
		return dimensionId != null && dimensionId.equals(capture.currentDimensionId()) && Minecraft.getInstance().level != null;
	}

	private Component dimensionLabel() {
		return Component.translatable("sixteentosave.map.dimension", dimensionId == null ? "-" : dimensionId);
	}

	private Component roofLabel() {
		if (clipY == ChunkSummarizer.NO_CLIP) {
			return Component.translatable(roofY != null ? "sixteentosave.map.below_roof" : "sixteentosave.map.full_height");
		}

		return Component.translatable("sixteentosave.map.full_height");
	}

	private boolean centerOnPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player != null && isCurrentDimension()) {
			view.setCenter(player.getX(), player.getZ());
			return true;
		}

		return false;
	}

	private void toggleRoof() {
		if (clipY == ChunkSummarizer.NO_CLIP && roofY != null) {
			setClip(roofY);
		} else {
			setClip(ChunkSummarizer.NO_CLIP);
		}
	}

	private void cycleDimension() {
		if (dimensions.size() < 2) {
			return;
		}

		int index = dimensions.indexOf(dimensionId);
		String next = dimensions.get((index + 1) % dimensions.size());
		closeSession();
		fullRasters.clear();
		clipRasters.clear();
		regions = new HashSet<>();
		dimensionId = next;
		clipY = ChunkSummarizer.NO_CLIP;
		sliderY = ChunkSummarizer.NO_CLIP;
		updateHeights();
		openSession();
		viewPlaced = centerOnPlayer();
		dimensionButton.setMessage(dimensionLabel());
		centerButton.active = isCurrentDimension();
		roofButton.setMessage(roofLabel());
		updateSelectionButtons();
	}

	/** Height range of the slider: the live level's for the current dimension, vanilla defaults otherwise. */
	private void updateHeights() {
		ClientLevel level = Minecraft.getInstance().level;

		if (level != null && dimensionId != null && dimensionId.equals(capture.currentDimensionId())) {
			minY = level.getMinY();
			maxY = level.getMaxY();
			roofY = level.dimensionType().hasCeiling() ? minY + level.dimensionType().logicalHeight() - 1 : null;
		} else if ("minecraft:the_nether".equals(dimensionId)) {
			minY = 0;
			maxY = 255;
			roofY = 127;
		} else if ("minecraft:the_end".equals(dimensionId)) {
			minY = 0;
			maxY = 255;
			roofY = null;
		} else {
			minY = -64;
			maxY = 319;
			roofY = null;
		}
	}

	private void setClip(int y) {
		int value = y >= maxY ? ChunkSummarizer.NO_CLIP : y;
		sliderY = value;

		if (value == clipY) {
			return;
		}

		clipY = value;
		clipRasters.clear();

		if (session != null) {
			session.nextGeneration();
			workers.clearQueue();
			textures.releaseAll();
		}

		requested.clear();

		if (roofButton != null) {
			roofButton.setMessage(roofLabel());
		}
	}

	// ---- selection (design §7.5) ----------------------------------------------------------

	private record Action(Component label, Button.OnPress press) {
	}

	/** Second toolbar row; button widths follow their labels and shrink to fit. */
	private void addSelectionRow() {
		List<Action> actions = List.of(
				new Action(Component.translatable("sixteentosave.map.select_all"), button -> selectAll()),
				new Action(Component.translatable("sixteentosave.map.invert"), button -> editSelection(s -> s.invert(universe()))),
				new Action(Component.translatable("sixteentosave.map.clear"), button -> editSelection(ChunkSelection::clear)),
				new Action(Component.translatable("sixteentosave.map.undo"), button -> undo()),
				new Action(radiusLabel(), button -> cycleRadius()),
				new Action(Component.translatable("sixteentosave.map.around_me"), button -> selectAroundPlayer()),
				new Action(Component.translatable("sixteentosave.map.around_spawn"), button -> selectAroundSpawn()),
				new Action(Component.translatable("sixteentosave.map.save_as"), button -> saveSelectionAs()),
				new Action(Component.translatable("sixteentosave.map.load"), button -> loadSelection()));

		int gap = 2;
		int natural = 0;

		for (Action a : actions) {
			natural += buttonWidth(a.label());
		}

		int available = this.width - 8 - gap * (actions.size() - 1);
		double factor = Math.min(1.0, available / (double) Math.max(1, natural));
		List<Button> buttons = new ArrayList<>();
		int x = 4;

		for (Action a : actions) {
			int w = Math.max(16, (int) Math.floor(buttonWidth(a.label()) * factor));
			buttons.add(this.addRenderableWidget(Button.builder(a.label(), a.press()).pos(x, ROW_2).size(w, 20).build()));
			x += w + gap;
		}

		selectAllButton = buttons.get(0);
		invertButton = buttons.get(1);
		clearButton = buttons.get(2);
		undoButton = buttons.get(3);
		radiusButton = buttons.get(4);
		aroundMeButton = buttons.get(5);
		aroundSpawnButton = buttons.get(6);
		saveButton = buttons.get(7);
		loadButton = buttons.get(8);

		undoButton.setTooltip(Tooltip.create(Component.translatable("sixteentosave.map.undo.tooltip")));
		radiusButton.setTooltip(Tooltip.create(Component.translatable("sixteentosave.map.radius.tooltip")));
		Tooltip around = Tooltip.create(Component.translatable("sixteentosave.map.around.tooltip"));
		aroundMeButton.setTooltip(around);
		aroundSpawnButton.setTooltip(around);
	}

	private void updateSelectionButtons() {
		if (selectAllButton == null) {
			return;
		}

		boolean ready = universe() != null;
		boolean any = selection != null && !selection.selection().isEmpty();
		selectAllButton.active = ready;
		invertButton.active = ready;
		clearButton.active = any;
		undoButton.active = selection != null && selection.canUndo();
		radiusButton.active = ready;
		aroundMeButton.active = ready && isCurrentDimension();
		aroundSpawnButton.active = ready && spawnChunk() != null;
		saveButton.active = any;
		loadButton.active = selection != null;
	}

	/** Cached chunks, or {@code null} while they are being read (selection tools wait for it). */
	private ChunkSelection universe() {
		return selection == null || session == null ? null : selection.universe();
	}

	private void editSelection(java.util.function.Consumer<ChunkSelection> change) {
		if (universe() != null) {
			selection.edit(change);
		}
	}

	private void selectAll() {
		ChunkSelection universe = universe();
		editSelection(s -> s.selectAll(universe));
	}

	private void undo() {
		if (selection != null) {
			selection.undo();
		}
	}

	private Component radiusLabel() {
		return Component.translatable("sixteentosave.map.radius", RADII[radiusIndex]);
	}

	private void cycleRadius() {
		int step = Minecraft.getInstance().hasShiftDown() ? RADII.length - 1 : 1;
		radiusIndex = (radiusIndex + step) % RADII.length;
		radiusButton.setMessage(radiusLabel());
	}

	/** Shift adds, Ctrl or Alt removes, nothing replaces. */
	private static ChunkSelection.Mode mode(boolean shift, boolean controlOrAlt) {
		return shift ? ChunkSelection.Mode.ADD : controlOrAlt ? ChunkSelection.Mode.SUBTRACT : ChunkSelection.Mode.REPLACE;
	}

	private static ChunkSelection.Mode keyboardMode() {
		Minecraft minecraft = Minecraft.getInstance();
		return mode(minecraft.hasShiftDown(), minecraft.hasControlDown() || minecraft.hasAltDown());
	}

	private void selectAround(int chunkX, int chunkZ) {
		ChunkSelection universe = universe();
		ChunkSelection.Mode mode = keyboardMode();
		int radius = RADII[radiusIndex];
		editSelection(s -> s.applyCircle(chunkX, chunkZ, radius, mode, universe));
	}

	private void selectAroundPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player != null && isCurrentDimension()) {
			selectAround((int) Math.floor(player.getX()) >> 4, (int) Math.floor(player.getZ()) >> 4);
		}
	}

	private void selectAroundSpawn() {
		int[] spawn = spawnChunk();

		if (spawn != null) {
			selectAround(spawn[0], spawn[1]);
		}
	}

	/** The world spawn's chunk if it is in the shown dimension (the server tells every client where it is). */
	private int[] spawnChunk() {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null || dimensionId == null || serverDir == null) {
			return null;
		}

		try {
			var spawn = level.getRespawnData();

			if (spawn == null || !dimensionId.equals(spawn.dimension().identifier().toString())) {
				return null;
			}

			var pos = spawn.pos();
			return new int[] { pos.getX() >> 4, pos.getZ() >> 4 };
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private void openExport() {
		WorldExporter running = WorldExporter.current();

		if (running != null && WorldExporter.isRunning()) {
			this.minecraft.gui.setScreen(new ExportProgressScreen(this, running));
			return;
		}

		if (selection != null) {
			// the export screen reads the saved selections; this one goes first
			selection.flush();
		}

		this.minecraft.gui.setScreen(new ExportScreen(this, capture));
	}

	private void saveSelectionAs() {
		if (selection != null && !selection.selection().isEmpty()) {
			this.minecraft.gui.setScreen(new SelectionNameScreen(this, selection.dimensionDir(), selection.dimensionId(),
					selection.selection().copy()));
		}
	}

	private void loadSelection() {
		SelectionState target = selection;

		if (target != null) {
			this.minecraft.gui.setScreen(new SelectionListScreen(this, target.dimensionDir(),
					loaded -> target.edit(s -> s.selectAll(loaded))));
		}
	}

	private int chunkAtX(double screenX) {
		return (int) Math.floor(view.blockX(screenX) / 16.0);
	}

	private int chunkAtZ(double screenY) {
		return (int) Math.floor(view.blockZ(screenY) / 16.0);
	}

	/** End of a left-button gesture: a rectangle, or a click on one chunk (one region when zoomed far out). */
	private void finishSelection() {
		ChunkSelection universe = universe();

		if (universe == null) {
			return;
		}

		int x0 = chunkAtX(dragStartX);
		int z0 = chunkAtZ(dragStartY);
		ChunkSelection.Mode mode = dragMode;

		if (dragMoved) {
			int x1 = chunkAtX(dragEndX);
			int z1 = chunkAtZ(dragEndY);
			selection.edit(s -> s.applyRect(x0, z0, x1, z1, mode, universe));
		} else if (lod == Lod.REGIONS) {
			int rx = x0 >> 5;
			int rz = z0 >> 5;
			selection.edit(s -> {
				if (mode == ChunkSelection.Mode.REPLACE) {
					s.toggleRegion(rx, rz, universe);
				} else {
					s.applyRect(rx * 32, rz * 32, rx * 32 + 31, rz * 32 + 31, mode, universe);
				}
			});
		} else {
			selection.edit(s -> {
				boolean on = switch (mode) {
					case REPLACE -> !s.contains(x0, z0);
					case ADD -> true;
					case SUBTRACT -> false;
				};

				// only cached chunks can be selected; any chunk can be deselected
				if (!on || universe.contains(x0, z0)) {
					s.set(x0, z0, on);
				}
			});
		}
	}

	// ---- input ----------------------------------------------------------------------------

	private boolean inSlider(double x, double y) {
		return x >= this.width - SLIDER_WIDTH && x < this.width && y >= TOP && y < this.height - BOTTOM;
	}

	private void sliderFromMouse(double y) {
		double top = TOP + 4;
		double bottom = this.height - BOTTOM - 4;
		double t = Math.max(0, Math.min(1, (bottom - y) / Math.max(1, bottom - top)));
		sliderY = t > 0.995 ? ChunkSummarizer.NO_CLIP : (int) Math.round(minY + t * (maxY - minY));
		sliderChangedAt = System.nanoTime();
	}

	/** Label width plus room on both sides. */
	private int buttonWidth(Component label) {
		return this.font.width(label.getString()) + BUTTON_PADDING;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			// A clicked button keeps the keyboard focus and is drawn highlighted ("stays pressed").
			// The toolbar buttons are actions, not states: drop the focus.
			this.setFocused(null);
			return true;
		}

		if (session != null && inSlider(event.x(), event.y())) {
			draggingSlider = true;
			sliderFromMouse(event.y());
			return true;
		}

		if (view.contains(event.x(), event.y())) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				if (universe() != null) {
					selecting = true;
					dragMoved = false;
					dragMode = mode(event.hasShiftDown(), event.hasControlDown() || event.hasAltDown());
					dragStartX = dragEndX = event.x();
					dragStartY = dragEndY = event.y();
				}

				return true;
			}

			panning = true;
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingSlider) {
			sliderFromMouse(event.y());
			return true;
		}

		if (selecting) {
			dragEndX = event.x();
			dragEndY = event.y();
			dragMoved |= Math.abs(dragEndX - dragStartX) > CLICK_SLOP || Math.abs(dragEndY - dragStartY) > CLICK_SLOP;
			return true;
		}

		if (panning) {
			view.pan(dragX, dragY);
			return true;
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingSlider) {
			draggingSlider = false;
			setClip(sliderY);
		}

		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (selecting) {
				selecting = false;
				finishSelection();
			}
		} else {
			panning = false;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (selection != null && event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
			undo();
			return true;
		}

		if (selection != null && event.isSelectAll()) {
			selectAll();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && view.contains(mouseX, mouseY)) {
			view.zoomAt(Math.pow(ZOOM_STEP, scrollY), mouseX, mouseY);
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// ---- frame ----------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, TOP, TOOLBAR);

		if (session == null) {
			String message = tr(serverDir == null ? "sixteentosave.map.no_server" : "sixteentosave.map.no_data");
			graphics.centeredText(this.font, message, this.width / 2, this.height / 2, WHITE);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		textures.flushReleases();
		takeAsyncResults();
		drainResults();
		applyLiveUpdates();
		selection.tick();
		updateSelectionButtons();

		if (!draggingSlider && sliderY != clipY && System.nanoTime() - sliderChangedAt > CLIP_DEBOUNCE_NANOS) {
			setClip(sliderY);
		}

		lod = Lod.select(view.scale(), lod);
		drawMap(graphics);
		drawSelection(graphics);
		drawIncomplete(graphics);
		drawGrid(graphics);
		drawDrag(graphics);
		drawPlayer(graphics);
		drawSlider(graphics);
		drawStatus(graphics, mouseX, mouseY);
		drawHint(graphics);
		requestVisible();

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private Map<Long, TileRasterizer.RegionRaster> activeRasters() {
		return clipY == ChunkSummarizer.NO_CLIP ? fullRasters : clipRasters;
	}

	private void takeAsyncResults() {
		Set<Long> listed = listedRegions;

		if (listed != null) {
			listedRegions = null;
			// the cache may have shrunk while the map was away (cleared, evicted)
			regions.removeIf(key -> !listed.contains(key) && !dirtySince.containsKey(key));
			fullRasters.keySet().retainAll(regions);
			clipRasters.keySet().retainAll(regions);
			regions.addAll(listed);

			if (!viewPlaced && !regions.isEmpty()) {
				placeViewOnCache();
			}
		}

		List<String> dims = listedDimensions;

		if (dims != null) {
			listedDimensions = null;
			List<String> all = new ArrayList<>(dims);

			if (dimensionId != null && !all.contains(dimensionId)) {
				all.add(dimensionId);
				all.sort(null);
			}

			dimensions = all;
			dimensionButton.active = dimensions.size() > 1;
		}
	}

	/** Centre of the cached regions, for a dimension the player isn't in. */
	private void placeViewOnCache() {
		double sx = 0;
		double sz = 0;

		for (long key : regions) {
			sx += (ChunkKey.x(key) + 0.5) * MapView.REGION_BLOCKS;
			sz += (ChunkKey.z(key) + 0.5) * MapView.REGION_BLOCKS;
		}

		view.setCenter(sx / regions.size(), sz / regions.size());
		view.setScale(0.25);
		viewPlaced = true;
	}

	private void drainResults() {
		TileWorkers.Result result;

		while ((result = workers.poll()) != null) {
			TileWorkers.Job job = result.job();
			selection.regionScanned(job.regionX(), job.regionZ(), result.chunkHeader(), result.entityHeader());

			if (job.generation() != session.generation()) {
				continue;
			}

			long key = ChunkKey.pack(job.regionX(), job.regionZ());
			requested.remove(key);
			boolean full = job.clipY() == ChunkSummarizer.NO_CLIP;

			if (full) {
				fullRasters.put(key, result.raster());
			} else if (job.clipY() == clipY) {
				clipRasters.put(key, result.raster());
			} else {
				continue;
			}

			if (full == (clipY == ChunkSummarizer.NO_CLIP)) {
				textures.invalidate(job.regionX(), job.regionZ());
			}

			if (result.raster().chunkCount() > 0) {
				regions.add(key);
			}
		}
	}

	private void applyLiveUpdates() {
		long now = System.nanoTime();
		Live live;

		while ((live = liveUpdates.poll()) != null) {
			session.putLive(live.chunkX(), live.chunkZ(), live.summary());
			selection.chunkWritten(live.chunkX(), live.chunkZ());
			long key = ChunkKey.pack(live.chunkX() >> 5, live.chunkZ() >> 5);
			regions.add(key);
			dirtySince.putIfAbsent(key, now);
		}

		int[] visible = view.visibleRegions();
		Iterator<Map.Entry<Long, Long>> it = dirtySince.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Long, Long> e = it.next();

			if (now - e.getValue() < LIVE_REFRESH_NANOS || requested.contains(e.getKey())) {
				continue;
			}

			it.remove();
			int rx = ChunkKey.x(e.getKey());
			int rz = ChunkKey.z(e.getKey());

			if (isVisible(visible, rx, rz)) {
				request(rx, rz);
			} else {
				// rebuilt when it scrolls into view
				fullRasters.remove(e.getKey());
				clipRasters.remove(e.getKey());
				textures.invalidate(rx, rz);
			}
		}
	}

	private static boolean isVisible(int[] visible, int rx, int rz) {
		return rx >= visible[0] && rz >= visible[1] && rx <= visible[2] && rz <= visible[3];
	}

	private void request(int rx, int rz) {
		long key = ChunkKey.pack(rx, rz);
		workers.request(new TileWorkers.Job(rx, rz, clipY, session.generation()), view.regionDistanceSq(rx, rz));
		requested.add(key);
	}

	private void requestVisible() {
		int[] visible = view.visibleRegions();
		Map<Long, TileRasterizer.RegionRaster> rasters = activeRasters();

		for (long key : regions) {
			int rx = ChunkKey.x(key);
			int rz = ChunkKey.z(key);

			if (!isVisible(visible, rx, rz) || requested.contains(key)) {
				continue;
			}

			TileRasterizer.RegionRaster raster = rasters.get(key);
			boolean needBlocks = lod == Lod.BLOCKS && raster != null && raster.lod0() == null && textures.get(rx, rz, Lod.BLOCKS) == null;

			if (raster == null || needBlocks) {
				request(rx, rz);
			}
		}
	}

	private void drawMap(GuiGraphicsExtractor graphics) {
		int right = this.width - SLIDER_WIDTH;
		int bottom = this.height - BOTTOM;
		graphics.fill(0, TOP, right, bottom, BACKGROUND);
		graphics.enableScissor(0, TOP, right, bottom);

		int[] visible = view.visibleRegions();
		Map<Long, TileRasterizer.RegionRaster> rasters = activeRasters();
		long uploadBudget = UPLOAD_PIXELS_PER_FRAME;
		double size = MapView.REGION_BLOCKS * view.scale();

		for (long key : regions) {
			int rx = ChunkKey.x(key);
			int rz = ChunkKey.z(key);

			if (!isVisible(visible, rx, rz)) {
				continue;
			}

			TileRasterizer.RegionRaster raster = rasters.get(key);

			if (raster == null) {
				continue;
			}

			double sx = view.screenX((double) rx * MapView.REGION_BLOCKS);
			double sy = view.screenY((double) rz * MapView.REGION_BLOCKS);

			if (lod == Lod.REGIONS) {
				fillQuad(graphics, sx, sy, size, raster.averageArgb());
				continue;
			}

			Lod drawn = lod;
			Identifier id = textures.get(rx, rz, lod);

			if (id == null) {
				int[] pixels = raster.pixels(lod);

				if (pixels != null && uploadBudget > 0) {
					id = textures.upload(rx, rz, lod, pixels);
					uploadBudget -= (long) lod.tileSize * lod.tileSize;

					if (lod == Lod.BLOCKS) {
						// the texture holds it now; drop the 1 MiB array
						rasters.put(key, new TileRasterizer.RegionRaster(rx, rz, null, raster.lod1(), raster.lod2(),
								raster.averageArgb(), raster.chunkCount(), raster.incomplete()));
					}
				}
			}

			for (Lod coarser = lod.coarser(); id == null && coarser != Lod.REGIONS; coarser = coarser.coarser()) {
				id = textures.get(rx, rz, coarser);
				drawn = coarser;
			}

			if (id == null) {
				fillQuad(graphics, sx, sy, size, raster.averageArgb());
				continue;
			}

			graphics.pose().pushMatrix();
			graphics.pose().translate((float) sx, (float) sy);
			float scale = (float) (size / drawn.tileSize);
			graphics.pose().scale(scale, scale);
			graphics.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0.0F, 0.0F, drawn.tileSize, drawn.tileSize, drawn.tileSize, drawn.tileSize);
			graphics.pose().popMatrix();
		}

		graphics.disableScissor();
	}

	private static void fillQuad(GuiGraphicsExtractor graphics, double x, double y, double size, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}

		graphics.fill((int) Math.floor(x), (int) Math.floor(y), (int) Math.ceil(x + size), (int) Math.ceil(y + size), argb);
	}

	private void drawSelection(GuiGraphicsExtractor graphics) {
		if (selection.selection().isEmpty()) {
			return;
		}

		graphics.enableScissor(0, TOP, this.width - SLIDER_WIDTH, this.height - BOTTOM);
		SelectionOverlay.draw(graphics, view, selection.selection(), view.visibleRegions(), SELECTION);
		graphics.disableScissor();
	}

	/** Stripes over chunks with containers whose contents were never seen: they export empty. */
	private void drawIncomplete(GuiGraphicsExtractor graphics) {
		int[] visible = view.visibleRegions();
		Map<Long, TileRasterizer.RegionRaster> rasters = activeRasters();
		boolean any = false;

		for (long key : regions) {
			int rx = ChunkKey.x(key);
			int rz = ChunkKey.z(key);
			TileRasterizer.RegionRaster raster = rasters.get(key);

			if (raster == null || raster.incomplete() == null || !isVisible(visible, rx, rz)) {
				continue;
			}

			long[] bits = raster.incomplete();

			for (int w = 0; w < bits.length; w++) {
				for (long word = bits[w]; word != 0; word &= word - 1) {
					if (!any) {
						graphics.enableScissor(0, TOP, this.width - SLIDER_WIDTH, this.height - BOTTOM);
						any = true;
					}

					int i = (w << 6) + Long.numberOfTrailingZeros(word);
					SelectionOverlay.hatch(graphics, view, rx * 32 + (i & 31), rz * 32 + (i >> 5), INCOMPLETE);
				}
			}
		}

		if (any) {
			graphics.disableScissor();
		}
	}

	/** The rectangle being dragged, snapped to chunks, with its size. */
	private void drawDrag(GuiGraphicsExtractor graphics) {
		if (!selecting || !dragMoved) {
			return;
		}

		int x0 = chunkAtX(dragStartX);
		int z0 = chunkAtZ(dragStartY);
		int x1 = chunkAtX(dragEndX);
		int z1 = chunkAtZ(dragEndY);
		int fill = switch (dragMode) {
			case REPLACE -> DRAG_REPLACE;
			case ADD -> DRAG_ADD;
			case SUBTRACT -> DRAG_SUBTRACT;
		};

		graphics.enableScissor(0, TOP, this.width - SLIDER_WIDTH, this.height - BOTTOM);
		SelectionOverlay.outline(graphics, view, x0, z0, x1, z1, fill, fill | 0xFF000000);
		graphics.disableScissor();

		String size = (Math.abs(x1 - x0) + 1) + " × " + (Math.abs(z1 - z0) + 1);
		int tx = (int) dragEndX + 8;
		int ty = (int) dragEndY + 8;
		graphics.fill(tx - 2, ty - 2, tx + this.font.width(size) + 2, ty + this.font.lineHeight, HINT_BACKGROUND);
		graphics.text(this.font, size, tx, ty, WHITE);
	}

	/** How to select, while nothing is selected. */
	private void drawHint(GuiGraphicsExtractor graphics) {
		if (!selection.selection().isEmpty() || selection.universe() == null || selecting) {
			return;
		}

		String hint = tr("sixteentosave.map.hint");
		int y = this.height - BOTTOM - this.font.lineHeight - 4;
		graphics.fill(0, y - 3, this.font.width(hint) + 8, this.height - BOTTOM, HINT_BACKGROUND);
		graphics.text(this.font, hint, 4, y, GREY);
	}

	private void drawGrid(GuiGraphicsExtractor graphics) {
		int right = this.width - SLIDER_WIDTH;
		int bottom = this.height - BOTTOM;
		double chunkPixels = 16 * view.scale();
		double regionPixels = MapView.REGION_BLOCKS * view.scale();

		graphics.enableScissor(0, TOP, right, bottom);

		if (chunkPixels >= 6) {
			drawLines(graphics, 16, GRID_CHUNK, right, bottom);
		}

		if (regionPixels >= 6) {
			drawLines(graphics, MapView.REGION_BLOCKS, GRID_REGION, right, bottom);
		}

		graphics.disableScissor();
	}

	private void drawLines(GuiGraphicsExtractor graphics, int spacing, int color, int right, int bottom) {
		double firstX = Math.floor(view.blockX(0) / spacing) * spacing;

		for (double bx = firstX; ; bx += spacing) {
			int x = (int) Math.round(view.screenX(bx));

			if (x > right) {
				break;
			}

			graphics.fill(x, TOP, x + 1, bottom, color);
		}

		double firstZ = Math.floor(view.blockZ(TOP) / spacing) * spacing;

		for (double bz = firstZ; ; bz += spacing) {
			int y = (int) Math.round(view.screenY(bz));

			if (y > bottom) {
				break;
			}

			graphics.fill(0, y, right, y + 1, color);
		}
	}

	private void drawPlayer(GuiGraphicsExtractor graphics) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null || !isCurrentDimension()) {
			return;
		}

		int x = (int) Math.round(view.screenX(player.getX()));
		int y = (int) Math.round(view.screenY(player.getZ()));

		if (!view.contains(x, y)) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		int tipX = x + (int) Math.round(-Math.sin(yaw) * 6);
		int tipY = y + (int) Math.round(Math.cos(yaw) * 6);
		graphics.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
		graphics.fill(x - 2, y - 2, x + 2, y + 2, PLAYER);
		graphics.fill(tipX - 1, tipY - 1, tipX + 1, tipY + 1, PLAYER);
	}

	private void drawSlider(GuiGraphicsExtractor graphics) {
		int left = this.width - SLIDER_WIDTH;
		int top = TOP;
		int bottom = this.height - BOTTOM;
		graphics.fill(left, top, this.width, bottom, SLIDER_TRACK);

		int shown = draggingSlider || sliderY != clipY ? sliderY : clipY;
		double t = shown == ChunkSummarizer.NO_CLIP ? 1 : (shown - minY) / (double) Math.max(1, maxY - minY);
		int handle = bottom - 4 - (int) Math.round(t * (bottom - top - 8));
		graphics.fill(left + 3, handle, this.width - 3, bottom - 2, SLIDER_FILL);
		graphics.fill(left + 1, handle - 2, this.width - 1, handle + 2, SLIDER_HANDLE);

		String label = shown == ChunkSummarizer.NO_CLIP ? tr("sixteentosave.map.all_heights") : tr("sixteentosave.map.max_y", shown);
		graphics.text(this.font, label, left - this.font.width(label) - 4, Math.max(TOP + 2, handle - 4), WHITE);
	}

	private void drawStatus(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int y = this.height - BOTTOM + 3;
		long chunks = 0;

		for (TileRasterizer.RegionRaster r : activeRasters().values()) {
			chunks += r.chunkCount();
		}

		String selected;

		if (selection.universe() == null) {
			selected = tr("sixteentosave.map.reading");
		} else {
			selected = tr("sixteentosave.map.selected", selection.cachedCount(), size(selection.estimateBytes()));
			int missing = selection.selection().count() - selection.cachedCount();

			if (missing > 0) {
				selected += " " + tr("sixteentosave.map.not_cached", missing);
			}
		}

		String map = tr("sixteentosave.map.status", tr("sixteentosave.map.lod." + lod.name().toLowerCase(Locale.ROOT)),
				regions.size(), chunks);
		int pending = workers.pending();

		if (pending > 0) {
			map += " · " + tr("sixteentosave.map.rendering", pending);
		}

		String cursor = null;

		if (view.contains(mouseX, mouseY)) {
			int bx = (int) Math.floor(view.blockX(mouseX));
			int bz = (int) Math.floor(view.blockZ(mouseY));
			cursor = tr("sixteentosave.map.cursor", bx, bz, bx >> 4, bz >> 4, bx >> 9, bz >> 9);
		}

		int room = this.width - SLIDER_WIDTH - 12 - (cursor == null ? 0 : this.font.width(cursor));
		String left = selected + " · " + map;
		graphics.text(this.font, this.font.width(left) <= room ? left : selected, 4, y, GREY);

		if (cursor != null) {
			graphics.text(this.font, cursor, this.width - SLIDER_WIDTH - this.font.width(cursor) - 4, y, WHITE);
		}
	}

	private static String size(long bytes) {
		if (bytes < 1024L * 1024) {
			return String.format(Locale.ROOT, "%.0f KiB", bytes / 1024.0);
		}

		if (bytes < 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
		}

		return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}
}

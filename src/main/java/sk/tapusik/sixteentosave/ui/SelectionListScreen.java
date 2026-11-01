package sk.tapusik.sixteentosave.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.selection.SelectionFiles;

/** "Load selection…": the named selections of one dimension, a page at a time, with delete. */
public final class SelectionListScreen extends Screen {
	private static final int ROW = 24;
	private static final int WIDTH = 300;
	private static final int DELETE_WIDTH = 60;
	private static final int TOP = 36;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final Screen parent;
	private final Path dimensionDir;
	private final Consumer<ChunkSelection> onLoad;
	private List<SelectionFiles.Named> entries;
	private int page;
	private boolean loading;

	/** {@code onLoad} runs on the render thread with the chosen selection, before returning to {@code parent}. */
	public SelectionListScreen(Screen parent, Path dimensionDir, Consumer<ChunkSelection> onLoad) {
		super(Component.translatable("sixteentosave.selection.load.title"));
		this.parent = parent;
		this.dimensionDir = dimensionDir;
		this.onLoad = onLoad;
		refresh();
	}

	private void refresh() {
		Minecraft minecraft = Minecraft.getInstance();
		SelectionFiles.listNamed(dimensionDir).whenComplete((list, error) -> minecraft.execute(() -> {
			entries = list == null ? List.of() : list;
			page = Math.min(page, Math.max(0, pages() - 1));

			if (minecraft.gui.screen() == this) {
				this.rebuildWidgets();
			}
		}));
	}

	private int perPage() {
		return Math.max(1, (this.height - TOP - 40) / ROW);
	}

	private int pages() {
		return entries == null ? 1 : Math.max(1, (entries.size() + perPage() - 1) / perPage());
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = TOP;

		if (entries != null) {
			int from = page * perPage();

			for (int i = from; i < Math.min(entries.size(), from + perPage()); i++) {
				SelectionFiles.Named entry = entries.get(i);
				String chunks = entry.chunks() < 0 ? "?" : Integer.toString(entry.chunks());
				Component label = Component.translatable("sixteentosave.selection.load.entry", entry.name(), chunks,
						DATE.format(Instant.ofEpochMilli(entry.savedAtMillis())));
				Button load = Button.builder(label, button -> load(entry)).pos(x, y).size(WIDTH - DELETE_WIDTH - 4, 20).build();
				load.active = !loading && entry.chunks() > 0;
				this.addRenderableWidget(load);
				Button delete = Button.builder(Component.translatable("sixteentosave.selection.delete"), button -> confirmDelete(entry))
						.pos(x + WIDTH - DELETE_WIDTH, y).size(DELETE_WIDTH, 20).build();
				delete.active = !loading;
				this.addRenderableWidget(delete);
				y += ROW;
			}
		}

		int bottom = this.height - 28;
		Button previous = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> turn(-1))
				.pos(x, bottom).size(20, 20).build());
		previous.active = page > 0;
		Button next = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> turn(1))
				.pos(x + 24, bottom).size(20, 20).build());
		next.active = page + 1 < pages();
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.pos(x + WIDTH - 100, bottom).size(100, 20).build());
	}

	private void turn(int delta) {
		page = Math.max(0, Math.min(pages() - 1, page + delta));
		this.rebuildWidgets();
	}

	private void load(SelectionFiles.Named entry) {
		Minecraft minecraft = Minecraft.getInstance();
		loading = true;
		this.rebuildWidgets();
		SelectionFiles.load(entry.file()).whenComplete((selection, error) -> minecraft.execute(() -> {
			loading = false;

			if (selection != null) {
				onLoad.accept(selection);
			}

			minecraft.gui.setScreen(parent);
		}));
	}

	private void confirmDelete(SelectionFiles.Named entry) {
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				SelectionFiles.delete(entry.file()).whenComplete((v, error) -> refresh());
			}

			this.minecraft.gui.setScreen(this);
		}, Component.translatable("sixteentosave.selection.delete.title"),
				Component.translatable("sixteentosave.selection.delete.message", entry.name()),
				Component.translatable("sixteentosave.selection.delete"), CommonComponents.GUI_CANCEL));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title.getString(), this.width / 2, 14, WHITE);
		int x = this.width / 2 - WIDTH / 2;

		if (entries == null) {
			graphics.centeredText(this.font, tr("sixteentosave.selection.load.reading"), this.width / 2, TOP + 6, GREY);
		} else if (entries.isEmpty()) {
			graphics.centeredText(this.font, tr("sixteentosave.selection.load.none"), this.width / 2, TOP + 6, GREY);
		}

		if (pages() > 1) {
			graphics.text(this.font, tr("sixteentosave.selection.load.page", page + 1, pages()), x + 50, this.height - 22, GREY);
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

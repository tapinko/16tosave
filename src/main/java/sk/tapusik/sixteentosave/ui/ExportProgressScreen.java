package sk.tapusik.sixteentosave.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.export.ExportReport;
import sk.tapusik.sixteentosave.export.WorldExporter;

/**
 * Progress of the running export, with Cancel. Closing it ("Hide") leaves the export running; a
 * toast tells the result.
 */
public final class ExportProgressScreen extends Screen {
	private static final int WIDTH = 280;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6060;
	private static final int BAR_BACK = 0xFF303030;
	private static final int BAR_FILL = 0xFF4080FF;

	private final Screen parent;
	private final WorldExporter exporter;
	private Button cancelButton;
	private Button closeButton;
	private boolean wasFinished;

	public ExportProgressScreen(Screen parent, WorldExporter exporter) {
		super(Component.translatable("sixteentosave.export.progress.title", exporter.worldName()));
		this.parent = parent;
		this.exporter = exporter;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 + 40;
		cancelButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
			exporter.cancel();
			button.active = false;
		}).pos(x, y).size(WIDTH / 2 - 2, 20).build());
		closeButton = this.addRenderableWidget(Button.builder(closeLabel(), button -> onClose())
				.pos(x + WIDTH / 2 + 2, y).size(WIDTH / 2 - 2, 20).build());
		update();
	}

	private Component closeLabel() {
		return exporter.state().finished() ? CommonComponents.GUI_DONE : Component.translatable("sixteentosave.export.progress.hide");
	}

	private void update() {
		boolean finished = exporter.state().finished();
		cancelButton.active = !finished;

		if (finished != wasFinished) {
			wasFinished = finished;
			closeButton.setMessage(closeLabel());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		update();
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int x = cx - WIDTH / 2;
		int y = this.height / 2 - 50;
		graphics.centeredText(this.font, this.title.getString(), cx, y, WHITE);
		y += 20;

		WorldExporter.State state = exporter.state();
		String line = switch (state) {
			case FLUSHING -> tr("sixteentosave.export.progress.flushing");
			case EXPORTING -> tr("sixteentosave.export.progress.exporting", String.valueOf(exporter.dimension()));
			case FINISHING -> tr("sixteentosave.export.progress.finishing");
			case DONE -> tr("sixteentosave.export.progress.done", exporter.worldDir() == null ? "" : exporter.worldDir().getFileName());
			case CANCELLED -> tr("sixteentosave.export.progress.cancelled");
			case FAILED -> tr("sixteentosave.export.progress.failed", String.valueOf(exporter.error()));
		};
		graphics.centeredText(this.font, line, cx, y, state == WorldExporter.State.FAILED ? RED : WHITE);
		y += 16;

		int total = Math.max(1, exporter.total());
		int done = Math.min(exporter.done(), total);
		graphics.fill(x, y, x + WIDTH, y + 10, BAR_BACK);
		graphics.fill(x, y, x + (int) ((long) WIDTH * done / total), y + 10, BAR_FILL);
		y += 16;
		graphics.centeredText(this.font, tr("sixteentosave.export.progress.chunks", done, exporter.total()), cx, y, GREY);
		y += 14;

		if (state.finished() && state != WorldExporter.State.FAILED) {
			ExportReport report = exporter.report();
			graphics.centeredText(this.font, tr("sixteentosave.export.toast.counts", report.written(), report.skipped()), cx, y, GREY);
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

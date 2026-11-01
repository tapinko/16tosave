package sk.tapusik.sixteentosave.ui;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import sk.tapusik.sixteentosave.selection.ChunkSelection;
import sk.tapusik.sixteentosave.selection.SelectionFiles;

/** "Save selection as…": asks for a name, confirms replacing an existing one, saves off-thread. */
public final class SelectionNameScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int MAX_NAME = 64;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6060;

	private final Screen parent;
	private final Path dimensionDir;
	private final String dimensionId;
	private final ChunkSelection snapshot;
	private EditBox name;
	private Button save;
	private String value = "";
	private boolean busy;
	private boolean failed;

	/** {@code snapshot} is a copy the caller won't change. */
	public SelectionNameScreen(Screen parent, Path dimensionDir, String dimensionId, ChunkSelection snapshot) {
		super(Component.translatable("sixteentosave.selection.save.title"));
		this.parent = parent;
		this.dimensionDir = dimensionDir;
		this.dimensionId = dimensionId;
		this.snapshot = snapshot;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 30;
		name = new EditBox(this.font, x, y, WIDTH, 20, Component.translatable("sixteentosave.selection.save.name"));
		name.setMaxLength(MAX_NAME);
		name.setValue(value);
		name.setResponder(text -> {
			value = text;
			failed = false;
			updateButtons();
		});
		this.addRenderableWidget(name);
		this.setInitialFocus(name);

		save = this.addRenderableWidget(Button.builder(Component.translatable("sixteentosave.selection.save.button"), button -> trySave())
				.pos(x, y + 34).size(WIDTH / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.pos(x + WIDTH / 2 + 2, y + 34).size(WIDTH / 2 - 2, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		if (save != null) {
			save.active = !busy && !value.isBlank();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && save != null && save.active) {
			trySave();
			return true;
		}

		return super.keyPressed(event);
	}

	private void trySave() {
		String chosen = value.strip();

		if (chosen.isEmpty() || busy) {
			return;
		}

		busy = true;
		updateButtons();
		Minecraft minecraft = Minecraft.getInstance();
		SelectionFiles.namedExists(dimensionDir, chosen).whenComplete((exists, error) -> minecraft.execute(() -> {
			busy = false;

			if (Boolean.TRUE.equals(exists)) {
				minecraft.gui.setScreen(new ConfirmScreen(yes -> {
					minecraft.gui.setScreen(this);

					if (yes) {
						save(chosen);
					}
				}, Component.translatable("sixteentosave.selection.replace.title"),
						Component.translatable("sixteentosave.selection.replace.message", chosen),
						Component.translatable("sixteentosave.selection.replace.yes"), CommonComponents.GUI_CANCEL));
			} else {
				save(chosen);
			}
		}));
	}

	private void save(String chosen) {
		Minecraft minecraft = Minecraft.getInstance();
		busy = true;
		updateButtons();
		SelectionFiles.saveNamed(dimensionDir, dimensionId, chosen, snapshot).whenComplete((file, error) -> minecraft.execute(() -> {
			busy = false;

			if (error == null) {
				minecraft.gui.setScreen(parent);
			} else {
				failed = true;
				updateButtons();
			}
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title.getString(), this.width / 2, this.height / 2 - 60, WHITE);
		graphics.centeredText(this.font, tr("sixteentosave.selection.save.info", snapshot.count(), dimensionId),
				this.width / 2, this.height / 2 - 46, GREY);

		if (failed) {
			graphics.centeredText(this.font, tr("sixteentosave.selection.save.failed"), this.width / 2, this.height / 2 + 30, RED);
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

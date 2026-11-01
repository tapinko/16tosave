package sk.tapusik.sixteentosave.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import sk.tapusik.sixteentosave.SixteenToSave;

/**
 * Records container contents the player has seen (design §5.4). The server never sends chest,
 * barrel, hopper… contents with the chunk; they reach the client only in the open container
 * screen. When such a screen closes, its slots are copied into the client's own block entity
 * (client memory only, nothing is sent), so the next snapshot saves them with vanilla code.
 *
 * <p>Taken are block containers whose menu has exactly one group of non-player slots of the
 * block entity's size, and double chests: their 54 slots are vanilla's
 * {@code CompoundContainer(first, second)}, where the first half is the chest of type RIGHT.
 * Entity inventories (chest boats, donkeys…) are not taken and stay marked incomplete.
 */
final class ContainerObserver {
	private final CaptureService capture;
	private final Set<Screen> seenScreens = Collections.newSetFromMap(new WeakHashMap<>());

	ContainerObserver(CaptureService capture) {
		this.capture = capture;
	}

	void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			try {
				onInit(client, screen);
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.debug("Container observation failed on open: {}", e.toString());
			}
		});
	}

	private void onInit(Minecraft client, Screen screen) {
		// init runs again on every resize; watch each screen once
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || !seenScreens.add(screen)) {
			return;
		}

		if (!capture.observesContainers()) {
			return;
		}

		ClientLevel level = client.level;

		if (level == null || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}

		BlockPos pos = hit.getBlockPos();
		ScreenEvents.remove(screen).register(removed -> {
			try {
				onClosed(containerScreen, level, pos);
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.debug("Container observation failed on close: {}", e.toString());
			}
		});
	}

	private void onClosed(AbstractContainerScreen<?> screen, ClientLevel level, BlockPos pos) {
		if (Minecraft.getInstance().level != level) {
			return;
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);

		if (!(blockEntity instanceof Container target)) {
			return;
		}

		int size = target.getContainerSize();
		Map<Container, List<Slot>> groups = new IdentityHashMap<>();

		for (Slot slot : screen.getMenu().slots) {
			if (!(slot.container instanceof Inventory)) {
				groups.computeIfAbsent(slot.container, c -> new ArrayList<>()).add(slot);
			}
		}

		List<Slot> match = null;

		for (Map.Entry<Container, List<Slot>> e : groups.entrySet()) {
			if (e.getKey().getContainerSize() == size && e.getValue().size() == size) {
				if (match != null) {
					return; // ambiguous: don't guess
				}

				match = e.getValue();
			}
		}

		if (match == null) {
			observeDoubleChest(level, pos, blockEntity, target, groups);
			return;
		}

		for (Slot slot : match) {
			int index = slot.getContainerSlot();

			if (index >= 0 && index < size) {
				target.setItem(index, slot.getItem().copy());
			}
		}

		capture.onContainerObserved(level, pos, blockEntity);
	}

	/** A double chest: one 54-slot group, split over the two halves' block entities. */
	private void observeDoubleChest(ClientLevel level, BlockPos pos, BlockEntity blockEntity, Container target,
			Map<Container, List<Slot>> groups) {
		if (!(blockEntity instanceof ChestBlockEntity chest)) {
			return;
		}

		BlockState state = chest.getBlockState();

		if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return;
		}

		BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));

		if (!(level.getBlockEntity(otherPos) instanceof ChestBlockEntity other)) {
			return;
		}

		int half = target.getContainerSize();

		if (other.getContainerSize() != half) {
			return;
		}

		List<Slot> match = null;

		for (Map.Entry<Container, List<Slot>> e : groups.entrySet()) {
			if (e.getKey().getContainerSize() == 2 * half && e.getValue().size() == 2 * half) {
				if (match != null) {
					return;
				}

				match = e.getValue();
			}
		}

		if (match == null) {
			return;
		}

		// vanilla: CompoundContainer(first, second), the RIGHT-type half is the first one
		boolean thisFirst = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT;
		Container first = thisFirst ? target : other;
		Container second = thisFirst ? other : target;

		for (Slot slot : match) {
			int index = slot.getContainerSlot();

			if (index >= 0 && index < half) {
				first.setItem(index, slot.getItem().copy());
			} else if (index >= half && index < 2 * half) {
				second.setItem(index - half, slot.getItem().copy());
			}
		}

		capture.onContainerObserved(level, pos, blockEntity);
		capture.onContainerObserved(level, otherPos, other);
	}
}

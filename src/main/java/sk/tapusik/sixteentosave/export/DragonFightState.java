package sk.tapusik.sixteentosave.export;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import sk.tapusik.sixteentosave.SixteenToSave;

/**
 * Writes the End's dragon-fight saved data as "the dragon has been killed", so that the exported
 * End doesn't start a fight (design §8.4).
 *
 * <p>The 26.x file format is not documented, so the file is made by vanilla itself: its own
 * {@code EnderDragonFight.CODEC} decodes an empty fight, three fields are set
 * ({@code needsStateScanning}, {@code dragonKilled}, {@code hasPreviouslyKilledDragon} — names
 * from the 26.3 sources), and the codec encodes it again; the file name comes from the class's
 * own {@code SavedDataType}. This goes through reflection on purpose: if any of it is different
 * in this game version, nothing is written and vanilla decides as it always does.
 */
final class DragonFightState {
	private static final String FIGHT_CLASS = "net.minecraft.world.level.dimension.end.EnderDragonFight";

	private DragonFightState() {
	}

	/** @return whether the file was written */
	static boolean writeKilled(Path endDimensionDir, int dataVersion) {
		try {
			Class<?> fightClass = Class.forName(FIGHT_CLASS);
			@SuppressWarnings("unchecked")
			Codec<Object> codec = (Codec<Object>) fightClass.getField("CODEC").get(null);
			String id = savedDataId(fightClass);

			Object fight = codec.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
			set(fightClass, fight, "needsStateScanning", false);
			set(fightClass, fight, "dragonKilled", true);
			set(fightClass, fight, "hasPreviouslyKilledDragon", true);
			Tag data = codec.encodeStart(NbtOps.INSTANCE, fight).getOrThrow();

			// read it back the way the game will: the three values must survive
			Object check = codec.parse(NbtOps.INSTANCE, data).getOrThrow();

			if (get(fightClass, check, "needsStateScanning") || !get(fightClass, check, "dragonKilled")
					|| !get(fightClass, check, "hasPreviouslyKilledDragon")) {
				throw new IllegalStateException("the fight state did not survive encoding");
			}

			int colon = id.indexOf(':');
			Path file = endDimensionDir.resolve("data").resolve(colon < 0 ? "minecraft" : id.substring(0, colon))
					.resolve((colon < 0 ? id : id.substring(colon + 1)) + ".dat");
			CompoundTag root = new CompoundTag();
			root.put("data", data);
			root.putInt("DataVersion", dataVersion);
			Files.createDirectories(file.getParent());
			NbtIo.writeCompressed(root, file);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError | java.io.IOException e) {
			SixteenToSave.LOGGER.info("{}: could not write the End's dragon state ({}); the game will decide when someone enters the End",
					SixteenToSave.NAME, e.toString());
			return false;
		}
	}

	/** The id of the class's {@code SavedDataType} constant, e.g. {@code minecraft:ender_dragon_fight}. */
	private static String savedDataId(Class<?> fightClass) throws ReflectiveOperationException {
		for (Field field : fightClass.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType().getSimpleName().equals("SavedDataType")) {
				field.setAccessible(true);
				Object type = field.get(null);
				Object id = type.getClass().getMethod("id").invoke(type);
				return id.toString();
			}
		}

		throw new NoSuchFieldException("no SavedDataType in " + fightClass.getName());
	}

	private static void set(Class<?> type, Object target, String name, boolean value) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.setBoolean(target, value);
	}

	private static boolean get(Class<?> type, Object target, String name) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.getBoolean(target);
	}
}

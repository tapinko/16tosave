package sk.tapusik.sixteentosave.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/** Groups the client's entities by chunk and saves them with vanilla's own entity serialisation. */
final class EntityCollector {
	private EntityCollector() {
	}

	/**
	 * One pass over all entities the client knows, grouped by chunk. Skipped: players (never
	 * saved), passengers (vanilla saves them inside their vehicle), and entities that are removed
	 * or dying.
	 */
	static Map<Long, List<Entity>> bucket(ClientLevel level) {
		Map<Long, List<Entity>> out = new HashMap<>();

		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof Player || entity.isPassenger() || entity.isRemoved()) {
				continue;
			}

			if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
				continue;
			}

			long key = ChunkKey.pack(Mth.floor(entity.getX()) >> 4, Mth.floor(entity.getZ()) >> 4);
			out.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
		}

		return out;
	}

	/** Full NBT of each entity, as vanilla writes it into {@code entities/} region files. */
	static List<EntityMerger.Entry<CompoundTag>> save(List<Entity> entities) {
		List<EntityMerger.Entry<CompoundTag>> out = new ArrayList<>(entities.size());

		for (Entity entity : entities) {
			try {
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());

				if (entity.save(output)) {
					CompoundTag tag = output.buildResult();
					out.add(new EntityMerger.Entry<>(entity.getUUID().toString(), tag.getStringOr("id", ""), tag));
				}
			} catch (RuntimeException e) {
				SixteenToSave.LOGGER.debug("Could not save entity {}: {}", entity, e.toString());
			}
		}

		return out;
	}
}

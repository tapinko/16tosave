package sk.tapusik.sixteentosave.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which entities a chunk's entity record keeps (design §5.5).
 *
 * <p>The server only sends entities within tracking range, so an empty-looking chunk at the edge
 * of view may still have entities. An <em>authoritative</em> observation (the whole chunk was in
 * range) replaces the stored list. Otherwise the observation is merged: what was seen is added or
 * updated, and earlier captures are kept, except tombstoned entities (seen dying, or seen again
 * in another chunk) and transient types (items, projectiles…) that would only go stale.
 * Tombstones never remove what is observed in the same snapshot.
 */
public final class EntityMerger {
	/** Entity types that are never kept from an earlier capture unless observed again. */
	public static final Set<String> DEFAULT_TRANSIENT_TYPES = Set.of(
			"minecraft:item",
			"minecraft:experience_orb",
			"minecraft:arrow",
			"minecraft:spectral_arrow",
			"minecraft:trident",
			"minecraft:snowball",
			"minecraft:egg",
			"minecraft:ender_pearl",
			"minecraft:splash_potion",
			"minecraft:lingering_potion",
			"minecraft:experience_bottle",
			"minecraft:fireball",
			"minecraft:small_fireball",
			"minecraft:dragon_fireball",
			"minecraft:wither_skull",
			"minecraft:wind_charge",
			"minecraft:breeze_wind_charge",
			"minecraft:firework_rocket",
			"minecraft:llama_spit",
			"minecraft:shulker_bullet",
			"minecraft:area_effect_cloud",
			"minecraft:tnt",
			"minecraft:falling_block",
			"minecraft:lightning_bolt",
			"minecraft:eye_of_ender",
			"minecraft:evoker_fangs");

	/** One entity. {@code uuid} may be empty when the stored data has none (cannot be de-duplicated). */
	public record Entry<T>(String uuid, String type, T data) {
	}

	private EntityMerger() {
	}

	public static <T> List<Entry<T>> merge(List<Entry<T>> previous, List<Entry<T>> observed, boolean authoritative,
			Set<String> tombstones, Set<String> transientTypes) {
		Map<String, Entry<T>> out = new LinkedHashMap<>();
		int anonymous = 0;

		if (!authoritative) {
			for (Entry<T> e : previous) {
				if (tombstones.contains(e.uuid()) || transientTypes.contains(e.type())) {
					continue;
				}

				out.put(e.uuid().isEmpty() ? "#" + anonymous++ : e.uuid(), e);
			}
		}

		// Observations always win: a tombstone only removes earlier captures. An entity that left a
		// chunk and came back is observed again and must not be lost.
		for (Entry<T> e : observed) {
			// A newer observation replaces the earlier capture of the same entity.
			out.put(e.uuid().isEmpty() ? "#" + anonymous++ : e.uuid(), e);
		}

		return new ArrayList<>(out.values());
	}
}

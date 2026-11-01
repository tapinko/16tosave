package sk.tapusik.sixteentosave.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sk.tapusik.sixteentosave.capture.EntityMerger.Entry;

class EntityMergerTest {
	private static Entry<String> e(String uuid, String type, String data) {
		return new Entry<>(uuid, type, data);
	}

	private static List<String> data(List<Entry<String>> entries) {
		return entries.stream().map(Entry::data).toList();
	}

	@Test
	void authoritativeReplacesEverything() {
		List<Entry<String>> prev = List.of(e("1", "minecraft:cow", "old cow"), e("2", "minecraft:pig", "pig"));
		List<Entry<String>> seen = List.of(e("1", "minecraft:cow", "new cow"));
		assertEquals(List.of("new cow"), data(EntityMerger.merge(prev, seen, true, Set.of(), EntityMerger.DEFAULT_TRANSIENT_TYPES)));
	}

	@Test
	void mergeKeepsUnseenAndUpdatesSeen() {
		List<Entry<String>> prev = List.of(e("1", "minecraft:cow", "old cow"), e("2", "minecraft:pig", "pig"));
		List<Entry<String>> seen = List.of(e("1", "minecraft:cow", "new cow"), e("3", "minecraft:sheep", "sheep"));
		assertEquals(List.of("new cow", "pig", "sheep"),
				data(EntityMerger.merge(prev, seen, false, Set.of(), EntityMerger.DEFAULT_TRANSIENT_TYPES)));
	}

	@Test
	void mergeDropsTombstonedAndTransient() {
		List<Entry<String>> prev = List.of(
				e("1", "minecraft:cow", "dead cow"),
				e("2", "minecraft:item", "old item"),
				e("3", "minecraft:villager", "villager"));
		List<Entry<String>> seen = List.of(e("4", "minecraft:item", "new item"));
		assertEquals(List.of("villager", "new item"),
				data(EntityMerger.merge(prev, seen, false, Set.of("1"), EntityMerger.DEFAULT_TRANSIENT_TYPES)));
	}

	@Test
	void observationsWinOverTombstones() {
		// the cow walked away (tombstone) and came back before the chunk was written
		List<Entry<String>> prev = List.of(e("1", "minecraft:cow", "old cow"));
		List<Entry<String>> seen = List.of(e("1", "minecraft:cow", "returned cow"));
		assertEquals(List.of("returned cow"), data(EntityMerger.merge(prev, seen, false, Set.of("1"), Set.of())));
	}

	@Test
	void entriesWithoutUuidAreKeptButNotDeduplicated() {
		List<Entry<String>> prev = List.of(e("", "minecraft:armor_stand", "a"), e("", "minecraft:armor_stand", "b"));
		assertEquals(List.of("a", "b"), data(EntityMerger.merge(prev, List.of(), false, Set.of(), Set.of())));
	}
}

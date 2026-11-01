package sk.tapusik.sixteentosave.map;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

/**
 * Column colours for the rasterizer: the vanilla map colour, replaced by the biome's grass,
 * foliage or water colour where the block is tinted in game. Thread-safe.
 */
public final class BiomeTints implements TileRasterizer.ColumnColors {
	private final Registry<Biome> biomes;
	private final Map<String, Optional<Biome>> lookup = new ConcurrentHashMap<>();

	public BiomeTints(RegistryAccess registries) {
		this.biomes = registries.lookupOrThrow(Registries.BIOME);
	}

	@Override
	public int rgb(ChunkSummary chunk, int column, int blockX, int blockZ) {
		byte packed = chunk.color()[column];
		int base = BlockColors.rgb(ChunkSummary.mapColorId(packed));

		if (base < 0) {
			return base;
		}

		int tint = ChunkSummary.tint(packed);

		if (tint == ChunkSummary.TINT_NONE) {
			return base;
		}

		Optional<Biome> biome = biome(chunk.biomeAt(column));

		if (biome.isEmpty()) {
			return base;
		}

		try {
			return switch (tint) {
				case ChunkSummary.TINT_GRASS -> biome.get().getGrassColor(blockX, blockZ) & 0xFFFFFF;
				case ChunkSummary.TINT_FOLIAGE -> biome.get().getFoliageColor() & 0xFFFFFF;
				case ChunkSummary.TINT_WATER -> biome.get().getSpecialEffects().waterColor() & 0xFFFFFF;
				default -> base;
			};
		} catch (RuntimeException e) {
			// e.g. colour maps not loaded yet: fall back to the plain map colour
			return base;
		}
	}

	private Optional<Biome> biome(String id) {
		return lookup.computeIfAbsent(id, key -> {
			Identifier identifier = Identifier.tryParse(key);
			return identifier == null ? Optional.empty() : biomes.get(identifier).map(Holder::value);
		});
	}
}

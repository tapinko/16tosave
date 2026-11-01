package sk.tapusik.sixteentosave.storage;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/** Vanilla codecs for a chunk section's block and biome palettes (same construction as Bobby on 26.3). */
public final class ChunkCodecs {
	public static final Codec<PalettedContainer<BlockState>> BLOCKS = PalettedContainer.codecRW(
			BlockState.CODEC,
			Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
			Blocks.AIR.defaultBlockState());

	private ChunkCodecs() {
	}

	/** Biome palette codec bound to the server's biome registry. */
	public static Codec<PalettedContainerRO<Holder<Biome>>> biomes(RegistryAccess registries) {
		Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		return PalettedContainer.codecRO(
				biomes.holderByNameCodec(),
				Strategy.createForBiomes(biomes.asHolderIdMap()),
				biomes.get(Biomes.PLAINS.identifier()).orElseThrow());
	}
}

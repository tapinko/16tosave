package sk.tapusik.sixteentosave.map;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import sk.tapusik.sixteentosave.storage.ChunkCodecs;
import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * What the map shows right now: one dimension folder of one server, plus state shared between
 * the map screen (render thread) and the tile workers. A new generation number cancels all work
 * of the previous view (other dimension, other height limit).
 */
final class MapSession {
	final Path dimensionDir;
	final String dimensionId;
	final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
	final BiomeTints tints;
	private final AtomicLong generation = new AtomicLong();
	/** Newest summaries pushed by the writer while the map is open: region key → chunk index → summary. */
	private final Map<Long, Map<Integer, ChunkSummary>> live = new ConcurrentHashMap<>();

	MapSession(Path dimensionDir, String dimensionId, RegistryAccess registries) {
		this.dimensionDir = dimensionDir;
		this.dimensionId = dimensionId;
		this.biomeCodec = ChunkCodecs.biomes(registries);
		this.tints = new BiomeTints(registries);
	}

	long generation() {
		return generation.get();
	}

	long nextGeneration() {
		return generation.incrementAndGet();
	}

	void putLive(int chunkX, int chunkZ, ChunkSummary summary) {
		long region = ChunkKey.pack(chunkX >> 5, chunkZ >> 5);
		live.computeIfAbsent(region, k -> new ConcurrentHashMap<>()).put((chunkX & 31) + (chunkZ & 31) * 32, summary);
	}

	Map<Integer, ChunkSummary> live(int regionX, int regionZ) {
		Map<Integer, ChunkSummary> m = live.get(ChunkKey.pack(regionX, regionZ));
		return m == null ? Map.of() : Map.copyOf(m);
	}
}

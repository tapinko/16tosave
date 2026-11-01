package sk.tapusik.sixteentosave.map;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import sk.tapusik.sixteentosave.SixteenToSave;

/**
 * GPU textures of map tiles, one per (region, level of detail), least recently used dropped
 * first when over the memory budget. Render thread only. Each upload gets a fresh id. Textures
 * are released only at the start of the next frame ({@link #flushReleases}): a texture dropped
 * while a frame is being built may already be part of that frame.
 */
final class MapTextures {
	private record Key(int regionX, int regionZ, Lod lod) {
	}

	private record Entry(Identifier id, long bytes) {
	}

	private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
	private final TextureManager textureManager = Minecraft.getInstance().getTextureManager();
	private final long budgetBytes;
	private final List<Identifier> toRelease = new ArrayList<>();
	private long totalBytes;
	private int counter;

	MapTextures(long budgetBytes) {
		this.budgetBytes = budgetBytes;
	}

	Identifier get(int regionX, int regionZ, Lod lod) {
		Entry e = entries.get(new Key(regionX, regionZ, lod));
		return e == null ? null : e.id();
	}

	/** Uploads a square ARGB tile; returns its texture id. */
	Identifier upload(int regionX, int regionZ, Lod lod, int[] argb) {
		int size = lod.tileSize;
		NativeImage image = new NativeImage(size, size, false);

		for (int y = 0; y < size; y++) {
			int row = y * size;

			for (int x = 0; x < size; x++) {
				image.setPixel(x, y, argb[row + x]);
			}
		}

		Identifier id = Identifier.fromNamespaceAndPath(SixteenToSave.MOD_ID, "map/tile_" + counter++);
		String label = SixteenToSave.NAME + " map " + regionX + "," + regionZ + " " + lod;
		textureManager.register(id, new DynamicTexture(() -> label, image));

		Key key = new Key(regionX, regionZ, lod);
		Entry old = entries.put(key, new Entry(id, 4L * size * size));

		if (old != null) {
			release(old);
		}

		totalBytes += 4L * size * size;
		trim();
		return id;
	}

	/** Drops every level of one region (its raster changed). */
	void invalidate(int regionX, int regionZ) {
		for (Lod lod : Lod.values()) {
			Entry e = entries.remove(new Key(regionX, regionZ, lod));

			if (e != null) {
				release(e);
			}
		}
	}

	/** Call at the start of a frame, before anything is drawn. */
	void flushReleases() {
		for (Identifier id : toRelease) {
			textureManager.release(id);
		}

		toRelease.clear();
	}

	/** Drops all tiles; they are released at the next {@link #flushReleases} (or {@link #close}). */
	void releaseAll() {
		for (Entry e : entries.values()) {
			toRelease.add(e.id());
		}

		entries.clear();
		totalBytes = 0;
	}

	/** The screen is gone: release everything now. */
	void close() {
		releaseAll();
		flushReleases();
	}

	private void trim() {
		Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();

		while (totalBytes > budgetBytes && entries.size() > 1 && it.hasNext()) {
			Entry e = it.next().getValue();
			it.remove();
			release(e);
		}
	}

	private void release(Entry e) {
		toRelease.add(e.id());
		totalBytes -= e.bytes();
	}
}

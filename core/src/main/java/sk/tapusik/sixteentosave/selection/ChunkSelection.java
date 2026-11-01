package sk.tapusik.sixteentosave.selection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import sk.tapusik.sixteentosave.storage.ChunkKey;

/**
 * A set of chunks of one dimension (design §8.1), stored per region as 1024 bits
 * ({@code long[16]}, bit {@code (x & 31) + (z & 31) * 32}). Also used for the "universe": the
 * chunks that exist in the cache. Not thread-safe.
 */
public final class ChunkSelection {
	public static final int WORDS = 16;
	private static final int REGION_CHUNKS = 32;

	public enum Mode {
		/** the result is exactly the new area */
		REPLACE,
		/** the new area is added */
		ADD,
		/** the new area is removed */
		SUBTRACT
	}

	private final Map<Long, long[]> regions = new HashMap<>();
	private int count;

	public static int bitIndex(int chunkX, int chunkZ) {
		return (chunkX & 31) + (chunkZ & 31) * 32;
	}

	private static long regionKey(int chunkX, int chunkZ) {
		return ChunkKey.pack(chunkX >> 5, chunkZ >> 5);
	}

	public boolean contains(int chunkX, int chunkZ) {
		long[] bits = regions.get(regionKey(chunkX, chunkZ));

		if (bits == null) {
			return false;
		}

		int i = bitIndex(chunkX, chunkZ);
		return (bits[i >> 6] & (1L << (i & 63))) != 0;
	}

	public void set(int chunkX, int chunkZ, boolean selected) {
		long key = regionKey(chunkX, chunkZ);
		int i = bitIndex(chunkX, chunkZ);
		long mask = 1L << (i & 63);
		long[] bits = regions.get(key);

		if (selected) {
			if (bits == null) {
				bits = new long[WORDS];
				regions.put(key, bits);
			}

			if ((bits[i >> 6] & mask) == 0) {
				bits[i >> 6] |= mask;
				count++;
			}
		} else if (bits != null && (bits[i >> 6] & mask) != 0) {
			bits[i >> 6] &= ~mask;
			count--;
			dropIfEmpty(key, bits);
		}
	}

	/** Flips one chunk; returns whether it is selected now. */
	public boolean toggle(int chunkX, int chunkZ) {
		boolean now = !contains(chunkX, chunkZ);
		set(chunkX, chunkZ, now);
		return now;
	}

	public int count() {
		return count;
	}

	public boolean isEmpty() {
		return count == 0;
	}

	public void clear() {
		regions.clear();
		count = 0;
	}

	public ChunkSelection copy() {
		ChunkSelection c = new ChunkSelection();

		for (Map.Entry<Long, long[]> e : regions.entrySet()) {
			c.regions.put(e.getKey(), e.getValue().clone());
		}

		c.count = count;
		return c;
	}

	/** Region keys ({@link ChunkKey#pack} of region coordinates) with at least one chunk. */
	public Set<Long> regionKeys() {
		return Set.copyOf(regions.keySet());
	}

	/** A copy of a region's 1024 bits, or {@code null} if nothing in it is selected. */
	public long[] regionBits(int regionX, int regionZ) {
		long[] bits = regions.get(ChunkKey.pack(regionX, regionZ));
		return bits == null ? null : bits.clone();
	}

	public int countInRegion(int regionX, int regionZ) {
		long[] bits = regions.get(ChunkKey.pack(regionX, regionZ));
		return bits == null ? 0 : popCount(bits);
	}

	/** Replaces one region's bits (e.g. when loading). */
	public void setRegionBits(int regionX, int regionZ, long[] bits) {
		long key = ChunkKey.pack(regionX, regionZ);
		long[] old = regions.remove(key);

		if (old != null) {
			count -= popCount(old);
		}

		if (bits != null && bits.length == WORDS && popCount(bits) > 0) {
			regions.put(key, bits.clone());
			count += popCount(bits);
		}
	}

	/** Adds one region's bits to what is already there (e.g. newly cached chunks). */
	public void addRegionBits(int regionX, int regionZ, long[] bits) {
		if (bits != null && bits.length == WORDS) {
			applyMask(regionX, regionZ, bits.clone(), Mode.ADD, null);
		}
	}

	/** Calls {@code action} for every selected chunk. */
	public void forEach(ChunkAction action) {
		for (Map.Entry<Long, long[]> e : regions.entrySet()) {
			int baseX = ChunkKey.x(e.getKey()) * REGION_CHUNKS;
			int baseZ = ChunkKey.z(e.getKey()) * REGION_CHUNKS;
			long[] bits = e.getValue();

			for (int w = 0; w < WORDS; w++) {
				long word = bits[w];

				while (word != 0) {
					int b = Long.numberOfTrailingZeros(word);
					int i = (w << 6) + b;
					action.accept(baseX + (i & 31), baseZ + (i >> 5));
					word &= word - 1;
				}
			}
		}
	}

	@FunctionalInterface
	public interface ChunkAction {
		void accept(int chunkX, int chunkZ);
	}

	// ---- area operations (all limited to {@code universe} when it is not null) -----------

	/** Rectangle of chunks, corners inclusive and in any order. */
	public void applyRect(int x0, int z0, int x1, int z1, Mode mode, ChunkSelection universe) {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);

		if (mode == Mode.REPLACE) {
			clear();
		}

		for (int rz = minZ >> 5; rz <= maxZ >> 5; rz++) {
			for (int rx = minX >> 5; rx <= maxX >> 5; rx++) {
				long[] mask = new long[WORDS];
				int lx0 = Math.max(minX, rx * REGION_CHUNKS) - rx * REGION_CHUNKS;
				int lx1 = Math.min(maxX, rx * REGION_CHUNKS + 31) - rx * REGION_CHUNKS;
				int lz0 = Math.max(minZ, rz * REGION_CHUNKS) - rz * REGION_CHUNKS;
				int lz1 = Math.min(maxZ, rz * REGION_CHUNKS + 31) - rz * REGION_CHUNKS;
				long row = lx1 - lx0 == 31 ? 0xFFFFFFFFL : ((1L << (lx1 - lx0 + 1)) - 1) << lx0;

				for (int lz = lz0; lz <= lz1; lz++) {
					mask[lz >> 1] |= row << ((lz & 1) * 32);
				}

				applyMask(rx, rz, mask, mode, universe);
			}
		}
	}

	/** Chunks whose centre is within {@code radius} chunks of the centre chunk's centre. */
	public void applyCircle(int centerX, int centerZ, int radius, Mode mode, ChunkSelection universe) {
		int r = Math.max(0, radius);
		long r2 = (long) r * r;

		if (mode == Mode.REPLACE) {
			clear();
		}

		for (int rz = (centerZ - r) >> 5; rz <= (centerZ + r) >> 5; rz++) {
			for (int rx = (centerX - r) >> 5; rx <= (centerX + r) >> 5; rx++) {
				long[] mask = new long[WORDS];

				for (int i = 0; i < 1024; i++) {
					long dx = rx * REGION_CHUNKS + (i & 31) - centerX;
					long dz = rz * REGION_CHUNKS + (i >> 5) - centerZ;

					if (dx * dx + dz * dz <= r2) {
						mask[i >> 6] |= 1L << (i & 63);
					}
				}

				applyMask(rx, rz, mask, mode, universe);
			}
		}
	}

	/** Region click: selects every cached chunk of the region, or clears them if all were selected. */
	public void toggleRegion(int regionX, int regionZ, ChunkSelection universe) {
		long[] full = new long[WORDS];
		Arrays.fill(full, -1L);
		long[] available = universe == null ? full : intersect(full, universe.regions.get(ChunkKey.pack(regionX, regionZ)));
		long[] mine = regions.get(ChunkKey.pack(regionX, regionZ));
		boolean allSelected = popCount(available) > 0 && mine != null && popCount(intersect(available, mine)) == popCount(available);
		applyMask(regionX, regionZ, available, allSelected ? Mode.SUBTRACT : Mode.ADD, null);
	}

	/** Becomes a copy of {@code universe}. */
	public void selectAll(ChunkSelection universe) {
		clear();

		for (Map.Entry<Long, long[]> e : universe.regions.entrySet()) {
			regions.put(e.getKey(), e.getValue().clone());
		}

		count = universe.count;
	}

	/** Becomes {@code universe} minus what was selected. */
	public void invert(ChunkSelection universe) {
		Map<Long, long[]> next = new HashMap<>();
		int n = 0;

		for (Map.Entry<Long, long[]> e : universe.regions.entrySet()) {
			long[] mine = regions.get(e.getKey());
			long[] out = new long[WORDS];

			for (int w = 0; w < WORDS; w++) {
				out[w] = e.getValue()[w] & ~(mine == null ? 0 : mine[w]);
			}

			int c = popCount(out);

			if (c > 0) {
				next.put(e.getKey(), out);
				n += c;
			}
		}

		regions.clear();
		regions.putAll(next);
		count = n;
	}

	/** Drops chunks that are not in {@code universe}. */
	public void retainAll(ChunkSelection universe) {
		Iterator<Map.Entry<Long, long[]>> it = regions.entrySet().iterator();
		int n = 0;

		while (it.hasNext()) {
			Map.Entry<Long, long[]> e = it.next();
			long[] out = intersect(e.getValue(), universe.regions.get(e.getKey()));
			int c = popCount(out);

			if (c == 0) {
				it.remove();
			} else {
				e.setValue(out);
				n += c;
			}
		}

		count = n;
	}

	/** How many selected chunks are also in {@code universe}. */
	public int countWithin(ChunkSelection universe) {
		int n = 0;

		for (Map.Entry<Long, long[]> e : regions.entrySet()) {
			n += popCount(intersect(e.getValue(), universe.regions.get(e.getKey())));
		}

		return n;
	}

	private void applyMask(int regionX, int regionZ, long[] mask, Mode mode, ChunkSelection universe) {
		long key = ChunkKey.pack(regionX, regionZ);
		long[] area = universe == null ? mask : intersect(mask, universe.regions.get(key));
		long[] bits = regions.get(key);
		int before = bits == null ? 0 : popCount(bits);

		if (mode == Mode.SUBTRACT) {
			if (bits == null) {
				return;
			}

			for (int w = 0; w < WORDS; w++) {
				bits[w] &= ~area[w];
			}
		} else {
			if (popCount(area) == 0) {
				return;
			}

			if (bits == null) {
				bits = new long[WORDS];
				regions.put(key, bits);
			}

			for (int w = 0; w < WORDS; w++) {
				bits[w] |= area[w];
			}
		}

		count += popCount(bits) - before;
		dropIfEmpty(key, bits);
	}

	private void dropIfEmpty(long key, long[] bits) {
		if (popCount(bits) == 0) {
			regions.remove(key);
		}
	}

	private static long[] intersect(long[] a, long[] b) {
		long[] out = new long[WORDS];

		if (b == null) {
			return out;
		}

		for (int w = 0; w < WORDS; w++) {
			out[w] = a[w] & b[w];
		}

		return out;
	}

	static int popCount(long[] bits) {
		int n = 0;

		for (long word : bits) {
			n += Long.bitCount(word);
		}

		return n;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof ChunkSelection other) || other.count != count || other.regions.size() != regions.size()) {
			return false;
		}

		for (Map.Entry<Long, long[]> e : regions.entrySet()) {
			if (!Arrays.equals(e.getValue(), other.regions.get(e.getKey()))) {
				return false;
			}
		}

		return true;
	}

	@Override
	public int hashCode() {
		return Integer.hashCode(count) * 31 + regions.keySet().hashCode();
	}
}

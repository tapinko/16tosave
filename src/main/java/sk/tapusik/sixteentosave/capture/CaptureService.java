package sk.tapusik.sixteentosave.capture;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import sk.tapusik.sixteentosave.SixteenToSave;
import sk.tapusik.sixteentosave.config.ConfigManager;
import sk.tapusik.sixteentosave.config.ModConfig;
import sk.tapusik.sixteentosave.map.ChunkSummary;
import sk.tapusik.sixteentosave.map.MapIndex;
import sk.tapusik.sixteentosave.storage.CacheLayout;
import sk.tapusik.sixteentosave.storage.CacheMaintenance;
import sk.tapusik.sixteentosave.storage.CacheWriter;
import sk.tapusik.sixteentosave.storage.ChunkKey;
import sk.tapusik.sixteentosave.storage.ChunkSnapshot;
import sk.tapusik.sixteentosave.storage.DimensionStore;
import sk.tapusik.sixteentosave.storage.RateLimitedLog;

/**
 * Capture layer (design §5): watches chunk load/unload through Fabric events, snapshots chunks on
 * the client thread within a time budget, and hands the snapshots to the {@link CacheWriter}.
 * Every method here runs on the client thread and never blocks.
 */
public final class CaptureService {
	private static final long FIRST_CAPTURE_DELAY_NANOS = 5_000_000_000L;
	private static final int MAX_CHECKS_PER_TICK = 512;
	private static final long STOP_TIMEOUT_MILLIS = 15_000;

	/** One connection to one server. Its stores are closed once all its levels are saved. */
	static final class Session {
		final ServerIdentity server;
		final Path dir;
		final Map<ResourceKey<Level>, DimensionStore> stores = new HashMap<>();
		boolean ending;
		boolean closed;

		Session(ServerIdentity server, Path dir) {
			this.server = server;
			this.dir = dir;
		}
	}

	/** What the settings screen shows. */
	public record Status(String serverId, String serverName, String dimensionId, boolean active, int trackedChunks,
			CacheWriter.Stats writer) {
	}

	private final ConfigManager configManager;
	private final CacheLayout layout;
	private final MapIndex mapIndex = new MapIndex();
	private final CacheWriter writer;
	private final ContainerObserver containers = new ContainerObserver(this);

	private Session session;
	private TrackedLevel tracked;
	/** Levels that are no longer current but still have chunks to save. */
	private final ArrayDeque<TrackedLevel> finalizing = new ArrayDeque<>();

	/** Exports waiting until the chunks they need are captured; then the cache is flushed for them. */
	private final List<PendingFlush> pendingFlushes = new ArrayList<>();

	private record PendingFlush(Session session, CompletableFuture<Void> done) {
	}

	private Map<Long, List<Entity>> tickEntities;
	private ClientLevel tickEntitiesLevel;

	public CaptureService(ConfigManager configManager, CacheLayout layout) {
		this.configManager = configManager;
		this.layout = layout;
		ModConfig config = configManager.get();
		this.writer = new CacheWriter((long) config.capture.queueMiB << 20, () -> configManager.get().cache.maxSizeMiB << 20, mapIndex);
	}

	public void register() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> guarded("chunk load", () -> onChunkLoad(level, chunk)));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> guarded("chunk unload", () -> onChunkUnload(level, chunk)));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> guarded("entity unload", () -> onEntityUnload(entity, level)));
		ClientTickEvents.END_CLIENT_TICK.register(client -> guarded("tick", () -> onEndTick(client)));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> guarded("stop", () -> onClientStopping()));
		containers.register();
	}

	/** Capture must never break the game: every event handler goes through here. */
	private static void guarded(String what, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException | LinkageError e) {
			RateLimitedLog.warn("capture-" + what, "{}: capture failed during {}: {}", SixteenToSave.NAME, what, e.toString());
		}
	}

	// ---- events -------------------------------------------------------------------------

	private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		Minecraft client = Minecraft.getInstance();

		if (level != client.level) {
			return; // not the level the player is in (e.g. another mod's preview level)
		}

		syncLevel(client, level);

		if (tracked != null && tracked.level == level) {
			tracked.onLoad(chunk, System.nanoTime());
		}
	}

	private void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		TrackedLevel owner = trackedFor(level);

		if (owner == null) {
			return;
		}

		long key = TrackedLevel.key(chunk);

		if (owner.remove(key) == null) {
			return;
		}

		// Right after this event vanilla clears the chunk's block entities, so snapshot now.
		DimensionStore store = storeFor(owner);

		if (store != null) {
			writer.submit(finalSnapshot(owner, store, chunk));
		}

		owner.forgetObserved(key);
	}

	private void onEntityUnload(Entity entity, ClientLevel level) {
		TrackedLevel owner = trackedFor(level);

		if (owner == null || !configManager.get().capture.entities
				|| !(entity instanceof LivingEntity living) || !living.isDeadOrDying()) {
			return;
		}

		DimensionStore store = storeFor(owner);

		if (store == null) {
			return;
		}

		long key = ChunkKey.pack(Mth.floor(entity.getX()) >> 4, Mth.floor(entity.getZ()) >> 4);
		String uuid = entity.getUUID().toString();
		writer.addTombstones(store, key, List.of(uuid), owner.isLoaded(key));
		owner.markDirty(key);
		owner.entityChunk.remove(uuid);
	}

	private void onEndTick(Minecraft client) {
		try {
			syncLevel(client, client.level);
			ModConfig config = configManager.get();
			TickBudget budget = TickBudget.ofMicros(config.capture.tickBudgetMicros).start();
			saveFinalizingLevels(budget, false);

			if (tracked != null) {
				liveSnapshots(client, budget, config);
			}

			startDueFlushes();
		} finally {
			tickEntities = null;
			tickEntitiesLevel = null;
		}
	}

	private void onClientStopping() {
		if (tracked != null) {
			startFinalizing(tracked);
			tracked = null;
		}

		saveFinalizingLevels(null, true);
		endSession();
		writer.shutdown(STOP_TIMEOUT_MILLIS);
	}

	void onContainerObserved(ClientLevel level, BlockPos pos, BlockEntity blockEntity) {
		TrackedLevel owner = trackedFor(level);

		if (owner != null) {
			owner.recordObserved(pos, blockEntity);
		}
	}

	// ---- level / session lifecycle ------------------------------------------------------

	/** Follows {@code client.level}: a different object means a dimension change, respawn or disconnect. */
	private void syncLevel(Minecraft client, ClientLevel level) {
		if (tracked != null && tracked.level == level) {
			return;
		}

		if (tracked != null) {
			startFinalizing(tracked);
			tracked = null;
		}

		if (level == null) {
			endSession();
			return;
		}

		ServerIdentity server = ServerIdentity.current(client);

		if (server == null) {
			// singleplayer: nothing to capture, the world is already on disk
			endSession();
			return;
		}

		if (session == null || !session.server.id().equals(server.id())) {
			endSession();
			session = new Session(server, layout.serverDir(server.id()));
			SixteenToSave.LOGGER.info("{}: capturing for server {} into {}", SixteenToSave.NAME, server.id(), session.dir);
		}

		tracked = new TrackedLevel(level, session);
	}

	private void startFinalizing(TrackedLevel level) {
		level.finalizing = true;
		finalizing.addLast(level);
	}

	private void endSession() {
		if (session != null) {
			session.ending = true;
			Session ended = session;
			session = null;
			maybeCloseSession(ended);
		}
	}

	private void maybeCloseSession(Session s) {
		if (!s.ending || s.closed) {
			return;
		}

		for (TrackedLevel level : finalizing) {
			if (level.session == s) {
				return;
			}
		}

		if (tracked != null && tracked.session == s) {
			return;
		}

		s.closed = true;
		writer.closeSession(s.dir, new ArrayList<>(s.stores.values()));
	}

	private TrackedLevel trackedFor(ClientLevel level) {
		if (tracked != null && tracked.level == level) {
			return tracked;
		}

		for (TrackedLevel old : finalizing) {
			if (old.level == level) {
				return old;
			}
		}

		return null;
	}

	/** The store to write this level's chunks to, or {@code null} if capture is off for it. */
	private DimensionStore storeFor(TrackedLevel level) {
		ModConfig config = configManager.get();
		String serverId = level.session.server.id();

		if (!config.capture.enabled || !config.isServerEnabled(serverId) || !config.isDimensionEnabled(level.dimensionId)) {
			return null;
		}

		ResourceKey<Level> dimension = level.level.dimension();
		DimensionStore store = level.session.stores.get(dimension);

		if (store == null) {
			try {
				store = new DimensionStore(serverId, dimension, layout.dimensionDir(serverId, level.dimensionId),
						level.level.registryAccess());
			} catch (RuntimeException e) {
				RateLimitedLog.warn("store-" + level.dimensionId, "{}: cannot capture {}: {}", SixteenToSave.NAME, level.dimensionId, e.toString());
				return null;
			}

			level.session.stores.put(dimension, store);
		}

		return store;
	}

	// ---- snapshots ----------------------------------------------------------------------

	private void saveFinalizingLevels(TickBudget budget, boolean all) {
		while (!finalizing.isEmpty() && (all || budget.tryUse())) {
			TrackedLevel old = finalizing.peekFirst();
			TrackedLevel.Tracked t = old.pollAny();

			if (t == null) {
				finalizing.removeFirst();
				maybeCloseSession(old.session);
				continue;
			}

			DimensionStore store = storeFor(old);

			if (store != null) {
				writer.submit(finalSnapshot(old, store, t.chunk));
			}
		}
	}

	private void liveSnapshots(Minecraft client, TickBudget budget, ModConfig config) {
		DimensionStore store = storeFor(tracked);

		if (store == null) {
			return;
		}

		long now = System.nanoTime();
		long interval = config.capture.liveIntervalSeconds * 1_000_000_000L;

		// urgent first: containers just seen, chunks an export is waiting for
		for (TrackedLevel.Tracked t = tracked.nextUrgent(); t != null; t = tracked.nextUrgent()) {
			if (!writer.hasRoomFor(Snapshotter.typicalLiveEstimate()) || !budget.tryUse() || !captureLive(store, t, now)) {
				tracked.retryUrgent(t);
				return;
			}
		}

		int checks = Math.min(tracked.size(), MAX_CHECKS_PER_TICK);

		for (int i = 0; i < checks; i++) {
			TrackedLevel.Tracked t = tracked.next();

			if (t == null) {
				break;
			}

			boolean due = t.captured
					? (t.dirty || t.chunk.isUnsaved()) && now - t.lastCapturedNanos >= interval
					: now - t.loadedAtNanos >= FIRST_CAPTURE_DELAY_NANOS;

			if (!due) {
				continue;
			}

			if (!writer.hasRoomFor(Snapshotter.typicalLiveEstimate()) || !budget.tryUse()) {
				break;
			}

			if (!captureLive(store, t, now)) {
				break; // stays dirty, retried later
			}
		}
	}

	/** Snapshots a loaded chunk and queues it; {@code false} if the writer did not take it. */
	private boolean captureLive(DimensionStore store, TrackedLevel.Tracked t, long now) {
		ChunkSnapshot snapshot = Snapshotter.take(store, t.chunk, false, tracked.observedPositions(t.chunk),
				captureEntities(tracked, store, t.chunk), System.currentTimeMillis());

		if (writer.submit(snapshot) != CacheWriter.SubmitResult.ACCEPTED) {
			return false;
		}

		t.chunk.tryMarkSaved();
		t.captured = true;
		t.dirty = false;
		t.lastCapturedNanos = now;
		return true;
	}

	/** Flushes for exports whose chunks are all captured now (or that can't be captured here). */
	private void startDueFlushes() {
		if (pendingFlushes.isEmpty()) {
			return;
		}

		boolean waiting = tracked != null && storeFor(tracked) != null && tracked.hasUrgent();

		if (waiting) {
			return;
		}

		for (PendingFlush f : pendingFlushes) {
			writer.closeWhenDrained(new ArrayList<>(f.session().stores.values()), () -> f.done().complete(null));
		}

		pendingFlushes.clear();
	}

	private ChunkSnapshot finalSnapshot(TrackedLevel owner, DimensionStore store, LevelChunk chunk) {
		return Snapshotter.take(store, chunk, true, owner.observedPositions(chunk),
				captureEntities(owner, store, chunk), System.currentTimeMillis());
	}

	private ChunkSnapshot.EntityCapture captureEntities(TrackedLevel owner, DimensionStore store, LevelChunk chunk) {
		ModConfig config = configManager.get();

		if (!config.capture.entities) {
			return null;
		}

		long key = TrackedLevel.key(chunk);
		List<Entity> inChunk = entitiesOf(owner).getOrDefault(key, List.of());
		List<EntityMerger.Entry<CompoundTag>> saved = EntityCollector.save(inChunk);

		// An entity now stored here must disappear from the chunk it was stored in before.
		Map<Long, List<String>> movedFrom = new HashMap<>();

		for (EntityMerger.Entry<CompoundTag> e : saved) {
			Long previous = owner.entityChunk.put(e.uuid(), key);

			if (previous != null && previous.longValue() != key) {
				movedFrom.computeIfAbsent(previous, k -> new ArrayList<>()).add(e.uuid());
			}
		}

		for (Map.Entry<Long, List<String>> e : movedFrom.entrySet()) {
			boolean loaded = owner.isLoaded(e.getKey()) && !owner.finalizing;
			writer.addTombstones(store, e.getKey(), e.getValue(), loaded);
			owner.markDirty(e.getKey());
		}

		LocalPlayer player = Minecraft.getInstance().player;
		boolean authoritative = !owner.finalizing
				&& player != null
				&& Minecraft.getInstance().level == owner.level
				&& ChunkGeometry.isChunkWithin(player.getX(), player.getZ(), chunk.getPos().x(), chunk.getPos().z(),
				config.capture.entityReplaceRange);

		return new ChunkSnapshot.EntityCapture(saved, authoritative);
	}

	/** Entities by chunk: one pass per tick for the current level, one pass in total for an old one. */
	private Map<Long, List<Entity>> entitiesOf(TrackedLevel owner) {
		if (owner.finalizing) {
			if (owner.frozenEntities == null) {
				owner.frozenEntities = EntityCollector.bucket(owner.level);
			}

			return owner.frozenEntities;
		}

		if (tickEntities == null || tickEntitiesLevel != owner.level) {
			tickEntities = EntityCollector.bucket(owner.level);
			tickEntitiesLevel = owner.level;
		}

		return tickEntities;
	}

	// ---- settings screen ------------------------------------------------------------------

	public boolean observesContainers() {
		ModConfig config = configManager.get();
		return config.capture.enabled && config.capture.observeContainers;
	}

	public Status status() {
		Session s = tracked != null ? tracked.session : session;
		boolean active = tracked != null && storeFor(tracked) != null;
		return new Status(
				s == null ? null : s.server.id(),
				s == null ? null : s.server.displayName(),
				tracked == null ? null : tracked.dimensionId,
				active,
				tracked == null ? 0 : tracked.size(),
				writer.stats());
	}

	// ---- map ------------------------------------------------------------------------------

	/** Cache folder of the server the player is on, or {@code null} when not on a server. */
	public Path currentServerDir() {
		Session s = tracked != null ? tracked.session : session;
		return s == null ? null : s.dir;
	}

	/** Dimension id of the level the player is in, or {@code null} when not capturing anywhere. */
	public String currentDimensionId() {
		return tracked == null ? null : tracked.dimensionId;
	}

	public void addMapListener(MapIndex.Listener listener) {
		mapIndex.addListener(listener);
	}

	public void removeMapListener(MapIndex.Listener listener) {
		mapIndex.removeListener(listener);
	}

	/** Writes the map index files that are still only in memory (background). */
	public void flushMapIndex() {
		writer.execute(mapIndex::flushNow);
	}

	/** Stores summaries the map rebuilt from region files, so the next open is instant (background). */
	public void persistRebuiltSummaries(Path dimensionDir, int regionX, int regionZ, Map<Integer, ChunkSummary> summaries) {
		writer.execute(() -> mapIndex.putRebuilt(dimensionDir, regionX, regionZ, summaries));
	}

	/** Measures the current server's cache in the background; {@code done} gets bytes or -1 (writer thread). */
	public void measureCurrentServer(LongConsumer done) {
		Session s = tracked != null ? tracked.session : session;

		if (s == null) {
			done.accept(-1);
			return;
		}

		Path dir = s.dir;
		writer.execute(() -> done.accept(CacheMaintenance.sizeOf(dir)));
	}

	/**
	 * Gets the cache ready for an export: loaded chunks of {@code selection} in the player's
	 * dimension that changed since their last capture (a chest just opened, a block just placed)
	 * are captured first, spread over the next ticks within the tick budget; then everything
	 * queued for the server is written and its region files are closed, so the files on disk are
	 * complete. Capturing goes on; the files are reopened by the next write. Completes on the
	 * writer thread. Call on the client thread.
	 *
	 * @param selection chunk keys ({@code ChunkKey.pack}) to bring up to date in the player's dimension
	 */
	public CompletableFuture<Void> flushCurrentServer(java.util.function.LongPredicate selection) {
		Session s = tracked != null ? tracked.session : session;

		if (s == null) {
			return CompletableFuture.completedFuture(null);
		}

		if (tracked != null && selection != null) {
			tracked.markChangedUrgent(selection);
		}

		CompletableFuture<Void> done = new CompletableFuture<>();
		pendingFlushes.add(new PendingFlush(s, done));
		return done;
	}

	/** Deletes the current server's cache; {@code done} runs on the client thread afterwards. */
	public void clearCurrentServer(Runnable done) {
		Session s = tracked != null ? tracked.session : session;

		if (s == null) {
			done.run();
			return;
		}

		SixteenToSave.LOGGER.info("{}: clearing cache for {}", SixteenToSave.NAME, s.server.id());
		writer.clearServer(s.dir, new ArrayList<>(s.stores.values()), () -> Minecraft.getInstance().execute(() -> {
			if (tracked != null && tracked.session == s) {
				tracked.resetCaptureState();
			}

			done.run();
		}));
	}
}

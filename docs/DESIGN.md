# 16tosave — Design Document

Status: **approved** (Q1–Q4 answered, §3) · Target: Minecraft Java 26.3 · Fabric · client-only
Display name **16tosave**, Fabric mod id `sixteentosave`, package `sk.tapusik.sixteentosave`, license EUPL-1.2.

This document was step 1 of the process in the build request; implementation follows the stages in §16.
Everything marked **[V]** was verified against a real 26.3 source (listed in §1); everything
marked **[U]** is unverified and listed in §13 together with how it will be checked.

---

## 0. Summary

While connected to a server, the mod snapshots the chunks the client already holds, writes them
into its own Anvil cache, shows them on a top-down map where you select chunks, and exports the
selection into a playable singleplayer world.

Key design calls (details in later sections):

| Area | Decision |
|---|---|
| Names | **Mojang official names**, not Yarn. Yarn does not exist for 26.x (§2.1). |
| Capture hook | **(b) snapshot `LevelChunk` from the client cache** via Fabric `ClientChunkEvents`, plus periodic re-snapshots of dirty loaded chunks. |
| Chunk NBT | Assembled from vanilla codecs (`PalettedContainer` codecs, block entity save, entity save), written with vanilla `SimpleRegionStorage`/`IOWorker`; validated with vanilla `SerializableChunkData.parse` at export time. |
| Mixins | **None planned.** Three contingency mixins are pre-designed in case an assumption fails (§9). |
| Fabric API | 3 modules: `fabric-key-mapping-api-v1`, `fabric-screen-api-v1`, `fabric-lifecycle-events-v1` (§10). |
| Export layout | Resolved by vanilla `LevelStorageAccess#getDimensionPath` — on 26.3 that is `saves/<w>/dimensions/minecraft/overworld/region` (§1). |
| level.dat | Since 26.1, world-gen settings and game rules are no longer in `level.dat`; the exporter must also write `data/minecraft/*.dat` saved data (§2.2, §8). |
| Code split | Gradle sub-project `core/` (pure Java, no Minecraft on the classpath) holds coordinate/LOD/tile/selection logic and its unit tests. |

---

## 1. What was verified, and how

The Mojang/Fabric download hosts are blocked from this build container (see §14), so the game
jar could not be inspected directly yet. Verification instead used source code that compiles
against 26.3 and data extracted from the 26.3 jar:

| # | Fact | Value | Source |
|---|---|---|---|
| F1 | Yarn for 26.x | **Does not exist.** Last Yarn branch is `25w46a` (1.21.11 cycle). Fabric: "As mappings are not provided for 26.1 …" | `FabricMC/yarn` branch list; `FabricMC/fabricmc.net` post 2026-03-14 |
| F2 | Loom plugin | `net.fabricmc.fabric-loom` (non-remapping); `implementation`, not `modImplementation`; `jar`, not `remapJar` | Fabric 26.1 post; `FabricMC/fabric-example-mod@26.3` (2026-10-24) |
| F3 | Toolchain | MC `26.3`, Loom `1.19-SNAPSHOT` (resolved 1.19.3), Fabric API `0.165.0+26.3`, Gradle `9.8.2`. Loader: compiled against `0.19.5`, requires `>=0.19.3` – the same minimum Fabric API 26.3 declares (the example mod's 0.20.1 is newer than the launchers' default) | example mod @26.3; `FabricMC/fabric@26.3` `gradle.properties` |
| F4 | Minimum Java | **Java 25** (`options.release = 25`, `"java": ">=25"`) | example mod @26.3; Fabric 26.1 post |
| F5 | DataVersion 26.3 | **5023** (protocol 777, released 2026-09-15). Code never hard-codes it: `SharedConstants.getCurrentVersion().dataVersion().version()` | `misode/mcmeta` tag `26.3-summary/version.json` (extracted from the jar); Bobby 26.3 for the accessor |
| F6 | Dimension folders | Vanilla opens chunks at `getDimensionPath(dim).resolve("region")` and entities at `….resolve("entities")`. On 26.x `getDimensionPath` gives `dimensions/<ns>/<path>` → overworld = `saves/<w>/dimensions/minecraft/overworld/region` | Moonrise `mc/26.3` (`ChunkMapMixin`, `ServerLevelMixin` mirror the vanilla constructors); MCA Selector master (2026-09) `FileHelper`; Paper 26.1 notes |
| F7 | level.dat split (26.1) | World-gen settings, game rules, weather, wandering trader, custom boss events → server `SavedData`; dragon fight → per-dimension saved data. `data/minecraft/world_gen_settings.dat` = `{data:{seed, generate_structures, bonus_chest, dimensions:{…}}, DataVersion}` | NeoForged 26.1 primer "Splitting the Primary Level Data into Saved Data"; MCA Selector template files (decoded; from a 26.1 snapshot) |
| F8 | Writing a fresh world's data on 26.3 | `PrimaryLevelData#createTag(null)` wrapped as `{Data: …}` → `NbtIo.writeCompressed`; `new SavedDataStorage(world.resolve("data"), DataFixers.getDataFixer(), registries)`, `.set(WorldGenSettings.TYPE, …)`, `.set(GameRuleMap.TYPE, …)`, `.saveAndJoin()` | Fabric API 26.3, `fabric-client-gametest-api-v1` `CreateWorldScreenMixin` |
| F9 | `SerializableChunkData` | `copyOf(ServerLevel, ChunkAccess)` needs a **ServerLevel** (unusable on the client); `parse(level, PalettedContainerFactory, CompoundTag)`; `read(ServerLevel, PoiManager, RegionStorageInfo, ChunkPos)` calls `PoiManager#checkConsistencyWithBlocks` → **missing POI is rebuilt from blocks on load**; `isLightOn` → light-correct flag; 26.3 removed the carving mask | Moonrise `mc/26.3` mixins; Fabric API 26.3 data-attachment mixin; NeoForged 26.3 primer |
| F10 | Client-side chunk serialization | `PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), air)`; biomes `codecRO(biomeRegistry.holderByNameCodec(), Strategy.createForBiomes(…asHolderIdMap()), plains)`; `LevelChunk#getBlockEntityNbtForSaving(pos, registryAccess)`; `getPersistedStatus().heightmapsAfter()`; `SimpleRegionStorage(RegionStorageInfo, Path, DataFixer, boolean, DataFixTypes)` with `write`/`read`; `RegionStorageInfo(levelId, dimension, type)` | Bobby master (`minecraftVersion = 26.3`) `ChunkSerializer`, `FakeChunkStorage` |
| F11 | Entity region format | `{DataVersion, Entities:[…], Position: ChunkPos.CODEC}`; entity NBT via `entity.save(TagValueOutput.createWithContext(reporter, registryAccess))` (returns `boolean`) | Moonrise `mc/26.3` `ChunkEntitySlices` |
| F12 | Fabric client events | `ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD` (hooked into `ClientChunkCache#replaceWithPacketData`, `#drop`, `#updateViewRadius`); `ClientTickEvents.END_CLIENT_TICK`; `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` (non-null levels only); `ScreenEvents.AFTER_INIT`, `ScreenEvents.remove(screen)`, `Screens.getWidgets`; `KeyMappingHelper.registerKeyMapping`, `KeyMapping.Category.register(Identifier)` | `FabricMC/fabric@26.3` sources and testmods |
| F13 | GUI/input API | `GuiGraphics` → `GuiGraphicsExtractor`, `render` → `extractRenderState`; `blit(RenderPipelines.GUI_TEXTURED, …)`; `minecraft.gui.screen()` / `gui.setScreen()`; SDL replaced GLFW in 26.3 (use `InputConstants`, never raw codes); `MouseButtonEvent`/`KeyEvent` carry modifiers | NeoForged 1.21.9/26.1 primers; Fabric 26.2/26.3 posts; Fabric API 26.3 testmods |
| F14 | Void/flat data | `the_void` flat preset = layers `[air×1]`, biome `the_void`, `features: true` (that spawns the start platform, so the export uses `features: false`); `world_preset/flat.json` keeps vanilla noise generators for Nether/End; overworld `min_y -64`, `height 384`; nether `0/256`, `logical_height 128` | `misode/mcmeta` tag `26.3-data` |
| F15 | Misc renames | `ResourceLocation` → `Identifier`; `ChunkPos` is a record (`x()`, `z()`, `getRegionX()`); `LevelStorageSource#validateAndCreateAccess`, `LevelStorageAccess#getLevelPath(LevelResource.DATA)` | example mod; 26.1 primer; Bobby; Fabric API 26.3 |

---

## 2. Spec items that 26.3 forces me to change

### 2.1 Yarn → Mojang official names (not optional)
26.1 is the first unobfuscated release; Fabric stopped Yarn and Loom no longer remaps. All class
and member names in this project are Mojang's own names as shipped in the jar. Consequence for
verification: names can be checked directly against the jar with `javap`, no mapping lookup (§13).

### 2.2 "Generate level.dat yourself" now means level.dat **plus** saved data
`level.dat` no longer holds world-gen settings or game rules (F7). A world with only `level.dat`
would fall back to default world generation, i.e. regular terrain next to the export. The
exporter writes `level.dat`, `data/minecraft/world_gen_settings.dat` and the game-rules saved
data (§8.4).

### 2.3 No `poi/` cache
The client never receives POI data, and vanilla rebuilds missing POI from block states when a
chunk loads (F9). Writing POI ourselves would duplicate vanilla logic and add nothing. The cache
and the export have `region/` and `entities/` only. If you want the `poi/` folders anyway, they
can be created empty, but they won't hold anything.

### 2.4 Light is not stored
Client light is computed locally and is often wrong at the edge of view distance. Chunks are
written with `isLightOn: false`, so the integrated server relights them on first load (F9).
This costs a one-time relight when the exported world is first opened.

---

## 3. Decisions (answered)

**Q1 — Nether/End generator vs. "no warning" → per-export option, default void.**
A void overworld is always "stable" in vanilla. From pre-26 code I expect a non-vanilla
Nether/End generator to mark the world as *experimental*, which shows a warning on every load
**[U4]**. The export dialog has a "Nether/End generator" choice: **Void** (default) or
**Vanilla** (no warning, but unselected chunks there generate real terrain). Next to *Void* it
says that it may show the experimental warning. If U4 turns out to be false, the note goes away.

**Q2 — Map data source → derived summary index.**
Decoding every chunk each time the map opens would take tens of seconds for 50k chunks. So
there is a derived summary index per region (top block, height, biome and water depth per
column, about 0.5 KB per chunk compressed). It is built from the Anvil data, not from
screenshots, can be deleted and rebuilt at any time, and is invalidated by the chunk timestamps
in the `.mca` header. It is used only while the height slider is at max; moving the slider down
always decodes the real chunk data.

**Q3 — Where to build → on your machine.** I write the code against the verified sources in §1.
You run `./gradlew build` locally and send me the output. Since v1 has no mixins, a successful
compile already checks every vanilla name the code uses; the remaining [U] items are runtime
behaviour, and the manual test steps after each stage check them.

**Q4 — Identity & license → "16tosave", EUPL-1.2.**
- Display name, jar name and data folders: `16tosave` (`.minecraft/16tosave/`, `config/16tosave.json`).
- Fabric mod id: `sixteentosave`. Fabric Loader only accepts ids matching `[a-z][a-z0-9-_]{1,63}`,
  so an id can't start with a digit (checked in `fabric-loader` `MetadataVerifier`).
- Java package: `sk.tapusik.sixteentosave`. A Java package segment can't start with a digit either.
- License: European Union Public Licence v1.2 (SPDX `EUPL-1.2`), in `LICENSE` and in the
  `license` field of `fabric.mod.json`.

---

## 4. Architecture

```
 client thread                         background                          disk
 ─────────────                         ──────────                          ────
 ClientChunkEvents ─┐
 END_CLIENT_TICK  ──┼─► CaptureScheduler ──► SnapshotQueue ──► Serializer ──► IOWorker (vanilla)
 ScreenEvents     ──┘   (time-budgeted)      (byte-bounded,     thread        ├─ region/r.X.Z.mca
                                             coalescing)                      └─ entities/r.X.Z.mca
                                                                     │
 MapScreen ◄── TileTextures ◄── upload budget ◄── TileWorkers ◄──────┘ (read path + summary index)
     │
     └─ Selection (core) ─► ExportJob thread ─► saves/<world>/…
```

Thread rules:
- The client thread only does O(1) reference capture and small, time-budgeted copies
  (default ≤ 1.5 ms per tick in total). It never blocks on a queue, lock or I/O.
- One serializer thread turns snapshots into NBT.
- Disk writes go through vanilla `IOWorker`, which has its own thread and merges repeated writes
  to the same chunk.
- 2 tile workers by default (configurable) decode and rasterize map tiles.
- The export runs on its own thread.
- GPU uploads happen on the render thread with a per-frame budget.

---

## 5. Capture layer

### 5.1 Hook choice: (b) snapshot from the client chunk cache — chosen

**Why (b):** the client already applies every block update, section update, block-entity update
and light update to its `LevelChunk`. A snapshot taken when the chunk unloads (or periodically
while it stays loaded) therefore shows the latest state. That covers the "blocks change after
first receipt" requirement for free.

**Why not (a):** to stay correct, a packet-level mixin would have to keep a shadow copy of every
chunk and re-implement how vanilla applies `ClientboundBlockUpdatePacket`,
`ClientboundSectionBlocksUpdatePacket`, `ClientboundBlockEntityDataPacket`, chunk-batch and light
packets. That duplicates vanilla logic, doubles memory, and needs several mixins into
`ClientPacketListener`, whose handler signatures change often.

**How it hooks in, with no mixins:** Fabric's `ClientChunkEvents.CHUNK_UNLOAD` already covers
all three unload paths inside `ClientChunkCache` (F12).

### 5.2 Snapshot kinds
1. **Final snapshot (unload).** The chunk is about to be dropped and nothing will change it
   again, so we keep *references* to its section array, block-entity map, heightmaps and the
   entities in it (no deep copy, so a teleport burst of hundreds of unloads costs about a
   microsecond per chunk). Block-entity and entity NBT is then serialized on the client thread
   within the tick budget, because some `save` implementations touch level state. Palette
   encoding and compression happen off-thread.
2. **Live snapshot (dirty, still loaded).** Every *N* seconds (default 30) the scheduler takes
   chunks marked dirty and deep-copies their sections (`PalettedContainer#copy` **[U2]**) within
   the tick budget. The dirty signal is `LevelChunk#isUnsaved()`, cleared with `tryMarkSaved()`
   after the copy. This relies on client-side block updates setting the flag **[U1]**;
   contingency C1 covers the case where they don't.
3. **Session end.** The scheduler keeps its own set of references to loaded chunks (from
   `CHUNK_LOAD`/`CHUNK_UNLOAD`). On every `END_CLIENT_TICK` it compares `client.level` with the
   previous one; on a change (dimension change or disconnect, where vanilla drops the old level
   without per-chunk unload events) it final-snapshots the previous level's chunks from those
   references, which are still intact in memory. This is why the networking module isn't needed.

### 5.3 What goes into a chunk tag
Assembled the Bobby way (F10), from vanilla codecs:

- `DataVersion` (runtime), `xPos`, `zPos`, `yPos` (min section), `Status: "minecraft:full"`,
  `isLightOn: false`
- `sections[]` with `Y`, `block_states`, `biomes`; no `BlockLight`/`SkyLight`
- `block_entities[]` from `getBlockEntityNbtForSaving`
- `Heightmaps` for the types the client has (missing types are recomputed by vanilla on load **[U13]**)
- mod-private compound `16tosave:meta`: capture timestamp, incomplete-BE flag, positions of
  BEs with observed container contents. It is stripped on export.

Not written: ticks, `structures`, `PostProcessing`, carving data. The server never sends them.

Rejected alternatives: `SerializableChunkData.copyOf` needs a `ServerLevel`; building the
`SerializableChunkData` record by hand breaks every version (26.3 just removed a component).
Correctness check: every exported chunk is run through vanilla `SerializableChunkData.parse`
first (§8.3).

### 5.4 Block entities and container contents
- From the chunk packet, the client gets only the "update tag" of each block entity: signs,
  banners, skulls, spawner display, campfire items and similar. **Chest, barrel, shulker, hopper,
  furnace, brewing stand, crafter, dispenser/dropper, lectern, chiseled bookshelf, beehive and
  jukebox contents are not sent.**
- **Container observation (no packets, no mixins):** `ScreenEvents.AFTER_INIT` on an
  `AbstractContainerScreen` records `minecraft.hitResult` (the block or entity the player just
  opened). On `ScreenEvents.remove`, the container slots of the menu (player-inventory slots
  excluded) are written as `Items` into that block entity's tag in the next snapshot. A double
  chest's 54 slots are vanilla's `CompoundContainer(first, second)` (confirmed in the 26.3
  sources); the first half is the chest whose `ChestBlock.TYPE` is `RIGHT`, the other half is
  found with `ChestBlock.getConnectedDirection`. Ender chests are skipped
  because their contents belong to the player. Entity inventories (chest boats, minecarts,
  donkeys/llamas) through `EntityHitResult` come later. **[U15]**
- Observed contents are written into the client's own block entity (client memory only), so the
  normal snapshot path saves them with vanilla code. The chunk is captured right away (an
  "urgent" queue ahead of the round-robin, still within the tick budget), not after the 30 s
  live interval, so the contents reach the cache and the map within a tick or two.
- Before an export, the loaded chunks of the selection in the player's dimension that changed
  since their last capture are queued as urgent too; the cache is flushed only after they are
  captured, so what you just opened or built is in the export. When a chunk is captured again later without
  a new observation, the earlier contents are carried over from the cache.
- **Incomplete marking:** a chunk is "BE-incomplete" if it contains a block entity of a type in
  the server-only list above whose contents weren't observed. This is stored in
  `16tosave:meta`, shown on the map as an optional overlay, and listed in the export metadata.
  Nothing is ever made up.

### 5.5 Entities
- Saved with vanilla `entity.save(TagValueOutput…)` (F11). Players are never saved (vanilla
  refuses to encode them). Passengers are saved through their root vehicle.
- **Tracking-range problem:** servers send entities only within entity tracking range, which is
  usually smaller than view distance. A chunk at the edge of view can look empty even though it
  has entities.
- **Merge policy:**
  - A snapshot *replaces* a chunk's entity list only if the whole chunk was within the minimum
    tracking range of the player at that moment (default 24 blocks, configurable; Spigot/Paper
    send item frames and paintings only within 32 blocks by default).
  - Otherwise it *merges*: it adds and updates the entities it saw and keeps the ones captured
    earlier.
  - An entity seen dying (`isDeadOrDying`) is removed from its chunk ("tombstone"). An entity
    captured in a different chunk than before is removed from the old one. Tombstones never remove
    an entity observed in the same snapshot.
  - Transient types (items, XP orbs, projectiles, falling blocks…) are kept only while observed.
- **Server-only data** (AI memories, base attribute values, villager trades not opened, most
  pet data) is captured as far as the client knows it. This is a documented limitation.

### 5.6 Back-pressure
- `SnapshotQueue` is bounded by **estimated bytes** (default 64 MiB), not item count, and keeps
  one entry per chunk (a newer snapshot replaces an older one).
- The serializer limits in-flight `IOWorker` writes with a semaphore (default 256). When `IOWorker`
  is slow, the serializer waits, the queue fills, and the client thread reacts:
  1. it skips live snapshots (those chunks stay dirty and are retried later);
  2. if the queue is still full, it drops the incoming final snapshot, counts it and logs one
     rate-limited WARN.
- The client thread never waits.

### 5.7 Capture settings & degradation
- Settings: capture on/off, per-server on/off, per-dimension on/off (default all), entities
  on/off, container observation on/off, live interval, tick budget, queue size.
- Singleplayer (`client.hasSingleplayerServer()`): capture is off and the map says why.
- A server where capture is disabled: no snapshots, and the cache can still be viewed and
  exported.
- Read-only: the mod registers no network channels and sends no packets. (Fabric API itself, if
  installed, announces its own channels on join. That is not this mod's doing and happens for
  any Fabric client.)

---

## 6. Cache layout & storage

```
.minecraft/16tosave/
  <server-id>/
    server.json                     # display name, address, first/last seen, mod version
    <namespace>/<path>/             # e.g. minecraft/overworld, myserver/resource_world
      region/r.X.Z.mca              # vanilla Anvil chunk tags (§5.3)
      entities/r.X.Z.mca            # vanilla entity-chunk tags (F11)
      map/r.X.Z.cps                 # map summary index (§7.3), derived, deletable
      selection.json                # current selection (auto-saved)
      selections/<name>.json        # named selections
      last_seen.properties          # last-seen time per region (for eviction)
```

- `<server-id>`: from `client.getCurrentServer()`. Host is lower-cased, default port 25565 is
  added, everything outside `[a-z0-9._-]` becomes `_` → `play.example.com_25565`. LAN games use
  the host's address and port like any server (the port changes each time the host opens to
  LAN), Realms → `realms_<name>`. Stable across sessions for the same address.
- **Proxy networks** (one address, several backend worlds with the same dimension keys) mix
  their caches (details and fix options: LIMITATIONS.md §1). v1 keeps the layout above.
  Contingency C3 (hashed-seed sub-folder) can split
  them later if you want that.
- Storage objects: one vanilla `SimpleRegionStorage` per (dimension, kind), created with
  `RegionStorageInfo("16tosave", dimKey, "chunk"|"entities")` and
  `DataFixTypes.CHUNK`/`ENTITY_CHUNK`, opened lazily and closed on session end.
- **Size cap & eviction:**
  - Default cap 4 GiB per server. When it's exceeded at session end, least-recently-seen regions
    are deleted first.
  - Never evicted: regions holding chunks of the current or any named selection, and regions of
    the active session.
  - "Clear cache for this server" closes the storages, then (after a confirmation dialog)
    deletes everything in the server folder except the selection files (`selection.json`,
    `selections/*.json`), which are tiny and still useful if the area is captured again.
  - Pins are read from the selection files when the session closes (writer thread). The map
    saves the current selection at most 1 s after a change and when it closes.
- Corrupt data: vanilla `RegionFile` already returns empty and logs on a bad chunk; our read path
  also catches exceptions, so a corrupt chunk shows as "missing" on the map and as "skipped" in
  the export.

---

## 7. Map screen

### 7.1 Opening
- Unbound keybind ("Open 16tosave map", own key category).
- A button added to `PauseScreen` through `ScreenEvents.AFTER_INIT` + `Screens.getWidgets`.
  It is placed in the top-right corner so it doesn't depend on the pause menu layout. It opens
  the map; the capture settings (stage 1 screen) are reached from the map's "Settings" button.

### 7.2 Tiles and LOD
A tile = one region (32×32 chunks). The zoom `s` is in GUI pixels per block (from 1/128 to 16).

| LOD | Texel = | Tile size | Used when | Click acts on |
|---|---|---|---|---|
| 0 | 1 block | 512² | s ≥ 0.5 | chunk |
| 1 | 4×4 blocks | 128² | 0.125 ≤ s < 0.5 | chunk |
| 2 | 1 chunk | 32² | 1/32 ≤ s < 0.125 | chunk |
| 3 | 1 region (aggregate) | 1 quad | s < 1/32 | **region** |

- LOD switches use ±10 % hysteresis (`Lod.select`, core).
- LOD3 draws one quad per region: the mean colour of what is cached there.
- While a finer tile is missing, a coarser texture of the same region is drawn scaled up.

### 7.3 Tile pipeline
1. **Summary index** (`<dimension>/map/r.X.Z.cps`, Q2). Per column it keeps the vanilla map
   colour id of the top visible block plus a tint class (none, grass, foliage, water), the
   height, the water depth and the biome id. The writer thread updates it every time it writes
   a chunk (`MapIndex`, a `CacheWriter.WriteHook`), so it never lags behind the cache; files are
   flushed in batches (every 15 s, when a dimension closes, when the map opens). The format is
   versioned (2: adds a flags byte per chunk, bit 0 = containers never seen) and can be deleted
   at any time.
2. **`TileQueue`** (core): the visible regions, nearest to the view centre first. Each job
   carries a *generation*; switching dimension or height limit bumps it and stale work is
   dropped.
3. **`TileWorkers`** (mod, 2 threads by default):
   - full height: load the region's summary file; chunks whose summary is missing or older than
     the region file (header timestamps, 10 s tolerance) are decoded from the region file and
     summarised, and the result is sent back to the writer to be stored;
   - with a height limit: decode every chunk of the region and scan from `clipY` down;
   - region files are read with our own read-only reader (core `RegionFileReader`: zlib, gzip,
     uncompressed, external `.mcc`) and parsed with vanilla `NbtIo.read`. A read that races
     with a write fails, is counted, and is retried the next time.
4. **`TileRasterizer`** (core, pure Java): pixels with vanilla map shading (compare with the
   northern neighbour; water darker with depth), then averaged down to LOD1/LOD2 and the LOD3
   colour. Colours: the vanilla map colour; grass, leaves and water use the biome's grass,
   foliage and water colour (`Biome#getGrassColor`, `#getFoliageColor`,
   `#getSpecialEffects().waterColor()`). Not cached = transparent.
5. **Upload** (render thread): one `DynamicTexture` per (region, LOD), at most ~600 k pixels per
   frame; each upload gets a fresh id and the old texture is released afterwards. Textures are
   kept in an LRU within `map.vramMiB` (192 MiB). After an LOD0 upload the 1 MiB pixel array is
   dropped; it is rebuilt if the texture is evicted and needed again.
6. **Live updates:** the writer tells the open map about every new summary; affected regions are
   rebuilt at most once a second while visible, or dropped and rebuilt when scrolled into view.

Performance: a frame only blits ready textures, uploads a few new ones and asks the workers for
missing regions; decoding never happens on the render thread. The status line shows how many
regions are still being drawn.

### 7.4 Controls & overlays
- Left button selects (§7.5); right- or middle-drag pans; wheel zooms around the cursor.
- **Height limit:** a slider drawn on the right edge of the map (not a vanilla slider widget),
  from the dimension's min to max Y; at the top it means "all heights". It applies 350 ms after
  the last change. The toolbar button "Below roof" sets it to `min_y + logical_height − 1` in
  dimensions with a ceiling (Nether → 127); "Full height" resets it.
- Toolbar, row 1: Settings · dimension (cycles through the dimensions cached for this server) ·
  Center on me · Below roof / Full height.
- Toolbar, row 2 (selection): Select all · Invert · Clear · Undo · Radius N · Around me ·
  Around spawn · Save as… · Load…. Button widths follow the labels and shrink to fit.
- Overlays: chunk grid (when chunks are ≥ 6 px), region grid, player marker with heading (only
  in the player's dimension), block/chunk/region under the cursor, level of detail, number of
  regions and chunks shown.
- Selection overlay: selected chunks tinted blue (runs per row, equal rows merged into one
  rectangle; when a chunk is under 1.5 px, one shaded square per region, darker the more of it
  is selected). The rectangle being dragged is drawn snapped to chunks, coloured by mode, with
  its size in chunks.
- Status line: selected chunks and estimated export size (§8.1), "+N not cached" when a loaded
  selection names chunks that are not in the cache, then LOD · regions · chunks · rendering.
  When nothing is selected, a one-line hint explains the gestures.
- Chunks with containers whose contents the client never saw (`16tosave:meta.incomplete`,
  §5) get orange diagonal stripes (a tint when a chunk is under 8 px): those containers will be
  empty in an export. The flag travels in the summary index (format 2; format-1 files are
  rebuilt once) and in each region raster as 1024 bits.

### 7.5 Selection gestures (§8 is the model)

| Gesture | Effect |
|---|---|
| Left-drag | rectangle, **replace** |
| Shift + left-drag | rectangle, **add** |
| Ctrl (or Alt) + left-drag | rectangle, **subtract** |
| Left-click (< 4 px movement) | toggle chunk; Shift: add it, Ctrl/Alt: remove it. At LOD3 the same for the whole region (click toggles: all cached chunks of the region, or none) |
| Ctrl+Z / Ctrl+A | undo (32 steps) / select all |

- Every operation is limited to the **universe** (chunks present in the cache); deselecting
  works anywhere. Until the region headers are read (a moment after opening) the left button
  does nothing and the status line says "reading the cache…".
- Around me / Around spawn: chunks whose centre is within *Radius* chunks (4, 8, 16, 32, 64,
  128; click = larger, Shift-click = smaller). Same modifiers as dragging: plain replaces,
  Shift adds, Ctrl/Alt removes. "Around spawn" uses the world spawn the server sends every
  client (`Level#getRespawnData()`), only if it is in the shown dimension.
- Save as…: name dialog; a name that maps to an existing file asks before replacing it.
  Load…: list of the dimension's named selections (newest first, with chunk count and date),
  a page at a time, each with Delete. Loading replaces the current selection (undoable).
- Export… comes with stage 4.

---

## 8. Selection & export

### 8.1 Selection model (core, pure Java)
- `ChunkSelection` = map of region key → `long[16]` (1024 bits), with a cached count.
- Operations: set, clear or toggle a chunk; toggle a region; rectangle with mode (replace, add,
  subtract); circle of radius *r* around a chunk; clear.
- `invert(universe)` and `selectAll(universe)` work against the universe, the set of chunks
  actually present in the cache — itself a `ChunkSelection`, built by `CacheHeaders` from the
  `.mca` headers (also pure Java). It is read when the map opens, then kept current from the
  map's live updates and from the headers the tile workers read anyway.
- File format: `{"format":1,"dimension":"minecraft:overworld","name":"base","savedAt":"<ISO-8601>","chunks":1234,"regions":{"0,-1":"<base64 of 128 bytes>"}}`
  (`name` only in named selections; `chunks` is informational). Unknown formats and bad region
  entries are skipped, never fatal.
- Persistence (`SelectionFiles`, one background thread, so reads and writes stay in order):
  - the current selection is saved per server + dimension in `selection.json`, 1 s after the
    last change and when the map closes or switches dimension; an empty selection removes the
    file;
  - named selections are `selections/<safe name>.json`; names that differ only in case or
    punctuation share a file (the dialog asks before replacing).
- **Size estimate:** sum of `sectorCount × 4096` from the `region/` and `entities/` headers for
  the selected chunks. This is the actual compressed size on disk, so the estimate is close.

### 8.2 Export flow (UI)
1. Map toolbar → **Export…** (the map first saves its selection; the dialog reads the saved
   selection of every vanilla dimension). Options dialog:
   - world name (default: server name + date) and the folder it will get in `saves/`;
   - one switch per vanilla dimension that has a selection (with its chunk count);
   - an **exported** Nether/End outside the selection: nothing (void, default) or vanilla
     terrain (per Q1). A Nether/End that is not exported always gets vanilla terrain (§8.4);
     when void is chosen the dialog says that Minecraft will show its warning;
   - game mode (Survival/Creative/Adventure/Spectator, default Creative);
   - commands (default on); entities (default on).
2. Progress screen: state, current dimension, progress bar, chunk count, **Cancel** and
   **Hide**. The export runs on its own thread; hiding the screen does not stop it, and a toast
   reports the result. One export at a time; the map's Export button reopens the progress
   screen while one runs.

### 8.3 Export pipeline (`WorldExporter`)
1. **Flush.** The capture layer drains its queue for the server and closes the region files
   (vanilla `IOWorker` writes everything). Capturing goes on; the next write reopens them.
   After 60 s the export continues with what is on disk.
2. **Prepare.**
   - A free folder name (`ExportLayout.folderName`: characters not allowed in file names → `_`,
     then `" (2)"`, `" (3)"`… — vanilla's own style).
   - Build in a hidden staging folder `saves/.<folder>.16tosave-tmp`. It has no `level.dat`
     until the end, so it never appears in the world list. Staging folders left by an
     interrupted export are deleted when the next export starts.
   - Dimension folders use the 26.x layout `dimensions/<ns>/<path>/{region,entities}` (F6),
     written directly (no `LevelStorageAccess` needed).
3. **For each dimension, region by region, chunk by chunk (each in try/catch):**
   1. read the cached tag with the read-only core reader (one retry with a fresh header if the
      read races with a write) and parse it with vanilla `NbtIo.read`;
   2. check `xPos`/`zPos` and that it has `sections`;
   3. remove `16tosave:meta` (its `incomplete` list goes into the metadata), set `Status: full`,
      add `LastUpdate`, `InhabitedTime`, `block_ticks`, `fluid_ticks`, `PostProcessing` if
      missing. `DataVersion` stays as stored: an older chunk is upgraded by vanilla on load.
      (No `SerializableChunkData.parse` validation — U3; the checks above replace it.)
   4. write it with `NbtIo.write` into a new region file built by core `RegionFileWriter`
      (zlib like vanilla, chunks over 255 sectors in `c.X.Z.mcc`).
   - Entity chunks: copied as stored if their `Position` matches. Player references (owner of a
     pet, …) are kept: in singleplayer you usually have the same UUID, so your pets stay yours.
   - Failures are skipped, logged and counted by reason (not cached, unreadable, corrupt, wrong
     position, write failed). One bad chunk never aborts the export.
4. **World files (§8.4)**, then `16tosave-export.json`, then `level.dat` **last**.
5. **Finalize.** Rename staging → `saves/<folder>` (atomic where the filesystem supports it).
   - **Cancel** or an error: the staging folder is deleted.

### 8.4 level.dat and saved data
- **Writers:** hand-built NBT (core `WorldFiles` + `Nbt`), not vanilla's `PrimaryLevelData` /
  `WorldGenSettings` objects: building those needs the server-side worldgen registries, which
  a client connected to a server does not have. The structure follows a `level.dat` and a
  `world_gen_settings.dat` written by a 26.1 server (MCA Selector's generator templates); a
  unit test pins it. `DataVersion`, version name and snapshot flag come from the running game,
  so vanilla sees a current world and shows no upgrade warning.
- **`level.dat`** = `{Data: {LevelName, DataVersion, Version{Id, Name, Series, Snapshot},
  version: 19133, initialized: 1, GameType, allowCommands, difficulty_settings{difficulty:
  normal, hardcore: 0, locked: 0}, spawn{pos, dimension, yaw, pitch}, Time: 0, LastPlayed,
  WasModded: 0, DataPacks{Enabled: [vanilla], Disabled: []}}}`. `initialized: 1` keeps vanilla
  from searching a spawn itself.
- **`data/minecraft/world_gen_settings.dat`** = `{data: {seed: 0, generate_structures: 0,
  bonus_chest: 0, dimensions: {…}}, DataVersion}`:
  - why not void everywhere: vanilla (`WorldDimensions#checkStability`) accepts any overworld
    generator, but a Nether or End whose generator is not the vanilla one makes the world
    "experimental", and Minecraft then shows "Worlds using Experimental Settings are not
    supported" when the world is opened (vanilla stores no "confirmed" flag for this, and a
    data pack wouldn't help: its entries are experimental too). So a Nether/End is void only
    if it is exported and the user chose void (`WorldFiles.generators`);
  - overworld (always), and an exported Nether/End with "void" = `minecraft:flat` with layers
    `[air×1]`, biome `minecraft:the_void`, `features: false` (so no stone platform),
    `lakes: false`, `structure_overrides: []`;
  - every other Nether/End = `minecraft:noise` with settings `minecraft:nether` /
    `minecraft:end` and biome sources `multi_noise` preset `nether` / `the_end`, exactly as
    in the reference file.
- **Spawn** (26.x spawn = `{pos, dimension, yaw, pitch}`, so it can be in any dimension):
  - where the player stands, if that chunk is exported;
  - otherwise (overworld if exported, else the first exported dimension) the chunk nearest the
    centre of the largest connected selected area (`SpawnPicker`), at its middle column; Y =
    `MOTION_BLOCKING` heightmap, else the highest non-air block from the block palettes, else
    128.
- **Not written:**
  - game rules (vanilla defaults; the respawn radius stays vanilla's, and vanilla's spawn
    search looks for solid ground near the spawn);
  - player data (a fresh player spawns at the spawn point).
- **The End's dragon fight** (`DragonFightState`). Vanilla starts a fight when a player comes
  near 0,0 in the End unless the fight data says the dragon was killed; without data it scans
  for an active exit portal. Rule:
  - main island exported (any of the four chunks around 0,0) with an **active** exit portal
    (`end_portal` blocks in them: the dragon was killed on the server) → write "killed";
  - main island exported with an inactive portal (dragon alive on the server) → nothing: the
    game starts the real fight with the exported crystals;
  - main island not exported, End void → write "killed" (no dragon over nothing);
  - main island not exported, End vanilla terrain → nothing: a normal vanilla End.
  The 26.x format of that saved data isn't documented, so vanilla writes it: its own
  `EnderDragonFight.CODEC` decodes an empty fight, the fields `needsStateScanning`,
  `dragonKilled`, `hasPreviouslyKilledDragon` (names from the 26.3 sources, via Paper's 26.3
  patches) are set by reflection, the codec encodes it, and the result is decoded again and
  checked. The file goes to `dimensions/minecraft/the_end/data/<ns>/<path>.dat`, from the id of
  the class's own `SavedDataType`. If any step fails nothing is written and vanilla decides as
  usual. The metadata records what happened (`options.endDragon`).
- **World icon** (`icon.png`, 64×64, core `WorldIcon` + `PngWriter`): the map view of the
  largest selected area of the spawn dimension (at most 128 chunks across, then centred on the
  spawn), drawn from the summary index with the map's colours and shading (`TileRasterizer`,
  biome tints from the server's registries); unselected chunks are dark. A failure costs only
  the icon.

### 8.5 Export metadata (`saves/<name>/16tosave-export.json`)
Contents:
- source server id and name, ISO timestamp;
- Minecraft version name and DataVersion, mod version;
- options (generators, game mode, commands, entities);
- per dimension: chunks selected / written / skipped (by reason), entity chunks written, and
  `incompleteContainerChunks` (chunks with containers the client never saw open; they are
  empty in the exported world).

---

## 9. Mixins

**Planned for v1: none.** Every hook is a Fabric event (§10) or plain public API.

Pre-designed contingencies. Each is used only if verification shows it's needed. All are
`@Inject`, `require = 0` with a WARN log, so a changed target makes the mod degrade instead of
crash:

| # | Target | Why | If the target changes |
|---|---|---|---|
| C1 | `LevelChunk#setBlockState` RETURN, client level only | set our dirty bit if `isUnsaved()` isn't set by client block updates **[U1]** | falls back to re-snapshotting every loaded chunk on a slower timer |
| C2 | `ClientPacketListener#handleRespawn` HEAD | snapshot before the level swap, if held references turn out not to be enough **[U9]** | data since the last periodic snapshot is lost on a dimension change |
| C3 | accessor on `BiomeManager` (hashed seed) | split proxy-network worlds into sub-folders (only if you want that) | that feature turns off |

---

## 10. Fabric API modules

| Module | Used for | Why a mixin-only alternative was rejected |
|---|---|---|
| `fabric-key-mapping-api-v1` | `KeyMappingHelper.registerKeyMapping` + key category | Mixin-only means rewriting `Options#keyMappings`, which is the same array Fabric API rewrites. Two rewriters conflict on almost every install, because nearly every install has Fabric API. |
| `fabric-screen-api-v1` | pause-menu button; container open/close observation | Needs mixins into `PauseScreen#init` plus an invoker for `addRenderableWidget`, and into `AbstractContainerScreen` init/removal. Those methods were reshaped in 1.21.9 and 26.1, so it breaks every update. |
| `fabric-lifecycle-events-v1` | `ClientChunkEvents`, `ClientTickEvents`, `ClientLevelEvents` | Replacing it means rebuilding Fabric's 4 injections into `ClientChunkCache`, with local captures. That is the most fragile code there is, and it is the heart of capture. |

Not used: `fabric-networking-api-v1` (session end is detected by observing `client.level`, §5.2),
rendering/HUD API (no HUD), Cloth Config, Mod Menu, any third-party library. Gson is used for
config; Minecraft ships it.

`fabric.mod.json` lists exactly these three modules in `depends`. Gradle depends on them
individually (`fabricApi.module(…)` **[U11]**), and the full Fabric API is a dev-runtime-only
dependency.

---

## 11. Project layout

```
settings.gradle            # includes :core; root project = the mod
build.gradle               # net.fabricmc.fabric-loom; jar also packs :core classes
gradle.properties          # versions from F3
core/                      # plain java-library, Java 25, JUnit 5 — NO Minecraft on classpath
  src/main/java/sk/tapusik/sixteentosave/{selection,map,storage,capture,export}/…   # pure logic
  src/test/java/…
src/main/java/sk/tapusik/sixteentosave/   # one source set: the mod is client-only, no split needed
  capture/  storage/  map/  selection/  export/  ui/  config/
src/main/resources/
  fabric.mod.json  sixteentosave.mixins.json (empty list in v1)  assets/sixteentosave/lang/en_us.json
LICENSE  README.md  docs/DESIGN.md
```

Putting the pure logic in its own Gradle project means *the compiler* enforces "no Minecraft
types", and the unit tests run in seconds without Loom. Both trees use the same feature package
names.

Pure-Java (core) classes:
- `RegionMath` (floorDiv/floorMod, keys);
- `Lod`, `MapView` (zoom ↔ level of detail, screen ↔ world, visible regions);
- `TileQueue` (tile jobs by priority);
- `TileRasterizer` (pixels, shading, downsampling), `ChunkSummary`, `RegionSummary`, `RegionSummaryCodec`;
- `ChunkSelection` (also used as the universe), `SelectionCodec`, `SelectionStats`, `SelectionPaths`, `CacheHeaders`;
- `RegionHeader`, `RegionFileReader` (read-only access to `.mca` files: offsets, sizes, timestamps, chunk data);
- `ByteBoundedCoalescingQueue`;
- `EvictionPlanner`;
- `ServerIdSanitizer`;
- `SpawnPicker` (largest connected component, centre).

Config file `.minecraft/config/16tosave.json`, plain and hand-editable. A missing or broken
file means defaults plus a WARN; unknown keys are kept:

```json
{
  "configVersion": 1,
  "capture": { "enabled": true, "entities": true, "observeContainers": true,
               "dimensions": { "*": true }, "liveIntervalSeconds": 30,
               "tickBudgetMicros": 1500, "queueMiB": 64, "entityReplaceRange": 24 },
  "servers": { "play.example.com_25565": { "capture": true } },
  "cache":   { "maxSizeMiB": 4096 },
  "map":     { "tileWorkers": 2, "vramMiB": 192, "summaryIndex": true },
  "export":  { "netherEndGenerator": "VOID" }
}
```

Logging: SLF4J logger `16tosave`.
- INFO: session open/close, export start/end.
- WARN: dropped snapshots (rate-limited), skipped chunks (summary), config problems.
- DEBUG: per-chunk detail.

---

## 12. Testing

- **Unit (core, JUnit 5):** selection operations and invert/all against a universe; codec round
  trip; selection file paths; universe from region headers; LOD thresholds/hysteresis and screen↔world maths at negative coordinates; `.mca` header
  parsing on generated fixtures (including a truncated or corrupt header); rasterizer shading;
  queue bounds/coalescing; eviction never deleting pinned regions; server-id sanitising; spawn
  picking.
- **Name check:** your local `./gradlew build` (no mixins, so compiling is the check). I keep a
  list of every vanilla class/member the code uses in `docs/vanilla-api.txt`, so a future
  Minecraft update shows quickly what to re-check.
- **Optional automated end-to-end test (dev-only, not shipped):** `fabric-client-gametest-api-v1`
  can start a dedicated server and connect the client (F8's module): place blocks → capture →
  export → open the exported world → check the blocks.
- **Manual test steps and expected results** after each stage, as the request asks.

---

## 13. Not verified yet — will be checked against the real jar before use

Stage 1 compiled against the real 26.3 jar, which settled every stage-1 name. Rows below that
are still open are runtime behaviour or later stages.

| # | Item | Plan if it's wrong |
|---|---|---|
| U1 | client block updates set `LevelChunk#isUnsaved` | contingency C1 |
| U2 | `PalettedContainer#copy()` exists in 26.3 | re-encode/decode copy (slower), still budgeted |
| U3 | ~~`SerializableChunkData.parse` validation~~ — not used: stage 4 checks position and `sections` itself (§8.3) | — |
| U4 | ~~void dimensions ⇒ "experimental" warning~~ — confirmed in game: a void (flat) overworld is fine, a void Nether/End triggers vanilla's warning. Resolved by §8.4: void only for an exported Nether/End when chosen | — |
| U5 | exact 26.3 `level.dat` / saved-data structure. Built after a 26.1 server's files; 26.3 adds a version history that vanilla writes itself (`PrimaryLevelData#writeVersionHistory`) | compare with a void superflat world made by 26.3 in game and adjust `WorldFiles` |
| U6 | ~~dynamic-texture upload API~~ — resolved: `DynamicTexture(Supplier, NativeImage)` + `TextureManager#register/release`, as used by ModMenu and YACL on 26.3 | — |
| U7 | ~~biome tint access~~ — resolved: `Biome#getGrassColor/getFoliageColor` (Sodium dev) and `getSpecialEffects().waterColor()` (Fabric 26.3). `BlockState#getMapColor` / `MapColor` remain [S] | untinted map colours |
| U8 | ~~unbound key constant~~ — resolved: `InputConstants.UNKNOWN.getValue()` (ModMenu 26.3) | — |
| U9 | held `LevelChunk` references stay intact after a level swap | contingency C2 |
| U10 | how complete `Entity#save` is for remote (client) entities | document the limits |
| U11 | Loom 1.19 `fabricApi.module(…)`; `1.19-SNAPSHOT` vs a release | depend on full Fabric API, declare the 3 modules in `fabric.mod.json` |
| U12 | `PauseScreen` exists (stage 1 compiled); whether the corner button overlaps anything is checked in game | move the button |
| U13 | missing heightmap types recomputed on load | write all four heightmap types, computed by us |
| U14 | ~~`validateAndCreateAccess`~~ — not used: staging folder and free name are our own (`ExportLayout`) | — |
| U15 | menu ↔ block mapping through `hitResult` in edge cases (crafter, chest boat); double chests: which half is first (RIGHT, from vanilla's `getBlockType` – not visible in the sources at hand) | swap the halves |
| U16 | dragon-fight saved data: written through vanilla's own codec (§8.4); unverified are the `SavedDataType` constant and decoding an empty fight | nothing written, vanilla decides (dragon may appear in a void End) |

---

## 14. Build-environment note

From the cloud container where this was written, `maven.fabricmc.net`, `meta.fabricmc.net`,
`piston-meta.mojang.com`, `piston-data.mojang.com`, `launchermeta.mojang.com` and
`libraries.minecraft.net` are refused by the network policy. That is why the build runs on your
machine (Q3). The Gradle wrapper can still be generated in the container, because
`services.gradle.org` is reachable. The container is temporary and this folder is not a git
repository, so the code needs to reach you after each stage, either pushed to a GitHub repository
you attach or as a downloadable archive.

---

## 15. Limitations

Kept up to date in [`LIMITATIONS.md`](LIMITATIONS.md): proxy networks (open, with the code
locations and fix options), what the server never sends, export, cache, tested setup.
**Server rules:** some servers forbid world downloading. The mod is strictly read-only, but
following server rules is the user's responsibility.

---

## 16. Stages

| Stage | Scope | "Done" means |
|---|---|---|
| 0 | Gradle skeleton + wrapper, `core`, empty client entrypoint, LICENSE (EUPL-1.2), README | on your machine `./gradlew build` passes and `./gradlew runClient` starts with the mod listed |
| 1 | Capture + storage + config + cache cap/eviction | joining a server produces valid `.mca` files that MCA Selector opens; no frame-time spikes; blocks placed after load show up in the cache |
| 2 | Map rendering, LODs, slider, overlays, summary index, keybind | map shows the cache at all 4 LODs, panning stays smooth with 10k+ chunks, live updates while capturing |
| 3 | Selection + persistence + named selections | gestures and buttons work; selection survives a restart; size estimate shown |
| 4 | Export + metadata + progress/cancel | exported world opens from the world list with no warning (subject to Q1); selected chunks match; unselected area is void; skipped chunks reported |

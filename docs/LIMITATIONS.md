# Known limitations

What 16tosave can't do, or does differently than you might expect. The list is ordered by how
likely you are to run into it. Items marked **(open)** could be fixed later; the others come from
how a client-side mod works or from vanilla rules.

---

## 1. Proxy networks mix their worlds into one cache (open)

Affects BungeeCord, Waterfall and Velocity networks, and any server where `/server`, a portal
or an NPC moves you to another world **without you changing the address** in the server
list. Examples: a lobby plus survival/creative/skyblock servers behind `play.example.net`.

### What happens

Everything you see behind one address goes into one cache folder:

```
.minecraft/16tosave/play.example.net_25565/minecraft/overworld/region/…
```

The lobby's overworld, the survival overworld and the creative overworld all have the same
dimension id (`minecraft:overworld`), so they are written into the **same** region files:

- a chunk at the same coordinates in two worlds overwrites the other one; the one you saw
  last wins;
- chunks at different coordinates sit next to each other, so the map shows a patchwork of
  several worlds;
- entities, the summary index, selections, the size cap and the export all see that mixture,
  so an export can contain pieces of several worlds.

Plugin worlds on a **single** server (Multiverse and the like) are usually fine: each world has
its own dimension id (e.g. `myserver:resource`) and so its own folder. Worlds that reuse the
vanilla ids have the same problem as a proxy.

### Where it is in the code

| Place | What it does |
|---|---|
| `src/main/java/sk/tapusik/sixteentosave/capture/ServerIdentity.java:37` | the server id comes only from the address in the server list (`ServerData.ip`) |
| `core/src/main/java/sk/tapusik/sixteentosave/capture/ServerIdSanitizer.java` | turns that address into the folder name (`host_port`) |
| `src/main/java/sk/tapusik/sixteentosave/capture/CaptureService.java:240` | a new level with the same server id keeps using the same session and folder; a backend switch looks just like a dimension change |
| `src/main/java/sk/tapusik/sixteentosave/capture/TrackedLevel.java:61` | the dimension folder comes only from the dimension id |
| `src/main/java/sk/tapusik/sixteentosave/storage/CacheLayout.java:28` | builds `<server>/<namespace>/<path>` |
| `docs/DESIGN.md` §6 and contingency C3 | the issue was known from the start; the fix was left optional |

Nothing in the client packets says "this is another backend". A switch to another backend
gives the client a new level, exactly like going through a portal.

### How to recognise it

- The map shows terrain that doesn't fit together: sharp edges between unrelated landscapes, a
  lobby next to a survival base.
- An area you know well suddenly looks different after you visited another game mode.

### What to do until it is fixed

- **Capture only one backend:** map → Settings → "On this server: OFF" before you switch to a
  backend you don't want, and back ON when you return. The switch applies to the whole
  address, so it has to be done by hand on each switch.
- **Or clear the cache** (Settings → Clear cache for this server) before you start capturing
  the backend you want. Saved selections are kept.
- If the network has **separate addresses** for its servers (e.g. `survival.example.net`),
  connect through those: each address gets its own cache.

### Ways to fix it later

1. **Seed fingerprint (contingency C3).** The server sends a hashed world seed with every
   level (vanilla uses it for biome blending). Different backends almost always have different
   seeds, so the cache could go into `<server>/<hashed seed>/…`.
   - plus: automatic, no clicks;
   - minus: some servers hide or randomise that value (anti seed-cracking plugins); then every
     backend looks the same (back to today's behaviour) or every join gets a new folder;
   - needs: reading the hashed seed on the client (an accessor on `BiomeManager`, i.e. one
     small mixin, or a getter if 26.3 has one), a sub-folder in `CacheLayout`, and moving or
     leaving existing caches.
2. **Named "world profiles" chosen by hand.** A button in the settings ("World: survival") that
   picks a sub-folder; the mod remembers the last choice per server.
   - plus: always correct, works with hidden seeds;
   - minus: you have to switch it yourself every time you change backend.
3. **Both:** the seed fingerprint picks the profile automatically, and you can rename it ("seed
   a1b2… = survival") or override it when the seed is hidden. This is the recommended fix.

Whatever the choice: the map, the selections and the export already work per folder, so only
the folder choice in the capture layer has to change. Existing mixed caches can't be separated
afterwards; they would stay as they are.

### Related: the same server under different folders

The folder comes from the address as typed, so these also split a cache:

- the same server reached through different addresses (`play.example.net` vs. its IP, an SRV
  name vs. a direct `host:port`);
- a Realm that is renamed (Realms are keyed by name);
- a LAN game: the host gets a new port every time they open to LAN, so every session is a new
  folder.

---

## 2. What the server never sends to a client

16tosave stores exactly what your client received. Some data never reaches a client.

- **Containers you never opened** are empty. Chests, barrels, shulker boxes, hoppers,
  furnaces, brewing stands, crafters and dispensers/droppers are saved when you open them. The
  map marks chunks that still have unopened ones (orange stripes), and the export lists them in
  `16tosave-export.json` (`incompleteContainerChunks`).
- **Container entities and animal inventories (open):** chest minecarts, chest boats, donkeys,
  mules and llamas are not saved, not even after you opened them. They export empty.
- **Ender chests** are never saved: their contents belong to the player, not to the world.
- **Contents without a container menu**, never sent: beehive and bee nest bees, chiseled
  bookshelf books, jukebox discs.
- **Lectern books** are not saved: the lectern shows a book, but its block entity is not a
  container, so reading the book doesn't store it.
- **Entity details the server keeps to itself:** AI memories, base attribute values, villager
  trades (even after trading), most pet data, mob inventories. Entities are saved as far as the
  client knows them.
- **Entities far away:** the server sends entities only within its tracking range, which is
  usually smaller than the view distance (Paper sends item frames and paintings only within
  32 blocks by default). Chunks at the edge of your view can look empty.
- **Anti-xray:** servers with anti-xray (e.g. Paper engine mode 2 or 3) send fake ores and hide
  real ones. They are captured as received.
- **Old data:** the cache holds each chunk as you last saw it. Areas you haven't visited for a
  while are out of date until you go there again.

---

## 3. Export

- **Vanilla dimensions only:** the overworld, the Nether and the End. Custom dimensions are
  captured and shown on the map, but the export dialog doesn't offer them.
- **No structures:** the exported world has no structure data. Structure-based spawns (Nether
  fortress blazes and wither skeletons outside spawners, witch huts, ocean monument guardians,
  pillager outposts), explorer maps and `/locate` don't work there.
- **Void Nether or End = vanilla warning:** a void Nether/End makes Minecraft show "Worlds using
  Experimental Settings are not supported" every time the world is opened. That is vanilla's
  rule for any non-vanilla Nether/End generator; the world works. A Nether/End you don't export,
  or one with "vanilla terrain", has no warning.
- **"Vanilla terrain" has seams:** the generated terrain uses seed 0, so it doesn't continue the
  exported area.
- **Game rules** are vanilla's defaults.
- **No player data:** you start as a new player at the spawn, with an empty inventory; the
  game may move a new player a few blocks to find solid ground.
- **Owners are kept:** tamed animals keep their owner's UUID. In singleplayer you usually have
  the same UUID as on the server, so your pets stay yours; other players' pets stay theirs.
- **The End's dragon state** is written through the game's own code; if that fails (logged as
  "could not write the End's dragon state"), the game decides and a dragon can appear. See
  `options.endDragon` in `16tosave-export.json`.
- **Unknown blocks, items and biomes** (from server plugins or data packs) are removed or
  replaced by vanilla when the world loads, with log warnings. The mod doesn't list them.
- **First load is slower:** light is not stored, so the game relights exported chunks when it
  first loads them; points of interest (beds, workstations, bells) are rebuilt from the blocks.

---

## 4. Cache

- **Size cap:** 4 GiB per server by default (`cache.maxSizeMiB`). Above it, the least recently
  seen regions are deleted when you disconnect. Regions with a selected chunk (current or
  saved selection) and regions of the session are never deleted.
- **One game per cache:** two game instances that share one `.minecraft` and are connected to
  the same server at the same time would write the same region files. Not supported.
- **Singleplayer** captures nothing: the world is already on disk.
- **Map index:** after an update that changes its format, the first map open per region is
  slower once while the index is rebuilt.

---

## 5. Tested with

Minecraft 26.3, Fabric Loader 0.19.5 and Fabric API 0.165.0+26.3 (Prism Launcher). Other
mods that change how chunks reach the client, e.g. Bobby or Distant Horizons, are untested;
Bobby's stored chunks could be captured as if the server had sent them.

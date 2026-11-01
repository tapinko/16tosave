# Manual tests

## Stage 0 – project builds

| # | Steps | Expected |
|---|---|---|
| 0.1 | `./gradlew build` with JDK 25 | `BUILD SUCCESSFUL`; the `core` tests run (75 tests, all passing); `build/libs/16tosave-1.0.0.jar` exists |
| 0.2 | `./gradlew runClient` | the game starts; the log contains `16tosave initialised; cache folder …/run/16tosave` |

A local test server makes stage 1 easy: download the 26.3 `server.jar`, run it once, set
`eula=true` and (for the dev client, which plays offline) `online-mode=false` in
`server.properties`, start it and connect to `localhost`.

## Stage 1 – capture and cache

| # | Steps | Expected |
|---|---|---|
| 1.1 | Open a singleplayer world, press Esc, click **16tosave** (top right) | the screen says "Not connected to a server"; no folder appears under `.minecraft/16tosave/` |
| 1.2 | Join a server, wait 10 s, open the 16tosave screen | "Capturing minecraft:overworld – N chunks loaded"; *Written* grows over the next seconds; *dropped* and *failed* stay 0 |
| 1.3 | Fly or walk ~300 blocks in one direction, then disconnect | `.minecraft/16tosave/<host>_<port>/minecraft/overworld/region/` holds `r.X.Z.mca` files, `entities/` too, and `last_seen.properties` exists after the disconnect |
| 1.4 | In MCA Selector open the region folder `.minecraft/16tosave/<host>_<port>/minecraft/overworld/region` | the area you visited shows up with correct terrain |
| 1.5 | Build something visible from above (e.g. a 5×5 gold-block square) in a loaded chunk, fly away until it unloads, then disconnect and check in MCA Selector | the square is in the cache: changes after the chunk was first received are saved |
| 1.6 | Put items in a single chest, close it; leave a second chest unopened; fly away so both unload; inspect that chunk's NBT in MCA Selector (chunk editor) | the opened chest's `block_entities` entry has `Items`; `16tosave:meta.observed` holds its position; the unopened chest's position is in `16tosave:meta.incomplete` |
| 1.7 | Rejoin, come back to that chest without opening it, fly away again | its `Items` are still in the cache (carried over from the earlier observation) |
| 1.8 | Go through a Nether portal | `…/minecraft/the_nether/region/` appears and fills |
| 1.9 | Turn "In minecraft:the_nether" off in the 16tosave screen, fly around | *Written* stops growing while in the Nether |
| 1.10 | "Clear cache for this server…" → Delete | the cache files of the server disappear (only `selection.json` / `selections/` files stay, if you have any); within ~10 s new files appear again for the chunks around you |
| 1.11 | Fly fast (elytra or spectator) for a minute with F3 frame-time graph open | no spikes caused by the mod; no `capture queue full` warning in the log |
| 1.12 | Close the game, truncate one `r.X.Z.mca` file to half its size, start again, join and fly over that region | no crash; at most a warning in the log; the region is written again |
| 1.13 | Edit `config/16tosave.json` by hand (e.g. add `"myNote": 1` at the top), start the game, toggle any setting | the file still contains `"myNote": 1` |

## Stage 2 – map

| # | Steps | Expected |
|---|---|---|
| 2.1 | Options → Controls → Key Binds → category **16tosave**: bind "Open 16tosave map" to a key | the binding is saved; it is unbound until you set it |
| 2.2 | On a server with some cached area: press the key (or Esc → **16tosave**) | the map opens centred on you; your marker (red square with a dot showing your heading) is in the middle; cached terrain appears within a few seconds; the status line says "rendering N" while it works |
| 2.3 | Close and open the map again | this time it appears almost immediately (the summary index exists now) |
| 2.4 | Zoom in and out with the wheel | the block under the cursor stays put; the level of detail in the status line changes 1 px = 1 block → 4 blocks → 1 chunk → regions; chunk grid appears when zoomed in, region grid further out |
| 2.5 | Drag with the right (or middle) mouse button | the map follows smoothly; no stutter while panning, also with many regions cached |
| 2.6 | Compare the map with the world | grass, leaves and water follow the biome (swamp water is greenish, desert has no grass); hills shade like a vanilla map; water darker where deep |
| 2.7 | With the map open, keep capturing (stand still while chunks around you are saved, or have someone build) | the map updates by itself within a few seconds |
| 2.8 | In the Nether: open the map | first you see the roof; **Below roof** shows the caves below Y 127; the slider label reads `Y ≤ 127` |
| 2.9 | Drag the slider on the right edge down to e.g. Y 40, release | after a short delay the map redraws showing what is below Y 40; dragging it to the top shows "all heights" again |
| 2.10 | Click the dimension button | it switches between the dimensions cached for this server (e.g. overworld ↔ nether); "Center on me" is only active in your own dimension |
| 2.11 | **Settings** button in the map | the capture settings from stage 1 open; Done returns to the map |
| 2.12 | Look into `.minecraft/16tosave/<server>/minecraft/overworld/map/` | `r.X.Z.cps` files exist; deleting them is safe – the map rebuilds them (slower first open) |
| 2.13 | Singleplayer: open the map | it says "Not on a server…" and does nothing else |

## Stage 3 – selection

| # | Steps | Expected |
|---|---|---|
| 3.1 | Open the map | a second toolbar row (Select all · Invert · Clear · Undo · Radius 16 · Around me · Around spawn · Save as… · Load…); bottom-left a hint about the gestures; the status line starts with "Selected: 0 chunks (≈ 0 KiB)" |
| 3.2 | Left-drag a rectangle over cached terrain | while dragging a white rectangle snapped to chunks with its size (e.g. `12 × 8`); after release the cached chunks inside are blue; count and size in the status line go up |
| 3.3 | Shift + left-drag next to it; then Ctrl + left-drag across both | Shift adds (green rectangle while dragging), Ctrl removes (red); a plain drag replaces the whole selection |
| 3.4 | Drag a rectangle that also covers uncached (black) area | only cached chunks get selected |
| 3.5 | Click single chunks (Shift-click, Ctrl-click) | plain click toggles one chunk; Shift-click only adds, Ctrl-click only removes; clicking an uncached chunk does nothing |
| 3.6 | Zoom far out (status shows "regions") and click a region | all cached chunks of the region get selected; clicking again deselects them |
| 3.7 | Ctrl+Z a few times; **Undo** button | each step goes back one change |
| 3.8 | **Select all**, then **Invert** | Select all = every cached chunk; Invert after a partial selection selects exactly the rest; **Clear** empties it |
| 3.9 | **Radius 16** → click it (and Shift-click) | it cycles 4 → 8 → 16 → 32 → 64 → 128 (Shift: backwards) |
| 3.10 | **Around me** / **Around spawn** (also with Shift / Ctrl held) | a circle of cached chunks around you / the world spawn; Shift adds, Ctrl removes. "Around spawn" is inactive in a dimension that doesn't hold the world spawn, "Around me" outside your own dimension |
| 3.11 | Make a selection, close the map, quit the game, start again, open the map | the selection is still there (`…/minecraft/overworld/selection.json`) |
| 3.12 | **Save as…** → type "base" → Save; then **Save as…** "Base" again | the first saves `selections/base.json` and returns to the map; the second asks whether to replace it |
| 3.13 | **Clear**, then **Load…** → click "base · N chunks · date" | the saved selection comes back; Ctrl+Z restores the previous (empty) one |
| 3.14 | **Load…** → Delete → confirm | the entry disappears from the list and the file from `selections/` |
| 3.15 | Switch dimension with the dimension button, select something, switch back | each dimension keeps its own selection |
| 3.16 | With a selection in a region, set `cache.maxSizeMiB` to `64` (the minimum) in `config/16tosave.json` while the game is closed, fly around until the cache is well over 64 MiB, disconnect | regions holding selected chunks (current or named selection) are not deleted; others may be (log: "removed N least recently seen regions") |
| 3.17 | Settings → Clear cache → back to the map | the map is empty; the status shows "Selected: 0 chunks … (+N not cached)" – the selection itself is kept |
| 3.18 | Compare "≈ X MiB" with the size of the selected regions' files | roughly matches (it counts the compressed chunk + entity data of the selected chunks) |

## Stage 4 – export

| # | Steps | Expected |
|---|---|---|
| 4.1 | Select an area around you in the overworld (e.g. **Around me**, radius 8), map → **Export…** | the dialog shows the world name (server name + date), "Folder: saves/…", "minecraft:overworld: N chunks – ON" and the options; **Export** is active |
| 4.2 | Leave the defaults, **Export** | the progress screen goes "Writing the cache to disk…" → "Copying chunks…" (bar fills) → "Done: saves/<folder>"; a toast "World … exported – N chunks written, 0 skipped" |
| 4.3 | Disconnect, Singleplayer: the new world is in the list; open it | it opens **without** any "experimental settings" or "older version" warning; you start where you stood when exporting (vanilla may move a new player up to ~10 blocks to find solid ground), in Creative; the selected area looks like on the server; outside it there is nothing |
| 4.4 | In the exported world: look at blocks you built on the server, open a chest you had opened on the server, one you never opened | built blocks are there; the opened chest has its items; the never-opened one is empty |
| 4.5 | Look for mobs, item frames, armour stands that were in the area | they are there (if "Entities" was on); with Entities off there are none |
| 4.6 | Walk to the edge of the exported area | the edge is a clean cut; beyond it is void (no terrain, no stone platform) |
| 4.7 | Open `saves/<folder>/16tosave-export.json` | server id/name, versions, options, per dimension: selected / written / skipped, `incompleteContainerChunks` listing chunks with unseen containers |
| 4.8 | Export again with the same name | the folder gets " (2)" added; the first world is untouched |
| 4.9 | Start an export of a large selection and press **Cancel** while it copies | "Cancelled – nothing was kept"; no new world in the list; no `.…16tosave-tmp` folder left in `saves/` |
| 4.10 | Start an export, press **Hide**, keep playing; press **Export…** on the map again | the game stays smooth while it runs; the progress screen comes back; the toast appears at the end |
| 4.11 | Select chunks in the overworld **and** the Nether (dimension button), export with "Exported Nether/End outside the selection: vanilla terrain" | both dimensions are exported; no warning when opening; a portal leads to the exported Nether area; Nether terrain outside the selection is normal vanilla Nether |
| 4.11b | The same with "nothing (void)" | the dialog shows (yellow) that Minecraft will warn; opening the world shows vanilla's "Worlds using Experimental Settings are not supported" – **I Know What I'm Doing!** opens it; the Nether outside the selection is empty |
| 4.11c | Export only overworld chunks | the Nether/End option is inactive; the world opens without any warning; the Nether is a normal vanilla Nether |
| 4.12 | Export while not standing in the selection (select an area far away) | the spawn is in the middle of the largest selected area, on the ground |
| 4.13 | Close the game while an export runs, start again, export something | the leftover `.…16tosave-tmp` folder in `saves/` is removed |
| 4.14 | Damage the cache: truncate one cached `r.X.Z.mca` (game closed), then export a selection covering it | the export finishes; the toast and the metadata count the damaged chunks as skipped (unreadable/corrupt); everything else is there |

## Extras – world icon, dragon, unopened containers

| # | Steps | Expected |
|---|---|---|
| 5.1 | Export an area, open Singleplayer | the world's icon shows a small map of the exported area (dark around it) instead of the default icon |
| 5.2 | On the map, look at chunks where you know there are chests you never opened (village, your base) | they have orange diagonal stripes (zoomed out: an orange tint); after opening a chest there and waiting for the chunk to be saved, the stripes of that chunk go away (if it was the only unopened one) |
| 5.3 | First map open after this update | the map takes a little longer once (the summary index is rebuilt to learn the new flag), later opens are fast again |
| 5.4 | Export an End area **without** the main island, End "nothing (void)", fly to 0,0 in that world (`/execute in minecraft:the_end run tp @s 0 100 0` in creative) | no dragon, no boss bar; `16tosave-export.json` → `options.endDragon` = `killed`; the log has no "could not write the End's dragon state" |
| 5.5 | Export the End's main island from a server where the dragon is dead (portal active) | in the world, no dragon appears and the exit portal works; `endDragon` = `killed` |
| 5.6 | Export the main island where the dragon is still alive, or an End with vanilla terrain | the game starts the fight as usual; `endDragon` = `game decides` |
| 5.7 | If 5.4 shows a dragon | send me `latest.log` – it says why the state could not be written (the game then decides as before) |

## Fix – chest contents

| # | Steps | Expected |
|---|---|---|
| 6.1 | Open a **single** chest with items, close it, wait 1–2 s, look at the map | the orange stripes of that chunk are gone (if it was the only unopened container there) |
| 6.2 | Right after closing it, export a selection containing it | in the exported world the chest has the same items |
| 6.3 | The same with a **double** chest (put different items in the left and right half) | both halves have their items, each in the right half (if the halves are swapped, tell me) |
| 6.4 | Place a block and immediately export | the block is in the export (no 30 s wait needed) |
| 6.5 | Barrel, shulker box, hopper, furnace, dispenser | contents exported after opening, as for a single chest |


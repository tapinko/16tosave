# Changelog

## 1.0.0

First public release, for Minecraft Java 26.3 (Fabric).

- Saves the chunks your client receives on a server (blocks, biomes, block entities, entities)
  into a local Anvil cache, with a size cap; container contents are saved once you open them.
- Map of the cache: four levels of detail, height limit (e.g. below the Nether roof), live
  updates, orange stripes on chunks with containers you never opened.
- Selection: rectangle, single chunks or regions, radius around you or the world spawn,
  select all / invert / undo, named selections; saved automatically per server and dimension.
- Export to a singleplayer world: void around the selection, every vanilla dimension, world
  icon, End dragon state, a metadata file listing what was skipped.

Known limitations, including proxy networks (BungeeCord/Velocity) mixing their worlds into one
cache: see `docs/LIMITATIONS.md`.

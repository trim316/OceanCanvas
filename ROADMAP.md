# Roadmap

## M0 — Bootstrap

- [x] Fabric 26.2 project
- [x] Java 25 build
- [x] automated CI
- [x] `/oceancanvas status`
- [x] configuration model tests
- [ ] first successful local and CI build
- [ ] Gradle wrapper committed
- [ ] Mod Menu configuration screen

## M1 — Per-world configuration

- Save `oceancanvas.json` beside each world.
- Add validation and version migration.
- Expose radius, center, shape, transition width, and enabled state.

## M2 — Region engine

- Classify positions as ocean interior, transition, or vanilla exterior.
- Add deterministic transition math and exhaustive tests.

## M3 — Generation prototype

- Identify the least invasive Fabric/Mixin hook for Minecraft 26.2.
- Preserve vanilla underground generation.
- Transform only newly generated Overworld chunks.

## M4 — Biomes and structures

- Assign ocean-compatible biome data in the protected region.
- Verify caves, ores, aquifers, strongholds, trial chambers, shipwrecks, ruins, and monuments.

## M5 — Chunky and expansion

- Generate Chunky commands from per-world settings.
- Add expansion and pruning plans without deleting chunks.
- Add safe-disable coverage reporting.

## 1.0

- Tested removable release suitable for a backed-up long-term world.

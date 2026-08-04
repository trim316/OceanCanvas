# Ocean Canvas

> Ocean Canvas exists so players can spend years building the world they imagine—not years fighting world generation.

Ocean Canvas is a Fabric mod for creating a configurable, expandable ocean build region while preserving vanilla Minecraft data and avoiding save lock-in.

## Current milestone: M0 bootstrap

The current branch proves the development pipeline:

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3
- Fabric API 0.156.0+26.2
- Java 25
- `/oceancanvas status`
- automated GitHub Actions builds
- pure-Java tests for region settings

Terrain generation is **not implemented yet**. Do not use development builds for a permanent world.

## Build on Windows

1. Install JDK 25 and ensure `JAVA_HOME` points to it.
2. Open PowerShell in the repository folder.
3. Run:

```powershell
.\bootstrap-build.ps1
```

The script downloads Gradle 9.5.1 into a repository-local ignored folder, runs the tests, and builds the mod. JARs are placed in `build/libs`.

## Project principles

- The save must open without Ocean Canvas installed.
- Existing chunks are never modified automatically.
- No custom blocks, biomes, dimensions, entities, or registries are required by the save.
- Destructive operations such as chunk pruning are never automatic.
- Generation behavior is explicit and inspectable.

See [`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

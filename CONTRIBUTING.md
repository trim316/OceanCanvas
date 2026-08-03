# Contributing

Ocean Canvas prioritizes world integrity and maintainability over feature speed.

## Requirements

- JDK 25
- Minecraft 26.2
- Fabric Loader 0.19.3

## Before opening a pull request

1. Run the complete build.
2. Add tests for region math or configuration changes.
3. Update documentation for visible behavior.
4. Do not introduce custom registry entries that make saves depend on the mod.
5. Do not add automatic chunk deletion or rewriting.

## Commit style

Use concise imperative messages, such as:

- `feat: add per-world config loader`
- `fix: clamp transition width`
- `docs: document safe-disable workflow`

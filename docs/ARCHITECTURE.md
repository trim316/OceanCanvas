# Architecture

## Compatibility boundary

Ocean Canvas is a generation-time transformer. It must not add save-critical registry objects.

The codebase is divided conceptually into:

- **Core:** version-independent region math, configuration validation, expansion planning, and Chunky command generation.
- **Fabric integration:** lifecycle events, commands, config paths, and optional mod detection.
- **Minecraft compatibility layer:** the smallest possible version-specific world-generation hook.
- **Client UI:** optional Mod Menu screen and status presentation.

## Generation pipeline target

1. Minecraft creates a new Overworld chunk using vanilla generation.
2. Vanilla density, caves, aquifers, and underground biomes remain authoritative.
3. Ocean Canvas applies a bounded surface transformation inside the configured region.
4. Biome and feature handling is corrected without introducing custom registries.
5. Minecraft saves a normal chunk.

This pipeline is a design target and must be validated experimentally before release.

## Configuration

Global configuration contains defaults only. Authoritative settings are stored per world in a sidecar JSON file ignored by vanilla Minecraft.

## Mixins

Fabric events are preferred. Mixins are permitted only when no stable Fabric extension point exists, and all Mixin code belongs in the version-specific compatibility layer.

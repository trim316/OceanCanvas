# Ocean Canvas

> Ocean Canvas exists so players can spend years building the world they imagine—not years fighting world generation.

Ocean Canvas is a Fabric mod for Minecraft 26.2 that creates a configurable, expandable ocean build region while preserving vanilla world data, retaining restore provenance, and avoiding save lock-in.

## Current development state

The active GitHub release candidate is `26.2-core-v0.2.28`. The repository now contains the authoritative runtime source plus a GitHub Actions validation pipeline that:

- builds an immutable Java 25 / Fabric 26.2 candidate;
- validates control-plane safety before touching the Minecraft runtime;
- installs the exact candidate transactionally on the self-hosted Windows test machine;
- progresses the permanent runtime ladder `G2 -> G4 -> G9 -> G16 -> PROVEN`;
- preserves durable gate and per-chunk progress across bounded validation slices;
- captures checksum-manifested checkpoint evidence after every slice.

The release candidate preserves the v0.2.27 command-driven full-Canvas flatten/restore path and adds the completed recovery-hardening line through R1-87. One active chunk at a time remains the authority boundary; prior two-, four-, nine-, and sixteen-chunk scale evidence is retained where the exercised implementation is unchanged.

See `docs/runtime-actions.md` for the current GitHub runtime architecture and recovery behavior.

## Safety

Development builds are not release-ready for a permanent world unless a build is explicitly promoted after the full runtime evidence ladder. Ocean Canvas development continues to prioritize reversible behavior, truthful progress reporting, restore safety, and preservation of player-authored world data.

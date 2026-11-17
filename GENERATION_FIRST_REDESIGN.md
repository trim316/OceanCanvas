# OceanCanvas: generation-first redesign decision (2026-11-16)

## Product contract (non-negotiable)
- Author a user-defined blank-ocean canvas with deterministic boundaries, water, seabed and correct biome metadata; preserve vanilla outside selected regions.
- Shipwrecks must follow vanilla-like distribution across eligible area, not one forced wreck; structure Never/Default/Always semantics, guardian-capable monuments, configurable ruined portals, shipwreck sapling chance 5–10%.
- Restore to Vanilla, Rewipe, Pregen, protected-chunk exclusion, command-only execution, and safe expansion must remain available.
- Never overwrite player builds or protected chunks. All operations are restartable, auditable, and bounded in memory.
- No claim of correctness from structural NBT tests alone. Require disposable Minecraft load, restart, structure and lighting validation.

## Change in engineering direction
Stop making the current 4,000×4,000 physical campaign the development critical path. Preserve its checkpoints and evidence, but do not rerun passed stages merely to validate unrelated changes. No changes to that pinned runtime lineage. Stop treating synthetic serialization throughput as world-generation throughput.

## Architecture candidates to compete
A. Vanilla lifecycle with bounded region-aware batches, targeted block mutation and batched lighting.
B. Native Minecraft chunk-section construction using version-specific supported APIs before publication; minimize per-block callbacks and redundant lifecycle transitions.
C. Offline Anvil region compiler fed by complete, version-matched Minecraft chunk templates; only eligible disposable/unpublished chunks; Minecraft opens, saves and restarts the result before any adoption.
D. Hybrid: choose B for biome/structure-sensitive chunks and C for safely template-compatible chunks; region staging and immutable receipts.

All candidates must preserve vanilla data invariants, heightmaps, light, entities, POI, structures, ticks and biome data. Never write directly into a live loaded world region file. Templates are not assumed safe across versions, seeds, coordinates, structures or block entities.

## Benchmarks that matter
- Time from command invocation through durable region write, Minecraft reload and physical verification, including failures and retries.
- Record chunks/min, p50/p95 chunk latency, peak RSS, GC pauses, IO bytes, CPU, stage breakdown, restart success and world hash.
- Workloads: homogeneous ocean, irregular seabed, coastline/boundary, structures, protected interleaving, interruptions and resume.
- Repeat each representative workload and compare paired baselines on identical disposable seeds and hardware.
- Under one hour for 62,500 chunks is an aspirational design target (>=1042 verified chunks/min). No throughput threshold excuses incorrect world output.

## Fast feedback ladder
1. Seconds: unit/property tests for palettes, boundaries, selection, structure rules, deterministic output, protection and checkpoint replay.
2. Minutes: disposable Minecraft 32–128 chunk smoke tests, load/save/restart, targeted structural/lighting assertions.
3. Tens of minutes: 1,024–4,096 chunk end-to-end comparison on representative terrain; require equivalent output and restart durability.
4. Release gate: representative full canvas and targeted edge-case stress tests; reuse prior proof if source, world format and relevant behavior unchanged.

CI must not claim a full end-to-end result from section-only NBT, and must not require a multi-day campaign for every patch.

## Decision gates
- First demonstrate a *playable* Minecraft chunk generated with candidate B or C, opened and saved by a disposable server, with light, heightmaps and structure references verified.
- Then benchmark all candidates end-to-end against A, not only serialization.
- Merge only winners proven correct; if two optimizations interact, rebenchmark the combination and roll back regressions.
- Any release must pass correctness, protection, resume, expansion, command-only operation and real-world restart tests.
- Avoid modifying user worlds or spending on external infrastructure.

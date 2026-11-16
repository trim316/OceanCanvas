# Transactional region construction: alternative performance architecture

Status: isolated design/prototype branch; NOT authorized for production. Baseline runtime remains unchanged.

## Why change the unit of work?

The current SingleChunkWorldPorts.persist and final verification each call MinecraftServer.saveAllChunks(false,true,true) per chunk. Improving block-write throughput alone cannot remove the resulting repeated world-level durability barriers. Recent conservative direct-section authoring was slower than vanilla, and unsafe variants failed skylight invariants.

Goal: >=500 independently physically verified chunks/minute including durable save and restart. Speed is never grounds for relaxing verification.

## Candidate A — durable cohort authoring (first implementation priority)

1. Pick a cohort of up to 16 adjacent **unprotected** chunks from the existing dependency planner. Never cross a protected-chunk boundary or alter the production planner's semantic decisions.
2. Before mutation, persist each member's exact immutable preimage (blocks, fluids, block entities, heightmaps, structures and required metadata) and fsync its receipt. Record a cohort PREPARED manifest listing member keys, expected preimage hashes, config/seed, and target canonical hashes. Reject incomplete preparation.
3. Author chunks with the proven Minecraft block API; allow lighting engine to process changes. Each chunk retains its own physical checks and error journal. Do not mark VERIFIED at this stage.
4. Perform one authoritative world save flush for the cohort; write and fsync a COHORT_DURABLE receipt only after the flush returns. This receipt is a barrier, not evidence that individual chunks are correct.
5. On a fresh process, independently reload every cohort member and check exact target blocks, water/fluids, block entities, structures, protected neighbors, heightmaps, skylight and blocklight. Commit each VERIFIED only after physical checks pass. Any mismatch halts and quarantines the cohort; restore from immutable preimages as needed.
6. On crash: PREPARED without COHORT_DURABLE is untrusted; restore or re-verify all members before retry. COHORT_DURABLE without VERIFIED requires reload and full verification. Never infer verified from receipt existence.
7. Keep save operations on the server thread; bound resident chunks and memory, preserve cancellation, and cap cohort size by memory and latency watchdog.

Critical design caveat: current per-chunk state machine assumes persist precedes later stages. A cohort scheduler must separate authoring, lighting settlement, durability and restart verification explicitly. Do not merely suppress saveAllChunks and pretend persistence succeeded.

## Candidate B — complete chunk assembly

Generate canonical section palettes, fluids, heightmaps, and block entities into an isolated chunk representation; hand it to Minecraft through a supported chunk integration path, schedule authoritative relighting, and verify after restart. Avoid raw LevelChunkSection.setBlockState on already-live chunks unless all vanilla side effects are reconstructed. No promotion until exact structures, neighboring chunks and lighting pass.

## Candidate C — offline region transformation

Operate on a copied, closed disposable world only. Parse region files and transform targeted chunks with transactional temp files and atomic rename; preserve untouched NBT, entities and POIs. On reopening Minecraft, force correct lighting/heightmap regeneration and compare all expected physical states. Do not edit live region files or the user's worlds.

## Experiments (stage-gated, do not run redundant full gates)

- E0: instrument per-chunk flush count/time, authoring, lighting, post-save and restart verification in existing baseline; record source SHA, seed, canvas, wall time, CPU/RSS, and CI runner.
- E1: cohort sizes 1, 4, 16 on identical 64-chunk disposable worlds. First test interruption at PREPARED, during authoring, before/after save, and during restart verification. Require zero false VERIFIED, zero changed protected neighbors, and deterministic rollback.
- E2: only passing cohort sizes on 256 and 2304 chunks, alternating run order and repeating trials to separate CI noise; measure end-to-end verified chunks/min, not mutation-only throughput.
- E3: if cohort durability cannot be made correct or cannot approach 500, prototype complete-chunk construction on 64 chunks with lighting, structures, and restart checks. Keep offline transformation as a separate riskier branch.

## Promotion criteria

All geometry, ocean floor, water columns, air, fluids, lighting, heightmaps, block entities, structures/shipwreck placement, protected chunks, seed/config determinism, and interrupted-operation recovery must match the canonical oracle. Every candidate must pass independent post-restart physical verification; compare at matched workload and include save and verify cost. No candidate merges to main before all gates pass.

## Existing evidence

- 256-chunk conservative direct-authoring: 157.7 vs 200.7 verified chunks/min baseline (slower).
- 256-chunk write-cap A/B: 280.8 vs 265.3 chunks/min, preliminary and not a release-scale result.
- Direct section mutation failed adaptive skylight invariant at first chunk before subsequent conservative variants passed.

Next engineering task: implement a *real* cohort scheduler and receipt protocol on this branch, not a benchmark-only bypass of durability.

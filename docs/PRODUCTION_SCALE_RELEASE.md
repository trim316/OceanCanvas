# OceanCanvas production-scale release boundary

## Release objective

A full OceanCanvas release must be safe for the intended Forever World and must complete the default 20,000 x 20,000 block Canvas in the overnight acceptance window.

Canonical production acceptance geometry:

- width: 20,000 blocks
- chunk grid: 1,250 x 1,250
- total chunks: 1,562,500
- maximum wall time: 8 hours
- Minecraft: 26.2
- Fabric

The release gate is deliberately fail-closed. A successful build or a small command proof is not production-scale evidence.

## Why the v0.2.28 command runtime is not the production scale engine

The v0.2.28 `FullCanvasServerRuntime` is the recovery/correctness canary. It is intentionally conservative and is useful for proving command lifecycle, interruption recovery, durable preimage handling, and exact restoration on bounded campaigns.

It is not eligible to certify the 20k production target because its current execution model is sequential per chunk and the adapter performs expensive durable settlement around each chunk. With the default 40-tick physical settle and 40-tick lighting settle, 1,562,500 chunks cannot meet the overnight SLO even before terrain writes and persistence costs are counted.

The exact-preimage canary is also the wrong storage model for a 20k production run. Capturing the full mutable vertical column for every production chunk would require on the order of 120 billion block-state entries at the default floor geometry before block-entity/NBT overhead.

Therefore:

1. Keep the v0.2.28 runtime as the bounded recovery/correctness canary.
2. Do not create production release evidence from the v0.2.28 sequential full-Canvas adapter.
3. Use the mature Pregen/Restore scheduler architecture for the production 20k path.

## Mature production engine provenance

The recovered mature source line is:

- build: `OceanCanvas 26.2-v253.125.54-visible-water-void-and-spawn-light-integrity`
- frozen source archive SHA-256: `8c3b1d2377373eea4f208b32126ae8cf086865e6009cd0138a309fd19308d73d`
- declared source-tree SHA-256: `b96e263e6cd74a4f8188e2b00b5dce2f6d8722c825174d98b6929b4c70e29d8f`

The production architecture separates terrain admission from lighting finalization, admits multiple terrain chunks under a wall-time budget, uses bounded residency/ticket cohorts, and drains a bounded lighting backlog rather than serializing every chunk behind a whole-server save/settle cycle.

Integration must preserve the later recovery-line safety work where compatible, but must not replace the mature scheduler with the sequential canary implementation.

## Required runtime acceptance ladder

The exact release candidate must produce build-bound, fresh-world evidence for all of the following:

1. 500-block stage: 1,024 chunks.
2. 5,000-block stage: 98,596 chunks.
3. 20,000-block stage: 1,562,500 chunks.
4. Fresh-world overnight 20k stage: 1,562,500 chunks completed in <= 8 hours.

The final diagnostic bundle must come from the overnight world and all production release diagnostics must PASS. The receipt must bind to the exact JAR SHA-256 and exact production build identity.

## Release evidence requirements

`release-evidence/v1.0.properties` is valid only when it contains, at minimum:

- `releaseVerdict=PASS`
- `productionEngine=v253.125.54`
- `freshWorld=true`
- `targetBlocks=20000`
- `targetChunks=1562500`
- `maxHours=8`
- `completedHours=<value <= 8>`
- `diagnosticVerdict=PASS`
- `exactBuild=true`
- `jarSha256=<64 lowercase hexadecimal characters>`

A READY marker without this evidence is a hard failure. CI/controller changes may reuse immutable evidence only when the produced JAR SHA-256 is identical to the certified JAR.

## Release rule

Do not tag or publish OceanCanvas v1.0 merely because CI is green. Publish only after the mature production engine is integrated and the exact candidate satisfies the acceptance ladder above. This rule prevents a correctness canary from being mistaken for a Forever-World production engine.

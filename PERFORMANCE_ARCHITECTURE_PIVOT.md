# Durable checkpoint architecture experiment (isolated, NOT release-approved)

## Why the existing pipeline cannot be the speed plan
The flatten pipeline currently calls `MinecraftServer.saveAllChunks(false, true, true)` once in `SingleChunkWorldPorts.persist` and again in final physical verification, **per chunk**. Those global, synchronous barriers dominate physical scanning. Previous grouped-save experiments regressed throughput; simply grouping calls is not proven safe or faster.

## New architecture: staged cohorts with explicit durable barriers
1. **Author cohort:** allow multiple independent chunks to complete their writes under the server-thread budget; retain preimage, immutable work identity, and a durable intent record before any mutation. No VERIFIED credit.
2. **Physical-settle cohort:** let vanilla lighting, fluid, heightmap and neighbor reconciliation finish. No save credit and no VERIFIED credit. Any dirty/changed chunk must re-enter settlement.
3. **Verify cohort:** scan every candidate chunk's physical blocks, block entities, lighting and invariants; record a provisional verification tied to a chunk content fingerprint and epoch, never a final receipt.
4. **Durable cohort barrier:** force a Minecraft-supported save of all dirty cohort chunks and await a real durable completion signal. If chunk-scoped flush cannot provide that guarantee, fall back to a synchronous whole-server save **once per cohort**, not once per chunk. Never infer durability merely from scheduling an asynchronous save.
5. **Post-barrier validation:** re-read physical chunk state and compare fingerprints/invariants after the barrier; if anything changed, invalidate the provisional proof and reprocess. Append final receipts only after durable barrier and physical verification both succeed.
6. **Recovery:** on restart, rebuild unfinished cohorts from durable intent/journal, re-read saved chunks, and re-verify. Never trust an in-memory success flag, provisional receipt, or previous process's unsaved state.

## Fail-closed rules
- Preserve strict physical, lighting, fluid, structure, block-entity and protection semantics; no sampling in place of final verification.
- No receipt is marked VERIFIED before an acknowledged durability barrier and post-barrier verification.
- Any exception, server shutdown, interrupted save, chunk unload, changed neighbor or ambiguous journal -> no completion credit; recover/retry from the durable checkpoint.
- Do not modify the pinned 2k release lineage, main, or personal Minecraft worlds. Use free disposable CI worlds only.

## Experiments and acceptance
A. **Feasibility spike:** instrument cohort size, number and duration of global saves, physical checks, flush wait, and throughput; compare against exact baseline with 8 in-flight chunks. First prove that barrier count falls dramatically and that all receipts remain correct.
B. **Crash matrix:** kill before barrier, during save, immediately after save, and before final receipt; restart each and verify complete physical state, journal monotonicity, and absence of false VERIFIED credit.
C. **Geometry:** 64-chunk screen -> 1,024-chunk restart -> 2,304-chunk restart -> 2k radius; preserve exact runtime hashes and previous evidence.
D. **Performance:** target >=250 verified chunks/min on matched restart workload as a meaningful architectural win; then 350 and 500. Reject candidates that pass performance but fail any safety test.
E. **Rollback:** feature flag defaults OFF; no promotion without complete crash matrix and independent physical verification.

## Immediate implementation questions
- Does the target Minecraft/Fabric version expose a chunk-scoped synchronous save/flush with acknowledged completion? Inspect implementation, not method names.
- Can cohort state be represented as durable intent plus stage epochs without altering the existing per-chunk journal's VERIFIED semantics?
- Can provisional scans remain valid across neighbor/light updates? If not, re-scan after flush.
- Is the primary bottleneck save *count*, save *scope*, or region-file I/O serialization? Instrument all three before choosing cohort size.

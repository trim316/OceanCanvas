# OceanCanvas Current Work Queue — 2026-10-27

This file is the short, current execution queue layered on top of `AUTOMATION_QUEUE.md`. It exists so autonomous development has a deep runway and does not stop after one green tranche.

## Execution rules

1. Work top-to-bottom unless the current item is blocked by an active exact-head run; then start the next independent item on a separate branch.
2. Do not rerun R1-297 through R1-302 exact candidate proofs. Reuse their evidence. R1-303 is currently under exact-head proof.
3. Every code tranche gets a focused branch, deterministic regression where practical, exact-head Recovery Core Proof, nondivergence check, then merge.
4. Do not weaken acceptance, timeouts, restore, lighting, block-entity, ticket, or completion contracts to make CI green.
5. No personal Minecraft/Modrinth worlds and no paid infrastructure.
6. After merging one item, immediately start the next READY item. The queue should not go idle while READY work remains.
7. If a task audit finds the code already hardened, mark it NO-CHANGE with exact source evidence and immediately move to the next item instead of manufacturing a patch.

## Active / ready recovery-authority runway

| ID | Status | Work | Primary surface | Completion evidence |
| --- | --- | --- | --- | --- |
| R1-303 | IN_PROGRESS | Refuse symlink/check-open redirection in post-COMPLETE sidecar digest authority. | `PostCompleteRecoveryProof.java` | Exact-head Recovery Core Proof for `d47ac72b97ac26e9181ae77f08162d81567be0d1`, then nondivergent merge. |
| R1-304 | READY | Audit `AcceptanceReceiptRecorder` and related acceptance evidence reads/writes for link-following opens, staged-file ambiguity, and post-check replacement. Patch only real gaps. | `core/acceptance/AcceptanceReceiptRecorder.java` + self-test | Direct-file/symlink negative regression and normal Gradle `check`. |
| R1-305 | READY | Audit `OperationManifestStore` canonical/staged authority for every `Files.newInputStream`, `Files.copy`, regular-file check, and atomic publication path; close remaining TOCTOU/symlink gaps. | `core/pipeline/OperationManifestStore.java` | Manifest bounded-read/atomic regressions remain green plus new direct-authority negative if needed. |
| R1-306 | READY | Audit `BlockEntitySidecarStore` verified replay, digest, staging, and archive publication for direct-file authority throughout the actual open/copy operation. | `core/restore/BlockEntitySidecarStore.java` | Existing bounded sidecar tests + targeted symlink/check-open regression. |
| R1-307 | READY | Audit `BlockStateRegistryIdentity` persistent authority reads/writes for link-following opens or identity replacement windows. | registry identity store under `core/restore` | Direct-file identity regression; preserve external target bytes on refusal. |
| R1-308 | READY | Audit `BoundedCampaignIdentityStore` read/publish/lock paths for any direct-file authority gap not covered by current regular-file tests. | `core/expansion/BoundedCampaignIdentityStore.java` | Exact bounded identity and symlink/TOCTOU refusal tests. |
| R1-309 | READY | Audit `FourChunkCanaryIdentityStore` publication staging/lock authority, not just bounded replay; ensure lock/stage/canonical paths cannot redirect through links. | `core/expansion/FourChunkCanaryIdentityStore.java` | Deterministic symlinked lock/stage/canonical negative tests where platform permits. |
| R1-310 | READY | Audit `NineChunkCanaryIdentityStore` publication staging/lock authority beyond already-proved bounded read. | `core/expansion/NineChunkCanaryIdentityStore.java` | Publication-path direct-authority test; do not redo R1-295 replay proof. |
| R1-311 | READY | Audit `SixteenChunkCanaryIdentityStore` publication staging/lock authority beyond already-proved bounded read. | `core/expansion/SixteenChunkCanaryIdentityStore.java` | Publication-path direct-authority test; do not redo R1-296 replay proof. |
| R1-312 | READY | Audit `TwoChunkCanaryIdentityStore` stage/lock/publication authority beyond already-proved bounded read. | `core/expansion/TwoChunkCanaryIdentityStore.java` | Publication-path negative regression; do not redo R1-298 replay proof. |
| R1-313 | READY | Repository-wide source audit for remaining `Files.newInputStream`, `Files.copy`, `Files.isRegularFile`, `Files.exists`, and `FileChannel.open` on durable authority/evidence paths without `NOFOLLOW_LINKS`; convert every real finding into focused patches. | `runtime-src/src/main/java/net/oceancanvas/core/**` | Zero unexplained durable-authority link-following call sites; findings documented by file/function. |
| R1-314 | READY | Audit durable lock-file paths themselves: symlinked `.lock` files must not redirect authority or lock unrelated external files. | journal, manifest, preimage, identity stores | Symlinked lock path refuses safely and leaves external target unchanged. |
| R1-315 | READY | Audit staged `.tmp` evidence paths: preexisting symlink/directory/device-like nonregular stage must be preserved/refused, never followed or replaced silently. | all atomic publishers | Cross-store staged-path negative regression or per-store focused tests. |
| R1-316 | READY | Add a shared direct-regular-file helper only if repeated patterns can be consolidated without changing semantics; otherwise explicitly keep local guards. | core durability utilities + callers | No authority weakening; all existing exact tests green; helper itself tested against symlink/directory replacement. |
| R1-317 | READY | Race-hardening review for size-check/read/recheck loops: ensure integer overflow, truncation, growth, and authority replacement fail closed consistently across evidence digests. | acceptance/restore/journal/receipt digest helpers | Deterministic mutation-during-read seam tests where injectability exists. |

## Recovery/restart correctness runway

| ID | Status | Work | Primary surface | Completion evidence |
| --- | --- | --- | --- | --- |
| R1-318 | READY | Reconcile all recovery certificates so optional/missing evidence cannot become inferred PASS and every digest binds exact operation/chunk/runtime identity. | acceptance certificate classes | Cross-certificate negative matrix for mismatched operation, chunk, digest, and absent optional evidence. |
| R1-319 | READY | Prove an authoritative journal append failure after successful physical action cannot cause duplicate destructive mutation on reopen. Reuse existing fault seams; do not rerun unrelated world campaigns. | `SingleChunkPipeline`, journal fault tests | Deterministic replay converges or fails closed with no second mutation authority. |
| R1-320 | READY | Prove receipt append/copy failure cannot alter authoritative stage progression or erase first-failure evidence. | pipeline + `RuntimeReceiptLog` | Receipt I/O failure remains forensic-only; journal truth and first failure persist. |
| R1-321 | READY | Re-audit ticket cleanup/reacquisition after current recovery changes: every terminal/failure/restart path owns at most one radius-zero ticket and releases exactly what it owns. | server runtime/ticket policy | Deterministic ticket lifecycle matrix; no wider ticket and no leak. |
| R1-322 | READY | Reconstruct completion/release evidence from immutable files after a cold process reopen and reject any cross-operation evidence mixing. | acceptance reconstruction + hosted core fixture | Cold reconstruction PASS for exact evidence; mixed fixture refuses before credit. |
| R1-323 | READY | Current-source disposable one-chunk smoke/restart proof only after static recovery queue above is integrated enough to justify it; reuse existing proven scenarios and run only the minimal changed-source gate. | hosted disposable proof | Exact merged-source run; do not repeat historical scenarios that changed code cannot affect. |

## Production / release runway after recovery static queue

These remain READY for source analysis/patching but runtime scale certification is gated by current production evidence. Do not spend an hour merely rerunning an unchanged red campaign.

- **P-01 READY:** Reconcile current forever-world production branch against merged recovery authority fixes and identify only source deltas that actually need porting.
- **P-02 READY:** Reinspect the preserved terminal pressure-retry/tail evidence from the most recent production RED before touching scheduler budgets; fix the concrete stuck state, not the timeout.
- **P-03 READY:** Attribute remaining synchronous main-thread physical proof call sites on current source; if material, route exact proof through existing cooperative state without sampling or weakening proof.
- **P-04 READY:** Audit lighting finalizer debt transitions for any state that can become parked with no wake source; add a deterministic scheduler regression before another expensive runtime campaign.
- **P-05 READY:** Verify command-only forever-world flattening remains present and fail-closed with explicit destructive authority; UI is not a release dependency.
- **P-06 READY:** Verify protected pregenerated chunks are skipped on subsequent flatten/pregen operations and protection identity survives restart.
- **P-07 READY:** Verify shipwreck `Always` placement semantics remain vanilla-like spacing/distribution, not one-off placement, without changing unrelated structures.
- **P-08 READY:** Reconcile Never/Default/Always structure semantics for monuments, shipwrecks, ruined portals; preserve guardian behavior for moved monuments.
- **P-09 READY:** Reconcile multi-pass relight/quarantine/stall-escape behavior against current production source and preserved first-failure evidence.
- **P-10 READY:** Once production source changes, run the smallest exact-source throughput gate that covers the changed path; promote to 2k/5k/20k only when the preceding exact gate is green.
- **P-11 READY:** Final release-candidate identity: bind exact Git SHA, built JAR SHA-256, Minecraft/Fabric versions, recovery schema, and immutable evidence roots.
- **P-12 READY:** Release checklist reconciliation: no publisher/tag until exact required scale/overnight evidence exists; missing evidence is HOLD, not synthetic PASS.

## Queue depth contract

At the end of every autonomous invocation:

- If fewer than **15 READY** items remain in this current queue, add concrete new release-risk tasks before stopping.
- Never add duplicate reruns as filler.
- Prefer code fixes and deterministic regressions over orchestration changes.
- If a green exact-head candidate is mergeable and nondivergent, integrate it and immediately start another READY task in the same invocation when execution capacity permits.

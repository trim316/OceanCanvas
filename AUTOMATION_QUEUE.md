# Ocean Canvas Autonomous Work Queue

This is the durable ordered queue for hourly autonomous development. The queue must always contain enough independent READY work that one blocked runtime item cannot consume an entire hourly cycle.

## Canvas-style dispatch: one work session, multiple independent tranches

The Canvas `GAMEPLAY_QUEUE.md` pattern is the operating model here: a large durable pool of *small, independently executable* product tasks; each task includes its precise source surface, an observable acceptance check, and a safe fallback. This is a queue for real Ocean Canvas engineering, not a queue of CI/workflow improvements.

**Prioritized ready lanes:** (A) recover/restore safety and the clean/interrupted one-chunk exit gate; (B) independent correctness and negative-case regressions while real Minecraft runs; (C) gated scale/performance and full-feature integration once proven safe. Do not do routine release paperwork while ready correctness tasks exist.

**Execution algorithm for each scheduled coding session:**

1. Inspect exact `recovery/core-proof` HEAD, hosted build and cloud Minecraft evidence, and the first unresolved entry in `FAILURE_LEDGER.md`. If tests are red, diagnose the first meaningful failure, save its evidence and fix the root cause when possible.
2. Select the highest-priority unclaimed READY *code task* with an identified implementation file, smallest deterministic regression and bounded acceptance criterion. If a task is blocked by another running Minecraft campaign, select an independent code task. Never pretend a queue entry or cloud audit is itself an implementation.
3. Create a focused source branch from the latest verified recovery commit for an independent task (for example `work/r1-19-journal-negative-cases`). Commit the implementation **and** its regression together. Avoid pushing partial changes repeatedly to a branch with active validation.
4. Validate the focused branch with Java 25/Gradle deterministic tests in the available free GitHub tier, then open a PR targeting `recovery/core-proof`. If GitHub-hosted CI cannot be triggered for that branch without expanding security permissions, use safe local/offline checks, create a reviewable PR and disclose the missing CI. Do not merge or claim DONE without current exact-source evidence.
5. While a previous focused PR is validating, work on another genuinely independent READY item using a different branch; never churn a running branch or cancel real Minecraft proof. After a PR passes, integrate safely and revalidate the combined head.
6. **Do not stop after one green task** if the coding session still has execution capacity. Start the next independent task; only stop for platform execution limits, an actual safety gate or missing user authorization. If work is interrupted, leave exact task ID, branch/commit, CI run, failure signature and next action in this queue.
7. When fewer than 12 independent READY code tasks remain, refill from the accepted release-risk categories. Always keep a larger secondary pool rather than inflating the count with duplicate or vague paperwork tasks. Scheduled audits detect depletion but cannot themselves write code.

**Explicit restrictions:** no paid coding agents or extra charges; no automatic destructive tests on a personal Minecraft/Modrinth profile; no scale promotion based on an older source commit. Scheduled sessions are periodic, not a persistent 24/7 coding agent. A successful cloud run must not be mistaken for active code generation between sessions.

### Near-term independently dispatchable task cards

| Task | Source surface | Concrete completion proof | Safe fallback |
| --- | --- | --- | --- |
| R1-19 | `core/journal/CoreJournal.java`, `core/receipt/RuntimeReceiptLog.java`, `CoreSelfTest.java` | Truncation, duplicate, reordered and checksum-valid malformed event regressions pass against exact head | Preserve original file and reject replay on any ambiguity |
| R1-18 | `core/pipeline/OperationManifestStore.java`, `CoreSelfTest.java` | Two conflicting creators cannot both publish operation authority; interrupted staged manifest survives | Refuse operation when manifest state cannot be established |
| R1-21 | `mod/server/SingleChunkWorldPorts.java`, runtime test harness | All terminal failures and restart paths release only the owned radius-zero ticket | Fail closed without scheduling more chunks |
| R1-22 | `BlockStatePreimageStore.java`, runtime receipts, cloud test controller | Identical captured and restored preimage SHA verified at all durable stages and in cloud artifacts | Do not claim COMPLETE on missing/inconsistent evidence |
| R1-24 | Physical authoring adapter and core restart fixtures | Interrupted partially authored column safely converges on replay without widening authority | Remain in single-chunk recovery |
| R1-28 | Minecraft adapter and isolated cloud Minecraft fixture | Block-entity states admitted/refused based on actual game state even before NBT materializes | Refuse a chunk that cannot be backed up completely |

## Rules

- Work top-down by priority, but continue with independent READY items when a higher item is BLOCKED or WAITING_ON_RUNTIME.
- Preserve useful partial work. Do not revert a whole tranche because one experiment failed.
- Every genuine defect fixed gets a regression test or deterministic invariant where practical.
- Runtime evidence, logs, checkpoints, and artifacts are evidence. Preserve them before changing the next hypothesis.
- Unknown destructive-risk states fail closed, but unrelated safe work continues.
- Product/core correctness outranks runtime-proof plumbing; runtime-proof plumbing outranks release/automation polish.
- Do not re-enable scale beyond one chunk until the one-chunk recovery exit gate passes repeatedly without harness changes.

## P0 — active recovery proof

| ID | Status | Work |
| --- | --- | --- |
| R0-01 | IN_PROGRESS | Prove recovery.3 one-chunk lifecycle on the self-hosted Windows/Minecraft runner. Launcher world identity is fixed; the September 30 full run reached COMPLETE and final restart, but a session-local evidence check rejected stages already recorded by prior runs. Run 36714701694 passed a resumed one-chunk lifecycle, completing a full checksummed journal re-attestation and final restart; independent clean-start repetition and interruption campaigns remain outstanding. Do not erase prior completed state before it is attested. |
| R0-02 | READY | After first successful launch, verify restart hold progression records every durable stage exactly once and final restart verification cannot be skipped. |
| R0-03 | READY | Validate exact preimage restore after real Minecraft restart, including preimage SHA continuity across PREIMAGE_CAPTURED / RESTORE_COMPLETE / RESTORE_VERIFIED receipts. |
| R0-04 | READY | Add reduced deterministic regression coverage for any runtime defect exposed by R0-01 through R0-03. |
| R0-05 | READY | Repeat the same one-chunk proof from a clean state without modifying the harness. |
| R0-06 | READY | Repeat one-chunk proof with deliberate interruption after PREIMAGE_CAPTURED; resume without recapturing or mutating before durable authority exists. |
| R0-07 | READY | Repeat with deliberate interruption after PHYSICAL_AUTHORED; prove persisted preimage permits safe recovery/restore. |
| R0-08 | READY | Repeat with deliberate interruption during RESTORED/RESTORE_VERIFIED boundary; prove no false COMPLETE. |
| R0-09 | READY | Add independent post-COMPLETE world reopen verification that restored block-state identity survives process restart. |
| R0-10 | READY | Produce a compact one-chunk recovery certificate bound to source commit, JAR SHA-256, operation ID, chunk, preimage SHA-256, journal digest, and receipt digest. |

## P1 — one-chunk robustness, independent work

| ID | Status | Work |
| --- | --- | --- |
| R1-01 | DONE (deterministic core scope) | Atomic acceptance-state persistence, complete journal append, torn/unterminated journal rejection and regressions passed hosted CI through 42aaa2d. Real interrupted Minecraft proof is tracked under R0-06 through R0-08. |
| R1-02 | DONE (partial scope) | Closed encoded-byte-count integer overflow before allocation and added a signed-digest malformed-file regression test. Additional format and registry-stability audit remains R1-14. |
| R1-03 | READY | Audit block-state restore behavior for fluid states, scheduled ticks, heightmaps, and lighting invalidation; identify any semantic gap before scale. |
| R1-04 | READY | Audit block-entity refusal path and ensure refusal occurs before first mutation with durable diagnostic evidence. |
| R1-05 | READY | Audit ticket lifecycle: acquire, resident proof, retry grace, restart reacquire, release, and failure cleanup remain radius-0 and one-chunk only. |
| R1-06 | READY | Add runtime evidence snapshot at each acceptance hold so a failed later stage does not erase earlier proven stages. |
| R1-07 | IN_PROGRESS | The forensic runtime receipt append now writes all bytes before fsync and rejects unterminated records (d583aa7 + 1020267 regressions). Best-effort independent evidence copy on every failed runtime stage remains. |
| R1-08 | READY | Add a machine-readable failure-classification receipt containing stage, first failure signature, runtime identity, and whether world mutation had begun. |
| R1-09 | READY | Ensure completed old recovery attempts are archived with immutable identity instead of deleted when starting a new clean repetition. |
| R1-10 | DONE | The filtered recovery branch push workflow is the authoritative automatic runtime proof; prior PR trigger is retired. |

| R1-11 | DONE | Stop cancelling a live self-hosted world proof on subsequent source commits: serialize newer runs behind active proof, then deduplicate obsolete candidates safely before launch. |
| R1-12 | DONE | Require at least ten genuine acceptance restarts and eleven distinct server opens before certifying complete proof; independently validate checksummed journal across prior invocations. |
| R1-13 | READY | Implement a non-destructive, explicit fresh-repeat mode that archives completed receipts and preimage provenance before resetting harness state. |

| R1-14 | IN_PROGRESS | Full bounded registry-ID preflight before any restore writes is implemented at 26f7770; prove stable IDs across supported restarts and bind preimage to runtime/registry identity or serialize stable state identities before cross-version restore. |
| R1-15 | READY | Implement independent fresh-repeat operation on an explicitly disposable world only after prior completed operation is fully attested and archived, without silently replacing preserved recovery evidence. |

## Current exact-head evidence gate\n\nLatest changes: 42aaa2d (journal terminator regression), 5b6bdd5 (reject unterminated journal records), 26f7770 (preflight all restore state IDs before any write), a9c2eb5 (torn-tail regression), da5ef2b (full journal append). These are **not release-certified** until exact-head hosted CI passes and real isolated Minecraft interruption testing is repeated. Scheduled cloud audits are support only, not code development.\n\n## New isolated real Minecraft proof lane\n\nThe default-branch `hosted-one-chunk-proof.yml` checks out recovery/core-proof on GitHub-hosted Ubuntu, builds Java 25, launches a disposable Minecraft 26.2 Fabric server, restarts at each acceptance hold, and collects the journal, receipts, session logs, and final verdict without using the user's local PC. First run: 36719537575. A hosted test outcome is evidence only for its pinned tested SHA; later commits must not inherit the PASS. No repeated clean proof, interruption certification, or scale promotion is granted by this one run alone.\n\n## September 30 correctness advances (current branch)

- Preimage writes now **fail closed** if the filesystem cannot atomically replace the durable backup, rather than falling back to a non-atomic overwrite. Retains staged evidence and existing canonical preimage.
- Preimage SHA helper now rejects oversized serialized files before heap allocation; regression checks both the read verifier and hash entry points, and preserves a canonical preimage despite orphan staged temp.
- Forensic receipts now advance the in-memory sequence only after the complete record is written and fsynced. A deterministic I/O failure/retry regression verifies the next successful append remains contiguous.
- The earlier isolated hosted Minecraft lifecycle PASS is bound to an older source commit; these newer changes have hosted build/preflight tests in progress and **must not inherit that prior runtime PASS**.
- Next release-critical work: runtime test these exact changes on an isolated disposable world; then deliberate crash tests, registry identity across restart, and exact cross-version restore refusal.

### Canvas-style multi-branch integration handoff (September 30)

- `recovery/core-proof` combined source commit: `0be1ec7d23c0ad5170a03f3b35c1a204822ce9a5`; exact-head cloud run: `36729771714` pending when queued. R1-18 and R1-19 focused branches independently passed before integration. Preserve actual combined-CI verdict and fix any meaningful regression before promoting either task to DONE.
- After combined validation, continue with **R1-21 ticket cleanup**, **R1-22 preimage SHA continuity** or **R1-24 partial authoring idempotency** as distinct branches while isolated Minecraft restart campaigns remain separate. Do not create duplicate branches for already merged R1-18/R1-19.

## P1B — independent release-risk backlog (code-only while runtime tests run)

These are concrete independent tasks, not automatic authority to mutate worlds. Only promote a task to DONE after an implementation and exact-head test evidence exist. Work top-down unless a higher task is blocked; do not manufacture completed status from CI alone.

| ID | Status | Work |
| --- | --- | --- |
| R1-16 | IN_PROGRESS | Block-state ID/source identity admission is implemented and deterministic regression passed at 59c0b15; real Minecraft failure-path evidence proving no mutations on rejected capture is still required before DONE. |
| R1-17 | READY | Bind preimage verification and evidence to a stable Minecraft registry fingerprint; fail closed on identity drift. |
| R1-18 | MERGED / COMBINED CI PENDING | Exclusive sibling manifest writer lock, conflicting-writer rejection, and safe post-lock reopen in PR #5 (focused SHA 035539b, focused CI 36729504514 PASS); merged as 0be1ec7. Do not call DONE until exact combined recovery HEAD passes. |
| R1-19 | MERGED / COMBINED CI PENDING | Duplicate/reordered signed journal and receipt regressions in PR #4 (focused SHA 8dda1de, focused CI 36729200560 PASS); prior malformed escapes covered at 0d8c33d/4127f87. Merged as dc35a79; verify combined HEAD before DONE. |
| R1-20 | READY | Audit restart-stage recovery when acceptance hold is present but forensic receipts are absent or partially written. |
| R1-21 | READY | Prove chunk ticket cleanup on failed preimage admission, failed restore, shutdown and cold resume. |
| R1-22 | READY | Capture and independently verify preimage SHA continuity across capture, restore and restore verification receipts. |
| R1-23 | READY | Add explicit source runtime/registry compatibility refusal before cross-version preimage replay. |
| R1-24 | READY | Audit and regression-test physical authoring idempotency after an interrupted partial column, including safe replay. |
| R1-25 | READY | Audit gravity and liquid settlement restart semantics; reduce any discrepancy to deterministic tests. |
| R1-26 | READY | Bound and validate world-height/preimage geometry and state-count arithmetic at every entry point. |
| R1-27 | READY | Make completion evidence independently reconstructible from immutable operation manifest, journal and receipt digests. |
| R1-28 | READY | Validate block-entity admission behavior against actual Minecraft block states in isolated cloud tests. |
| R1-29 | READY | Audit persistent save and restore ordering so COMPLETE cannot precede restored-world flush verification. |
| R1-30 | READY | Add isolated hosted runtime adversarial tests for interrupted preimage staging and canonical corruption refusal. |
| R1-31 | READY | Create a non-mutating clean-world certification preflight that refuses accidental production-world paths. |
| R1-32 | READY | Review cloud interruption proof failures, preserve first-failure logs and add targeted deterministic reproductions. |

## P1C — next implementation queue (new release-risk tranche)

Dependency rule: these are independent, non-scale engineering tasks available while one-chunk runtime campaigns run. Each task must end with code or a concrete failing/proving regression and exact-head test evidence; an audit-only finding must produce a follow-on implementation task. Do not duplicate P1B tasks or use a previous commit's runtime PASS to certify modified code.

| ID | Status | Work |
| --- | --- | --- |
| R1-33 | READY | Implement deterministic canonical preimage byte-level comparison without large temporary clones; test idempotent restart paths. |
| R1-34 | READY | Reject mutated block-state registry snapshots during preimage restore preflight before any writes; add negative-case integration tests. |
| R1-35 | READY | Prove entity-free refusal scans cover every saved vertical section, including high build-limit and below-sea-level blocks. |
| R1-36 | READY | Verify exact restoration of waterlogged vanilla block states and natural fluid propagation after save/reopen in a disposable test world. |
| R1-37 | READY | Verify changed biome/weather surface ice and snow do not create false restore PASS or destructive retry loops. |
| R1-38 | READY | Audit cross-chunk edge and corner light propagation after single-chunk restore without force-loading adjacent chunks. |
| R1-39 | READY | Test restart behavior between physical write completion and the first durable PHYSICAL_AUTHORED journal append. |
| R1-40 | READY | Test restart behavior between durable RESTORE_VERIFIED journal entry and final ticket release receipt. |
| R1-41 | READY | Validate backup/manifest/journal/receipt provenance agreement before resuming any destructive single-chunk stage. |
| R1-42 | READY | Enforce a hard fail-closed policy for stale or unknown backup format versions and preserve the original bytes as evidence. |
| R1-43 | READY | Add bounded streaming CRC/journal read verification to avoid heap spikes from unexpectedly large corruption artifacts. |
| R1-44 | READY | Verify save/reload preserves heightmaps at the authored ocean floor and after exact preimage restoration. |
| R1-45 | READY | Instrument bounded block-write/physical-scan counters per stage for realistic performance regression baselines. |
| R1-46 | READY | Test persistent operation identity consistency across negative chunk coordinates and canvas boundary selections. |
| R1-47 | READY | Add deterministic test coverage for duplicate successful stage acknowledgments after a forced process restart. |
| R1-48 | READY | Make runtime proof failure artifacts preserve world metadata and backup hashes even when log collection itself fails. |
| R1-49 | READY | Add explicit immutable release-candidate identity linking exact Git SHA, built mod JAR hash, loader, Minecraft version and recovery format. |
| R1-50 | READY | Validate command and config refusal for destructive authority absent exact chunk confirmation in cloud Minecraft. |
| R1-51 | READY | Regression-test simultaneous scheduled cloud checks and pinned hosted runtime proofs to ensure evidence never mixes revisions. |
| R1-52 | READY | Audit ability to shut down and reopen during prolonged physical settlement and light-settlement waits without false stage credit. |
| R1-53 | READY | Add compatibility boundary tests for absent/mismatched world operation manifest before any resident chunk is altered. |
| R1-54 | READY | Verify stale resident-chunk futures cannot widen ownership or leave ticket leaks during bounded reacquisition. |
| R1-55 | READY | Establish a reproducible fresh-world proof baseline with archived original and final restored chunk hashes. |
| R1-56 | READY | Record exact first-failure signatures and block-mutation-started flags for every interrupted cloud recovery scenario. |

**Dispatch order:** prioritize an existing READY task from P0/P1/P1B when it blocks safety or one-chunk proof; use P1C tasks to fill independent execution capacity. Complete one cohesive task, commit and validate, then immediately select another READY item. All scale/overnight tasks remain BLOCKED until the defined recovery exit gate passes.

## P1D — queued recovery implementation tranche (parallel branches)

New independent work pool after the Canvas-style branch integration. Select the highest release-risk READY item not already implemented by another branch. Each tranche must change code and add a deterministic test or isolated-world evidence; send it through its own focused `work/<id>-...` branch and integrate only after exact-head CI passes. This is not authorization for destructive tests on a personal world, paid infrastructure, or scale promotion.

| ID | Status | Implementation task | Intended source/test surface | Acceptance gate |
| --- | --- | --- | --- | --- |
| R1-57 | READY | Create a restore-state compatibility test fixture covering same-version registry reload and explicit refusal on mismatched runtime identity. | `BlockStatePreimageStore and disposable Minecraft fixture` | Exact registry fingerprint agrees after clean restart; mismatched fixture is refused before writes |
| R1-58 | READY | Regression-test crash immediately after atomic preimage publication but before PREIMAGE_CAPTURED journal append. | `SingleChunkPipeline and preimage restart fixture` | No recapture, no discarded original backup and exactly one durable transition on replay |
| R1-59 | READY | Audit operation-authority fail-closed behavior if a staged manifest exists alongside a valid canonical manifest. | `OperationManifestStore and CoreSelfTest` | Identical canonical manifest remains authoritative without deleting unresolved staged evidence |
| R1-60 | READY | Test simulated disk-full exceptions independently at manifest, preimage, journal and receipt fsync boundaries. | `Durability stores and injectable channel/test hooks` | Every injected failure preserves prior canonical evidence and cannot grant false COMPLETE |
| R1-61 | READY | Add strict bounded decoding tests for zero-byte, invalid UTF-8 and extraneous-field journal records. | `CoreJournal and CoreSelfTest` | Malformed valid-CRC inputs fail without silent replacement or skipped records |
| R1-62 | READY | Add deterministic restoration preflight for state-ID references at the final preimage index. | `SingleChunkWorldPorts restore preflight and test fixture` | Invalid final index blocks every preceding restore write |
| R1-63 | READY | Prevent preimage temporary-file collision from erasing forensic evidence of a previous interrupted capture. | `BlockStatePreimageStore and CoreSelfTest` | Existing orphan temp is preserved and a competing write is refused |
| R1-64 | READY | Prove persistent journal replay retains first failure evidence when a late stage fails on reopen. | `CoreJournal and SingleChunkPipeline tests` | Restart retains the exact first failing stage and refuses any later success fabrication |
| R1-65 | READY | Bound runtime receipt size and ensure oversized receipt files fail safely during verification. | `RuntimeReceiptLog and CoreSelfTest` | Oversized or forged receipts fail before disproportionate allocation |
| R1-66 | READY | Verify no duplicate chunk mutation after restart when a physical stage returned success but journal append failed. | `SingleChunkPipeline and deterministic fault-injection adapter` | Idempotent retry converges without widening chunk authority |
| R1-67 | READY | Instrument read-only verification of canonical ocean block-state sampling at chunk borders after world reload. | `SingleChunkWorldPorts and hosted proof controller` | Edge samples and full stage invariants agree after process restart |
| R1-68 | READY | Validate that an interrupted restore that saves only some columns replays to the original captured hash. | `Isolated Minecraft restore interruption fixture` | Final independent reopened-world block states equal original fixture across every column |
| R1-69 | READY | Add a stage-bound certificate schema with explicitly UNKNOWN statuses instead of inferring skipped test evidence. | `Recovery evidence certificate parser and tests` | Missing receipts cannot be converted into inferred PASS |
| R1-70 | READY | Preserve first runtime failure in the hosted proof even if secondary artifact copying or shutdown also fails. | `hosted-minecraft-proof.py` | Failure verdict reports original stage/error and collects surviving evidence best-effort |
| R1-71 | READY | Audit saved-world identity and disposable-path guards for cloud clean and interrupted proof controllers. | `Hosted controller and preflight tests` | Test controller refuses unexpected or preexisting non-disposable world paths |
| R1-72 | READY | Ensure a completed interrupted proof can be repeated from a newly generated disposable world with immutable prior evidence. | `Hosted controller fresh-repeat mode` | Two distinct world identities and independent certificates; first artifacts unchanged |

**Dispatch fallback:** when a recovery-runtime job is occupying the test lane, take R1-59, R1-60, R1-61, R1-63, R1-64, R1-65, R1-69 or R1-70 without waiting; the live-runtime items remain available for a separate disposable run. Do not claim exact-head Minecraft proof from a core-only test. After each green focused PR, integrate and immediately pick another independent READY item if the scheduled coding session has capacity.

## Queue replenishment and execution contract

- Maintain **at least 12 independent READY code/test tasks** in addition to any world-dependent campaign gates. When READY falls below 12, expand the risk backlog before marking the next task complete: decompose the next unresolved release gate into distinct implementation, negative-case, recovery and exact-head-evidence tasks; assign new permanent IDs. Never recycle completed IDs or add vague filler.
- Never start a second mutation-capable test on the same world; if a runtime or release gate is blocked, continue with the next independent READY code task instead.
- Every development session must leave the **next actionable task**, exact repository commit, CI run/evidence reference, and first unresolved failure in this file or FAILURE_LEDGER.md. A green audit is not a code-development session.
- The hourly cloud audit detects low task inventory and identifies a next task; it does **not** generate or merge engineering code. Scheduled development sessions replenish the queue and implement code. No paid workers, tokens or services.
- Replenishing tasks must preserve the product safety contract and cannot automatically unfreeze 2/4/9/16-chunk or overnight campaigns before repeated clean/interrupted one-chunk proof.

## P2 — scale only after recovery exit gate

These remain BLOCKED until the one-chunk exit gate is repeatedly green.

| ID | Status | Work |
| --- | --- | --- |
| S2-01 | BLOCKED | Two chunks sequentially, one active owned chunk maximum. |
| S2-02 | BLOCKED | Four-chunk deterministic campaign. |
| S2-03 | BLOCKED | Nine-chunk campaign. |
| S2-04 | BLOCKED | Sixteen-chunk campaign. |
| S2-05 | BLOCKED | Reduced reproduction for any scale-only defect; do not mask with broad retry logic. |
| S2-06 | BLOCKED | Larger campaigns with checkpoint/resume. |
| S2-07 | BLOCKED | Performance profiling and allocation/hot-path work. |
| S2-08 | BLOCKED | Overnight 20k target campaign and restore certification. |

## Hourly operating loop

Each hourly invocation should:

1. Read this queue and `FAILURE_LEDGER.md`.
2. Inspect the default-branch hourly cloud checks and recovery-branch GitHub Actions / exact-head artifacts; do not rely on retired PR #3.
3. Update the highest-priority actionable item.
4. If a runtime job is executing, use that time on an independent READY audit/test item.
5. When a failure arrives, preserve its artifact/log, append the ledger, fix or reduce the cause, and continue.
6. Commit useful cohesive progress as soon as it is stable; do not leave the entire hour as one uncommitted experiment.
7. Before ending the invocation, ensure at least twelve independent READY code/test items remain or replenish the queue from concrete unresolved release risks. Record exact next task ID and CI evidence.

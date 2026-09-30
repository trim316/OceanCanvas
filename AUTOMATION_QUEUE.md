# Ocean Canvas Autonomous Work Queue

This is the durable ordered queue for hourly autonomous development. The queue must always contain enough independent READY work that one blocked runtime item cannot consume an entire hourly cycle.

## Two-chunk Minecraft integration handoff — 2026-09-30

### Block-entity recovery milestone — 2026-09-30

- **R1-108 merged:** PR #49 merged as `a63b59d207e6beb16d75b18ed97f314a93b26271`. Exact candidate `bcef2a03` built cleanly and disposable Minecraft run `36774594740` **PASS**: seed 4182033, one real `minecraft:chest`, inventory `minecraft:diamond x3`, independent state archive SHA `585c5b9d4a3ca481d05b2be9c3b3a72b7e6e7e47aa1cf2f244a57072ea88550d`, block-entity archive SHA `c108e3bfef581401f2ab06eb265608e23e6809327d42048b4745e26d0f5d8bc7`, terminal release receipt binds archived sidecar/envelope, cold reopen verifies immutable archives and restored inventory. Merged recovery core run `36775224731` PASS.
- **R1-93 live negatives dispatched:** run `36775317835` pins merged `a63b59d2` and independently proves (1) preexisting block entity without separate recovery authority is captured/preserved but authoring remains refused, and (2) a block entity introduced after state-only preimage capture is refused before unbacked overwrite.
- **R1-109 multi-type proof:** draft PR #50 `work/r1-109-blockentity-multitype-proof`, commit `876cece2`, adds a disposable seed 4182034 fixture containing a chest with diamonds plus a barrel with emeralds in one authorized chunk. Run `36775471351` must prove two-entry sidecar identity, exact terminal archive binding, both inventories after restore, and both again after cold reopen before merge.


### Next live validation and fail-closed source work

- Corrected R1-100 PR #37 negative test fixture to a different **still adjacent** target (32,33), commit `d084294b`, preserving all original archive/identity comparisons; exact-hosted runtime run `36765492840` dispatched. The preceding red run rejected non-adjacent (34,32) in admission and did not exercise immutable pair mismatch. Keep PR draft until explicit identity-mismatch proof PASS.
- New distinct-seed earlier interruption proof `36765624532` runs on merged recovery `8a74ca25`, seed `4182031`, deliberately kills second chunk after `PREIMAGE_CAPTURED` and requires first completed archive unchanged plus strict final cold restart. Do not inherit the existing `PHYSICAL_AUTHORED` proof from a different seed/stage.
- R1-101 draft PR #38 `work/r1-101-pair-orphan-canonical-refusal`, commit `cda8dc59`, hardens actual immutable plan source: if a valid canonical plan coexists with an orphan staged plan, refuse ambiguous operation authority while preserving both files. Adds deterministic refusal/idempotent-resume tests; focused core runs `36765755177` and `36765776445` dispatched. Do not merge until exact head is green; merging changes recovery source and needs its own runtime validation.


### September 30 subsequent gate outcomes

- **Second independent seed PASS:** Hosted two-chunk run `36764494760`, exact merged recovery source `8a74ca25`, disposable seed `4182031`: both full ten-stage journals, SHA-linked separate immutable archives, strictly sequential ticket ownership and final cold-restart proof PASS. This supplements the earlier seed `4182026` clean and `PHYSICAL_AUTHORED`-interrupted evidence; it does **not** prove wider 4/9/16-chunk scale or block-entity mutation support.
- **Fresh one-chunk regression PASS:** Hosted run `36764146200`, exact merged pair source `441d312e`, disposable seed `4182031`: ten transitions, eleven server sessions, ten verified restarts and independently verified archived preimage receipts. Recovery merged `8a74ca25` focused CI `36764460187` PASS.
- **R1-100 negative fixture correction:** Initial immutable-pair live refusal run `36764385581` compiled and passed 652 core checks, and refused mutation, but incorrectly redirected second chunk from (33,32) to nonadjacent (34,32). The adjacency preflight rejected it before immutable identity could be exercised; do **not** claim R1-100 PASS from that run. PR #37 head `d084294b` changes fixture to different still-adjacent (32,33) and matching exact confirmation. Replacement isolated run `36765492840` dispatched. Promote only after the specific immutable identity failure, unchanged original two archives and absent unauthorized third-chunk state are all verified.
- **Next product correctness:** after negative identity proof passes, integrate its harness, add missing real Minecraft block-entity codec/sidecar tests without lifting default block-entity refusal prematurely, and expand distinct-seed interrupted two-chunk coverage. Do not substitute additional orchestration/queue changes for mod implementation.


### Live proof promotions after R1-97 integration — 2026-09-30

- Merged R1-97 immutable pair PR #33, recovery commit `441d312e`, combined source CI run `36763896738` PASS. Clean real Minecraft two-chunk **exact combined** run `36764014746` PASS: seed 4182026, both ten-stage journals, two independently unchanged SHA-bound archives, sequential radius-zero ticket ownership and successful final cold restart.
- Merged R1-99 second-chunk interruption harness PR #36 as `8a74ca25`: exact test source `bd0e88ed`, Java/Fabric core CI `36763928930` PASS; live isolated interruption run `36763975511` **PASS**, killed the second server exactly after observed `PHYSICAL_AUTHORED`, restarted that world, verified first complete chunk immutable evidence unchanged, completed second chunk and cold-restarted both original archived preimages. Combined merged core run `36764460187` dispatched; inspect its exact verdict.
- Fresh baseline single-chunk seeded cloud regression `36764146200` dispatched against `441d312e`; distinguish it from previous one-chunk evidence before merging more changes.
- R1-100 negative real-world authority test drafted in PR #37 `work/r1-100-immutable-pair-live-refusal`, exact code `454a74b2`, cloud run `36764385581`. Test must first complete both originally consented chunks, then change only the second target to (34,32), require explicit durable plan identity refusal and prove neither archive/identity changed and no third chunk directory exists. Keep draft until exact-hosted runtime proof PASS and reconcile with latest recovery merge.
- Additional distinct-world clean two-chunk check dispatched on merged `8a74ca25`, seed `4182031`, run `36764494760`. A safe block-entity refusal is NOT a clean two-chunk PASS; preserve and inspect any first-failure artifact.
- Restriction: all these tests use free-tier GitHub-hosted **disposable** worlds. No personal Minecraft world access. No four-chunk/large-area promotion based on a single-seed or a pure-core PASS.


### Current combined recovery milestone — 2026-09-30

- **Merged R1-96 PR #32:** separate, independently consented two-chunk Minecraft runtime. Exact pre-merge runtime source `3d319e274f` passed hosted disposable clean two-chunk run `36762626971`: both chunks each have ten transitions and independently verified preimage/receipt SHA; the second ticket was acquired only after the first ticket release; cold restart verified both archives. Exact combined core CI on recovery merge `d5d1957`, run `36763578079`, PASS.
- **Merged preventive proof repair PR #35:** disposable one-chunk server cannot pause when unattended; branch exact-head CI `36762875879` PASS and merged `213b09ab`. Combined core CI `36763608402` PASS.
- **Merged R1-97 PR #33:** publish both exact targets and geometry in durable SHA-bound identity before either chunk opens. Current recovery combined commit `441d312e`, core CI `36763896738` PASS. This merged identity logic still requires its **own** exact-source real Minecraft run (not inherited from the older R1-96 pass).
- **Three isolated, exact-source live gates dispatched:** clean two-chunk on combined `441d312e` in run `36764014746`; deliberately kill second chunk after observing PHYSICAL_AUTHORED, then resume and cold reopen both archives on combined plus test harness `bd0e88ed` in run `36763975511` (draft PR #36); fresh one-chunk hosted regression on combined `441d312e` in run `36764146200`. These started on isolated GitHub-hosted runners; check current verdicts and preserved artifacts before any promotion.
- **Next mandatory actions:** inspect first-failure logs if any campaign fails and make a focused source/root-cause correction; if both two-chunk campaigns PASS, merge PR #36, revalidate the merged exact source, then add further interrupted stages/more-than-one-world evidence. Do **not** authorize four chunks, use personal world data, or describe a core-only PASS as end-to-end release evidence.


### Live two-chunk cloud failure and corrective rerun — 2026-09-30

- **Exact original proof failure:** disposable GitHub run `36758536535` at PR #32 commit `c5436766be` timed out after 30 minutes; preserved artifact `11118863062` includes both checked journals and first-session server log. The FIRST chunk (32,32) completed all ten transitions and archived its SHA-bound restored preimage before the second chunk began. The SECOND chunk (33,32) progressed through `VERIFIED`, but at 18:28:46 UTC vanilla Minecraft logged `Server empty for 60 seconds, pausing`, preventing tick-driven RESTORED/COMPLETE. This is a disposable test harness configuration failure, **not** a runtime recovery PASS or proof that second restore works. Do not increase test timeout or advance scale to paper over it.
- **Fix pushed:** PR #32 head `3d319e274f` sets `pause-when-empty-seconds=-1` **only** in the disposable test server's generated properties and explicitly fails if an unexpected empty-server pause appears. Its exact-source Java/Fabric run is `36762607709`; the `main` test dispatcher is pinned to exactly `3d319e274f` and has started replacement disposable test `36762626971`. Preserve its first-failure artifact if red; require two full ten-stage journals, independently verified backup/receipts, first-ticket release before second-ticket acquisition and a fresh two-chunk cold restart before PR #32 can merge.
- **Follow-up:** after the replacement proof is actually green, merge PR #32 into the current recovery branch, then rebase/retarget stacked PR #33 onto that **exact** merged commit, rerun focused and live proof and add deliberate two-chunk interruption. The old PR #33 focused green run `36758735967` does not validate this fixed runtime/harness combination.
- **Cloud audit:** independent queue parsing false-positive fixed and merged from PR #34 at `0288ca28`; prior scheduled run `36758427833` is historical failed evidence and is not a current audit verdict.


- **Verified predecessor:** combined recovery-core CI run `36757148136` PASS on merged coordinator + checked coordinate source `788f7ad`. The one-chunk vine fix and prior four-distinct-seed clean/crash acceptance remain pinned historical source evidence; they do not certify new two-chunk code.
- **R1-96 — guarded *real* Minecraft adapter:** DRAFT PR #32 `work/r1-96-guarded-twochunk-runtime`, exact head `c5436766be`, core Fabric/Java and cloud-controller syntax CI `36758396491` **PASS**. Requires independent file `oceancanvas-two-chunk-canary.properties`, explicit confirmation of BOTH adjacent in-bounds chunks, `mode=CORE_AUTHORING`, `expansionEnabled=true`, `singleChunkEnabled=false`, acceptance harness disabled. Missing/ambiguous consent is inert or refused. New `TwoChunkServerRuntime` wraps the already-validated `SequentialChunkCoordinator` with isolated per-chunk manifest/journal/receipts/preimage and at most ONE owned radius-zero ticket at a time. Unsupported block entities remain refused.
- **Runtime gate:** dedicated no-personal-device, GitHub-hosted disposable 2-chunk cloud proof `36758536535` is RUNNING and pinned to PR32 exact code `c5436766be`, seed `4182026`, first chunk (32,32), second (33,32). It requires both full ten-stage journals, separate unchanged immutable archived preimages with checksum-bound receipts, no second journal before first COMPLETE, first ticket release before second install, and a fresh Minecraft restart verifying BOTH completed original archives. If the second chunk correctly refuses an unsupported block entity, preserve negative safety evidence and choose a different confirmed disposable fixture; do not count refusal as an end-to-end two-chunk PASS.
- **R1-97 — immutable pair across restarts:** stacked DRAFT PR #33 `work/r1-97-durable-twochunk-plan`, exact head `56a55593ec`, focused core CI `36758735967` PASS; branches from PR32 and targets PR32 pending live proof. Before either chunk opens it publishes an exact SHA-bound `pair-operation.identity` covering BOTH chunk coordinates + geometry under an exclusive writer lease/atomic staged commit. Restart with a different second chunk or geometry fails closed and keeps prior evidence. Merge PR32 only after its live runtime test passes; then retarget/revalidate PR33 on current recovery branch, merge in order and rerun live proof on exact combined code. PR33's green pure tests do NOT certify the changed Minecraft adapter.
- **Next immediate work:** inspect run `36758536535` verdict and preserved artifact, fix its first meaningful real Minecraft failure if red, or integrate PR32 and PR33 sequentially if green. Then test deliberate two-chunk crash/restart scenarios before advancing to four chunks. Avoid additional CI churn, costs, personal world access or using a one-chunk result as two-chunk certification.

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

### Same-seed supported-vine restore reproduction

- Source `1568be4`, varied run `36752093145`, seed `4182029` PHYSICAL_AUTHORED: `RESTORED -> FAILED` on south-facing vine at (527,108,512), original backup index 4589; non-air south neighbor at (527,108,513), original backup index 9389. Independent unarchived backup artifact `11115650669` proves first-pass support ordering.
- Draft PR #26 `work/r1-68-vine-support-reapply` (head `52c71e5`) ports two-pass restorative reapplication onto current code. Focused core run `36753020206`; targeted same-world-seed, same-crash cloud run `36753142006`. Treat only a verified strict cold-restart PASS as evidence to merge; **do not merge older PR #23**.
- Seed `4182027` correctly refused a block entity pre-mutation. Seeds `4182028`, `4182030` passed their respective crash cases. Replacement independent clean seed `4182031` run `36752489508` must pass before declaring diverse clean evidence.
- After any PR #26 runtime PASS, integrate carefully, validate combined exact-head, then rerun independent clean+crash canary seeds to prove no regression. Full 20k and 2/4/9/16-chunk gates still BLOCKED.

## Latest development handoff — one-chunk diverse-seed recovery PASS

**Proved root correction:** PR #26 merged as `3f19335`: restoring the exact vanilla source snapshot with `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS` prevents intermediate shape updates from destroying inherited hanging vines. Retains two bounded passes, immutable preimage archive, ten checksummed transitions and strict cold-restart exact block-state equality. Original precise seed/crash reproduction `36754750971` passed with 10 verified restarts and archived preimage matching receipts. The preceding two-pass-only hypothesis *failed* and draft PR #23 must never merge.

**Repeatable cross-seed runtime evidence:** merged exact source `3f19335`, hosted run `36755564341`: **ALL FOUR separate disposable Minecraft worlds PASS**: clean seed `4182026`, PREIMAGE_CAPTURED forced crash seed `4182028`, PHYSICAL_AUTHORED forced crash seed `4182029` and RESTORED forced crash seed `4182030`. Combined source core CI `36755531488` PASS. This unlocks *preparation* for the next sequential **two-chunk canary**, not blanket release certification or unrestricted mutation. Seed `4182027` previously refused a block-entity-containing chunk before authoring, correctly preserving a known full-feature gap.

**Current code advancement:** R1-90 versioned immutable block-entity NBT format contract PR #28 merged `c2b8570`; R1-91 bounded checksummed atomic on-disk NBT sidecar PR #29 merged `f3e55b9`; neither enables Minecraft block-entity mutation, which remains fail-closed until actual codec, schema compatibility and interrupted cold-restart evidence. R1-94 fixed a genuine coordinator bug that previously closed its resident chunk adapter and released tickets on *every tick*: PR #30 merged `c95f679`, focused core CI `36756591332` PASS; combined core run `36756877161` started. R1-95 extreme-world-coordinate safe adjacent target selection PR #31 merged `788f7ad`, focused CI `36756809413` PASS; latest combined recovery core run `36757148136` validates it together with retained-session PR #30. Neither change enables destructive two-chunk gameplay yet.

**Next release work:** (1) confirm combined R1-94 CI and R1-95 focused CI; integrate green R1-95 safely. (2) Define an **explicitly confirmed disposable** two-chunk server adapter using existing conservative `SequentialChunkCoordinator`: one active chunk/ticket maximum, distinct per-chunk immutable manifest/journal/backup and exact identity, first chunk proven COMPLETE/released before second admission, durable restart-proof at each boundary, fail-closed if either chunk contains unsupported block entities. (3) Run two-chunk clean and interruption cloud evidence on more than one seed before considering four chunks. (4) Independently add actual Minecraft NBT capture and restoration for R1-92/93, preserving current refusal until it passes. No personal Modrinth world use, no paid infrastructure, no overnight 20k/large campaign prematurely.


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
| R1-33 | DONE (CORE) | Implement deterministic canonical preimage byte-level comparison without large temporary clones; test idempotent restart paths. PR #14 merged 4689675, combined core 36746059113 PASS; immutable compare without redundant cloning. |
| R1-34 | READY | Reject mutated block-state registry snapshots during preimage restore preflight before any writes; add negative-case integration tests. |
| R1-35 | READY | Prove entity-free refusal scans cover every saved vertical section, including high build-limit and below-sea-level blocks. |
| R1-36 | READY | Verify exact restoration of waterlogged vanilla block states and natural fluid propagation after save/reopen in a disposable test world. |
| R1-37 | READY | Verify changed biome/weather surface ice and snow do not create false restore PASS or destructive retry loops. |
| R1-38 | READY | Audit cross-chunk edge and corner light propagation after single-chunk restore without force-loading adjacent chunks. |
| R1-39 | READY | Test restart behavior between physical write completion and the first durable PHYSICAL_AUTHORED journal append. |
| R1-40 | READY | Test restart behavior between durable RESTORE_VERIFIED journal entry and final ticket release receipt. |
| R1-41 | MERGED / EXACT COMBINED CI | Validate backup/manifest/journal/receipt provenance agreement before resuming any destructive single-chunk stage. PR #21 merged cb8a6b3; normal COMPLETE reopen now validates archived evidence without the acceptance harness. |
| R1-42 | READY | Enforce a hard fail-closed policy for stale or unknown backup format versions and preserve the original bytes as evidence. |
| R1-43 | DONE (core CI only; runtime repetition still required) | Add bounded streaming CRC/journal read verification to avoid heap spikes from unexpectedly large corruption artifacts. |
| R1-44 | READY | Verify save/reload preserves heightmaps at the authored ocean floor and after exact preimage restoration. |
| R1-45 | READY | Instrument bounded block-write/physical-scan counters per stage for realistic performance regression baselines. |
| R1-46 | DONE (CORE) | Test persistent operation identity consistency across negative chunk coordinates and canvas boundary selections. PR #15 merged f2a343f; centered authority coordinates now fail closed on integer overflow. |
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
| R1-59 | DONE (CORE) | Audit operation-authority fail-closed behavior if a staged manifest exists alongside a valid canonical manifest. PR #12 merged 1fcd65f; retained orphan manifest evidence with valid canonical authority. | `OperationManifestStore and CoreSelfTest` | Identical canonical manifest remains authoritative without deleting unresolved staged evidence |
| R1-60 | READY | Test simulated disk-full exceptions independently at manifest, preimage, journal and receipt fsync boundaries. | `Durability stores and injectable channel/test hooks` | Every injected failure preserves prior canonical evidence and cannot grant false COMPLETE |
| R1-61 | READY | Add strict bounded decoding tests for zero-byte, invalid UTF-8 and extraneous-field journal records. | `CoreJournal and CoreSelfTest` | Malformed valid-CRC inputs fail without silent replacement or skipped records |
| R1-62 | READY | Add deterministic restoration preflight for state-ID references at the final preimage index. | `SingleChunkWorldPorts restore preflight and test fixture` | Invalid final index blocks every preceding restore write |
| R1-63 | DONE (CORE) | Prevent preimage temporary-file collision from erasing forensic evidence of a previous interrupted capture. PR #6 merged 98e9cdd; orphan preimage stage preserved. | `BlockStatePreimageStore and CoreSelfTest` | Existing orphan temp is preserved and a competing write is refused |
| R1-64 | READY | Prove persistent journal replay retains first failure evidence when a late stage fails on reopen. | `CoreJournal and SingleChunkPipeline tests` | Restart retains the exact first failing stage and refuses any later success fabrication |
| R1-65 | DONE (CORE) | Bound runtime receipt size and ensure oversized receipt files fail safely during verification. PR #7 merged afb7333; bounded forensic receipt growth and reads. | `RuntimeReceiptLog and CoreSelfTest` | Oversized or forged receipts fail before disproportionate allocation |
| R1-66 | READY | Verify no duplicate chunk mutation after restart when a physical stage returned success but journal append failed. | `SingleChunkPipeline and deterministic fault-injection adapter` | Idempotent retry converges without widening chunk authority |
| R1-67 | READY | Instrument read-only verification of canonical ocean block-state sampling at chunk borders after world reload. | `SingleChunkWorldPorts and hosted proof controller` | Edge samples and full stage invariants agree after process restart |
| R1-68 | DONE / RUNTIME-PROVED | Validate that an interrupted restore that saves only some columns replays to the original captured hash. PR #26 at 52c71e5 is current two-pass candidate ported to latest recovery source; focused CI run 36753020206 and same-seed PHYSICAL_AUTHORED live run 36753142006 are the gates. Archived source vine at index 4589 needed south neighbor from index 9389 on seed 4182029. DO NOT MERGE PR #26 until exact same-seed runtime cold restart PASS; old draft PR #23 superseded. Earlier random-world run 36748154376 had two release-blocking RESTORE->FAILED air substitutions. PR #20 diagnostic merged 05548b3. | `Isolated Minecraft restore interruption fixture` | Final independent reopened-world block states equal original fixture across every column |
| R1-69 | READY | Add a stage-bound certificate schema with explicitly UNKNOWN statuses instead of inferring skipped test evidence. | `Recovery evidence certificate parser and tests` | Missing receipts cannot be converted into inferred PASS |
| R1-70 | MERGED / EXACT COMBINED CI | Preserve first runtime failure in the hosted proof even if secondary artifact copying or shutdown also fails. PR #25 merged 1568be4; first-durable-failure cloud regression focused CI PASS 36751880565. | `hosted-minecraft-proof.py` | Failure verdict reports original stage/error and collects surviving evidence best-effort |
| R1-71 | READY | Audit saved-world identity and disposable-path guards for cloud clean and interrupted proof controllers. | `Hosted controller and preflight tests` | Test controller refuses unexpected or preexisting non-disposable world paths |
| R1-72 | READY | Ensure a completed interrupted proof can be repeated from a newly generated disposable world with immutable prior evidence. | `Hosted controller fresh-repeat mode` | Two distinct world identities and independent certificates; first artifacts unchanged |

**Dispatch fallback:** when a recovery-runtime job is occupying the test lane, take R1-59, R1-60, R1-61, R1-63, R1-64, R1-65, R1-69 or R1-70 without waiting; the live-runtime items remain available for a separate disposable run. Do not claim exact-head Minecraft proof from a core-only test. After each green focused PR, integrate and immediately pick another independent READY item if the scheduled coding session has capacity.

## P1E — independent release blocker implementation tranche

This additional pool is available **without** unlocking destructive scale. Match the Canvas modpack approach: one cohesive implementation and regression per focused branch, preserve separate CI evidence, integrate green exact-head PRs, and take the next independent task during the same coding session. Prefer older unresolved P0/P1 tasks if they block the one-chunk exit gate; use these as parallel capacity, not a replacement for recovery certification.

| ID | Status | Implementation task | Source or test surface | Acceptance criterion |
| --- | --- | --- | --- | --- |
| R1-73 | DONE (CORE) | Add bounded evidence receipt parser that rejects repeated keys with conflicting values instead of trusting last-write-wins. PR #11 merged 5f36866; duplicate manifest keys refused. | RuntimeReceiptLog and certificate parser tests | Conflicting operation, chunk or digest fields fail certification |
| R1-74 | READY | Prove that immutable preimage re-open never silently repairs a corrupt canonical backup from a surviving staging file. | BlockStatePreimageStore and CoreSelfTest | Original corrupt file and staged bytes preserved; destructive resume refused |
| R1-75 | READY | Reject a world-height/config change between durable preimage capture and physical authoring after restart. | OperationManifestStore and Minecraft adapter restart test | Changed bounds fail before any new block write |
| R1-76 | READY | Test state transition replay when forensic receipt disk is unavailable but the authoritative journal remains intact. | SingleChunkPipeline and receipt fault tests | Journal authority remains truthful and diagnostic failure remains visible |
| R1-77 | READY | Add a deterministic fixture for interrupted world save during restore verification without terminal status promotion. | Restore verification adapter and fault-injected acceptance fixture | Incomplete save never yields COMPLETE or final certificate |
| R1-78 | READY | Prove runtime preimage capture refuses a newly materialized block entity discovered late in the vertical scan. | SingleChunkWorldPorts and disposable world fixture | Admission failure retains zero authoring writes and captures refusal location |
| R1-79 | READY | Validate atomic backup staging on same-filesystem and unsupported-atomic-move paths with injectable file operations. | BlockStatePreimageStore and filesystem contract tests | Unsupported atomic move leaves canonical backup unchanged |
| R1-80 | READY | Add checksum-bound operation context to first-failure diagnostics without persisting mutable world paths in receipts. | RuntimeReceiptLog and diagnostic tests | Fault log reproducibly identifies stage, chunk, operation and source candidate |
| R1-81 | DONE (CORE) | Guard against integer overflow while computing per-column scan offsets for extreme but supported world build bounds. PR #13 merged 72aecb4; checked scan geometry before mutation. | SingleChunkWorldPorts and pure geometry tests | Invalid heights fail before array allocation or mutation |
| R1-82 | READY | Verify restarting during chunk residency retry cannot reissue a wider ticket than radius zero. | ResidencyReacquirePolicy and mock ticket lifecycle tests | At most one owned radius-zero ticket across every retry and close path |
| R1-83 | EXACT-HEAD CORE PASS / MERGE PENDING | Audit the core pipeline failure transition for repeated exception dispatch and ensure failed stages remain terminal after reopen. | SingleChunkPipeline and CoreJournal failure replay tests | No new stage action or false restart PASS after durable FAILED |
| R1-84 | READY | Add a bounded independent core test for SHA-256 certificate continuity over immutable preimage files without loading them repeatedly. | Preimage digest helper and CoreSelfTest | Deterministic digest equality; bounded memory and mutation refusal |
| R1-85 | DONE (CORE) | Test receipt and journal Unicode handling with malformed UTF-8 byte sequences that carry recomputed CRCs. PR #10 merged 30140ca; strict receipt UTF-8 provenance. | CoreJournal, RuntimeReceiptLog and negative-case tests | Decoder refuses ambiguous input; intact UTF-8 remains round-trippable |
| R1-86 | READY | Create a non-destructive server-start guard that refuses a mismatch between operation manifest and current confirmed target chunk. | OperationManifestStore and Minecraft entrypoint test | Mismatch detected before chunk residency or authoring |
| R1-87 | READY | Capture per-stage durable resource counters for one-chunk campaigns without introducing hot-path per-block allocations. | SingleChunkWorldPorts and deterministic accounting tests | Bounded counters survive reporting and do not change operation ordering |
| R1-88 | READY | Prove recovery journal archival maintains independent immutable evidence when two disposable-world campaigns use identical chunk coordinates. | Hosted proof controller and isolated fixture | Distinct world identities retain separate attested artifacts |

**Immediate branch dispatch candidates:** R1-73 (receipt evidence), R1-74 (backup immutability), R1-81 (range overflow), R1-83 (terminal failure replay), and R1-85 (UTF-8 corruption). Avoid duplicating a branch with equivalent live work; review current PRs before claiming a task. The cloud audits detect depletion, but coding sessions must implement the work; no paid background workers.

## P1F — newly surfaced full-feature recovery blocker

| ID | Status | Implementation task | Evidence gate |
| --- | --- | --- | --- |
| R1-89 | MERGED / COMBINED CORE GREEN | Exclusive immutable preimage publication writer lease, PR #22 merged b7db889; exact combined core run `36751316872` PASS. | A conflicting capture never replaces the original backup or overwrites staged evidence. |
| R1-90 | DONE (PURE CORE) | Define a versioned operation-bound, integrity-checked block-entity NBT backup contract that preserves existing schema-2 block-state-only backups without unsafe migration. | Unit tests for exact BE identity, NBT size bounds, corrupt/unknown schema refusal, and original backup immutability. Do not enable mutation from design alone. |
| R1-91 | DONE (PURE CORE) | Capture all supported resident vanilla block entities with their exact state identity and bounded NBT, preserving no-entity snapshots. | Independent preimage reopen and fail-injected capture prove durable NBT without losing canonical backup. |
| R1-92 | DONE (LIVE MINECRAFT) | Restore captured BE states and NBT at the correct lifecycle point, then re-open and verify type and canonical NBT. | PR #49 merged as `a63b59d2`; exact-source run `36774594740` PASS on disposable seed 4182033. Real `minecraft:chest` with 3 diamonds survived authoring, exact restore, SHA-bound state/sidecar archival, terminal release receipts, and cold reopen. |
| R1-93 | IN PROGRESS | Add isolated Minecraft block-entity/structure edge cases, no-implicit-world-authority regression, and schema upgrade tests. | Merged R1-108 core is green (`36775224731`). Exact merged-source edge-refusal run `36775317835` is active for preexisting-without-consent and late-after-preimage cases. Draft PR #50 / run `36775471351` adds live multi-type chest+barrel recovery and cold reopen. Never silently skip unsupported entities. |

**Priority reminder:** Do not delay resolving the observed `RESTORED -> FAILED` source-state changes for speculative NBT implementation. The seed-4182027 refusal is *successful fail-closed behavior*, not permission to weaken the guard. The experimental two-pass restoration PR #23 remains draft until live seed-specific evidence justifies adoption.

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

## 2026-09-30 scheduled-session handoff (bounded journal and terminal replay)

- **Integrated product/core R1-43:** PR #9 merged as recovery/core-proof b29d412d556d62331e72b58ccae4f72e2d4c6715. Streams CRC-checked journal records under total/per-record bounds; refuses oversize appends and detects file-size changes during replay. Exact combined-head hosted CI 36734328327 **PASS** (Java 25 core + Windows preflight). This is core evidence, **not** a new isolated Minecraft runtime certificate.
- **R1-83 revalidated on current head:** existing PR #8 was advanced without duplicating it to work/r1-83-terminal-failure-replay 89e475a4f00abeec6a022eb4d424f88b09a92c8d (merged ancestry includes b29d412 and prior branch evidence). Exact-head hosted run 36734513644 **PASS** at exact head (core + Windows preflight). Changes touch only CoreSelfTest; recovery HEAD moved afterward only through queue/ledger docs. Ready-for-review was set, but the attempted merge was denied by the execution safety layer. Recheck tip/code divergence and merge through the normal protected path when available; then verify combined HEAD.
- **Isolated real-world matrix:** latest available hosted disposable-world run 36724539629 PASS for main's pinned ac440d3 test orchestrator and **exact checked-out recovery SHA e2c0adfad56971d6785e9f55b43b8e912f10d224** (verified in job logs for both clean and PHYSICAL_AUTHORED cases). Its clean and interruption artifacts remain useful historical evidence but **cannot certify b29d412 or subsequent source changes**. Self-hosted personal Modrinth/game profile was not touched.
- **READY next:** R1-84 bounded streaming preimage file digest or R1-33 no-clone canonical preimage replay, on a focused work branch from the latest green recovery SHA. Attempts to persist those independent source changes were blocked by execution safety checks; do not claim implementation. If blocked again, take R1-73 or another non-overlapping READY test/core task. Preserve at least twelve READY cards from P1B/P1C/P1E.
- **No scale promotion:** repeated clean and deliberately interrupted one-chunk exact-source runtime proofs remain a release gate. Cloud-only CI push builds cannot substitute for it.

**Safety/write boundary at this handoff:** CI did not block R1-33 or R1-84; the repository tool execution safety layer denied the attempted source writes for these two independently scoped preimage hardening tranches. Do not represent them as committed, and do not reroute writes through an unsafe workaround. R1-43 source merge and queue/ledger writes were allowed. Existing work/r1-84 branch, if still present, was created before the denied write and has no certified R1-84 source commit.

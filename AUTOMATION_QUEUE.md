# Ocean Canvas Autonomous Work Queue

This is the durable ordered queue for hourly autonomous development. The queue must always contain enough independent READY work that one blocked runtime item cannot consume an entire hourly cycle.

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
| R0-01 | IN_PROGRESS | Prove recovery.3 one-chunk lifecycle on the self-hosted Windows/Minecraft runner. Launcher world identity is fixed; the September 30 full run reached COMPLETE and final restart, but a session-local evidence check rejected stages already recorded by prior runs. A corrected operation-wide checksummed journal verifier is now under live Windows proof. Do not erase prior completed state before it is attested. |
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
| R1-01 | READY | Audit acceptance-state and journal crash boundaries for write ordering and torn-write behavior; add corruption tests where missing. |
| R1-02 | READY | Audit preimage serialization bounds/size limits and malformed-input rejection. |
| R1-03 | READY | Audit block-state restore behavior for fluid states, scheduled ticks, heightmaps, and lighting invalidation; identify any semantic gap before scale. |
| R1-04 | READY | Audit block-entity refusal path and ensure refusal occurs before first mutation with durable diagnostic evidence. |
| R1-05 | READY | Audit ticket lifecycle: acquire, resident proof, retry grace, restart reacquire, release, and failure cleanup remain radius-0 and one-chunk only. |
| R1-06 | READY | Add runtime evidence snapshot at each acceptance hold so a failed later stage does not erase earlier proven stages. |
| R1-07 | READY | Make recovery evidence capture best-effort/non-destructive: one copy failure must not suppress the rest of the evidence bundle. |
| R1-08 | READY | Add a machine-readable failure-classification receipt containing stage, first failure signature, runtime identity, and whether world mutation had begun. |
| R1-09 | READY | Ensure completed old recovery attempts are archived with immutable identity instead of deleted when starting a new clean repetition. |
| R1-10 | DONE | The filtered recovery branch push workflow is the authoritative automatic runtime proof; prior PR trigger is retired. |

| R1-11 | READY | Stop cancelling a live self-hosted world proof on subsequent source commits: serialize newer runs behind active proof, then deduplicate obsolete candidates safely before launch. |
| R1-12 | READY | Require at least ten genuine acceptance restarts and eleven distinct server opens before certifying complete proof; independently validate checksummed journal across prior invocations. |
| R1-13 | READY | Implement a non-destructive, explicit fresh-repeat mode that archives completed receipts and preimage provenance before resetting harness state. |

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
2. Inspect the latest PR #3 / GitHub Actions / runtime artifact state.
3. Update the highest-priority actionable item.
4. If a runtime job is executing, use that time on an independent READY audit/test item.
5. When a failure arrives, preserve its artifact/log, append the ledger, fix or reduce the cause, and continue.
6. Commit useful cohesive progress as soon as it is stable; do not leave the entire hour as one uncommitted experiment.
7. Before ending the invocation, ensure at least ten READY items remain or replenish the queue.

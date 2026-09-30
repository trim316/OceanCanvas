# Ocean Canvas Recovery Program

This branch exists to restore forward engineering progress by proving the smallest correct Ocean Canvas runtime before scaling it.

## Standing development rule

Ocean Canvas correctness comes first. Runtime validation exists to prove correctness. Automation exists to support validation. A problem in automation must not silently redefine the mod, and a problem in the mod must not be hidden by increasingly elaborate retry/orchestration behavior.

Infrastructure work is not counted as equivalent product progress.

## Branch roles

- `main`: integration/evidence history. Do not use it as the place to discover basic runtime correctness.
- `recovery/core-proof`: active recovery branch. Only changes that improve or prove the one-chunk core belong here until the exit gate below passes.
- Scale work (`G2/G4/G9/G16`, larger campaigns, overnight 20k runs) is frozen on this branch until the one-chunk exit gate passes repeatedly.

## Required one-chunk proof

One explicitly confirmed sacrificial chunk must pass, in order:

`load -> author -> settle -> persist -> lighting -> verify -> release -> restart -> verify persisted truth -> restore`

The proof must demonstrate:

1. Exact destructive authority: only the explicitly confirmed chunk can mutate.
2. One active chunk maximum.
3. Durable stage journal with corruption detection.
4. Idempotent replay after interruption at every durable boundary.
5. Residency/ticket acquisition has deterministic bounded recovery and terminal failure.
6. Physical authoring converges to the configured canonical ocean profile.
7. Physical settling/reconciliation converges without uncontrolled widening.
8. Save completion is durable before later stages receive credit.
9. Lighting settles and is verified from authoritative server state.
10. Final verification survives a real process/world restart.
11. Restore returns the chunk to its pre-authoring state from captured provenance.
12. Every PASS is bound to the exact runtime identity and evidence; no retry can manufacture PASS.

## Progression rule

Expansion is sequential:

`1 chunk repeated -> 2 -> 4 -> 9 -> 16 -> larger campaigns -> performance -> overnight 20k target`

A scale level may begin only after the preceding level has repeatable clean-start and restart evidence.

If a scale level fails twice for materially different reasons, expansion stops. The failing subsystem is reduced to the smallest reproducible case and fixed there before scale resumes.

## Failure policy

Do not classify an Ocean Canvas runtime failure as transient merely because a retry later succeeds.

Retries are appropriate only for understood infrastructure noise. Runtime failures must retain the first failing evidence and be reduced to a deterministic case whenever possible.

Unknown failures fail closed.

## Development reporting

Every development update must distinguish:

- **Product correctness progress**: behavior of the Ocean Canvas mod itself.
- **Runtime proof progress**: evidence that the behavior works in Minecraft.
- **Automation/release progress**: CI, runner, packaging, checkpointing, or orchestration.

Do not present automation work as equivalent progress toward the mod's functional goals.

## Current recovery focus

Chunk residency recovery now lives in deterministic core logic (`ResidencyReacquirePolicy`) with self-tests. The one-chunk lifecycle now also requires a durable checksum-verified block-state preimage before authoring, followed by restore and restore verification before `COMPLETE`. Preimage capture fails closed if a block entity is present, rather than claiming an incomplete backup is restorable.

Next priority: exercise this ten-transition lifecycle in Minecraft with real restart holds at every durable boundary and confirm the restored chunk survives the final restart.

## Exit gate

This recovery phase is complete only when the same one-chunk scenario passes repeatedly from clean start and interrupted/restart states, including restore, without changing the harness between repetitions to make the test pass.

Only then may scale gates be re-enabled.

## Active runtime proof

Draft PR #3 is the active recovery proof surface. It must remain unmerged until the one-chunk Minecraft workflow produces a PASS artifact for `26.2-core-v0.2.26-recovery.3`.
Default-branch recovery workflow bootstrap is present on `main`; PR #3 is the active executable proof surface rather than a documentation-only checkpoint.

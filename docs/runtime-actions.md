# Ocean Canvas GitHub runtime dashboard

GitHub Actions is the authoritative control plane for Ocean Canvas runtime validation.

The current release candidate is `26.2-core-v0.2.26`. Every source-changing run builds an immutable candidate on a GitHub-hosted Linux runner, validates the Windows control plane on a GitHub-hosted Windows runner, then installs and exercises that exact candidate on the self-hosted Windows Minecraft machine.

The self-hosted runner uses the durable Ocean Canvas runtime workspace under:

`%LOCALAPPDATA%\OceanCanvas\AutonomousSupervisor\workspace`

The proven runtime progression remains intentionally serial and fail-closed:

`G2 PASS -> G4 PASS -> G9 PASS -> G16 PASS -> PROVEN`

Each runtime chunk must still reach `COMPLETE`, prove `finalRestartVerified=true`, and complete all required restart verifications before the next chunk is admitted. Maximum active chunks remains 1.

Disposable Minecraft proofs have reached the sixteen-chunk gate. v0.2.26 also contains a separately armed 5x5–16x16 bounded campaign lane; that lane is inert without exact destructive consent and is not represented as runtime-proven merely because the code builds.

## Resumable validation

Runtime validation is split into bounded slices. Durable gate state and per-chunk PASS evidence are reused between slices so a timeout, launcher recovery, or known transient residency race does not discard already-proven work.

The installed permanent controller is hardened idempotently for chunk-level checkpoint resume, including a verified schema-1 to schema-2 migration path. A stale or partially patched controller now fails closed instead of being accepted merely because the old marker exists.

After every slice, GitHub captures a checkpoint artifact containing the gate ledger, runtime evidence, current single-chunk campaign state, controller log, and latest Minecraft log. Checkpoint schema 2 adds:

- explicit source-stability and world-lock state;
- SHA-256 hashes for every captured file in `CHECKPOINT-MANIFEST.sha256`;
- current chunk, lifecycle stage, verified restart count, and completed-gate summary.

Checkpoint artifacts are evidence and recovery material; they do not override the durable gate ledger or convert an incomplete chunk into PASS.

## Ownership and recovery

GitHub Actions is the only intended runtime owner. Before launching a new attempt, the bridge retires obsolete local supervisor/controller processes, verifies or recovers the Minecraft world lock, and preserves already-completed gate credit.

Known recoverable infrastructure failures are classified explicitly. Unknown failures stop fail-closed and retain a postable log plus runtime evidence rather than resetting progress or forcing unsafe world access.

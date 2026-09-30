# Ocean Canvas Recovery Failure Ledger

Failures are retained as engineering evidence. A failed attempt is not "scrapped" if it established a useful fact, preserved a safe state, or disproved a hypothesis.

| Date | Run / Artifact | Classification | Failure | What was preserved / learned | Disposition |
| --- | --- | --- | --- | --- | --- |
| 2026-09-29 | run 36659417524 | Safety / prior-state discovery | Existing one-chunk state belonged to chunk 3,0 while new proof requested 32,32. | Safety guard prevented overwriting unknown interrupted state. Established that prior runtime state must be classified before starting a new operation. | Runner changed to diagnose and archive only proven pre-mutation leftovers; mutated/unknown states still fail closed. |
| 2026-09-29 | run 36659658218; artifact 11074460580 | Launcher integration | Minecraft PID and fresh log appeared, but the world never opened. Preserved controller showed Modrinth deep link used an empty world name. | Artifact preserved prior 3,0 state, diagnostic, temporary controller, transcript, and verdict. Diagnostic proved 3,0 was only LOADED with no authoring/persist/restore receipt and no preimage, so it was a pre-mutation leftover and safe to archive. Root cause of launch failure: controller global WorldName defaulted to empty because recovery runner invoked controller without -WorldName. | Fixed runner to pass explicit -ProfilePath and -WorldName on launch and close. Next run validates this hypothesis. |

## Failure classes

- **A — product correctness:** Ocean Canvas changes or verifies the world incorrectly. Release-blocking; reduce and fix immediately.
- **B — runtime integration:** Minecraft/Fabric/ticket/save/light/restart integration prevents the correct core behavior from executing. Release-blocking; preserve evidence and fix/reduce.
- **C — evidence/observability:** behavior may be correct but proof collection is incomplete or fragile. Preserve runtime state; repair evidence without invalidating proven product work.
- **D — infrastructure/environment:** runner, launcher, GitHub, filesystem, network, or tooling issue not caused by Ocean Canvas semantics. Do not let it consume the entire hourly cycle; retain diagnostics and continue independent work.

Every entry should identify the first failure, not only the final wrapper exception.

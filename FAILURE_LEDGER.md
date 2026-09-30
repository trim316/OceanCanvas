# Ocean Canvas Recovery Failure Ledger

Failures are retained as engineering evidence. A failed attempt is not "scrapped" if it established a useful fact, preserved a safe state, or disproved a hypothesis.

| Date | Run / Artifact | Classification | Failure | What was preserved / learned | Disposition |
| --- | --- | --- | --- | --- | --- |
| 2026-09-29 | run 36659417524 | Safety / prior-state discovery | Existing one-chunk state belonged to chunk 3,0 while new proof requested 32,32. | Safety guard prevented overwriting unknown interrupted state. Established that prior runtime state must be classified before starting a new operation. | Runner changed to diagnose and archive only proven pre-mutation leftovers; mutated/unknown states still fail closed. |
| 2026-09-29 | run 36659658218; artifact 11074460580 | Launcher integration | Minecraft PID and fresh log appeared, but the world never opened. Preserved controller showed Modrinth deep link used an empty world name. | Artifact preserved prior 3,0 state, diagnostic, temporary controller, transcript, and verdict. Diagnostic proved 3,0 was only LOADED with no authoring/persist/restore receipt and no preimage, so it was a pre-mutation leftover and safe to archive. Root cause of launch failure: controller global WorldName defaulted to empty because recovery runner invoked controller without -WorldName. | Fixed runner to pass explicit -ProfilePath and -WorldName on launch and close. Next run validates this hypothesis. |

| 2026-09-29 | run 36660779507; artifact 11074448340 | Runtime acceptance / stale-hold detection | Minecraft repeatedly launched and closed but durable stage stayed LOADED for all 14 sessions. | Proved launcher/world-open path worked and clean closes were normally reliable. Showed persisted acceptance hold could be mistaken for fresh progress; no mutation had begun. | Runner now requires sessionsOpened/restart counters to advance and rejects stale persisted holds instead of cycling them. |
| 2026-09-29 | run 36661574317; artifact 11074941337 | Runtime close integration / FastQuit | Recovery advanced through PREIMAGE_CAPTURED to PHYSICAL_AUTHORED, then first graceful close saved/unloaded the world but Minecraft remained at the title screen for 150 seconds. | Preserved exact preimage SHA-256, PHYSICAL_AUTHORED journal state, runtime receipts, latest.log, and controller trace. latest.log proves integrated server/chunks saved successfully before the client remained alive. This is resumable, not discarded. | Recovery close changed to two-phase graceful shutdown: verify world lock/save release first, then send a second normal window-close to the title-screen client; still never force-kill an active/saving world. |

| 2026-09-30 | run 36662388258; artifact 11074319054 | D — Windows controller | Recovery close attempted `$pid=...` inside an embedded PowerShell here-string. `$PID` is a case-insensitive read-only automatic variable, so the first stale-client close aborted before Minecraft reopened. | Hosted core build and preflight passed; existing world state remained at `PHYSICAL_AUTHORED`, with earlier preimage/journal/receipts preserved. Failure proves controller preflight had parsed outer script but not validated executable embedded close-body semantics. | Replaced all `$pid` references in embedded close body with `$targetProcessId`; added Windows preflight regression check for reserved PID references. Validate on new exact-head run; preserve interrupted world state. |

| 2026-09-30 | run 36712751351; artifact 11095611744 | C — resumable proof accounting | Actual Minecraft operation reached COMPLETE and survived final world restart, but final runner check wrongly required the latest runner process to observe LOADED and all earlier stages already recorded by previous runs. | Preserved complete checksummed ten-transition journal, runtime receipts, final restart evidence, and prior preimage SHA provenance. Corrected operation-wide journal verifier passed in run 36714701694; Windows runtime, hosted builds, and preflight all green. | Keep this proof as a resumed-operation milestone only. Require 10 recorded restarts/11 opens, then start independent clean-repeat and deliberate-interruption campaigns. |

| 2026-09-30 | work/r1-43 9a666f6; run 36733791155 | D — development/source assembly | Initial focused streaming-parser change generated Java character literals with doubled escapes and failed javac on lines 73/76/86. | Original failed CI logs retained; no real Minecraft proof ran and no world state was touched. Exact source fix at 4c890726 corrected newline/CR/tab literals and restored an actual Unicode fixture. | Follow-up exact-head hosted run 36733904318 PASS; merged R1-43 as b29d412, combined core/preflight run 36734328327 PASS. |

## Verified recovery milestones

- **36714701694**: recovered an already-COMPLETE operation from its durable journal; hosted checks and Windows runtime passed. Proof is resumed-operation evidence, not a clean-start repetition.
- **36715033822**: strengthened acceptance required all ten checksummed transitions and at least ten verified restarts/eleven server opens; hosted and Windows runtime checks passed.
- **36715225534**: preimage byte-count overflow regression, hosted build/self-tests, and Windows runtime proof passed without cancelling the previous active proof.
- A fresh clean-start one-chunk repetition and fault-injection recovery tests remain unproven and retain priority.

## Failure classes

- **A — product correctness:** Ocean Canvas changes or verifies the world incorrectly. Release-blocking; reduce and fix immediately.
- **B — runtime integration:** Minecraft/Fabric/ticket/save/light/restart integration prevents the correct core behavior from executing. Release-blocking; preserve evidence and fix/reduce.
- **C — evidence/observability:** behavior may be correct but proof collection is incomplete or fragile. Preserve runtime state; repair evidence without invalidating proven product work.
- **D — infrastructure/environment:** runner, launcher, GitHub, filesystem, network, or tooling issue not caused by Ocean Canvas semantics. Do not let it consume the entire hourly cycle; retain diagnostics and continue independent work.

Every entry should identify the first failure, not only the final wrapper exception.

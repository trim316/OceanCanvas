# Production test lanes

Routine changes run candidate/recovery checks alongside the build. After the
immutable candidate is available, two seed tests, two focused 128-block lighting
reproductions, and a 1,000-block profiling run can execute concurrently. Each
runner owns a separate disposable world; no local Modrinth world is involved.

The profile artifact contains `profile.jfr`, `profile-summary.txt`, `gc.log`, full
console output, acceptance, and restart evidence. Open the recording in Java
Mission Control or inspect it with `jfr print --events jdk.ExecutionSample` to
attribute CPU work. Compare GC pauses and lighting backlog before changing engine
limits. A focused reproduction passing does not prove the old full-world fault
cannot recur: these are faster diagnostic cases, not replacements for certification.

Pinned Fabric/Minecraft setup files and downloads are cached, with local digest
checks before reuse. Worlds and candidate mods never enter that cache. Gradle
dependencies are cached separately by setup-java.

## Endurance checkpoints

Run **Ocean Canvas Resumable Endurance Diagnostic** manually. Supply the build run
ID containing `oceancanvas-production-candidate`, its source commit, runtime build,
and width. Leave checkpoint run ID blank for a fresh world. Each segment runs for
up to 30 minutes, saves every five minutes, and shuts down gracefully. A segment
that does not complete remains failed/incomplete, not certified.

To continue, use the previous diagnostic run ID as checkpoint run ID with the
same candidate and width. The artifact restores the world and original logs.
The mod's durable job must report an actual restart resume; the harness never
issues another start over that job. JAR, source, seed, profile, floor, Minecraft,
loader, and dimensions must all match. Changed candidates require a fresh world.

Checkpoints are retained for 14 days and require enough runner disk/artifact
capacity. A hard runner termination may prevent the final upload; the five-minute
saves live on that runner, not on remote persistent storage. This is resumable
diagnostic tooling, not an automatic claim of restart certification or 20k speed.
Completion still requires the original structured terrain/lighting/integrity
audit. This workflow does not publish a release. Fully completed checkpoints
should be audited rather than restarted as active jobs.

The 500-block limited release still requires its scoped checks. Larger scopes
remain experimental until their separate certification succeeds.

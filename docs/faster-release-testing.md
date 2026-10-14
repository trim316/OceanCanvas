# Faster release testing

Build one immutable candidate, then run the baseline, two other seeds, six focused
runtime cases and interruption/resume proof concurrently. Focused tests do not
wait for a 500-block world or for unrelated fixture checks. Matrices use
`fail-fast: false`: one failing case does not discard another case's evidence.
Each Minecraft process gets an isolated GitHub runner, not competing JVMs on one
machine. Actual concurrency remains limited by available account runner slots.

After the baseline passes, fresh 5k and fresh 20k acceptance run concurrently.
Both must pass before evidence admission; publication also joins all independent
fixtures, seeds, focused cases and checkpoint proof. Expected scale wall time
becomes approximately `baseline + max(5k, 20k)`, rather than
`baseline + 5k + 20k`. This changes scheduling, not any completion limit or oracle.

## Keep useful work

* Use **Test Existing Candidate** to run only selected cases against an existing
  `oceancanvas-production-candidate` artifact. It uses the current harness with
  the pinned source/build/JAR, skips compilation, runs cases concurrently, and
  never publishes. This also lets a harness fix be tested without rebuilding
  the product or cancelling an older campaign.
* Retry an infrastructure failure with GitHub's **Re-run failed jobs**, on the
  same workflow run. Successful jobs and the original candidate artifact remain
  available; do not select **Re-run all jobs** unless the inputs changed.
* Use **Resumable Endurance Diagnostic** with the original `candidate_run_id`,
  source SHA, build, width and `checkpoint_run_id`. Checksummed stopped worlds
  retain their durable job and original UTC start time. Pauses still count
  against the existing eight-hour limit. A checkpoint is not a completed proof.
* Its `5000+20000` width starts both bounded campaigns together. On the next
  segment, provide the previous run ID to resume each matching artifact.
* Push workflow-only experiments on a separate branch. Do not cancel an active
  candidate just because its orchestration is being improved. Dispatch campaigns
  have separate concurrency groups. Runtime dependency caches contain only
  verified immutable libraries; they never silently supply a generated world.

## Learn from one test while another continues

Both runtime harnesses save `replay-manifest.json` after a clean server stop,
including a checksum inventory of world and configuration. Their evidence can
be consumed by **Parallel Saved-World Replays**. Give it the parent run/artifact
and a JSON array of candidates:

```json
[
  {"name":"control","run":"123","source":"<exact 40-character source SHA>","build":"v253.125.80"},
  {"name":"fix","run":"456","source":"<exact 40-character source SHA>","build":"v253.125.80"}
]
```

Every candidate gets a separate verified copy of the identical parent world.
The original stays intact. The replay opens the saved durable job; it does not
start a replacement operation. Compare `replay-result.json`, logs, native
diagnostics and JFR profiles. Replays produce another clean snapshot for the
next experiment, so a fix can be investigated immediately while an older
candidate's full tests keep running. Large-world artifacts retain world/config
for 14 days; download important evidence before expiry.

Cross-candidate replay always records `freshWorld=false`, `releaseVerdict=HOLD`
and both parent and target identities. It cannot replace a fresh-world test:
new generation code may behave differently on terrain that already exists.
An unrelated workflow edit can keep using the pinned candidate's existing
results; a changed JAR needs new affected runtime proof. Re-run focused failure
cases first, then let that candidate's complete release checks finish together.
Do not swap binaries underneath a running JVM or relabel old evidence.

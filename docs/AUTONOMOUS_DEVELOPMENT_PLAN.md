# Unattended development: deployment and trust contract

Status: architecture documented; **persistent autonomous coding is NOT yet deployed**. Existing scheduled cloud workflow only audits and runs deterministic Java tests. Do not mislabel green tests as new development.

## Required one-time configuration

The repository owner must authorize a coding provider and place its credential in **Settings > Secrets and variables > Actions** as `OPENAI_API_KEY`. Do not commit credentials or paste them into chat. Set a budget/rate limit at the API provider; scheduled code generation incurs API charges. Grant GitHub Actions permission to create pull requests only after reviewing the isolated publish workflow.

## Intended scheduler

A scheduled/default-branch workflow should dispatch at a bounded interval and optionally via manual dispatch. Its selector reads `recovery/core-proof:AUTOMATION_QUEUE.md`, selects the first independent READY code-only task, and skips a task with an existing open agent PR. Cap at two simultaneous pending agent PRs; stop selection rather than producing duplicate or unsafe work. Never automatically pick destructive Minecraft campaigns, world resets, or scale expansion.

## Restricted authoring and publication

Run the official Codex GitHub Action on an ephemeral GitHub-hosted Linux runner with `permission-profile: ":workspace"`, `safety-strategy: drop-sudo`, and repository contents **read-only token permissions**. Run the agent only after trusted dependencies are fetched. The agent may edit checked-out source and tests, but must not receive a GitHub write token or a user's game profile. It leaves a patch plus concise result/evidence artifact.

A separate **uncredentialed** publication job checks out the exact selected recovery commit, validates patch paths (only reviewed Java implementation/test scope), rejects symlinks, workflow/secret edits, path escapes, and unexpectedly large patches, applies the patch, runs deterministic Java 25 build/self-tests, and only then pushes a unique branch and opens a PR targeting `recovery/core-proof`. Save patch and logs when compilation or publication fails. No automatic merge. New push builds must not trigger a self-hosted Minecraft session.

## Progress and failure behavior

An independent scheduled cloud audit continues whether or not an agent credential is configured, but its PASS is not coding progress. Keep one append-only attempt receipt per agent iteration with task ID, selected source SHA, proposed diff SHA, build status, PR or blocking condition. Failures must be preserved and the next independent READY item considered by the *next* safe iteration; never erase a failed attempt to claim green. A watchdog should detect disabled schedules, an absent credential, permission denial, repeated identical failures, and a queue of READY tasks with no code progress. The watchdog alone does not restart a coding model.

## Proof gates

Resumed one-chunk restore proof is not independent clean-start or interruption proof. Keep self-hosted runtime tests explicitly gated to a demonstrably disposable, isolated profile; never interfere with the user's current local Minecraft or Modrinth session. Require repeated clean one-chunk restoration and interruption tests before 2, 4, 9, 16 or larger campaigns.

## Practical constraint

GitHub Actions scheduling is best-effort and cannot guarantee 24/7 uninterrupted execution or arbitrary new engineering output. A genuinely persistent worker requires a separately provisioned cloud service, credentials, budget and monitoring. The scheduled agent above is a bounded first deployment, not a promise of uninterrupted operation.

# R1-204R exact-line handoff

Base: `work/release-bulk-water-placement` at `1c167f947869e46dabb136b4435ce77191dc5e3b`.

Purpose: restack only the R1-204 physical-audit selection-authority hoist onto the current release line. Do not inherit the stale R1-204 branch's old runtime evidence. The transform must preserve the strict fallback path and must not persist the retained-scope snapshot across cooperative yields.

Validation: focused deterministic regression, existing operation-column-scope and physical-audit-fastpath regressions, Java 25 production build, then persist the exact tested production mutation.

Staging SHA `97d3382f7cc7f56f64259097df25ddf8b9a45e42` passed focused transform/regressions and Java 25 production build in repository-wide run `39015212237`, which persisted product commit `aec1bd131980669383767de0b05a25262184ca47`. The persisted product was 7 ahead / 0 behind exact release base and initially had zero repository-wide exact-SHA runs, so staging evidence MUST NOT be inherited as product-head validation. PR #168 is DRAFT/HOLD. This handoff-only commit intentionally retriggers the idempotent focused workflow so the already-persisted product source is compiled/regressed on its own current exact head; do not integrate until that exact-head run is GREEN and divergence is rechecked.

No timeout, 8 ms finalizer budget, 48-ticket cap, strict-lighting oracle, restore/block-entity/vegetation acceptance, or scale/release criterion changes are authorized.

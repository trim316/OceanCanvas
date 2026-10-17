# R1-204R exact-line handoff

Base: `work/release-bulk-water-placement` at `1c167f947869e46dabb136b4435ce77191dc5e3b`.

Purpose: restack only the R1-204 physical-audit selection-authority hoist onto the current release line. Do not inherit the stale R1-204 branch's old runtime evidence. The transform must preserve the strict fallback path and must not persist the retained-scope snapshot across cooperative yields.

Validation: focused deterministic regression, existing operation-column-scope and physical-audit-fastpath regressions, Java 25 production build, then persist the exact tested production mutation. After bot persistence, validate the new exact product SHA separately before integration.

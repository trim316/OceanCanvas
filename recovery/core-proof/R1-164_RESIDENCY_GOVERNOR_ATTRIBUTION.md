# R1-164 — Residency governor attribution

Status: diagnostic gate; no product mutation.

Exact base: `4c9f7978477ffe8339630c585b0c026cd81c841a` (R1-159).

Purpose: prevent an unsafe inference from R1-162. `historicalResidencyCapHolds` alone does not prove the hard 48-ticket maximum is saturated. Historical LIGHT_ONLY residency shares the residency ledger with other ownership classes and is subject to an adaptive effective cap before the separate 88% raw-heap refusal. R1-164 must attribute effective cap tier and ownership before any product change.

Non-negotiable constraints:
- do not raise the 48-ticket hard maximum;
- do not weaken the 88% historical raw-heap refusal;
- do not repeat R1-161's one-tick cadence hypothesis;
- preserve strict LIGHT_ONLY proof, loaded-only certification, bounded ownership, and fail-closed behavior;
- do not treat hosted diagnosis as Minecraft runtime or scale certification.

Next gate: run `.github/workflows/r1-164-residency-governor-attribution.yml` on the exact branch head and persist the source attribution. Only after that evidence exists should a focused R1-165 product hypothesis be selected.

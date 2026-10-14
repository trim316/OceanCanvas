#!/usr/bin/env python3
from pathlib import Path
import re

src = Path('recovery/core-proof/R1-179_R1-176_WALL_ATTRIBUTION.txt')
out = Path('recovery/core-proof/R1-180_R1-176_COOPERATIVE_PHASE_ATTRIBUTION.txt')
text = src.read_text(encoding='utf-8')
required = {
    'sourceSha': 'b2dce48ba19b8330610810a6fe6d73f78080a486',
    'runId': '38465956259',
    'artifactId': '11849397308',
    'activeWorkExhaustionExpectedZero': 'true',
}
for k, v in required.items():
    if f'{k}={v}' not in text:
        raise SystemExit(f'missing exact evidence binding {k}={v}')

# Use the latest bounded cooperative sample: it is the closest observation to
# the failed throughput deadline and already came from the exact preserved run.
coop = re.findall(r'LIGHT-COOPERATIVE[^\n|]*', text)
if not coop:
    raise SystemExit('no bounded LIGHT-COOPERATIVE evidence')
line = coop[-1]

def num(name):
    m = re.search(rf'\b{name}=(\d+)', line)
    if not m:
        raise SystemExit(f'missing {name} in cooperative evidence')
    return int(m.group(1))

phases = []
for label, prefix in [
    ('physicalAudit', 'physicalAudit'),
    ('strictProof', 'strictProof'),
    ('fingerprint', 'fingerprint'),
    ('sweep', 'sweep'),
]:
    slices = num(prefix + 'Slices')
    yields = num(prefix + 'Yields')
    max_us = num('max' + prefix[0].upper() + prefix[1:] + 'SliceMicros')
    phases.append((label, slices, yields, max_us))

wall_budget_us = 8000
if 'configuredWallBudgetMicros=8000' not in text:
    raise SystemExit('unchanged 8ms wall budget not bound in exact attribution')
if 'activeWorkDeferrals=0' not in text:
    raise SystemExit('expected zero active-work deferrals not observed')

# Rank by yielded slices, a bounded proxy for how often cooperative work had to
# continue across invocations. This is attribution only; it does not authorize
# changing proof correctness, the 8ms wall budget, or acceptance.
ranked = sorted(phases, key=lambda x: (x[2], x[1]), reverse=True)
lines = [
    'R1-180 exact R1-176 cooperative phase attribution',
    'sourceSha=b2dce48ba19b8330610810a6fe6d73f78080a486',
    'runId=38465956259',
    'artifactId=11849397308',
    'configuredWallBudgetMicros=8000',
    'activeWorkDeferrals=0',
    'NOTE: diagnostic attribution only; no acceptance/safety/runtime certification change.',
    '',
]
for label, slices, yields, max_us in ranked:
    ratio = (100.0 * yields / slices) if slices else 0.0
    lines.append(f'{label}: slices={slices} yields={yields} yieldPct={ratio:.2f} maxSliceMicros={max_us} maxOverWallBudget={str(max_us > wall_budget_us).lower()}')
lines += [
    '',
    f'dominantYieldPhase={ranked[0][0]}',
    f'dominantYieldCount={ranked[0][2]}',
    'nextAction=inspect-and-reduce-repeated-work-inside-dominant-cooperative-phase-without-changing-proof-or-wall-budget',
]
out.write_text('\n'.join(lines) + '\n', encoding='utf-8')
if out.stat().st_size > 8192:
    raise SystemExit('attribution unexpectedly oversized')
print(out.read_text(encoding='utf-8'))

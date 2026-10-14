#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import subprocess

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

# R1-181 product candidate generation.
# The exact R1-180 diagnosis shows physical audit as the dominant cooperative
# phase. The pre-light audit is already an exhaustive physical-profile proof,
# and its completion bit is explicitly scoped to the current physical epoch:
# every real block mutation calls resetSkyRecoveryForPhysicalMutation(), which
# clears the cooperative proof state. Stage-1 fingerprint verification separately
# rejects mutations that occur while lighting settles. Therefore the second
# exhaustive pre-publish scan is redundant when BOTH certificates are still live.
# This does not change the proof, wall budget, repair authority, or acceptance;
# it reuses an exact proof only within the same mutation-invalidated epoch.
BASE = 'b2dce48ba19b8330610810a6fe6d73f78080a486'
BRANCH = 'work/r1-181-current-epoch-physical-audit-reuse'
WT = Path('/tmp/oceancanvas-r1-181')
SOURCE_REL = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
MARKER = 'R1-181 current-epoch physical audit reuse'
ANCHOR = '\t\t\tif (allowPhysicalRepair && !lightFinalizerSession().prePublishPhysicalAuditComplete.contains(packed)) {'
INSERT = '''\t\t\t// R1-181 current-epoch physical audit reuse: the pre-light audit is an\n\t\t\t// exhaustive physical-profile proof. Its completion certificate is cleared by\n\t\t\t// resetSkyRecoveryForPhysicalMutation -> clearIncrementalDeepRepairState ->\n\t\t\t// clearCooperativeFinalizerState on every real block mutation. Stage-1\n\t\t\t// fingerprint verification independently rejects canonical-boundary changes\n\t\t\t// while lighting settles. If both certificates are still live after all late\n\t\t\t// physical-repair opportunities, this is the same physical epoch, so the\n\t\t\t// second exhaustive scan proves nothing new. Reuse the exact proof; any real\n\t\t\t// mutation clears it and falls back to the full pre-publish audit below.\n\t\t\tif (allowPhysicalRepair\n\t\t\t\t\t&& !biomeReasserted\n\t\t\t\t\t&& lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)\n\t\t\t\t\t&& lightFinalizerSession().stage1FingerprintVerified.contains(packed)\n\t\t\t\t\t&& !lightFinalizerSession().prePublishPhysicalAuditComplete.contains(packed)) {\n\t\t\t\tlightFinalizerSession().prePublishPhysicalAuditComplete.add(packed);\n\t\t\t}\n\n'''

subprocess.run(['git', 'cat-file', '-e', BASE + '^{commit}'], check=True)
if WT.exists():
    subprocess.run(['git', 'worktree', 'remove', '--force', str(WT)], check=False)
    shutil.rmtree(WT, ignore_errors=True)
subprocess.run(['git', 'worktree', 'add', '--detach', str(WT), BASE], check=True)
try:
    product = WT / SOURCE_REL
    source = product.read_text(encoding='utf-8')

    # Fail closed unless the invalidation and verification contracts R1-181 relies
    # on are visibly present in the exact source being transformed.
    required_source = [
        'private static void resetSkyRecoveryForPhysicalMutation(long packed)',
        'clearIncrementalDeepRepairState(packed);',
        'private static void clearCooperativeFinalizerState(long packed)',
        'preLightPhysicalAuditComplete.remove(packed);',
        'prePublishPhysicalAuditComplete.remove(packed);',
        'stage1FingerprintVerified.add(packed);',
        'if (allowPhysicalRepair && biomeReasserted)',
        'LIGHT_FINALIZER_WALL_BUDGET_NS = 8_000_000L',
        ANCHOR.strip(),
    ]
    for needle in required_source:
        if needle not in source:
            raise SystemExit(f'R1-181 safety anchor missing: {needle}')
    if source.count(ANCHOR) != 1:
        raise SystemExit(f'R1-181 expected exactly one pre-publish audit anchor, found {source.count(ANCHOR)}')
    if source.index('stage1FingerprintVerified.add(packed);') > source.index(ANCHOR):
        raise SystemExit('R1-181 stage-1 fingerprint verification must precede pre-publish audit')
    if source.index('if (allowPhysicalRepair && biomeReasserted)') > source.index(ANCHOR):
        raise SystemExit('R1-181 biome-reassert restart must precede pre-publish audit')
    if MARKER in source:
        raise SystemExit('R1-181 marker unexpectedly already present in exact R1-176 base')

    source = source.replace(ANCHOR, INSERT + ANCHOR, 1)
    product.write_text(source, encoding='utf-8')

    # Deterministic post-transform assertions: do not allow the optimization to
    # exist without the mutation invalidation and stage-1 verification guards.
    transformed = product.read_text(encoding='utf-8')
    for needle in [
        MARKER,
        '&& !biomeReasserted',
        '&& lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)',
        '&& lightFinalizerSession().stage1FingerprintVerified.contains(packed)',
        'lightFinalizerSession().prePublishPhysicalAuditComplete.add(packed);',
        'LIGHT_FINALIZER_WALL_BUDGET_NS = 8_000_000L',
    ]:
        if needle not in transformed:
            raise SystemExit(f'R1-181 transformed invariant missing: {needle}')

    subprocess.run(['git', '-C', str(WT), 'config', 'user.name', 'github-actions[bot]'], check=True)
    subprocess.run(['git', '-C', str(WT), 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
    subprocess.run(['git', '-C', str(WT), 'add', str(SOURCE_REL)], check=True)
    subprocess.run(['git', '-C', str(WT), 'commit', '-m', 'R1-181: reuse current-epoch physical audit before publish'], check=True)
    candidate = subprocess.check_output(['git', '-C', str(WT), 'rev-parse', 'HEAD'], text=True).strip()
    subprocess.run(['git', '-C', str(WT), 'push', '--force-with-lease', 'origin', f'HEAD:refs/heads/{BRANCH}'], check=True)
    print(f'R1-181 candidateSha={candidate}')
    print(f'R1-181 branch={BRANCH}')
finally:
    subprocess.run(['git', 'worktree', 'remove', '--force', str(WT)], check=False)
    shutil.rmtree(WT, ignore_errors=True)

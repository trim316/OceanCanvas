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
print(out.read_text(encoding='utf-8'))

# R1-182: keep the exhaustive pre-light physical certificate alive through the
# same physical epoch and reuse it in resident fast certification. R1-181 proved
# that current-epoch certificate reuse is safe but only reduced cooperative audit
# work ~18%. JFR from exact R1-181 shows the larger hidden duplicate: about 88% of
# sampled synchronous firstPhysicalProfileMismatch CPU is called from
# tryFastCertifyResidentCanvasLighting. The certificate is already fail-closed:
# every real physical mutation calls resetSkyRecoveryForPhysicalMutation ->
# clearIncrementalDeepRepairState -> clearCooperativeFinalizerState, which removes
# preLightPhysicalAuditComplete. Do not change wall/time/safety budgets or proof.
BASE = 'd5fefd89207cbf59dff0c678b95323783d6b3284'
BRANCH = 'work/r1-182-retain-current-epoch-physical-proof'
WT = Path('/tmp/oceancanvas-r1-182')
SOURCE_REL = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
MARKER = 'R1-182 retain current-epoch physical proof'
SWEEP_OLD = '''\t\t\t\tif (!advanceLiveChunkLightSweep(world, live, true, packed)) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }\n\t\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.remove(packed);\n\t\t\t\tlightFinalizerSession().pendingPasses.put(packed, 1);'''
SWEEP_NEW = '''\t\t\t\tif (!advanceLiveChunkLightSweep(world, live, true, packed)) { lightFinalizerSession().pendingTicks.put(packed, 1); continue; }\n\t\t\t\t// R1-182 retain current-epoch physical proof: do not discard the successful\n\t\t\t\t// exhaustive pre-light audit merely because the light sweep completed. Every\n\t\t\t\t// real physical mutation already clears this certificate through\n\t\t\t\t// resetSkyRecoveryForPhysicalMutation -> clearCooperativeFinalizerState.\n\t\t\t\t// Keeping it therefore changes no authority: it only avoids proving the same\n\t\t\t\t// unchanged physical epoch again in fast-cert and pre-publish paths.\n\t\t\t\tlightFinalizerSession().pendingPasses.put(packed, 1);'''
FAST_OLD = '''        if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;\n        if (firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get()) != null) return false;\n        if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;'''
FAST_NEW = '''        if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;\n        // R1-182 retain current-epoch physical proof: if the exhaustive pre-light\n        // audit is still live, no physical mutation has occurred since that proof.\n        // Otherwise fail closed through the unchanged full profile audit.\n        if (!lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)\n                && firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get()) != null) return false;\n        if (net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(world.getServer())) return false;'''

subprocess.run(['git', 'cat-file', '-e', BASE + '^{commit}'], check=True)
if WT.exists():
    subprocess.run(['git', 'worktree', 'remove', '--force', str(WT)], check=False)
    shutil.rmtree(WT, ignore_errors=True)
subprocess.run(['git', 'worktree', 'add', '--detach', str(WT), BASE], check=True)
try:
    product = WT / SOURCE_REL
    source = product.read_text(encoding='utf-8')
    required_source = [
        'R1-181 current-epoch physical audit reuse',
        'private static void resetSkyRecoveryForPhysicalMutation(long packed)',
        'clearIncrementalDeepRepairState(packed);',
        'private static void clearCooperativeFinalizerState(long packed)',
        'preLightPhysicalAuditComplete.remove(packed);',
        'prePublishPhysicalAuditComplete.remove(packed);',
        'preLightPhysicalAuditComplete.add(packed);',
        'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L',
        'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48',
        SWEEP_OLD,
        FAST_OLD,
    ]
    for needle in required_source:
        if needle not in source:
            raise SystemExit(f'R1-182 safety anchor missing: {needle[:120]}')
    if source.count(SWEEP_OLD) != 1:
        raise SystemExit(f'R1-182 expected one sweep certificate drop, found {source.count(SWEEP_OLD)}')
    if source.count(FAST_OLD) != 1:
        raise SystemExit(f'R1-182 expected one fast-cert full-audit anchor, found {source.count(FAST_OLD)}')
    if MARKER in source:
        raise SystemExit('R1-182 marker unexpectedly already present')

    source = source.replace(SWEEP_OLD, SWEEP_NEW, 1)
    source = source.replace(FAST_OLD, FAST_NEW, 1)
    product.write_text(source, encoding='utf-8')

    transformed = product.read_text(encoding='utf-8')
    # Fail closed: certificate retention is permitted only while the exact
    # mutation invalidation chain and hard safety constants remain present.
    invariants = [
        MARKER,
        'preLightPhysicalAuditComplete.add(packed);',
        'preLightPhysicalAuditComplete.remove(packed);',  # cleanup path still exists
        'clearCooperativeFinalizerState(packed);',
        '!lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)',
        '&& firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get()) != null',
        'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L',
        'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48',
    ]
    for needle in invariants:
        if needle not in transformed:
            raise SystemExit(f'R1-182 transformed invariant missing: {needle}')
    if SWEEP_OLD in transformed or FAST_OLD in transformed:
        raise SystemExit('R1-182 old duplicate-audit anchor still present')

    subprocess.run(['git', '-C', str(WT), 'config', 'user.name', 'github-actions[bot]'], check=True)
    subprocess.run(['git', '-C', str(WT), 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
    subprocess.run(['git', '-C', str(WT), 'add', str(SOURCE_REL)], check=True)
    subprocess.run(['git', '-C', str(WT), 'commit', '-m', 'R1-182: retain current-epoch physical proof for fast certification'], check=True)
    candidate = subprocess.check_output(['git', '-C', str(WT), 'rev-parse', 'HEAD'], text=True).strip()
    subprocess.run(['git', '-C', str(WT), 'push', '--force-with-lease', 'origin', f'HEAD:refs/heads/{BRANCH}'], check=True)
    print(f'R1-182 candidateSha={candidate}')
    print(f'R1-182 branch={BRANCH}')
finally:
    subprocess.run(['git', 'worktree', 'remove', '--force', str(WT)], check=False)
    shutil.rmtree(WT, ignore_errors=True)

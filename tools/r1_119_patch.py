"""Read-only replacement for the obsolete v73 patcher. Never weakens source proof."""
from pathlib import Path
import subprocess
source = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
assert 'hasBoundedImmediateLateralSkySupport' not in source, 'neighbor brightness must not bypass constructive source proof'
assert 'hasProvenLateralSkySource' in source
assert 'queueUnsupportedSkyComponent(world, sky)' in source
assert 'cycleAttempts < 2 && shouldAttemptFloorBandCycleBreak' in source
assert 'floorBandCycleBreakCounts.put(packed, cycleAttempts + 1)' in source
assert 'pendingTicks.put(packed, LIGHT_VISIBLE_DEEP_DENSE_REPAIR_SETTLE_TICKS)' in source
policy = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/UnsupportedSkyRepairPolicy.java')
fixture = Path('scripts/ci/fixtures/unsupported-sky')
output = Path('proof-audit-classes')
output.mkdir(exist_ok=True)
subprocess.run(['javac','-d',str(output),str(policy),str(fixture/'UnsupportedSkyPolicyRegression.java'),str(fixture/'SavedSkyRepairReplay.java')],check=True)
subprocess.run(['java','-cp',str(output),'net.oceancanvas.mod.worldgen.UnsupportedSkyPolicyRegression'],check=True)
subprocess.run(['java','-cp',str(output),'net.oceancanvas.mod.worldgen.SavedSkyRepairReplay',str(fixture/'repair-replay.csv')],check=True)
print('R1_119_STRICT_SOURCE_PROOF_AUDIT_PASS_NO_SOURCE_MUTATION')

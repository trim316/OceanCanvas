"""Prove normal segment checkpoint/resume on one small disposable world."""
import json
import os
from pathlib import Path
import subprocess
import sys
from checkpoint_bundle import verify_manifest

out = Path('production-runtime-evidence')
env = dict(os.environ, OC_TEST_WIDTH='500', OC_STAGE_SECONDS='2', OC_RESUME='false',
           OC_CHECKPOINT_SEGMENT='true', OC_REQUIRE_CHECKPOINT_MANIFEST='true')
subprocess.run([sys.executable, 'scripts/ci/production-scale.py'], env=env, check=True)
status = json.loads((out / 'checkpoint-status.json').read_text())
identity = json.loads((out / 'checkpoint.json').read_text())
timing = (out / 'checkpoint-timing.json').read_bytes()
verify_manifest(out, identity)
if status.get('segmentPending') is not True or status['completionObserved'] or status['serverExit'] != 0:
    raise SystemExit('Expected a clean, unfinished normal segment')
if (out / 'acceptance.json').exists() or (out / 'restart.json').exists():
    raise SystemExit('Unfinished segment produced completion evidence')
env.update(OC_STAGE_SECONDS='300', OC_RESUME='true', OC_CHECKPOINT_SEGMENT='false')
subprocess.run([sys.executable, 'scripts/ci/production-scale.py'], env=env, check=True)
verify_manifest(out, identity)
for name in ('acceptance.json', 'restart.json'):
    if json.loads((out / name).read_text()).get('passed') is not True:
        raise SystemExit('Resumed world failed ' + name)
if json.loads(timing)['startedUtc'] != json.loads((out / 'checkpoint-timing.json').read_text())['startedUtc']:
    raise SystemExit('Resume reset original timing')
print('CHECKPOINT_NORMAL_SEGMENT_RESUME_PASS')

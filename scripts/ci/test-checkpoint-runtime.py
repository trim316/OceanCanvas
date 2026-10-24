"""Exercise actual save/interruption/resume on a disposable 500-block world."""
import json
import os
from pathlib import Path
import subprocess
import sys

env = dict(os.environ, OC_TEST_WIDTH='500', OC_STAGE_SECONDS='2', OC_RESUME='false')
first = subprocess.run([sys.executable, 'scripts/ci/production-scale.py'], env=env)
status = json.loads(Path('production-runtime-evidence/checkpoint-status.json').read_text())
log = Path('production-runtime-evidence/console.log').read_text()
if first.returncode == 0 or not status['savedWorldPresent'] or status['completionObserved']:
    raise SystemExit('Interruption fixture did not leave incomplete saved work')
if status['serverExit'] != 0 or 'PREGEN-ACCEPTANCE-START' not in log:
    raise SystemExit('Initial segment failed before a clean saved checkpoint')
env.update(OC_STAGE_SECONDS='1800', OC_RESUME='true')
subprocess.run([sys.executable, 'scripts/ci/production-scale.py'], env=env, check=True)
result = json.loads(Path('production-runtime-evidence/acceptance.json').read_text())
if result.get('passed') is not True:
    raise SystemExit('Resumed runtime did not pass structured acceptance')
print('CHECKPOINT_RESTART_RUNTIME_PASS')

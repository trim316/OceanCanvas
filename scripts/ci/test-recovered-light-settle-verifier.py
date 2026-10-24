#!/usr/bin/env python3
"""Deterministic regression for the R1-254 recovered light-settle evidence contract."""
import json
import subprocess
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name('verify-recovered-light-settle.py')

def run(console, acceptance=True, restart=True):
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root/'acceptance.json').write_text(json.dumps({'passed': acceptance}))
        (root/'restart.json').write_text(json.dumps({'passed': restart}))
        (root/'console.log').write_text(console)
        (root/'restart.log').write_text('')
        return subprocess.run(['python3', str(SCRIPT), str(root)], capture_output=True, text=True)

clean = run('PREGEN-ACCEPTANCE-DONE relightFailed=0 persistentFault=0 ticketLeak=0\n')
assert clean.returncode == 0, clean.stderr

recovered = run('LIGHT-ROOT-CAUSE classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE requiredQuietTicks=80 action=restart-from-heightmap-and-light-stage\nPREGEN-ACCEPTANCE-DONE relightFailed=0 persistentFault=0 ticketLeak=0\n')
assert recovered.returncode == 0, recovered.stderr
assert 'mutations=1' in recovered.stdout

missing_restart = run('LIGHT-ROOT-CAUSE classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE requiredQuietTicks=80\nPREGEN-ACCEPTANCE-DONE\n')
assert missing_restart.returncode != 0

zero_quiet = run('LIGHT-ROOT-CAUSE classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE requiredQuietTicks=0 action=restart-from-heightmap-and-light-stage\nPREGEN-ACCEPTANCE-DONE\n')
assert zero_quiet.returncode != 0

for terminal_fault in ('relightFailed=3', 'persistentFault=7', 'ticketLeak=4'):
    unresolved = run('LIGHT-ROOT-CAUSE classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE requiredQuietTicks=80 action=restart-from-heightmap-and-light-stage\nPREGEN-ACCEPTANCE-DONE ' + terminal_fault + '\n')
    assert unresolved.returncode != 0, terminal_fault

bad_acceptance = run('PREGEN-ACCEPTANCE-DONE\n', acceptance=False)
assert bad_acceptance.returncode != 0

bad_restart = run('PREGEN-ACCEPTANCE-DONE\n', restart=False)
assert bad_restart.returncode != 0

vegetation_failure = run('OCEAN-VEGETATION-FAILED\nPREGEN-ACCEPTANCE-DONE\n')
assert vegetation_failure.returncode != 0

print('R1-254 deterministic recovered-light-settle verifier regression: PASS')

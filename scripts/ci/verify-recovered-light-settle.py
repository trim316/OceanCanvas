#!/usr/bin/env python3
"""Fail-closed verifier for recovered lighting-settle mutations in disposable runtime evidence."""
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'production-runtime-evidence')
acceptance = json.loads((root / 'acceptance.json').read_text())
restart = json.loads((root / 'restart.json').read_text())
text = (root / 'console.log').read_text(errors='replace') + '\n' + (root / 'restart.log').read_text(errors='replace')

assert acceptance.get('passed') is True, acceptance
assert restart.get('passed') is True, restart
assert 'OCEAN-VEGETATION-FAILED' not in text, 'vegetation placement threw at runtime'

mutation_lines = [line for line in text.splitlines() if 'classification=BLOCKS_CHANGED_DURING_LIGHT_SETTLE' in line]
for line in mutation_lines:
    assert 'action=restart-from-heightmap-and-light-stage' in line, line
    quiet = re.search(r'requiredQuietTicks=(\d+)', line)
    assert quiet and int(quiet.group(1)) > 0, line

# A recovered transient may exist, but terminal evidence must still be clean. Do not
# convert persistent/finalizer failure, ticket leakage, or failed acceptance into PASS.
assert 'PREGEN-ACCEPTANCE-DONE' in text, 'strict acceptance completion marker missing'
for field in ('relightFailed', 'persistentFault', 'ticketLeak'):
    for match in re.finditer(rf'\b{field}=(\d+)\b', text):
        assert int(match.group(1)) == 0, f'unresolved terminal lighting evidence: {match.group(0)}'

print(f'R1-254 recovered light-settle verifier: PASS mutations={len(mutation_lines)}')

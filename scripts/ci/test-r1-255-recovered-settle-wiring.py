#!/usr/bin/env python3
from pathlib import Path

workflow = Path('.github/workflows/r1-115-overnight-profile.yml').read_text()
old = "assert 'BLOCKS_CHANGED_DURING_LIGHT_SETTLE' not in text"
call = 'python3 scripts/ci/verify-recovered-light-settle.py production-runtime-evidence'

assert old not in workflow, 'obsolete zero-occurrence settle assertion remains wired'
assert workflow.count(call) == 1, 'recovered-settle verifier must be invoked exactly once'
assert "assert acceptance.get('passed') is True" in workflow
assert "assert restart.get('passed') is True" in workflow
assert "assert 'OCEAN-VEGETATION-FAILED' not in text" in workflow

verifier = Path('scripts/ci/verify-recovered-light-settle.py').read_text()
for required in (
    'action=restart-from-heightmap-and-light-stage',
    'requiredQuietTicks=',
    "'relightFailed', 'persistentFault', 'ticketLeak'",
    "assert 'PREGEN-ACCEPTANCE-DONE' in text",
):
    assert required in verifier, required

print('R1-255 recovered-settle wiring regression: PASS')

"""Guard the actual workflow dependency graph and publication joins."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2] / '.github/workflows'


def jobs(name):
    text = (root / name).read_text(encoding='utf-8').split('\njobs:\n', 1)[1]
    starts = list(re.finditer(r'^  ([a-z][a-z0-9-]*):\s*$', text, re.M))
    return {m[1]: text[m.end():starts[i+1].start() if i+1 < len(starts) else len(text)]
            for i, m in enumerate(starts)}


def needs(job):
    match = re.search(r'^    needs: (.+)$', job, re.M)
    return set(match[1].strip('[]').replace(' ', '').split(',')) if match else set()


pipeline = jobs('production-scale-release-gate.yml')
assert 'production-runtime.py' not in pipeline['production-build'], 'Build must publish before any world run'
for lane in ('baseline-runtime', 'parallel-runtime', 'diagnostic-runtime', 'checkpoint-smoke'):
    assert needs(pipeline[lane]) == {'production-build'}, lane
required = {'production-build', 'baseline-runtime', 'independent-checks', 'parallel-runtime',
            'diagnostic-runtime', 'checkpoint-smoke'}
assert needs(pipeline['production-finalize']) == required | {'production-acceptance'}
assert needs(pipeline['publish-500']) == required
assert required <= needs(pipeline['status-report'])
acceptance = jobs('production-acceptance.yml')
assert needs(acceptance['scale-5000']) == needs(acceptance['scale-20000']) == {'baseline-500'}
assert needs(acceptance['admit-evidence']) == {'scale-5000', 'scale-20000'}
assert 'git push' not in acceptance['scale-20000']
assert 'git push' in acceptance['admit-evidence']
assert "job['name'] == 'Fresh 500-block baseline'" in acceptance['baseline-500']
for lane in ('scale-5000', 'scale-20000'):
    assert 'production-runtime-evidence/server/world/' in acceptance[lane]
    assert 'include-hidden-files: true' in acceptance[lane]
print('PASS: independent runtime starts; parallel scale runs; all proof joins preserved; checkpoints uploaded')

from diagnostic_cases import select, CASES
assert len(select([case['name'] for case in CASES])) == 8
for invalid in ([], ['unknown'], ['throughput-profile', 'throughput-profile'], 'throughput-profile', [123]):
    try:
        select(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('Invalid diagnostic selection accepted')
print('PASS: pinned-candidate diagnostics select only requested, known cases')

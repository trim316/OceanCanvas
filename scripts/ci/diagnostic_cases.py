"""Plan selected independent diagnostics for an already-built candidate."""
import json
import os

CASES = [
    dict(name='controlled-waterfall-inflow', width=128, x=-464, z=436, fixture='true', border='true', seconds=240),
    dict(name='building-water-persistence', width=128, x=0, z=0, gameplay='true', seconds=240),
    dict(name='lighting-hotspot-east', width=128, x=134, z=14, seconds=240),
    dict(name='lighting-hotspot-edge', width=128, x=186, z=-246, seconds=240),
    dict(name='waterfall-frontier', width=128, x=-464, z=436, border='true', seconds=240),
    dict(name='throughput-profile', width=1000, x=0, z=0, profile='true', border='true', seconds=900),
    dict(name='baseline-500', width=500, x=0, z=0, seconds=1800),
    dict(name='checkpoint-resume', width=500, x=0, z=0, seconds=1800),
]


def select(names):
    allowed = {case['name']: case for case in CASES}
    if (not isinstance(names, list) or not names or any(not isinstance(n, str) for n in names)
            or len(set(names)) != len(names) or not set(names) <= set(allowed)):
        raise ValueError('Select unique case names from: ' + ', '.join(allowed))
    return [allowed[name] for name in names]


if __name__ == '__main__':
    selected = select(json.loads(os.environ['REQUESTED_CASES']))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write('matrix=' + json.dumps(selected) + '\n')

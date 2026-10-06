"""Describe observed lighting backlog; never substitute for acceptance."""
import json
from pathlib import Path
import re
import sys

def report(text):
    samples = []
    for line in text.splitlines():
        match = re.search(r'progress: (\d+)/(\d+) chunks terrain-confirmed .*?; (\d+) lighting pending', line)
        if match:
            samples.append(tuple(map(int, match.groups())))
    pending = [sample[2] for sample in samples]
    completed = bool(re.search(r'PREGEN-ACCEPTANCE-DONE ', text))
    return dict(samples=len(samples), peakLightingPending=max(pending, default=0),
                lastLightingPending=pending[-1] if pending else None,
                netPendingChange=pending[-1]-pending[0] if pending else None,
                growingIntervals=sum(b>a for a,b in zip(pending,pending[1:])),
                drainingIntervals=sum(b<a for a,b in zip(pending,pending[1:])),
                lastTerrainConfirmed=samples[-1][0] if samples else None,
                targetChunks=samples[-1][1] if samples else None,
                completionObserved=completed,
                interpretation='completion observed; consult acceptance' if completed else
                'backlog growing' if pending and pending[-1]>pending[0] else
                'backlog stable or draining; completion not proven')

if __name__ == '__main__':
    path = Path(sys.argv[1])
    result = report(path.read_text(errors='replace'))
    output = path.with_name('backlog-report.json')
    output.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))

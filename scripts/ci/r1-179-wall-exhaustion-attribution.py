from pathlib import Path
import re

# Consume the bounded R1-178 summary rather than the multi-megabyte R1-177 raw
# diagnosis.  R1-178 is itself pinned to the exact R1-176 failed run/artifact and
# retains the final bounded budget contexts needed for attribution.
src = Path('recovery/core-proof/R1-178_R1-176_COMPACT_SUMMARY.txt')
out = Path('recovery/core-proof/R1-179_R1-176_WALL_ATTRIBUTION.txt')
if not src.exists():
    raise SystemExit('missing persisted R1-178 compact diagnosis')
text = src.read_text(errors='replace')
required = [
    'source_sha=b2dce48ba19b8330610810a6fe6d73f78080a486',
    'run_id=38465956259',
    'artifact_id=11849397308',
    'configuredWallBudgetMicros=8000',
    'activeWorkDeferrals=0',
]
for needle in required:
    if needle not in text:
        raise SystemExit(f'evidence binding missing: {needle}')
lines = text.splitlines()
wall = [(i, l) for i, l in enumerate(lines) if 'wallBudgetDeferrals=' in l]
if not wall:
    raise SystemExit('no wall-budget telemetry found')
rows = []
max_wall = -1
for i, line in wall:
    match = re.search(r'wallBudgetDeferrals=(\d+)', line)
    if not match:
        continue
    value = int(match.group(1))
    max_wall = max(max_wall, value)
    # Keep bounded local context so the next repair can identify what co-varied
    # with wall exhaustion without promoting diagnosis into acceptance evidence.
    context = ' | '.join(x.strip() for x in lines[max(0, i - 1):min(len(lines), i + 2)])
    rows.append((value, context[:4000]))
rows = sorted(rows, key=lambda x: x[0])
if max_wall <= 0:
    raise SystemExit('wall budget never exhausted; attribution premise disproved')
# Representative early/mid/late samples, de-duplicated.
indexes = sorted(set([0, len(rows) // 4, len(rows) // 2, (3 * len(rows)) // 4, len(rows) - 1]))
report = [
    'R1-179 exact R1-176 wall-exhaustion attribution',
    'sourceSha=b2dce48ba19b8330610810a6fe6d73f78080a486',
    'runId=38465956259',
    'artifactId=11849397308',
    f'wallSamples={len(rows)}',
    f'maxWallBudgetDeferrals={max_wall}',
    'activeWorkExhaustionExpectedZero=true',
    'NOTE: diagnostic attribution only; no acceptance/safety/runtime certification change.',
    '',
]
for number, index in enumerate(indexes, 1):
    report.append(f'SAMPLE {number} wallBudgetDeferrals={rows[index][0]}')
    report.append(rows[index][1])
    report.append('')
out.write_text('\n'.join(report))
if out.stat().st_size > 30000:
    raise SystemExit('bounded attribution unexpectedly large')

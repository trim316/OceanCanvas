from pathlib import Path
import re

src = Path('recovery/core-proof/R1-177_R1-176_DIAGNOSIS.txt')
out = Path('recovery/core-proof/R1-179_R1-176_WALL_ATTRIBUTION.txt')
if not src.exists():
    raise SystemExit('missing persisted R1-177 diagnosis')
text = src.read_text(errors='replace')
required = ['sourceSha=b2dce48ba19b8330610810a6fe6d73f78080a486','runId=38465956259','artifactId=11849397308']
for needle in required:
    if needle not in text:
        raise SystemExit(f'evidence binding missing: {needle}')
lines = text.splitlines()
wall = [(i,l) for i,l in enumerate(lines) if 'wallBudgetDeferrals=' in l]
if not wall:
    raise SystemExit('no wall-budget telemetry found')
rows=[]
max_wall=-1
for i,l in wall:
    m=re.search(r'wallBudgetDeferrals=(\d+)',l)
    if not m: continue
    v=int(m.group(1)); max_wall=max(max_wall,v)
    # Keep bounded local context so the next repair can identify what co-varied with wall exhaustion.
    ctx=' | '.join(x.strip() for x in lines[max(0,i-1):min(len(lines),i+2)])
    rows.append((v,ctx[:4000]))
rows=sorted(rows,key=lambda x:x[0])
if max_wall <= 0:
    raise SystemExit('wall budget never exhausted; attribution premise disproved')
# Representative early/mid/late samples, de-duplicated.
idxs=sorted(set([0,len(rows)//4,len(rows)//2,(3*len(rows))//4,len(rows)-1]))
report=['R1-179 exact R1-176 wall-exhaustion attribution','sourceSha=b2dce48ba19b8330610810a6fe6d73f78080a486','runId=38465956259','artifactId=11849397308',f'wallSamples={len(rows)}',f'maxWallBudgetDeferrals={max_wall}','activeWorkExhaustionExpectedZero=true','NOTE: diagnostic attribution only; no acceptance/safety/runtime certification change.','']
for n,j in enumerate(idxs,1):
    report.append(f'SAMPLE {n} wallBudgetDeferrals={rows[j][0]}')
    report.append(rows[j][1])
    report.append('')
out.write_text('\n'.join(report))
if out.stat().st_size > 30000:
    raise SystemExit('bounded attribution unexpectedly large')

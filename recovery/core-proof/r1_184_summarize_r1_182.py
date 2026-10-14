#!/usr/bin/env python3
from pathlib import Path
import re
SRC=Path('recovery/core-proof/R1-183_R1-182_DIAGNOSIS.txt')
OUT=Path('recovery/core-proof/R1-184_R1-182_BOTTLENECK_SUMMARY.txt')
SOURCE_SHA='0afe6a93cfab93118cd2d7c253d6aa07230563fc'; RUN_ID='38501748712'; ARTIFACT_ID='11866289263'
text=SRC.read_text(encoding='utf-8',errors='strict')
for required in (SOURCE_SHA,RUN_ID,ARTIFACT_ID):
    if required not in text: raise SystemExit(f'missing exact evidence binding: {required}')
lines=text.splitlines()
cats={
'wall_budget':r'wall.{0,24}(budget|exhaust|defer)|budget.{0,24}wall','active_work_budget':r'active.{0,16}work|work.{0,16}budget','strict_sky':r'strict.{0,12}sky|sky.{0,12}(proof|audit)','physical_proof':r'physical.{0,16}(audit|proof|profile|certificate)','fast_certification':r'fast.{0,16}(cert|proof)|resident.{0,16}(cert|proof)','light_sweep':r'light.{0,12}sweep|sweep.{0,12}light','fingerprint':r'fingerprint','cooperative_yield':r'cooperative|yield|continuation','lighting_debt':r'light.{0,16}(pending|debt|backlog)|pending.{0,16}light','residency':r'residenc|ticket','terrain':r'terrain|submitted','retry_delay':r'retry|delay|defer|reschedul','memory_pressure':r'heap|memory|pressure'}
matched={k:[ln for ln in lines if re.search(rx,ln,re.I)] for k,rx in cats.items()}
if not matched['wall_budget']: raise SystemExit('refusing empty wall-budget diagnosis')
if not any(matched[k] for k in ('strict_sky','physical_proof','light_sweep','fingerprint')): raise SystemExit('refusing diagnosis with no proof-phase evidence')
def bounded(xs): return xs if len(xs)<=12 else xs[:6]+['... bounded ...']+xs[-6:]
ranked=sorted(((len(v),k) for k,v in matched.items()),reverse=True)
out=['R1-184 bounded bottleneck summary (diagnostic evidence only)',f'source_sha={SOURCE_SHA}',f'run_id={RUN_ID}',f'artifact_id={ARTIFACT_ID}','','CATEGORY_COUNTS']
out += [f'{k}={n}' for n,k in ranked]
out += ['','RANKED_NONZERO']+[f'{k}:{n}' for n,k in ranked if n]
for _,k in ranked:
    if matched[k]: out += ['',f'[{k}]']+bounded(matched[k])
result='\n'.join(out)+'\n'
if len(result.encode())>128*1024: raise SystemExit('summary exceeded 128 KiB bound')
OUT.write_text(result,encoding='utf-8'); print(result)

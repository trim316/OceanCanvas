"""Artifact-bound 1k release gate; publication is a separate explicit step."""
import json, hashlib, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkpoint_bundle import verify_manifest
from candidate_verifier import verify_candidate
from datetime import datetime

def validate(candidate, evidence, build, source):
    jar, sha = verify_candidate(Path(candidate), build, source)
    evidence = Path(evidence)
    identity = json.loads((evidence / 'checkpoint.json').read_text())
    required = dict(sourceCommit=source, jarSha256=sha, build=build,
                    requestedBlocks=1000, targetBlocks=1032, targetChunks=4356,
                    naturalBorderBlocks=16, minBlockX=-516, maxBlockX=515,
                    minBlockZ=-516, maxBlockZ=515)
    if any(identity.get(k) != v for k,v in required.items()):
        raise ValueError('Exact 1k identity/geometry mismatch')
    verify_manifest(evidence, identity)
    status = json.loads((evidence / 'checkpoint-status.json').read_text())
    if status.get('serverExit') != 0 or status.get('completionObserved') is not True or status.get('segmentPending') is not False:
        raise ValueError('Saved pending segments cannot certify a release')
    acceptance = json.loads((evidence / 'acceptance.json').read_text())
    if acceptance.get('passed') is not True or acceptance.get('log_sha256') != hashlib.sha256((evidence/'console.log').read_bytes()).hexdigest():
        raise ValueError('Strict acceptance has not passed')
    restart = json.loads((evidence / 'restart.json').read_text())
    if restart.get('passed') is not True or any(restart.get(k) != v for k,v in required.items() if k in ('sourceCommit','jarSha256','build','targetBlocks','requestedBlocks','targetChunks')):
        raise ValueError('Exact saved-world restart has not passed')
    if hashlib.sha256((evidence/'restart.log').read_bytes()).hexdigest()!=restart.get('logSha256'):
        raise ValueError('Restart log checksum mismatch')
    timing=json.loads((evidence/'checkpoint-timing.json').read_text())
    if timing['identity']!=identity or timing['startedUtc']!='2026-10-06T23:02:43.773984+00:00':
        raise ValueError('Original clock was not retained')
    elapsed=(datetime.fromisoformat(timing['completedUtc'])-datetime.fromisoformat(timing['startedUtc'])).total_seconds()/3600
    if not 0<=elapsed<=8: raise ValueError('Original eight-hour window exceeded')
    lineage=json.loads((evidence/'candidate-upgrade-lineage.json').read_text())
    if lineage.get('candidateIdentity')!=identity or lineage.get('parentRun')!=37545088998 or lineage.get('freshWorld') is not False:
        raise ValueError('Explicit upgrade lineage missing')
    return jar,sha

if __name__=='__main__':
    jar,sha=validate(Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3],sys.argv[4])
    print(json.dumps({'validated':True,'jar':str(jar),'sha256':sha,'scope':'1000 requested / 1032 authored blocks; preserved upgrade lineage'}))



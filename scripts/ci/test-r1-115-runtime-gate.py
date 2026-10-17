#!/usr/bin/env python3
"""Regression guard: R1-115 must execute disposable runtime on the exact candidate head.

A pull_request workflow normally checks out the synthetic merge ref while Actions metadata still
reports the PR head SHA. Release evidence must therefore select, build, run, and attest the actual
PR head explicitly; otherwise a green runtime can certify source that was never the candidate.
"""
from pathlib import Path
import re

workflow = Path('.github/workflows/r1-115-overnight-profile.yml').read_text(encoding='utf-8')
job_marker = '  ocean-vegetation-1k:\n'
start = workflow.index(job_marker)
# Match only the next top-level job key. A plain ``find('\\n  ')`` also matches every
# four-space-indented property inside this job and can silently truncate the inspected YAML.
next_job_match = re.search(r'(?m)^  [A-Za-z0-9_-]+:\s*$', workflow[start + len(job_marker):])
next_job = (start + len(job_marker) + next_job_match.start()) if next_job_match else len(workflow)
job = workflow[start:next_job]
job_header_end = job.find('\n    steps:')
job_header = job[:job_header_end if job_header_end != -1 else len(job)]

assert 'if:' not in job_header, (
    'R1-115 ocean-vegetation-1k has a job-level condition; production evidence must not be skipped'
)
assert 'CANDIDATE_SHA: ${{ github.event.pull_request.head.sha || github.sha }}' in job
assert 'ref: ${{ env.CANDIDATE_SHA }}' in job
assert 'test "$(git rev-parse HEAD)" = "$CANDIDATE_SHA"' in job
assert "'$CANDIDATE_SHA'" not in job, 'candidate SHA must not be accidentally single-quoted away from shell expansion'
assert '"$CANDIDATE_SHA" "$GITHUB_RUN_ID"' in job
assert 'EXPECTED_SOURCE_COMMIT: ${{ env.CANDIDATE_SHA }}' in job
assert "candidate = os.environ['CANDIDATE_SHA']" in job
assert "identity.get('sourceCommit') == candidate" in job
assert "restart.get('sourceCommit') == candidate" in job
assert "identity.get('sourceCommit') == '${{ github.sha }}'" not in job
assert 'Fresh 1k generation, strict acceptance, and saved-world restart' in workflow
assert 'Require clean runtime/restart and no vegetation placement failure' in workflow
assert 'Verify kelp and seagrass persisted in the saved 1k world' in workflow
print('R1-145 exact-head production runtime gate contract: PASS')

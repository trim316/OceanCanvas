#!/usr/bin/env python3
"""Regression guard: R1-115 must execute its disposable 1k runtime job on production PRs.

This is intentionally a text-level workflow contract so a future branch-name optimization cannot
silently turn a green workflow into focused-build-only evidence again.
"""
from pathlib import Path

workflow = Path('.github/workflows/r1-115-overnight-profile.yml').read_text(encoding='utf-8')
job_marker = '  ocean-vegetation-1k:\n'
start = workflow.index(job_marker)
next_job = workflow.find('\n  ', start + len(job_marker))
job_header = workflow[start: next_job if next_job != -1 else len(workflow)]

assert 'if:' not in job_header, (
    'R1-115 ocean-vegetation-1k has a job-level condition; production evidence must not be skipped'
)
assert 'Fresh 1k generation, strict acceptance, and saved-world restart' in workflow
assert 'Require clean runtime/restart and no vegetation placement failure' in workflow
assert 'Verify kelp and seagrass persisted in the saved 1k world' in workflow
print('R1-143 production runtime gate contract: PASS')

"""Create a read-only, source-bound report of this GitHub test run."""
from datetime import datetime
import html
import json
import os
from pathlib import Path
import urllib.request

repo = os.environ.get('GITHUB_REPOSITORY', 'trim316/OceanCanvas')
run_id = os.environ['GITHUB_RUN_ID']
source = os.environ['GITHUB_SHA']
headers = {'Accept':'application/vnd.github+json'}
if os.environ.get('GH_TOKEN'):
    headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
def fetch(path):
    request = urllib.request.Request(f'https://api.github.com/repos/{repo}/{path}', headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)
run = fetch(f'actions/runs/{run_id}')
if run['head_sha'] != source:
    raise ValueError('Report source does not match workflow run')
jobs = fetch(f'actions/runs/{run_id}/jobs?per_page=100')['jobs']
out = Path(os.environ.get('OC_STATUS_OUTPUT', 'test-status-report')); out.mkdir(parents=True,exist_ok=True)
rows = []
for job in jobs:
    elapsed = None
    if job.get('started_at') and job.get('completed_at'):
        elapsed = (datetime.fromisoformat(job['completed_at'].replace('Z','+00:00')) -
                   datetime.fromisoformat(job['started_at'].replace('Z','+00:00'))).total_seconds()
    rows.append(dict(name=job['name'], status=job['status'], conclusion=job['conclusion'],
                     jobWallSeconds=max(0, elapsed) if elapsed is not None else None, url=job['html_url']))
result = dict(runId=run_id, sourceCommit=source, status=run['status'], jobs=rows,
              snapshotAt=datetime.now().astimezone().isoformat(),
              scope='test status only; does not expand released size support')
(out/'status.json').write_text(json.dumps(result,indent=2))
table = ''.join(f'<tr><td><a href="{html.escape(row["url"])}">{html.escape(row["name"])}</a></td>'
                f'<td>{html.escape(row["conclusion"] or row["status"])}</td>'
                f'<td>{row["jobWallSeconds"] if row["jobWallSeconds"] is not None else "running/queued"}</td></tr>'
                for row in rows)
(out/'index.html').write_text(f'''<!doctype html><meta charset="utf-8">
<title>OceanCanvas test status</title><style>body{{font:16px system-ui;margin:32px;color:#172b3a}}
table{{border-collapse:collapse;width:100%}}td,th{{padding:12px;text-align:left;border-bottom:1px solid #ddd}}</style>
<h1>OceanCanvas test status</h1><p>Run {html.escape(run_id)} Â· source {html.escape(source)}</p>
<p>This is a status snapshot. Job wall time includes setup; it is not a generation benchmark.</p>
<table><tr><th>Test</th><th>Result</th><th>Job seconds</th></tr>{table}</table>
<p>Details, backlog trends and failure diagnostics remain attached to each test's artifacts.</p>
<p>Released size support is separate from diagnostic results.</p>''', encoding='utf-8')
print(json.dumps(result,indent=2))

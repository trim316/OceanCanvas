"""Hand an already tested candidate to runtime CI without duplicate active runs."""
import json
import os
import subprocess
import urllib.parse
import urllib.request

REPO = 'trim316/OceanCanvas'
WORKFLOWS = ('r1-145-exact-throughput.yml', 'production-scale-release-gate.yml')
INPUTS = ['production-src', 'scripts/ci/production-runtime.py', 'scripts/ci/production-scale.py',
          'scripts/ci/runtime_support.py', 'scripts/ci/runtime_stall.py',
          'scripts/ci/candidate_verifier.py', 'scripts/ci/scale_evidence.py',
          'scripts/ci/scale_geometry.py', 'scripts/ci/checkpoint_bundle.py',
          'scripts/ci/snapshot_replay.py', '.github/workflows/production-acceptance.yml',
          '.github/workflows/production-finalize.yml']


def api(path, method='GET', data=None):
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + '/' + path,
        data=None if data is None else json.dumps(data).encode(), method=method,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
        return json.loads(payload) if payload else None


def equivalent(source, head, workflow):
    dependencies = list(INPUTS)
    if workflow == 'production-scale-release-gate.yml':
        dependencies.append(':(glob)scripts/ci/test-*.py')
    return subprocess.run(['git', 'diff', '--quiet', source, head, '--',
        *dependencies, '.github/workflows/' + workflow], check=False).returncode == 0


def handoff(branch, head, request=api, same_inputs=equivalent):
    for workflow in WORKFLOWS:
        name = urllib.parse.quote(workflow, safe='')
        path = f'actions/workflows/{name}'
        runs = request(path + '/runs?per_page=100&branch=' + urllib.parse.quote(branch, safe=''))['workflow_runs']
        active = next((run for run in runs if run['status'] != 'completed'
                       and same_inputs(run['head_sha'], head, workflow)), None)
        if active:
            print(f"Runtime handoff: retaining equivalent active {workflow} run {active['id']}")
            continue
        request(path + '/dispatches', 'POST', dict(ref=branch))
        print(f'Runtime handoff: dispatched {workflow} for {branch} at {head}')


if __name__ == '__main__':
    branch = os.environ['GITHUB_REF_NAME']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    remote = subprocess.check_output(['git', 'ls-remote', 'origin', 'refs/heads/' + branch], text=True).split()[0]
    if head != remote:
        raise SystemExit('Branch moved after validation; refusing to dispatch untested source')
    handoff(branch, head)

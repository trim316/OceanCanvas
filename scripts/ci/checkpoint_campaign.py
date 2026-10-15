"""Validate a durable checkpoint campaign launch/resume request."""
import json
import os
from pathlib import Path
import re


def validate(data):
    data = dict(data)
    for name in ('candidate_run_id', 'checkpoint_run_id'):
        value = str(data.get(name, ''))
        if not re.fullmatch(r'[0-9]+', value) and not (name == 'checkpoint_run_id' and value == ''):
            raise ValueError('Invalid ' + name)
        data[name] = value
    if not re.fullmatch(r'[a-f0-9]{40}', data.get('source_commit', '')):
        raise ValueError('Invalid exact source')
    if not re.fullmatch(r'v[0-9]+(?:\.[0-9]+)+', data.get('build', '')):
        raise ValueError('Invalid build')
    if data.get('width') not in ('2000', '5000', '10000', '20000', '5000+20000'):
        raise ValueError('Invalid width')
    border = data.get('natural_border', False)
    if border not in (True, False, 'true', 'false'):
        raise ValueError('Invalid border flag')
    data['natural_border'] = 'true' if border in (True, 'true') else 'false'
    return {key: data[key] for key in ('candidate_run_id', 'checkpoint_run_id', 'source_commit', 'build', 'width', 'natural_border')}


if __name__ == '__main__':
    if os.environ['GITHUB_EVENT_NAME'] == 'push':
        data = json.loads(Path('release-tests/active-checkpoint-campaign.json').read_text(encoding='utf-8'))
    else:
        data = json.loads(os.environ['CHECKPOINT_INPUTS'])
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for name, value in validate(data).items():
            output.write(name + '=' + value + '\n')

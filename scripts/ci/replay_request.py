"""Validate saved-world comparison requests before allocating runtime runners."""
import json
import os
from pathlib import Path
import re


def validate(data):
    if not re.fullmatch(r'[0-9]+', str(data.get('parent_run_id', ''))):
        raise ValueError('Invalid parent run')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', data.get('parent_artifact', '')):
        raise ValueError('Invalid parent artifact')
    seconds = int(data.get('seconds', 180))
    if not 1 <= seconds <= 1800:
        raise ValueError('Invalid observation window')
    candidates = data.get('candidates')
    if isinstance(candidates, str):
        candidates = json.loads(candidates)
    if not isinstance(candidates, list) or not 1 <= len(candidates) <= 4:
        raise ValueError('Select one to four candidates')
    seen = set()
    for case in candidates:
        for key, pattern in [('name', r'[A-Za-z0-9_-]{1,64}'), ('run', r'[0-9]+'),
                             ('source', r'[a-f0-9]{40}'), ('build', r'v[0-9]+(?:\.[0-9]+)+')]:
            if not re.fullmatch(pattern, str(case.get(key, ''))):
                raise ValueError('Invalid candidate ' + key)
        if case.get('profile', 'preserve') not in ('preserve', 'quiet', 'overnight'):
            raise ValueError('Invalid diagnostic profile')
        if case['name'] in seen:
            raise ValueError('Duplicate candidate name')
        seen.add(case['name'])
    return dict(parent_run_id=str(data['parent_run_id']), parent_artifact=data['parent_artifact'],
                seconds=str(seconds), candidates=json.dumps(candidates))


if __name__ == '__main__':
    data = (json.loads(Path('release-tests/active-replay.json').read_text(encoding='utf-8'))
            if os.environ['GITHUB_EVENT_NAME'] == 'push' else json.loads(os.environ['REPLAY_INPUTS']))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for name, value in validate(data).items():
            output.write(name + '=' + value + '\n')

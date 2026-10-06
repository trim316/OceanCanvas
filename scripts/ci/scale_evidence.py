"""Durable UTC timing and installed-binary checks for segmented scale proofs."""
from datetime import datetime, timezone
import hashlib
import json


def open_timing(path, identity, resume, now=None):
    now = now or datetime.now(timezone.utc)
    if resume:
        if not path.is_file():
            raise ValueError('Checkpoint lacks durable UTC timing; preserve it, but do not certify elapsed time')
        timing = json.loads(path.read_text())
        if timing.get('identity') != identity:
            raise ValueError('Checkpoint timing identity mismatch')
        datetime.fromisoformat(timing['startedUtc'])
        return timing
    timing = dict(identity=identity, startedUtc=now.isoformat())
    path.write_text(json.dumps(timing, indent=2))
    return timing


def finish_timing(path, identity, max_hours, now=None):
    now = now or datetime.now(timezone.utc)
    timing = open_timing(path, identity, True, now)
    started = datetime.fromisoformat(timing['startedUtc'])
    elapsed = (now - started).total_seconds() / 3600
    timing.update(completedUtc=now.isoformat(), elapsedHours=elapsed)
    path.write_text(json.dumps(timing, indent=2))
    if elapsed < 0 or elapsed > max_hours:
        raise ValueError(f'Durable elapsed time {elapsed:.3f}h exceeds certified {max_hours}h window')
    return timing


def verify_installed_jar(path, digest):
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError('Installed candidate changed before saved-world restart')

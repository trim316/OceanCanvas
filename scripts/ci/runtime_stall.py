"""Pure conservative repeated physical-mutation stall detector.

Feed live console lines with monotonic arrival times only during completion
waiting. A returned verdict is a failure signal, never completion evidence.
"""
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class StallVerdict:
    chunk: str
    repeats: int
    stalled_seconds: float
    reason: str = 'Repeated physical mutation without observed terrain or lighting-finalizer advancement'


class PhysicalMutationStall:
    def __init__(self, minimum_repeats=6, minimum_seconds=360):
        if minimum_repeats < 6 or minimum_seconds < 360:
            raise ValueError('Guard must retain conservative six-repeat/six-minute thresholds')
        self.minimum_repeats = minimum_repeats
        self.minimum_seconds = minimum_seconds
        self.reset()

    def reset(self):
        self.terrain = -1
        self.finalized = -1
        self.mutations = {}
        self.completed = False
        self.last_now = None

    def observe(self, line, now):
        if self.last_now is not None and now < self.last_now:
            raise ValueError('Use monotonic arrival times; log timestamps may cross midnight')
        self.last_now = now
        if 'PREGEN-ACCEPTANCE-START ' in line:
            self.reset()
            self.last_now = now
        if 'PREGEN-ACCEPTANCE-DONE ' in line:
            self.completed = True
            self.mutations.clear()
            return None
        if self.completed:
            return None
        terrain = re.search(r'(\d+)/(\d+) chunks terrain-confirmed', line)
        finalized = re.search(r'LIGHT-DIAG SUMMARY .*?\bfinalized=(\d+)', line)
        progressed = False
        if terrain and int(terrain[1]) > self.terrain:
            self.terrain = int(terrain[1])
            progressed = True
        if finalized and int(finalized[1]) > self.finalized:
            self.finalized = int(finalized[1])
            progressed = True
        if progressed:
            self.mutations.clear()
        mutation = re.search(r'\bchunk=(-?\d+,-?\d+) classification=POST_CERT_PHYSICAL_MUTATION\b', line)
        if not mutation:
            return None
        # Require repeated fresh mutation observations across the full interval;
        # mere silence, one old repair, or different isolated chunks cannot fail.
        chunk = mutation[1]
        first, count = self.mutations.get(chunk, (now, 0))
        count += 1
        self.mutations[chunk] = (first, count)
        duration = now - first
        if count >= self.minimum_repeats and duration >= self.minimum_seconds:
            return StallVerdict(chunk, count, duration)
        return None

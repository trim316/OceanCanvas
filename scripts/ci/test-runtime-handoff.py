import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('handoff', Path(__file__).with_name('dispatch-runtime-after-build.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HandoffTest(unittest.TestCase):
    def exercise(self, runs, same):
        posted = []
        def api(path, method='GET', data=None):
            if method == 'GET': return dict(workflow_runs=runs)
            posted.append((path, data))
        module.handoff('work/fix', 'new', api, lambda *args: same)
        return posted

    def test_production_equivalence_includes_fixture_dependencies(self):
        with patch.object(module.subprocess, 'run') as run:
            run.return_value.returncode = 0
            module.equivalent('old', 'new', 'production-scale-release-gate.yml')
            self.assertIn(':(glob)scripts/ci/test-*.py', run.call_args.args[0])
            module.equivalent('old', 'new', 'r1-145-exact-throughput.yml')
            self.assertNotIn(':(glob)scripts/ci/test-*.py', run.call_args.args[0])

    def test_no_runs_dispatches_both(self):
        self.assertEqual(len(self.exercise([], False)), 2)

    def test_matching_active_runs_are_retained(self):
        self.assertEqual(self.exercise([dict(id=1,status='in_progress',head_sha='old')], True), [])

    def test_different_candidate_is_dispatched(self):
        self.assertEqual(len(self.exercise([dict(id=1,status='in_progress',head_sha='old')], False)), 2)

    def test_completed_failure_does_not_leave_handoff_idle(self):
        self.assertEqual(len(self.exercise([dict(id=1,status='completed',head_sha='old')], True)), 2)


if __name__ == '__main__': unittest.main()

import importlib.util
import hashlib
import json
from pathlib import Path
import unittest

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

base = Path(__file__).parent
fixtures = load('fixtures', base / 'test-production-candidate.py')
publisher = load('publisher', base / 'publish-production-500.py')

class LimitedReleaseTest(fixtures.CandidateIdentityTest):
    def setUp(self):
        super().setUp()
        self.evidence = self.directory / 'evidence'
        self.evidence.mkdir()
        self.identity = dict(jarSha256=hashlib.sha256(self.jar.read_bytes()).hexdigest(),
                             sourceCommit=fixtures.COMMIT, build=fixtures.BUILD,
                             freshWorld=True, targetBlocks=500, targetChunks=1024)
        self.restart = dict(passed=True, build=fixtures.BUILD,
                            sourceCommit=fixtures.COMMIT, jarSha256=self.identity['jarSha256'])
        self.write_evidence()

    def write_evidence(self, passed=True):
        for name, data in [('identity', self.identity), ('restart', self.restart),
                           ('acceptance', dict(passed=passed))]:
            (self.evidence / f'{name}.json').write_text(json.dumps(data))

    def validate(self):
        return publisher.validate(self.directory, self.evidence, fixtures.BUILD, fixtures.COMMIT)

    def test_limited_release_pass(self):
        self.validate()

    def test_larger_scope_rejected(self):
        self.identity['targetBlocks'] = 20000
        self.write_evidence()
        with self.assertRaises(ValueError): self.validate()

    def test_failed_acceptance_rejected(self):
        self.write_evidence(passed=False)
        with self.assertRaises(ValueError): self.validate()

    def test_wrong_restart_candidate_rejected(self):
        self.restart['jarSha256'] = '0' * 64
        self.write_evidence()
        with self.assertRaises(ValueError): self.validate()

if __name__ == '__main__':
    unittest.main()

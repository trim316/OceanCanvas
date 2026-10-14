"""Exercise immutable snapshot forks and candidate isolation without Minecraft."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from snapshot_replay import write_snapshot, verify_snapshot, fork_snapshot


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.parent = self.root / 'parent'
        for folder in ('world', 'config'):
            (self.parent / 'server' / folder).mkdir(parents=True)
        (self.parent / 'server/world/level.dat').write_bytes(b'original durable job')
        (self.parent / 'server/config/oceancanvas.properties').write_text('canvasSize=20000')
        self.build, self.source = 'v253.125.80', 'a' * 40
        self.candidate = self.root / 'candidate'
        self.candidate.mkdir()
        jar = self.candidate / f'oceancanvas-26.2-{self.build}.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps(dict(id='oceancanvas', version='26.2-' + self.build)))
            archive.writestr('net/oceancanvas/mod/OceanCanvas.class', b'candidate fixture')
        (self.candidate / 'candidate.properties').write_text(f'sourceCommit={self.source}\nruntimeBuild={self.build}\n')
        (self.candidate / 'JAR-SHA256.txt').write_text(hashlib.sha256(jar.read_bytes()).hexdigest())
        write_snapshot(self.parent, dict(sourceCommit='b'*40, jarSha256='c'*64), 0)

    def fork(self, name='fork'):
        return fork_snapshot(self.parent, self.root / name, self.candidate, self.build, self.source)

    def test_two_variants_share_parent_but_never_mutate_it(self):
        before = verify_snapshot(self.parent)
        _, identity = self.fork('a')
        self.fork('b')
        (self.root / 'a/server/world/level.dat').write_bytes(b'repaired by A')
        self.assertEqual((self.root / 'b/server/world/level.dat').read_bytes(), b'original durable job')
        self.assertEqual(verify_snapshot(self.parent), before)
        self.assertFalse(identity['freshWorld'])
        self.assertEqual(identity['releaseVerdict'], 'HOLD')
        self.assertNotIn('passed', identity)
        self.assertNotEqual(identity['sourceCommit'], identity['parentIdentity']['sourceCommit'])

    def test_tampered_world_rejected_before_output_created(self):
        (self.parent / 'server/world/level.dat').write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.fork()
        self.assertFalse((self.root / 'fork').exists())

    def test_extra_file_rejected(self):
        (self.parent / 'server/world/extra').write_bytes(b'extra')
        with self.assertRaises(ValueError): self.fork()

    def test_unclean_stop_rejected(self):
        with self.assertRaises(ValueError): write_snapshot(self.parent, {}, -9)

    def test_wrong_candidate_rejected(self):
        with self.assertRaises(ValueError):
            fork_snapshot(self.parent, self.root / 'fork', self.candidate, self.build, 'd'*40)
        self.assertFalse((self.root / 'fork').exists())

    def test_parent_and_existing_output_rejected(self):
        with self.assertRaises(ValueError):
            fork_snapshot(self.parent, self.parent / 'nested', self.candidate, self.build, self.source)
        self.fork()
        with self.assertRaises(ValueError): self.fork()


if __name__ == '__main__':
    unittest.main()

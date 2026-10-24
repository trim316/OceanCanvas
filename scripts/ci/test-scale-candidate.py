#!/usr/bin/env python3
"""Exercise the actual shared scale candidate verifier without launching a server."""
import ast
import hashlib
import json
import pathlib
import re
import tempfile
import unittest
import zipfile
import os
import runpy
from unittest.mock import patch

from candidate_verifier import verify_candidate as verify
BUILD = 'v253.125.59'
COMMIT = 'a' * 40


class CandidateIdentityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = pathlib.Path(self.tmp.name)
        self.jar = self.directory / f'oceancanvas-26.2-{BUILD}.jar'
        self.manifest = self.directory / 'candidate.properties'
        self.manifest.write_text(f'sourceCommit={COMMIT}\nruntimeBuild={BUILD}\n')
        self.write_jar()

    def write_jar(self, version=f'26.2-{BUILD}', mod_id='oceancanvas', entrypoint=True):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps(dict(id=mod_id, version=version)))
            if entrypoint:
                archive.writestr('net/oceancanvas/mod/OceanCanvas.class', b'test fixture')
        digest = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        (self.directory / 'JAR-SHA256.txt').write_text(digest + '  ' + self.jar.name + '\n')

    def rejected(self, build=BUILD, source=COMMIT):
        with self.assertRaises((ValueError, FileNotFoundError, KeyError)):
            verify(self.directory, build, source)

    def test_exact_candidate(self):
        jar, digest = verify(self.directory, BUILD, COMMIT)
        self.assertEqual(jar, self.jar)
        self.assertEqual(digest, hashlib.sha256(jar.read_bytes()).hexdigest())

    def test_old_jar_renamed_to_new_candidate(self):
        self.write_jar(version='26.2-v253.125.56')
        self.rejected()

    def test_checksum_preserving_wrong_mod(self):
        self.write_jar(mod_id='canvas')
        self.rejected()

    def test_tampered_jar(self):
        with self.jar.open('ab') as target:
            target.write(b'tamper')
        self.rejected()

    def test_wrong_source(self):
        self.rejected(source='b' * 40)

    def test_wrong_manifest_build(self):
        self.manifest.write_text(f'sourceCommit={COMMIT}\nruntimeBuild=v253.125.56\n')
        self.rejected()

    def test_duplicate_source_cannot_override(self):
        self.manifest.write_text(f'sourceCommit={COMMIT}\nsourceCommit={"b" * 40}\n')
        self.rejected()

    def test_multiple_candidate_jars(self):
        (self.directory / 'stale.jar').write_bytes(self.jar.read_bytes())
        self.rejected()

    def test_missing_identity_cannot_fall_back(self):
        self.rejected(build=None)
        self.rejected(source=None)

    def test_missing_production_entrypoint(self):
        self.write_jar(entrypoint=False)
        self.rejected()


    def test_missing_manifest_build_rejected(self):
        self.manifest.write_text(f'sourceCommit={COMMIT}\n')
        self.rejected()

    def test_duplicate_build_rejected(self):
        self.manifest.write_text(f'sourceCommit={COMMIT}\nruntimeBuild={BUILD}\nruntimeBuild={BUILD}\n')
        self.rejected()

    def test_malformed_requested_identity_rejected(self):
        self.rejected(source='short')
        self.rejected(build='old-name')

    def test_invalid_scale_candidate_preserves_existing_checkpoint(self):
        # Run the actual harness startup with an invalid candidate. It must fail
        # before server setup or rewriting identity/checkpoint/timing metadata.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            candidate = root / 'production-candidate'
            candidate.mkdir()
            for item in self.directory.iterdir():
                (candidate / item.name).write_bytes(item.read_bytes())
            (candidate / 'candidate.properties').write_text(f'sourceCommit={COMMIT}\nruntimeBuild=wrong\n')
            evidence = root / 'production-runtime-evidence'
            (evidence / 'server/world').mkdir(parents=True)
            for name in ('checkpoint.json', 'checkpoint-timing.json', 'identity.json', 'server/world/level.dat'):
                (evidence / name).write_bytes(b'preserve existing checkpoint')
            before = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            with patch.dict(os.environ, EXPECTED_BUILD=BUILD, EXPECTED_SOURCE_COMMIT=COMMIT,
                            OC_TEST_WIDTH='500', OC_RESUME='true', OC_EVIDENCE_DIR='production-runtime-evidence'), \
                    patch('pathlib.Path.cwd', return_value=root):
                with self.assertRaises(ValueError):
                    runpy.run_path(str(pathlib.Path(__file__).with_name('production-scale.py')))
            after = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            self.assertEqual(before, after)

if __name__ == '__main__':
    unittest.main(verbosity=2)

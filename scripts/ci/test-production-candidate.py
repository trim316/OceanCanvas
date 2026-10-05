#!/usr/bin/env python3
"""Exercise the runtime harness's artifact checks without launching a server."""
import ast
import hashlib
import json
import pathlib
import re
import tempfile
import unittest
import zipfile

source = pathlib.Path(__file__).with_name('production-runtime.py')
module = ast.parse(source.read_text())
validator = next(n for n in module.body if isinstance(n, ast.FunctionDef) and n.name == 'verify_candidate')
namespace = dict(hashlib=hashlib, json=json, re=re, zipfile=zipfile)
exec(compile(ast.Module(body=[validator], type_ignores=[]), str(source), 'exec'), namespace)
verify = namespace['verify_candidate']
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


if __name__ == '__main__':
    unittest.main(verbosity=2)

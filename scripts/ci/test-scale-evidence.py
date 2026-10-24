import hashlib
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
from scale_evidence import open_timing, finish_timing, verify_installed_jar


class ScaleEvidenceTest(unittest.TestCase):
    def test_resume_preserves_original_utc_and_rejects_day_wrap(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'timing.json'
            start = datetime(2026, 10, 6, 23, 30, tzinfo=timezone.utc)
            identity = {'jarSha256': 'abc', 'sourceCommit': 'def'}
            original = open_timing(path, identity, False, start)
            self.assertEqual(open_timing(path, identity, True, start + timedelta(hours=25)), original)
            with self.assertRaises(ValueError):
                finish_timing(path, identity, 8, start + timedelta(hours=25))

    def test_exact_identity_and_missing_legacy_timing_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'timing.json'
            with self.assertRaises(ValueError): open_timing(path, {}, True)
            open_timing(path, {'sourceCommit': 'a'}, False)
            with self.assertRaises(ValueError): open_timing(path, {'sourceCommit': 'b'}, True)

    def test_short_segment_and_installed_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'timing.json'
            start = datetime.now(timezone.utc)
            open_timing(path, {}, False, start)
            self.assertEqual(finish_timing(path, {}, 8, start + timedelta(minutes=30))['elapsedHours'], .5)
            jar = Path(directory) / 'mod.jar'
            jar.write_bytes(b'candidate')
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            verify_installed_jar(jar, digest)
            jar.write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_installed_jar(jar, digest)


if __name__ == '__main__': unittest.main()

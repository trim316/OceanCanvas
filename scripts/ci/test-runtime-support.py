import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from runtime_support import bind_checkpoint, cached_download

class RuntimeSupportTest(unittest.TestCase):
    def test_checkpoint_exact_candidate_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'checkpoint.json'
            original = dict(jarSha256='a'*64, sourceCommit='b'*40, targetBlocks=5000, seed=4182026)
            bind_checkpoint(path, original, False)
            bind_checkpoint(path, original, True)
            for key in original:
                changed = dict(original, **{key: 'changed'})
                with self.assertRaises(ValueError): bind_checkpoint(path, changed, True)
            with self.assertRaises(ValueError): bind_checkpoint(path, original, False)

    def test_missing_checkpoint_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError): bind_checkpoint(Path(tmp)/'absent', {}, True)

    def test_download_cache_and_corruption_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            origin = root / 'origin.jar'
            origin.write_bytes(b'fixture binary')
            destination = root / 'copied.jar'
            cached_download(origin.as_uri(), destination, root/'cache')
            with patch('runtime_support.urllib.request.urlopen', side_effect=AssertionError('unnecessary download')):
                cached_download(origin.as_uri(), destination, root/'cache')
            for p in (root/'cache').iterdir():
                if p.suffix != '.sha256': p.write_bytes(b'corrupt')
            cached_download(origin.as_uri(), destination, root/'cache')
            self.assertEqual(destination.read_bytes(), origin.read_bytes())

if __name__ == '__main__': unittest.main()

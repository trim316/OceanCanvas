#!/usr/bin/env python3
"""Execute the real Actions checkpoint publication decision on tiny disposable fixtures."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/current-2k-radius-controller.yml"
SOURCE = WORKFLOW.read_text()
BLOCK = SOURCE.split("      - name: Verify new checkpoint before allowing expensive artifact upload", 1)[1].split("      - name: Preserve exact resumable checkpoint", 1)[0]
match = re.search(r"python3 - <<'PY'\n(.*?)\n          PY", BLOCK, re.S)
if not match:
    raise RuntimeError("Cannot locate actual checkpoint publication Python in workflow")
CODE = "\n".join(line[10:] for line in match.group(1).splitlines())

class CheckpointPublicationTest(unittest.TestCase):
    def exercise(self, predecessor_number, predecessor_digest, number, authenticated=True, present=True):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = root / "checkpoint-2k-radius-evidence"
            bundle.mkdir()
            if present:
                (bundle / "checkpoint-status.json").write_text(json.dumps({"checkpointNumber": number}))
                (bundle / "checkpoint-manifest.json").write_text('{"new":true}')
                (bundle / "checkpoint.json").write_text('{"runtime":"fixture"}')
            output = root / "output"
            verifier = types.ModuleType("checkpoint_bundle")
            calls = []
            def verify(path, identity):
                calls.append((path, identity))
                if not authenticated:
                    raise ValueError("bad manifest")
            verifier.verify_manifest = verify
            env = {"GITHUB_OUTPUT": str(output), "PREVIOUS_NUMBER": str(predecessor_number),
                   "PREVIOUS_MANIFEST": predecessor_digest}
            old = Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ, env), patch.dict(sys.modules, {"checkpoint_bundle": verifier}):
                    if authenticated:
                        exec(CODE, {"__name__": "__main__"})
                    else:
                        with self.assertRaisesRegex(ValueError, "bad manifest"):
                            exec(CODE, {"__name__": "__main__"})
            finally:
                os.chdir(old)
            return output.read_text() if output.exists() else "", calls

    def test_unchanged_predecessor_not_uploaded(self):
        digest = hashlib.sha256(b'{"new":true}').hexdigest()
        result, calls = self.exercise(396, digest, 396)
        self.assertIn("publish=false", result)
        self.assertFalse(calls)

    def test_advanced_authenticated_checkpoint_uploaded(self):
        result, calls = self.exercise(396, "older-manifest-digest", 397)
        self.assertIn("publish=true", result)
        self.assertEqual(len(calls), 1)

    def test_invalid_manifest_refuses_publication(self):
        result, calls = self.exercise(396, "older-manifest-digest", 397, authenticated=False)
        self.assertNotIn("publish=true", result)
        self.assertEqual(len(calls), 1)

    def test_first_checkpoint_can_publish(self):
        result, calls = self.exercise("", "", 1)
        self.assertIn("publish=true", result)
        self.assertEqual(len(calls), 1)

    def test_no_checkpoint_never_publishes(self):
        result, calls = self.exercise("", "", "", present=False)
        self.assertIn("publish=false", result)
        self.assertFalse(calls)

if __name__ == "__main__":
    unittest.main()

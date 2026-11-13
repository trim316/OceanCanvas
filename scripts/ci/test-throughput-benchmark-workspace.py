#!/usr/bin/env python3
"""Focused disposable-workspace refusal regressions; no Minecraft launch."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("full-canvas-throughput-benchmark.py")
spec = importlib.util.spec_from_file_location("throughput_benchmark_under_test", SCRIPT)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)

class WorkspaceRefusalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = self.root / "runtime"
        self.world = self.run / "world"
        self.output = self.root / "evidence"
        for name, value in (("RUN", self.run), ("WORLD", self.world), ("OUTPUT", self.output)):
            patcher = patch.object(bench, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def assert_refuses_without_overwrite(self, path):
        before = path.read_bytes() if path.is_file() else None
        with self.assertRaises(RuntimeError):
            bench.configure()
        if before is not None:
            self.assertEqual(path.read_bytes(), before)

    def test_existing_world_refused(self):
        self.world.mkdir(parents=True)
        self.assert_refuses_without_overwrite(self.world)

    def test_dangling_world_symlink_refused(self):
        self.run.mkdir()
        self.world.symlink_to(self.root / "missing-world", target_is_directory=True)
        self.assert_refuses_without_overwrite(self.world)

    def test_existing_result_refused(self):
        self.output.mkdir()
        result = self.output / "result.json"
        result.write_text('{"verdict":"PASS","previous":true}')
        self.assert_refuses_without_overwrite(result)

    def test_existing_server_config_refused(self):
        self.run.mkdir()
        config = self.run / "server.properties"
        config.write_text("prior-world=do-not-touch\n")
        self.assert_refuses_without_overwrite(config)

    def test_symlinked_runtime_refused(self):
        target = self.root / "unrelated"
        target.mkdir()
        self.run.symlink_to(target, target_is_directory=True)
        self.assert_refuses_without_overwrite(self.run)

    def test_fresh_disposable_workspace_configures(self):
        bench.configure()
        self.assertTrue(self.world.parent.joinpath("server.properties").is_file())
        self.assertFalse(self.world.exists())
        self.assertTrue(self.output.is_dir())

if __name__ == "__main__":
    unittest.main()

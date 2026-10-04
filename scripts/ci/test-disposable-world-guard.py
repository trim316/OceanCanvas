#!/usr/bin/env python3
"""Deterministic R1-31 tests for the cloud disposable-world path preflight."""
from __future__ import annotations

import pathlib
import tempfile

from disposable_world_guard import DisposableWorldRefusal, require_pristine_disposable_world


def expect_refusal(label, fn):
    try:
        fn()
    except DisposableWorldRefusal:
        return
    raise AssertionError(label + " did not refuse")


def main():
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-31-") as td:
        workspace = pathlib.Path(td).resolve()
        project = workspace / "runtime-src"
        project.mkdir()
        run_root = project / "run"
        world = run_root / "world"
        hosted = {
            "GITHUB_ACTIONS": "true",
            "GITHUB_WORKSPACE": str(workspace),
            "RUNNER_ENVIRONMENT": "github-hosted",
        }

        # Success is purely read-only: no run/world path may be created.
        require_pristine_disposable_world(project, run_root, world, hosted)
        assert not run_root.exists()
        assert not world.exists()

        expect_refusal(
            "local invocation",
            lambda: require_pristine_disposable_world(
                project, run_root, world,
                {"GITHUB_WORKSPACE": str(workspace), "RUNNER_ENVIRONMENT": "github-hosted"},
            ),
        )
        expect_refusal(
            "self-hosted runner",
            lambda: require_pristine_disposable_world(
                project, run_root, world,
                {"GITHUB_ACTIONS": "true", "GITHUB_WORKSPACE": str(workspace),
                 "RUNNER_ENVIRONMENT": "self-hosted"},
            ),
        )
        expect_refusal(
            "unexpected project root",
            lambda: require_pristine_disposable_world(
                workspace / "other-project", workspace / "other-project" / "run",
                workspace / "other-project" / "run" / "world", hosted,
            ),
        )
        expect_refusal(
            "unexpected world path",
            lambda: require_pristine_disposable_world(
                project, project / "run-alt", project / "run-alt" / "world", hosted,
            ),
        )

        run_root.mkdir()
        expect_refusal(
            "pre-existing empty run root",
            lambda: require_pristine_disposable_world(project, run_root, world, hosted),
        )
        assert run_root.exists()
        assert list(run_root.iterdir()) == []

    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-31-world-") as td:
        workspace = pathlib.Path(td).resolve()
        project = workspace / "runtime-src"
        world = project / "run" / "world"
        world.mkdir(parents=True)
        (world / "level.dat").write_bytes(b"do-not-touch")
        hosted = {
            "GITHUB_ACTIONS": "true",
            "GITHUB_WORKSPACE": str(workspace),
            "RUNNER_ENVIRONMENT": "github-hosted",
        }
        before = (world / "level.dat").read_bytes()
        expect_refusal(
            "pre-existing world",
            lambda: require_pristine_disposable_world(project, project / "run", world, hosted),
        )
        assert (world / "level.dat").read_bytes() == before

    print("R1-31 disposable-world preflight PASS")


if __name__ == "__main__":
    main()

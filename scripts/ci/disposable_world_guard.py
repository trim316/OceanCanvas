#!/usr/bin/env python3
"""Read-only preflight that proves a hosted Minecraft proof starts from a pristine disposable world path."""
from __future__ import annotations

import os
import pathlib
from collections.abc import Mapping


class DisposableWorldRefusal(RuntimeError):
    pass


def _resolved(path: pathlib.Path) -> pathlib.Path:
    return path.expanduser().resolve(strict=False)


def require_pristine_disposable_world(
    project: pathlib.Path,
    run_root: pathlib.Path,
    world: pathlib.Path,
    environ: Mapping[str, str] | None = None,
) -> None:
    """Fail closed before any proof file/world creation.

    The one-chunk hosted proof is intentionally cloud-only. It may create only
    <GITHUB_WORKSPACE>/runtime-src/run/world, and only when the entire run root
    is absent. A reused run directory can contain a real or interrupted world
    whose provenance this process cannot prove, so even an empty-looking
    existing run root is refused rather than cleaned or reused.
    """
    env = os.environ if environ is None else environ
    if env.get("GITHUB_ACTIONS") != "true":
        raise DisposableWorldRefusal("disposable Minecraft proof requires GitHub Actions")

    runner_environment = env.get("RUNNER_ENVIRONMENT", "")
    if runner_environment and runner_environment != "github-hosted":
        raise DisposableWorldRefusal(
            "disposable Minecraft proof refuses non-github-hosted runner environment"
        )

    workspace_text = env.get("GITHUB_WORKSPACE", "").strip()
    if not workspace_text:
        raise DisposableWorldRefusal("GITHUB_WORKSPACE is required")

    workspace = _resolved(pathlib.Path(workspace_text))
    project_resolved = _resolved(project)
    expected_project = _resolved(workspace / "runtime-src")
    if project_resolved != expected_project:
        raise DisposableWorldRefusal(
            "unexpected project path; disposable proof may only use GITHUB_WORKSPACE/runtime-src"
        )

    run_resolved = _resolved(run_root)
    world_resolved = _resolved(world)
    expected_run = _resolved(project_resolved / "run")
    expected_world = _resolved(expected_run / "world")
    if run_resolved != expected_run or world_resolved != expected_world:
        raise DisposableWorldRefusal(
            "unexpected disposable world path; expected runtime-src/run/world"
        )

    # Refuse any pre-existing run root rather than attempting to classify,
    # delete, archive, or mutate it. This keeps the preflight non-destructive.
    if run_root.exists():
        raise DisposableWorldRefusal(
            "runtime-src/run already exists; refusing possible pre-existing world"
        )

    # Resolve-parent containment is checked without creating anything.
    if workspace not in project_resolved.parents:
        raise DisposableWorldRefusal("project path escapes GitHub workspace")

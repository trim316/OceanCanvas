#!/usr/bin/env python3
import importlib.util
import json
import os
import pathlib
import tempfile
from types import SimpleNamespace

MODULE_PATH = pathlib.Path(__file__).with_name("hosted-fresh-repeat-proof.py")
spec = importlib.util.spec_from_file_location("fresh_repeat", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def member(root: pathlib.Path, operation: str, marker: str, seed: str = "4182026"):
    root.mkdir(parents=True)
    (root / "VERDICT.json").write_text(json.dumps({"verdict": "PASS", "world_seed": seed}) + "\n")
    (root / "operation.properties").write_text("operationId=" + operation + "\n")
    (root / "archive.bin").write_bytes(marker.encode("ascii"))


def must_fail(fn, needle):
    try:
        fn()
    except RuntimeError as exc:
        assert needle in str(exc), (needle, str(exc))
    else:
        raise AssertionError("expected failure containing " + needle)


with tempfile.TemporaryDirectory() as td:
    base = pathlib.Path(td)
    first = base / "first"
    second = base / "second"
    member(first, "op-first", "immutable-a", "4182026")
    member(second, "op-second", "independent-b", "4182031")
    sealed = mod.tree_digest(first)
    report = mod.verify_pair(first, second, sealed)
    assert report["verdict"] == "PASS"
    assert report["distinct_world_seed"] is True
    assert report["distinct_operation_identity"] is True
    assert report["first_evidence_immutable"] is True

    # Recreating the same deterministic seed is not an independent fresh-world
    # repeat even if the wrapper directory itself is new.
    second_verdict = second / "VERDICT.json"
    second_verdict.write_text(json.dumps({"verdict": "PASS", "world_seed": "4182026"}) + "\n")
    must_fail(lambda: mod.verify_pair(first, second, sealed), "reused disposable world seed")
    second_verdict.write_text(json.dumps({"verdict": "PASS", "world_seed": "4182031"}) + "\n")

    # Identity reuse must never count as a fresh repeat.
    (second / "operation.properties").write_text("operationId=op-first\n")
    must_fail(lambda: mod.verify_pair(first, second, sealed), "reused operation/world identity")
    (second / "operation.properties").write_text("operationId=op-second\n")

    # Any mutation of the first evidence after it was sealed must fail closed.
    (first / "archive.bin").write_bytes(b"mutated")
    must_fail(lambda: mod.verify_pair(first, second, sealed), "first proof evidence changed")

    # A non-PASS member cannot be promoted into repeat evidence.
    member(base / "failed", "op-third", "c", "4182032")
    (base / "failed" / "VERDICT.json").write_text(json.dumps({"verdict": "FAIL", "world_seed": "4182032"}) + "\n")
    must_fail(lambda: mod.verify_pair(base / "failed", second, mod.tree_digest(base / "failed")), "not PASS")

# Regression for the real R1-72 first failure: the wrapper must never rewrite
# GITHUB_WORKSPACE to an evidence subdirectory. The child needs the actual
# checkout workspace so disposable_world_guard can prove runtime-src/run/world.
with tempfile.TemporaryDirectory() as td:
    base = pathlib.Path(td)
    github_workspace = base / "workspace"
    github_workspace.mkdir()
    repeat_root = base / "repeat"
    original_output_root = mod.OUTPUT_ROOT
    original_subprocess_run = mod.subprocess.run
    previous_workspace = os.environ.get("GITHUB_WORKSPACE")
    seen_workspace = []
    seen_seed = []

    def fake_run(args, cwd, env):
        assert cwd == mod.ROOT
        assert env["GITHUB_WORKSPACE"] == str(github_workspace)
        assert env["OCEANCANVAS_WORLD_SEED"] == "4182026"
        seen_workspace.append(env["GITHUB_WORKSPACE"])
        seen_seed.append(env["OCEANCANVAS_WORLD_SEED"])
        member(github_workspace / "hosted-minecraft-proof", "op-child", "child-evidence", env["OCEANCANVAS_WORLD_SEED"])
        return SimpleNamespace(returncode=0)

    try:
        os.environ["GITHUB_WORKSPACE"] = str(github_workspace)
        mod.OUTPUT_ROOT = repeat_root
        mod.subprocess.run = fake_run
        evidence = mod.run_member("first", "4182026")
        assert seen_workspace == [str(github_workspace)]
        assert seen_seed == ["4182026"]
        assert evidence == repeat_root / "first" / "hosted-minecraft-proof"
        assert evidence.is_dir()
        assert not (github_workspace / "hosted-minecraft-proof").exists()

        # Stale shared child evidence is ambiguous and must be refused, not
        # deleted or silently folded into a new repeat member.
        (github_workspace / "hosted-minecraft-proof").mkdir()
        must_fail(
            lambda: mod.run_member("blocked", "4182031"),
            "refuses preexisting hosted proof evidence directory",
        )
    finally:
        mod.OUTPUT_ROOT = original_output_root
        mod.subprocess.run = original_subprocess_run
        if previous_workspace is None:
            os.environ.pop("GITHUB_WORKSPACE", None)
        else:
            os.environ["GITHUB_WORKSPACE"] = previous_workspace

source = MODULE_PATH.read_text(encoding="utf-8")
assert 'env["GITHUB_WORKSPACE"] =' not in source
assert 'env["OCEANCANVAS_WORLD_SEED"] = world_seed' in source
assert mod.FIRST_WORLD_SEED != mod.SECOND_WORLD_SEED
print("R1-72 fresh-repeat evidence regressions PASS")

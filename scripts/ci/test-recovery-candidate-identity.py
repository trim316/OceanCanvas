#!/usr/bin/env python3
"""Deterministic R1-49 regression for immutable recovery candidate identity."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("recovery_candidate_identity.py")
spec = importlib.util.spec_from_file_location("recovery_candidate_identity", SCRIPT)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load recovery_candidate_identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


SOURCE = "a" * 40
RUN_ID = "424242"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def fixture(root: Path) -> None:
    gradle = root / "runtime-src/gradle.properties"
    gradle.parent.mkdir(parents=True, exist_ok=True)
    gradle.write_text(
        "minecraft_version=26.2\n"
        "loader_version=0.19.3\n"
        "fabric_version=0.157.0+26.2\n"
        "mod_version=26.2-core-v0.2.26-recovery.3\n",
        encoding="utf-8",
    )
    for index, relative in enumerate(identity.FORMAT_FILES):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes((f"format-source-{index}\n").encode("utf-8"))
    libs = root / "runtime-src/build/libs"
    libs.mkdir(parents=True, exist_ok=True)
    (libs / "oceancanvas-core-26.2-core-v0.2.26-recovery.3.jar").write_bytes(
        b"deterministic-test-jar\x00bytes"
    )


def expect_refusal(action, message: str) -> None:
    refused = False
    try:
        action()
    except identity.IdentityError:
        refused = True
    check(refused, message)


def test_exact_identity() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-49-") as tmp:
        root = Path(tmp)
        fixture(root)
        candidate = root / "candidate"
        manifest = identity.assemble(root, candidate, SOURCE, RUN_ID)
        fields = identity.verify(root, candidate, SOURCE, RUN_ID)
        check(manifest == candidate / "candidate.properties", "manifest path")
        check(fields["schema"] == "1", "manifest schema")
        check(fields["runtime"] == "26.2-core-v0.2.26-recovery.3", "runtime identity")
        check(fields["coreVersion"] == "core-v0.2.26-recovery.3", "core version identity")
        check(fields["minecraftVersion"] == "26.2", "Minecraft identity")
        check(fields["loaderVersion"] == "0.19.3", "Loader identity")
        check(fields["fabricVersion"] == "0.157.0+26.2", "Fabric API identity")
        check(fields["sourceCommit"] == SOURCE, "source SHA identity")
        check(fields["buildRunId"] == RUN_ID, "build run identity")
        check(fields["recoveryScope"] == "one-chunk-only", "recovery scope")
        check(fields["recoveryFormatVersion"] == "1", "recovery format version")
        check(len(fields["recoveryFormatSha256"]) == 64, "recovery format SHA")
        check(len(fields["jarSha256"]) == 64, "JAR SHA")


def test_jar_drift_refused() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-49-jar-") as tmp:
        root = Path(tmp)
        fixture(root)
        candidate = root / "candidate"
        identity.assemble(root, candidate, SOURCE, RUN_ID)
        jar = next(candidate.glob("*.jar"))
        jar.write_bytes(jar.read_bytes() + b"tamper")
        expect_refusal(
            lambda: identity.verify(root, candidate, SOURCE, RUN_ID),
            "candidate JAR drift must be refused",
        )


def test_recovery_format_drift_refused() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-49-format-") as tmp:
        root = Path(tmp)
        fixture(root)
        candidate = root / "candidate"
        identity.assemble(root, candidate, SOURCE, RUN_ID)
        format_source = root / identity.FORMAT_FILES[0]
        format_source.write_text("changed-format-source\n", encoding="utf-8")
        expect_refusal(
            lambda: identity.verify(root, candidate, SOURCE, RUN_ID),
            "recovery format source drift must be refused",
        )


def test_duplicate_manifest_field_refused_without_repair() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-49-duplicate-") as tmp:
        root = Path(tmp)
        fixture(root)
        candidate = root / "candidate"
        identity.assemble(root, candidate, SOURCE, RUN_ID)
        manifest = candidate / "candidate.properties"
        original = manifest.read_bytes()
        manifest.write_bytes(original + b"sourceCommit=" + SOURCE.encode("ascii") + b"\n")
        ambiguous = manifest.read_bytes()
        expect_refusal(
            lambda: identity.verify(root, candidate, SOURCE, RUN_ID),
            "duplicate candidate identity field must be refused",
        )
        check(manifest.read_bytes() == ambiguous, "refusal must not repair candidate evidence")


def test_wrong_source_and_run_refused() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-49-source-") as tmp:
        root = Path(tmp)
        fixture(root)
        candidate = root / "candidate"
        identity.assemble(root, candidate, SOURCE, RUN_ID)
        expect_refusal(
            lambda: identity.verify(root, candidate, "b" * 40, RUN_ID),
            "wrong exact source SHA must be refused",
        )
        expect_refusal(
            lambda: identity.verify(root, candidate, SOURCE, "424243"),
            "wrong GitHub run id must be refused",
        )


def main() -> int:
    test_exact_identity()
    test_jar_drift_refused()
    test_recovery_format_drift_refused()
    test_duplicate_manifest_field_refused_without_repair()
    test_wrong_source_and_run_refused()
    print("R1-49 recovery candidate identity regression PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

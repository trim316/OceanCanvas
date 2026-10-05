#!/usr/bin/env python3
"""Deterministic R1-48 regression for hosted failure-evidence preservation."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("preserve_hosted_failure_evidence.py")
spec = importlib.util.spec_from_file_location("preserve_hosted_failure_evidence", SCRIPT)
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load preserve_hosted_failure_evidence.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-48-") as tmp:
        root = Path(tmp)
        world = root / "world"
        state = world / "oceancanvas-core" / "single-chunk"
        state.mkdir(parents=True)

        level = b"level-metadata"
        operation = b"operation-authority"
        archive = b"immutable-preimage-archive"
        (world / "level.dat").write_bytes(level)
        (state / "operation.properties").write_bytes(operation)
        (state / "preimage-blockstates.bin.completed.archive").write_bytes(archive)

        # Simulate a broken evidence item that cannot be read as a file. The
        # collector must report it and still preserve the later evidence.
        (state / "runtime-receipts.log").mkdir()

        output = root / "evidence"
        manifest = collector.preserve(world, output, "a" * 40, "4182036")
        check(manifest["preserved"] == 3, "three healthy files must be preserved")
        check(manifest["failed"] == 1, "broken receipt entry must be reported once")

        entries = {entry["path"]: entry for entry in manifest["entries"]}
        check(entries["level.dat"]["sha256"] == sha(level), "world metadata SHA preserved")
        check(entries["oceancanvas-core/single-chunk/operation.properties"]["sha256"] == sha(operation),
              "operation authority SHA preserved")
        check(entries["oceancanvas-core/single-chunk/preimage-blockstates.bin.completed.archive"]["sha256"] == sha(archive),
              "backup archive SHA preserved after earlier copy failure")
        broken = entries["oceancanvas-core/single-chunk/runtime-receipts.log"]
        check(broken["status"] == "failed", "non-regular receipt evidence fails closed")

        manifest_file = output / "FAILURE-EVIDENCE.json"
        check(manifest_file.is_file(), "manifest always published")
        persisted = json.loads(manifest_file.read_text(encoding="utf-8"))
        check(persisted["sourceSha"] == "a" * 40, "source SHA bound")
        check(persisted["worldSeed"] == "4182036", "world seed bound")
        check(persisted["forensicOnly"] is True, "manifest cannot be mistaken for certification")
        check((output / "files" / "level.dat").read_bytes() == level, "metadata bytes exact")
        check((output / "files" / "oceancanvas-core/single-chunk/operation.properties").read_bytes() == operation,
              "operation bytes exact")
        check((output / "files" / "oceancanvas-core/single-chunk/preimage-blockstates.bin.completed.archive").read_bytes() == archive,
              "archive bytes exact")
        check((state / "runtime-receipts.log").is_dir(), "broken source evidence remains untouched")

        # A second collector run must not replace already-preserved bytes. It
        # records those paths as failures while leaving the original artifact intact.
        second = collector.preserve(world, output, "a" * 40, "4182036")
        check(second["failed"] >= 3, "repeat refuses replacement of preserved evidence")
        check((output / "files" / "level.dat").read_bytes() == level,
              "repeat cannot replace existing preserved metadata")

    print("R1-48 hosted failure evidence preservation regression PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

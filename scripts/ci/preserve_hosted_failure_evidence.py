#!/usr/bin/env python3
"""Best-effort bounded preservation of hosted disposable-world failure evidence.

This collector is deliberately forensic-only. It never mutates source evidence,
never certifies a runtime PASS, and continues after each individual copy/hash
failure so one damaged file cannot suppress the remainder of the evidence set.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
from pathlib import Path

MAX_FILE_BYTES = 64 * 1024 * 1024
KNOWN_STATE_NAMES = {
    "acceptance-state.properties",
    "transitions.journal",
    "runtime-receipts.log",
    "operation.properties",
    "block-state-registry.identity",
    "preimage-blockstates.bin",
    "preimage-blockstates.bin.completed.archive",
    "preimage-blockentities.ocbe",
    "preimage-blockentities.ocbe.completed.archive",
    "pair-operation.identity",
    "four-chunk-operation.identity",
    "nine-chunk-operation.identity",
    "sixteen-chunk-operation.identity",
}
WORLD_METADATA_NAMES = {"level.dat", "level.dat_old"}


def _sha256(path: Path, expected_size: int) -> str:
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as handle:
        while True:
            block = handle.read(1024 * 1024)
            if not block:
                break
            count += len(block)
            if count > expected_size or count > MAX_FILE_BYTES:
                raise OSError("evidence grew beyond bounded size while hashing")
            digest.update(block)
    if count != expected_size:
        raise OSError("evidence size changed while hashing")
    return digest.hexdigest()


def _candidate_paths(world_root: Path) -> list[Path]:
    candidates: set[Path] = set()
    for name in WORLD_METADATA_NAMES:
        path = world_root / name
        if path.exists() or path.is_symlink():
            candidates.add(path)

    core = world_root / "oceancanvas-core"
    if core.exists() and core.is_dir() and not core.is_symlink():
        for root, dirs, files in os.walk(core, followlinks=False):
            root_path = Path(root)
            dirs[:] = [d for d in dirs if not (root_path / d).is_symlink()]
            for name in files:
                if name in KNOWN_STATE_NAMES:
                    candidates.add(root_path / name)
            # Preserve known-name non-regular entries too, so the manifest can
            # report them rather than silently pretending they were absent.
            for name in KNOWN_STATE_NAMES:
                path = root_path / name
                if path.exists() or path.is_symlink():
                    candidates.add(path)
    return sorted(candidates, key=lambda p: p.as_posix())


def _safe_relative(world_root: Path, source: Path) -> str:
    relative = source.absolute().relative_to(world_root.absolute()).as_posix()
    if relative.startswith("../") or relative == "..":
        raise OSError("evidence escaped disposable world root")
    return relative


def _copy_one(world_root: Path, output: Path, source: Path) -> dict[str, object]:
    relative = _safe_relative(world_root, source)
    entry: dict[str, object] = {"path": relative}
    try:
        info = source.lstat()
        if stat.S_ISLNK(info.st_mode):
            raise OSError("refusing symbolic-link evidence")
        if not stat.S_ISREG(info.st_mode):
            raise OSError("evidence is not a regular file")
        size = info.st_size
        if size < 0 or size > MAX_FILE_BYTES:
            raise OSError("evidence exceeds bounded preservation size")
        before = _sha256(source, size)
        destination = output / "files" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() or destination.is_symlink():
            raise OSError("refusing to replace already-preserved evidence")
        shutil.copyfile(source, destination)
        copied = destination.stat()
        if not stat.S_ISREG(copied.st_mode) or copied.st_size != size:
            raise OSError("preserved evidence size mismatch")
        copied_sha = _sha256(destination, size)
        source_after = source.lstat()
        if not stat.S_ISREG(source_after.st_mode) or source_after.st_size != size:
            raise OSError("source evidence changed during preservation")
        after = _sha256(source, size)
        if before != copied_sha or before != after:
            raise OSError("source/copy SHA-256 mismatch")
        entry.update(status="preserved", size=size, sha256=before)
    except BaseException as exc:  # evidence collection must continue per file
        entry.update(status="failed", error=f"{type(exc).__name__}: {exc}")
    return entry


def preserve(world_root: Path, output: Path, source_sha: str, world_seed: str) -> dict[str, object]:
    world_root = world_root.absolute()
    output = output.absolute()
    output.mkdir(parents=True, exist_ok=True)
    entries = [_copy_one(world_root, output, path) for path in _candidate_paths(world_root)]
    preserved = sum(1 for entry in entries if entry["status"] == "preserved")
    failed = sum(1 for entry in entries if entry["status"] == "failed")
    manifest: dict[str, object] = {
        "schema": 1,
        "forensicOnly": True,
        "sourceSha": source_sha,
        "worldSeed": world_seed,
        "worldRootName": world_root.name,
        "preserved": preserved,
        "failed": failed,
        "entries": entries,
    }
    manifest_path = output / "FAILURE-EVIDENCE.json"
    temp = output / "FAILURE-EVIDENCE.json.tmp"
    if temp.exists() or temp.is_symlink():
        raise RuntimeError("stale failure-evidence manifest stage exists")
    temp.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temp, manifest_path)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--world-root", default="runtime-src/run/world")
    parser.add_argument("--output", default="hosted-failure-evidence")
    parser.add_argument("--source-sha", default=os.environ.get("GITHUB_SHA", "UNKNOWN"))
    parser.add_argument("--world-seed", default=os.environ.get("OCEANCANVAS_WORLD_SEED", "UNKNOWN"))
    args = parser.parse_args()
    manifest = preserve(Path(args.world_root), Path(args.output), args.source_sha, args.world_seed)
    print(json.dumps({
        "preserved": manifest["preserved"],
        "failed": manifest["failed"],
        "manifest": str(Path(args.output) / "FAILURE-EVIDENCE.json"),
    }, sort_keys=True))
    # A per-file collection failure is evidence, not a reason to suppress the
    # artifact upload. The manifest records it while the workflow continues.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

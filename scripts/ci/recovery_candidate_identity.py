#!/usr/bin/env python3
"""Assemble and verify immutable Ocean Canvas recovery candidate identity.

This is release-evidence plumbing, not runtime certification. The manifest binds the
candidate JAR to the exact Git source plus the Minecraft/Loader/Fabric versions and
an explicit digest over the source files that define durable recovery formats.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
from pathlib import Path


MANIFEST_SCHEMA = "1"
RECOVERY_FORMAT_VERSION = "1"
RECOVERY_SCOPE = "one-chunk-only"
FORMAT_FILES = (
    "runtime-src/src/main/java/net/oceancanvas/core/pipeline/ChunkStage.java",
    "runtime-src/src/main/java/net/oceancanvas/core/pipeline/SingleChunkOperationSpec.java",
    "runtime-src/src/main/java/net/oceancanvas/core/pipeline/OperationManifestStore.java",
    "runtime-src/src/main/java/net/oceancanvas/core/journal/CoreJournal.java",
    "runtime-src/src/main/java/net/oceancanvas/core/receipt/ReceiptKind.java",
    "runtime-src/src/main/java/net/oceancanvas/core/receipt/RuntimeReceiptLog.java",
    "runtime-src/src/main/java/net/oceancanvas/core/receipt/PreimageReceiptContinuity.java",
    "runtime-src/src/main/java/net/oceancanvas/core/restore/BlockStatePreimageStore.java",
    "runtime-src/src/main/java/net/oceancanvas/core/restore/BlockEntityBackupContract.java",
    "runtime-src/src/main/java/net/oceancanvas/core/restore/BlockEntitySidecarStore.java",
    "runtime-src/src/main/java/net/oceancanvas/core/restore/BlockStateRegistryIdentityStore.java",
    "runtime-src/src/main/java/net/oceancanvas/core/acceptance/CompletionEvidenceCertificate.java",
)


class IdentityError(RuntimeError):
    pass


def _parse_unique_properties(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        raise IdentityError(f"properties file missing or non-regular: {path}")
    result: dict[str, str] = {}
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            raise IdentityError(f"malformed property at {path}:{line_no}")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if not key or not value:
            raise IdentityError(f"blank property key/value at {path}:{line_no}")
        if key in result:
            raise IdentityError(f"duplicate property {key} at {path}:{line_no}")
        result[key] = value
    return result


def _sha256(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise IdentityError(f"candidate input missing or non-regular: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            block = handle.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def recovery_format_sha256(repo_root: Path) -> str:
    digest = hashlib.sha256()
    digest.update(b"OCEANCANVAS_RECOVERY_FORMAT_V1\0")
    for relative in FORMAT_FILES:
        path = repo_root / relative
        if not path.is_file() or path.is_symlink():
            raise IdentityError(f"recovery format source missing or non-regular: {relative}")
        rel_bytes = relative.encode("utf-8")
        data = path.read_bytes()
        digest.update(len(rel_bytes).to_bytes(4, "big"))
        digest.update(rel_bytes)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def _runtime_identity(repo_root: Path) -> tuple[dict[str, str], str, str]:
    gradle = _parse_unique_properties(repo_root / "runtime-src/gradle.properties")
    required = ("minecraft_version", "loader_version", "fabric_version", "mod_version")
    missing = [key for key in required if key not in gradle]
    if missing:
        raise IdentityError("missing Gradle identity fields: " + ",".join(missing))
    minecraft = gradle["minecraft_version"]
    runtime = gradle["mod_version"]
    prefix = minecraft + "-"
    if not runtime.startswith(prefix):
        raise IdentityError("mod_version is not bound to minecraft_version")
    core_version = runtime[len(prefix) :]
    return gradle, runtime, core_version


def _validate_run_identity(source_commit: str, build_run_id: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise IdentityError("sourceCommit must be an exact 40-character lowercase Git SHA")
    if not re.fullmatch(r"[1-9][0-9]*", build_run_id):
        raise IdentityError("buildRunId must be a positive decimal GitHub run id")


def _find_built_jar(repo_root: Path) -> Path:
    libs = repo_root / "runtime-src/build/libs"
    candidates = sorted(
        path
        for path in libs.glob("oceancanvas-core-*.jar")
        if path.is_file() and not path.is_symlink() and not path.name.endswith("-sources.jar")
    )
    if len(candidates) != 1:
        raise IdentityError(f"expected exactly one runtime JAR, found {len(candidates)}")
    return candidates[0]


def _expected_manifest(repo_root: Path, jar: Path, source_commit: str, build_run_id: str) -> dict[str, str]:
    _validate_run_identity(source_commit, build_run_id)
    gradle, runtime, core_version = _runtime_identity(repo_root)
    return {
        "schema": MANIFEST_SCHEMA,
        "runtime": runtime,
        "coreVersion": core_version,
        "minecraftVersion": gradle["minecraft_version"],
        "loaderVersion": gradle["loader_version"],
        "fabricVersion": gradle["fabric_version"],
        "jarName": jar.name,
        "jarSha256": _sha256(jar),
        "sourceCommit": source_commit,
        "buildRunId": build_run_id,
        "recoveryScope": RECOVERY_SCOPE,
        "recoveryFormatVersion": RECOVERY_FORMAT_VERSION,
        "recoveryFormatSha256": recovery_format_sha256(repo_root),
    }


def _write_manifest(path: Path, fields: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ordered = (
        "schema",
        "runtime",
        "coreVersion",
        "minecraftVersion",
        "loaderVersion",
        "fabricVersion",
        "jarName",
        "jarSha256",
        "sourceCommit",
        "buildRunId",
        "recoveryScope",
        "recoveryFormatVersion",
        "recoveryFormatSha256",
    )
    path.write_text("".join(f"{key}={fields[key]}\n" for key in ordered), encoding="utf-8")


def assemble(repo_root: Path, candidate_dir: Path, source_commit: str, build_run_id: str) -> Path:
    repo_root = repo_root.resolve()
    candidate_dir = candidate_dir.resolve()
    built_jar = _find_built_jar(repo_root)
    candidate_dir.mkdir(parents=True, exist_ok=True)
    jar = candidate_dir / built_jar.name
    if jar.exists() or jar.is_symlink():
        raise IdentityError(f"refusing to replace existing candidate JAR: {jar}")
    shutil.copyfile(built_jar, jar)
    fields = _expected_manifest(repo_root, jar, source_commit, build_run_id)
    manifest = candidate_dir / "candidate.properties"
    if manifest.exists() or manifest.is_symlink():
        raise IdentityError(f"refusing to replace existing candidate manifest: {manifest}")
    _write_manifest(manifest, fields)
    verify(repo_root, candidate_dir, source_commit, build_run_id)
    return manifest


def verify(repo_root: Path, candidate_dir: Path, source_commit: str, build_run_id: str) -> dict[str, str]:
    repo_root = repo_root.resolve()
    candidate_dir = candidate_dir.resolve()
    _validate_run_identity(source_commit, build_run_id)
    manifest_path = candidate_dir / "candidate.properties"
    actual = _parse_unique_properties(manifest_path)
    jar_name = actual.get("jarName", "")
    if not jar_name or Path(jar_name).name != jar_name:
        raise IdentityError("candidate jarName must be one basename")
    jar = candidate_dir / jar_name
    expected = _expected_manifest(repo_root, jar, source_commit, build_run_id)
    if set(actual) != set(expected):
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        raise IdentityError(f"candidate manifest field set mismatch missing={missing} extra={extra}")
    for key, value in expected.items():
        if actual.get(key) != value:
            raise IdentityError(f"candidate identity mismatch for {key}")
    return actual


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("assemble", "verify"))
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--candidate-dir", default="candidate")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--build-run-id", required=True)
    args = parser.parse_args()
    repo_root = Path(args.repo_root)
    candidate_dir = Path(args.candidate_dir)
    try:
        if args.mode == "assemble":
            manifest = assemble(repo_root, candidate_dir, args.source_commit, args.build_run_id)
            print(f"RECOVERY_CANDIDATE_IDENTITY_ASSEMBLED={manifest}")
        else:
            fields = verify(repo_root, candidate_dir, args.source_commit, args.build_run_id)
            print("RECOVERY_CANDIDATE_IDENTITY_VERIFIED=" + fields["recoveryFormatSha256"])
        return 0
    except IdentityError as exc:
        print(f"RECOVERY_CANDIDATE_IDENTITY_REFUSED={exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

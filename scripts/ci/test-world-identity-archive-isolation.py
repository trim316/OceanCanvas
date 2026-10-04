#!/usr/bin/env python3
"""R1-88: prove recovery evidence is archived independently by world identity.

This is a hosted, filesystem-only regression. It deliberately uses identical
chunk coordinates for two disposable-world fixtures and proves that archival
identity cannot collapse to chunk coordinates alone.
"""
from __future__ import annotations

import hashlib
import json
import tempfile
from pathlib import Path

CHUNK_X = 32
CHUNK_Z = 32


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def world_namespace(world_identity: str) -> str:
    if not world_identity or world_identity.strip() != world_identity:
        raise ValueError("world identity must be non-empty canonical text")
    return sha256_bytes(world_identity.encode("utf-8"))


def archive_campaign(root: Path, world_identity: str, journal: bytes) -> Path:
    namespace = world_namespace(world_identity)
    target = root / namespace / f"chunk-{CHUNK_X}-{CHUNK_Z}"
    if target.exists():
        raise FileExistsError(f"refusing to overwrite immutable recovery evidence: {target}")
    target.mkdir(parents=True, exist_ok=False)

    journal_sha = sha256_bytes(journal)
    (target / "recovery.journal").write_bytes(journal)
    attestation = {
        "format": 1,
        "worldIdentity": world_identity,
        "worldIdentitySha256": namespace,
        "chunkX": CHUNK_X,
        "chunkZ": CHUNK_Z,
        "journalSha256": journal_sha,
    }
    encoded = (json.dumps(attestation, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    (target / "attestation.json").write_bytes(encoded)
    return target


def verify_archive(path: Path, expected_world_identity: str, expected_journal: bytes) -> None:
    attestation = json.loads((path / "attestation.json").read_text(encoding="utf-8"))
    assert attestation["worldIdentity"] == expected_world_identity
    assert attestation["worldIdentitySha256"] == world_namespace(expected_world_identity)
    assert attestation["chunkX"] == CHUNK_X and attestation["chunkZ"] == CHUNK_Z
    assert (path / "recovery.journal").read_bytes() == expected_journal
    assert attestation["journalSha256"] == sha256_bytes(expected_journal)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="oceancanvas-r1-88-") as td:
        root = Path(td) / "immutable-recovery-archive"
        world_a = "disposable-world:r1-88:a"
        world_b = "disposable-world:r1-88:b"
        journal_a = b"operation=a\nchunk=32,32\nstage=COMPLETE\n"
        journal_b = b"operation=b\nchunk=32,32\nstage=COMPLETE\n"

        archive_a = archive_campaign(root, world_a, journal_a)
        archive_b = archive_campaign(root, world_b, journal_b)

        assert archive_a != archive_b, "different world identities aliased one archive namespace"
        assert archive_a.parent.name != archive_b.parent.name
        verify_archive(archive_a, world_a, journal_a)
        verify_archive(archive_b, world_b, journal_b)

        before_a = (archive_a / "recovery.journal").read_bytes()
        before_b = (archive_b / "recovery.journal").read_bytes()
        try:
            archive_campaign(root, world_a, b"replacement must be refused\n")
        except FileExistsError:
            pass
        else:
            raise AssertionError("immutable world/chunk archive accepted overwrite")

        assert (archive_a / "recovery.journal").read_bytes() == before_a
        assert (archive_b / "recovery.journal").read_bytes() == before_b
        verify_archive(archive_a, world_a, journal_a)
        verify_archive(archive_b, world_b, journal_b)

    print("R1_88_WORLD_IDENTITY_ARCHIVE_ISOLATION_PASS")


if __name__ == "__main__":
    main()

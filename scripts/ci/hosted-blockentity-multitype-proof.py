#!/usr/bin/env python3
"""Disposable Minecraft proof: two vanilla block-entity types restore exactly and survive cold reopen."""
from disposable_world_guard import require_pristine_disposable_world
import os
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time
import zlib

spec = importlib.util.spec_from_file_location(
    "capture_proof", Path(__file__).resolve().with_name("hosted-blockentity-capture-refusal.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

PROJECT = capture.PROJECT
RUN = capture.RUN
WORLD = capture.WORLD
STATE = capture.STATE
OUTPUT = Path(capture.os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-multitype-proof"

FIXTURES = (
    ((512, 25, 512), "minecraft:chest", "minecraft:diamond", 3),
    ((513, 25, 512), "minecraft:barrel", "minecraft:emerald", 2),
)


def wait_complete(log_path, process, seconds=480):
    journal = STATE / "transitions.journal"
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        stages = []
        if journal.exists():
            for line in journal.read_text(errors="replace").splitlines():
                fields = line.split("\t")
                if len(fields) >= 6:
                    stages.append(fields[5])
        text = log_path.read_text(errors="replace") if log_path.exists() else ""
        if "SINGLE-CHUNK-INIT-FAILED" in text:
            raise RuntimeError("Minecraft refused multi-type block-entity recovery startup")
        if stages and stages[-1] == "FAILED":
            raise RuntimeError("multi-type block-entity recovery reached durable FAILED stage")
        if len(stages) == 10 and stages[-1] == "COMPLETE":
            return text
        if process.poll() is not None:
            raise RuntimeError("Minecraft exited before multi-type COMPLETE")
        time.sleep(0.25)
    raise RuntimeError("multi-type block-entity recovery timed out")


def inspect_fixture(pos, type_id, item_id):
    x, y, z = pos
    capture.rcon(f"forceload add {x} {z}")
    time.sleep(0.5)
    try:
        data = capture.rcon(f"data get block {x} {y} {z}")
    finally:
        capture.rcon(f"forceload remove {x} {z}")
    if type_id.split(":", 1)[1] not in data.lower():
        raise RuntimeError("restored block entity type missing at %r: %s" % (pos, data))
    if item_id.split(":", 1)[1] not in data.lower():
        raise RuntimeError("restored inventory item missing at %r: %s" % (pos, data))
    return data


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_receipt():
    path = STATE / "runtime-receipts.log"
    raw = path.read_bytes()
    if not raw.endswith(b"\n"):
        raise RuntimeError("unterminated receipt chain")
    found = None
    for index, line in enumerate(raw.decode("utf-8").splitlines()):
        fields = line.split("\t")
        if len(fields) != 7 or fields[0] != str(index):
            raise RuntimeError("malformed receipt sequence")
        payload = "\t".join(fields[:-1])
        if zlib.crc32(payload.encode("utf-8")) != int(fields[-1]):
            raise RuntimeError("receipt CRC mismatch")
        if fields[2] == "TICKET_RELEASED" and "restoreVerified=true" in fields[5]:
            found = fields[5]
    if found is None:
        raise RuntimeError("missing terminal verified ticket release")
    return found


def token(detail, key):
    prefix = key + "="
    for value in detail.split(";"):
        if value.startswith(prefix):
            return value[len(prefix):]
    return None


def main():
    require_pristine_disposable_world(PROJECT, RUN, RUN / "world", os.environ)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("proof requires initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=False)
    (RUN / "eula.txt").write_text("eula=true\n")
    props = capture.server_properties().replace("level-seed=4182033", "level-seed=4182034")
    (RUN / "server.properties").write_text(props)

    # Prepare two distinct real vanilla block entities while OceanCanvas has no
    # mutation authority.
    capture.write_core(False)
    setup_log = OUTPUT / "setup-safe-hold.log"
    process, sink = capture.start(setup_log)
    try:
        capture.wait_for(setup_log, "Done (", process)
        capture.rcon("forceload add 512 512")
        time.sleep(1)
        for pos, type_id, item_id, count in FIXTURES:
            x, y, z = pos
            placed = capture.rcon(f"setblock {x} {y} {z} {type_id}")
            if "changed" not in placed.lower():
                raise RuntimeError("failed to place %s: %s" % (type_id, placed))
            item = capture.rcon(
                f"item replace block {x} {y} {z} container.0 with {item_id} {count}")
            if "replaced" not in item.lower() and "modified" not in item.lower():
                raise RuntimeError("failed to seed %s inventory: %s" % (type_id, item))
            inspect_fixture(pos, type_id, item_id)
        capture.rcon("save-all flush")
        capture.rcon("forceload remove 512 512")
    finally:
        capture.stop(process)
        sink.close()

    # Arm exact one-chunk authoring plus separate block-entity recovery consent.
    capture.write_core(True)
    cfg = RUN / "config"
    (cfg / "oceancanvas-block-entity-recovery.properties").write_text(
        "enabled=true\nchunkX=32\nchunkZ=32\n"
        "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n")

    author_log = OUTPUT / "author-restore.log"
    process, sink = capture.start(author_log)
    try:
        text = wait_complete(author_log, process)
        if "blockEntities=2" not in text:
            raise RuntimeError("runtime did not attest exactly two restored block entities")
        for pos, type_id, item_id, _ in FIXTURES:
            inspect_fixture(pos, type_id, item_id)

        state_archive = STATE / "preimage-blockstates.bin.completed.archive"
        sidecar_archive = STATE / "preimage-blockentities.ocbe.completed.archive"
        if not state_archive.is_file() or not sidecar_archive.is_file():
            raise RuntimeError("completed multi-type operation did not archive both preimages")
        state_sha = sha(state_archive)
        sidecar_sha = sha(sidecar_archive)
        parsed = capture.parse_sidecar(sidecar_archive)
        if parsed["preimage_sha256"] != state_sha:
            raise RuntimeError("multi-type sidecar is not bound to archived block-state preimage")
        actual_types = sorted(entry[1] for entry in parsed["entries"])
        expected_types = sorted(f[1] for f in FIXTURES)
        if actual_types != expected_types:
            raise RuntimeError("sidecar block-entity type set mismatch: " + repr(actual_types))
        detail = release_receipt()
        if token(detail, "blockEntities") != "2":
            raise RuntimeError("release receipt does not attest two block entities")
        if token(detail, "blockEntityArchiveSha256") != sidecar_sha:
            raise RuntimeError("release receipt does not bind exact multi-type sidecar archive")
    finally:
        capture.stop(process)
        sink.close()

    # Cold process reopen must validate both archives before terminal COMPLETE,
    # then both independent real block entities must still hold their inventory.
    cold_log = OUTPUT / "cold-reopen.log"
    process, sink = capture.start(cold_log)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            text = cold_log.read_text(errors="replace") if cold_log.exists() else ""
            if "SINGLE-CHUNK-INIT-FAILED" in text:
                raise RuntimeError("cold reopen rejected multi-type completed evidence")
            if "SINGLE-CHUNK-OPEN" in text and "resumedStage=COMPLETE" in text:
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before multi-type cold reopen")
            time.sleep(0.5)
        else:
            raise RuntimeError("multi-type cold reopen did not attest COMPLETE")
        for pos, type_id, item_id, _ in FIXTURES:
            inspect_fixture(pos, type_id, item_id)
        if sha(STATE / "preimage-blockstates.bin.completed.archive") != state_sha:
            raise RuntimeError("cold reopen changed multi-type state archive")
        if sha(STATE / "preimage-blockentities.ocbe.completed.archive") != sidecar_sha:
            raise RuntimeError("cold reopen changed multi-type sidecar archive")
    finally:
        capture.stop(process)
        sink.close()

    return {
        "verdict": "PASS",
        "seed": "4182034",
        "cold_reopen": True,
        "block_entity_count": 2,
        "types": [f[1] for f in FIXTURES],
        "inventories": ["minecraft:diamond x3", "minecraft:emerald x2"],
        "state_archive_sha256": state_sha,
        "block_entity_archive_sha256": sidecar_sha,
    }


if __name__ == "__main__":
    try:
        result = main()
        code = 0
    except BaseException as exc:
        result = {"verdict": "FAIL", "error": str(exc)}
        code = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    sys.exit(code)

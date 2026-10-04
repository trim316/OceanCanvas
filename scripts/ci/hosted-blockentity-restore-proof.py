#!/usr/bin/env python3
"""Disposable Minecraft proof: real chest NBT survives author, restore, and cold reopen."""
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
OUTPUT = Path(capture.os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-restore-proof"
TARGET = capture.TARGET


def journal_stages():
    path = STATE / "transitions.journal"
    if not path.exists():
        return []
    stages = []
    for line in path.read_text(errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) >= 6:
            stages.append(parts[5])
    return stages


def wait_complete(log_path, process, seconds=480):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        stages = journal_stages()
        text = log_path.read_text(errors="replace") if log_path.exists() else ""
        if "SINGLE-CHUNK-INIT-FAILED" in text:
            raise RuntimeError("Minecraft refused block-entity recovery startup")
        if stages and stages[-1] == "FAILED":
            raise RuntimeError("block-entity recovery reached durable FAILED stage")
        if len(stages) == 10 and stages[-1] == "COMPLETE":
            return text
        if process.poll() is not None:
            raise RuntimeError("Minecraft exited before COMPLETE")
        time.sleep(0.25)
    raise RuntimeError("block-entity recovery did not reach COMPLETE")


def inspect_chest():
    capture.rcon("forceload add 512 512")
    time.sleep(1)
    try:
        text = capture.rcon("data get block 512 25 512")
    finally:
        capture.rcon("forceload remove 512 512")
    if "minecraft:chest" not in text.lower() and "diamond" not in text.lower():
        raise RuntimeError("restored block is not the expected chest: " + text)
    if "diamond" not in text.lower():
        raise RuntimeError("restored chest lost captured inventory NBT: " + text)
    return text


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verified_receipts():
    path = STATE / "runtime-receipts.log"
    raw = path.read_bytes()
    if not raw or not raw.endswith(b"\n"):
        raise RuntimeError("durable receipt chain missing or unterminated")
    result = []
    for index, line in enumerate(raw.decode("utf-8").splitlines()):
        fields = line.split("\t")
        if len(fields) != 7 or fields[0] != str(index):
            raise RuntimeError("malformed durable receipt sequence")
        payload = "\t".join(fields[:-1])
        if zlib.crc32(payload.encode("utf-8")) != int(fields[-1]):
            raise RuntimeError("durable receipt CRC mismatch")
        result.append((fields[2], fields[5]))
    return result


def detail_token(detail, key):
    prefix = key + "="
    for token in detail.split(";"):
        if token.startswith(prefix):
            return token[len(prefix):]
    return None


def main():
    require_pristine_disposable_world(PROJECT, RUN, RUN / "world", os.environ)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("proof requires initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=False)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(capture.server_properties())

    # Session 1: create durable vanilla source data with OceanCanvas in SAFE_HOLD.
    capture.write_core(False)
    setup_log = OUTPUT / "setup-safe-hold.log"
    process, sink = capture.start(setup_log)
    try:
        capture.wait_for(setup_log, "Done (", process)
        capture.rcon("forceload add 512 512")
        time.sleep(1)
        result = capture.rcon("setblock 512 25 512 minecraft:chest")
        if "changed" not in result.lower():
            raise RuntimeError("failed to place chest fixture: " + result)
        result = capture.rcon("item replace block 512 25 512 container.0 with minecraft:diamond 3")
        if "replaced" not in result.lower() and "modified" not in result.lower():
            raise RuntimeError("failed to seed chest inventory: " + result)
        before = capture.rcon("data get block 512 25 512")
        if "diamond" not in before.lower():
            raise RuntimeError("source chest inventory missing before OceanCanvas: " + before)
        capture.rcon("save-all flush")
        capture.rcon("forceload remove 512 512")
    finally:
        capture.stop(process)
        sink.close()

    # Session 2: exact single-chunk authoring plus separate BE consent.
    capture.write_core(True)
    cfg = RUN / "config"
    (cfg / "oceancanvas-block-entity-recovery.properties").write_text(
        "enabled=true\nchunkX=32\nchunkZ=32\n"
        "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n")
    run_log = OUTPUT / "author-restore.log"
    process, sink = capture.start(run_log)
    try:
        text = wait_complete(run_log, process)
        if "blockEntities=1" not in text:
            raise RuntimeError("runtime did not attest one captured/restored block entity")
        restored = inspect_chest()

        state_archive = STATE / "preimage-blockstates.bin.completed.archive"
        sidecar_archive = STATE / "preimage-blockentities.ocbe.completed.archive"
        if not state_archive.is_file() or not sidecar_archive.is_file():
            raise RuntimeError("completed operation did not archive both state and block-entity evidence")
        if (STATE / "preimage-blockentities.ocbe").exists():
            raise RuntimeError("completed operation retained mutable/live block-entity sidecar")
        state_sha = sha(state_archive)
        sidecar_sha = sha(sidecar_archive)
        parsed = capture.parse_sidecar(sidecar_archive)
        if parsed["preimage_sha256"] != state_sha:
            raise RuntimeError("archived sidecar is not bound to archived block-state preimage")
        if len(parsed["entries"]) != 1 or parsed["entries"][0][1] != "minecraft:chest":
            raise RuntimeError("archived sidecar lost chest identity")
        receipts = verified_receipts()
        verified_detail = next((d for kind, d in receipts if kind == "RESTORE_VERIFIED"), None)
        release_detail = next((d for kind, d in receipts
                               if kind == "TICKET_RELEASED"
                               and "restoreVerified=true" in d), None)
        if verified_detail is None or release_detail is None:
            raise RuntimeError("missing durable restore-verification or release receipt")
        if detail_token(release_detail, "blockEntityArchiveSha256") != sidecar_sha:
            raise RuntimeError("release receipt does not bind archived sidecar SHA")
        verified_envelope = detail_token(verified_detail, "blockEntityEnvelopeSha256")
        released_envelope = detail_token(release_detail, "blockEntityEnvelopeSha256")
        if not verified_envelope or verified_envelope != released_envelope:
            raise RuntimeError("block-entity envelope identity is not continuous through release")
        if detail_token(release_detail, "blockEntities") != "1":
            raise RuntimeError("release receipt does not attest exactly one block entity")
    finally:
        capture.stop(process)
        sink.close()

    # Session 3: cold reopen must independently validate archived state + sidecar
    # before accepting terminal COMPLETE. Then inspect the world again.
    cold_log = OUTPUT / "cold-reopen.log"
    process, sink = capture.start(cold_log)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            text = cold_log.read_text(errors="replace") if cold_log.exists() else ""
            if "SINGLE-CHUNK-INIT-FAILED" in text:
                raise RuntimeError("cold reopen rejected completed block-entity recovery evidence")
            if "SINGLE-CHUNK-OPEN" in text and "resumedStage=COMPLETE" in text:
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before completed cold-reopen proof")
            time.sleep(0.5)
        else:
            raise RuntimeError("cold reopen did not attest terminal COMPLETE")
        inspect_chest()
        if sha(STATE / "preimage-blockstates.bin.completed.archive") != state_sha:
            raise RuntimeError("cold reopen changed immutable block-state archive")
        if sha(STATE / "preimage-blockentities.ocbe.completed.archive") != sidecar_sha:
            raise RuntimeError("cold reopen changed immutable block-entity archive")
    finally:
        capture.stop(process)
        sink.close()

    return {
        "verdict": "PASS",
        "seed": "4182033",
        "cold_reopen": True,
        "block_entity_count": 1,
        "type_id": "minecraft:chest",
        "inventory": "minecraft:diamond x3",
        "state_archive_sha256": state_sha,
        "block_entity_archive_sha256": sidecar_sha,
        "target": list(TARGET),
    }


if __name__ == "__main__":
    try:
        verdict = main()
        code = 0
    except BaseException as exc:
        verdict = {"verdict": "FAIL", "error": str(exc)}
        code = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(verdict, indent=2) + "\n")
    print(json.dumps(verdict, indent=2), flush=True)
    sys.exit(code)

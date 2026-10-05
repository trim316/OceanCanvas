#!/usr/bin/env python3
"""Disposable Minecraft proof: block-entity recovery survives a real process kill."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location(
    "restore_proof", Path(__file__).resolve().with_name("hosted-blockentity-restore-proof.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)

capture = restore.capture
PROJECT = restore.PROJECT
RUN = restore.RUN
WORLD = restore.WORLD
STATE = restore.STATE
OUTPUT = Path(capture.os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-interruption-proof"
TARGET = restore.TARGET
INTERRUPT_AFTER = capture.os.environ.get(
    "OCEANCANVAS_BLOCKENTITY_INTERRUPT_AFTER", "PREIMAGE_CAPTURED")
ALLOWED = ("PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "RESTORED")


def journal_stages():
    path = STATE / "transitions.journal"
    if not path.exists():
        return []
    result = []
    for line in path.read_text(errors="replace").splitlines():
        fields = line.split("\t")
        if len(fields) >= 6:
            result.append(fields[5])
    return result


def main():
    if INTERRUPT_AFTER not in ALLOWED:
        raise RuntimeError("unsupported block-entity interruption boundary: " + INTERRUPT_AFTER)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("proof requires initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        capture.server_properties().replace("level-seed=4182033", "level-seed=4182035"))

    # Session 1: establish a real chest/inventory while OceanCanvas has no
    # authoring authority.
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
        result = capture.rcon(
            "item replace block 512 25 512 container.0 with minecraft:diamond 3")
        if "replaced" not in result.lower() and "modified" not in result.lower():
            raise RuntimeError("failed to seed chest inventory: " + result)
        restore.inspect_chest()
        capture.rcon("save-all flush")
        capture.rcon("forceload remove 512 512")
    finally:
        capture.stop(process)
        sink.close()

    # Session 2: authorize exact one-chunk BE recovery, then kill the server
    # only after the requested durable stage is visible in the checksummed
    # journal. This is a real process kill, not an orderly server stop.
    capture.write_core(True)
    cfg = RUN / "config"
    (cfg / "oceancanvas-block-entity-recovery.properties").write_text(
        "enabled=true\nchunkX=32\nchunkZ=32\n"
        "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n")
    first_log = OUTPUT / "interrupted-session.log"
    process, sink = capture.start(first_log)
    pre_crash_sidecar_sha = None
    pre_crash_state_sha = None
    try:
        deadline = time.monotonic() + 480
        while time.monotonic() < deadline:
            stages = journal_stages()
            text = first_log.read_text(errors="replace") if first_log.exists() else ""
            if "SINGLE-CHUNK-INIT-FAILED" in text:
                raise RuntimeError("Minecraft refused block-entity interruption fixture")
            if stages and stages[-1] == "FAILED":
                raise RuntimeError("block-entity recovery failed before requested interruption")
            if INTERRUPT_AFTER in stages:
                state = STATE / "preimage-blockstates.bin"
                sidecar = STATE / "preimage-blockentities.ocbe"
                if INTERRUPT_AFTER == "PREIMAGE_CAPTURED":
                    if not state.is_file() or not sidecar.is_file():
                        raise RuntimeError("durable preimages absent at PREIMAGE_CAPTURED")
                    pre_crash_state_sha = restore.sha(state)
                    pre_crash_sidecar_sha = restore.sha(sidecar)
                process.kill()
                process.wait(timeout=20)
                (OUTPUT / "INTERRUPTION.txt").write_text(
                    "requested=" + INTERRUPT_AFTER + "\n"
                    "observed=" + stages[-1] + "\n"
                    "method=isolated-server-process-kill\n"
                    + ("stateSha256=" + pre_crash_state_sha + "\n"
                       if pre_crash_state_sha else "")
                    + ("sidecarSha256=" + pre_crash_sidecar_sha + "\n"
                       if pre_crash_sidecar_sha else ""))
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before requested interruption")
            time.sleep(0.25)
        else:
            raise RuntimeError("requested block-entity interruption stage was never reached")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=20)
        sink.close()

    # Session 3: restart the same disposable world and require normal recovery
    # to COMPLETE, then prove exact inventory and immutable archive provenance.
    resume_log = OUTPUT / "resume-after-kill.log"
    process, sink = capture.start(resume_log)
    try:
        text = restore.wait_complete(resume_log, process)
        if "blockEntities=1" not in text:
            raise RuntimeError("resumed runtime did not attest one block entity")
        restore.inspect_chest()
        state_archive = STATE / "preimage-blockstates.bin.completed.archive"
        sidecar_archive = STATE / "preimage-blockentities.ocbe.completed.archive"
        if not state_archive.is_file() or not sidecar_archive.is_file():
            raise RuntimeError("resumed operation did not archive both recovery sources")
        state_sha = restore.sha(state_archive)
        sidecar_sha = restore.sha(sidecar_archive)
        if pre_crash_state_sha and state_sha != pre_crash_state_sha:
            raise RuntimeError("state preimage changed across process kill")
        if pre_crash_sidecar_sha and sidecar_sha != pre_crash_sidecar_sha:
            raise RuntimeError("block-entity sidecar changed across process kill")
        parsed = capture.parse_sidecar(sidecar_archive)
        if parsed["preimage_sha256"] != state_sha:
            raise RuntimeError("resumed sidecar lost state-preimage binding")
        if len(parsed["entries"]) != 1 or parsed["entries"][0][1] != "minecraft:chest":
            raise RuntimeError("resumed sidecar lost captured chest identity")
        receipts = restore.verified_receipts()
        release_detail = next((d for kind, d in receipts
                               if kind == "TICKET_RELEASED"
                               and "restoreVerified=true" in d), None)
        if release_detail is None:
            raise RuntimeError("resumed recovery lacks terminal verified release")
        if restore.detail_token(release_detail, "blockEntityArchiveSha256") != sidecar_sha:
            raise RuntimeError("resumed release receipt lost archived sidecar SHA")
    finally:
        capture.stop(process)
        sink.close()

    # Session 4: cold reopen terminal evidence and inspect the restored chest
    # once more. This distinguishes crash recovery from same-process success.
    cold_log = OUTPUT / "cold-reopen.log"
    process, sink = capture.start(cold_log)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            text = cold_log.read_text(errors="replace") if cold_log.exists() else ""
            if "SINGLE-CHUNK-INIT-FAILED" in text:
                raise RuntimeError("cold reopen rejected interrupted BE recovery evidence")
            if "SINGLE-CHUNK-OPEN" in text and "resumedStage=COMPLETE" in text:
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before interrupted BE cold reopen")
            time.sleep(0.5)
        else:
            raise RuntimeError("interrupted BE cold reopen did not attest COMPLETE")
        restore.inspect_chest()
        if restore.sha(STATE / "preimage-blockstates.bin.completed.archive") != state_sha:
            raise RuntimeError("cold reopen changed interrupted state archive")
        if restore.sha(STATE / "preimage-blockentities.ocbe.completed.archive") != sidecar_sha:
            raise RuntimeError("cold reopen changed interrupted sidecar archive")
    finally:
        capture.stop(process)
        sink.close()

    return {
        "verdict": "PASS",
        "seed": "4182035",
        "requested_interruption": INTERRUPT_AFTER,
        "real_process_kill": True,
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

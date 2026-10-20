#!/usr/bin/env python3
"""Disposable 1000x1000 OceanCanvas integrated scale proof. Never touches a user world."""
import hashlib
import zipfile
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("fullproof", HERE.with_name("hosted-full-canvas-command-proof.py"))
full = importlib.util.module_from_spec(spec)
spec.loader.exec_module(full)

single = full.single
PROJECT = full.PROJECT
RUN = full.RUN
WORLD = RUN / "world"
ROOT = WORLD / "oceancanvas-core"
FULL = ROOT / "full-canvas"
FLATTEN_STATE = FULL / "operation.properties"
RESTORE_STATE = FULL / "restore.properties"
SHIPWRECK_STATE = ROOT / "shipwreck-population" / "operation.properties"
MONUMENT_STATE = ROOT / "monument-population" / "operation.properties"
HEALTH_STATE = ROOT / "health-scan" / "operation.properties"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-1k-integrated-proof"
# The shared launcher opens its log using its own module-level output path.
# Keep every session log in this proof's created and uploaded evidence directory.
full.OUTPUT = OUTPUT
SEED = os.environ.get("OCEANCANVAS_1K_SEED", "4182041")
CANVAS_SIZE = 1000
CENTER = 0


def props(path):
    return single.properties(path) if path.is_file() else {}


def await_status(path, wanted, timeout, process=None, log_path=None):
    deadline = time.monotonic() + timeout
    last = {}
    while time.monotonic() < deadline:
        last = props(path)
        if last.get("status") == wanted:
            return last
        if process is not None and process.poll() is not None:
            tail = log_path.read_text(errors="replace")[-12000:] if log_path and log_path.exists() else ""
            raise RuntimeError(f"server exited while waiting for {path} -> {wanted}; last={last}; tail=\n{tail}")
        time.sleep(2)
    raise RuntimeError(f"timeout waiting for {path} -> {wanted}; last={last}")


def wait_for_progress(path, minimum_fraction, timeout, process, log_path):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = props(path)
        total = int(state.get("totalChunks", "0") or 0)
        done = int(state.get("nextIndex", "0") or 0)
        if total > 0 and done / total >= minimum_fraction:
            return state
        if process.poll() is not None:
            tail = log_path.read_text(errors="replace")[-12000:] if log_path.exists() else ""
            raise RuntimeError("server exited before planned interruption; tail=\n" + tail)
        time.sleep(2)
    raise RuntimeError("flatten did not reach planned interruption threshold")


def launch(name):
    return full.launch(name)


CHECKPOINT_STARTED_UTC = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
CHECKPOINT_PARENT = None


def stop(process, sink):
    response = full.stop(process, sink)
    if process.returncode != 0:
        raise RuntimeError("refusing checkpoint without clean server shutdown")
    retain_checkpoint(Path(sink.name).stem)
    return response


def retain_checkpoint(phase):
    global CHECKPOINT_PARENT
    if not WORLD.is_dir():
        raise RuntimeError("clean stop did not leave a world to checkpoint")
    target = OUTPUT / (phase + "-checkpoint.zip")
    if target.exists():
        raise RuntimeError("refusing to overwrite immutable checkpoint: " + str(target))
    files = sorted(p for p in WORLD.rglob("*") if p.is_file())
    files += sorted(p for p in (RUN / "config").rglob("*") if p.is_file())
    files += sorted(p for p in (PROJECT / "build/libs").glob("*.jar") if not p.name.endswith("-sources.jar"))
    files += [p for p in [RUN / "server.properties", OUTPUT / (phase + ".log")] if p.is_file()]
    identity = {
        "schema": 1, "phase": phase, "cleanStopObserved": True,
        "campaignStartedUtc": CHECKPOINT_STARTED_UTC,
        "capturedUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "seed": SEED, "targetBlocks": CANVAS_SIZE,
        "parentCheckpointSha256": CHECKPOINT_PARENT,
        "scope": "clean stopped checkpoint only; not acceptance or certification",
        "files": {},
    }
    temporary = target.with_suffix(".zip.partial")
    with zipfile.ZipFile(temporary, "x", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            name = path.relative_to(PROJECT.parent).as_posix()
            with path.open("rb") as source, archive.open(name, "w") as dest:
                digest = hashlib.sha256()
                while data := source.read(1024 * 1024):
                    digest.update(data)
                    dest.write(data)
            identity["files"][name] = digest.hexdigest()
        archive.writestr("checkpoint-manifest.json", json.dumps(identity, indent=2))
    with zipfile.ZipFile(temporary) as archive:
        for name, expected in identity["files"].items():
            with archive.open(name) as source:
                if hashlib.file_digest(source, "sha256").hexdigest() != expected:
                    raise RuntimeError("checkpoint digest mismatch: " + name)
    temporary.rename(target)
    with target.open("rb") as source:
        CHECKPOINT_PARENT = hashlib.file_digest(source, "sha256").hexdigest()
    target.with_suffix(".sha256").write_text(CHECKPOINT_PARENT + "  " + target.name + "\n")


def command_ok(command, label):
    return full.assert_command_ok(full.rcon(command), label)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("1k proof requires an initially absent disposable world")
    if not SEED or len(SEED) > 64:
        raise RuntimeError("invalid disposable-world seed")

    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "level-name=world\nlevel-seed=" + SEED + "\nonline-mode=false\n"
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "pause-when-empty-seconds=-1\n"
        "enable-rcon=true\nrcon.port=" + str(single.RCON_PORT) + "\n"
        "rcon.password=" + single.PASSWORD + "\nserver-port=25591\n")
    cfg = RUN / "config"
    cfg.mkdir(exist_ok=True)
    (cfg / "oceancanvas-core.properties").write_text(
        "mode=CORE_AUTHORING\ncanvasSize=1000\ncenterX=0\ncenterZ=0\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=1024\nmaxChecksPerTick=4096\n"
        "stageWallBudgetMicros=6000\nphysicalSettleTicks=20\nlightSettleTicks=20\n")

    evidence = {"seed": SEED, "canvasSize": CANVAS_SIZE, "center": [CENTER, CENTER]}
    process, sink, log = launch("flatten-session-1.log")
    try:
        evidence["flatten_start"] = command_ok("oceancanvas flatten start ERASE_CONFIGURED_CANVAS", "flatten start")
        progress = wait_for_progress(FLATTEN_STATE, 0.10, 10800, process, log)
        evidence["interruption_nextIndex"] = int(progress.get("nextIndex", "0"))
        evidence["interruption_totalChunks"] = int(progress.get("totalChunks", "0"))
    finally:
        evidence["flatten_interrupt_stop"] = stop(process, sink)

    process, sink, log = launch("flatten-session-2.log")
    try:
        before = props(FLATTEN_STATE)
        if before.get("status") not in {"RUNNING", "PAUSED", "COMPLETE"}:
            raise RuntimeError("flatten durable state did not survive restart: " + repr(before))
        if before.get("status") == "PAUSED":
            evidence["flatten_resume"] = command_ok("oceancanvas flatten resume", "flatten resume")
        flat = await_status(FLATTEN_STATE, "COMPLETE", 18000, process, log)
        evidence["flatten_complete"] = {"nextIndex": int(flat.get("nextIndex", "0")), "totalChunks": int(flat.get("totalChunks", "0"))}
        if evidence["flatten_complete"]["nextIndex"] != evidence["flatten_complete"]["totalChunks"]:
            raise RuntimeError("flatten COMPLETE cursor mismatch")
        evidence["shipwreck_rule"] = command_ok("oceancanvas structurerules set shipwreck force_on", "shipwreck rule")
        evidence["monument_rule"] = command_ok("oceancanvas structurerules set monument force_on", "monument rule")
        evidence["shipwreck_start"] = command_ok("oceancanvas shipwreck populate start", "shipwreck population")
        ship = await_status(SHIPWRECK_STATE, "COMPLETE", 1800, process, log)
        evidence["shipwrecks_placed"] = int(ship.get("placed", "0"))
        evidence["monument_start"] = command_ok("oceancanvas monument populate start", "monument population")
        monument = await_status(MONUMENT_STATE, "COMPLETE", 1800, process, log)
        evidence["monuments_placed"] = int(monument.get("placed", "0"))
        evidence["health_start"] = command_ok("oceancanvas health deep", "deep health")
        health = await_status(HEALTH_STATE, "COMPLETE", 7200, process, log)
        evidence["health"] = dict(health)
    finally:
        evidence["post_flatten_stop"] = stop(process, sink)

    process, sink, log = launch("restore-session.log")
    try:
        if props(FLATTEN_STATE).get("status") != "COMPLETE":
            raise RuntimeError("flatten terminal state did not survive pre-restore cold restart")
        evidence["restore_start"] = command_ok("oceancanvas restore start RESTORE_CONFIGURED_CANVAS", "restore start")
        restored = await_status(RESTORE_STATE, "COMPLETE", 18000, process, log)
        evidence["restore_complete"] = {"nextIndex": int(restored.get("nextIndex", "0")), "totalChunks": int(restored.get("totalChunks", "0"))}
        if evidence["restore_complete"]["nextIndex"] != evidence["restore_complete"]["totalChunks"]:
            raise RuntimeError("restore COMPLETE cursor mismatch")
    finally:
        evidence["restore_stop"] = stop(process, sink)

    process, sink, log = launch("final-cold-restart.log")
    try:
        if props(FLATTEN_STATE).get("status") != "COMPLETE" or props(RESTORE_STATE).get("status") != "COMPLETE":
            raise RuntimeError("terminal flatten/restore state did not survive final cold restart")
        evidence["final_restore_status"] = command_ok("oceancanvas restore status", "final restore status")
    finally:
        evidence["final_stop"] = stop(process, sink)

    evidence["verdict"] = "PASS"
    return evidence


if __name__ == "__main__":
    started = time.time()
    try:
        result = main()
        code = 0
    except BaseException as exc:
        result = {"verdict": "FAIL", "error": str(exc)}
        code = 1
    result["elapsedSeconds"] = round(time.time() - started, 3)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    sys.exit(code)

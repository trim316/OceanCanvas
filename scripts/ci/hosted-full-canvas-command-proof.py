#!/usr/bin/env python3
"""Disposable command-driven full-Canvas flatten/restore proof. Never touches a user world."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time

HERE = Path(__file__).resolve()
spec_single = importlib.util.spec_from_file_location("single_hosted", HERE.with_name("hosted-minecraft-proof.py"))
single = importlib.util.module_from_spec(spec_single)
spec_single.loader.exec_module(single)
spec_two = importlib.util.spec_from_file_location("two_hosted", HERE.with_name("hosted-two-chunk-proof.py"))
two = importlib.util.module_from_spec(spec_two)
spec_two.loader.exec_module(two)

PROJECT = single.PROJECT
RUN = single.RUN
WORLD = RUN / "world"
ROOT = WORLD / "oceancanvas-core" / "full-canvas"
CHUNKS = ROOT / "chunks"
FLATTEN_STATE = ROOT / "operation.properties"
RESTORE_STATE = ROOT / "restore.properties"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-full-canvas-command-proof"
SEED = os.environ.get("OCEANCANVAS_FULL_COMMAND_SEED", "4182040")
TARGETS = ((32, 32), (33, 32), (32, 33), (33, 33))
FLATTEN_STAGES = single.STAGES[:7]
two.ROOT = CHUNKS

def recv_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise ConnectionError("RCON response truncated")
        data += chunk
    return data

def rcon(command):
    with socket.create_connection(("127.0.0.1", single.RCON_PORT), timeout=5) as sock:
        sock.settimeout(5)

        def send(pid, typ, text):
            body = struct.pack("<ii", pid, typ) + text.encode() + b"\x00\x00"
            sock.sendall(struct.pack("<i", len(body)) + body)

        def receive():
            size = struct.unpack("<i", recv_exact(sock, 4))[0]
            payload = recv_exact(sock, size)
            pid, typ = struct.unpack("<ii", payload[:8])
            text = payload[8:-2].decode("utf-8", errors="replace")
            return pid, typ, text

        send(41, 3, single.PASSWORD)
        authenticated = False
        for _ in range(3):
            pid, typ, _ = receive()
            if pid == -1:
                raise RuntimeError("RCON authentication failed")
            if pid == 41 and typ == 2:
                authenticated = True
                break
        if not authenticated:
            raise RuntimeError("RCON authentication response missing")

        send(42, 2, command)
        pid, _, text = receive()
        if pid != 42:
            raise RuntimeError("unexpected RCON response id for command: " + command)
        return text.strip()

def wait_rcon(process, log_path, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Minecraft server exited before RCON became ready")
        try:
            rcon("list")
            return
        except (OSError, RuntimeError, ConnectionError):
            time.sleep(1)
    tail = log_path.read_text(errors="replace")[-8000:] if log_path.exists() else ""
    raise RuntimeError("RCON did not become ready; log tail:\n" + tail)

def launch(name):
    path = OUTPUT / name
    sink = path.open("wb")
    process = subprocess.Popen(
        ["./gradlew", "--no-daemon", "runServer"],
        cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    try:
        wait_rcon(process, path)
    except BaseException:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
        sink.close()
        raise
    return process, sink, path

def stop(process, sink):
    try:
        response = rcon("stop")
        process.wait(timeout=120)
        if process.returncode != 0:
            raise RuntimeError("Minecraft server did not stop cleanly")
        return response
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
        sink.close()

def prop(path, key):
    return single.properties(path).get(key)

def await_state(path, wanted, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file():
            p = single.properties(path)
            if p.get("status") == wanted:
                return p
        time.sleep(0.5)
    raise RuntimeError("state did not reach " + wanted + ": " + str(path))

def assert_pause_stable(path):
    first = await_state(path, "PAUSED", 30)
    index = first.get("nextIndex")
    time.sleep(3)
    second = single.properties(path)
    if second.get("status") != "PAUSED" or second.get("nextIndex") != index:
        raise RuntimeError("cancel/pause allowed progress after durable pause")
    return int(index)

def chunk_root(target):
    return CHUNKS / ("chunk_%d_%d" % target)

def flatten_chunk_report(target):
    records = two.journal(target)
    if len(records) != len(FLATTEN_STAGES) or records[-1][5] != "VERIFIED":
        raise RuntimeError("flatten chunk did not stop at durable VERIFIED: " + str(target))
    if [r[5] for r in records] != FLATTEN_STAGES:
        raise RuntimeError("flatten stage sequence mismatch: " + str(target))
    live = chunk_root(target) / "preimage-blockstates.bin"
    archive = chunk_root(target) / "preimage-blockstates.bin.completed.archive"
    if not live.is_file() or archive.exists():
        raise RuntimeError("flatten did not retain exactly one live immutable preimage: " + str(target))
    if live.stat().st_size > 16 * 1024 * 1024:
        raise RuntimeError("preimage exceeded bounded size")
    return {
        "target": target,
        "preimage_sha256": hashlib.sha256(live.read_bytes()).hexdigest(),
        "journal_transitions": len(records),
    }

def assert_command_ok(response, action):
    low = response.lower()
    if "unknown" in low or "refused" in low or "failed" in low or "could not" in low:
        raise RuntimeError(action + " command failed: " + response)
    return response

def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("full-Canvas command proof requires an initially absent disposable world")
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
        "mode=CORE_AUTHORING\ncanvasSize=32\ncenterX=528\ncenterZ=528\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=512\nmaxChecksPerTick=2048\n"
        "stageWallBudgetMicros=3000\nphysicalSettleTicks=20\nlightSettleTicks=20\n")

    command_evidence = {}

    process, sink, _ = launch("flatten-session.log")
    try:
        command_evidence["flatten_start"] = assert_command_ok(
            rcon("oceancanvas flatten start ERASE_CONFIGURED_CANVAS"), "flatten start")
        command_evidence["flatten_cancel"] = assert_command_ok(
            rcon("oceancanvas flatten cancel"), "flatten cancel")
        command_evidence["flatten_paused_at"] = assert_pause_stable(FLATTEN_STATE)
        command_evidence["flatten_resume"] = assert_command_ok(
            rcon("oceancanvas flatten resume"), "flatten resume")
        flat = await_state(FLATTEN_STATE, "COMPLETE", 1500)
        if flat.get("totalChunks") != "4" or flat.get("nextIndex") != "4":
            raise RuntimeError("full-Canvas flatten cursor/total mismatch")
        command_evidence["flatten_status"] = assert_command_ok(
            rcon("oceancanvas flatten status"), "flatten status")
        flatten_reports = [flatten_chunk_report(t) for t in TARGETS]
    finally:
        command_evidence["flatten_stop"] = stop(process, sink)

    process, sink, _ = launch("flatten-cold-restart.log")
    try:
        cold_flat = single.properties(FLATTEN_STATE)
        if cold_flat.get("status") != "COMPLETE" or RESTORE_STATE.exists():
            raise RuntimeError("flatten cold restart changed terminal state or created restore state")
        cold_reports = [flatten_chunk_report(t) for t in TARGETS]
        if cold_reports != flatten_reports:
            raise RuntimeError("flatten cold restart altered immutable preimage evidence")

        command_evidence["restore_start"] = assert_command_ok(
            rcon("oceancanvas restore start RESTORE_CONFIGURED_CANVAS"), "restore start")
        command_evidence["restore_cancel"] = assert_command_ok(
            rcon("oceancanvas restore cancel"), "restore cancel")
        command_evidence["restore_paused_at"] = assert_pause_stable(RESTORE_STATE)
        command_evidence["restore_resume"] = assert_command_ok(
            rcon("oceancanvas restore resume"), "restore resume")
        restored = await_state(RESTORE_STATE, "COMPLETE", 1500)
        if restored.get("totalChunks") != "4" or restored.get("nextIndex") != "4":
            raise RuntimeError("full-Canvas restore cursor/total mismatch")
        command_evidence["restore_status"] = assert_command_ok(
            rcon("oceancanvas restore status"), "restore status")
        restore_reports = [two.independently_verify_chunk(t) for t in TARGETS]
        for before, after in zip(flatten_reports, restore_reports):
            if before["preimage_sha256"] != after["immutable_backup_sha256"]:
                raise RuntimeError("restore archive hash differs from captured flatten preimage")
        for prior, later in zip(restore_reports, restore_reports[1:]):
            if later["ticket_installed_ms"] < prior["ticket_released_ms"]:
                raise RuntimeError("full-Canvas restore overlapped owned chunk tickets")
    finally:
        command_evidence["restore_stop"] = stop(process, sink)

    process, sink, _ = launch("restore-cold-restart.log")
    try:
        if prop(FLATTEN_STATE, "status") != "COMPLETE" or prop(RESTORE_STATE, "status") != "COMPLETE":
            raise RuntimeError("terminal full-Canvas state did not survive final cold restart")
        final_reports = [two.independently_verify_chunk(t) for t in TARGETS]
        if final_reports != restore_reports:
            raise RuntimeError("final cold restart changed immutable restored evidence")
        command_evidence["final_restore_status"] = assert_command_ok(
            rcon("oceancanvas restore status"), "final restore status")
    finally:
        command_evidence["final_stop"] = stop(process, sink)

    return {
        "verdict": "PASS",
        "seed": SEED,
        "canvasSize": 32,
        "center": [528, 528],
        "targets": TARGETS,
        "flatten": flatten_reports,
        "restore": restore_reports,
        "commandEvidence": command_evidence,
        "coldRestartAfterFlatten": True,
        "coldRestartAfterRestore": True,
        "simultaneouslyActiveChunksMax": 1,
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
    print(json.dumps(result, indent=2))
    sys.exit(code)

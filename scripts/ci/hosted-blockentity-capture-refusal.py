#!/usr/bin/env python3
"""Disposable Minecraft proof: capture real chest NBT, publish sidecar, still refuse mutation."""
from disposable_world_guard import require_pristine_disposable_world
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time

PROJECT = Path(__file__).resolve().parents[2] / "runtime-src"
RUN = PROJECT / "run"
WORLD = RUN / "world"
STATE = WORLD / "oceancanvas-core" / "single-chunk"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-capture-refusal"
RCON_PORT = 25586
PASSWORD = "oceancanvas-blockentity-proof"
TARGET = (512, 25, 512)
MAGIC = 0x4F434245


def rcon(command):
    with socket.create_connection(("127.0.0.1", RCON_PORT), timeout=5) as sock:
        sock.settimeout(5)
        def send(pid, typ, text):
            body = struct.pack("<ii", pid, typ) + text.encode() + b"\x00\x00"
            sock.sendall(struct.pack("<i", len(body)) + body)
        def recv():
            size_raw = sock.recv(4)
            if len(size_raw) != 4:
                raise RuntimeError("RCON size truncated")
            size = struct.unpack("<i", size_raw)[0]
            payload = b""
            while len(payload) < size:
                part = sock.recv(size - len(payload))
                if not part:
                    raise RuntimeError("RCON payload truncated")
                payload += part
            pid, typ = struct.unpack("<ii", payload[:8])
            return pid, typ, payload[8:-2].decode(errors="replace")
        send(101, 3, PASSWORD)
        ok = False
        for _ in range(3):
            pid, typ, _ = recv()
            if pid == -1:
                raise RuntimeError("RCON authentication failed")
            if pid == 101 and typ == 2:
                ok = True
                break
        if not ok:
            raise RuntimeError("RCON authentication response missing")
        send(102, 2, command)
        pid, _, text = recv()
        if pid != 102:
            raise RuntimeError("RCON command response identity mismatch")
        return text


def server_properties():
    return (
        "level-name=world\nlevel-seed=4182033\nonline-mode=false\nspawn-protection=0\n"
        "view-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\n"
        "enable-rcon=true\nrcon.port=" + str(RCON_PORT) + "\n"
        "rcon.password=" + PASSWORD + "\nserver-port=25591\n"
    )


def write_core(armed):
    cfg = RUN / "config"
    cfg.mkdir(exist_ok=True)
    if armed:
        text = (
            "mode=CORE_AUTHORING\ncanvasSize=20000\ncenterX=0\ncenterZ=0\n"
            "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
            "expansionEnabled=false\nsingleChunkEnabled=true\n"
            "singleChunkX=32\nsingleChunkZ=32\nsingleChunkConfirm=ERASE_CHUNK_32_32\n"
            "maxBlockWritesPerTick=256\nmaxChecksPerTick=1024\n"
            "stageWallBudgetMicros=3000\nphysicalSettleTicks=40\n"
            "lightSettleTicks=40\nacceptanceHarnessEnabled=false\n"
        )
    else:
        text = (
            "mode=SAFE_HOLD\ncanvasSize=20000\ncenterX=0\ncenterZ=0\n"
            "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
            "expansionEnabled=false\nsingleChunkEnabled=false\n"
            "maxBlockWritesPerTick=256\nmaxChecksPerTick=1024\n"
            "stageWallBudgetMicros=3000\nphysicalSettleTicks=40\n"
            "lightSettleTicks=40\nacceptanceHarnessEnabled=false\n"
        )
    (cfg / "oceancanvas-core.properties").write_text(text)


def start(log_path):
    sink = log_path.open("wb")
    process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
        cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    return process, sink


def wait_for(log_path, token, process, seconds=240):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        text = log_path.read_text(errors="replace") if log_path.exists() else ""
        if token in text:
            return text
        if process.poll() is not None:
            raise RuntimeError("Minecraft exited before: " + token)
        time.sleep(1)
    raise RuntimeError("timeout waiting for: " + token)


def stop(process):
    if process.poll() is not None:
        return
    try:
        rcon("stop")
        process.wait(timeout=90)
    except BaseException:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()


def read_int(data, offset):
    if offset + 4 > len(data):
        raise RuntimeError("sidecar truncated")
    return struct.unpack(">i", data[offset:offset+4])[0], offset + 4


def read_string(data, offset, max_len):
    length, offset = read_int(data, offset)
    if length < 1 or length > max_len or offset + length > len(data):
        raise RuntimeError("invalid sidecar string length")
    return data[offset:offset+length].decode("utf-8"), offset + length


def parse_sidecar(path):
    raw = path.read_bytes()
    if len(raw) < 64:
        raise RuntimeError("sidecar too small")
    payload, stored_digest = raw[:-32], raw[-32:]
    if hashlib.sha256(payload).digest() != stored_digest:
        raise RuntimeError("sidecar SHA-256 mismatch")
    off = 0
    magic, off = read_int(payload, off)
    schema, off = read_int(payload, off)
    if magic != MAGIC or schema != 1:
        raise RuntimeError("unexpected sidecar magic/schema")
    operation, off = read_string(payload, off, 4096)
    chunk_x, off = read_int(payload, off)
    chunk_z, off = read_int(payload, off)
    preimage_sha, off = read_string(payload, off, 64)
    state_count, off = read_int(payload, off)
    entry_count, off = read_int(payload, off)
    entries = []
    for _ in range(entry_count):
        index, off = read_int(payload, off)
        type_id, off = read_string(payload, off, 1024)
        nbt_len, off = read_int(payload, off)
        if nbt_len < 1 or nbt_len > 1024 * 1024 or off + nbt_len > len(payload):
            raise RuntimeError("invalid NBT payload length")
        nbt = payload[off:off+nbt_len]
        off += nbt_len
        entries.append((index, type_id, nbt))
    if off != len(payload):
        raise RuntimeError("trailing sidecar payload")
    return {
        "operation": operation, "chunk": [chunk_x, chunk_z],
        "preimage_sha256": preimage_sha, "state_count": state_count,
        "entries": entries, "sidecar_sha256": hashlib.sha256(raw).hexdigest(),
    }


def main():
    require_pristine_disposable_world(PROJECT, RUN, RUN / "world", os.environ)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("proof requires initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=False)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(server_properties())

    # Session 1 has zero OceanCanvas mutation authority. Create and persist a
    # real vanilla chest/inventory before arming the one-chunk authoring gate.
    write_core(False)
    setup_log = OUTPUT / "setup-safe-hold.log"
    process, sink = start(setup_log)
    try:
        wait_for(setup_log, "Done (", process)
        rcon("forceload add 512 512")
        time.sleep(1)
        response = rcon("setblock 512 25 512 minecraft:chest")
        if "changed" not in response.lower():
            raise RuntimeError("failed to place chest fixture: " + response)
        item = rcon("item replace block 512 25 512 container.0 with minecraft:diamond 3")
        if "replaced" not in item.lower() and "modified" not in item.lower():
            raise RuntimeError("failed to seed chest inventory: " + item)
        before = rcon("data get block 512 25 512")
        if "diamond" not in before.lower():
            raise RuntimeError("chest inventory fixture missing before refusal test: " + before)
        rcon("save-all flush")
        rcon("forceload remove 512 512")
    finally:
        stop(process)
        sink.close()

    # Arm exactly one chunk, deliberately WITHOUT the independent
    # oceancanvas-block-entity-recovery.properties consent file.
    write_core(True)
    be_consent = RUN / "config" / "oceancanvas-block-entity-recovery.properties"
    if be_consent.exists():
        raise RuntimeError("no-consent proof unexpectedly found block-entity recovery authority")

    refusal_log = OUTPUT / "no-consent-refusal.log"
    process, sink = start(refusal_log)
    try:
        deadline = time.monotonic() + 360
        refused = False
        while time.monotonic() < deadline:
            text = refusal_log.read_text(errors="replace") if refusal_log.exists() else ""
            journal = STATE / "transitions.journal"
            stages = []
            if journal.exists():
                for line in journal.read_text(errors="replace").splitlines():
                    fields = line.split("\t")
                    if len(fields) >= 6:
                        stages.append(fields[5])
            if "preimage capture refuses block-entity state or entity" in text:
                refused = True
                break
            if stages and stages[-1] == "FAILED":
                raise RuntimeError("pipeline failed, but not at no-consent block-entity capture guard")
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before no-consent refusal evidence")
            time.sleep(0.25)
        if not refused:
            raise RuntimeError("no-consent block-entity refusal did not occur")

        # No durable backup may be published from a partially scanned,
        # unauthorized block-entity preimage.
        if (STATE / "preimage-blockstates.bin").exists():
            raise RuntimeError("no-consent refusal published a block-state preimage")
        if (STATE / "preimage-blockentities.ocbe").exists():
            raise RuntimeError("no-consent refusal published a block-entity sidecar")

        # FAILED closes OceanCanvas' owned ticket. Inspect this disposable chunk
        # independently and prove the original chest/inventory survived.
        rcon("forceload add 512 512")
        time.sleep(1)
        after = rcon("data get block 512 25 512")
        rcon("forceload remove 512 512")
        if "chest" not in after.lower() or "diamond" not in after.lower():
            raise RuntimeError("no-consent refusal failed to preserve chest/inventory: " + after)

        return {
            "verdict": "PASS",
            "seed": "4182033",
            "block_entity_recovery_consent": False,
            "refused_before_preimage_publication": True,
            "state_preimage_published": False,
            "block_entity_sidecar_published": False,
            "chest_inventory_survived": True,
            "target": list(TARGET),
        }
    finally:
        stop(process)
        sink.close()


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

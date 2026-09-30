#!/usr/bin/env python3
"""Disposable Minecraft proof: late block entity is never overwritten after state-only capture."""
import importlib.util
import json
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location(
    "single_hosted", Path(__file__).resolve().with_name("hosted-minecraft-proof.py"))
single = importlib.util.module_from_spec(spec)
spec.loader.exec_module(single)

PROJECT = single.PROJECT
RUN = single.RUN
STATE = RUN / "world" / "oceancanvas-core" / "single-chunk"
OUTPUT = Path(single.os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-late-blockentity-refusal"
TARGET = (512, 25, 512)


def rcon(command):
    with socket.create_connection(("127.0.0.1", single.RCON_PORT), timeout=5) as sock:
        sock.settimeout(5)
        def packet(pid, typ, text):
            body = struct.pack("<ii", pid, typ) + text.encode() + b"\x00\x00"
            sock.sendall(struct.pack("<i", len(body)) + body)
        def response():
            size = struct.unpack("<i", sock.recv(4))[0]
            payload = b""
            while len(payload) < size:
                block = sock.recv(size - len(payload))
                if not block:
                    raise ConnectionError("RCON response truncated")
                payload += block
            pid, typ = struct.unpack("<ii", payload[:8])
            text = payload[8:-2].decode(errors="replace")
            return pid, typ, text
        packet(101, 3, single.PASSWORD)
        authenticated = False
        for _ in range(3):
            pid, typ, _ = response()
            if pid == -1:
                raise RuntimeError("RCON authentication failed")
            if pid == 101 and typ == 2:
                authenticated = True
                break
        if not authenticated:
            raise RuntimeError("RCON authentication response missing")
        packet(102, 2, command)
        pid, _, text = response()
        if pid != 102:
            raise RuntimeError("RCON command response identity mismatch")
        return text


def journal_lines():
    path = STATE / "transitions.journal"
    return path.read_text(errors="replace").splitlines() if path.exists() else []


def main():
    if (RUN / "world").exists():
        raise RuntimeError("late block-entity proof requires a fresh disposable world")
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "level-name=world\nlevel-seed=4182032\nonline-mode=false\nspawn-protection=0\n"
        "view-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\n"
        "enable-rcon=true\nrcon.port=" + str(single.RCON_PORT) + "\n"
        "rcon.password=" + single.PASSWORD + "\nserver-port=25591\n")
    cfg = RUN / "config"
    cfg.mkdir(exist_ok=True)
    (cfg / "oceancanvas-core.properties").write_text(
        "mode=CORE_AUTHORING\ncanvasSize=20000\ncenterX=0\ncenterZ=0\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=false\nsingleChunkEnabled=true\n"
        "singleChunkX=32\nsingleChunkZ=32\nsingleChunkConfirm=ERASE_CHUNK_32_32\n"
        "maxBlockWritesPerTick=256\nmaxChecksPerTick=1024\n"
        "stageWallBudgetMicros=3000\nphysicalSettleTicks=40\n"
        "lightSettleTicks=40\nacceptanceHarnessEnabled=false\n")

    log = OUTPUT / "server.log"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 360
            captured = False
            while time.monotonic() < deadline:
                lines = journal_lines()
                stages = [line.split("\t")[5] for line in lines if len(line.split("\t")) > 5]
                if "FAILED" in stages:
                    break
                if "PREIMAGE_CAPTURED" in stages and not captured:
                    response = rcon("setblock 512 25 512 minecraft:chest")
                    captured = True
                    if "could not" in response.lower() or "failed" in response.lower():
                        raise RuntimeError("failed to insert disposable chest fixture: " + response)
                if process.poll() is not None:
                    raise RuntimeError("Minecraft exited before late block-entity refusal")
                time.sleep(1)
            else:
                raise RuntimeError("late block-entity refusal proof timed out")

            text = log.read_text(errors="replace")
            if "physical authoring refuses block entity introduced after preimage" not in text:
                raise RuntimeError("pipeline failed, but not at the late block-entity write guard")
            block = rcon("data get block 512 25 512")
            if "minecraft:chest" not in block and "Items" not in block:
                raise RuntimeError("late chest block entity did not survive refusal: " + block)
            return {
                "verdict": "PASS",
                "seed": "4182032",
                "inserted_after_stage": "PREIMAGE_CAPTURED",
                "refused_before_unbacked_overwrite": True,
                "block_entity_survived": True,
                "target": list(TARGET),
            }
        finally:
            if process.poll() is None:
                try:
                    single.rcon_stop()
                    process.wait(timeout=60)
                except BaseException:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()


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

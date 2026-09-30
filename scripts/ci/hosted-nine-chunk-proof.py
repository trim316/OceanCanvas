#!/usr/bin/env python3
"""Disposable-only clean nine-chunk scale proof; never uses a personal/local world."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

spec = importlib.util.spec_from_file_location(
    "fourchunk", Path(__file__).resolve().with_name("hosted-four-chunk-proof.py"))
four = importlib.util.module_from_spec(spec)
spec.loader.exec_module(four)

single = four.single
PROJECT = four.PROJECT
RUN = four.RUN
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-nine-chunk-proof"
ROOT = RUN / "world" / "oceancanvas-core" / "nine-chunk-canary"
SEED = os.environ.get("OCEANCANVAS_NINE_CHUNK_SEED", "4182035")
TARGETS = tuple((32 + column, 32 + row) for row in range(3) for column in range(3))
STAGES = four.STAGES


def chunk_root(target):
    return ROOT / ("chunk_%d_%d" % target)


def journal(target):
    path = chunk_root(target) / "transitions.journal"
    if not path.is_file():
        return []
    data = path.read_bytes()
    if data and not data.endswith(b"\n"):
        raise RuntimeError("unterminated durable journal: " + str(target))
    result = []
    previous = "DISCOVERED"
    import zlib
    for index, line in enumerate(data.decode("utf-8").splitlines()):
        fields = line.split("\t")
        if len(fields) != 10:
            raise RuntimeError("malformed journal field count in " + str(target))
        payload = "\t".join(fields[:-1])
        if zlib.crc32(payload.encode("utf-8")) != int(fields[-1]):
            raise RuntimeError("journal CRC mismatch in " + str(target))
        if (fields[0] != str(index) or fields[2:4] != list(map(str, target))
                or fields[4] != previous
                or fields[5] != (STAGES[index] if index < len(STAGES) else "INVALID")):
            if fields[5] == "FAILED":
                raise RuntimeError("durable FAILED at %s after %s: %s"
                                   % (target, fields[4], fields[8]))
            raise RuntimeError("journal stage/identity mismatch in " + str(target))
        previous = fields[5]
        result.append(fields)
    return result


def verify_chunk(target):
    original = four.ROOT
    try:
        four.ROOT = ROOT
        return four.verify_chunk(target)
    finally:
        four.ROOT = original


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (RUN / "world").exists():
        raise RuntimeError("nine-chunk proof requires an initially absent disposable world")
    if not SEED or len(SEED) > 64:
        raise RuntimeError("invalid isolated-world seed")

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
        "mode=CORE_AUTHORING\ncanvasSize=20000\ncenterX=0\ncenterZ=0\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=256\nmaxChecksPerTick=1024\n"
        "stageWallBudgetMicros=3000\nphysicalSettleTicks=40\nlightSettleTicks=40\n")
    lines = ["enabled=true"]
    for index, (x, z) in enumerate(TARGETS):
        lines += [
            "chunk%dX=%d" % (index, x),
            "chunk%dZ=%d" % (index, z),
            "chunk%dConfirm=ERASE_CHUNK_%d_%d" % (index, x, z),
        ]
    (cfg / "oceancanvas-nine-chunk-canary.properties").write_text("\n".join(lines) + "\n")

    session_log = OUTPUT / "session.log"
    with session_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 6600
            while time.monotonic() < deadline:
                journals = [journal(target) for target in TARGETS]
                for idx in range(1, len(TARGETS)):
                    if journals[idx] and (len(journals[idx - 1]) != len(STAGES)
                            or journals[idx - 1][-1][5] != "COMPLETE"):
                        raise RuntimeError("later nine-chunk target began before predecessor COMPLETE")
                if all(len(records) == len(STAGES) for records in journals):
                    break
                if process.poll() is not None:
                    raise RuntimeError("disposable nine-chunk Minecraft server exited early")
                text = session_log.read_text(errors="replace")
                if "NINE-CHUNK-INIT-FAILED" in text:
                    raise RuntimeError("nine-chunk runtime admission refused; see server log")
                if "NINE-CHUNK-CANARY-FAILED" in text:
                    time.sleep(0.25)
                if "Server empty for" in text and "pausing" in text:
                    raise RuntimeError("disposable nine-chunk server unexpectedly paused")
                time.sleep(1)
            else:
                raise RuntimeError("nine-chunk lifecycle timed out")

            reports = [verify_chunk(target) for target in TARGETS]
            for prior, later in zip(reports, reports[1:]):
                if later["ticket_installed_ms"] < prior["ticket_released_ms"]:
                    raise RuntimeError("concurrent nine-chunk ticket ownership detected")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("nine-chunk server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()

    cold_log = OUTPUT / "cold-restart.log"
    with cold_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                text = cold_log.read_text(errors="replace")
                if "NINE-CHUNK-INIT-FAILED" in text:
                    raise RuntimeError("cold restart refused completed nine-chunk evidence")
                if "NINE-CHUNK-CANARY-OPEN" in text and "completeCount=9" in text:
                    break
                if process.poll() is not None:
                    raise RuntimeError("Minecraft exited before nine-chunk cold-restart proof")
                time.sleep(1)
            else:
                raise RuntimeError("nine-chunk cold-restart evidence check timed out")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("nine-chunk cold-restart server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()

    final = [verify_chunk(target) for target in TARGETS]
    if final != reports:
        raise RuntimeError("nine-chunk cold restart altered immutable chunk evidence")
    return {
        "verdict": "PASS",
        "seed": SEED,
        "targets": TARGETS,
        "cold_restart": True,
        "simultaneously_active_chunks_max": 1,
        "chunks": final,
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
    if ROOT.exists():
        for target in TARGETS:
            source = chunk_root(target)
            target_dir = OUTPUT / ("chunk_%d_%d" % target)
            target_dir.mkdir(exist_ok=True)
            for name in ("operation.properties", "transitions.journal", "runtime-receipts.log",
                         "preimage-blockstates.bin", "preimage-blockstates.bin.completed.archive",
                         "preimage-blockentities.ocbe", "preimage-blockentities.ocbe.completed.archive"):
                path = source / name
                if path.exists():
                    (target_dir / name).write_bytes(path.read_bytes())
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(code)

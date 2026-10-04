#!/usr/bin/env python3
"""Disposable-only four-chunk scale proof; never uses a personal/local world."""
from disposable_world_guard import require_pristine_disposable_world
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location(
    "twochunk", Path(__file__).resolve().with_name("hosted-two-chunk-proof.py"))
two = importlib.util.module_from_spec(spec)
spec.loader.exec_module(two)

single = two.single
PROJECT = two.PROJECT
RUN = two.RUN
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-four-chunk-proof"
ROOT = RUN / "world" / "oceancanvas-core" / "four-chunk-canary"
SEED = os.environ.get("OCEANCANVAS_FOUR_CHUNK_SEED", "4182031")
TARGETS = ((32, 32), (33, 32), (32, 33), (33, 33))
STAGES = two.STAGES
INTERRUPT_INDEX_RAW = os.environ.get("OCEANCANVAS_FOUR_CHUNK_INTERRUPT_INDEX", "")
INTERRUPT_AFTER = os.environ.get("OCEANCANVAS_FOUR_CHUNK_INTERRUPT_AFTER", "")
ALLOWED_INTERRUPT_STAGES = ("PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "RESTORED")


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
    # Reuse the exact two-chunk independent archive/receipt verifier by
    # temporarily presenting this four-chunk root to its chunk_root helper.
    original = two.ROOT
    try:
        two.ROOT = ROOT
        return two.independently_verify_chunk(target)
    finally:
        two.ROOT = original


def main():
    require_pristine_disposable_world(PROJECT, RUN, RUN / "world", os.environ)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (RUN / "world").exists():
        raise RuntimeError("four-chunk proof requires an initially absent disposable world")
    if not SEED or len(SEED) > 64:
        raise RuntimeError("invalid isolated-world seed")
    interrupt_index = None
    if INTERRUPT_INDEX_RAW or INTERRUPT_AFTER:
        if not INTERRUPT_INDEX_RAW or not INTERRUPT_AFTER:
            raise RuntimeError("four-chunk interruption requires both index and stage")
        try:
            interrupt_index = int(INTERRUPT_INDEX_RAW)
        except ValueError as exc:
            raise RuntimeError("invalid four-chunk interruption index") from exc
        if interrupt_index <= 0 or interrupt_index >= len(TARGETS):
            raise RuntimeError("four-chunk interruption index must preserve at least one completed predecessor")
        if INTERRUPT_AFTER not in ALLOWED_INTERRUPT_STAGES:
            raise RuntimeError("unapproved four-chunk interruption stage: " + INTERRUPT_AFTER)

    RUN.mkdir(parents=True, exist_ok=False)
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
    (cfg / "oceancanvas-four-chunk-canary.properties").write_text(
        "enabled=true\n"
        "northWestX=32\nnorthWestZ=32\nnorthEastX=33\nnorthEastZ=32\n"
        "southWestX=32\nsouthWestZ=33\nsouthEastX=33\nsouthEastZ=33\n"
        "northWestConfirm=ERASE_CHUNK_32_32\n"
        "northEastConfirm=ERASE_CHUNK_33_32\n"
        "southWestConfirm=ERASE_CHUNK_32_33\n"
        "southEastConfirm=ERASE_CHUNK_33_33\n")

    first_log = OUTPUT / "first-session.log"
    interruption_done = False
    observed_interruption_stage = None
    predecessor_reports_before_interrupt = None
    interruption_log_sink = None
    with first_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 2400
            while time.monotonic() < deadline:
                journals = [journal(target) for target in TARGETS]
                for idx in range(1, len(TARGETS)):
                    if journals[idx] and (len(journals[idx - 1]) != len(STAGES)
                            or journals[idx - 1][-1][5] != "COMPLETE"):
                        raise RuntimeError("later four-chunk target began before predecessor COMPLETE")
                if interrupt_index is not None and not interruption_done:
                    records = journals[interrupt_index]
                    wanted_position = STAGES.index(INTERRUPT_AFTER)
                    if len(records) >= wanted_position + 1 and len(records) < len(STAGES):
                        for predecessor in range(interrupt_index):
                            if (len(journals[predecessor]) != len(STAGES)
                                    or journals[predecessor][-1][5] != "COMPLETE"):
                                raise RuntimeError("interruption reached before predecessor completed")
                        predecessor_reports_before_interrupt = [
                            verify_chunk(TARGETS[idx]) for idx in range(interrupt_index)
                        ]
                        observed_interruption_stage = records[-1][5]
                        process.kill()
                        process.wait(timeout=20)
                        interruption_done = True
                        (OUTPUT / "INTERRUPTION.txt").write_text(
                            "targetIndex=" + str(interrupt_index) + "\n"
                            + "target=" + str(TARGETS[interrupt_index]) + "\n"
                            + "requested=" + INTERRUPT_AFTER + "\n"
                            + "observed=" + observed_interruption_stage + "\n"
                            + "method=isolated-server-process-kill\n")
                        first_log = OUTPUT / "interrupted-restart.log"
                        interruption_log_sink = first_log.open("wb")
                        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
                            cwd=PROJECT, stdout=interruption_log_sink,
                            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
                        time.sleep(2)
                        continue
                    if len(records) == len(STAGES):
                        raise RuntimeError("interrupted target completed before requested stage could be killed")
                if all(len(records) == len(STAGES) for records in journals):
                    break
                if process.poll() is not None:
                    raise RuntimeError("disposable four-chunk Minecraft server exited early")
                text = first_log.read_text(errors="replace")
                if "FOUR-CHUNK-INIT-FAILED" in text:
                    raise RuntimeError("four-chunk runtime admission refused; see server log")
                if "FOUR-CHUNK-CANARY-FAILED" in text:
                    # Journal parser above will provide the durable first reason
                    # on the next iteration when the record is visible.
                    time.sleep(0.25)
                if "Server empty for" in text and "pausing" in text:
                    raise RuntimeError("disposable four-chunk server unexpectedly paused")
                time.sleep(1)
            else:
                raise RuntimeError("four-chunk lifecycle timed out")

            if interrupt_index is not None and not interruption_done:
                raise RuntimeError("requested four-chunk interruption never occurred")
            reports = [verify_chunk(target) for target in TARGETS]
            if predecessor_reports_before_interrupt is not None:
                if reports[:interrupt_index] != predecessor_reports_before_interrupt:
                    raise RuntimeError("completed predecessor evidence changed across later-chunk crash")
            for prior, later in zip(reports, reports[1:]):
                if later["ticket_installed_ms"] < prior["ticket_released_ms"]:
                    raise RuntimeError("concurrent four-chunk ticket ownership detected")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("four-chunk server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
            if interruption_log_sink is not None:
                interruption_log_sink.close()

    cold_log = OUTPUT / "cold-restart.log"
    with cold_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                text = cold_log.read_text(errors="replace")
                if "FOUR-CHUNK-INIT-FAILED" in text:
                    raise RuntimeError("cold restart refused completed four-chunk evidence")
                if "FOUR-CHUNK-CANARY-OPEN" in text and "completeCount=4" in text:
                    break
                if process.poll() is not None:
                    raise RuntimeError("Minecraft exited before four-chunk cold-restart proof")
                time.sleep(1)
            else:
                raise RuntimeError("four-chunk cold-restart evidence check timed out")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("four-chunk cold-restart server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()

    final = [verify_chunk(target) for target in TARGETS]
    if final != reports:
        raise RuntimeError("four-chunk cold restart altered immutable chunk evidence")
    return {
        "verdict": "PASS",
        "seed": SEED,
        "targets": TARGETS,
        "cold_restart": True,
        "requested_interruption_index": interrupt_index,
        "requested_interruption_stage": INTERRUPT_AFTER or None,
        "observed_interruption_stage": observed_interruption_stage,
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
                         "preimage-blockstates.bin", "preimage-blockstates.bin.completed.archive"):
                path = source / name
                if path.exists():
                    (target_dir / name).write_bytes(path.read_bytes())
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(code)

#!/usr/bin/env python3
"""Short disposable production-path performance probe; never uses a personal/local world."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import time

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("full", HERE.with_name("hosted-full-canvas-command-proof.py"))
full = importlib.util.module_from_spec(spec)
spec.loader.exec_module(full)

RUN = full.RUN
WORLD = full.WORLD
ROOT = full.ROOT
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-performance-preflight"
SEED = os.environ.get("OCEANCANVAS_PERF_SEED", "4182040")
TARGETS = full.TARGETS


def configure():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    # full.launch() writes its session log beneath full.OUTPUT. Point that
    # existing proof helper at this probe's output before launching so the
    # parent directory is guaranteed to exist and all evidence stays together.
    full.OUTPUT = OUTPUT
    if WORLD.exists():
        raise RuntimeError("performance preflight requires an initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "level-name=world\nlevel-seed=" + SEED + "\nonline-mode=false\n"
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "pause-when-empty-seconds=-1\n"
        "enable-rcon=true\nrcon.port=" + str(full.single.RCON_PORT) + "\n"
        "rcon.password=" + full.single.PASSWORD + "\nserver-port=25591\n")
    cfg = RUN / "config"
    cfg.mkdir(exist_ok=True)
    # Match the existing four-chunk command proof so results stay comparable.
    (cfg / "oceancanvas-core.properties").write_text(
        "mode=CORE_AUTHORING\ncanvasSize=32\ncenterX=528\ncenterZ=528\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=4096\nmaxChecksPerTick=16384\n"
        "stageWallBudgetMicros=15000\nphysicalSettleTicks=2\nlightSettleTicks=20\n")


def java_rss_kib():
    """Return aggregate RSS of runServer Java processes, excluding Gradle daemons when identifiable."""
    try:
        text = subprocess.check_output(["ps", "-eo", "rss=,args="], text=True)
    except Exception:
        return 0
    total = 0
    for line in text.splitlines():
        if "java" not in line.lower():
            continue
        if "runServer" not in line and "fabric-server-launch" not in line and "net.minecraft.server" not in line:
            continue
        try:
            total += int(line.strip().split(None, 1)[0])
        except (ValueError, IndexError):
            pass
    return total


def journal_metrics(target):
    records = full.two.journal(target)
    expected = full.FLATTEN_STAGES
    if len(records) != len(expected) or [r[5] for r in records] != expected:
        raise RuntimeError("performance probe chunk did not reach exact VERIFIED sequence: " + str(target))
    stamps = [int(r[1]) for r in records]
    deltas = {}
    for i in range(1, len(records)):
        deltas[records[i][5]] = stamps[i] - stamps[i - 1]
    return {
        "target": list(target),
        "elapsed_ms": stamps[-1] - stamps[0],
        "stage_delta_ms": deltas,
    }


def main():
    configure()
    source_sha = os.environ.get("GITHUB_SHA", "unknown")
    process, sink, _ = full.launch("preflight-session.log")
    samples = []
    sampling = True

    def sampler():
        while sampling:
            samples.append({"t": time.monotonic(), "rss_kib": java_rss_kib()})
            time.sleep(0.5)

    thread = threading.Thread(target=sampler, daemon=True)
    thread.start()
    progress = []
    stage_progress = []
    try:
        started = time.monotonic()
        full.assert_command_ok(full.rcon("oceancanvas flatten start ERASE_CONFIGURED_CANVAS"), "flatten start")
        last_index = -1
        last_change = started
        last_stage_change = started
        last_stage_count = -1
        max_stage_no_progress = 0.0
        max_no_progress = 0.0
        deadline = started + 900
        while time.monotonic() < deadline:
            # Sample durable journal transitions, not just nextIndex: a chunk can
            # spend a long time writing, settling, or finalizing at one index.
            # Incomplete journal reads during atomic replacement are retried.
            try:
                stage_count = sum(len(full.two.journal(t)) for t in TARGETS)
                if stage_count != last_stage_count:
                    now = time.monotonic()
                    if last_stage_count >= 0:
                        max_stage_no_progress = max(max_stage_no_progress, now - last_stage_change)
                    stage_progress.append({"transitions": stage_count, "elapsed_s": now - started})
                    last_stage_count = stage_count
                    last_stage_change = now
            except (OSError, ValueError):
                pass
            if full.FLATTEN_STATE.is_file():
                state = full.single.properties(full.FLATTEN_STATE)
                index = int(state.get("nextIndex", "0"))
                if index != last_index:
                    now = time.monotonic()
                    if last_index >= 0:
                        max_no_progress = max(max_no_progress, now - last_change)
                    progress.append({"index": index, "elapsed_s": now - started})
                    last_index = index
                    last_change = now
                if state.get("status") == "COMPLETE":
                    break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited during performance preflight")
            time.sleep(0.2)
        else:
            raise RuntimeError("performance preflight exceeded 15 minute screening bound")
        ended = time.monotonic()
        max_no_progress = max(max_no_progress, ended - last_change)
        max_stage_no_progress = max(max_stage_no_progress, ended - last_stage_change)
        reports = [full.flatten_chunk_report(t) for t in TARGETS]
        chunks = [journal_metrics(t) for t in TARGETS]
        elapsed = ended - started
        result = {
            "verdict": "PASS",
            "source_sha": source_sha,
            "seed": SEED,
            "workload": {"canvas_size_blocks": 32, "chunks": len(TARGETS)},
            "elapsed_s": elapsed,
            "chunk_throughput_per_min": len(TARGETS) * 60.0 / elapsed,
            "max_no_progress_s": max_no_progress,
            "max_stage_no_progress_s": max_stage_no_progress,
            "stage_progress": stage_progress,
            "peak_sampled_java_rss_kib": max((s["rss_kib"] for s in samples), default=0),
            "progress": progress,
            "chunks": chunks,
            "correctness": reports,
        }
        return result
    finally:
        sampling = False
        thread.join(timeout=2)
        try:
            full.stop(process, sink)
        except Exception:
            if process.poll() is None:
                process.kill()


if __name__ == "__main__":
    try:
        result = main()
        code = 0
    except BaseException as exc:
        result = {"verdict": "FAIL", "error": str(exc), "source_sha": os.environ.get("GITHUB_SHA", "unknown")}
        code = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(code)

#!/usr/bin/env python3
"""Disposable-only two-chunk canary; no personal Minecraft or local runner."""
from disposable_world_guard import require_pristine_disposable_world
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import zlib
import hashlib

spec = importlib.util.spec_from_file_location(
    "single_hosted", Path(__file__).resolve().with_name("hosted-minecraft-proof.py"))
single = importlib.util.module_from_spec(spec)
spec.loader.exec_module(single)

PROJECT = single.PROJECT
RUN = single.RUN
ROOT = RUN / "world" / "oceancanvas-core" / "two-chunk-canary"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-two-chunk-proof"
SEED = os.environ.get("OCEANCANVAS_TWO_CHUNK_SEED", "4182026")
STAGES = single.STAGES
TARGETS = ((32, 32), (33, 32))
INTERRUPT_AFTER = os.environ.get("OCEANCANVAS_TWO_CHUNK_INTERRUPT_SECOND_AFTER", "")
ALLOWED_INTERRUPTS = ("PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "RESTORED")


def chunk_root(target):
    x, z = target
    return ROOT / ("chunk_%d_%d" % (x, z))


def journal(target):
    path = chunk_root(target) / "transitions.journal"
    if not path.is_file():
        return []
    data = path.read_bytes()
    if data and not data.endswith(b"\n"):
        raise RuntimeError("unterminated durable journal: " + str(target))
    result = []
    previous = "DISCOVERED"
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


def independently_verify_chunk(target):
    from_state = chunk_root(target)
    records = journal(target)
    if len(records) != len(STAGES) or records[-1][5] != "COMPLETE":
        raise RuntimeError("chunk missing full immutable lifecycle: " + str(target))
    archive = from_state / "preimage-blockstates.bin.completed.archive"
    live = from_state / "preimage-blockstates.bin"
    if live.exists() or not archive.is_file() or archive.stat().st_size > 16 * 1024 * 1024:
        raise RuntimeError("completed chunk lost archive or retained unarchived backup")
    sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    receipt_path = from_state / "runtime-receipts.log"
    raw = receipt_path.read_bytes()
    if len(raw) > 8 * 1024 * 1024 or not raw.endswith(b"\n"):
        raise RuntimeError("unbounded or unterminated receipt chain")
    wanted = {"PREIMAGE_CAPTURED": "preimageSha256",
              "RESTORE_COMPLETE": "preimageSha256",
              "RESTORE_VERIFIED": "preimageSha256",
              "TICKET_RELEASED": "preimageArchiveSha256"}
    stages = {x: 0 for x in wanted}
    release_time = None
    first_ticket_time = None
    operation = single.properties(from_state / "operation.properties").get("operationId")
    if not operation:
        raise RuntimeError("missing immutable operation manifest")
    for i, line in enumerate(raw.decode("utf-8").splitlines()):
        parts = line.split("\t")
        if len(parts) != 7 or parts[0] != str(i) or parts[3:5] != list(map(str, target)):
            raise RuntimeError("mixed receipt identity or sequence at %s:%d" % (target, i))
        payload = "\t".join(parts[:-1])
        if zlib.crc32(payload.encode("utf-8")) != int(parts[-1]):
            raise RuntimeError("forged receipt CRC at %s:%d" % (target, i))
        kind = parts[2]
        if kind == "TICKET_INSTALLED" and first_ticket_time is None:
            first_ticket_time = int(parts[1])
        if kind not in wanted:
            continue
        detail = parts[5].split(";")
        expected = wanted[kind] + "=" + sha
        if expected not in detail:
            if kind == "TICKET_RELEASED" and not any(
                    token.startswith("preimageArchiveSha256=") for token in detail):
                continue
            raise RuntimeError("source backup hash drift in %s receipt %s" % (target, kind))
        if kind == "TICKET_RELEASED":
            if "restoreVerified=true" not in detail:
                raise RuntimeError("release before exact restore verification")
            release_time = int(parts[1])
        elif "operation=" + operation not in detail:
            raise RuntimeError("different operation tried to certify another preimage")
        stages[kind] += 1
    if any(v < 1 for v in stages.values()) or first_ticket_time is None or release_time is None:
        raise RuntimeError("missing independent restored-archive or ticket receipt for " + str(target))
    return {"target": target, "immutable_backup_sha256": sha,
            "journal_transitions": len(records), "receipt_stages": stages,
            "ticket_installed_ms": first_ticket_time, "ticket_released_ms": release_time}


def main():
    require_pristine_disposable_world(PROJECT, RUN, RUN / "world", os.environ)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (RUN / "world").exists():
        raise RuntimeError("cloud two-chunk proof requires an initially absent disposable world")
    if not SEED or len(SEED) > 64:
        raise RuntimeError("invalid isolated-world seed")
    if INTERRUPT_AFTER and INTERRUPT_AFTER not in ALLOWED_INTERRUPTS:
        raise RuntimeError("unapproved two-chunk interruption boundary: " + INTERRUPT_AFTER)
    RUN.mkdir(parents=True, exist_ok=False)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "level-name=world\nlevel-seed=" + SEED + "\nonline-mode=false\n"
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        # Minecraft pauses unattended servers after 60s by default, which
        # suspends the tick-driven restore of the second destructive canary.
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
    (cfg / "oceancanvas-two-chunk-canary.properties").write_text(
        "enabled=true\nfirstX=32\nfirstZ=32\nsecondX=33\nsecondZ=32\n"
        "firstConfirm=ERASE_CHUNK_32_32\nsecondConfirm=ERASE_CHUNK_33_32\n")

    server_log = OUTPUT / "first-session.log"
    interruption_done = False
    interruption_stage_observed = None
    first_before_interruption = None
    interruption_log_sink = None
    with server_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 1800
            while time.monotonic() < deadline:
                first = journal(TARGETS[0])
                second = journal(TARGETS[1])
                if second and (len(first) != len(STAGES) or first[-1][5] != "COMPLETE"):
                    raise RuntimeError("second chunk began before first durable COMPLETE")
                if INTERRUPT_AFTER and not interruption_done and second:
                    observed = second[-1][5]
                    if (len(second) >= STAGES.index(INTERRUPT_AFTER) + 1
                            and len(second) < len(STAGES)):
                        # The first archive must already be independent and
                        # immutable before deliberately killing the second.
                        first_before_interruption = independently_verify_chunk(TARGETS[0])
                        process.kill()
                        process.wait(timeout=20)
                        interruption_done = True
                        interruption_stage_observed = observed
                        (OUTPUT / "INTERRUPTION.txt").write_text(
                            "requested=" + INTERRUPT_AFTER + "\nobserved=" + observed
                            + "\nmethod=isolated-server-process-kill"
                            + "\nfirstBackupSha256="
                            + first_before_interruption["immutable_backup_sha256"] + "\n")
                        server_log = OUTPUT / "interrupted-restart.log"
                        interruption_log_sink = server_log.open("wb")
                        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
                            cwd=PROJECT, stdout=interruption_log_sink,
                            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
                        time.sleep(2)
                        continue
                    if len(second) == len(STAGES):
                        raise RuntimeError("second chunk completed before requested interruption could be observed")
                if (len(first) == len(STAGES) and len(second) == len(STAGES)):
                    break
                if process.poll() is not None:
                    raise RuntimeError("disposable Minecraft server exited early")
                live_log = server_log.read_text(errors="replace")
                if "TWO-CHUNK-INIT-FAILED" in live_log:
                    raise RuntimeError("two-chunk runtime admission refused; see server log")
                if "Server empty for" in live_log and "pausing" in live_log:
                    raise RuntimeError("disposable Minecraft server unexpectedly paused; check pause-when-empty-seconds=-1")
                time.sleep(2)
            else:
                raise RuntimeError("two-chunk lifecycle timed out")
            if INTERRUPT_AFTER and not interruption_done:
                raise RuntimeError("requested second-chunk interruption never occurred")
            first_report = independently_verify_chunk(TARGETS[0])
            if first_before_interruption is not None and first_report != first_before_interruption:
                raise RuntimeError("first completed chunk evidence changed across second-chunk interruption")
            second_report = independently_verify_chunk(TARGETS[1])
            if second_report["ticket_installed_ms"] < first_report["ticket_released_ms"]:
                raise RuntimeError("concurrent chunk-ticket ownership detected")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("first server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill()
            if interruption_log_sink is not None:
                interruption_log_sink.close()
    # Reopen both already-completed operations on a fresh Minecraft process:
    # PostCompleteRecoveryProof must reopen/verify BOTH archived originals.
    second_log = OUTPUT / "cold-restart.log"
    with second_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=PROJECT, stdout=sink, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                text = second_log.read_text(errors="replace")
                if "TWO-CHUNK-INIT-FAILED" in text:
                    raise RuntimeError("second process refused completed archived recovery evidence")
                if "TWO-CHUNK-CANARY-OPEN" in text and "completeCount=2" in text:
                    break
                if process.poll() is not None:
                    raise RuntimeError("Minecraft exited before completed cold-restart proof")
                time.sleep(2)
            else:
                raise RuntimeError("cold-restart two-chunk evidence check timed out")
            single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("final server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill()
    final_first = independently_verify_chunk(TARGETS[0])
    final_second = independently_verify_chunk(TARGETS[1])
    if final_first != first_report or final_second != second_report:
        raise RuntimeError("completed cold restart altered immutable chunk evidence")
    return {"verdict": "PASS", "seed": SEED, "cold_restart": True,
            "requested_interruption": INTERRUPT_AFTER or None,
            "observed_interruption_stage": interruption_stage_observed,
            "simultaneously_active_chunks_max": 1,
            "first": final_first, "second": final_second}


if __name__ == "__main__":
    try:
        verdict = main()
        status = 0
    except BaseException as exc:
        verdict = {"verdict": "FAIL", "error": str(exc)}
        status = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(verdict, indent=2) + "\n")
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
    print(json.dumps(verdict, indent=2), flush=True)
    sys.exit(status)

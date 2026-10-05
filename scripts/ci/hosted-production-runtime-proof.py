#!/usr/bin/env python3
"""Disposable real-Minecraft proof for the v1 production scheduler and seed Restore."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("full_hosted", HERE.with_name("hosted-full-canvas-command-proof.py"))
full = importlib.util.module_from_spec(spec)
spec.loader.exec_module(full)

PROJECT = full.PROJECT
RUN = full.RUN
WORLD = RUN / "world"
ROOT = WORLD / "oceancanvas-core" / "production-scale"
PREGEN = ROOT / "pregen.properties"
RESTORE = ROOT / "restore.properties"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-production-runtime-proof"
full.OUTPUT = OUTPUT
SEED = os.environ.get("OCEANCANVAS_PRODUCTION_PROOF_SEED", "4182041")
FINGERPRINT_RE = re.compile(r"sha256=([0-9a-f]{64})\s+chunks=(\d+)\s+cells=(\d+)\s+seed=(-?\d+)")
DETAIL_RE = re.compile(r"\sdetail=([^\s]+)$")
LAST_EVIDENCE = {}


def prop(path):
    return full.single.properties(path)


def await_status(path, wanted, timeout=900):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file():
            state = prop(path)
            if state.get("status") == wanted:
                return state
        time.sleep(0.25)
    state = prop(path) if path.is_file() else {}
    raise RuntimeError(f"state did not reach {wanted}: {path}: {state}")


def command_ok(command):
    response = full.rcon(command)
    low = response.lower()
    if any(token in low for token in ("unknown", "refused", "failed", "could not", "error")):
        raise RuntimeError(f"command failed: {command}: {response}")
    return response


def parse_detail(raw):
    result = {}
    if not raw:
        return result
    for entry in raw.split('|'):
        parts = entry.split(':')
        if len(parts) != 5:
            raise RuntimeError(f"malformed fingerprint detail: {entry}")
        result[parts[0]] = {
            "chunk": parts[1],
            "deep": parts[2],
            "authored": parts[3],
            "high": parts[4],
        }
    return result


def fingerprint(label):
    response = command_ok("oceancanvas productionfingerprint")
    match = FINGERPRINT_RE.search(response)
    if not match:
        raise RuntimeError(f"could not parse production fingerprint: {response}")
    detail_match = DETAIL_RE.search(response)
    result = {
        "label": label,
        "sha256": match.group(1),
        "chunks": int(match.group(2)),
        "cells": int(match.group(3)),
        "seed": int(match.group(4)),
        "detail": parse_detail(detail_match.group(1) if detail_match else ""),
        "response": response,
    }
    if result["chunks"] != 16:
        raise RuntimeError(f"fingerprint expected 16 chunks, got {result['chunks']}")
    if len(result["detail"]) != 16:
        raise RuntimeError(f"fingerprint expected 16 chunk diagnostics, got {len(result['detail'])}")
    return result


def diff_details(before, after):
    changed = {}
    for key in sorted(before["detail"]):
        if before["detail"][key] != after["detail"].get(key):
            changed[key] = {"before": before["detail"][key], "after": after["detail"].get(key)}
    return changed


def assert_terminal(state, total=16):
    if state.get("status") != "COMPLETE":
        raise RuntimeError("operation not COMPLETE")
    if int(state.get("totalChunks", "-1")) != total or int(state.get("nextIndex", "-1")) != total:
        raise RuntimeError(f"terminal cursor mismatch: {state}")
    if state.get("engineBaseline") != "v253.125.54":
        raise RuntimeError(f"unexpected production baseline: {state.get('engineBaseline')}")
    if state.get("engineSourceSha256") != "b96e263e6cd74a4f8188e2b00b5dce2f6d8722c825174d98b6929b4c70e29d8f":
        raise RuntimeError("production source reference mismatch")


def main():
    global LAST_EVIDENCE
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("production proof requires an initially absent disposable world")
    if not SEED or len(SEED) > 64:
        raise RuntimeError("invalid production proof seed")

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
    (cfg / "oceancanvas-core.properties").write_text(
        "mode=CORE_AUTHORING\ncanvasSize=64\ncenterX=528\ncenterZ=528\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=512\nmaxChecksPerTick=2048\n"
        "stageWallBudgetMicros=3000\nphysicalSettleTicks=20\nlightSettleTicks=20\n")

    evidence = {"seed": SEED}
    LAST_EVIDENCE = evidence
    process, sink, _ = full.launch("production-session.log")
    try:
        before = fingerprint("before")
        evidence["before"] = before

        evidence["pregen_start"] = command_ok("oceancanvas pregen start ERASE_CONFIGURED_CANVAS")
        evidence["pregen_cancel"] = command_ok("oceancanvas pregen cancel")
        paused = await_status(PREGEN, "PAUSED", 60)
        paused_index = int(paused.get("nextIndex", "-1"))
        time.sleep(2)
        if int(prop(PREGEN).get("nextIndex", "-2")) != paused_index:
            raise RuntimeError("production pregen advanced after durable pause")
        evidence["pregen_resume"] = command_ok("oceancanvas pregen resume")
        pregen_done = await_status(PREGEN, "COMPLETE", 900)
        assert_terminal(pregen_done)
        evidence["pregen_state"] = pregen_done

        flattened = fingerprint("flattened")
        evidence["flattened"] = flattened
        if flattened["sha256"] == before["sha256"]:
            raise RuntimeError("production pregen did not alter the configured Canvas")

        evidence["restore_start"] = command_ok("oceancanvas restoreseed start RESTORE_CONFIGURED_CANVAS_FROM_SEED")
        evidence["restore_cancel"] = command_ok("oceancanvas restoreseed cancel")
        await_status(RESTORE, "PAUSED", 180)
        evidence["restore_resume"] = command_ok("oceancanvas restoreseed resume")
        restore_done = await_status(RESTORE, "COMPLETE", 1200)
        assert_terminal(restore_done)
        evidence["restore_state"] = restore_done

        restored = fingerprint("restored")
        evidence["restored"] = restored
        if restored["sha256"] != before["sha256"]:
            evidence["restore_diff"] = diff_details(before, restored)
            (OUTPUT / "RESTORE_DIFF.json").write_text(
                json.dumps(evidence["restore_diff"], indent=2, sort_keys=True) + "\n")
            raise RuntimeError("seed-native Restore terrain fingerprint differs from original world")
    finally:
        evidence["first_stop"] = full.stop(process, sink)

    process, sink, _ = full.launch("production-cold-restart.log")
    try:
        pregen_cold = prop(PREGEN)
        restore_cold = prop(RESTORE)
        assert_terminal(pregen_cold)
        assert_terminal(restore_cold)
        cold = fingerprint("cold_restart")
        evidence["cold_restart"] = cold
        if cold["sha256"] != evidence["before"]["sha256"]:
            evidence["cold_diff"] = diff_details(evidence["before"], cold)
            raise RuntimeError("cold restart changed restored terrain fingerprint")
    finally:
        evidence["final_stop"] = full.stop(process, sink)

    evidence["verdict"] = "PASS"
    return evidence


if __name__ == "__main__":
    try:
        verdict = main()
        status = 0
    except BaseException as exc:
        verdict = dict(LAST_EVIDENCE)
        verdict["verdict"] = "FAIL"
        verdict["error"] = str(exc)
        status = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(verdict, indent=2, sort_keys=True) + "\n")
    if ROOT.exists():
        target = OUTPUT / "production-scale-state"
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(ROOT, target)
    print(json.dumps(verdict, indent=2, sort_keys=True), flush=True)
    sys.exit(status)

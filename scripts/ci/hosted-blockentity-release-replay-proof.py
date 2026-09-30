#!/usr/bin/env python3
"""Deterministic restart proof for the two block-entity release archive crash boundaries."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location(
    "restore_proof", Path(__file__).resolve().with_name("hosted-blockentity-restore-proof.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
capture = restore.capture

OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-release-replay"
STATE = restore.STATE


def trim_terminal_line(path, required):
    raw = path.read_bytes()
    if not raw.endswith(b"\n"):
        raise RuntimeError("durable file is not newline terminated: " + str(path))
    lines = raw.decode("utf-8").splitlines()
    if not lines or required not in lines[-1]:
        raise RuntimeError("unexpected terminal durable line in " + str(path))
    path.write_text("\n".join(lines[:-1]) + "\n")


def wait_recompleted(log_path, process, seconds=240):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        text = log_path.read_text(errors="replace") if log_path.exists() else ""
        stages = restore.journal_stages()
        if "SINGLE-CHUNK-INIT-FAILED" in text:
            raise RuntimeError("release replay startup failed closed unexpectedly")
        if stages and stages[-1] == "FAILED":
            raise RuntimeError("release replay reached durable FAILED")
        if len(stages) == 10 and stages[-1] == "COMPLETE":
            return text
        if process.poll() is not None:
            raise RuntimeError("Minecraft exited before replayed release completed")
        time.sleep(0.1)
    raise RuntimeError("release replay did not return to COMPLETE")


def replay_boundary(name):
    log = OUTPUT / (name + ".log")
    process, sink = capture.start(log)
    try:
        wait_recompleted(log, process)
        restore.inspect_chest()
    finally:
        capture.stop(process)
        sink.close()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)

    baseline = restore.main()
    if baseline.get("verdict") != "PASS" or not baseline.get("cold_reopen"):
        raise RuntimeError("baseline block-entity restore proof did not pass")

    state_archive = STATE / "preimage-blockstates.bin.completed.archive"
    state_live = STATE / "preimage-blockstates.bin"
    be_archive = STATE / "preimage-blockentities.ocbe.completed.archive"
    be_live = STATE / "preimage-blockentities.ocbe"
    journal = STATE / "transitions.journal"
    receipts = STATE / "runtime-receipts.log"
    state_sha = restore.sha(state_archive)
    be_sha = restore.sha(be_archive)

    # Boundary A: state archive committed, BE sidecar still live, no release
    # receipt and no COMPLETE journal append.
    if state_live.exists() or be_live.exists():
        raise RuntimeError("baseline unexpectedly retained live recovery evidence")
    be_archive.replace(be_live)
    trim_terminal_line(journal, "\tRESTORE_VERIFIED\tCOMPLETE\t")
    trim_terminal_line(receipts, "\tTICKET_RELEASED\t")
    replay_boundary("restart-after-state-archive-before-sidecar-archive")
    if state_live.exists() or be_live.exists() or not be_archive.is_file():
        raise RuntimeError("boundary A replay did not retire live sidecar into immutable archive")
    if restore.sha(state_archive) != state_sha or restore.sha(be_archive) != be_sha:
        raise RuntimeError("boundary A replay altered immutable archive bytes")

    # Boundary B: both immutable archives committed, but process died before
    # durable TICKET_RELEASED receipt / COMPLETE journal append.
    trim_terminal_line(journal, "\tRESTORE_VERIFIED\tCOMPLETE\t")
    trim_terminal_line(receipts, "\tTICKET_RELEASED\t")
    replay_boundary("restart-after-both-archives-before-release-credit")
    if state_live.exists() or be_live.exists():
        raise RuntimeError("boundary B replay recreated live recovery evidence")
    if restore.sha(state_archive) != state_sha or restore.sha(be_archive) != be_sha:
        raise RuntimeError("boundary B replay rewrote immutable archives")

    verified = restore.verified_receipts()
    releases = [detail for kind, detail in verified
                if kind == "TICKET_RELEASED" and "restoreVerified=true" in detail]
    if len(releases) != 1:
        raise RuntimeError("replayed final durable history should contain exactly one verified release receipt")
    if restore.detail_token(releases[0], "blockEntityArchiveSha256") != be_sha:
        raise RuntimeError("replayed release receipt lost block-entity archive identity")

    return {
        "verdict": "PASS",
        "baseline_restore": True,
        "boundary_state_archive_sidecar_live": "PASS",
        "boundary_both_archives_before_release_credit": "PASS",
        "state_archive_sha256": state_sha,
        "block_entity_archive_sha256": be_sha,
        "chest_inventory_survived": True,
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
    print(json.dumps(result, indent=2), flush=True)
    sys.exit(code)

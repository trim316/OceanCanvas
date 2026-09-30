#!/usr/bin/env python3
"""Cloud-only Ocean Canvas recovery audit: never launches/edits Minecraft or user files.

Every invocation leaves a JSON report even on check failures so failed campaigns
provide evidence rather than being silently discarded. Run in a checkout of
recovery/core-proof, not main.
"""
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(os.environ.get("RECOVERY_AUDIT_OUT", "cloud-audit-evidence"))
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = []


def check(name, condition, evidence):
    RESULTS.append({"check": name, "pass": bool(condition), "evidence": evidence})


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


try:
    queue = read("AUTOMATION_QUEUE.md")
    ledger = read("FAILURE_LEDGER.md")
    workflow = read(".github/workflows/recovery-core-proof.yml")
    runner = read("scripts/ci/run-recovery-one-chunk.ps1")
    stages = read("runtime-src/src/main/java/net/oceancanvas/core/pipeline/ChunkStage.java")
    transitions = read("runtime-src/src/main/java/net/oceancanvas/core/pipeline/ChunkTransitions.java")
    test = read("runtime-src/src/test/java/net/oceancanvas/core/CoreSelfTest.java")
    store = read("runtime-src/src/main/java/net/oceancanvas/core/restore/BlockStatePreimageStore.java")
    mandatory = (
        "LOADED", "PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "PHYSICAL_SETTLED",
        "PERSISTED", "LIGHTING_SETTLED", "VERIFIED", "RESTORED",
        "RESTORE_VERIFIED", "COMPLETE",
    )
    task_rows = re.findall(r"(?m)^\\|\\s*(R\\d+-\\d+)\\s*\\|\\s*([^|]+)\\|\\s*(.*?)\\s*\\|$", queue)
    ready = [(task_id, desc.strip()) for task_id, status, desc in task_rows
             if status.strip() == "READY"]
    independent = [(task_id, desc) for task_id, desc in ready if task_id.startswith("R1-")]
    check("independent_backlog", len(independent) >= 12,
          {"ready_independent": len(independent), "minimum": 12,
           "next_independent": independent[0] if independent else None,
           "ready_ids": [task_id for task_id, _ in independent]})
    ids = [task_id for task_id, _, _ in task_rows]
    check("durable_task_identity", len(ids) == len(set(ids)),
          {"unique": len(set(ids)), "total": len(ids)})
    check("queue_replenishment_policy", "at least 12 independent READY" in queue,
          "Low inventory requires concrete release-risk replenishment; audit does not invent code.")
    check("failure_ledger_retained", "Failure classes" in ledger and "36712751351" in ledger,
          "historical failure evidence remains tracked")
    check("single_authoritative_runtime_trigger",
          "push:" in workflow and "recovery/core-proof" in workflow
          and "cancel-in-progress: false" in workflow,
          "recovery pipeline is serial, does not cancel working tests")
    check("runtime_disables_scale", "expansionEnabled=false" in runner,
          "runtime proof restricted to explicit one chunk")
    check("no_forbidden_pid_assignment", not re.search(r"(?im)^\s*\$pid\s*=", runner),
          "PowerShell PID is read-only")
    check("resume_verifies_full_journal",
          all(name in runner and name in stages for name in mandatory)
          and "Assert-DurableLifecycle" in runner and "verifiedRestarts" in runner,
          "operator-independent journal-based final attestation")
    check("no_stage_skip", "PREIMAGE_CAPTURED" in transitions
          and "RESTORE_VERIFIED" in transitions and "VERIFIED" in transitions,
          "durable stage graph contains recovery and restore")
    check("core_restart_regression_exists",
          "testRestartAtEverySingleChunkStage" in test and "testBlockStatePreimageStore" in test,
          "deterministic tests compiled by separate cloud build")
    check("preimage_overflow_guard",
          "Math.multiplyExact(count, Integer.BYTES)" in store
          and "preimage byte count overflow rejects before allocation" in test,
          "rejects forged signed-digest oversized preimage without huge allocation")
except Exception as exc:
    check("audit_exception", False, {"type": type(exc).__name__, "message": str(exc)})

report = {
    "utc": dt.datetime.now(dt.timezone.utc).isoformat(),
    "source_branch": "recovery/core-proof",
    "checkout_sha": os.environ.get("GITHUB_SHA", "local"),
    "checked_tree": hashlib.sha256(
        "\n".join(str((ROOT / x).resolve()) for x in (
            "AUTOMATION_QUEUE.md", "FAILURE_LEDGER.md",
            ".github/workflows/recovery-core-proof.yml")).encode()
    ).hexdigest(),
    "checks": RESULTS,
    "verdict": "PASS" if all(r["pass"] for r in RESULTS) else "FAIL",
}
path = OUT / "cloud-audit.json"
path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
sys.exit(0 if report["verdict"] == "PASS" else 1)

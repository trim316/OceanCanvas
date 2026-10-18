#!/usr/bin/env python3
"""Run the hosted one-chunk proof twice from fresh disposable worlds.

This controller never accepts an existing runtime world. The first proof is sealed by a
recursive evidence digest before its runtime directory is removed; the second proof must
produce a distinct operation identity, and the first evidence tree must remain byte-for-byte
unchanged. This is cloud/disposable proof orchestration only and grants no release credit.
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = ROOT / "runtime-src"
RUN = PROJECT / "run"
CONTROLLER = pathlib.Path(__file__).with_name("hosted-minecraft-proof.py")
OUTPUT_ROOT = pathlib.Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-fresh-repeat-proof"


def tree_digest(root: pathlib.Path) -> str:
    if not root.is_dir():
        raise RuntimeError("evidence directory missing: " + str(root))
    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    if not files:
        raise RuntimeError("evidence directory is empty: " + str(root))
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        data = path.read_bytes()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def properties(path: pathlib.Path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def inspect_evidence(root: pathlib.Path):
    verdict_path = root / "VERDICT.json"
    operation_path = root / "operation.properties"
    if not verdict_path.is_file() or not operation_path.is_file():
        raise RuntimeError("repeat evidence lacks verdict or operation identity")
    verdict = json.loads(verdict_path.read_text(encoding="utf-8"))
    if verdict.get("verdict") != "PASS":
        raise RuntimeError("repeat member is not PASS")
    operation_id = properties(operation_path).get("operationId", "")
    if not operation_id:
        raise RuntimeError("repeat member lacks operationId")
    return operation_id, tree_digest(root)


def verify_pair(first: pathlib.Path, second: pathlib.Path, sealed_first_digest: str):
    first_id, current_first_digest = inspect_evidence(first)
    second_id, second_digest = inspect_evidence(second)
    if current_first_digest != sealed_first_digest:
        raise RuntimeError("first proof evidence changed after second fresh world")
    if first_id == second_id:
        raise RuntimeError("fresh repeat reused operation/world identity")
    return {
        "verdict": "PASS",
        "first_operation_id": first_id,
        "second_operation_id": second_id,
        "first_evidence_sha256": current_first_digest,
        "second_evidence_sha256": second_digest,
        "distinct_operation_identity": True,
        "first_evidence_immutable": True,
    }


def run_member(label: str):
    workspace = OUTPUT_ROOT / label
    workspace.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["GITHUB_WORKSPACE"] = str(workspace)
    completed = subprocess.run([sys.executable, str(CONTROLLER)], cwd=ROOT, env=env)
    evidence = workspace / "hosted-minecraft-proof"
    if completed.returncode != 0:
        raise RuntimeError(label + " hosted proof failed; evidence=" + str(evidence))
    return evidence


def main():
    if RUN.exists():
        raise RuntimeError("fresh-repeat controller refuses preexisting runtime directory: " + str(RUN))
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=False)
    first = run_member("first")
    first_id, sealed_first_digest = inspect_evidence(first)
    if not first_id:
        raise RuntimeError("first operation identity missing")
    if not RUN.is_dir():
        raise RuntimeError("first proof did not create disposable runtime world")
    shutil.rmtree(RUN)
    if RUN.exists():
        raise RuntimeError("failed to remove first disposable runtime before repeat")
    second = run_member("second")
    report = verify_pair(first, second, sealed_first_digest)
    (OUTPUT_ROOT / "FRESH_REPEAT_VERDICT.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    try:
        main()
    except BaseException as exc:
        OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        (OUTPUT_ROOT / "FRESH_REPEAT_VERDICT.json").write_text(
            json.dumps({"verdict": "FAIL", "error": str(exc)}, indent=2) + "\n", encoding="utf-8")
        print(str(exc), file=sys.stderr, flush=True)
        raise

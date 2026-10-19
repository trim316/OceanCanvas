#!/usr/bin/env python3
"""Run the hosted one-chunk proof twice from fresh disposable worlds.

This controller never accepts an existing runtime world. The first proof is sealed by a
recursive evidence digest before its runtime directory is removed; the second proof uses a
distinct pinned disposable-world seed and must produce distinct restored-world evidence,
while the first evidence tree remains byte-for-byte unchanged. The operationId is a
deterministic authority identity for the same target/configuration and is therefore expected
to remain stable across the two worlds. This is cloud/disposable proof orchestration only
and grants no release credit.
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
FIRST_WORLD_SEED = "4182026"
SECOND_WORLD_SEED = "4182031"


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
    world_seed = str(verdict.get("world_seed", "")).strip()
    if not world_seed:
        raise RuntimeError("repeat member lacks world seed evidence")
    archive_sha = str(verdict.get("archived_preimage", {}).get("sha256", "")).strip()
    if len(archive_sha) != 64:
        raise RuntimeError("repeat member lacks archived preimage identity")
    return operation_id, world_seed, archive_sha, tree_digest(root)


def verify_pair(first: pathlib.Path, second: pathlib.Path, sealed_first_digest: str):
    first_id, first_seed, first_archive, current_first_digest = inspect_evidence(first)
    second_id, second_seed, second_archive, second_digest = inspect_evidence(second)
    if current_first_digest != sealed_first_digest:
        raise RuntimeError("first proof evidence changed after second fresh world")
    if first_seed == second_seed:
        raise RuntimeError("fresh repeat reused disposable world seed")
    if first_archive == second_archive:
        raise RuntimeError("fresh repeat produced identical archived world preimage")
    if first_id != second_id:
        raise RuntimeError("fresh repeat changed deterministic operation authority identity")
    return {
        "verdict": "PASS",
        "operation_id": first_id,
        "first_world_seed": first_seed,
        "second_world_seed": second_seed,
        "first_archive_sha256": first_archive,
        "second_archive_sha256": second_archive,
        "first_evidence_sha256": current_first_digest,
        "second_evidence_sha256": second_digest,
        "distinct_world_seed": True,
        "distinct_archived_world_preimage": True,
        "stable_operation_authority_identity": True,
        "first_evidence_immutable": True,
    }


def run_member(label: str, world_seed: str):
    env = os.environ.copy()
    github_workspace_text = env.get("GITHUB_WORKSPACE", "").strip()
    if not github_workspace_text:
        raise RuntimeError("GITHUB_WORKSPACE is required for fresh-repeat proof")
    shared_evidence = pathlib.Path(github_workspace_text) / "hosted-minecraft-proof"
    if shared_evidence.exists():
        raise RuntimeError(
            "fresh-repeat member refuses preexisting hosted proof evidence directory: "
            + str(shared_evidence)
        )

    workspace = OUTPUT_ROOT / label
    workspace.mkdir(parents=True, exist_ok=False)
    evidence = workspace / "hosted-minecraft-proof"

    env["OCEANCANVAS_WORLD_SEED"] = world_seed
    completed = subprocess.run([sys.executable, str(CONTROLLER)], cwd=ROOT, env=env)
    if shared_evidence.exists():
        shutil.move(str(shared_evidence), str(evidence))
    if completed.returncode != 0:
        raise RuntimeError(label + " hosted proof failed; evidence=" + str(evidence))
    if not evidence.is_dir():
        raise RuntimeError(label + " hosted proof produced no evidence directory")
    return evidence


def main():
    if RUN.exists():
        raise RuntimeError("fresh-repeat controller refuses preexisting runtime directory: " + str(RUN))
    if FIRST_WORLD_SEED == SECOND_WORLD_SEED:
        raise RuntimeError("fresh-repeat controller requires distinct pinned world seeds")
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=False)
    first = run_member("first", FIRST_WORLD_SEED)
    first_id, first_seed, _, sealed_first_digest = inspect_evidence(first)
    if not first_id or first_seed != FIRST_WORLD_SEED:
        raise RuntimeError("first operation/world identity mismatch")
    if not RUN.is_dir():
        raise RuntimeError("first proof did not create disposable runtime world")
    shutil.rmtree(RUN)
    if RUN.exists():
        raise RuntimeError("failed to remove first disposable runtime before repeat")
    second = run_member("second", SECOND_WORLD_SEED)
    report = verify_pair(first, second, sealed_first_digest)
    if report["second_world_seed"] != SECOND_WORLD_SEED:
        raise RuntimeError("second operation/world identity mismatch")
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

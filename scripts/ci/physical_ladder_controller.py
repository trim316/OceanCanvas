#!/usr/bin/env python3
"""Resume/promote the disposable physical radius ladder without hand-authored S6/S7 workflows.

The caller supplies an extracted predecessor artifact directory. This controller is
fail-closed: it resumes an incomplete exact checkpoint, or promotes only after the
completed predecessor has the required acceptance/restart/structure evidence and
current-source regressions are green. It never manufactures PASS evidence.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess
from checkpoint_bundle import verify_manifest
from candidate_verifier import verify_candidate

ROOT = Path.cwd()
PARENT = Path(os.environ.get("OC_LADDER_PARENT", "verified-ladder-parent"))
BUILD = os.environ["EXPECTED_BUILD"]
SOURCE = os.environ["EXPECTED_SOURCE_COMMIT"]
JAR_SHA = os.environ["EXPECTED_JAR_SHA256"]

WIDTHS = [2000, 4000, 10000, 20000]  # 1k, 2k, 5k, 10k radii
LABELS = {2000:"1k", 4000:"2k", 10000:"5k", 20000:"10k"}
SEGMENT_SECONDS = "3300"  # 55 minutes; aligned to the hourly Codex supervision cadence.


def sha256(path: Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def passed_json(path: Path) -> bool:
    if not path.is_file():
        return False
    try:
        return json.loads(path.read_text()).get("passed") is True
    except Exception:
        return False


def find_evidence(parent: Path) -> Path:
    candidates = sorted({p.parent for p in parent.rglob("identity.json") if "physical-radius-" in p.parent.name})
    if len(candidates) != 1:
        raise SystemExit(f"Expected exactly one physical ladder evidence root, found {len(candidates)}")
    return candidates[0]


def copy_candidate(parent: Path):
    matches = [p for p in parent.rglob("production-candidate") if p.is_dir()]
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one production-candidate, found {len(matches)}")
    dst = ROOT / "production-candidate"
    if dst.exists(): shutil.rmtree(dst)
    shutil.copytree(matches[0], dst)
    jar, actual = verify_candidate(dst, BUILD, SOURCE)
    if actual != JAR_SHA:
        raise SystemExit(f"Candidate JAR mismatch: {actual} != {JAR_SHA}")
    return jar


def run_regressions():
    subprocess.run(["bash", "gradlew", "check", "--console=plain"], check=True)


def preserve_hold(source: Path, identity: dict, reason: str):
    """Republish an ambiguous but intact checkpoint as HOLD without rerunning terrain."""
    width = int(identity["requestedBlocks"])
    label = LABELS[width]
    out = ROOT / f"physical-radius-{label}-evidence"
    if out.exists(): shutil.rmtree(out)
    shutil.copytree(source, out)
    copy_candidate(PARENT)
    hold = {
        "promotion": "HOLD",
        "scaleAccepted": False,
        "reason": reason,
        "sourceCommit": SOURCE,
        "jarSha256": JAR_SHA,
        "preservedWorld": True,
        "terrainRerun": False,
    }
    (out / "controller-hold.json").write_text(json.dumps(hold, indent=2) + "\n")
    print(f"LADDER_HOLD radius={label} reason={reason}")


def resume(source: Path, identity: dict):
    width = int(identity["requestedBlocks"])
    label = LABELS[width]
    out = ROOT / f"physical-radius-{label}-evidence"
    if out.exists(): shutil.rmtree(out)
    shutil.copytree(source, out)
    copy_candidate(PARENT)
    verify_manifest(out, json.loads((out / "checkpoint.json").read_text()))
    env = os.environ.copy()
    env.update({
        "OC_TEST_WIDTH": str(width),
        "OC_EVIDENCE_DIR": out.name,
        "OC_STAGE_SECONDS": SEGMENT_SECONDS,
        "OC_CHECKPOINT_SEGMENT": "true",
        "OC_RESUME": "true",
        "OC_REQUIRE_CHECKPOINT_MANIFEST": "true",
    })
    subprocess.run(["python3", "scripts/ci/production-scale.py"], check=True, env=env)
    print(f"LADDER_RESUMED radius={label}")


def promote(source: Path, identity: dict):
    width = int(identity["requestedBlocks"])
    required = {
        "acceptance": passed_json(source / "acceptance.json"),
        "restart": passed_json(source / "restart.json"),
        "structures": passed_json(source / "locate-after-rejoin.json"),
        "noFirstFailure": not (source / "first-failure.json").exists(),
        "noStall": not (source / "stall.json").exists(),
    }
    if not all(required.values()):
        raise SystemExit("Completed checkpoint missing required promotion proof: " + json.dumps(required, sort_keys=True))
    run_regressions()
    if width == WIDTHS[-1]:
        final = ROOT / "physical-radius-10k-evidence"
        if final.exists(): shutil.rmtree(final)
        shutil.copytree(source, final)
        (final / "ladder-controller.json").write_text(json.dumps({
            "scaleAccepted": True, "radiusBlocks": 10000, "final": True,
            "sourceCommit": SOURCE, "jarSha256": JAR_SHA,
            "proof": required | {"currentSourceRegressions": True}
        }, indent=2))
        print("LADDER_FINAL_10K_READY_FOR_INDEPENDENT_RELEASE_REVIEW")
        return
    next_width = WIDTHS[WIDTHS.index(width)+1]
    next_label = LABELS[next_width]
    copy_candidate(PARENT)
    out = ROOT / f"physical-radius-{next_label}-evidence"
    if out.exists(): shutil.rmtree(out)
    env = os.environ.copy()
    env.update({
        "OC_TEST_WIDTH": str(next_width),
        "OC_EVIDENCE_DIR": out.name,
        "OC_STAGE_SECONDS": SEGMENT_SECONDS,
        "OC_CHECKPOINT_SEGMENT": "true",
        "OC_RESUME": "false",
        "OC_REQUIRE_CHECKPOINT_MANIFEST": "true",
    })
    subprocess.run(["python3", "scripts/ci/production-scale.py"], check=True, env=env)
    print(f"LADDER_PROMOTED from={LABELS[width]} to={next_label}")


def main():
    source = find_evidence(PARENT)
    identity = json.loads((source / "identity.json").read_text())
    if identity.get("sourceCommit") != SOURCE or identity.get("jarSha256") != JAR_SHA or identity.get("build") != BUILD:
        raise SystemExit("Predecessor identity does not match frozen production candidate")
    width = int(identity["requestedBlocks"])
    if width not in WIDTHS:
        raise SystemExit(f"Unexpected ladder width {width}")
    status = json.loads((source / "checkpoint-status.json").read_text())
    if status.get("serverExit") != 0 or not status.get("savedWorldPresent"):
        raise SystemExit("Checkpoint is not cleanly resumable")
    if status.get("completionObserved") is True:
        promote(source, identity)
    elif status.get("segmentPending") is True:
        resume(source, identity)
    else:
        preserve_hold(source, identity, "checkpoint status is neither complete nor authorized pending; preserve world for explicit classification")

if __name__ == "__main__":
    main()

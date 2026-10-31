#!/usr/bin/env python3
"""Resume/promote the disposable physical radius ladder without hand-authored S6/S7 workflows.

The caller supplies an extracted predecessor artifact directory. This controller is
fail-closed: it resumes an incomplete exact checkpoint, or promotes only after the
completed predecessor has the required acceptance/restart/structure evidence and
current-source regressions are green. It never manufactures PASS evidence.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tempfile, zipfile
from checkpoint_bundle import verify_manifest
from candidate_verifier import verify_candidate

ROOT = Path.cwd()
PARENT = Path(os.environ.get("OC_LADDER_PARENT", "verified-ladder-parent"))
BUILD = os.environ["EXPECTED_BUILD"]
SOURCE = os.environ["EXPECTED_SOURCE_COMMIT"]
JAR_SHA = os.environ["EXPECTED_JAR_SHA256"]

WIDTHS = [2000, 4000, 10000, 20000]
LABELS = {2000:"1k", 4000:"2k", 10000:"5k", 20000:"10k"}
SEGMENT_SECONDS = "3300"


def sha256(path: Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def passed_json(path: Path) -> bool:
    if not path.is_file(): return False
    try: return json.loads(path.read_text()).get("passed") is True
    except Exception: return False


def find_evidence(parent: Path) -> Path:
    candidates = sorted({p.parent for p in parent.rglob("identity.json") if "physical-radius-" in p.parent.name})
    if len(candidates) != 1:
        raise SystemExit(f"Expected exactly one physical ladder evidence root, found {len(candidates)}")
    return candidates[0]


def _verified_candidate_dir(candidate: Path):
    jar, actual = verify_candidate(candidate, BUILD, SOURCE)
    if actual != JAR_SHA:
        raise RuntimeError(f"Candidate JAR mismatch: {actual} != {JAR_SHA}")
    return jar


def _recover_bound_candidate(parent: Path) -> Path:
    """Recover only the exact candidate artifact named by the preserved evidence.

    Some historical checkpoints accidentally republished an older top-level candidate
    while their immutable identity correctly bound the physical world to a newer build.
    Repair packaging only: fetch the build-run artifact named by that identity, then
    require the same frozen build/source/JAR hash before it can become authoritative.
    """
    evidence = find_evidence(parent)
    identity = json.loads((evidence / "identity.json").read_text())
    run_id = str(identity.get("candidateBuildRunId", "")).strip()
    if not run_id.isdigit():
        raise SystemExit("Canonical candidate is invalid and evidence has no valid candidateBuildRunId; refusing recovery")
    repo = os.environ["GITHUB_REPOSITORY"]
    response = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{repo}/actions/runs/{run_id}/artifacts"], text=True))
    artifacts = [a for a in response.get("artifacts", []) if not a.get("expired")]
    if len(artifacts) != 1:
        raise SystemExit(f"Expected exactly one non-expired candidate artifact from bound build run {run_id}, found {len(artifacts)}")
    artifact = artifacts[0]
    with tempfile.TemporaryDirectory(prefix="oc-bound-candidate-") as td:
        td = Path(td); archive = td / "candidate.zip"; extracted = td / "extracted"
        with archive.open("wb") as out:
            subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact['id']}/zip"], check=True, stdout=out)
        extracted.mkdir()
        with zipfile.ZipFile(archive) as zf: zf.extractall(extracted)
        dirs = [p.parent for p in extracted.rglob("candidate.properties")]
        valid = []
        for d in dirs:
            try:
                _verified_candidate_dir(d); valid.append(d)
            except Exception:
                pass
        if len(valid) != 1:
            raise SystemExit(f"Bound build artifact {artifact['id']} did not contain exactly one frozen candidate; found {len(valid)}")
        repaired = ROOT / ".recovered-production-candidate"
        if repaired.exists(): shutil.rmtree(repaired)
        shutil.copytree(valid[0], repaired)
    print(f"LADDER_CANDIDATE_RECOVERED buildRun={run_id} artifact={artifact['id']} build={BUILD} source={SOURCE} jarSha256={JAR_SHA}")
    return repaired


def copy_candidate(parent: Path):
    # Prefer the checkpoint's canonical top-level candidate. Historical checkpoint
    # packaging may contain a stale candidate; never accept it by position alone.
    direct = parent / "production-candidate"
    source_dir = direct if direct.is_dir() else None
    if source_dir is not None:
        try:
            _verified_candidate_dir(source_dir)
        except Exception as exc:
            print(f"LADDER_CANDIDATE_CANONICAL_INVALID reason={exc}")
            source_dir = _recover_bound_candidate(parent)
    else:
        matches = [p for p in parent.rglob("production-candidate") if p.is_dir()]
        valid = []
        for p in matches:
            try:
                _verified_candidate_dir(p); valid.append(p)
            except Exception:
                pass
        if len(valid) == 1:
            source_dir = valid[0]
        elif len(valid) == 0:
            source_dir = _recover_bound_candidate(parent)
        else:
            raise SystemExit(f"Expected one frozen production-candidate, found {len(valid)} valid copies")
    dst = ROOT / "production-candidate"
    if dst.exists(): shutil.rmtree(dst)
    shutil.copytree(source_dir, dst)
    jar = _verified_candidate_dir(dst)
    return jar


def run_regressions():
    subprocess.run(["bash", "gradlew", "check", "--console=plain"], check=True)


def preserve_hold(source: Path, identity: dict, reason: str):
    width = int(identity["requestedBlocks"]); label = LABELS[width]
    out = ROOT / f"physical-radius-{label}-evidence"
    if out.exists(): shutil.rmtree(out)
    shutil.copytree(source, out)
    copy_candidate(PARENT)
    hold = {"promotion":"HOLD","scaleAccepted":False,"reason":reason,"sourceCommit":SOURCE,"jarSha256":JAR_SHA,"preservedWorld":True,"terrainRerun":False}
    (out / "controller-hold.json").write_text(json.dumps(hold, indent=2) + "\n")
    print(f"LADDER_HOLD radius={label} reason={reason}")


def resume(source: Path, identity: dict):
    width=int(identity["requestedBlocks"]); label=LABELS[width]; out=ROOT/f"physical-radius-{label}-evidence"
    if out.exists(): shutil.rmtree(out)
    shutil.copytree(source,out); copy_candidate(PARENT)
    verify_manifest(out,json.loads((out/"checkpoint.json").read_text()))
    env=os.environ.copy(); env.update({"OC_TEST_WIDTH":str(width),"OC_EVIDENCE_DIR":out.name,"OC_STAGE_SECONDS":SEGMENT_SECONDS,"OC_CHECKPOINT_SEGMENT":"true","OC_RESUME":"true","OC_REQUIRE_CHECKPOINT_MANIFEST":"true"})
    subprocess.run(["python3","scripts/ci/production-scale.py"],check=True,env=env)
    print(f"LADDER_RESUMED radius={label}")


def promote(source: Path, identity: dict):
    width=int(identity["requestedBlocks"])
    required={"acceptance":passed_json(source/"acceptance.json"),"restart":passed_json(source/"restart.json"),"structures":passed_json(source/"locate-after-rejoin.json"),"noFirstFailure":not (source/"first-failure.json").exists(),"noStall":not (source/"stall.json").exists()}
    if not all(required.values()): raise SystemExit("Completed checkpoint missing required promotion proof: "+json.dumps(required,sort_keys=True))
    run_regressions()
    if width==WIDTHS[-1]:
        final=ROOT/"physical-radius-10k-evidence"
        if final.exists(): shutil.rmtree(final)
        shutil.copytree(source,final)
        (final/"ladder-controller.json").write_text(json.dumps({"scaleAccepted":True,"radiusBlocks":10000,"final":True,"sourceCommit":SOURCE,"jarSha256":JAR_SHA,"proof":required|{"currentSourceRegressions":True}},indent=2))
        print("LADDER_FINAL_10K_READY_FOR_INDEPENDENT_RELEASE_REVIEW"); return
    next_width=WIDTHS[WIDTHS.index(width)+1]; next_label=LABELS[next_width]; copy_candidate(PARENT); out=ROOT/f"physical-radius-{next_label}-evidence"
    if out.exists(): shutil.rmtree(out)
    env=os.environ.copy(); env.update({"OC_TEST_WIDTH":str(next_width),"OC_EVIDENCE_DIR":out.name,"OC_STAGE_SECONDS":SEGMENT_SECONDS,"OC_CHECKPOINT_SEGMENT":"true","OC_RESUME":"false","OC_REQUIRE_CHECKPOINT_MANIFEST":"true"})
    subprocess.run(["python3","scripts/ci/production-scale.py"],check=True,env=env)
    print(f"LADDER_PROMOTED from={LABELS[width]} to={next_label}")


def main():
    source=find_evidence(PARENT); identity=json.loads((source/"identity.json").read_text())
    if identity.get("sourceCommit")!=SOURCE or identity.get("jarSha256")!=JAR_SHA or identity.get("build")!=BUILD: raise SystemExit("Predecessor identity does not match frozen production candidate")
    width=int(identity["requestedBlocks"])
    if width not in WIDTHS: raise SystemExit(f"Unexpected ladder width {width}")
    status=json.loads((source/"checkpoint-status.json").read_text())
    if status.get("serverExit")!=0 or not status.get("savedWorldPresent"): raise SystemExit("Checkpoint is not cleanly resumable")
    if status.get("completionObserved") is True: promote(source,identity)
    elif status.get("segmentPending") is True: resume(source,identity)
    else: preserve_hold(source,identity,"checkpoint status is neither complete nor authorized pending; preserve world for explicit classification")

if __name__=="__main__": main()

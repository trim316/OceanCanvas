#!/usr/bin/env python3
"""Exact-source 1,000-block-radius OceanCanvas controller in resumable hosted segments."""
from __future__ import annotations
import importlib.util, json, os, shutil, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("checkpointed1k", HERE.with_name("hosted-1k-checkpointed-live.py"))
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
sys.path.insert(0, str(HERE.parent))
from checkpoint_bundle import verify_manifest, write_manifest, archive_checkpoint

WORKSPACE = Path(os.environ.get("GITHUB_WORKSPACE", ".")).resolve()
EXACT_SOURCE = os.environ["OC_EXACT_SOURCE_COMMIT"].strip()
SEGMENT_SECONDS = int(os.environ.get("OC_1K_SEGMENT_SECONDS", "780"))
SEED = os.environ.get("OCEANCANVAS_1K_SEED", "4182042")
CANVAS_SIZE = 2000
MIN_BLOCK = -(CANVAS_SIZE // 2)
MAX_BLOCK_EXCLUSIVE = MIN_BLOCK + CANVAS_SIZE
MIN_CHUNK = MIN_BLOCK // 16
MAX_CHUNK = (MAX_BLOCK_EXCLUSIVE - 1) // 16
TARGET_CHUNKS = (MAX_CHUNK - MIN_CHUNK + 1) ** 2
CHECKPOINT = WORKSPACE / "checkpoint-1k-radius-evidence"
OUTPUT = WORKSPACE / "hosted-1k-radius-controller"
LOGDIR = OUTPUT / "logs"
STARTED = h.RUN / "oceancanvas-1k-radius-started.json"
BINARY_EVIDENCE = OUTPUT / "runtime-context-evidence"

def require_exact_checkout():
    # Controller/orchestration commits may advance on main, but the runtime payload
    # being certified must remain byte-identical to the selected green source.
    rc = subprocess.run(["git","diff","--quiet",EXACT_SOURCE,"--","runtime-src"])
    if rc.returncode != 0:
        raise RuntimeError(f"runtime-src differs from exact candidate source {EXACT_SOURCE}")

def configure(identity=None):
    h.OUTPUT=OUTPUT; h.LOGDIR=LOGDIR; h.CHECKPOINT=CHECKPOINT; h.STARTED=STARTED
    h.SEED=SEED; h.SOURCE=EXACT_SOURCE; h.CANVAS_SIZE=CANVAS_SIZE
    h.BINARY_EVIDENCE=BINARY_EVIDENCE; h.full.OUTPUT=LOGDIR
    h.IDENTITY = identity or {"sourceCommit":EXACT_SOURCE,"requestedBlocks":CANVAS_SIZE,
        "targetBlocks":CANVAS_SIZE,"targetChunks":TARGET_CHUNKS,"seed":SEED,"centerX":0,"centerZ":0}

def restore_checkpoint():
    if not (CHECKPOINT/"checkpoint-manifest.json").is_file(): return None
    identity=json.loads((CHECKPOINT/"checkpoint.json").read_text())
    if identity.get("sourceCommit") != EXACT_SOURCE: raise RuntimeError("checkpoint source mismatch")
    if int(identity.get("targetBlocks",0)) != CANVAS_SIZE:
        raise RuntimeError("checkpoint is not the real 2000x2000 1k-radius gate")
    recorded_chunks = int(identity.get("targetChunks",0))
    if recorded_chunks not in {15625, TARGET_CHUNKS}:
        raise RuntimeError(f"checkpoint chunk geometry mismatch: {recorded_chunks} not in legacy/corrected 1k identities")
    verify_manifest(CHECKPOINT, identity)
    if h.RUN.exists(): shutil.rmtree(h.RUN)
    h.RUN.mkdir(parents=True)
    shutil.copytree(CHECKPOINT/"server/world", h.WORLD)
    shutil.copytree(CHECKPOINT/"server/config", h.RUN/"config")
    shutil.copy2(CHECKPOINT/"server/server.properties", h.RUN/"server.properties")
    (h.RUN/"eula.txt").write_text("eula=true\n")
    if OUTPUT.exists(): shutil.rmtree(OUTPUT)
    LOGDIR.mkdir(parents=True)
    shutil.copytree(CHECKPOINT/"candidate", BINARY_EVIDENCE/"candidate")
    shutil.copytree(CHECKPOINT/"runtime-context", BINARY_EVIDENCE/"runtime-context")
    shutil.copy2(CHECKPOINT/"checkpoint-timing.json", STARTED)
    print(f"CURRENT_1K_CHECKPOINT_AUTHENTICATED source={EXACT_SOURCE} blocks={CANVAS_SIZE} chunks={TARGET_CHUNKS}", flush=True)
    return identity

def enforce_fast_profile():
    cfg=h.RUN/"config"/"oceancanvas-core.properties"
    expected=(f"mode=CORE_AUTHORING\ncanvasSize={CANVAS_SIZE}\ncenterX=0\ncenterZ=0\n"
      "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
      "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
      "maxBlockWritesPerTick=4096\nmaxChecksPerTick=16384\n"
      "stageWallBudgetMicros=15000\nphysicalSettleTicks=2\nlightSettleTicks=2\n")
    if cfg.exists():
        p=h.single.properties(cfg)
        for k,v in {"canvasSize":str(CANVAS_SIZE),"centerX":"0","centerZ":"0","waterSurfaceY":"62","oceanFloorY":"25","oceanFloorVariation":"5"}.items():
            if p.get(k) != v: raise RuntimeError(f"saved geometry mismatch {k}={p.get(k)} expected={v}")
    cfg.parent.mkdir(parents=True,exist_ok=True); cfg.write_text(expected)

def snapshot(phase,current,total,n):
    h.snapshot(phase,current,total,n)
    status=json.loads((CHECKPOINT/"checkpoint-status.json").read_text())
    status["segmentPending"]=phase!="COMPLETE"; status["savedWorldPresent"]=True
    (CHECKPOINT/"checkpoint-status.json").write_text(json.dumps(status,sort_keys=True,indent=2)+"\n")
    write_manifest(CHECKPOINT,h.IDENTITY); archive_checkpoint(CHECKPOINT,h.IDENTITY)

def flatten(deadline,n):
    process,sink,log=h.launch(f"radius-flatten-{n}.log")
    try:
        p=h.props(h.FLATTEN)
        if not p: h.full.assert_command_ok(h.full.rcon("oceancanvas flatten start ERASE_CONFIGURED_CANVAS"),"flatten start")
        elif p.get("status")=="PAUSED": h.full.assert_command_ok(h.full.rcon("oceancanvas flatten resume"),"flatten resume")
        last=0.0
        while time.monotonic()<deadline:
            p,current,total=h.count_state(h.FLATTEN); now=time.monotonic()
            if now-last>=h.HEARTBEAT: h.heartbeat("FLATTEN",current,total,n,"status="+p.get("status","?")); last=now
            if p.get("status")=="COMPLETE": break
            if p.get("status")=="PAUSED":
                raise RuntimeError(f"FullCanvas auto-paused at chunk {current}/{total}; preserve checkpoint and diagnose fatal runtime cause")
            if process.poll() is not None: raise RuntimeError("Minecraft exited during flatten: "+str(log))
            time.sleep(2)
    finally: h.clean_stop(process,sink)
    p,current,total=h.count_state(h.FLATTEN)
    snapshot("STRUCTURES" if p.get("status")=="COMPLETE" else "FLATTEN",current,total,n)

def population_path(kind):
    if kind=="shipwreck": path,start,resume=h.SHIP,"oceancanvas shipwreck populate start","oceancanvas shipwreck populate resume"
    else: path,start,resume=h.MON,"oceancanvas monument populate start","oceancanvas monument populate resume"
    p=h.props(path)
    if p.get("status")=="PAUSED": h.full.assert_command_ok(h.full.rcon(resume),kind+" resume")
    elif not p: h.full.assert_command_ok(h.full.rcon(start),kind+" start")
    return path

def postflat(deadline,n):
    process,sink,_=h.launch(f"radius-postflat-{n}.log"); active=h.SHIP; phase="STRUCTURES"
    try:
        h.full.assert_command_ok(h.full.rcon("oceancanvas structurerules set shipwreck force_on"),"shipwreck rule")
        h.full.assert_command_ok(h.full.rcon("oceancanvas structurerules set monument force_on"),"monument rule")
        while time.monotonic()<deadline:
            if h.props(h.SHIP).get("status")!="COMPLETE": active=population_path("shipwreck")
            elif h.props(h.MON).get("status")!="COMPLETE": active=population_path("monument")
            elif h.props(h.HEALTH).get("status")!="COMPLETE":
                phase="HEALTH"; active=h.HEALTH
                if not h.props(h.HEALTH): h.full.assert_command_ok(h.full.rcon("oceancanvas health deep"),"health deep")
            else: phase="RESTORE"; break
            p,current,total=h.count_state(active); h.heartbeat(phase,current,total,n,"status="+p.get("status","?"))
            if process.poll() is not None: raise RuntimeError("Minecraft exited during postflat")
            time.sleep(5)
    finally: h.clean_stop(process,sink)
    _,current,total=h.count_state(active); snapshot(phase,current,total,n)

def restore(deadline,n):
    process,sink,log=h.launch(f"radius-restore-{n}.log")
    try:
        p=h.props(h.RESTORE)
        if not p: h.full.assert_command_ok(h.full.rcon("oceancanvas restore start RESTORE_CONFIGURED_CANVAS"),"restore start")
        elif p.get("status")=="PAUSED": h.full.assert_command_ok(h.full.rcon("oceancanvas restore resume"),"restore resume")
        last=0.0
        while time.monotonic()<deadline:
            p,current,total=h.count_state(h.RESTORE); now=time.monotonic()
            if now-last>=h.HEARTBEAT: h.heartbeat("RESTORE",current,total,n,"status="+p.get("status","?")); last=now
            if p.get("status")=="COMPLETE": break
            if process.poll() is not None: raise RuntimeError("Minecraft exited during restore: "+str(log))
            time.sleep(2)
    finally: h.clean_stop(process,sink)
    p,current,total=h.count_state(h.RESTORE); snapshot("FINAL" if p.get("status")=="COMPLETE" else "RESTORE",current,total,n)

def final(n):
    process,sink,_=h.launch(f"radius-final-{n}.log")
    try:
        if h.props(h.FLATTEN).get("status")!="COMPLETE" or h.props(h.RESTORE).get("status")!="COMPLETE":
            raise RuntimeError("final requires complete flatten and restore")
        h.full.assert_command_ok(h.full.rcon("oceancanvas restore status"),"final restore status")
    finally: h.clean_stop(process,sink)
    snapshot("COMPLETE",1,1,n)

def main():
    require_exact_checkout()
    identity=restore_checkpoint(); configure(identity)
    h.initialize(); enforce_fast_profile(); h.bind_runtime_context()
    if int(h.IDENTITY.get("targetChunks", 0)) != TARGET_CHUNKS:
        prior = dict(h.IDENTITY)
        h.IDENTITY = dict(prior)
        h.IDENTITY["targetChunks"] = TARGET_CHUNKS
        timing = json.loads(STARTED.read_text())
        timing["identity"] = h.IDENTITY
        STARTED.write_text(json.dumps(timing, indent=2) + "\n")
        print(f"CURRENT_1K_IDENTITY_MIGRATED oldTargetChunks={prior.get('targetChunks')} correctedTargetChunks={TARGET_CHUNKS}", flush=True)
    old=json.loads((CHECKPOINT/"checkpoint-status.json").read_text()) if (CHECKPOINT/"checkpoint-status.json").is_file() else {}
    n=int(old.get("checkpointNumber",0))+1
    if old.get("completionObserved") is True or old.get("phase")=="COMPLETE":
        print("CURRENT_1K_ALREADY_COMPLETE",flush=True); return
    deadline=time.monotonic()+SEGMENT_SECONDS
    if h.props(h.FLATTEN).get("status")!="COMPLETE": flatten(deadline,n)
    elif h.props(h.SHIP).get("status")!="COMPLETE" or h.props(h.MON).get("status")!="COMPLETE" or h.props(h.HEALTH).get("status")!="COMPLETE": postflat(deadline,n)
    elif h.props(h.RESTORE).get("status")!="COMPLETE": restore(deadline,n)
    else: final(n)
    s=json.loads((CHECKPOINT/"checkpoint-status.json").read_text())
    print("CURRENT_1K_SEGMENT_DONE "+json.dumps({"source":EXACT_SOURCE,"blocks":CANVAS_SIZE,"chunks":TARGET_CHUNKS,
      "phase":s.get("phase"),"current":s.get("current"),"total":s.get("total"),"checkpoint":s.get("checkpointNumber"),
      "overallApproxPercent":s.get("overallApproxPercent")},sort_keys=True),flush=True)

if __name__=="__main__": main()

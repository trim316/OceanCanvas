#!/usr/bin/env python3
"""Crash while batched persistence is intentionally pending; prove restart recovery remains exact."""
from __future__ import annotations
import importlib.util, json, os, shutil, subprocess, time
from pathlib import Path

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("fullproof", HERE.with_name("hosted-full-canvas-command-proof.py"))
full = importlib.util.module_from_spec(spec); spec.loader.exec_module(full)
single = full.single

PROJECT = full.PROJECT
RUN = full.RUN
WORLD = RUN / "world"
ROOT = WORLD / "oceancanvas-core" / "full-canvas"
FLATTEN = ROOT / "operation.properties"
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "batched-persist-crash-proof"
SEED = os.environ.get("OC_CRASH_SEED", "4182043")
CANVAS = int(os.environ.get("OC_CRASH_CANVAS", "128"))
full.OUTPUT = OUTPUT

def props(path):
    return single.properties(path) if path.is_file() else {}

def terminal_stage(path):
    if not path.is_file(): return None
    lines = path.read_text(errors="strict").splitlines()
    if not lines: return None
    p = lines[-1].split("\t")
    return p[5] if len(p) >= 6 else None

def configure():
    if WORLD.exists(): shutil.rmtree(WORLD)
    if OUTPUT.exists(): shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN/"eula.txt").write_text("eula=true\n")
    (RUN/"server.properties").write_text(
        "level-name=world\nlevel-seed="+SEED+"\nonline-mode=false\n"
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "pause-when-empty-seconds=-1\nenable-rcon=true\nrcon.port="+str(single.RCON_PORT)+"\n"
        "rcon.password="+single.PASSWORD+"\nserver-port=25591\n")
    cfg=RUN/"config"; cfg.mkdir(exist_ok=True)
    (cfg/"oceancanvas-core.properties").write_text(
        f"mode=CORE_AUTHORING\ncanvasSize={CANVAS}\ncenterX=0\ncenterZ=0\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        "maxBlockWritesPerTick=4096\nmaxChecksPerTick=16384\n"
        "stageWallBudgetMicros=15000\nphysicalSettleTicks=2\nlightSettleTicks=20\n")

def hard_kill(process, sink):
    process.kill()
    process.wait(timeout=30)
    if sink and not sink.closed: sink.close()

def main():
    configure()
    process=sink=log=None
    crash_snapshot={}
    try:
        process,sink,log=full.launch("before-crash.log")
        full.assert_command_ok(full.rcon("oceancanvas flatten start ERASE_CONFIGURED_CANVAS"),"flatten start")
        deadline=time.monotonic()+240
        while time.monotonic()<deadline:
            journals=list((ROOT/"chunks").glob("chunk_*/transitions.journal")) if (ROOT/"chunks").exists() else []
            stages=[terminal_stage(j) for j in journals]
            settled=sum(s=="PHYSICAL_SETTLED" for s in stages)
            persisted=sum(s=="PERSISTED" for s in stages)
            state=props(FLATTEN)
            # With persistBatch=12 and max wait=10 ticks this catches chunks
            # that have journaled settlement but have not yet earned durable PERSISTED.
            if settled >= 4 and persisted == 0:
                crash_snapshot={
                    "cursor":int(state.get("nextIndex","0") or 0),
                    "journals":len(journals),
                    "settled_waiting":settled,
                    "persisted":persisted,
                }
                hard_kill(process,sink); process=sink=log=None
                break
            if process.poll() is not None:
                raise RuntimeError("server exited before crash window")
            time.sleep(0.05)
        else:
            raise RuntimeError("did not observe an unflushed persistence batch")

        process,sink,log=full.launch("after-crash.log")
        resumed=props(FLATTEN)
        resume_cursor=int(resumed.get("nextIndex","0") or 0)
        if resume_cursor > crash_snapshot["cursor"]:
            raise RuntimeError(f"durable cursor advanced across hard crash: {crash_snapshot['cursor']} -> {resume_cursor}")
        if resumed.get("status")=="PAUSED":
            full.assert_command_ok(full.rcon("oceancanvas flatten resume"),"flatten resume")

        deadline=time.monotonic()+900
        while time.monotonic()<deadline:
            state=props(FLATTEN)
            if state.get("status")=="COMPLETE": break
            if state.get("status")=="PAUSED":
                raise RuntimeError("candidate auto-paused after crash")
            if process.poll() is not None:
                raise RuntimeError("server exited after crash")
            time.sleep(0.2)
        else:
            raise RuntimeError("recovery did not finish")

        state=props(FLATTEN)
        total=int(state.get("totalChunks","0") or 0)
        journals=list((ROOT/"chunks").glob("chunk_*/transitions.journal"))
        if len(journals)!=total:
            raise RuntimeError(f"journal count {len(journals)} != total {total}")
        bad=[str(j) for j in journals if terminal_stage(j)!="VERIFIED"]
        if bad:
            raise RuntimeError("non-VERIFIED terminal journals after crash recovery: "+repr(bad[:3]))
        return {
            "verdict":"PASS",
            "crash_snapshot":crash_snapshot,
            "restart_cursor":resume_cursor,
            "total_chunks":total,
            "verified_journals":len(journals),
        }
    finally:
        if process is not None:
            try: full.stop(process,sink)
            except Exception:
                if process.poll() is None: process.kill()
                if sink and not sink.closed: sink.close()

if __name__=="__main__":
    try:
        result=main(); code=0
    except BaseException as e:
        result={"verdict":"FAIL","error":str(e)}; code=1
    OUTPUT.mkdir(parents=True,exist_ok=True)
    (OUTPUT/"VERDICT.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2),flush=True)
    raise SystemExit(code)

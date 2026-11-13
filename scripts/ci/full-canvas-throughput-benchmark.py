#!/usr/bin/env python3
"""Disposable FullCanvas throughput benchmark. Never touches checkpointed scale-test worlds."""
from __future__ import annotations
import importlib.util, json, os, subprocess, sys, threading, time
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
OUTPUT = Path(os.environ.get("GITHUB_WORKSPACE", ".")) / ("throughput-benchmark-" + os.environ.get("OC_BENCH_NAME","unnamed"))
SEED = os.environ.get("OC_BENCH_SEED", "4182042")
CANVAS = int(os.environ.get("OC_BENCH_CANVAS", "128"))
CENTER_X = int(os.environ.get("OC_BENCH_CENTER_X", "0"))
CENTER_Z = int(os.environ.get("OC_BENCH_CENTER_Z", "0"))
WRITES = int(os.environ.get("OC_BENCH_WRITES", "4096"))
CHECKS = int(os.environ.get("OC_BENCH_CHECKS", "16384"))
WALL_US = int(os.environ.get("OC_BENCH_WALL_US", "15000"))
LIGHT_TICKS = int(os.environ.get("OC_BENCH_LIGHT_TICKS", "20"))
PHYSICAL_TICKS = int(os.environ.get("OC_BENCH_PHYSICAL_TICKS", "2"))
HEAP = os.environ.get("OC_BENCH_HEAP", "default")
full.OUTPUT = OUTPUT

def props(path):
    return single.properties(path) if path.is_file() else {}

def java_processes():
    try:
        text = subprocess.check_output(["ps","-eo","pid=,rss=,args="], text=True)
    except Exception:
        return []
    out=[]
    for line in text.splitlines():
        low=line.lower()
        if "java" not in low: continue
        if "runserver" not in low and "net.minecraft.server" not in low and "fabric-server-launch" not in low:
            continue
        parts=line.strip().split(None,2)
        if len(parts)<3: continue
        try: out.append({"pid":int(parts[0]),"rss_kib":int(parts[1]),"args":parts[2]})
        except ValueError: pass
    return out

def configure():
    # Refuse dangling symlinks too: Path.exists() alone follows links and can
    # return False for a link into an unavailable or removed world.
    if WORLD.exists() or WORLD.is_symlink():
        raise RuntimeError("benchmark refuses an existing world path; use a fresh disposable workspace")
    if OUTPUT.exists() or OUTPUT.is_symlink():
        raise RuntimeError("benchmark refuses existing evidence output; use a fresh disposable workspace")
    # The harness owns these files; never overwrite an earlier run's server
    # configuration or follow a symlink to an unrelated workspace.
    if RUN.is_symlink() or (RUN.exists() and not RUN.is_dir()):
        raise RuntimeError("benchmark refuses unsafe runtime directory")
    for owned in (RUN/"eula.txt", RUN/"server.properties", RUN/"config",
                  RUN/"config"/"oceancanvas-core.properties"):
        if owned.exists() or owned.is_symlink():
            raise RuntimeError("benchmark refuses existing runtime configuration: "+str(owned))
    OUTPUT.mkdir(parents=True)
    RUN.mkdir(parents=True,exist_ok=True)
    (RUN/"eula.txt").write_text("eula=true\n")
    (RUN/"server.properties").write_text(
        "level-name=world\nlevel-seed="+SEED+"\nonline-mode=false\n"
        "spawn-protection=0\nview-distance=2\nsimulation-distance=2\n"
        "pause-when-empty-seconds=-1\nenable-rcon=true\nrcon.port="+str(single.RCON_PORT)+"\n"
        "rcon.password="+single.PASSWORD+"\nserver-port=25591\n")
    cfg=RUN/"config"; cfg.mkdir(exist_ok=True)
    (cfg/"oceancanvas-core.properties").write_text(
        f"mode=CORE_AUTHORING\ncanvasSize={CANVAS}\ncenterX={CENTER_X}\ncenterZ={CENTER_Z}\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n"
        f"maxBlockWritesPerTick={WRITES}\nmaxChecksPerTick={CHECKS}\n"
        f"stageWallBudgetMicros={WALL_US}\nphysicalSettleTicks={PHYSICAL_TICKS}\n"
        f"lightSettleTicks={LIGHT_TICKS}\n")

def main():
    configure()
    samples=[]; running=True
    def sampler():
        while running:
            ps=java_processes()
            samples.append({"t":time.monotonic(),"rss_kib":sum(x["rss_kib"] for x in ps),"processes":ps})
            time.sleep(0.5)
    thread=threading.Thread(target=sampler,daemon=True); thread.start()
    process=sink=log=None
    progress=[]
    try:
        process,sink,log=full.launch("benchmark.log")
        started=time.monotonic()
        full.assert_command_ok(full.rcon("oceancanvas flatten start ERASE_CONFIGURED_CANVAS"),"flatten start")
        last=-1; last_change=started; max_gap=0.0
        deadline=started+1200
        while time.monotonic()<deadline:
            p=props(FLATTEN)
            cur=int(p.get("nextIndex","0") or 0); total=int(p.get("totalChunks","0") or 0)
            now=time.monotonic()
            if cur!=last:
                if last>=0: max_gap=max(max_gap,now-last_change)
                progress.append({"chunks":cur,"elapsed_s":now-started})
                last=cur; last_change=now
            if p.get("status")=="PAUSED":
                raise RuntimeError("benchmark auto-paused at %d/%d"%(cur,total))
            if p.get("status")=="COMPLETE":
                break
            if process.poll() is not None:
                tail=log.read_text(errors="replace")[-12000:] if log and log.exists() else ""
                raise RuntimeError("server exited during benchmark\n"+tail)
            time.sleep(0.2)
        else:
            raise RuntimeError("benchmark exceeded 20-minute bound")
        ended=time.monotonic(); max_gap=max(max_gap,ended-last_change)
        p=props(FLATTEN); done=int(p.get("nextIndex","0")); total=int(p.get("totalChunks","0"))
        if total <= 0 or done != total or p.get("status") != "COMPLETE":
            raise RuntimeError(f"flatten not complete or empty: {done}/{total}")
        # Strictly require every chunk journal to end VERIFIED.
        chunks_dir=ROOT/"chunks"
        journals=list(chunks_dir.glob("chunk_*/transitions.journal"))
        if len(journals)!=total:
            raise RuntimeError(f"journal count {len(journals)} != total {total}")
        verified=0
        stage_samples={}
        expected_stages = list(full.FLATTEN_STAGES)
        for j in journals:
            lines=j.read_text(errors="strict").splitlines()
            if not lines:
                raise RuntimeError("empty journal: "+str(j))
            parsed=[]
            for line in lines:
                p=line.split("\t")
                if len(p) < 6:
                    raise RuntimeError("malformed journal record: "+str(j))
                parsed.append((int(p[1]), p[5]))
            if [stage for _, stage in parsed] != expected_stages:
                raise RuntimeError("incomplete or reordered stage sequence: "+str(j))
            if any(parsed[i][0] < parsed[i-1][0] for i in range(1, len(parsed))):
                raise RuntimeError("nonmonotonic stage timestamps: "+str(j))
            for i in range(1,len(parsed)):
                stage=parsed[i][1]
                stage_samples.setdefault(stage,[]).append(parsed[i][0]-parsed[i-1][0])
            verified+=1
        stage_timing_ms={}
        for stage,vals in stage_samples.items():
            vals=sorted(vals)
            stage_timing_ms[stage]={
                "count":len(vals),
                "avg":sum(vals)/len(vals),
                "p50":vals[len(vals)//2],
                "p95":vals[min(len(vals)-1,int(len(vals)*0.95))]
            }
        if not samples or max((x["rss_kib"] for x in samples), default=0) <= 0:
            raise RuntimeError("missing live Java memory samples; refusing benchmark PASS")
        result={
            "verdict":"PASS","name":os.environ.get("OC_BENCH_NAME"),
            "source":subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),
            "canvas_blocks":CANVAS,"center":{"x":CENTER_X,"z":CENTER_Z},"chunks":total,"elapsed_s":ended-started,
            "throughput_chunks_per_min":total*60.0/(ended-started),
            "max_no_progress_s":max_gap,"verified_journals":verified,
            "peak_java_rss_kib":max((x["rss_kib"] for x in samples),default=0),
            "heap":HEAP,"writes_per_tick":WRITES,"checks_per_tick":CHECKS,
            "wall_budget_us":WALL_US,"physical_settle_ticks":PHYSICAL_TICKS,
            "light_settle_ticks":LIGHT_TICKS,"progress":progress,
            "last_process_snapshot":samples[-1]["processes"] if samples else [],
            "stage_timing_ms":stage_timing_ms
        }
        return result
    finally:
        running=False; thread.join(timeout=2)
        if process is not None:
            try: full.stop(process,sink)
            except Exception:
                if process.poll() is None: process.kill()
                if sink and not sink.closed: sink.close()

if __name__=="__main__":
    # Capture this before main() creates its own disposable evidence directory.
    # A refusal must not overwrite an existing benchmark result in the handler.
    output_preexisting = OUTPUT.exists() or OUTPUT.is_symlink()
    try:
        result=main(); code=0
    except BaseException as e:
        result={"verdict":"FAIL","name":os.environ.get("OC_BENCH_NAME"),"error":str(e)}; code=1
    if not output_preexisting:
        OUTPUT.mkdir(parents=True,exist_ok=True)
        (OUTPUT/"result.json").write_text(json.dumps(result,indent=2)+"\n")
    print("OC_THROUGHPUT_BENCH "+json.dumps(result,sort_keys=True),flush=True)
    raise SystemExit(code)

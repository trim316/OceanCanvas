#!/usr/bin/env python3
"""Certify save/restart and structure persistence on an already-completed scale checkpoint.

This deliberately does not run pregen, mutate terrain, or reinterpret historical timing.
It only cold-opens the preserved world with the exact installed candidate, compares
structure locate metadata with the pre-generation/rejoin baseline, saves, and stops.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, threading, time

ROOT = Path.cwd()
OUT = Path(os.environ["OC_EVIDENCE_DIR"]).resolve()
RUN = OUT / "server"
EXPECTED_BUILD = os.environ["EXPECTED_BUILD"]
EXPECTED_SOURCE = os.environ["EXPECTED_SOURCE_COMMIT"]
EXPECTED_JAR_SHA = os.environ["EXPECTED_JAR_SHA256"]


def sha256(path: Path) -> str:
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def require_clean_checkpoint():
    identity = json.loads((OUT / "identity.json").read_text())
    status = json.loads((OUT / "checkpoint-status.json").read_text())
    if identity.get("build") != EXPECTED_BUILD or identity.get("sourceCommit") != EXPECTED_SOURCE or identity.get("jarSha256") != EXPECTED_JAR_SHA:
        raise SystemExit("Completed checkpoint identity does not match frozen candidate")
    if status.get("completionObserved") is not True or status.get("serverExit") != 0 or status.get("savedWorldPresent") is not True:
        raise SystemExit("Checkpoint is not a clean completed world")
    if (OUT / "first-failure.json").exists() or (OUT / "stall.json").exists():
        raise SystemExit("Retained failure evidence blocks completed-checkpoint certification")
    if not (OUT / "locate-before.json").is_file():
        raise SystemExit("Missing locate-before.json; refusing to manufacture structure baseline")
    jars = [p for p in (RUN / "mods").glob("*.jar") if p.name.startswith("oceancanvas")]
    if len(jars) != 1 or sha256(jars[0]) != EXPECTED_JAR_SHA:
        raise SystemExit("Installed OceanCanvas JAR does not match frozen candidate")
    return identity, jars[0]


def main():
    identity, jar = require_clean_checkpoint()
    log = OUT / "controller-restart.log"
    process = subprocess.Popen([
        "java", "-Xms1G", "-Xmx14G", "-XX:+UseZGC", "-XX:SoftMaxHeapSize=10G",
        "-jar", "fabric-server-launch.jar", "nogui"
    ], cwd=RUN, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
       text=True, bufsize=1)
    lines=[]
    def collect():
        with log.open("w", encoding="utf-8", buffering=65536) as target:
            for line in process.stdout:
                target.write(line); lines.append(line)
    thread=threading.Thread(target=collect, daemon=True); thread.start()
    def command(value):
        process.stdin.write(value+"\n"); process.stdin.flush()
    def wait(pattern, seconds, start=0):
        rx=re.compile(pattern, re.I); deadline=time.monotonic()+seconds; cursor=start
        while time.monotonic()<deadline:
            for i,line in enumerate(lines[cursor:], cursor):
                if re.search(r"PREGEN-ACCEPTANCE-START|Starting pregen|Pregen started", line, re.I):
                    raise RuntimeError("Generation rerun detected during completed-checkpoint certification")
                if re.search(r"ERROR|Exception", line) and "Done (" not in line:
                    # Preserve the complete log; do not infer PASS across an explicit runtime error.
                    raise RuntimeError("Runtime error during completed-checkpoint certification: "+line.strip())
                if rx.search(line): return i+1
            cursor=len(lines)
            if process.poll() is not None: raise RuntimeError(f"Server exited {process.returncode} before {pattern}")
            time.sleep(.2)
        raise TimeoutError(pattern)
    try:
        wait(r"Done \(", 240)
        result={}
        for key in ("minecraft:shipwreck","minecraft:ocean_ruin_cold","minecraft:monument"):
            cursor=len(lines); command("execute positioned 0 63 0 run locate structure "+key)
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                for line in lines[cursor:]:
                    if key not in line: continue
                    m=re.search(r"nearest.*"+re.escape(key)+r".*?\[(-?\d+),\s*(?:~|-?\d+),\s*(-?\d+)\]", line, re.I)
                    if m: result[key]={"found":True,"x":int(m.group(1)),"z":int(m.group(2))}; break
                    if re.search(r"(?:not find|unable to find).*structure", line, re.I): result[key]={"found":False}; break
                if key in result: break
                if process.poll() is not None: raise RuntimeError("Server exited during locate regression")
                time.sleep(.2)
            if key not in result: raise TimeoutError("Missing locate result for "+key)
        before=json.loads((OUT/"locate-before.json").read_text())
        if result != before: raise RuntimeError("Structure locate metadata changed across completed-world cold reopen")
        command("save-all flush"); time.sleep(3); command("stop")
        process.wait(timeout=180); thread.join(timeout=10)
        if process.returncode != 0: raise RuntimeError(f"Saved-world restart shutdown exit={process.returncode}")
        if sha256(jar) != EXPECTED_JAR_SHA: raise RuntimeError("Frozen candidate JAR changed during restart proof")
        common={"passed":True,"build":EXPECTED_BUILD,"sourceCommit":EXPECTED_SOURCE,"jarSha256":EXPECTED_JAR_SHA,
                "requestedBlocks":identity["requestedBlocks"],"targetChunks":identity["targetChunks"],"terrainRerun":False}
        (OUT/"locate-after-rejoin.json").write_text(json.dumps(common|{"results":result,"scope":"completed-checkpoint-structure-rejoin"},indent=2)+"\n")
        (OUT/"restart.json").write_text(json.dumps(common|{"scope":"completed-checkpoint-cold-save-restart","logSha256":sha256(log)},indent=2)+"\n")
        print("COMPLETED_CHECKPOINT_CERTIFIED restart=true structures=true terrainRerun=false")
    finally:
        if process.poll() is None:
            try: command("stop"); process.wait(timeout=120)
            except Exception: process.kill(); process.wait(timeout=30)
        thread.join(timeout=10)

if __name__ == "__main__": main()

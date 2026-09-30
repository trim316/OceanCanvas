#!/usr/bin/env python3
"""Disposable cloud Minecraft one-chunk restart/restore proof. Never touches user PC."""
import json
import hashlib
import os
import pathlib
import secrets
import socket
import struct
import subprocess
import sys
import time
import zlib

PROJECT = pathlib.Path(__file__).resolve().parents[2] / "runtime-src"
RUN = PROJECT / "run"
WORLD = RUN / "world"
STATE = WORLD / "oceancanvas-core" / "single-chunk"
OUTPUT = pathlib.Path(os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-minecraft-proof"
OUTPUT.mkdir(parents=True, exist_ok=True)
STAGES = ["LOADED", "PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "PHYSICAL_SETTLED",
          "PERSISTED", "LIGHTING_SETTLED", "VERIFIED", "RESTORED", "RESTORE_VERIFIED", "COMPLETE"]
RCON_PORT = 25586
PASSWORD = secrets.token_hex(16)
SESSION_LIMIT = 12
# Cloud-only deliberate process interruption; never run against a user world.
INTERRUPT_AFTER = os.environ.get("OCEANCANVAS_INTERRUPT_AFTER", "").strip()
if INTERRUPT_AFTER and INTERRUPT_AFTER not in ("PREIMAGE_CAPTURED", "PHYSICAL_AUTHORED", "RESTORED"):
    raise SystemExit("unsupported cloud-only interruption stage: " + INTERRUPT_AFTER)

def properties(path):
    if not path.exists():
        return {}
    result = {}
    for line in path.read_text(errors="replace").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, val = line.split("=", 1)
            result[key.strip()] = val.strip()
    return result

def rcon_stop():
    with socket.create_connection(("127.0.0.1", RCON_PORT), timeout=5) as sock:
        sock.settimeout(5)
        def packet(pid, typ, text):
            body = struct.pack("<ii", pid, typ) + text.encode() + b"\x00\x00"
            sock.sendall(struct.pack("<i", len(body)) + body)
        def response():
            size = struct.unpack("<i", sock.recv(4))[0]
            payload = b""
            while len(payload) < size:
                chunk = sock.recv(size - len(payload))
                if not chunk: raise ConnectionError("RCON response truncated")
                payload += chunk
            return struct.unpack("<ii", payload[:8])
        packet(41, 3, PASSWORD)
        # Some RCON implementations emit an empty value packet before auth.
        # Never confuse that packet with successful authentication.
        authenticated = False
        for _ in range(3):
            pid, typ = response()
            if pid == -1:
                raise RuntimeError("RCON authentication failed")
            if pid == 41 and typ == 2:
                authenticated = True
                break
        if not authenticated:
            raise RuntimeError("RCON authentication response missing")
        packet(42, 2, "stop")

def verify_journal():
    path = STATE / "transitions.journal"
    data = path.read_bytes()
    if not data.endswith(b"\n"): raise RuntimeError("journal missing final newline")
    lines = data.decode().splitlines()
    if len(lines) != 10: raise RuntimeError("journal transition count: " + str(len(lines)))
    previous = "DISCOVERED"
    for index, line in enumerate(lines):
        fields = line.split("\t")
        if len(fields) != 10: raise RuntimeError("journal field count at " + str(index))
        payload = "\t".join(fields[:-1])
        if zlib.crc32(payload.encode()) != int(fields[-1]):
            raise RuntimeError("journal CRC mismatch at " + str(index))
        if (fields[0] != str(index) or fields[2:4] != ["32", "32"]
            or fields[4] != previous or fields[5] != STAGES[index]
            or fields[7] != str(index + 1)):
            raise RuntimeError("journal sequence/identity/transition mismatch at " + str(index))
        previous = fields[5]
    return len(lines)

def verify_archive_receipt_chain():
    archive = STATE / "preimage-blockstates.bin.completed.archive"
    receipt_path = STATE / "runtime-receipts.log"
    if not archive.is_file():
        raise RuntimeError("completed recovery archive missing at final restart")
    if archive.stat().st_size > 16 * 1024 * 1024:
        raise RuntimeError("completed recovery archive exceeds safe size bound")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if not receipt_path.is_file() or receipt_path.stat().st_size > 8 * 1024 * 1024:
        raise RuntimeError("missing or oversized forensic receipt chain")
    raw = receipt_path.read_bytes()
    if not raw.endswith(b"\\n"):
        raise RuntimeError("unterminated forensic receipt chain")
    required = {
        "PREIMAGE_CAPTURED": "preimageSha256",
        "RESTORE_COMPLETE": "preimageSha256",
        "RESTORE_VERIFIED": "preimageSha256",
        "TICKET_RELEASED": "preimageArchiveSha256",
    }
    operation = properties(STATE / "operation.properties").get("operationId")
    if not operation:
        raise RuntimeError("missing exact operation identity")
    seen = {kind: 0 for kind in required}
    for index, line in enumerate(raw.decode("utf-8").splitlines()):
        fields = line.split("\\t")
        if len(fields) != 7 or fields[0] != str(index) or fields[3:5] != ["32", "32"]:
            raise RuntimeError("receipt sequence/target/field count mismatch at " + str(index))
        payload = "\\t".join(fields[:-1])
        if zlib.crc32(payload.encode("utf-8")) != int(fields[-1]):
            raise RuntimeError("receipt checksum mismatch at " + str(index))
        kind = fields[2]
        if kind not in required:
            continue
        parts = fields[5].split(";")
        expected_field = required[kind] + "=" + digest
        if expected_field not in parts:
            # Non-terminal ticket releases are diagnostic, not archive proof.
            if kind == "TICKET_RELEASED" and not any(
                    part.startswith("preimageArchiveSha256=") for part in parts):
                continue
            raise RuntimeError(kind + " receipt differs from actual immutable archive")
        if kind != "TICKET_RELEASED" and ("operation=" + operation) not in parts:
            raise RuntimeError(kind + " receipt operation mismatch")
        if kind == "TICKET_RELEASED" and "restoreVerified=true" not in parts:
            raise RuntimeError("archive release missing verified restore")
        seen[kind] += 1
    if any(count == 0 for count in seen.values()):
        raise RuntimeError("missing required archived preimage evidence: " + repr(seen))
    return {"sha256": digest, "receipt_stage_counts": seen}


def main():
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "level-name=world\nonline-mode=false\nspawn-protection=0\n"
        "view-distance=2\nsimulation-distance=2\n"
        "enable-rcon=true\nrcon.port=" + str(RCON_PORT) + "\n"
        "rcon.password=" + PASSWORD + "\n"
        "server-port=25591\n"
    )
    (RUN / "config").mkdir(exist_ok=True)
    (RUN / "config" / "oceancanvas-core.properties").write_text(
        "mode=CORE_AUTHORING\ncanvasSize=20000\ncenterX=0\ncenterZ=0\n"
        "waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n"
        "expansionEnabled=false\nsingleChunkEnabled=true\n"
        "singleChunkX=32\nsingleChunkZ=32\nsingleChunkConfirm=ERASE_CHUNK_32_32\n"
        "maxBlockWritesPerTick=256\nmaxChecksPerTick=1024\n"
        "stageWallBudgetMicros=3000\nphysicalSettleTicks=40\n"
        "lightSettleTicks=40\nacceptanceHarnessEnabled=true\n"
    )
    statefile = STATE / "acceptance-state.properties"
    prior_sessions = 0
    observed = []
    interruption_proven = False
    for session in range(SESSION_LIMIT):
        log = OUTPUT / ("server-session-%02d.log" % (session+1))
        with log.open("wb") as output:
            process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
                cwd=PROJECT, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 300
                reached = None
                while time.monotonic() < deadline:
                    props = properties(statefile)
                    sessions = int(props.get("sessionsOpened", "0"))
                    stagefile = STATE / "transitions.journal"
                    entries = stagefile.read_text(errors="replace").splitlines() if stagefile.exists() else []
                    stage = entries[-1].split("\t")[5] if entries and len(entries[-1].split("\t")) > 5 else "DISCOVERED"
                    if sessions > prior_sessions:
                        if stage == "COMPLETE" and props.get("finalRestartVerified") == "true":
                            reached = "FINAL_RESTART"
                            break
                        awaiting = props.get("awaitingRestartStage", "")
                        if awaiting and awaiting == stage and (not observed or stage != observed[-1]):
                            reached = stage
                            break
                    if process.poll() is not None:
                        raise RuntimeError("server exited before lifecycle hold; see " + str(log))
                    time.sleep(1)
                if not reached:
                    raise RuntimeError("timeout waiting for new lifecycle stage in " + str(log))
                if reached != "FINAL_RESTART":
                    observed.append(reached)
                prior_sessions = sessions
                if reached == INTERRUPT_AFTER and not interruption_proven:
                    # Kill only the server subprocess launched in this disposable
                    # hosted job. The journal/preimage fsyncs are already complete
                    # for the observed transition; the next session must replay.
                    process.kill()
                    process.wait(timeout=20)
                    interruption_proven = True
                    (OUTPUT / "INTERRUPTION.txt").write_text(
                        "stage=" + reached + "\\nmethod=hosted-server-process-kill\\n"
                        "source=disposable-cloud-world\\n")
                    continue
                try:
                    rcon_stop()
                except Exception as exc:
                    raise RuntimeError("graceful cloud server stop failed: " + str(exc))
                try:
                    process.wait(timeout=90)
                except subprocess.TimeoutExpired:
                    raise RuntimeError("cloud server did not exit after RCON stop")
                if process.returncode != 0:
                    raise RuntimeError("Gradle/server exited " + str(process.returncode))
                if reached == "FINAL_RESTART":
                    break
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=20)
                    except subprocess.TimeoutExpired: process.kill()
    else:
        raise RuntimeError("session limit reached without final restart")
    if observed != STAGES:
        raise RuntimeError("missing/duplicate stage: " + repr(observed))
    if INTERRUPT_AFTER and not interruption_proven:
        raise RuntimeError("requested deliberate interruption was not exercised: " + INTERRUPT_AFTER)
    count = verify_journal()
    props = properties(statefile)
    if int(props.get("verifiedRestarts", "0")) < 10 or prior_sessions < 11:
        raise RuntimeError("insufficient real server restart evidence")
    if (STATE / "preimage-blockstates.bin").exists():
        raise RuntimeError("live preimage unexpectedly survived completed archival")
    archive_proof = verify_archive_receipt_chain()
    return {"verdict": "PASS", "stages": observed, "journal_entries": count,
            "sessions": prior_sessions, "verified_restarts": props["verifiedRestarts"],
            "archived_preimage": archive_proof,
            "interruption": INTERRUPT_AFTER or "none", "interruption_exercised": interruption_proven}

if __name__ == "__main__":
    try:
        report = main()
        code = 0
    except BaseException as exc:
        report = {"verdict": "FAIL", "error": str(exc)}
        code = 1
    (OUTPUT / "VERDICT.json").write_text(json.dumps(report, indent=2) + "\n")
    if STATE.exists():
        for name in ("acceptance-state.properties", "transitions.journal",
                     "runtime-receipts.log", "operation.properties",
                     "preimage-blockstates.bin.completed.archive"):
            source = STATE / name
            if source.exists():
                (OUTPUT / name).write_bytes(source.read_bytes())
    print(json.dumps(report, indent=2), flush=True)
    sys.exit(code)

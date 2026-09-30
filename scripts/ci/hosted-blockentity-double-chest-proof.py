#!/usr/bin/env python3
"""Disposable Minecraft proof: paired double-chest states + NBT restore exactly and survive cold reopen."""
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location(
    "restore_proof", Path(__file__).resolve().with_name("hosted-blockentity-restore-proof.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)

capture = restore.capture
RUN = restore.RUN
WORLD = restore.WORLD
STATE = restore.STATE
OUTPUT = Path(capture.os.environ.get("GITHUB_WORKSPACE", ".")) / "hosted-blockentity-double-chest-proof"

LEFT = (512, 25, 512)
RIGHT = (513, 25, 512)


def inspect(pos, item):
    x, y, z = pos
    capture.rcon(f"forceload add {x} {z}")
    time.sleep(0.5)
    try:
        data = capture.rcon(f"data get block {x} {y} {z}")
    finally:
        capture.rcon(f"forceload remove {x} {z}")
    if "minecraft:chest" not in data.lower() and "chest" not in data.lower():
        raise RuntimeError("expected chest block entity missing at %r: %s" % (pos, data))
    if item.split(":", 1)[1] not in data.lower():
        raise RuntimeError("expected inventory item missing at %r: %s" % (pos, data))
    return data


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if WORLD.exists():
        raise RuntimeError("proof requires initially absent disposable world")
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        capture.server_properties().replace("level-seed=4182033", "level-seed=4182037"))

    # Establish both chest halves with explicit paired block states while
    # OceanCanvas has zero mutation authority.
    capture.write_core(False)
    setup_log = OUTPUT / "setup-safe-hold.log"
    process, sink = capture.start(setup_log)
    try:
        capture.wait_for(setup_log, "Done (", process)
        capture.rcon("forceload add 512 512")
        time.sleep(1)
        a = capture.rcon(
            "setblock 512 25 512 minecraft:chest[facing=north,type=left,waterlogged=false]")
        b = capture.rcon(
            "setblock 513 25 512 minecraft:chest[facing=north,type=right,waterlogged=false]")
        if "changed" not in a.lower() or "changed" not in b.lower():
            raise RuntimeError("failed to establish paired chest states: %r / %r" % (a, b))
        ia = capture.rcon(
            "item replace block 512 25 512 container.0 with minecraft:diamond 3")
        ib = capture.rcon(
            "item replace block 513 25 512 container.0 with minecraft:emerald 2")
        if ("replaced" not in ia.lower() and "modified" not in ia.lower()):
            raise RuntimeError("failed to seed left chest inventory: " + ia)
        if ("replaced" not in ib.lower() and "modified" not in ib.lower()):
            raise RuntimeError("failed to seed right chest inventory: " + ib)
        inspect(LEFT, "minecraft:diamond")
        inspect(RIGHT, "minecraft:emerald")
        capture.rcon("save-all flush")
        capture.rcon("forceload remove 512 512")
    finally:
        capture.stop(process)
        sink.close()

    capture.write_core(True)
    cfg = RUN / "config"
    (cfg / "oceancanvas-block-entity-recovery.properties").write_text(
        "enabled=true\nchunkX=32\nchunkZ=32\n"
        "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n")

    run_log = OUTPUT / "author-restore.log"
    process, sink = capture.start(run_log)
    try:
        text = restore.wait_complete(run_log, process)
        if "blockEntities=2" not in text:
            raise RuntimeError("runtime did not attest both double-chest block entities")
        inspect(LEFT, "minecraft:diamond")
        inspect(RIGHT, "minecraft:emerald")

        state_archive = STATE / "preimage-blockstates.bin.completed.archive"
        sidecar_archive = STATE / "preimage-blockentities.ocbe.completed.archive"
        if not state_archive.is_file() or not sidecar_archive.is_file():
            raise RuntimeError("double-chest recovery did not archive both preimages")
        state_sha = restore.sha(state_archive)
        sidecar_sha = restore.sha(sidecar_archive)
        parsed = capture.parse_sidecar(sidecar_archive)
        if parsed["preimage_sha256"] != state_sha:
            raise RuntimeError("double-chest sidecar lost block-state archive binding")
        types = [entry[1] for entry in parsed["entries"]]
        if types != ["minecraft:chest", "minecraft:chest"]:
            raise RuntimeError("double-chest sidecar type/count mismatch: " + repr(types))
        receipts = restore.verified_receipts()
        release_detail = next((d for kind, d in receipts
                               if kind == "TICKET_RELEASED"
                               and "restoreVerified=true" in d), None)
        if release_detail is None:
            raise RuntimeError("double-chest recovery lacks terminal release receipt")
        if restore.detail_token(release_detail, "blockEntities") != "2":
            raise RuntimeError("release receipt does not attest both chest halves")
        if restore.detail_token(release_detail, "blockEntityArchiveSha256") != sidecar_sha:
            raise RuntimeError("release receipt does not bind double-chest sidecar archive")
    finally:
        capture.stop(process)
        sink.close()

    cold_log = OUTPUT / "cold-reopen.log"
    process, sink = capture.start(cold_log)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            text = cold_log.read_text(errors="replace") if cold_log.exists() else ""
            if "SINGLE-CHUNK-INIT-FAILED" in text:
                raise RuntimeError("cold reopen rejected double-chest completed evidence")
            if "SINGLE-CHUNK-OPEN" in text and "resumedStage=COMPLETE" in text:
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before double-chest cold reopen")
            time.sleep(0.5)
        else:
            raise RuntimeError("double-chest cold reopen did not attest COMPLETE")
        inspect(LEFT, "minecraft:diamond")
        inspect(RIGHT, "minecraft:emerald")
        if restore.sha(STATE / "preimage-blockstates.bin.completed.archive") != state_sha:
            raise RuntimeError("cold reopen changed double-chest state archive")
        if restore.sha(STATE / "preimage-blockentities.ocbe.completed.archive") != sidecar_sha:
            raise RuntimeError("cold reopen changed double-chest sidecar archive")
    finally:
        capture.stop(process)
        sink.close()

    return {
        "verdict": "PASS",
        "seed": "4182037",
        "cold_reopen": True,
        "block_entity_count": 2,
        "structure": "paired double chest",
        "types": ["minecraft:chest", "minecraft:chest"],
        "inventories": ["minecraft:diamond x3", "minecraft:emerald x2"],
        "state_archive_sha256": state_sha,
        "block_entity_archive_sha256": sidecar_sha,
    }


if __name__ == "__main__":
    try:
        result = main()
        code = 0
    except BaseException as exc:
        result = {"verdict": "FAIL", "error": str(exc)}
        code = 1
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "VERDICT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(code)

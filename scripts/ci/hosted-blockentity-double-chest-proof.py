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

    # Establish a real paired chest structure while OceanCanvas has no mutation
    # authority. This fixture intentionally exercises vanilla-coupled block
    # states whose chest half may normalize after reload/ticks.
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

    # Authoring plus BE recovery consent is explicit, but this coupled structure
    # must still fail closed if vanilla changes either captured block state
    # before OceanCanvas begins physical authoring.
    capture.write_core(True)
    cfg = RUN / "config"
    (cfg / "oceancanvas-block-entity-recovery.properties").write_text(
        "enabled=true\nchunkX=32\nchunkZ=32\n"
        "confirm=RECOVER_BLOCK_ENTITIES_CHUNK_32_32\n")

    refusal_log = OUTPUT / "author-refusal.log"
    process, sink = capture.start(refusal_log)
    failure_text = ""
    try:
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            text = refusal_log.read_text(errors="replace") if refusal_log.exists() else ""
            journal = STATE / "transitions.journal"
            stages = []
            failure_reason = ""
            if journal.exists():
                for line in journal.read_text(errors="replace").splitlines():
                    fields = line.split("\t")
                    if len(fields) >= 6:
                        stages.append(fields[5])
                        if fields[5] == "FAILED" and len(fields) >= 9:
                            failure_reason = fields[8]
            if stages and stages[-1] == "FAILED":
                # The journal is fsynced before the logger necessarily flushes.
                # Use the durable transition reason as the authority so a
                # correct fail-closed operation cannot race the proof harness.
                failure_text = failure_reason or text
                break
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before paired-chest refusal")
            time.sleep(0.25)
        else:
            raise RuntimeError("paired-chest edge did not reach fail-closed refusal")

        if "physical authoring refuses changed captured block-entity state" not in failure_text:
            raise RuntimeError("paired-chest failure was not the captured-state safety guard")

        state_preimage = STATE / "preimage-blockstates.bin"
        sidecar = STATE / "preimage-blockentities.ocbe"
        if not state_preimage.is_file() or not sidecar.is_file():
            raise RuntimeError("paired-chest refusal lost durable recovery evidence")
        parsed = capture.parse_sidecar(sidecar)
        if len(parsed["entries"]) != 2:
            raise RuntimeError("paired-chest refusal did not retain both NBT records")
        if sorted(entry[1] for entry in parsed["entries"]) != ["minecraft:chest", "minecraft:chest"]:
            raise RuntimeError("paired-chest sidecar type set changed")
        if (STATE / "preimage-blockstates.bin.completed.archive").exists():
            raise RuntimeError("failed paired-chest operation incorrectly created completed state archive")
        if (STATE / "preimage-blockentities.ocbe.completed.archive").exists():
            raise RuntimeError("failed paired-chest operation incorrectly created completed sidecar archive")

        # FAILED releases OceanCanvas' ticket. Inspect the disposable structure
        # independently; both inventories must still be present.
        inspect(LEFT, "minecraft:diamond")
        inspect(RIGHT, "minecraft:emerald")
        state_sha = restore.sha(state_preimage)
        sidecar_sha = restore.sha(sidecar)
    finally:
        capture.stop(process)
        sink.close()

    # Reopen only in SAFE_HOLD and prove the refused world/evidence remain
    # untouched across a server restart.
    capture.write_core(False)
    cold_log = OUTPUT / "safe-hold-reopen.log"
    process, sink = capture.start(cold_log)
    try:
        capture.wait_for(cold_log, "Done (", process)
        inspect(LEFT, "minecraft:diamond")
        inspect(RIGHT, "minecraft:emerald")
        if restore.sha(STATE / "preimage-blockstates.bin") != state_sha:
            raise RuntimeError("SAFE_HOLD reopen changed refused state preimage")
        if restore.sha(STATE / "preimage-blockentities.ocbe") != sidecar_sha:
            raise RuntimeError("SAFE_HOLD reopen changed refused block-entity sidecar")
    finally:
        capture.stop(process)
        sink.close()

    return {
        "verdict": "PASS",
        "seed": "4182037",
        "structure": "paired double chest",
        "expected_outcome": "fail_closed_before_physical_authoring",
        "block_entity_count": 2,
        "both_inventories_survived": True,
        "durable_state_preimage_sha256": state_sha,
        "durable_block_entity_sidecar_sha256": sidecar_sha,
        "completed_archive_created": False,
        "safe_hold_reopen": True,
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

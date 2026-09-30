#!/usr/bin/env python3
"""Live disposable-world refusal proof: immutable two-chunk plan cannot redirect target."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

module_path = Path(__file__).resolve().with_name("hosted-two-chunk-proof.py")
spec = importlib.util.spec_from_file_location("twochunk_hosted", module_path)
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)

OUTPUT = proof.OUTPUT.parent / "hosted-two-chunk-identity-refusal"
OUTPUT.mkdir(parents=True, exist_ok=True)


def main():
    # First establish a real complete operation on both exact originally
    # consented chunks, including verified unchanged archives after cold restart.
    baseline = proof.main()
    if baseline["verdict"] != "PASS" or not baseline["cold_restart"]:
        raise RuntimeError("ordinary two-chunk and cold-restart baseline not PASS")
    first_before = proof.independently_verify_chunk(proof.TARGETS[0])
    second_before = proof.independently_verify_chunk(proof.TARGETS[1])
    identity_path = proof.ROOT / "pair-operation.identity"
    identity_bytes = identity_path.read_bytes()
    if not identity_bytes.startswith(b"OCEANCANVAS_TWO_CHUNK_PLAN_V1\n"):
        raise RuntimeError("baseline omitted immutable pair identity")

    consent = proof.RUN / "config" / "oceancanvas-two-chunk-canary.properties"
    contents = consent.read_text()
    if contents.count("secondX=33\n") != 1 or contents.count(
            "secondConfirm=ERASE_CHUNK_33_32\n") != 1:
        raise RuntimeError("unexpected baseline consent; refusing negative fixture")
    # Redirect to a *different, still edge-adjacent* target; otherwise
    # the ordinary adjacency gate rejects first and never tests pair identity.
    changed = contents.replace("secondX=33\n", "secondX=32\n")
    changed = changed.replace("secondZ=32\n", "secondZ=33\n")
    changed = changed.replace("secondConfirm=ERASE_CHUNK_33_32\n",
                              "secondConfirm=ERASE_CHUNK_32_33\n")
    consent.write_text(changed)
    unapproved = proof.chunk_root((32, 33))
    if unapproved.exists():
        raise RuntimeError("disposable redirect target already has operation state")

    server_log = OUTPUT / "redirected-restart.log"
    with server_log.open("wb") as sink:
        process = subprocess.Popen(["./gradlew", "--no-daemon", "runServer"],
            cwd=proof.PROJECT, stdout=sink, stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                text = server_log.read_text(errors="replace")
                if "TWO-CHUNK-INIT-FAILED" in text:
                    if "immutable two-chunk plan changed" not in text:
                        raise RuntimeError("refusal occurred, but not because of immutable plan mismatch")
                    break
                if "TWO-CHUNK-CANARY-OPEN" in text:
                    raise RuntimeError("altered second target unexpectedly acquired authority")
                if process.poll() is not None:
                    raise RuntimeError("redirected server terminated before refusal evidence")
                time.sleep(1)
            else:
                raise RuntimeError("altered second target was not explicitly rejected")
            proof.single.rcon_stop()
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError("refusal proof server did not gracefully shut down")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()

    if identity_path.read_bytes() != identity_bytes:
        raise RuntimeError("refused restart modified canonical immutable identity")
    if unapproved.exists():
        raise RuntimeError("refused restart opened an unauthorized third chunk")
    if proof.independently_verify_chunk(proof.TARGETS[0]) != first_before:
        raise RuntimeError("refused restart changed first verified archive evidence")
    if proof.independently_verify_chunk(proof.TARGETS[1]) != second_before:
        raise RuntimeError("refused restart changed second verified archive evidence")
    return {
        "verdict": "PASS",
        "seed": proof.SEED,
        "baseline_cold_restart": True,
        "refused_redirect_to": [32, 33],
        "original_chunks_unchanged": True,
        "canonical_pair_identity_unchanged": True,
        "new_chunk_state_created": False,
    }


if __name__ == "__main__":
    try:
        result = main()
        exit_code = 0
    except BaseException as exc:
        result = {"verdict": "FAIL", "error": str(exc)}
        exit_code = 1
    (OUTPUT / "VERDICT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    sys.exit(exit_code)

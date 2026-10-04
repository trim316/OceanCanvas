#!/usr/bin/env python3
"""R1-71: every hosted controller that creates runtime-src/run must guard it first."""
from __future__ import annotations
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
DIRECT_WORLD_CREATORS = (
    "hosted-minecraft-proof.py",
    "hosted-two-chunk-proof.py",
    "hosted-blockentity-capture-refusal.py",
    "hosted-late-blockentity-refusal.py",
    "hosted-blockentity-restore-proof.py",
    "hosted-blockentity-interruption-proof.py",
    "hosted-blockentity-multitype-proof.py",
    "hosted-blockentity-double-chest-proof.py",
    "hosted-four-chunk-proof.py",
    "hosted-nine-chunk-proof.py",
    "hosted-sixteen-chunk-proof.py",
)

for name in DIRECT_WORLD_CREATORS:
    text = (HERE / name).read_text(encoding="utf-8")
    main = text.find("def main():")
    assert main >= 0, f"{name}: missing main"
    guard = text.find("require_pristine_disposable_world(", main)
    create = text.find("RUN.mkdir(parents=True, exist_ok=False)", main)
    assert guard >= 0, f"{name}: missing pristine disposable-world guard"
    assert create >= 0, f"{name}: run root must use exist_ok=False"
    assert guard < create, f"{name}: guard must execute before run-root creation"

identity = (HERE / "hosted-two-chunk-identity-refusal.py").read_text(encoding="utf-8")
assert "baseline = proof.main()" in identity
assert "RUN.mkdir(" not in identity

print(f"R1-71 hosted-controller disposable-world guard PASS ({len(DIRECT_WORLD_CREATORS)} creators)")

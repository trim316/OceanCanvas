#!/usr/bin/env python3
"""Strict end-to-end OceanCanvas throughput gate.

Requires an independently produced JSON receipt from a disposable Minecraft
load/save/restart verification run. Structural NBT tests cannot pass this gate.
"""
import argparse
import json
from pathlib import Path

TARGET=500.0
REQUIRED=("minecraft_loaded","restart_verified","terrain_verified",
          "biomes_verified","lighting_verified","structures_verified",
          "protected_areas_unchanged","all_chunks_physically_verified")

def evaluate(data):
    if not isinstance(data,dict):raise ValueError("receipt must be an object")
    if data.get("scope")!="minecraft_end_to_end":raise ValueError("not an end-to-end Minecraft receipt")
    for key in REQUIRED:
        if data.get(key) is not True:raise ValueError(f"missing successful verification: {key}")
    chunks=data.get("verified_chunks")
    seconds=data.get("elapsed_seconds")
    if type(chunks) is not int or chunks<=0:raise ValueError("verified_chunks must be positive integer")
    if type(seconds) not in (int,float) or not 0<seconds<1e9:raise ValueError("elapsed_seconds invalid")
    if data.get("expected_chunks")!=chunks:raise ValueError("not all expected chunks verified")
    rate=chunks*60/seconds
    return {"verified_chunks":chunks,"verified_chunks_per_minute":round(rate,3),
            "target":TARGET,"pass":rate>=TARGET}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("receipt",type=Path)
    args=parser.parse_args()
    result=evaluate(json.loads(args.receipt.read_text()))
    print(json.dumps(result,sort_keys=True))
    if not result["pass"]:raise SystemExit(1)

if __name__=="__main__":main()

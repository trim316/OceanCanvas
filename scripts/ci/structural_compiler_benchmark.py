#!/usr/bin/env python3
"""Reproducible structural throughput comparison; NOT a Minecraft world benchmark.

Compares direct NBT serialization vs cache reuse and parallel Anvil packing.
Never writes to a user's world. Measures wall clock, sizes, and hashes.
"""
import argparse
import json
import tempfile
import time
from pathlib import Path
from direct_section_nbt import section_root_nbt
from section_template_cache import SectionTemplateCache
from offline_region_packer import pack_regions_parallel
from region_receipts import receipt,verify_receipt

def run(region_count=1,workers=1):
    if region_count<1 or workers<1:raise ValueError("positive region_count and workers required")
    water=["minecraft:water"]*4096
    stone=["minecraft:stone"]*4096
    # Alternating sections ensure the cache actually has both hits and misses.
    states=[water,stone]
    start=time.perf_counter()
    direct=[section_root_nbt(0,states[i%2]) for i in range(region_count*1024)]
    direct_seconds=time.perf_counter()-start
    cache=SectionTemplateCache()
    start=time.perf_counter()
    cached=[cache.encode(0,states[i%2]) for i in range(region_count*1024)]
    cached_seconds=time.perf_counter()-start
    if direct!=cached:raise AssertionError("template serialization diverged")
    regions={}
    for region in range(region_count):
        regions[(region,0)]={(i%32,i//32):cached[region*1024+i] for i in range(1024)}
    with tempfile.TemporaryDirectory(prefix="oceancanvas-structural-bench-") as td:
        start=time.perf_counter()
        packed=pack_regions_parallel(regions,td,workers=workers)
        packing_seconds=time.perf_counter()-start
        records={str(k):receipt(Path(td)/f"r.{k[0]}.{k[1]}.mca") for k in packed}
        if not all(verify_receipt(Path(td)/f"r.{k[0]}.{k[1]}.mca",records[str(k)]) for k in packed):
            raise AssertionError("physical receipt mismatch")
        output_bytes=sum(x["bytes"] for x in packed.values())
    return {"scope":"structural_only_not_playable_chunks","regions":region_count,
            "chunks":region_count*1024,"workers":workers,
            "direct_nbt_seconds":round(direct_seconds,6),
            "cached_nbt_seconds":round(cached_seconds,6),
            "cache_hits":cache.hits,"cache_misses":cache.misses,
            "region_pack_seconds":round(packing_seconds,6),
            "output_bytes":output_bytes,
            "minecraft_verified":False}

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--regions",type=int,default=1)
    parser.add_argument("--workers",type=int,default=1)
    args=parser.parse_args()
    print(json.dumps(run(args.regions,args.workers),sort_keys=True))

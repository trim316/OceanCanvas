#!/usr/bin/env python3
"""Experimental optimization tournament with exact-output and round-trip checks.

Synthetic sections only. Benchmarks multiple independent and combined strategies;
none of these timings count toward physically verified Minecraft throughput.
"""
import argparse
import hashlib
import json
import statistics
import time
import zlib
from direct_section_nbt import section_root_nbt
from section_template_cache import SectionTemplateCache
from adaptive_section_encoder import AdaptiveSectionEncoder

WATER=("minecraft:water",)*4096
STONE=("minecraft:stone",)*4096
DIRT="minecraft:dirt"

def workload(mode,n):
    if mode=="uniform":
        return [(0,WATER if i%2 else STONE,"minecraft:ocean") for i in range(n)]
    if mode=="mixed":
        return [(0,tuple([STONE[0] if i%2 else DIRT]+list(WATER[1:])),"minecraft:ocean") for i in range(n)]
    if mode=="unique":
        out=[]
        for i in range(n):
            blocks=list(WATER)
            blocks[i%4096]=STONE[0]
            blocks[(i//4096+1)%4096]=DIRT
            out.append((0,tuple(blocks),"minecraft:ocean"))
        return out
    raise ValueError("unknown workload")

def serialize(items,strategy):
    cache=SectionTemplateCache()
    memo={}
    adaptive=AdaptiveSectionEncoder()
    outputs=[]
    for y,blocks,biome in items:
        if strategy=="adaptive":
            blob=adaptive.encode(y,blocks,biome)
        elif strategy=="direct":
            blob=section_root_nbt(y,blocks,biome)
        elif strategy=="cache":
            blob=cache.encode(y,blocks,biome)
        elif strategy=="identity":
            # Fast when an upstream immutable template guarantees object identity.
            key=(y,biome,id(blocks))
            entry=memo.get(key)
            if entry is None or entry[0] is not blocks:
                blob=section_root_nbt(y,blocks,biome)
                memo[key]=(blocks,blob)
            else:blob=entry[1]
        elif strategy=="uniform":
            # Safe constant palette path, with direct fallback for varied terrain.
            first=blocks[0]
            if all(x==first for x in blocks):
                key=(y,biome,first)
                if key not in memo:memo[key]=section_root_nbt(y,blocks,biome)
                blob=memo[key]
            else:blob=section_root_nbt(y,blocks,biome)
        else:raise ValueError("unknown strategy")
        outputs.append(blob)
    return outputs

def timed(fn,repeats):
    measurements=[]
    value=None
    for _ in range(repeats):
        start=time.perf_counter()
        value=fn()
        measurements.append(time.perf_counter()-start)
    return value,round(statistics.median(measurements),6)

def tournament(mode="uniform",n=256,repeats=3):
    if n<1 or repeats<1:raise ValueError("positive sample and repeat counts required")
    items=workload(mode,n)
    baseline,baseline_time=timed(lambda:serialize(items,"direct"),repeats)
    baseline_digest=hashlib.sha256(b"".join(baseline)).hexdigest()
    variants={}
    for strategy in ("direct","cache","identity","uniform","adaptive"):
        blobs,seconds=timed(lambda:serialize(items,strategy),repeats)
        if blobs!=baseline:raise AssertionError("serialized output changed: "+strategy)
        variants[strategy]={"seconds":seconds,"speedup":round(baseline_time/max(seconds,1e-9),3)}
    # Independently test compression levels, which preserve uncompressed NBT.
    compression={}
    for level in (1,3,6,9):
        compressed,seconds=timed(lambda:[zlib.compress(blob,level) for blob in baseline],repeats)
        if [zlib.decompress(x) for x in compressed]!=baseline:
            raise AssertionError("compression round-trip changed")
        compression[str(level)]={"seconds":seconds,"bytes":sum(map(len,compressed))}
    # Combined pipeline: choose fastest measured safe encoder + fastest compressor.
    # Re-measure full pipeline, rather than adding isolated timings.
    winner=min(variants,key=lambda k:variants[k]["seconds"])
    fastest_level=min(compression,key=lambda k:compression[k]["seconds"])
    def pipeline(strategy,level):
        return [zlib.compress(blob,level) for blob in serialize(items,strategy)]
    baseline_pack,base_total=timed(lambda:pipeline("direct",6),repeats)
    combined_pack,combined_total=timed(lambda:pipeline(winner,int(fastest_level)),repeats)
    if [zlib.decompress(x) for x in combined_pack]!=baseline:
        raise AssertionError("combined pipeline changed uncompressed NBT")
    return {"scope":"synthetic_sections_not_playable_chunks","minecraft_verified":False,
            "mode":mode,"samples":n,"repeats":repeats,"reference_sha256":baseline_digest,
            "serialization":variants,"compression":compression,
            "combined":{"strategy":winner,"zlib_level":int(fastest_level),
                        "baseline_seconds":base_total,"optimized_seconds":combined_total,
                        "speedup":round(base_total/max(combined_total,1e-9),3),
                        "compressed_bytes":sum(map(len,combined_pack)),
                        "baseline_bytes":sum(map(len,baseline_pack))}}

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--mode",choices=("uniform","mixed","unique"),default="uniform")
    p.add_argument("--samples",type=int,default=256)
    p.add_argument("--repeats",type=int,default=3)
    a=p.parse_args()
    print(json.dumps(tournament(a.mode,a.samples,a.repeats),sort_keys=True))

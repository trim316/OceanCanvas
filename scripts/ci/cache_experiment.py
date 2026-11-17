#!/usr/bin/env python3
"""Controlled offline cache experiment. Synthetic NBT only, not verified chunks."""
import argparse
import hashlib
import json
import time
from direct_section_nbt import section_root_nbt
from section_template_cache import SectionTemplateCache

class LegacyCache:
    def __init__(self):self.templates={};self.hits=0;self.misses=0
    def encode(self,y,blocks,biome="minecraft:ocean"):
        key=(y,biome,tuple(blocks))
        digest=hashlib.sha256(repr(key).encode("utf-8")).digest()
        bucket=self.templates.setdefault(digest,[])
        for previous,blob in bucket:
            if previous==key:
                self.hits+=1
                return blob
        blob=section_root_nbt(y,blocks,biome)
        bucket.append((key,blob));self.misses+=1
        return blob

def experiment(count=1024,mode="uniform"):
    if count<1:raise ValueError("count must be positive")
    if mode not in ("uniform","mixed","unique"):raise ValueError("invalid mode")
    water=["minecraft:water"]*4096
    stone=["minecraft:stone"]*4096
    def states(i):
        if mode=="uniform":return water if i%2==0 else stone
        a=water.copy()
        if mode=="mixed":a[0]="minecraft:stone" if i%2==0 else "minecraft:dirt"
        else:
            a[i%4096]="minecraft:stone"
            a[(i//4096)%4096]="minecraft:dirt"
        return a
    samples=[states(i) for i in range(count)]
    results={}
    outputs={}
    for name,encoder in (("direct",lambda i,s:section_root_nbt(0,s)),
                         ("legacy",lambda i,s:legacy.encode(0,s)),
                         ("optimized",lambda i,s:optimized.encode(0,s))):
        legacy=LegacyCache() if name=="legacy" else None
        optimized=SectionTemplateCache() if name=="optimized" else None
        start=time.perf_counter()
        outputs[name]=[encoder(i,s) for i,s in enumerate(samples)]
        results[name+"_seconds"]=round(time.perf_counter()-start,6)
        if name!="direct":
            cache=legacy if name=="legacy" else optimized
            results[name+"_hits"]=cache.hits
            results[name+"_misses"]=cache.misses
    if outputs["direct"]!=outputs["legacy"] or outputs["direct"]!=outputs["optimized"]:
        raise AssertionError("byte mismatch between cache implementations")
    results.update({"count":count,"mode":mode,"minecraft_verified":False})
    return results

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--count",type=int,default=1024)
    p.add_argument("--mode",choices=("uniform","mixed","unique"),default="uniform")
    a=p.parse_args()
    print(json.dumps(experiment(a.count,a.mode),sort_keys=True))

#!/usr/bin/env python3
"""Immutable NBT section template cache with constant-section fast path.

Never assumes a hash match proves equality. Constant sections avoid building a
4096-entry tuple or SHA256 digest; mixed sections use exact-key comparisons.
Experimental structural optimization, not a playable chunk generator.
"""
from direct_section_nbt import section_root_nbt

class SectionTemplateCache:
    def __init__(self):
        self.templates={}
        self.hits=0
        self.misses=0
    def encode(self,section_y,blocks,biome="minecraft:ocean"):
        if len(blocks)!=4096:raise ValueError("section must have 4096 blocks")
        first=blocks[0]
        uniform=all(state==first for state in blocks)
        if uniform:
            key=("uniform",section_y,biome,first)
        else:
            key=("mixed",section_y,biome,tuple(blocks))
        blob=self.templates.get(key)
        if blob is not None:
            self.hits+=1
            return blob
        blob=section_root_nbt(section_y,blocks,biome)
        self.templates[key]=blob
        self.misses+=1
        return blob

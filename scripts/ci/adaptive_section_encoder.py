#!/usr/bin/env python3
"""Adaptive immutable section serializer: identity, uniform, repetition, direct.

No hash-only equality, no persistent cross-world cache, bounded content cache.
The inputs are experimental section-root NBT, NOT playable Minecraft chunks.
"""
from collections import OrderedDict
from direct_section_nbt import section_root_nbt

class AdaptiveSectionEncoder:
    def __init__(self,max_templates=128,probe=16):
        if max_templates<1 or probe<1:raise ValueError("positive bounds required")
        self.max_templates=max_templates
        self.probe=probe
        self.templates=OrderedDict()
        self.identity={}
        self.observed=set()
        self.samples=0
        self.repeats=0
        self.stats={"identity":0,"uniform":0,"content":0,"direct":0,"evictions":0}
    def encode(self,y,blocks,biome="minecraft:ocean"):
        if len(blocks)!=4096:raise ValueError("section must have 4096 blocks")
        identity=(y,biome,id(blocks))
        old=self.identity.get(identity)
        if old is not None and old[0] is blocks:
            self.stats["identity"]+=1
            return old[1]
        first=blocks[0]
        uniform=all(x==first for x in blocks)
        key=("uniform",y,biome,first) if uniform else None
        # Content lookup only when the probe observes repetition. A miss in
        # unique terrain should not allocate a large tuple merely for caching.
        if not uniform:
            signature=(y,biome,blocks[0],blocks[1],blocks[2048],blocks[-1])
            if self.samples<self.probe:
                if signature in self.observed:self.repeats+=1
                self.observed.add(signature)
                self.samples+=1
            if self.samples>=self.probe and self.repeats:
                key=("mixed",y,biome,tuple(blocks))
        if key is not None and key in self.templates:
            blob=self.templates[key]
            self.templates.move_to_end(key)
            self.stats["uniform" if uniform else "content"]+=1
        else:
            blob=section_root_nbt(y,blocks,biome)
            self.stats["direct"]+=1
            if key is not None:
                self.templates[key]=blob
                if len(self.templates)>self.max_templates:
                    self.templates.popitem(last=False)
                    self.stats["evictions"]+=1
        # Keep only last identity to avoid retaining all source sections.
        self.identity={identity:(blocks,blob)}
        return blob

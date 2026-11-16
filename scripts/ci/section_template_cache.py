#!/usr/bin/env python3
"""Content-addressed immutable section template cache, experimental.

Identical section content is encoded once, reused as immutable bytes.
Coordinates are intentionally excluded; only section Y, biome and states
are part of the cache key. This does not deduplicate saved Minecraft regions.
"""
import hashlib
from direct_section_nbt import section_root_nbt

class SectionTemplateCache:
    def __init__(self):
        self.templates={}
        self.hits=0
        self.misses=0
    def encode(self,section_y,blocks,biome="minecraft:ocean"):
        # Hash the full ordered state stream; no correctness dependence on
        # the digest because equality is checked against the full key.
        key=(section_y,biome,tuple(blocks))
        digest=hashlib.sha256(repr(key).encode("utf-8")).digest()
        bucket=self.templates.setdefault(digest,[])
        for previous,blob in bucket:
            if previous==key:
                self.hits+=1
                return blob
        blob=section_root_nbt(section_y,blocks,biome)
        bucket.append((key,blob))
        self.misses+=1
        return blob

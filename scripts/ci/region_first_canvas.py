#!/usr/bin/env python3
"""Fail-closed region-first canvas planner for a replacement Minecraft generator.

No world writes. A generator backend must produce complete Minecraft chunks.
"""
from dataclasses import dataclass
from collections import defaultdict

@dataclass(frozen=True)
class Canvas:
    min_x:int
    min_z:int
    width:int
    depth:int
    def __post_init__(self):
        if self.width<=0 or self.depth<=0 or self.width%16 or self.depth%16:
            raise ValueError("canvas must be positive and chunk aligned")
        if self.min_x%16 or self.min_z%16:
            raise ValueError("canvas origin must be chunk aligned")
    def chunks(self):
        for z in range(self.min_z//16,(self.min_z+self.depth)//16):
            for x in range(self.min_x//16,(self.min_x+self.width)//16):
                yield x,z

def region_for_chunk(x,z):
    return (x//32,z//32),(x%32,z%32)

def plan(canvas,protected=frozenset(),existing=frozenset()):
    """Reject conflicting protected or preexisting chunks, never silently skip."""
    selected=set(canvas.chunks())
    protected_overlap=selected.intersection(protected)
    existing_overlap=selected.intersection(existing)
    if protected_overlap or existing_overlap:
        raise ValueError(f"unsafe selection: protected={len(protected_overlap)}, existing={len(existing_overlap)}")
    regions=defaultdict(list)
    for x,z in sorted(selected,key=lambda pos:(pos[1],pos[0])):
        region,local=region_for_chunk(x,z)
        regions[region].append((local,(x,z)))
    if sum(map(len,regions.values()))!=len(selected):
        raise AssertionError("chunk accounting mismatch")
    return dict(sorted(regions.items()))

def expected_chunks(canvas):
    return canvas.width*canvas.depth//256

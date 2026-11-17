#!/usr/bin/env python3
"""Strict offline build orchestration for complete Minecraft-produced chunk NBT.

Only for a disposable, CLOSED world. The provider supplies full playable chunk
NBT with correct coordinates, heightmaps, structures, light and version.
This does NOT implement that provider; a structural fixture cannot establish it.
"""
from pathlib import Path
from offline_region_packer import pack_region
from region_first_canvas import plan

def build_regions(canvas,provider,destination,protected=frozenset(),existing=frozenset()):
    destination=Path(destination)
    if destination.exists() and any(destination.iterdir()):
        raise FileExistsError("destination must be empty")
    groups=plan(canvas,protected,existing)
    manifest={}
    for region,entries in groups.items():
        chunks={}
        for local,world in entries:
            payload=provider(*world)
            if not isinstance(payload,bytes):
                raise TypeError("provider must return complete NBT bytes")
            chunks[local]=payload
        path=destination/f"r.{region[0]}.{region[1]}.mca"
        manifest[region]=pack_region(chunks,path)
    return manifest

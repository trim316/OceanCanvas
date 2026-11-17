#!/usr/bin/env python3
"""Fail-closed schema preflight for externally supplied Minecraft chunk NBT.

This is NOT Minecraft gameplay validation. It prevents section-only NBT fixtures
from entering the replacement builder, and checks chunk identity before packing.
"""
from nbt_validator import Reader,NBTError,validate_nbt

REQUIRED={"DataVersion":3,"xPos":3,"zPos":3,"Status":8,"sections":9,
          "Heightmaps":10,"structures":10,"block_entities":9,
          "block_ticks":9,"fluid_ticks":9}

def top_level(nbt):
    validate_nbt(nbt)
    reader=Reader(nbt)
    if reader.number(">B")!=10:raise NBTError("root not compound")
    reader.string()
    result={}
    while True:
        tag=reader.number(">B")
        if tag==0:break
        name=reader.string()
        if name in result:raise NBTError("duplicate root field "+name)
        start=reader.pos
        reader.payload(tag)
        result[name]=(tag,start,reader.pos)
    return result

def validate_chunk_preflight(nbt,x,z):
    fields=top_level(nbt)
    for name,tag in REQUIRED.items():
        if name not in fields or fields[name][0]!=tag:
            raise NBTError("missing or wrong-type chunk field "+name)
    for name,expected in (("xPos",x),("zPos",z)):
        _,start,end=fields[name]
        actual=int.from_bytes(nbt[start:end],"big",signed=True)
        if actual!=expected:raise NBTError(f"{name} mismatch: {actual} != {expected}")
    _,start,end=fields["DataVersion"]
    if int.from_bytes(nbt[start:end],"big",signed=True)<=0:
        raise NBTError("invalid DataVersion")
    _,start,end=fields["Status"]
    size=int.from_bytes(nbt[start:start+2],"big")
    if nbt[start+2:start+2+size] not in (b"minecraft:full",b"full"):
        raise NBTError("chunk is not FULL")
    return True

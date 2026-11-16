#!/usr/bin/env python3
"""Offline Anvil region packer for already-validated, Minecraft-produced chunk NBT.

Not an NBT generator. Inputs must be trusted complete serialized chunk NBT bytes.
Never write to an open Minecraft world. Destination must not exist.
"""
import hashlib
import os
from pathlib import Path
import struct
import tempfile
import zlib
from nbt_validator import validate_nbt
from concurrent.futures import ThreadPoolExecutor

SECTOR=4096
def _pad(blob):
    return blob + bytes((-len(blob)) % SECTOR)

def pack_region(chunks, output):
    """Map local (x,z) -> complete uncompressed NBT bytes, 0<=x,z<32."""
    output=Path(output)
    if output.exists():
        raise FileExistsError("refusing to replace existing region")
    if not chunks:
        raise ValueError("empty region")
    header=bytearray(8192)
    body=bytearray()
    seen=set()
    for (x,z),nbt in sorted(chunks.items(),key=lambda item:item[0][1]*32+item[0][0]):
        if not (isinstance(x,int) and isinstance(z,int) and 0<=x<32 and 0<=z<32):
            raise ValueError("invalid local chunk coordinates")
        slot=z*32+x
        if slot in seen: raise ValueError("duplicate slot")
        seen.add(slot)
        validate_nbt(nbt)
        compressed=zlib.compress(nbt,6)
        record=struct.pack(">I",len(compressed)+1)+bytes([2])+compressed
        sectors=(len(record)+SECTOR-1)//SECTOR
        offset=2+len(body)//SECTOR
        if sectors>255 or offset>0xffffff:
            raise ValueError("chunk exceeds Anvil sector limits")
        struct.pack_into(">I",header,slot*4,(offset<<8)|sectors)
        body.extend(_pad(record))
    payload=bytes(header)+bytes(body)
    output.parent.mkdir(parents=True,exist_ok=True)
    # fsync staging file, then atomic publish. Caller must ensure Minecraft closed.
    fd,name=tempfile.mkstemp(prefix=".oceancanvas-",dir=output.parent)
    try:
        with os.fdopen(fd,"wb") as f:
            f.write(payload);f.flush();os.fsync(f.fileno())
        if output.exists(): raise FileExistsError(output)
        # hard-link publishes without replacing an existing region, even on race.
        os.link(name,output)
        dirfd=os.open(output.parent,os.O_RDONLY)
        try: os.fsync(dirfd)
        finally: os.close(dirfd)
    finally:
        os.unlink(name)
    return {"chunks":len(chunks),"sha256":hashlib.sha256(payload).hexdigest(),"bytes":len(payload)}

def pack_regions_parallel(regions, destination, workers=4):
    """No partial acceptance: caller must verify every output before using any."""
    destination=Path(destination)
    if destination.exists() and any(destination.iterdir()):
        raise FileExistsError("destination must be empty")
    def one(item):
        (rx,rz),chunks=item
        return (rx,rz),pack_region(chunks,destination/f"r.{rx}.{rz}.mca")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return dict(pool.map(one,sorted(regions.items())))

#!/usr/bin/env python3
"""Minecraft paletted-container long-array encoder (experimental, not a chunk serializer).

For block states the local palette starts at 4 bits per entry; 4096 entries per section.
Values never straddle 64-bit long boundaries (Minecraft padded packing).
"""
import math
import struct

def encode_local_palette(values, *, min_bits=4):
    if not values: raise ValueError("empty container")
    palette=list(dict.fromkeys(values))
    bits=max(min_bits,(len(palette)-1).bit_length())
    if bits>8: raise ValueError("palette too large for local block-state encoding")
    mapping={name:i for i,name in enumerate(palette)}
    per_long=64//bits
    longs=[0]*math.ceil(len(values)/per_long)
    for index,name in enumerate(values):
        longs[index//per_long] |= mapping[name] << ((index%per_long)*bits)
    return palette,bits,longs

def decode_local_palette(palette,bits,longs,count):
    if not 1<=bits<=32 or not palette: raise ValueError("invalid palette")
    per_long=64//bits
    if len(longs)!=math.ceil(count/per_long): raise ValueError("invalid packed array length")
    mask=(1<<bits)-1
    result=[]
    for index in range(count):
        v=(longs[index//per_long] >> ((index%per_long)*bits))&mask
        if v>=len(palette): raise ValueError("invalid palette index")
        result.append(palette[v])
    return result

def signed_long_bytes(longs):
    return b"".join(struct.pack(">Q",value) for value in longs)

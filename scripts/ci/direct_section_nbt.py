#!/usr/bin/env python3
"""Experimental direct NBT chunk-section encoder. NOT a playable chunk generator.

Emits real big-endian NBT compound/list/long-array tags and canonical section
palettes; world-level status, version, heightmaps, light and structures are
intentionally absent. Never publish as a playable world.
"""
import struct
from palette_encoder import encode_local_palette,signed_long_bytes
from nbt_validator import validate_nbt

def string(value):
    data=value.encode("utf-8")
    if len(data)>65535:raise ValueError("NBT string too long")
    return struct.pack(">H",len(data))+data

def named(tag,name,payload):
    return bytes([tag])+string(name)+payload

def compound(entries):
    return b"".join(entries)+b"\x00"

def nbt_list(tag,values):
    return bytes([tag])+struct.pack(">i",len(values))+b"".join(values)

def nbt_long_array(values):
    return struct.pack(">i",len(values))+signed_long_bytes(values)

def section_nbt(section_y,blocks,biome="minecraft:ocean"):
    if len(blocks)!=4096:raise ValueError("section must have 4096 blocks")
    palette,bits,longs=encode_local_palette(blocks)
    # Minecraft's single-value paletted containers omit the data array.
    block_entries=[named(9,"palette",nbt_list(10,[
        compound([named(8,"Name",string(state))]) for state in palette]))]
    if len(palette)>1:
        block_entries.append(named(12,"data",nbt_long_array(longs)))
    biome_entries=[named(9,"palette",nbt_list(8,[string(biome)]))]
    entries=[
        named(1,"Y",struct.pack(">b",section_y)),
        named(10,"block_states",compound(block_entries)),
        named(10,"biomes",compound(biome_entries)),
    ]
    return compound(entries)

def section_root_nbt(section_y,blocks,biome="minecraft:ocean"):
    result=named(10,"",compound([
        named(9,"sections",nbt_list(10,[section_nbt(section_y,blocks,biome)]))
    ]))
    validate_nbt(result)
    return result

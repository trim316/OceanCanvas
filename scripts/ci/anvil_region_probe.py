#!/usr/bin/env python3
"""Read-only Anvil .mca structural inspector for DISPOSABLE, CLOSED world copies.

Does not interpret Minecraft NBT, mutate region files, or certify world correctness.
"""
import argparse
import json
import pathlib
import struct
import zlib
import gzip

SECTOR = 4096
HEADER = 8192
MAX_DECOMPRESSED = 16 * 1024 * 1024

class RegionError(ValueError):
    pass

def inspect_region(path: pathlib.Path, *, decompress=False):
    if path.suffix != ".mca":
        raise RegionError("expected .mca region file")
    data = path.read_bytes()
    if len(data) < HEADER or len(data) % SECTOR:
        raise RegionError("invalid Anvil file size or sector alignment")
    sectors = len(data) // SECTOR
    used = {0, 1}
    chunks = []
    for slot in range(1024):
        raw = int.from_bytes(data[slot*4:slot*4+4], "big")
        offset, count = raw >> 8, raw & 255
        timestamp = struct.unpack_from(">I", data, 4096 + slot*4)[0]
        if not offset and not count:
            continue
        if offset < 2 or count == 0 or offset + count > sectors:
            raise RegionError(f"slot {slot}: invalid sector range {offset}+{count}/{sectors}")
        overlap = used.intersection(range(offset, offset+count))
        if overlap:
            raise RegionError(f"slot {slot}: overlapping sectors {sorted(overlap)[:4]}")
        used.update(range(offset, offset+count))
        start = offset * SECTOR
        length = struct.unpack_from(">I", data, start)[0]
        if length < 1 or length + 4 > count * SECTOR:
            raise RegionError(f"slot {slot}: invalid chunk length {length}")
        compression = data[start+4]
        external = bool(compression & 0x80)
        codec = compression & 0x7f
        if codec not in (1, 2, 3):
            raise RegionError(f"slot {slot}: unsupported compression {codec}")
        if external and length != 1:
            raise RegionError(f"slot {slot}: external chunk has unexpected length")
        if decompress and not external:
            payload = data[start+5:start+4+length]
            try:
                if codec == 1:
                    decoded = gzip.decompress(payload)
                elif codec == 2:
                    obj = zlib.decompressobj()
                    decoded = obj.decompress(payload, MAX_DECOMPRESSED + 1)
                    if obj.unconsumed_tail or not obj.eof or obj.unused_data:
                        raise RegionError(f"slot {slot}: truncated or trailing compressed stream")
                else:
                    decoded = payload
                if len(decoded) > MAX_DECOMPRESSED:
                    raise RegionError(f"slot {slot}: decompression exceeds safe bound")
            except (zlib.error, OSError) as e:
                raise RegionError(f"slot {slot}: corrupt compressed payload: {e}") from e
        chunks.append({"slot": slot, "local_x": slot & 31,
                       "local_z": slot >> 5, "sector_offset": offset,
                       "sector_count": count, "compression": codec,
                       "external": external, "timestamp": timestamp})
    return {"path": str(path), "sectors": sectors, "chunks": chunks,
            "chunk_count": len(chunks), "occupied_sectors": len(used)}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("region", type=pathlib.Path)
    p.add_argument("--decompress", action="store_true",
                   help="check supported inline compressed payload integrity")
    args = p.parse_args()
    print(json.dumps(inspect_region(args.region, decompress=args.decompress), indent=2))

if __name__ == "__main__":
    main()

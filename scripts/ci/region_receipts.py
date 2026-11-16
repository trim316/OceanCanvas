#!/usr/bin/env python3
"""Offline immutable region receipt. Does not replace post-restart Minecraft verification."""
import hashlib
import json
from pathlib import Path

def receipt(path, *, minecraft_verified=False):
    if minecraft_verified:
        raise ValueError("offline hash cannot assert Minecraft verification")
    data=Path(path).read_bytes()
    return {"filename":Path(path).name,"bytes":len(data),
            "sha256":hashlib.sha256(data).hexdigest(),
            "minecraft_verified":False}

def verify_receipt(path,record):
    if record.get("minecraft_verified") is not False:
        raise ValueError("untrusted verification claim")
    actual=receipt(path)
    return actual==record

def serialize_receipts(records):
    return json.dumps(records,sort_keys=True,separators=(",",":")).encode("utf-8")

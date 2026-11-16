#!/usr/bin/env python3
"""Crash-safe, versioned expansion registry prototype. Not connected to Minecraft.

A registry entry is planning evidence only: VERIFIED requires an independent
physical verifier and is never inferred from successful generation or saving.
"""
import hashlib
import json
import os
import pathlib
import tempfile
from expansion_region_planner import Bounds, plan_expansion

SCHEMA = 1

def atomic_write(path, payload):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as f:
        temp = pathlib.Path(f.name)
        f.write(payload)
        f.flush()
        os.fsync(f.fileno())
    try:
        os.replace(temp, path)
        fd = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(fd)
        finally: os.close(fd)
    finally:
        temp.unlink(missing_ok=True)

def digest(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def bound_array(b):
    return [b.west,b.north,b.east,b.south]

class ExpansionRegistry:
    def __init__(self, path):
        self.path = pathlib.Path(path)
    def create(self, initial, identity):
        if self.path.exists(): raise ValueError("registry already exists")
        if not identity or not isinstance(identity, dict):
            raise ValueError("pinned world identity required")
        data = {"schema": SCHEMA, "identity": identity, "bounds": bound_array(initial),
                "generation": 0, "chunks": {}, "pending": []}
        self._write(data)
    def _write(self, data):
        obj = dict(data)
        obj["checksum"] = digest(obj)
        atomic_write(self.path, json.dumps(obj, sort_keys=True).encode())
    def read(self):
        data = json.loads(self.path.read_text())
        checksum = data.pop("checksum")
        if data["schema"] != SCHEMA or digest(data) != checksum:
            raise ValueError("registry corrupt or incompatible")
        return data
    def propose(self, new_bounds, *, identity, already_generated=frozenset(),
                allow_conversion=False):
        data = self.read()
        if data["identity"] != identity: raise ValueError("world identity drift")
        if data["pending"]: raise ValueError("previous expansion not reconciled")
        before = Bounds(*data["bounds"])
        protected = {tuple(map(int,k.split(","))) for k,v in data["chunks"].items()
                     if v == "PROTECTED"}
        verified = {tuple(map(int,k.split(","))) for k,v in data["chunks"].items()
                    if v == "VERIFIED"}
        plan = plan_expansion(before, new_bounds, protected=protected, verified=verified,
                              already_generated=already_generated, allow_conversion=allow_conversion)
        data["bounds"] = bound_array(new_bounds)
        data["generation"] += 1
        data["pending"] = [f"{x},{z}" for x,z in sorted(plan.pending)]
        self._write(data)
        return plan
    def record(self, x, z, status, *, physical_verified=False):
        data = self.read()
        key = f"{x},{z}"
        if status not in ("PROTECTED", "VERIFIED", "QUARANTINED"):
            raise ValueError("invalid status")
        if status == "VERIFIED" and not physical_verified:
            raise ValueError("physical verification evidence required")
        if key in data["chunks"] and data["chunks"][key] == "PROTECTED" and status != "PROTECTED":
            raise ValueError("cannot downgrade protected chunk")
        if key not in data["pending"] and status != "PROTECTED":
            raise ValueError("chunk not pending")
        data["chunks"][key] = status
        if key in data["pending"]:
            data["pending"].remove(key)
        self._write(data)

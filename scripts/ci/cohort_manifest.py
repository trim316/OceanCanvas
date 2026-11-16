#!/usr/bin/env python3
"""Crash-safe cohort manifest prototype. NOT a Minecraft durability implementation.

The cohort barrier is only an *intent to verify*; never evidence of VERIFIED.
All receipts are fsynced and atomic, and replay is conservative.
"""
from __future__ import annotations
import json, os, pathlib, tempfile
from dataclasses import dataclass

STATES = ("PREPARED", "AUTHORED", "DURABLE", "VERIFIED", "QUARANTINED")

def atomic_json(path: pathlib.Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        tmp = pathlib.Path(stream.name)
        json.dump(obj, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

@dataclass
class Cohort:
    root: pathlib.Path
    cohort_id: str

    @property
    def manifest(self) -> pathlib.Path:
        return self.root / (self.cohort_id + ".json")

    def read(self) -> dict:
        return json.loads(self.manifest.read_text())

    def prepare(self, members: dict[str, dict]) -> None:
        if self.manifest.exists(): raise ValueError("cohort already exists")
        if not members: raise ValueError("empty cohort")
        if len(members) > 16: raise ValueError("cohort too large")
        for key, member in members.items():
            if member.get("protected"): raise ValueError("protected member " + key)
            if not member.get("preimage_fsynced") or not member.get("preimage_hash"):
                raise ValueError("missing durable preimage " + key)
            if not member.get("target_hash"): raise ValueError("missing target hash " + key)
        atomic_json(self.manifest, {"schema": 1, "state": "PREPARED",
                                   "members": members, "verified": []})

    def authored(self) -> None:
        m = self.read()
        if m["state"] != "PREPARED": raise ValueError("invalid authoring transition")
        m["state"] = "AUTHORED"
        atomic_json(self.manifest, m)

    def durable(self, save_flush_returned: bool) -> None:
        m = self.read()
        if m["state"] != "AUTHORED" or not save_flush_returned:
            raise ValueError("durability barrier not proven")
        m["state"] = "DURABLE"
        atomic_json(self.manifest, m)

    def verify_after_restart(self, restarted: bool, physical_verifier) -> bool:
        m = self.read()
        if not restarted or m["state"] != "DURABLE":
            raise ValueError("fresh-process durability verification required")
        # No VERIFIED receipt until *every* member passes independent physical checks.
        for key, member in m["members"].items():
            if not physical_verifier(key, member):
                m["state"] = "QUARANTINED"
                atomic_json(self.manifest, m)
                return False
        m["state"] = "VERIFIED"
        m["verified"] = sorted(m["members"])
        atomic_json(self.manifest, m)
        return True

    def replay_action(self) -> str:
        m = self.read()
        return {"PREPARED": "RESTORE_OR_REVERIFY",
                "AUTHORED": "RESTORE_OR_REVERIFY",
                "DURABLE": "RESTART_AND_VERIFY",
                "VERIFIED": "DONE",
                "QUARANTINED": "MANUAL_RECOVERY"}[m["state"]]

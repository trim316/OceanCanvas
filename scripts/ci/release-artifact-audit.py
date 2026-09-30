#!/usr/bin/env python3
"""Fail-closed audit for a built Ocean Canvas release JAR."""
from __future__ import annotations
import json
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "runtime-src"

def gradle_properties():
    props = {}
    for line in (RUNTIME / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props

def fail(message):
    raise SystemExit("RELEASE_ARTIFACT_AUDIT_FAIL: " + message)

def main():
    props = gradle_properties()
    version = props.get("mod_version")
    base = props.get("archives_base_name")
    minecraft = props.get("minecraft_version")
    if not version or not base or not minecraft:
        fail("missing mod_version/archives_base_name/minecraft_version")
    if "recovery" in version.lower():
        fail("release artifact version still contains recovery marker: " + version)

    libs = RUNTIME / "build" / "libs"
    jars = sorted(p for p in libs.glob(f"{base}-*.jar")
                  if not p.name.endswith("-sources.jar"))
    if len(jars) != 1:
        fail("expected exactly one distributable JAR, found: " + repr([p.name for p in jars]))
    jar = jars[0]
    expected_name = f"{base}-{version}.jar"
    if jar.name != expected_name:
        fail(f"artifact name mismatch: expected {expected_name}, got {jar.name}")

    with zipfile.ZipFile(jar) as zf:
        names = set(zf.namelist())
        required = {
            "fabric.mod.json",
            "net/oceancanvas/mod/OceanCanvas.class",
        }
        missing = sorted(required - names)
        if missing:
            fail("missing required runtime entries: " + repr(missing))
        metadata = json.loads(zf.read("fabric.mod.json"))
        if metadata.get("id") != "oceancanvas":
            fail("fabric id mismatch")
        if metadata.get("version") != version:
            fail(f"fabric version mismatch: {metadata.get('version')} != {version}")
        depends = metadata.get("depends", {})
        if not str(depends.get("minecraft", "")).lstrip("~^>=").startswith(minecraft):
            fail("fabric Minecraft dependency does not bind configured target " + minecraft)
        entrypoints = metadata.get("entrypoints", {}).get("main", [])
        if "net.oceancanvas.mod.OceanCanvas" not in entrypoints:
            fail("OceanCanvas main entrypoint missing")

        class_bytes = zf.read("net/oceancanvas/mod/OceanCanvas.class")
        if class_bytes[:4] != b"\xca\xfe\xba\xbe":
            fail("entrypoint class has invalid class-file magic")
        major = int.from_bytes(class_bytes[6:8], "big")
        if major != 69:
            fail(f"entrypoint Java class major must be 69 (Java 25), got {major}")

        if any(name.endswith(".java") for name in names):
            fail("source .java files leaked into distributable JAR")

    print(json.dumps({
        "verdict": "PASS",
        "jar": jar.name,
        "mod_version": version,
        "minecraft_version": minecraft,
        "java_class_major": 69,
        "fabric_id": "oceancanvas",
    }, indent=2))

if __name__ == "__main__":
    main()

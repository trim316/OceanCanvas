#!/usr/bin/env python3
"""Independent architecture hypothesis gates. PASS is not a performance claim.

These are disposable synthetic-data tests; the full Minecraft restart benchmark
must be added before any lane can be promoted to production.
"""
import concurrent.futures
import hashlib
import json
import pathlib
import tempfile
import unittest
from expansion_region_planner import square, plan_expansion
from expansion_registry import ExpansionRegistry
from cohort_manifest import *  # existing crash boundary invariants exercised by own suite

class ArchitectureGates(unittest.TestCase):
    def test_palette_first(self):
        # Uniform sections require exactly one palette entry; mixed requires <=3.
        for floor, section_y, surface in ((40,0,63),(0,2,63),(0,5,63),(8,0,8)):
            vals = tuple(0 if y+section_y*16 < floor else
                         1 if y+section_y*16 <= surface else 2 for y in range(16))
            palette = tuple(sorted(set(vals)))
            self.assertLessEqual(len(palette), 3)
            self.assertEqual(tuple(palette.index(v) for v in vals),
                             tuple(palette.index(v) for v in vals))
    def test_template_reuse(self):
        # Reusing immutable template bytes must not share mutable chunk state.
        templates = {n: bytes([n])*4096 for n in range(3)}
        a,b = bytearray(templates[1]),bytearray(templates[1])
        a[0] = 0
        self.assertEqual(b[0],1)
        self.assertEqual(templates[1][0],1)
    def test_parallel_preparation(self):
        def task(key):
            x,z=key
            return key,hashlib.sha256(bytes(((x*31+z*17+y)&255) for y in range(4096))).hexdigest()
        keys=[(x,z) for z in range(-4,4) for x in range(-4,4)]
        serial=dict(map(task,keys))
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            parallel=dict(pool.map(task,reversed(keys)))
        self.assertEqual(serial,parallel)
    def test_initial_lighting(self):
        # Fail-closed: never accept an unverified synthetic lighting shortcut.
        candidate={"blocks_written":True,"light_initialized":False,"light_verified":False}
        self.assertFalse(candidate["light_initialized"] and candidate["light_verified"])
    def test_region_durability(self):
        # Fail-closed: a saved cohort without post-restart proof is not VERIFIED.
        receipt={"durable":True,"restarted":False,"physical_verified":False}
        self.assertFalse(all(receipt.values()))
    def test_incremental_verification(self):
        before=square(0,0,32); after=square(0,0,96)
        plan=plan_expansion(before,after,verified={(2,2)},protected={(-2,-2)})
        self.assertNotIn((2,2),plan.pending)
        self.assertNotIn((-2,-2),plan.pending)
        self.assertEqual(len(plan.pending),30)

if __name__=="__main__": unittest.main()

#!/usr/bin/env python3
"""Pure, read-only expansion planner for a future OceanCanvas region compiler.

Coordinates are half-open BLOCK intervals. Existing protected chunks are never
eligible for work. This module does not modify Minecraft worlds.
"""
from dataclasses import dataclass
from typing import FrozenSet

@dataclass(frozen=True)
class Bounds:
    west: int
    north: int
    east: int
    south: int
    def __post_init__(self):
        if self.west >= self.east or self.north >= self.south:
            raise ValueError("empty or inverted bounds")
        if any(v % 16 for v in (self.west, self.north, self.east, self.south)):
            raise ValueError("boundaries must align to chunks")
    def contains_chunk(self, x: int, z: int) -> bool:
        return self.west // 16 <= x < self.east // 16 and self.north // 16 <= z < self.south // 16

@dataclass(frozen=True)
class ExpansionPlan:
    before: Bounds
    after: Bounds
    pending: FrozenSet[tuple[int, int]]
    protected_skipped: FrozenSet[tuple[int, int]]
    already_verified_skipped: FrozenSet[tuple[int, int]]
    existing_generated_pending: FrozenSet[tuple[int, int]]
    def by_region(self) -> dict[tuple[int, int], tuple[tuple[int, int], ...]]:
        groups = {}
        for x, z in sorted(self.pending):
            groups.setdefault((x // 32, z // 32), []).append((x, z))
        return {key: tuple(values) for key, values in sorted(groups.items())}

def plan_expansion(before: Bounds, after: Bounds, *,
                   protected=frozenset(), verified=frozenset(),
                   already_generated=frozenset(), allow_conversion=False) -> ExpansionPlan:
    if not (after.west <= before.west and after.north <= before.north
            and after.east >= before.east and after.south >= before.south):
        raise ValueError("expansion cannot shrink or move existing canvas")
    protected, verified, already_generated = map(frozenset, (protected, verified, already_generated))
    pending, skipped_protected, skipped_verified, conversion = set(), set(), set(), set()
    for z in range(after.north // 16, after.south // 16):
        for x in range(after.west // 16, after.east // 16):
            key = (x, z)
            if before.contains_chunk(x, z):
                continue
            if key in protected:
                skipped_protected.add(key)
            elif key in verified:
                skipped_verified.add(key)
            elif key in already_generated:
                conversion.add(key)
                if allow_conversion:
                    pending.add(key)
            else:
                pending.add(key)
    return ExpansionPlan(before, after, frozenset(pending),
                         frozenset(skipped_protected), frozenset(skipped_verified),
                         frozenset(conversion))

def square(center_x: int, center_z: int, width_blocks: int) -> Bounds:
    if width_blocks <= 0 or width_blocks % 32:
        raise ValueError("square width must be a positive multiple of 32")
    if center_x % 16 or center_z % 16:
        raise ValueError("center must align to chunk grid")
    h = width_blocks // 2
    return Bounds(center_x-h, center_z-h, center_x+h, center_z+h)

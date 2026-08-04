# Design decisions

## DD-0001: No custom save-critical registry entries

**Status:** Accepted

Ocean Canvas will not add blocks, items, entities, dimensions, or biomes required to open a save. This preserves removability.

## DD-0002: Settings are per world

**Status:** Accepted

Each world owns its center, radius, transition, and enabled state. Global settings provide defaults only.

## DD-0003: Existing chunks are never regenerated automatically

**Status:** Accepted

Changing the radius changes future generation only. Expansion requires a backup and explicit pruning by the world owner.

## DD-0004: Chunky is optional

**Status:** Accepted

Ocean Canvas may detect Chunky and produce matching commands, but it will not require or bundle Chunky.

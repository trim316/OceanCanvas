# Region-first redesign (experimental, not released)

## Fundamental change
Replace per-chunk live-world transactions with immutable 32x32 chunk region plans and copy-on-write transformations on CLOSED disposable Minecraft worlds. Preserve existing production runtime as control.

## A: Offline Anvil region transformation
1. Generate a pinned-version world and shut down Minecraft.
2. Refuse protected chunks, unsupported NBT/data versions, unsafe block entities or unknown structures before any writes.
3. Persist original region bytes and per-chunk immutable preimages; fsync PREPARED manifest.
4. Build canonical section palettes, fluid data and required heightmaps directly from deterministic coordinates and configuration. Preserve unrelated metadata and structure references. Invalidate light according to exact version semantics.
5. Write new region to sibling temp file, validate Anvil offsets and checksums, fsync, atomically rename and fsync directory.
6. Restart Minecraft for authoritative lighting and structure processing; save and stop.
7. Restart independently and physically verify EVERY chunk, including fluids, skylight, heightmaps, protected exclusions, block entities, and structure placement. Only then record VERIFIED.
8. Crash or mismatch means restore from preimage or quarantine; never guess.

## B: In-memory section assembly
Fallback if direct NBT rewriting cannot be proven safe: assemble complete chunk sections and integrate using version-pinned Minecraft APIs, then same relight/restart verification.

## C: Control
Keep existing single-chunk runtime untouched for identical-seed, identical-hardware comparison.

## Experimental gates
E0 instrument authoring, global save, lighting, reload and physical verification.
E1 test 4, 16, 64 chunks including negative coordinates, region edges, protected chunks and block entities.
E2 inject failures before/after fsync, rename, relight and verification; prove recovery.
E3 repeat 256 and 2304 chunk comparisons only after all correctness gates pass.
E4 require sustained 500 fully verified chunks/min INCLUDING backups, relight, restart and independent verification. No release claims from short benchmarks.

The Java cohort barrier remains an isolated alternative, not the final design. No personal worlds, extra spending, or weakened verification.

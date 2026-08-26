# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas work. Important requirements, architecture decisions, compatibility/migration rules, current implementation state, known risks, validation status, and next work belong here rather than only in chat history.

## Product identity and UI rule
Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. The player creates the world; Ocean Canvas measures, visualizes, organizes, analyzes, warns, compares and guides. The **map/control center is the canonical UI**.

## Canonical terrain operations
- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the original seed/vanilla generation. Confirmations must warn that player modifications are replaced.
- Prefer internal concepts `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`; keep legacy reset job IDs readable for migration.

## Foundational state model
Never conflate Terrain (`VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`), Generation/job (`UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`), and Project (`UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`).

## Safety / longevity
Metadata/planning-asset failures must not unnecessarily make terrain inaccessible. Recovery/read-only modes remain required. Destructive operations need impact previews. Protected/Archived areas cannot be silently changed. Restore must be genuine vanilla regeneration. Keep project snapshots separate from full world backups. Do not describe groundwork as production-complete.

# Implemented lineage through v47
- v34 platform/Health/Expansion/template/performance groundwork.
- v35 exact-mask map-region Pregen + numeric bounds.
- v36 unified map control center + authoritative config sync.
- v37 Rewipe terminology + experimental Restore-to-Vanilla path.
- v38 terrain-state tracking, planning persistence, Inspector foundation, Archive locks.
- v39 Project/Planning/Inspector sync and map controls.
- v40 first authoritative planning-vector editor.
- v41 workspace notebook/scenarios, planning schema 2, huge-image mip/tile store, terrain analysis substrate, first Blueprint renderer.
- v42 tiled reference images on map + cross-section analysis.
- v43 exact reference transforms + vertical planning/Blueprint beacons.
- v44 rotated reference pixels, 2/3-point registration, multi-point elevations, actual-vs-planned cross-sections.
- v45 map-assisted world-point registration, image-backed Blueprint mosaic, Sea/Slope/Contour analysis layers.
- v46 direct image-pixel picking from the rendered reference layer + persistent arbitrary Sea Preview Y adjustment.
- **v47 reference-asset recovery/replacement workflow plus exact in-world Rewipe/Restore safety previews.**

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 testing on copied/disposable worlds including structures, block entities, lighting, seams, save/reload, then Rewipe.

# v47 — Asset recovery + destructive-operation Preview In World

## Actually implemented

### Reference asset recovery
- Added **Asset Recovery…** from the Reference Transform panel.
- A local PNG/JPG path can be imported through the existing content-addressed tiled asset store.
- **Relink Exact** only succeeds as a metadata-neutral recovery when the chosen file hashes to the same asset id as the existing reference. This safely rebuilds missing local source/tile data without altering shared project metadata.
- **Replace Asset** is explicit and server-authoritative. It updates only the reference layer's asset id after the replacement image has been imported locally.
- Locked reference layers reject asset replacement.
- New server planning action: `reference_asset_replace`.
- Asset bytes remain non-critical/local and are never stored in SavedData or periodic sync.

### Preview In World for destructive operations
- Arming **Rewipe** now publishes an in-world client-only safety preview before the second confirmation click.
- Arming **Restore / Restore to Vanilla** does the same and retains the destructive warning.
- Irregular regions render the exact selected chunk mask; rectangular regions use their true bounds.
- Preview geometry is drawn through Blueprint Mode only and creates no blocks/entities/particles.
- Rewipe and Restore use visually distinct preview colors and the preview is cleared when the operation is confirmed.
- This is a safety visualization only; server-side archive/protection validation remains authoritative.

## v47 compatibility
- No SavedData schema bump.
- One additive planning edit action (`reference_asset_replace`).
- Operation preview is transient client-only state and never enters the world save.

## v47 limitations / next work
- Asset recovery currently uses a typed/pasted local path; a native file chooser is intentionally not required for portability but can be added if Fabric/Minecraft provides a stable safe picker.
- Rewipe/Restore preview currently requires leaving/closing the map to inspect it in the world; a dedicated **View In World / Return to Map** transition remains required.
- Cancel/timeout paths should clear armed-operation preview state more aggressively in the next polish pass.
- Add direct numeric Sea Y entry, elevation bands/legend/opacity, rich vector vertex editing/snapping, and deeper Canvas Health/Adaptive Pregen work.

# Accepted full scope — IMPLEMENT, not merely brainstorm
All previously accepted scope remains required: multiple reference images and revisions; registration/warping/missing-asset recovery; rich planning vectors, selection algebra, vertex editing, Bezier/smoothing, widths/snapping/hierarchy/linked endpoints/scenarios/focus; scale simulator and travel estimates; elevation profiles, cross-sections, contours/elevation bands/slope/sea/watershed/deviation; in-world Blueprint image/vector/volume guides and destructive-operation previews; map selections/Inspector/Explain This/region hierarchy/inheritance; geographic TODOs/journal/viewpoints/screenshots; deep Canvas Health/recovery; Adaptive Pregen v2/job queue/Overnight; history/snapshots/Atlas/Timeline/export; and asymmetric canvas expansion. Implement incrementally with safe migrations and honest completion labels.

# Immediate implementation order after v47
1. Add **View In World / Return to Map** for operation previews and aggressive preview cancel/timeout cleanup.
2. Registration magnifier/thumbnail precision and direct numeric Sea Preview Y, analysis opacity/legends/elevation bands.
3. Rich vector vertex move/insert/delete, snapping, Bezier/smooth curves and variable-width fills.
4. Deep Canvas Health/recovery + Adaptive Pregen v2/job queue/Overnight in parallel.
5. Watershed/drainage/deviation analysis, then region hierarchy/history/Atlas/Timeline.
6. Replace the mosaic Blueprint reference with continuous textured tiles only after the exact 26.2 world-render API is compiled and runtime-verified.

## Validation / handoff contract
Current v47 source has balanced Java structure and ZIP integrity passes. Classpath-less validation still cannot replace an authoritative Minecraft/Fabric 26.2 Gradle compile/runtime test in the proper toolchain environment.

Every build/handoff must update this file with actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.

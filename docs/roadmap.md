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

# Implemented lineage through v46
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
- **v46 direct image-pixel picking from the rendered reference layer + persistent arbitrary Sea Preview Y adjustment.**

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 testing on copied/disposable worlds including structures, block entities, lighting, seams, save/reload, then Rewipe.

# v46 — Direct pixel registration + adjustable sea preview

## Actually implemented
- Reference Registration now has separate **Pick P1/P2/P3 Image** actions alongside world-point picking.
- Image picking occurs directly against the selected rendered reference on the Ocean Canvas map. The clicked world location is inverse-transformed through the reference bounds/rotation into source-image pixel coordinates and returned to the registration draft.
- This removes the ordinary need to manually determine Photoshop/Gaea image pixel coordinates.
- Sea Preview no longer resets to dimension sea level during UI refresh. Design Mode exposes **Sea Y -1 / +1** controls for arbitrary non-destructive flood-level inspection while preserving the loaded-terrain-only rule.
- No SavedData schema change; these are client authoring/analysis improvements over existing planning schema 2.

## v46 limitations / risks
- Image picking currently assumes the reference's similarity-transform footprint. Controlled non-uniform/perspective warping remains later work.
- A dedicated zoomable image thumbnail/magnifier is still desirable for precision.
- Sea Preview still needs direct numeric Y entry, opacity/legend controls and elevation bands.
- The in-world reference remains a bounded sampled mosaic rather than a verified continuous textured world-space surface.
- Authoritative Minecraft/Fabric 26.2 Gradle compile/runtime validation remains required in the proper toolchain environment.

# Accepted full scope — IMPLEMENT, not merely brainstorm
All previously accepted scope remains required: multiple reference images and revisions; registration/warping/missing-asset recovery; rich planning vectors, selection algebra, vertex editing, Bezier/smoothing, widths/snapping/hierarchy/linked endpoints/scenarios/focus; scale simulator and travel estimates; elevation profiles, cross-sections, contours/elevation bands/slope/sea/watershed/deviation; in-world Blueprint image/vector/volume guides and destructive-operation previews; map selections/Inspector/Explain This/region hierarchy/inheritance; geographic TODOs/journal/viewpoints/screenshots; deep Canvas Health/recovery; Adaptive Pregen v2/job queue/Overnight; history/snapshots/Atlas/Timeline/export; and asymmetric canvas expansion. Implement incrementally with safe migrations and honest completion labels.

# Immediate implementation order after v46
1. Registration magnifier/thumbnail precision + missing-asset Locate/Replace flow.
2. Direct numeric Sea Preview Y, analysis opacity/legends and elevation bands.
3. Rich vector vertex move/insert/delete, snapping, Bezier/smooth curves and variable-width fills.
4. **Preview In World** for Rewipe/Restore/temporary selections.
5. Deep Canvas Health/recovery + Adaptive Pregen v2/job queue/Overnight in parallel.
6. Watershed/drainage/deviation analysis, then region hierarchy/history/Atlas/Timeline.
7. Replace the mosaic Blueprint reference with continuous textured tiles only after the exact 26.2 world-render API is compiled and runtime-verified.

## Validation / handoff contract
Current v46 source under classpath-less `javac -proc:none` shows no Java grammar/parser diagnostics in the changed tranche before expected unresolved Minecraft/Fabric dependencies. ZIP integrity passes. A real Minecraft/Fabric 26.2 Gradle compile and runtime test remains required.

Every build/handoff must update this file with actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.

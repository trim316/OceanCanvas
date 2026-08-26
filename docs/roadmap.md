# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas work. Important product requirements, architecture decisions, compatibility/migration rules, current implementation state, known risks, validation status, and next work belong here rather than only in chat history.

## Product identity and UI rule
Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. The player creates the world; Ocean Canvas measures, visualizes, organizes, analyzes, warns, compares and guides rather than automatically designing terrain. The **Ocean Canvas map/control center is the canonical UI**. Commands may remain power-user equivalents, but ordinary functionality should be available from the map. Mod Menu routes into the same control center. World/server-affecting changes are server-authoritative; purely visual client preferences may remain client-side.

## Canonical terrain operations
- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This replaces old Reset/Reclear/destructive-Unprotect wording.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the original seed and vanilla generator. Confirmations must explicitly warn that player modifications are replaced.
- Prefer internal concepts `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`; legacy `reset` / `region-reset` job IDs stay readable for migration.

## Foundational state model
Never conflate Terrain (`VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`), Generation/job (`UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`), and Project (`UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`). Pregen/Rewipe/Restore must not silently change project stage.

## Safety / longevity
Metadata or planning-asset failures must not unnecessarily make Minecraft terrain inaccessible. Recovery/read-only fallback remain required. Destructive operations need impact previews and explicit confirmation. Protected/Archived areas cannot be silently changed. Restore must be genuine vanilla regeneration, never relabeled Rewipe. Keep lightweight Ocean Canvas project snapshots separate from full world backups. Long-term compatibility across Minecraft/Ocean Canvas versions is first-class. Missing/corrupt planning images must never prevent world load. Do not describe groundwork as production-complete.

# Implemented lineage through v55
- **v34** platform/Health/Expansion/template/performance persistence groundwork.
- **v35** exact-mask map-region Pregen + numeric bounds.
- **v36** unified map control center + server-authoritative config sync; Mod Menu routes into it.
- **v37** canonical Rewipe terminology + experimental Restore-to-Vanilla regeneration path.
- **v38** explicit terrain-state tracking, planning persistence, Inspector foundation, Archive locks.
- **v39** Project/Planning/Inspector sync and map controls.
- **v40** first server-authoritative planning-vector editor.
- **v41** workspace notebook/scenarios, planning schema 2 styling/width/elevation/scenario/implemented fields, huge-image mip/tile store, terrain-analysis substrate, first Blueprint renderer.
- **v42** tiled reference images on map + cross-section analysis.
- **v43** exact reference transforms + vertical planning and Blueprint beacons.
- **v44** rotated reference pixels, 2/3-point registration, multi-point elevations, actual-vs-planned cross-sections.
- **v45** map-assisted registration, image-backed Blueprint mosaic, Sea/Slope/Contour analysis.
- **v46** direct image-pixel registration picking + arbitrary Sea Preview Y adjustment.
- **v47** reference asset recovery/replacement + exact in-world Rewipe/Restore preview geometry.
- **v48** View Preview In World round-trip, preview lifecycle cleanup, direct numeric Sea Y.
- **v49** in-world Return to Ocean Canvas key, Elevation Bands, analysis legends.
- **v50** precise planning vertex editor with insert/delete, numeric X/Z and 1/4/8/16-block grid snapping.
- **v51** direct draggable map vertex handles.
- **v52** smart snapping to nearby plan vertices and region edges/corners with visible target feedback.
- **v53** reversible Straight/Smooth paths using Catmull-Rom interpolation over the existing editable anchors; map and Blueprint share the same smoothing flag.
- **v54** first conservative Deep Canvas Health implementation, canonical Health tab, explicit-state contradiction scan, legacy-unverified classification, metadata-only safe repair.
- **v55** persisted Adaptive Pregen profiles (Quiet/Balanced/Overnight/Custom), learned benchmark display, profile-specific controller envelopes, and current authoritative test candidate.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 runtime testing on copied/disposable worlds including structures, block entities, lighting, seams, save/reload, then Rewipe of the same restored area.

# Current planning/design state

## Reference images / Blueprint
Implemented: multiple tiled PNG/JPG reference assets, non-critical content-addressed local asset store, LOD/mip pyramid, map rendering, opacity/visibility/lock/transform, rotation derivatives, exact bounds, 2/3-point registration, world-point and direct image-pixel picking, asset relink/replace recovery, image-backed in-world sampled Blueprint mosaic, vector Blueprint guides, vertical target beacons, surface/fixed/sea/floating projection, operation Preview In World, View In World/Return to Map.

Still required: registration magnifier/thumbnail, optional controlled multi-anchor warping, missing-asset polish, continuous textured in-world tile projection after exact 26.2 render API is compiled and verified, reference revisions/A-B comparison/clipping masks.

## Planning geometry
Implemented: continent/coastline/mountain/river/lake/biome/forest/desert/road/border/city/landmark/freeform/text types; direct tracing; selection/rename/visible/lock/delete; width; scenario and implemented state; multi-point elevation profiles; numeric vertex editor; insert/delete; grid snapping; direct draggable handles; smart snapping to nearby planning vertices/region edges/corners; reversible Straight/Smooth Catmull-Rom rendering in map and Blueprint.

Still required: dedicated Bezier handles/modes, brush/lasso/rectangle authoring, add/subtract/intersect selection algebra, linked endpoints, planning hierarchy/parent-child UI, variable-width visual fills, advisory constraints, Focus/Solo mode.

## Terrain analysis
Implemented: cross-sections, actual-vs-planned elevation overlay, approximate travel-scale estimates, arbitrary Sea Preview Y, Slope, Contours, Elevation Bands, compact legends, loaded-terrain-only safety.

Still required: configurable layer opacity/band thresholds/contour interval, watershed/drainage advisory analysis, elevation/coastline deviation heatmaps, 3D planning volumes and richer vertical construction guides.

# v54 — Conservative Deep Canvas Health

## Implemented
- `OceanCanvasDeepHealthService` performs a read-only metadata integrity scan without force-generating chunks.
- Explicit terrain states are cross-checked against the processed seal.
- `STATE_MISMATCH`: an explicit intended `CANVAS` or `VANILLA` state exists but the anti-reprocessing processed seal is absent.
- `LEGACY_UNVERIFIED`: chunk was processed by an older Ocean Canvas version before explicit terrain-state tracking; its state is deliberately **not guessed**.
- **Repair Safe Metadata** only restores missing processed seals when an explicit intended terrain state already exists. It never changes blocks and never assigns inferred state to legacy chunks.
- New server-authoritative `health_request` / `health_response` feed.
- New first-class **Health** tab in the canonical map with scan, counts/findings and conditional repair.
- Power-user equivalents: `/oceancanvas health deep` and `/oceancanvas health repair-metadata`.

## Not complete yet
This is the first deep integrity layer, not a complete physical world verifier. Actual block/biome/structure sampling, selection/region/canvas physical scans, health heatmap, diagnostic codes, self-test, recurring-failure trends and physical targeted repair remain required.

# v55 — Adaptive Pregen profiles + learned benchmark

## Saved-data migration
`project_data` advances **schema 2 → schema 3** solely to persist `pregenProfile`; older saves default safely to `BALANCED` and are upgraded to current schema when saved.

Profiles:
- **Quiet** — low rate/queue ceiling and stricter tick target; prioritizes gameplay.
- **Balanced** — default/current conservative behavior.
- **Overnight** — larger safe queue headroom and looser healthy tick target while retaining hard heap/tick/queue pause conditions.
- **Custom** — uses configured `pregenChunksPerTick` as the adaptive ceiling and derives proportional queue targets.

All profiles retain heap-pressure pause, queue backpressure, measured tick-cadence control, asymmetric fast backoff/slower ramp-up, completion-rate learning and final drain logic.

The Health tab exposes the profile cycle so the map remains the canonical editor. Project sync carries profile and learned benchmark. Successful jobs continue persisting observed chunks/sec, learned tick time and preferred outstanding depth; Health displays the learned benchmark when available.

# Accepted full scope — IMPLEMENT, not merely brainstorm
All previously accepted scope remains required: temporary selections and multi-selection; selection algebra; region folders/groups/hierarchy and inheritance/provenance; Archive locking; Current Project/checklists/dependencies/stats/bookmarks; operation history/snapshots/diffs/world changelog; Recovery/read-only compatibility; deep physical Canvas Health/targeted repair/heatmaps/diagnostics; Adaptive Pregen v2 calibration/job queue/saved workflows/Run Overnight/reporting; reference-image planning/warping/revisions; rich vector hierarchy/linked geometry; scale simulator/rulers/buffers; vertical planning/cross-sections/contours/slope/sea/watershed/deviation; in-world Blueprint image/vector/volume guides; geographic TODOs/journal/viewpoints/screenshots; Atlas/Timeline/export/`.oceanproject`; visual Expansion Planner and eventual asymmetric `minX/maxX/minZ/maxZ` canvas bounds. Implement incrementally with safe migrations and honest completion labels.

# CURRENT TEST GATE — v55

**Stop additional Mojang/Fabric API-dependent feature layering until this candidate is compiled and exercised in the real Minecraft 26.2 environment.** This is now the highest-leverage next step, not a request for design feedback.

A whole-source `javac -proc:none` pass initially found a real accumulated syntax defect from earlier preview work (`private private void viewArmedOperationInWorld()`); it was fixed. After the fix the complete source tree produces **zero parser/grammar diagnostics** under classpath-less javac. Remaining errors are unresolved Minecraft/Fabric/ModMenu dependencies, as expected without the target classpath.

The ChatGPT execution environment still has no `gradle-wrapper.jar`, no installed Gradle, Java 21, while this build requests Java 25. Therefore it cannot perform the authoritative target compile itself.

## Required v55 test matrix
Use a copied/disposable world first.

1. **Build/startup** — Gradle build against intended Minecraft 26.2/Fabric/Java toolchain; launch; open an existing Ocean Canvas world; confirm project schema 2→3 migration keeps regions, plans, notes and reference metadata.
2. **Planning regression** — select a vector; Straight/Smooth map rendering; Blueprint uses same curve; drag/snap anchors while Smooth; save/reload; smoothing persists.
3. **Health** — open Health tab; Run Deep Scan; old processed-only chunks show Legacy unverified rather than corruption; any provable mismatch enables safe metadata repair; repair changes no blocks; restart/rerun.
4. **Adaptive Pregen** — cycle Quiet/Balanced/Overnight/Custom; confirm persistence after restart. Run a bounded region Pregen; observe responsiveness, heap, outstanding queue and adaptive logs. Compare Quiet vs Overnight. Confirm clean drain/completion and learned benchmark appears in Health. Only then attempt the 20k×20k overnight target.
5. **Destructive-operation regression** — Rewipe preview → View In World → Return to Ocean Canvas → cancel/timeout; no stale preview. Restore only on disposable terrain and continue its existing dedicated structure/block-entity/lighting/seam/save/reload/Rewipe test matrix.
6. **General regression** — existing map settings, region Pregen, reference images/registration, Project tab, Rules, Archive locks and coordinate editing still function.

# Implementation order after v55 passes
1. Physical Deep Health sampling/heatmap and targeted chunk/selection/region scan/repair.
2. Adaptive Pregen v2 calibration + richer telemetry + persisted visible job queue / Overnight workflows.
3. Registration magnifier/thumbnail and missing-asset polish.
4. Planning hierarchy/linked endpoints + dedicated Bezier handles.
5. Watershed/deviation analysis, snapshots/history, Atlas and Timeline.
6. Asymmetric canvas bounds/visual Expansion Planner after compatibility design is finalized.

## Handoff contract
Every build/handoff must update this file with actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.
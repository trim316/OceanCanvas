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

# CURRENT HANDOFF — 2026-08-26

The user reports that the Minecraft/Fabric 26.2 compile bugs uncovered during the v55-v58 test gate have now been fixed in the **local working tree**. Earlier authoritative compile failures included private/protected Mojang API access (`GenerationChunkHolder#getOrCreateFuture`, `ChunkMap#createEmptyChunk`, `ChunkMap#getVisibleChunkIfPresent`), stale `ServerPlayer#serverLevel()` mappings, and then a large client-render/GUI compatibility tranche. Treat the user's current local source as newer than the packaged v55-v58 candidates and likely newer than GitHub code unless those fixes have been committed/pushed.

**First action in a new chat:** inspect the current repository/local branch state before editing. If the user's compile fixes are not in GitHub yet, get them committed/pushed or work from the current uploaded/local source rather than resurrecting old v58 code. Do not reintroduce the previously-fixed access/mapping bugs.

## Validation status
- User reports the compile bugs are fixed.
- Do **not** infer that runtime behavior has been validated unless a successful launch/playtest is explicitly confirmed.
- The next gate is therefore runtime/regression testing on a copied/disposable world, then continuation of implementation.

## Immediate runtime test matrix
1. Launch Minecraft 26.2/Fabric with the current local build and open an existing/copied Ocean Canvas world.
2. Verify project schema 2→3 migration preserves regions, notes, planning vectors, reference metadata and Pregen profile.
3. Planning regression: Straight/Smooth, vertex drag/snapping, reference image rendering/registration, cross-sections, analysis layers.
4. Health: Run Deep Scan; verify Legacy Unverified vs Contradictions; metadata repair must change no blocks.
5. Adaptive Pregen: bounded-region Quiet/Balanced/Overnight/Custom runs; observe TPS/MSPT, heap, queue depth, clean drain, learned benchmark persistence.
6. Rewipe: exact preview → View In World → Return to Ocean Canvas → cancel/timeout/confirm; no stale preview.
7. Restore: disposable terrain only; validate terrain, structures, block entities, lighting, seams, save/reload, then Rewipe the same area.
8. General regression: Settings/Mod Menu route, Region/Project/Rules/Plan/Health tabs, Archive locks, coordinate editing, image assets, job overlays.

# Remaining implementation work after runtime gate

## Priority 1 — Physical Deep Canvas Health
- Add actual terrain/block/biome/structure sampling beyond metadata consistency.
- Targeted scopes: chunk, temporary selection, saved region, whole canvas.
- Health heatmap and per-chunk classifications: healthy, ungenerated, generated-but-unprocessed, unexpected terrain, processing incomplete, pending retry, state mismatch, protected/archived, etc.
- Conservative targeted physical repair with explicit preview; never silently modify protected/archived/player-modified terrain.
- Diagnostic/support codes, self-test, crash/interrupted-job recovery dashboard, recurring-failure trends.

## Priority 2 — Adaptive Pregen v2 / Overnight workflow
- Automatic calibration/benchmark of sustainable throughput on the actual machine/world.
- Rich visible telemetry: MSPT, throughput, outstanding queue, heap/GC/save pressure, confidence-bounded ETA.
- Persisted job queue and workflows such as Rewipe → Pregen → Health Scan.
- **Run Overnight** mode with idle acceleration, safe pause/backoff, morning completion/error report.
- Keep the ~20k×20k overnight target as a performance goal, not a guaranteed rate.

## Priority 3 — Planning/reference polish
- Registration magnifier/thumbnail for precise control-point picking.
- Missing-asset Locate/Replace UX polish and optional reference revision/A-B comparison.
- Dedicated Bezier/smooth handles rather than only Catmull-Rom toggle.
- Brush/lasso/rectangle tools and add/subtract/intersect geometry operations.
- Linked endpoints, planning hierarchy/parent-child UI, variable-width fills, advisory constraints, Focus/Solo mode.
- Optional controlled multi-anchor image warping and clipping masks.

## Priority 4 — Blueprint/terrain design expansion
- Verify/rebuild the in-world renderer against the exact 26.2 rendering pipeline if the compile-fix tranche changed or temporarily disabled any Blueprint drawing path.
- Continuous textured tile projection with LOD rather than only sampled mosaic, once stable.
- Richer vertical guides and 3D blueprint volumes.
- Configurable analysis opacity/band thresholds/contour interval.
- Watershed/drainage advisory analysis.
- Planned-vs-actual elevation and coastline deviation heatmaps.
- Scale simulator, ghost ruler, distance buffers and reference stamps.

## Priority 5 — Project organization/history
- Region folders/groups/hierarchy and inherited rules with provenance/Explain This.
- Geographic TODO polish, completion checklists, dependencies/blockers, region statistics/bookmarks/thumbnails.
- Operation history, named metadata snapshots, automatic restore points, snapshot diffs and world changelog.
- Recovery Mode/read-only fallback and feature/data-format capability registry.
- Full Ocean Canvas project export/import independent of terrain.

## Priority 6 — Long-term visualization/export
- Natural-feature catalog and Implemented-plan promotion.
- Atlas view/styles and optional exploration-hidden labels.
- High-resolution/static planning-map export and optional read-only HTML/standalone map.
- World Timeline using periodic project/map snapshots.
- `.oceanproject` shareable package.

## Priority 7 — Expansion architecture
- Visual Expansion Planner with new/reused chunk counts, protected intersections, learned ETA and disk forecast.
- Migrate from centered square `canvasSize` to backward-compatible asymmetric `minX/maxX/minZ/maxZ` bounds.
- Multiple canvases/dimensions remain later than a rock-solid Overworld workflow.

## Handoff contract
Every build/handoff must update this file with actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.

# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas work. Important requirements, architecture decisions, compatibility/migration rules, current implementation state, known risks, validation status, and next work belong here rather than only in chat history.

## Product identity and UI rule

Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. It may measure, visualize, organize, analyze, warn, compare and guide, but must not become an automatic terrain designer or WorldEdit replacement: the player creates the world.

The **Ocean Canvas map/control center is the canonical UI**. Every normal feature and setting should be editable there. Commands may remain power-user equivalents. Mod Menu routes into the same control center. World/server-affecting changes are server-authoritative; purely visual preferences may remain client-side.

## Canonical terrain operations

- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This replaces old Reset/Reclear/destructive-Unprotect wording.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the world's original seed and vanilla generation. Confirmations/tooltips must explicitly say **Restore to Vanilla** and warn that player modifications in affected chunks are replaced.
- Internally prefer `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`. Persisted legacy `reset` / `region-reset` jobs remain readable for migration.

## Foundational state model

Never conflate:
- Terrain: `VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`.
- Generation/job: `UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`.
- Project: `UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`.

Pregen/Rewipe/Restore must not silently change project stage.

## Safety / longevity requirements

- Metadata problems must not unnecessarily make Minecraft terrain inaccessible. Recovery Mode/read-only fallback remain required.
- Destructive operations require impact previews and explicit confirmations.
- Protected/Archived areas must not be silently modified.
- Restore must use genuine vanilla regeneration, never relabeled Rewipe.
- Keep lightweight Ocean Canvas project snapshots separate from full world backups.
- Long-term Minecraft/Ocean Canvas compatibility is first-class.
- Missing/corrupt planning assets must never prevent world load.
- Do not claim groundwork is production-complete.

# Implemented lineage through v45

- **v34** platform groundwork: Health/Expansion Planner/templates/stages/milestone/performance persistence foundations.
- **v35** map region Pregen + numeric region bounds; exact irregular-region masks.
- **v36** unified map control center; Mod Menu routes into it; server-authoritative config sync.
- **v37** canonical Rewipe terminology + experimental Restore-to-Vanilla regeneration path.
- **v38** explicit terrain-state tracking, planning persistence, Inspector foundation, Archived stage/locks.
- **v39** Project/Planning/Inspector sync; map Inspector; Project tab; Archive/Unlock; planning layer feed.
- **v40** first server-authoritative planning editor: trace/select/rename/show/hide/lock/delete planning objects.
- **v41** Design Mode substrate: workspace/project notebook SavedData, geographic TODOs, scenarios, planning schema 2 style/width/elevation/scenario/implemented fields, huge-image tile-pyramid asset store, terrain-analysis service, first in-world Blueprint vector/TODO renderer.
- **v42** imported mip tiles render on map with opacity/LOD; reference selection/editing; two-click Cross-section returns loaded-terrain profile, distance and travel estimates.
- **v43** exact reference transform screen; nudge/scale/rotate controls; first plan elevation editor; Blueprint elevation interpolation and vertical target beacons.
- **v44** rendered rotated reference pixels, 2/3-point image registration, multi-point elevation profiles, actual-vs-planned cross-section comparison.
- **v45** map-assisted registration point picking, image-backed in-world Blueprint mosaic, and loaded-terrain Sea Preview/Slope/Contour analysis layers.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 runtime testing on copied/disposable worlds, including structures, block entities, lighting, seams, save/reload, then Rewipe of the restored area.

# v45 — Map-assisted registration + image-backed Blueprint + terrain analysis

## Actually implemented

### Map-assisted registration

- Reference Registration now includes **Pick P1/P2/P3 on Map**.
- The registration screen preserves its current draft, returns to the Ocean Canvas map, accepts the clicked Minecraft X/Z, then reopens with that world point populated.
- Image pixel X/Y stays explicit, so registration remains deterministic and compatible with the existing 2/3-point similarity-transform solver.
- Uses the existing schema-2 registration-point data and `reference_registration` server-authoritative edit action; no SavedData schema bump.

### Image-backed in-world Blueprint projection

- Blueprint Mode now projects imported reference imagery in-world as a **distance/LOD-aware sampled colored mosaic**.
- It samples the same local rotated mip/tile pyramid used by the map, so registration/rotation is consistent between map and world views.
- Projection respects reference visibility/opacity, the current Blueprint projection mode, render distance and the existing per-frame geometry budget.
- Added a small bounded CPU-side reference-tile sample cache for image-color lookup.
- Projection remains client-only and creates no blocks, entities or persistent particles.

**Important limitation:** v45 image projection is a coarse sampled mosaic rendered with safe guide geometry, not yet a continuous textured world-space surface. This is intentional until the exact Minecraft/Fabric 26.2 textured-world rendering API can be authoritatively compiled and verified.

### Terrain-analysis map layers

Plan tab now cycles an **Analysis Layer**:

1. Off
2. Sea Preview
3. Slope
4. Contours

- **Sea Preview** shades known loaded terrain at/below the preview sea level; its first value follows the dimension's actual sea level.
- **Slope** estimates local grade from neighboring loaded surface samples and shows gentle/moderate/steep areas.
- **Contours** derives adaptive contour crossings; spacing changes with zoom (roughly 10/20/40 vertical blocks).
- These layers reuse the existing client terrain sampler and never request or generate unloaded chunks. Unknown terrain remains unknown.
- Added read-only `surfaceYAt` to the isolated map terrain service.

## v45 compatibility

- No SavedData schema bump.
- No new persistent server format for analysis layers; they are client-side views over loaded terrain.
- Registration uses the existing schema-2 registration-point representation.
- Reference image bytes remain outside critical SavedData.

## v45 known limitations / risks

- Image-pixel coordinates in registration are still typed manually; a visual image-pixel picker/thumbnail is still needed.
- In-world image projection is mosaic-based, not continuous textured tiles yet.
- Sea Preview still needs direct arbitrary-Y editing and layer-opacity controls.
- Slope/contour layers only know client-loaded terrain and may appear sparse when zoomed beyond render distance; do not 'fix' this by forcing generation.
- Full slope legend, contour labels, elevation bands, watershed/drainage, and plan-vs-actual deviation heatmaps remain pending.
- Authoritative Minecraft/Fabric 26.2 Gradle compile/runtime validation remains required because the ChatGPT development environment still lacks the target dependency/toolchain setup.

# Accepted full scope — IMPLEMENT, not merely brainstorm

Everything below remains accepted product scope and should be implemented incrementally with safe migrations and honest completion labels.

## Reference images / Design Mode
- Multiple PNG/JPG layers from Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- Visibility, opacity, lock, order, translation, scale, rotation, crop, exact bounds, notes.
- 2/3-point registration (implemented), visual image-pixel picker, later optional controlled multi-anchor warping.
- Design revisions/A-B comparison, clipping masks.
- Mandatory huge-image tile/mip/LOD architecture.
- Missing-asset Locate/Replace/Remove, thumbnails, optional portable project-local asset copies.

## Rich planning geometry
- Continents/coastlines, mountains, rivers, lakes, forests/deserts/biome areas, roads, borders, cities, landmarks, freeform shapes/lines/text.
- Rectangle/polygon/brush/lasso/line/point tools and add/subtract/intersect selection algebra.
- Vertex move/insert/delete, robust segment/polygon hit testing, Bezier/smooth curves, variable-width fills.
- Snapping to block/chunk/grid/region/other vertices/cardinal angles.
- Planning hierarchy, linked endpoints, advisory constraints, variants/scenarios, Blueprint Solo Mode, Current Project Focus Mode.

## Scale / measurement
- Design-scale simulator; distance/area; walk/sprint/horse/boat/Elytra estimates.
- Scale-reference stamps, in-world ghost ruler, grid modes, distance buffers and guide rings.

## Vertical / 3D planning and terrain analysis
- Multi-point elevation profiles (implemented first practical version).
- Actual-vs-planned terrain cross-sections (implemented first version).
- Contours/slope/sea preview (implemented first map versions in v45), elevation bands and arbitrary sea-level controls.
- Watershed/drainage/likely-flow advisory analysis.
- Vertical target guides, 3D blueprint envelopes/volumes.
- Actual-minus-planned elevation deviation and coastline deviation.
- Project completion remains manually controlled.

## In-world Blueprint Mode
- Full continuous reference-image projection plus traced-object/outline modes with category filters.
- Fixed-Y, sea-level, floating and surface-following projection.
- Tiled/LOD/distance-aware rendering.
- Construction guides for mountains/rivers/cities/roads.
- Preview In World for temporary selections/Rewipe/Restore before confirmation.
- Optional AR compass/task/current-project markers and seamless View In World/Open Map context transition.

## Map / selection / regions / Inspector
- Temporary selections independent of saved regions; Pregen/Rewipe/Restore/Health/Protect/Create Region actions.
- Multi-region selection, coordinate editing/go-to, cursor block/chunk/region readout, scale guides/bookmarks/favorites, context actions, command palette/search.
- Inspector/Explain This with rule provenance, hierarchy, health and terrain state.
- Region groups/hierarchy and relationship-aware inheritance with explicit provenance.
- Templates, notes, Current Project, project stages, archive lock, checklists, dependencies, stats, thumbnails/bookmarks, metadata import/export.

## Notebook / TODOs / viewpoints
- Coordinate-anchored notes/TODOs, status/priority markers in map and Blueprint Mode.
- Next thing to work on / Take Me There.
- Screenshot attachments, before/after viewpoints, build journal, optional time-spent stats, progress screenshots, session resume note.

## Canvas Health / recovery
- Deep health classifications; chunk/selection/region/canvas scans; targeted repair with protection/archive safety.
- Health heatmap/trends/recurring failures, interrupted-job recovery dashboard, diagnostic codes, self-test, diagnostic bundle, Recovery/read-only modes.

## Adaptive Pregen v2 / jobs
- Rolling MSPT/completion latency/queue/memory/GC/save-pressure control and learned sustainable throughput.
- Quiet/Balanced/Overnight/Custom, calibration, idle/empty-server acceleration, ETA confidence, performance history/disk forecast.
- Robust drain/backpressure/self-healing, visible job queue, saved workflows, Run Overnight + completion report.

## History / Atlas / Timeline
- Operation history, named project snapshots, automatic restore points, snapshot diff, world changelog, project export/import, compatibility/migration view.
- Lightweight periodic map/project snapshots and World Timeline playback without block-by-block recording.
- World Atlas with technical/topographic/fantasy/Minecraft styles, discovery mode, static/high-resolution export, eventual HTML map and `.oceanproject` package.

## Expansion
- Visual Expansion Planner with new/reused chunks, learned ETA, disk estimate and protected intersections.
- Eventual migration from centered square `canvasSize` to explicit `minX/maxX/minZ/maxZ` for directional/asymmetric expansion while preserving old saves.

# Immediate implementation order after v45

1. Visual image-pixel picker/thumbnail + richer registration and missing-asset Locate/Replace flow.
2. True textured-tile in-world Blueprint projection after 26.2 rendering API verification.
3. Arbitrary sea-level control, elevation bands, slope legend, contour labels and analysis-layer opacity.
4. Rich vector vertex editing/snapping/Bezier/variable-width fills and intermediate elevation handles directly on the map.
5. Operation Preview In World for Rewipe/Restore/temporary selections.
6. Deep Canvas Health/recovery + Adaptive Pregen v2/job queue/Overnight in parallel.
7. Watershed/drainage/deviation analysis, then region hierarchy/history/Atlas/Timeline.

## Validation / handoff contract

Current v45 source under classpath-less `javac -proc:none` reaches expected missing Minecraft/Fabric/ModMenu dependencies and **zero Java grammar/parse errors** for this tranche. A real Minecraft/Fabric 26.2 Gradle compile and runtime test remains required in the proper target environment.

Every build/handoff must update this file with actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity, and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.
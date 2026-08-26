# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas work. Important product requirements, architecture decisions, compatibility/migration rules, current implementation state, known risks, validation status, and next work belong here rather than only in chat history.

## Product identity and UI rule

Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. It may measure, visualize, organize, analyze, warn, compare and guide, but must not become an automatic terrain designer or WorldEdit replacement: the player creates the world.

The **Ocean Canvas map/control center is the canonical UI**. Every normal feature and setting should be editable there. Commands may remain as power-user equivalents. Mod Menu routes to the same control center rather than owning a second settings surface. World/server-affecting changes are server-authoritative; purely visual preferences may remain client-side.

## Canonical terrain operations

- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This replaces old Reset/Reclear/destructive-Unprotect wording.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the world's original seed and vanilla generation. Confirmations/tooltips must explicitly say **Restore to Vanilla** and warn that player modifications in affected chunks are replaced.
- Internally prefer `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`. Persisted legacy `reset` / `region-reset` jobs remain readable for migration.

## Foundational state model

Never conflate these dimensions:

- Terrain: `VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`.
- Generation/job: `UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`.
- Project: `UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`.

Pregen/Rewipe/Restore must not silently change project stage.

## Safety / longevity requirements

- Ocean Canvas metadata problems must not unnecessarily make Minecraft terrain inaccessible. Add Recovery Mode/read-only fallback for incompatible or unreadable project data.
- Destructive operations need impact previews and explicit confirmations.
- Protected/Archived areas must not be silently modified.
- Restore must use genuine vanilla regeneration, never relabeled Rewipe.
- Keep lightweight Ocean Canvas project snapshots separate from full Minecraft-world backups.
- Long-term Minecraft/Ocean Canvas compatibility is first-class.
- Missing/corrupt planning assets must never prevent world load.
- Do not claim groundwork is production-complete.

# Implemented lineage through v44

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
- **v44** rendered rotated reference pixels, 2/3-point image registration, multi-point elevation profiles, and actual-vs-planned cross-section comparison.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 runtime testing on copied/disposable worlds, including structures, block entities, lighting, seams, save/reload, then Rewipe of the restored area.

# v44 — Registration + rotated tiles + richer vertical planning

## Actually implemented

### Rotated reference imagery

- Reference-image rotation now renders actual image pixels instead of falling back to a rectangle.
- To avoid depending on unstable GUI matrix APIs, the client creates a **disposable rotated derivative mip/tile pyramid** from the original content-addressed reference asset, then renders it through the same viewport/LOD cache as ordinary references.
- Rotated derivatives are local cache data only and never enter critical SavedData.
- Reference hit-testing inverse-rotates the clicked world point, so selecting a rotated layer uses its actual rotated footprint.

### 2/3-point reference registration

- Added **Reference Registration** from the Reference Transform panel.
- Registration pairs image-pixel `X/Y` with Minecraft `X/Z`.
- Two points solve translation + rotation + uniform scale.
- A third point uses a least-squares 2D similarity transform and reports RMS alignment error.
- The solved transform computes the reference's world center/bounds/rotation using the cached source-image dimensions.
- Up to three registration points are persisted in the existing planning-data model and synchronized to clients.
- New server-authoritative planning action: `reference_registration`.

### Multi-point vertical planning

- Elevation Profile editor now supports targets at 0%, 25%, 50%, 75%, and 100% along a plan; intermediate values are optional.
- Server action `elevation_profile` validates/sorts 2–8 control points.
- Existing Blueprint interpolation already supports arbitrary profile points, so richer profiles immediately affect in-world guide geometry and vertical beacons.

### Actual-vs-planned cross-section

- With a planning vector selected, **Cross-section** automatically analyzes its first-to-last span rather than requiring two clicks.
- If that vector has an elevation profile, the analysis card overlays **actual sampled terrain in cyan** and **planned elevation in gold** on one Y scale.
- With no plan selected, the existing two-click arbitrary cross-section workflow remains available.

## v44 compatibility

- `planning_data` remains schema 2; registration points and arbitrary elevation profiles were already represented, so no SavedData schema bump is required.
- Planning reference sync append-adds one optional trailing packed-registration field. Older packets remain readable and default to no registration points.
- Existing `reference_transform`, `elevation_linear`, and `elevation_clear` paths remain valid.
- Rotated derivative files are disposable local cache only.

## v44 limitations / risks

- Registration currently fits only uniform scale + rotation + translation. Perspective/non-uniform multi-anchor warping remains future work.
- Generating a never-before-used rotation reads the original full source image once, so extremely large references can cause a one-time CPU/RAM spike. The result is cached afterward.
- Reference image bytes remain client-local; multiplayer asset-transfer/authorization still needs a deliberate implementation.
- Authoritative Minecraft/Fabric 26.2 Gradle compile/runtime validation remains required because the development package still lacks a usable wrapper/toolchain environment in ChatGPT.

# Accepted Design / Planning scope — IMPLEMENT, not merely brainstorm

Everything below is accepted scope and should be implemented incrementally with safe migrations and honest completion labels.

## Reference images / Design Mode

- Multiple PNG/JPG layers from Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- Visibility, opacity, lock, order, translation, scale, rotation, crop, exact bounds, notes.
- 2/3-point registration (implemented v44), later optional controlled multi-anchor warping.
- Design revisions/A-B comparison, clipping masks.
- Mandatory huge-image tile/mip/LOD architecture; never one monolithic 20k×20k frame texture.
- Project-local content-addressed copies where useful, missing-asset Locate/Replace/Remove, thumbnails.

## Rich planning geometry

- Continents/coastlines, mountains, rivers, lakes, forests/deserts/biome areas, roads, borders, cities, landmarks, freeform shapes/lines/text.
- Rectangle/polygon/brush/lasso/line/point tools and add/subtract/intersect selection algebra.
- Vertex move/insert/delete and robust segment/polygon hit testing.
- Smooth/Bezier curves, variable widths, snapping to block/chunk/grid/region/other vertices/cardinal angles.
- Planning hierarchy, linked endpoints, advisory constraints, variants/scenarios, Blueprint Solo Mode, Current Project Focus Mode.

## Scale / measurement

- Design-scale simulator; distance/area; walk/sprint/horse/boat/Elytra estimates.
- Scale-reference stamps, in-world ghost ruler, grid modes, distance buffers and guide rings.

## Vertical / 3D planning and terrain analysis

- Multi-point elevation profiles (first practical version implemented v44).
- Actual-vs-planned terrain cross-sections (first version implemented v44).
- Contours, elevation bands, slope heatmap, arbitrary sea-level preview.
- Watershed/drainage/likely-flow advisory analysis.
- Vertical target guides, 3D blueprint envelopes/volumes.
- Actual-minus-planned elevation deviation and coastline deviation.
- Project completion remains manually controlled.

## In-world Blueprint Mode

- Full reference-image projection plus traced-object/outline modes with category filters.
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

# Immediate implementation order after v44

1. Map-assisted registration point capture + image thumbnail/pixel picker + missing-asset Locate/Replace.
2. Tiled reference-image projection into **in-world Blueprint Mode**.
3. Elevation editing tied directly to vector vertices + vertical deviation metrics.
4. Contour, slope, elevation-band and arbitrary sea-level map layers, then drainage/deviation analysis.
5. Deep Canvas Health/recovery and Adaptive Pregen v2/job queue/Overnight work in parallel.
6. Rich vector vertex/Bezier/snapping tools and complete notebook/viewpoint UI.
7. Region hierarchy/inheritance, snapshots/history, Atlas and Timeline.

## Validation / handoff contract

Current v44 changed files reach expected missing Minecraft/Fabric dependency errors under classpath-less `javac`; no new parser-level diagnostic was observed before dependency-resolution failures. A real Minecraft/Fabric 26.2 Gradle compile and runtime test remains required in the proper target environment.

Every build/handoff must update this file with: actually implemented vs planned, save/network/config migrations, known risks/limitations, exact build/test status, current build identity, and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.
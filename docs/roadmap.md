# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas changes. Important requirements, architecture decisions, save/network compatibility, current implementation status, validation status, known risks, and next work belong here rather than only in chat history.

## Product identity

Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. The player creates the geography and world. Ocean Canvas may measure, visualize, organize, analyze, warn, compare and guide, but it must not become an automatic terrain designer, WorldEdit replacement, or generator that makes the creative decisions for the player.

The **Ocean Canvas map/control center is the canonical UI**. Every normal mod feature and setting must be editable there. Commands may remain as power-user equivalents. Mod Menu routes into the same control center. Server/world-affecting state is server-authoritative; purely visual display preferences may be client-side.

## Canonical terrain operations

- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This replaces old Reset/Reclear/destructive Unprotect wording.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the world's original seed and vanilla generation. UI may say Restore; confirmations/tooltips should say **Restore to Vanilla** and explicitly warn that player modifications in affected chunks are replaced.
- Internally prefer `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`. Persisted old `reset` / `region-reset` job kinds must remain readable for migration.

## Foundational state model

Never conflate these dimensions:
- Terrain: `VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`.
- Generation/job: `UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`.
- Project: `UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`.

Pregen/Rewipe/Restore must not silently change project stage.

## Core safety / longevity requirements

- A metadata problem must never unnecessarily make Minecraft terrain inaccessible. Provide Recovery Mode and read-only fallback for incompatible/unreadable Ocean Canvas project data.
- Destructive operations need impact previews and explicit confirmations.
- Archived/protected areas must not be silently modified.
- Restore to Vanilla must use a genuine vanilla-regeneration path, never a relabeled Rewipe.
- Keep lightweight Ocean Canvas project snapshots separate from full Minecraft-world backups.
- Long-term compatibility across Minecraft/Ocean Canvas versions is first-class.
- Missing/corrupt planning images must never prevent the world from loading.
- Do not describe groundwork as production-complete.

# Implemented lineage through v42

- **v34**: health/planner/templates/stages/milestone/performance persistence foundations.
- **v35**: map region Pregen + numeric coordinate editing; exact irregular-region masks.
- **v36**: unified map control center; Mod Menu routes into it; server-authoritative config sync.
- **v37**: canonical Rewipe terminology + experimental Restore-to-Vanilla regeneration path.
- **v38**: explicit terrain-state tracking, planning persistence, Inspector foundation, Archived stage/locks.
- **v39**: server→client Project/Planning/Inspector sync; map Inspector; Project tab; Archive/Unlock; Planning layer feed.
- **v40**: first server-authoritative planning editor: trace/select/rename/show/hide/lock/delete planning objects on the map.
- **v41**: broad Design Mode tranche: Workspace SavedData (geographic TODOs, journal, viewpoints, Atlas features, scenarios, session notes); planning schema 2 with style/width/elevation/scenario/Implemented metadata; SHA-256 content-addressed PNG/JPG imports and 256px mip/tile pyramids; read-only terrain-analysis service; first client-only in-world Blueprint Mode with vector/task guides and sea-level/fixed/surface/floating projection.
- **v42**: actual tiled reference-image pixels render on the map from the mip pyramid with viewport culling, LOD choice and per-layer opacity; reference layers are selectable/editable in Plan (rename, show/hide, lock/unlock, delete, opacity); Design Cross-Section is a real two-click map tool with server-authored loaded-terrain elevation profile, distance and walk/horse/Elytra scale estimates.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 compile/runtime testing on disposable copied worlds, including structures, block entities, lighting, seams, save/reload, then Rewipe of the restored area.

# v42 implementation details / compatibility

## Reference tile renderer

- Uses the v41 content-addressed planning asset store and its 256×256 mip/LOD pyramid.
- Chooses an LOD from current screen scale rather than loading full source resolution.
- Requests/draws only tiles intersecting the current map viewport.
- Applies reference opacity by alpha-adjusting runtime tile textures.
- Runtime GPU texture cache is bounded (192 textures) and fail-soft.
- Dynamic-texture internals are reflection-isolated because Minecraft's 26.x render rewrite has changed constructor/registration details repeatedly.
- If texture registration fails, the map falls back to reference bounds; world/project data remain safe.
- Rotated reference images are still stored but currently fall back to bounds rather than textured rotation.

## Reference layer editing

Plan-tab reference selection now uses topmost visible layer under the clicked coordinate when no vector is hit. Server-authoritative actions support rename, show/hide, lock/unlock, delete and opacity. Existing planning SavedData schema remains unchanged.

## Design Cross-Section

New network payloads: `analysis_request` and `analysis_response`.

Plan → Cross-section → click A → click B. Server samples loaded surface columns only (unloaded columns are reported missing, not force-generated), returns distance/travel estimates and terrain elevation samples, and the map renders the line/profile card.

## v42 validation status

- Source-level parse/brace checks show no Java grammar errors in changed files.
- Full Gradle/Fabric 26.2 compile is still pending because the project package lacks `gradle-wrapper.jar` and the execution environment has Java 21 while the project requests a Java 25 toolchain.
- `GuiGraphicsExtractor.blit(RenderPipelines.GUI_TEXTURED, ...)` follows current Fabric 26.2 rendering guidance; runtime dynamic texture compatibility remains an explicit playtest item.

# Accepted full Design / Planning scope — IMPLEMENT, not merely brainstorm

Everything below is accepted product scope and should be built incrementally with safe migrations and honest completion labels.

## Design Mode / reference images

- Dedicated Design Mode workspace inside the canonical control center.
- Multiple PNG/JPG layers from Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- Visibility, opacity, lock, draw order, translation, scale, rotation, crop, exact Minecraft bounds, notes.
- 2/3-point registration/alignment; later optional controlled multi-anchor warping.
- Design revisions, A/B compare, clipping masks.
- Mandatory huge-image tile/mip/LOD architecture; never one monolithic 20k×20k per-frame texture.
- Optional project-local content-addressed copies, missing-asset locate/replace/remove and identifying thumbnails.

## Rich planning geometry

- Continents/coastlines, mountains, rivers, lakes, forests/deserts/biome areas, roads, borders, cities, landmarks, freeform areas/lines and text.
- Rectangle/polygon/brush/lasso/line/point tools.
- Add/subtract/intersect selection algebra.
- Vertex move/insert/delete and robust segment/polygon hit testing.
- Smooth/Bezier curves, variable-width footprints, snapping to block/chunk/grid/region/other vertices/cardinal angles.
- Planning hierarchy, linked endpoints, parent/child relationships, advisory constraints/warnings.
- Design variants/scenarios, Blueprint Solo and Current Project Focus Mode.

## Scale / measurement

- Design-scale simulator; distance/area measurement; walk/sprint/horse/boat/Elytra estimates.
- Scale-reference stamps/guides, in-world ghost ruler, block/chunk/large-grid modes, distance buffers and guide rings/intervals.

## Vertical / 3D planning and terrain analysis

- Elevation profiles for ridges/rivers/plateaus/cities.
- Actual-vs-planned cross sections.
- Contours, elevation bands, slope heatmap, arbitrary sea-level preview.
- Watershed/drainage/likely-flow advisory analysis.
- Vertical target beacons/guides and 3D blueprint volumes/envelopes.
- Actual-minus-planned elevation deviation and planned-vs-actual coastline deviation.
- Project completion remains manually controlled even when geographic metrics exist.

## In-World Blueprint Mode full vision

- Full projected reference images, traced vectors and outline-only modes.
- Per-category visibility; fixed Y, sea-level, floating and surface-following projection.
- Tile/LOD/distance-aware rendering.
- Mountain width/peak Y, river width/elevation, city radius/plateau, road width construction guides.
- Preview In World for temporary selections/Rewipe/Restore before confirmation.
- Optional AR-style compass/task/current-project markers.
- Seamless View In World / Open Map geographic-context transition.

## Map / selection / Inspector

- Temporary selections independent of persistent regions; Pregen/Rewipe/Restore/Health/Protect/Create Region actions.
- Multi-region selection, numeric coordinates, go-to, cursor block/chunk/region readout, scale guides, bookmarks/favorites, right-click context actions, command palette/search.
- Inspector/Explain This with rule provenance, hierarchy, health and terrain state.

## Regions / organization

- Folders/groups/hierarchy: World → Continent → country/major region → city/terrain/landmark.
- Relationship-aware inheritance with explicit provenance.
- Templates, notes, Current Project, stages Reserved → Terrain Construction → Detailing → Complete → Archived, archive lock, completion checklists, lightweight dependencies, region stats/thumbnails/bookmarks, metadata import/export.

## Geographic TODOs / notebook / viewpoints

- Full task list/edit/status/priority UI and Blueprint markers.
- Next thing to work on / Take Me There.
- Screenshot/photo attachments, saved before/after viewpoints, build journal, optional time-spent stats, project cover/progress screenshots, session resume note.

## Canvas Health / diagnostics / recovery

- Deep classifications, chunk/selection/region/canvas scans, targeted repair with protected/archive safety, health heatmap/trends/recurring failures, interrupted-job recovery dashboard, diagnostic codes, self-test, diagnostic bundle, Recovery Mode and read-only fallback.

## Adaptive Pregen v2 / jobs

- Rolling MSPT/completion latency/queue/memory/GC-save-pressure controller, learned sustainable throughput/concurrency, Quiet/Balanced/Overnight/Custom profiles, auto-calibration, idle/empty-server acceleration, ETA range/confidence, performance history/disk forecasting, robust drain/backpressure/self-healing, visible job queue, saved workflows and Run Overnight report.

## History / snapshots / Timeline

- Operation history, named lightweight snapshots, automatic restore points, snapshot diff, world changelog, project export/import excluding terrain, capability/data-format migration view, compatibility checks, periodic lightweight map/project snapshots and World Timeline playback from blank ocean to completed project.

## Atlas / completed-world experience

- Mark plans Implemented and catalogue completed features.
- World Atlas with technical/topographic/fantasy/Minecraft-map styles, optional exploration/discovery view, static/high-resolution export, eventual read-only HTML map and shareable `.oceanproject` project package without terrain.

## Expansion

- Visual Expansion Planner with affected/new/reused chunks, learned ETA, disk estimate and protected intersections.
- Eventual migration from centered square `canvasSize` to explicit `minX/maxX/minZ/maxZ` for directional expansion, preserving old square-canvas saves.

# Immediate implementation order after v42

1. Authoritative Minecraft/Fabric 26.2 compile + runtime fixes; continue Restore safety test matrix.
2. Reference-image registration-point/numeric alignment editor plus move/scale/rotate handles and proper textured rotation.
3. Extend tiled reference system into **image-backed in-world Blueprint Mode** with strict distance/LOD budgets.
4. Full vector editing/hit testing/styles/scenario assignment and elevation-profile editor; vertical beacons/3D envelopes.
5. Contour/elevation-band/slope/sea-level analysis layers, then drainage/deviation analysis.
6. Full task/journal/viewpoint/scenario management UI.
7. Deep Canvas Health / recovery + Adaptive Pregen v2/job queue/Overnight.
8. Region hierarchy/inheritance, history/snapshots, Atlas and Timeline.

## Development handoff contract

Every build/handoff must update this file with implemented vs groundwork/planned, save/network/config changes and migrations, known risks/limitations, exact build/test status and Minecraft/Fabric target, current build identity, and next work. Never rely on chat history alone for an important Ocean Canvas decision.
# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas changes. Important requirements, architecture decisions, save/network compatibility, current implementation status, validation status, known risks, and next work belong here rather than only in chat history.

## Product identity

Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. Minecraft provides the game; the player creates the geography and world. Ocean Canvas may measure, visualize, organize, analyze, warn, compare and guide, but it must not become an automatic terrain designer, WorldEdit replacement, or generator that makes creative decisions for the player.

The **Ocean Canvas map/control center is the canonical UI**. Every normal mod feature/settings workflow should be editable there. Commands can remain power-user equivalents. Mod Menu should route to the same editor rather than own a second configuration surface. Server/world-affecting configuration is server-authoritative; purely visual client preferences may remain client-side.

## Canonical terrain operations

- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This replaces old Reset/Reclear/destructive Unprotect wording.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the world's original seed and vanilla generation. UI may say Restore; confirmations/tooltips should say **Restore to Vanilla** and explicitly warn that player modifications in affected chunks are replaced.
- Internally prefer unambiguous concepts such as `PREGENERATE`, `REWIPE_TO_CANVAS`, `RESTORE_TO_VANILLA`. Persisted old `reset` / `region-reset` job kinds must remain readable for migration.

## Foundational state model

Never conflate these:

- Terrain state: `VANILLA`, `CANVAS`, `CUSTOM_OR_MODIFIED`, `UNKNOWN`.
- Generation/job state: `UNGENERATED`, `QUEUED`, `PROCESSING`, `COMPLETE`, `RETRYING`, `ERROR`, `SKIPPED_OR_PROTECTED`.
- Project state: `UNASSIGNED`, `RESERVED`, `TERRAIN_CONSTRUCTION`, `DETAILING`, `COMPLETE`, `ARCHIVED`.

Pregen/Rewipe/Restore must not silently change project stage.

## Core safety / longevity requirements

- A metadata problem must never unnecessarily make Minecraft terrain inaccessible. Provide Recovery Mode and read-only fallback for incompatible/unreadable Ocean Canvas project data.
- Destructive operations need impact previews and explicit confirmations.
- Archived/protected areas must not be silently modified.
- Restore to Vanilla must use a real vanilla-regeneration path, never a relabeled Rewipe.
- Keep lightweight Ocean Canvas project snapshots separate from full Minecraft-world backups.
- Long-term compatibility across Minecraft/Ocean Canvas versions is first-class.
- Missing/corrupt planning images must never prevent the world from loading.
- Do not mark groundwork as production-complete.

# Implemented lineage through v43

- **v34** platform groundwork: health/planner/templates/stages/milestone/performance persistence foundations.
- **v35** map region Pregen + numeric coordinate editing; exact irregular-region masks.
- **v36** unified map control center; Mod Menu routes into it; server-authoritative config sync.
- **v37** canonical Rewipe terminology + experimental Restore-to-Vanilla regeneration path.
- **v38** explicit terrain-state tracking, planning persistence, Inspector foundation, Archived stage/locks.
- **v39** server→client Project/Planning/Inspector sync; map Inspector; Project tab; Archive/Unlock; Planning layer feed.
- **v40** first server-authoritative planning editor: trace/select/rename/show/hide/lock/delete planning objects on the map.
- **v41** broad Design Mode tranche: workspace/project notebook SavedData, geographic TODOs, design scenarios, planning schema 2 styling/width/elevation/scenario/implemented fields, massive reference-image tile-pyramid asset store, terrain-analysis service, first in-world Blueprint vector/TODO renderer.
- **v42** imported reference-image mip tiles actually render on the map with opacity/LOD; references can be selected/renamed/hidden/locked/deleted; two-click Cross-section Design Analysis returns loaded-terrain elevation profile, distance and travel-scale estimates.
- **v43** exact reference transform + first usable vertical-planning tranche, detailed below.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 compile/runtime testing on disposable copied worlds, including structures, block entities, lighting, seams, save/reload, then Rewipe of the restored area.

# v43 — Reference transform + vertical planning

## Actually implemented

- Added an Ocean Canvas **Reference Transform** sub-panel reached from the canonical map Plan tab when a reference image is selected.
- Transform editor supports exact Minecraft `minX/minZ/maxX/maxZ` bounds and rotation degrees.
- Transform editor also provides fast 16-block nudges, ±10% scaling, and ±5° rotation before Save.
- Added server-authoritative `reference_transform`; locked reference layers reject transformation server-side.
- Planning sync now appends the packed elevation profile to each vector. Older v42-and-earlier packets without this field remain readable and default to no elevation guide.
- Added an Ocean Canvas **Elevation Profile** sub-panel for selected planning vectors.
- First vertical-planning editor supports a linear target Y from first plan point to last, plus clearing the guide.
- Added server-authoritative `elevation_linear` and `elevation_clear`; locked plans reject elevation edits.
- Blueprint Mode now interpolates target elevation along plan geometry when a profile exists instead of forcing the global projection Y.
- Blueprint Mode draws vertical target guide/beacon columns at traced control points between ordinary projection/surface and planned Y.
- All Blueprint guides remain client-only visuals; no blocks, entities or persistent particles are placed.
- Plan tab exposes `Transform Reference…` for image layers and `Elevation Profile…` / `Elevation Profile ✓…` for vectors.

## v43 compatibility

- `planning_data` SavedData remains schema 2; elevation profile fields already existed in schema 2, so no SavedData migration is needed.
- `planning_sync` changes additively by appending packed elevation data after existing vector fields.
- Reference transforms only mutate existing schema-2 reference fields.

## v43 limitations

- Rotated image metadata is editable, but the v42 tiled map renderer still does not draw rotated pixels. Rotated references currently fall back to a bounds outline until transformed-quad/tile rendering is implemented.
- Elevation editing is currently two-endpoint linear. Intermediate elevation control points remain required.
- 2/3-point image↔Minecraft registration/control-point alignment remains required; exact bounds are usable now but are not the end-state alignment workflow.
- Full tiled reference-image projection inside in-world Blueprint Mode remains pending.

# Accepted full Design / Planning scope — IMPLEMENT, not merely brainstorm

Everything below is accepted product scope and should be built incrementally with safe migrations and honest completion labels.

## Reference images / Design Mode

- Dedicated Design Mode within the canonical map control center.
- Multiple PNG/JPG layers from Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- Visibility, opacity, lock, draw order, translation, scale, rotation, crop, exact Minecraft bounds, notes.
- 2/3-point registration/alignment; later controlled multi-anchor warping where useful.
- Multiple design revisions, A/B compare, clipping masks.
- Mandatory massive-image mip/tile/LOD architecture; never one monolithic 20k×20k frame texture.
- Optional project-local content-addressed copies, missing-asset locate/replace/remove flow, identifying thumbnails.

## Rich planning geometry

- Continents/coastlines, mountains, rivers, lakes, forests/deserts/biome areas, roads, borders, cities, landmarks, freeform shapes/lines and text.
- Rectangle/polygon/brush/lasso/line/point tools; add/subtract/intersect selection algebra.
- Vertex move/insert/delete and robust segment/polygon hit testing.
- Smooth/Bezier curves, variable-width rivers/roads/mountain influence/city envelopes.
- Snapping to block/chunk/grid/region/other vertices/cardinal angles.
- Planning hierarchy, linked endpoints, parent/child relationships, advisory constraints/warnings.
- Named variants/scenarios, Blueprint Solo Mode and Current Project Focus Mode.

## Scale / measurement

- Design-scale simulator; distance/area tools; approximate walk/sprint/horse/boat/Elytra times.
- Reusable scale stamps/guides; in-world ghost ruler/distance-to-plan.
- Block/chunk/large-grid/region grid modes; distance buffers and guide rings/intervals.

## Vertical / 3D planning and terrain analysis

- Multi-point elevation profiles for ridges/rivers/plateaus/cities.
- Actual-vs-planned terrain cross-sections.
- Contours, elevation bands, slope heatmap, arbitrary sea-level preview.
- Watershed/drainage/likely-flow advisory analysis.
- Vertical target beacons/guides and 3D blueprint envelopes/volumes.
- Actual-minus-planned elevation deviation and coastline deviation.
- Project completion remains manually controlled even when geographic metrics exist.

## In-world Blueprint Mode full vision

- Full reference-image projection, traced objects and outline-only modes with category filters.
- Fixed Y, sea level, floating and surface-following projection.
- Tiled/LOD/distance-aware rendering for huge plans.
- Construction guides for mountain width/peak Y, river width/elevation, city radius/plateau, road width.
- Preview In World for temporary selections/Rewipe/Restore before confirmation.
- Optional AR compass/task/current-project markers.
- Seamless View In World / Open Map geographic-context transition.

## Map / regions / inspector

- Temporary selections independent of persistent regions; Pregen/Rewipe/Restore/Health/Protect/Create Region actions.
- Multi-region selection; numeric coordinate editing; go-to coordinates; cursor block/chunk/region readout.
- Scale guides, bookmarks, favorites, right-click actions, command palette/search.
- Inspector/Explain This with rule provenance, hierarchy, health and terrain state.
- Region folders/groups/hierarchy and relationship-aware inheritance with explicit provenance.
- Templates, notes, Current Project, stages Reserved→Terrain Construction→Detailing→Complete→Archived, archive lock, checklists, dependencies, stats, thumbnails/bookmarks, metadata import/export.

## Geographic TODOs / notebook / viewpoints

- Coordinate-anchored notes/TODOs, status/priority and map/Blueprint markers.
- Next thing to work on / Take Me There.
- Screenshot/photo attachments, saved before/after viewpoints, build journal, optional time-spent stats, progress screenshots, session resume note.

## Canvas Health / recovery

- Deep classifications and chunk/selection/region/canvas scans.
- Targeted repair with protected/archive safety, health heatmap/trends, recurring-failure detection.
- Crash/interrupted-job recovery dashboard, diagnostic codes, self-test, one-click diagnostic bundle, Recovery Mode/read-only fallback.

## Adaptive Pregen v2 / jobs

- Rolling MSPT/completion latency/queue/memory/GC/save-pressure control.
- Learned sustainable throughput/concurrency; Quiet/Balanced/Overnight/Custom; calibration; idle/empty-server acceleration.
- ETA confidence ranges, performance history, disk forecasting, robust drain/backpressure/self-healing.
- Visible job queue, saved workflows such as Rewipe→Pregen→Health Scan, Run Overnight + report.

## History / Atlas / Timeline

- Operation history, named project snapshots, automatic restore points, snapshot diff, world changelog, project export/import, migration/capability view, compatibility checks.
- Periodic lightweight map/project snapshots and **World Timeline** playback without block-by-block recording.
- Implemented-feature catalogue and World Atlas with technical/topographic/fantasy/Minecraft styles, optional discovery mode, high-resolution/static export, eventual read-only HTML map and shareable `.oceanproject` package.

## Expansion

- Visual Expansion Planner with new/reused chunks, learned ETA, disk estimate and protected intersections.
- Eventual migration from centered square `canvasSize` to explicit `minX/maxX/minZ/maxZ` for asymmetric directional expansion while preserving old saves.

# Immediate implementation order after v43

1. 2/3-point image registration and transformed/rotated tile renderer.
2. Intermediate elevation-profile control points and actual-vs-planned cross-section overlay.
3. Tiled reference-image projection in in-world Blueprint Mode.
4. Sea-level preview, slope heatmap, contour/elevation map layers, then drainage/deviation analysis.
5. Continue Deep Canvas Health / recovery and Adaptive Pregen v2 / job queue / Overnight work in parallel.
6. Rich vector vertex/Bezier/snapping tools and Design Mode notebook/viewpoint UIs.
7. Region hierarchy/inheritance, snapshots/history, Atlas and Timeline.

## Validation / handoff contract

Current v43 source passes brace/Java grammar checks. `javac -proc:none` in the ChatGPT environment still reaches expected missing Minecraft/Fabric/ModMenu dependencies and no new Java parse errors. An authoritative Minecraft/Fabric 26.2 Gradle compile and runtime test is still required in the proper target environment.

Every build/handoff must update this file with actually implemented vs groundwork/planned, save/network/config migrations, known risks, exact build/test status, current build identity, and next recommended work. Never rely on chat history alone for an important Ocean Canvas decision.
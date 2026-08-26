# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** Read this before major Ocean Canvas changes. Important requirements, architecture decisions, save/network compatibility, current implementation status, validation status, known risks, and next work belong here rather than only in chat history.

## Product identity

Ocean Canvas is a Minecraft world-planning, generation-management, safety/recovery, and project-control platform for building very large handcrafted worlds over years. Minecraft provides the game; the player creates the geography and world. Ocean Canvas may measure, visualize, organize, analyze, warn, compare and guide, but it must not become an automatic terrain designer, WorldEdit replacement, or generator that makes the creative decisions for the player.

The **Ocean Canvas map/control center is the canonical UI**. Every normal mod feature/settings workflow should be editable there. Commands can remain as power-user equivalents. Mod Menu should route to the same editor rather than own a second configuration surface. Server/world-affecting configuration is server-authoritative; purely visual client preferences may remain client-side.

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

# Implemented lineage through v40

Recent development packages include:

- v34 platform groundwork: health/planner/templates/stages/milestone/performance persistence foundations.
- v35 map region Pregen + numeric coordinate editing; exact irregular-region masks.
- v36 unified map control center; Mod Menu routes into it; server-authoritative config sync.
- v37 canonical Rewipe terminology + experimental Restore-to-Vanilla regeneration path.
- v38 explicit terrain-state tracking, planning persistence, Inspector foundation, Archived stage/locks.
- v39 server→client Project/Planning/Inspector sync; map Inspector; Project tab; Archive/Unlock; Planning layer feed.
- v40 first server-authoritative planning editor: trace/select/rename/show/hide/lock/delete planning objects on the map.

The experimental Restore path still requires authoritative Minecraft/Fabric 26.2 compile/runtime testing on disposable copied worlds, including structures, block entities, lighting, seams, save/reload, then Rewipe of the restored area.

# v41 broad Design Mode tranche — ACTUALLY IMPLEMENTED IN WORKING TREE

The packaged v41 development tree materially implements the following rather than only listing them.

## Workspace / project notebook data

Added independent versioned `OceanCanvasWorkspaceData` (schema 1), separate from critical terrain/region state, containing:

- geographic tasks with TODO / IN_PROGRESS / DONE / BLOCKED status, priority, exact X/Z, notes, timestamps and optional region/planning-object attachment;
- journal entries;
- saved viewpoints: XYZ, yaw/pitch, region, optional screenshot-asset id;
- Atlas-feature records;
- named design scenarios + active scenario;
- session note.

Added server-authoritative `workspace_sync` and `workspace_edit_request`. Supported server actions include task add/done/delete, journal entry, session note, scenario add/activate, viewpoint add and Atlas-feature add. The Project tab can add a geographic TODO at the selected region center and outstanding tasks render on the map. Full task/journal/viewpoint management UI is not yet complete.

## Planning schema 2

`planning_data` advances additively from schema 1 → 2. Planning objects now persist:

- stroke/fill ARGB;
- footprint width in blocks;
- optional elevation profile;
- optional design-scenario id;
- Planned vs Implemented state.

Schema-1 objects load with safe defaults, preserving v38-v40 geometry. Client planning parsing tolerates old packets. The Plan tab exposes footprint width and Planned/Implemented state. Planning types exposed now include continent, coastline, mountain range, river, lake, biome area, forest, desert, road, border, city, landmark, freeform area/line, text.

## Massive reference-image asset groundwork

Added client `OceanCanvasReferenceAssetStore`:

- imports PNG/JPG with a 512 MiB cap;
- content-addresses images with SHA-256;
- stores them outside critical SavedData under client planning assets;
- builds a 256px tiled mip/LOD pyramid by repeated downsampling until one-tile overview size;
- writes an asset manifest.

When no planning vector is selected, the Plan tab can accept a local PNG/JPG path, import/tile it, and create server-authoritative reference-layer metadata with initial Minecraft X/Z bounds centered around the current map view.

**Important architecture rule:** PNG/JPG bytes must never ride periodic project/planning sync or become required for world load. Actual textured tile rendering, multiplayer asset transfer/authorization, thumbnails and locate/replace recovery UI are still pending.

## Terrain-analysis utilities

Added read-only `OceanCanvasTerrainAnalysisService` for:

- loaded-chunk terrain cross-section samples;
- slope calculation;
- arbitrary sea-level submerged tests;
- approximate walk/sprint/horse/boat/Elytra travel-time estimates.

No terrain is modified and unloaded chunks are not forcibly generated by these analysis helpers. Map cross-section/contour/slope/sea-level UI is not complete yet.

## First real In-World Blueprint Mode

Added experimental client-only `OceanCanvasBlueprintOverlay` using Fabric's Minecraft 26.2 level-render event path.

Current behavior:

- renders sampled/distance-capped planning guide geometry in-world;
- renders outstanding geographic TODO markers;
- places **no blocks, entities or persistent particles**;
- supports sea-level, fixed-Y, surface-following and player-relative floating projection modes;
- uses a per-frame geometry budget and render-distance cap;
- filters scenario-tagged vectors against the active design scenario;
- shows Implemented plans differently from planned ones;
- can be toggled with the dedicated B key **and from the Plan tab**;
- Plan tab also cycles projection mode and active design scenario.

Full projected reference-image textures are not implemented yet; they depend on the tiled GPU/cache path.

## v41 network/save compatibility

- New `workspace_data` is additive; absence means empty workspace.
- `planning_data` schema 1→2 uses defaulted fields.
- New vector sync fields append after the older compact v40 fields for tolerant parsing.
- Reference asset bytes stay non-critical/outside SavedData.

## v41 validation / risk status

- `javac -proc:none` with no Minecraft/Fabric classpath reports expected missing-dependency errors and **no Java grammar/parse errors** in the tranche.
- The working project still lacks a usable Gradle wrapper JAR/installed Gradle in the ChatGPT execution environment, so this is not an authoritative Minecraft/Fabric 26.2 compile.
- Highest compile/runtime risk in v41 is exact 26.2 render API/mapping behavior around the experimental Blueprint renderer; verify it in the real target environment before relying on Blueprint Mode.

# Accepted full Design / Planning scope — IMPLEMENT, not merely brainstorm

All items below are accepted scope and should be built incrementally with safe migrations and honest completion labels.

## Reference images and Design Mode

- Dedicated **Design Mode** workspace inside the canonical control center.
- Multiple PNG/JPG reference layers from Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- visibility, opacity, lock, order, translation, scale, rotation, crop, exact Minecraft bounds, notes.
- 2- or 3-point registration/alignment; later optional controlled multi-anchor warping.
- multiple design revisions, A/B comparison, clipping masks.
- mandatory huge-image mip/tile/LOD rendering architecture; never one monolithic 20k×20k per-frame texture.
- optional project-local content-addressed copies for portability.
- missing-asset locate/replace/remove flow; tiny identifying thumbnails where useful.

## Rich planning geometry

Planning objects include coastlines/continents, mountains, rivers, lakes, forests/deserts/biome areas, roads, borders, cities, landmarks, freeform shapes/lines and text.

Implement:

- rectangle, polygon, brush, lasso, line, point tools;
- add/subtract/intersect selection algebra;
- vertex move/insert/delete and robust segment/polygon hit testing;
- smooth/Bezier curves where useful;
- variable-width rivers/roads/mountain influence/city envelopes;
- snapping to block/chunk/grid/region/other vertices/cardinal angles;
- planning hierarchy and linked endpoints;
- parent/child relationships and advisory constraints/warnings;
- planning variants and named design scenarios;
- Blueprint solo mode and Current Project Focus Mode.

## Scale / measurement

Implement:

- design-scale simulator;
- distance and area measurement;
- approximate travel times by walk/sprint/horse/boat/Elytra;
- reusable scale-reference stamps/guides;
- in-world ghost ruler/distance-to-plan;
- block/chunk/large-grid/region grid modes;
- distance buffers and guide rings/intervals.

These are mathematical/measurement guides, not automatic world design.

## Vertical / 3D planning and terrain analysis

Implement:

- elevation profiles for mountain ridges, rivers, plateaus, cities, etc.;
- terrain cross-section actual-vs-planned view;
- contour lines;
- elevation bands;
- slope heatmap;
- arbitrary sea-level preview;
- watershed/drainage/likely-flow advisory analysis;
- vertical target beacons/guides;
- 3D blueprint volumes/envelopes;
- actual-minus-planned elevation deviation;
- planned-vs-actual coastline deviation.

Project completion stage remains manually controlled even if geographic metrics are calculated.

## In-World Blueprint Mode full vision

- Full reference-image projection, traced planning objects and outline-only modes.
- per-category visibility.
- fixed Y, sea-level, floating and surface-following projection.
- tiled/LOD/distance-aware rendering for huge plans.
- construction guides: mountain width/peak Y, river width/elevation, city radius/plateau, road width.
- operation Preview In World for temporary selections/Rewipe/Restore before confirming.
- optional AR-style compass strip and task/current-project markers.
- seamless **View In World** / **Open Map** geographic-context transition.
- complex editing remains map-first; simple in-world adjustments may be added later.

## Map / selection / inspector

Implement/continue:

- temporary selections independent of persistent regions;
- actions: Pregen, Rewipe, Restore, Health Scan, Protect, Create Region;
- multi-region selection;
- numeric coordinate editing + go-to coordinates;
- cursor block/chunk/region readout;
- scale guides, bookmarks, favorites;
- right-click contextual actions;
- command palette/search inside map;
- Inspector/Explain This with rule provenance, hierarchy, health and terrain state.

## Regions / organization

Implement/continue:

- folders/groups/hierarchy: World → Continent → country/major region → city/terrain/landmark;
- relationship-aware inheritance with explicit source/provenance;
- templates;
- notes;
- Current Project;
- stages Reserved → Terrain Construction → Detailing → Complete → Archived;
- archived configuration/terrain lock;
- completion checklists;
- lightweight dependencies;
- region stats, thumbnails, bookmarks;
- metadata import/export.

## Geographic TODOs / notebook / viewpoints

Implement full UI for:

- coordinate-anchored notes and TODOs;
- status/priority/task markers in map + Blueprint Mode;
- “Next thing to work on” and Take Me There;
- screenshot/photo attachments;
- saved before/after viewpoints;
- build journal;
- optional project time-spent stats;
- project cover/progress screenshots;
- session resume note.

## Canvas Health / diagnostics / recovery

Implement:

- deep classifications: healthy, ungenerated, generated-but-unprocessed, unexpected terrain, processing incomplete, pending retry, state mismatch, protected, etc.;
- chunk/selection/region/canvas scans;
- targeted repair with protected/archive safety;
- health heatmap/trends/recurring failures;
- crash/interrupted-job recovery dashboard;
- diagnostic codes;
- self-test;
- one-click diagnostic bundle excluding giant world chunks;
- Recovery Mode and read-only fallback.

## Adaptive Pregen v2 / jobs

Goal: safely exploit available hardware and make roughly 20k×20k overnight preparation practical on capable machines.

Implement:

- rolling MSPT/completion latency/queue/memory/GC-save-pressure control;
- learned sustainable throughput/concurrency;
- Quiet/Balanced/Overnight/Custom profiles;
- automatic calibration;
- idle/empty-server acceleration;
- ETA ranges/confidence;
- performance history + disk forecasting;
- robust drain/backpressure/self-healing;
- visible job queue;
- saved workflows such as Rewipe → Pregen → Health Scan;
- Run Overnight + completion report.

## History / snapshots / timeline

Implement:

- operation history;
- named lightweight project snapshots;
- automatic restore points before important metadata changes;
- snapshot diff;
- world-level changelog;
- project export/import excluding terrain;
- capability/data-format migration view;
- world compatibility checks;
- periodic lightweight map/project snapshots;
- **World Timeline** playback showing the project grow from blank ocean to completed world without block-by-block replay.

## Atlas / completed-world experience

Implement:

- mark plans Implemented and catalogue completed natural/world features;
- World Atlas based on accumulated project/planning data;
- technical, topographic, fantasy-atlas and Minecraft/map-tile styles;
- optional exploration/discovery presentation mode;
- static/high-resolution export;
- eventual read-only HTML map;
- shareable `.oceanproject`-style project package with planning/project/assets but no Minecraft terrain.

## Expansion

Implement:

- visual Expansion Planner with affected/new/reused chunks, learned ETA, disk estimate and protected intersections;
- eventual migration from one centered square `canvasSize` to explicit `minX/maxX/minZ/maxZ` so north/east/south/west can expand independently;
- preserve old square-canvas saves during migration.

# Immediate implementation order after v41

1. Authoritative Minecraft/Fabric 26.2 compile + Blueprint render API fixes; continue Restore safety test matrix.
2. Actual tiled reference-image map renderer + Layer Manager + opacity/order/bounds/rotation + missing-asset handling.
3. Full vector editing/hit testing/styles/scenario assignment/elevation editor.
4. Design Mode UI for tasks/journal/viewpoints/scenarios.
5. Map scale/travel/cross-section/sea-level/slope/elevation tools, then contours/drainage/deviation analysis.
6. Extend Blueprint Mode with tiled image projection, vertical guides, variable-width footprints, task labels and operation previews.
7. Deep Canvas Health / recovery + Adaptive Pregen v2/job queue/Overnight.
8. Region hierarchy/inheritance, history/snapshots, Atlas and Timeline.

## Development handoff contract

Every build/handoff must update this file with:

- actually implemented vs groundwork/planned;
- save/network/config format changes and migrations;
- known bugs/risks/limitations;
- exact build/test status and Minecraft/Fabric target;
- current branch/build identity where relevant;
- next recommended development slice.

Never rely on chat history alone for an important Ocean Canvas decision.
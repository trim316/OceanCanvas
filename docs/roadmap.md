# Ocean Canvas — Authoritative Roadmap

> **Cross-chat source of truth.** This file is the durable handoff for Ocean Canvas development. Before major changes, read and preserve it. Every development pass must update implementation status, important architecture decisions, compatibility/migration concerns, known risks, validation status, and the next slice of work.

## Product identity

Ocean Canvas is a Minecraft world-planning, generation-management, and project-control system for building very large handcrafted worlds. It should help the player plan, prepare, protect, inspect, restore, and progressively build the world themselves. It should **not** become a terrain generator, WorldEdit replacement, or automatic world designer.

The Ocean Canvas **map/control center is the canonical UI**. Every normal mod feature and setting must be editable there. Commands may remain as power-user equivalents. Mod Menu should route into the same control center rather than maintain a second configuration surface. Server/world-affecting configuration is server-authoritative; purely visual client preferences may remain client-side.

## Canonical operation terminology

- **Pregen** — generate/prepare selected chunks using Ocean Canvas processing.
- **Rewipe** — return selected chunks to the Ocean Canvas blank-ocean state. This is the behavior historically called Reset/Reclear/Unprotect in older builds.
- **Restore / Restore to Vanilla** — regenerate selected chunks from the world's original seed and vanilla world-generation pipeline, replacing the Ocean Canvas-cleared terrain. UI buttons may say **Restore**; confirmations/tooltips should say **Restore to Vanilla** and explicitly warn that player modifications in affected chunks will be replaced.
- Internally prefer unambiguous concepts such as `PREGENERATE`, `REWIPE_TO_CANVAS`, and `RESTORE_TO_VANILLA`. Legacy persisted `reset` / `region-reset` job identifiers must remain readable for migration of interrupted older jobs.

## Foundational state model

Do not conflate these dimensions:

### Terrain state
`VANILLA` / `CANVAS` / `CUSTOM_OR_MODIFIED` / `UNKNOWN`

### Generation/job state
`UNGENERATED` / `QUEUED` / `PROCESSING` / `COMPLETE` / `RETRYING` / `ERROR` / `SKIPPED_OR_PROTECTED`

### Project state
`UNASSIGNED` / `RESERVED` / `TERRAIN_CONSTRUCTION` / `DETAILING` / `COMPLETE` / `ARCHIVED`

A region's project stage must survive Pregen/Rewipe/Restore operations unless the user explicitly changes it.

## Core safety requirements

- Ocean Canvas metadata failure must never make the Minecraft terrain inaccessible when a safe read-only/recovery path is possible.
- Add **Recovery Mode** for unreadable/incompatible project metadata and **read-only fallback** when an older Ocean Canvas cannot safely understand newer data.
- Destructive operations must have impact previews and clear confirmations.
- Protected/archived areas must not be silently modified.
- Restore to Vanilla must be a genuine vanilla-regeneration path; never implement it by relabeling Rewipe.
- Major metadata/config changes should support lightweight project snapshots independent of full world backups.
- Long-term save compatibility across Minecraft/Ocean Canvas versions is a first-class requirement.

# Planning System — Core Pillar

Planning is now a first-class Ocean Canvas subsystem, not a cosmetic future feature. It should replace much of the external Photoshop-style planning workflow while remaining non-destructive.

## Reference image import

Support PNG/JPG reference images exported from tools such as Photoshop/GIMP, Gaea, Azgaar, WorldPainter, etc.

Each image is a planning layer with:
- visibility toggle
- opacity
- lock/unlock
- draw order
- translation/position
- scale
- rotation
- non-destructive crop
- exact Minecraft X/Z bounds
- optional name/notes
- multiple simultaneous images and design revisions
- compare/fade between revisions

### Alignment

Support both direct numeric alignment and registration/control points:
- image point A -> Minecraft X/Z
- image point B -> Minecraft X/Z
- optional third point for robust scale/rotation alignment

The image import is a **reference/blueprint**, not terrain generation.

## Planning/vector layers

Allow non-destructive planning objects drawn over reference images and/or Minecraft map data:
- continent/coastline
- mountain range
- river
- lake
- biome/forest/desert area
- road/path
- political/project border
- city/settlement
- landmark/pin
- freeform area
- freeform line
- text/note

Provide rectangle, polygon, brush, lasso, line, point, add/subtract/intersect selection tools where appropriate. Manual tracing is preferred initially over automatic AI coastline extraction.

Planning objects may later be converted into saved Ocean Canvas regions while preserving the original planning object for plan-vs-reality comparison.

## Map layer system

The map needs a proper layer manager so complexity does not become visual clutter. Candidate layers include:
- actual Minecraft terrain/map tiles
- imported reference images
- planning vectors
- regions and groups
- annotations
- protection/archive status
- biome assignment
- structures policy
- generation/job status
- Canvas Health
- performance heatmap

Users control visibility, ordering where appropriate, and opacity.

# In-World Blueprint Mode — Core Planning Feature

Planning data must be usable **in the Minecraft world**, not only on the map.

## Blueprint Mode

Add a quick-toggle **Blueprint Mode** that renders planning information client-side without placing blocks, entities, armor stands, or permanent particles. Turning it off must leave the world completely untouched.

Display modes:
- **Full Reference Overlay** — imported image projected into the world with adjustable opacity.
- **Planning Objects** — traced coastlines, rivers, mountain ranges, roads, cities, etc.
- **Outline Only** — simplified major boundaries.
- per-category visibility filters.

## Projection modes

Support, progressively:
- fixed Y projection
- sea-level projection
- floating offset above terrain
- surface projection that follows terrain

The blank-canvas use case should make it easy to project a planned continent/coastline just above the ocean surface.

## Performance

In-world rendering must be distance-aware and client-side. Do not attempt to render a 20k×20k texture at full detail around the player. Use clipping/tiling/LOD so nearby data is detailed and distant data simplifies to major outlines. Render distance must be configurable.

## Construction guides

Planning objects may optionally carry guide metadata, for example:
- mountain base width, target peak Y range, ridge direction
- river width and target elevations
- city radius/bounds/center
- road width

These are visual guides only; Ocean Canvas must not build the terrain automatically.

## In-world operation preview

Temporary selections and destructive-operation previews should optionally remain visible after closing the map. Before Rewipe or Restore to Vanilla, the player can choose **Preview In World**, fly around the affected boundary, then confirm/cancel. This is a safety feature as well as a planning tool.

# Map / Selection / Inspector

- Temporary map selections independent of saved regions.
- Actions on temporary selections: Pregen, Rewipe, Restore to Vanilla, Health Scan, Protect, Create Region, etc.
- Saved-region Pregen uses the region's exact chunk mask, including irregular polygon/brush shapes.
- Coordinate editing for rectangular region bounds directly in the map; irregular shapes retain their geometry rather than silently becoming rectangles.
- Multi-region selection.
- Selection add/subtract/intersect.
- Go to X/Z coordinates.
- Persistent cursor readout for block/chunk/region.
- Distance and area measurement tools.
- World center, cardinal axes, scale guides, optional travel-scale guides.
- Map bookmarks/favorites and Current Project centering.
- Context-sensitive right-click actions.
- Command palette/search inside the map.
- Region/object search by name, group, stage, note, template, coordinates.

## Inspector / Explain This

Clicking any map point should expose effective state and rule provenance: canvas membership, terrain state, generation state, health, protection, biome, containing region hierarchy, and effective rules.

Add **Explain this / Why?** diagnostics so a user can ask why a chunk was skipped, why a biome/rule applies, or which parent region supplied an inherited setting.

# Regions / Organization

- Region templates/presets.
- Region notes.
- Project stages: Reserved -> Terrain Construction -> Detailing -> Complete -> Archived.
- Archive/configuration lock distinct from ordinary terrain protection; archived regions require explicit unlocking before destructive changes.
- Region folders/groups/hierarchy such as World -> Continent -> Country/Major Region -> Terrain Region/City/Landmark.
- Rule inheritance from parent groups with explicit UI provenance.
- Relationship-aware containment should be preferred over arbitrary numeric precedence where possible; retain explicit priority for exceptional overlaps.
- Multi-region actions.
- Completion checklists, customizable per region/template.
- Lightweight dependencies/blockers where useful.
- Region statistics, child counts, generated/verified percentages, etc.
- Region thumbnails/bookmarks.
- Region metadata import/export.

# Generation / Performance

## Adaptive Pregen v2

Goal: use as much safe generation throughput as possible while preserving server health and supporting a roughly 20k×20k overnight-preparation workflow on capable hardware.

Controller inputs should include rolling MSPT, completion latency, outstanding futures/queue depth, heap/memory pressure, GC/save pressure where observable, and actual throughput. Learn the machine/world's sustainable rate rather than endlessly oscillating.

Profiles:
- Quiet
- Balanced
- Overnight
- Custom

Additional goals:
- automatic calibration/benchmark
- learned sustainable concurrency/rate
- idle acceleration / pause-or-slow while active players need performance
- dedicated-server empty acceleration
- ETA with confidence/range, not false precision
- performance history
- actual-world disk-size forecasting
- safe queue/backpressure/drain/self-healing behavior
- scheduled/queued overnight operations

## Job queue / workflows

Allow sequences such as Rewipe -> Pregen -> Health Scan, with a visible job queue. Eventually support saved workflows such as **Prepare Build Region** and a smart **Run Overnight** mode that reports results in the morning.

# Canvas Health / Diagnostics

Canvas Health must distinguish a shallow summary from a deep integrity scan. Deep scan classifications should include healthy, ungenerated, generated-but-unprocessed, unexpected terrain, processing incomplete, pending retry, state mismatch, protected, etc.

Features:
- health dashboard and map heatmap
- targeted scan: chunk / selection / region / canvas
- targeted repair: chunk / selection / region / recoverable-all
- never silently repair protected/archived terrain
- crash/interrupted-job recovery dashboard
- health trends and recurring-failure detection
- diagnostic error/support codes
- built-in correctness self-test for persistence, serialization, processing pipeline, networking, backup destination, etc.
- one-click diagnostic bundle with version/config/project/job/health/benchmark/relevant-log data but no giant world chunks

# Expansion

- Visual Expansion Planner with affected/new chunks, reused/generated counts, learned ETA, disk forecast, protected-region intersection, and preview overlay.
- Eventually support **asymmetric canvas bounds** (`minX/maxX/minZ/maxZ`) rather than only a centered square `canvasSize`, allowing north/east/south/west expansion independently.
- Preserve compatibility with existing square-canvas worlds during migration.
- Multiple canvases and other dimensions are possible later, but lower priority than a rock-solid Overworld workflow.

# History / Snapshots / Recovery

- Operation history with timestamps and undoability/backup status.
- Lightweight named project snapshots for metadata/config/regions/plans.
- Automatic metadata restore points before consequential changes.
- Snapshot comparison showing added/deleted/changed regions, boundaries, rules, stages, and planning objects.
- World-level changelog for significant project events.
- Full Ocean Canvas project export/import excluding Minecraft terrain.
- Feature/data-format capability registry and migration view.
- World compatibility check on upgrade.

# Dashboard / Project Management

The control center may open to a map-centered Home dashboard showing world health, canvas size, generation %, Current Project, active job/ETA, and items needing attention.

Additional project features:
- designate Current Project
- lightweight session/next-step notes
- world statistics dashboard
- milestones/vanilla-style advancements where appropriate
- read-only showcase mode
- export a high-resolution/static planning map and eventually an optional standalone read-only HTML map

# Backups / Safety Policies

Move beyond one generic backup toggle toward operation-aware policies, e.g. Restore/Rewipe may require or strongly recommend backups depending on affected/modified chunks, while Pregen normally does not need a full backup. Impact previews should report affected chunk count, protected/archive intersections, apparent player modifications when reliably detectable, estimated time/storage effects, and whether a backup/snapshot will be created.

Investigate a lightweight, performant way to flag chunks that appear player-modified after Ocean Canvas processing without intercepting every block placement if possible.

# External workflow bridge

- Reference-image import is the primary bridge to Photoshop/GIMP/Gaea/Azgaar/WorldPainter.
- Coordinate bridge between image pixels/external maps and Minecraft X/Z.
- Planning-map export with selectable layers.
- Do not automatically generate terrain from these planning assets; the user remains the world builder.

# Current implementation lineage / handoff notes

Recent development packages prior to this roadmap included groundwork for:
- unified map/control-center settings
- server-authoritative config synchronization
- region coordinate editing
- exact-mask region Pregen
- project stage/notes persistence and commands
- learned throughput/benchmark profile groundwork
- shallow Canvas Health and Expansion Planner groundwork
- Pregen/Rewipe terminology migration
- experimental Restore-to-Vanilla regeneration path
- map job status/overlay groundwork

**Important:** the authoritative Minecraft/Fabric 26.2 build and runtime tests remain necessary before declaring the experimental Restore path production-safe. Test Restore on disposable/copied worlds first, including structures, block entities, lighting, chunk seams, save/reload, then Rewipe of the same restored area.

# Development order

Near-term priority order:

1. Keep Pregen/Rewipe stable and prove Restore-to-Vanilla correctness/safety on Minecraft 26.2.
2. Formalize terrain-state tracking and migrations.
3. Deep Canvas Health + targeted repair + recovery/read-only compatibility.
4. Adaptive Pregen v2 calibration, learned performance, richer per-chunk telemetry, job queue/Overnight mode.
5. Build the Planning subsystem data model and map layer manager.
6. Implement reference-image import/alignment and manual planning vectors in the map.
7. Implement client-side In-World Blueprint Mode with performant tiled/LOD rendering and operation previews.
8. Region hierarchy/groups, inheritance, archive locking, inspector/Explain This.
9. Visual Expansion Planner and eventual asymmetric canvas bounds.
10. Snapshots/history/diagnostic bundle/project export and remaining project-management polish.

## Validation contract for every development pass

Before handing off a build/ZIP or merging a development slice, update this roadmap with:
- what is actually implemented vs groundwork/planned
- changed save/network/config formats and migration behavior
- known bugs/risks/limitations
- build/test status and exact Minecraft/Fabric target
- current branch/build identity where relevant
- next recommended development slice

Do not describe groundwork as production-complete. Preserve old save compatibility wherever reasonably possible, and never rely on chat history as the only record of an important Ocean Canvas decision.

# OceanCanvas v0.2.26

OceanCanvas v0.2.26 promotes the recovered Minecraft 26.2 Fabric runtime to the release line.

## Highlights

- Restores the recovered runtime core and bounded campaign integration on Minecraft 26.2.
- Registers the bounded campaign runtime in the Fabric entrypoint.
- Preserves the proven single-chunk lifecycle through authoring, save/flush, lighting verification, exact restore, and final restart.
- Uses artifact-bound release evidence so CI-only/controller changes do not replay already-proven Minecraft runtime work.
- Keeps release validation fail-closed: the published JAR must reproduce the proven SHA-256 before the release is created.

## Proven release artifact

- Runtime: `26.2-core-v0.2.26`
- SHA-256: `211f6cbde2d5396b38f5ec121b45eac111cd27f76fd40ac9f4dafe30939500bd`
- Verified restarts: 10
- Final restart verified: yes
- Exact restore verified: yes

The downloadable JAR and `SHA256SUMS.txt` are attached to this GitHub release.

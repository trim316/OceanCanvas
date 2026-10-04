# OceanCanvas v0.2.28

Minecraft 26.2 / Fabric release candidate that combines the v0.2.27 command-driven full-Canvas flatten/restore path with the completed fail-closed recovery hardening through R1-87.

## Included
- Production `/oceancanvas flatten ERASE_CONFIGURED_CANVAS` path over the configured Canvas, one active chunk at a time.
- Exact block-state and block-entity preimage preservation for restore.
- Restart-safe immutable operation/target authority.
- Fail-closed handling for changed geometry, late block entities, corrupt/staged preimages, receipt failures, restore-save interruption, and retry ownership.
- Atomic preimage publication and bounded streamed digest verification.
- Path-free first-failure forensic context and bounded primitive stage counters.
- Existing guarded two-, four-, nine-, and sixteen-chunk evidence retained where implementation is unchanged.

## Release gate
Publication is intentionally blocked until the v0.2.28 evidence file records a passing exact full-Canvas command proof plus the final large-scale/overnight certification.

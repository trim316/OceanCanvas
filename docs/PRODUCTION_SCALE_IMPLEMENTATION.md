# Production-scale runtime implementation

This branch implements the production-scale OceanCanvas execution lane separately from the v0.2.28 exact-preimage recovery canary. The production lane is based on the v253.125.54 architecture: bounded multi-chunk admission, raw chunk authoring, decoupled lighting finalization, coarse durable checkpoints, and seed-bound native Restore.

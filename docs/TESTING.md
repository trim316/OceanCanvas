# Testing

## Unit tests

Pure Java tests cover:

- radius and full-width calculations
- boundary classification
- transition validation
- future Chunky and expansion calculations

## Release-blocking integration tests

Before a build is marked safe for long-term testing:

1. Generate disposable worlds with fixed seeds.
2. Verify the full protected interior contains no surface land.
3. Verify terrain outside the transition matches vanilla using control worlds.
4. Compare cave and ore distributions against control worlds.
5. Locate representative underground and ocean structures.
6. Remove Ocean Canvas and reopen the save.
7. Reinstall it and continue generating new chunks.
8. Expand the radius, prune a disposable expansion ring, and verify regeneration.

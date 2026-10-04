#!/usr/bin/env python3
"""Deterministic contract checks for the v0.2.28 publisher HOLD gate."""
from pathlib import Path

publisher = Path(".github/workflows/publish-v0.2.28.yml").read_text(encoding="utf-8")

required = [
    "release-gate:",
    "id: evidence",
    "echo 'ready=false' >> \"$GITHUB_OUTPUT\"",
    "No GitHub Release was created or modified.",
    "Large-scale/overnight certification remains required before publication.",
    "needs: release-gate",
    "if: needs.release-gate.outputs.ready == 'true'",
    "test \"$runtime\" = '26.2-core-v0.2.28'",
    "test \"$verdict\" = 'PASS'",
    "test \"$command_proof\" = 'true'",
    "test \"$scale_verdict\" = 'PASS'",
    "test \"$overnight\" = 'true'",
    "test \"$actual\" = \"$expected\"",
    "gh release create",
]
for needle in required:
    assert needle in publisher, f"publisher contract missing: {needle}"

gate_pos = publisher.index("release-gate:")
publish_pos = publisher.index("\n  publish:")
hold_pos = publisher.index("echo 'ready=false'")
release_pos = publisher.index("gh release create")
assert gate_pos < hold_pos < publish_pos < release_pos
assert "contents: write" in publisher
assert publisher.count("gh release create") == 1
assert publisher.count("gh release upload") == 1

# Missing final evidence is an expected HOLD, not permission to synthesize it.
assert "release-evidence/v0.2.28.properties" in publisher
assert "touch release-evidence" not in publisher
assert "mkdir -p release-evidence" not in publisher

print("V0_2_28_PUBLISHER_HOLD_CONTRACT_PASS")

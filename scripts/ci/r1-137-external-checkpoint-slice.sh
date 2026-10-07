#!/usr/bin/env bash
set -euo pipefail

: "${SLICE:?SLICE is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"
: "${EXPECTED_SOURCE_COMMIT:=033d1701e5b7008b71190562831a33647a7cae6d}"
: "${EXPECTED_BUILD:=v253.125.80}"
: "${CANDIDATE_ARTIFACT_ID:=11484526247}"

if ! [[ "$SLICE" =~ ^[0-9]+$ ]] || (( SLICE < 1 || SLICE > 20 )); then
  echo "SLICE must be 1..20" >&2
  exit 2
fi

target_pct=$(( SLICE * 5 ))
rm -rf production-candidate .r1-137-candidate
mkdir -p production-candidate .r1-137-candidate

echo "R1-137 slice=$SLICE target=${target_pct}%: retrieving immutable v80 candidate artifact ${CANDIDATE_ARTIFACT_ID}"
gh api "/repos/trim316/OceanCanvas/actions/artifacts/${CANDIDATE_ARTIFACT_ID}/zip" > .r1-137-candidate/candidate.zip
unzip -q .r1-137-candidate/candidate.zip -d .r1-137-candidate/unpacked

candidate_props="$(find .r1-137-candidate/unpacked -type f -name candidate.properties -print -quit)"
jar="$(find .r1-137-candidate/unpacked -type f -name 'oceancanvas-26.2-v253.125.80.jar' -print -quit)"
sha_file="$(find .r1-137-candidate/unpacked -type f -name JAR-SHA256.txt -print -quit)"
test -n "$candidate_props" && test -n "$jar" && test -n "$sha_file"
cp "$candidate_props" "$jar" "$sha_file" production-candidate/
grep -Fx "sourceCommit=${EXPECTED_SOURCE_COMMIT}" production-candidate/candidate.properties
grep -Fx "runtimeBuild=${EXPECTED_BUILD}" production-candidate/candidate.properties
expected_sha="$(awk '{print $1}' production-candidate/JAR-SHA256.txt)"
actual_sha="$(sha256sum production-candidate/oceancanvas-26.2-v253.125.80.jar | awk '{print $1}')"
test "$actual_sha" = "$expected_sha"

if (( SLICE == 1 )); then
  rm -rf production-runtime-evidence
  unset OC_RESUME || true
  unset OC_REQUIRE_CHECKPOINT_MANIFEST || true
else
  checkpoint="$(find incoming-checkpoint -type f -name '*.tgz' -print -quit)"
  test -n "$checkpoint"
  rm -rf production-runtime-evidence
  tar -xzf "$checkpoint"
  test -f production-runtime-evidence/checkpoint-manifest.json
  test -f production-runtime-evidence/server/world/level.dat
  export OC_RESUME=true
  export OC_REQUIRE_CHECKPOINT_MANIFEST=true
fi

export EXPECTED_BUILD EXPECTED_SOURCE_COMMIT
export OC_TEST_WIDTH=5000
export OC_NATURAL_BORDER=true
export OC_CHECKPOINT_SEGMENT=true
export OC_CHECKPOINT_PROGRESS_PCT=5
# Progress is the normal boundary; this is only a fail-safe if throughput collapses.
export OC_STAGE_SECONDS=7200

python3 scripts/ci/production-scale.py

test -f production-runtime-evidence/checkpoint-status.json
python3 - "$target_pct" <<'PY'
import json, pathlib, sys
expected = int(sys.argv[1])
p = pathlib.Path('production-runtime-evidence/checkpoint-status.json')
d = json.loads(p.read_text())
if not d.get('savedWorldPresent'):
    raise SystemExit(f'checkpoint has no saved world: {d}')
if d.get('completionObserved'):
    print(f'R1_137_SLICE_COMPLETE target={expected}% completion=true')
    pathlib.Path('.r1-137-completed').write_text('true\n')
else:
    if not d.get('segmentPending'):
        raise SystemExit(f'neither completion nor resumable checkpoint: {d}')
    if d.get('checkpointReason') != 'progress-boundary':
        raise SystemExit(f'5% slice ended for non-progress reason: {d}')
    boundary = int(d.get('checkpointBoundaryPercent') or 0)
    if boundary != expected:
        raise SystemExit(f'expected {expected}% boundary, got {boundary}%: {d}')
    if not pathlib.Path('production-runtime-evidence/checkpoint-manifest.json').is_file():
        raise SystemExit('verified checkpoint manifest missing')
    print(f'R1_137_EXTERNAL_CHECKPOINT_PASS boundary={boundary}% confirmed={d.get("checkpointConfirmedChunks")}')
    pathlib.Path('.r1-137-completed').write_text('false\n')
PY

tar -czf "r1-137-v80-5k-${target_pct}pct.tgz" production-runtime-evidence
completed="$(cat .r1-137-completed)"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "completed=$completed" >> "$GITHUB_OUTPUT"
  echo "target_pct=$target_pct" >> "$GITHUB_OUTPUT"
fi

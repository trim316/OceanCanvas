#!/usr/bin/env python3
"""Fail-closed Ocean Canvas runtime-log acceptance checker.

This does not replace in-game inspection. It turns the mod's existing completion,
lighting, throughput and shutdown telemetry into a reproducible first-pass gate for
500/5k/20k/overnight certification runs.

v253.121 adds two important evidence guarantees:
* --scope-latest-run ignores stale failures before the latest matching Pregen start.
  Concatenate restart logs in chronological order to preserve lifecycle evidence.
* --max-hours proves the wall-clock acceptance window when timestamps are available.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
from statistics import mean, median

PAIR_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s.]+)")
DONE_RE = re.compile(r"Pregen finished:\s+(\d+)/(\d+) target chunks confirmed complete; zero failed targets; terrain, lighting finalization, and Ocean Canvas-owned work are fully drained\.")
BUILD_RE = re.compile(r"\bbuild=(v?\d+(?:\.\d+){2,3})\b")
START_RE = re.compile(r"Started pregen centered at \([^)]*\):\s+(\d+) chunks for up to\s+(\d+) x (\d+) blocks")
STRUCT_START_RE = re.compile(r"PREGEN-ACCEPTANCE-START build=(v?\d+(?:\.\d+){2,3}) worldUuid=([0-9a-fA-F-]{36}) chunks=(\d+) widthBlocks=(\d+) centerX=(-?\d+) centerZ=(-?\d+)")
STRUCT_DONE_RE = re.compile(r"PREGEN-ACCEPTANCE-DONE build=(v?\d+(?:\.\d+){2,3}) worldUuid=([0-9a-fA-F-]{36}) chunks=(\d+)")
ADAPTIVE_RE = re.compile(r"Adaptive pregen:.*?completed~(\d+) chunks/s, forward~(\d+) chunks/s")

ABS_TIME_PATTERNS = (
    (re.compile(r"\[(\d{1,2}[A-Za-z]{3}\d{4}) (\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?\]"), "%d%b%Y %H:%M:%S"),
    (re.compile(r"\[(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:[.,](\d{1,6}))?\]"), "%Y-%m-%d %H:%M:%S"),
)
TOD_RE = re.compile(r"\[(\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,6}))?\]")


def pairs(line: str) -> dict[str, str]:
    return {k: v.rstrip(',;') for k, v in PAIR_RE.findall(line)}


def as_int(d: dict[str, str], key: str):
    try:
        return int(d[key])
    except (KeyError, ValueError):
        return None


def _micros(raw: str | None) -> int:
    return int(((raw or '') + '000000')[:6]) if raw else 0


def absolute_time(line: str) -> dt.datetime | None:
    for regex, fmt in ABS_TIME_PATTERNS:
        m = regex.search(line)
        if not m:
            continue
        try:
            base = dt.datetime.strptime(m.group(1) + ' ' + m.group(2), fmt)
            return base.replace(microsecond=_micros(m.group(3)))
        except ValueError:
            pass
    return None


def time_of_day_seconds(line: str) -> float | None:
    m = TOD_RE.search(line)
    if not m:
        return None
    h, minute, sec = map(int, m.group(1, 2, 3))
    return h * 3600 + minute * 60 + sec + _micros(m.group(4)) / 1_000_000.0


def elapsed_hours(start_line: str, end_line: str) -> float | None:
    a, b = absolute_time(start_line), absolute_time(end_line)
    if a is not None and b is not None:
        seconds = (b - a).total_seconds()
        return seconds / 3600.0 if seconds >= 0 else None
    a2, b2 = time_of_day_seconds(start_line), time_of_day_seconds(end_line)
    if a2 is None or b2 is None:
        return None
    if b2 < a2:
        b2 += 86400.0
    return (b2 - a2) / 3600.0


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def audit(args: argparse.Namespace) -> tuple[int, dict]:
    text = args.log.read_text(encoding='utf-8', errors='replace')
    all_lines = text.splitlines()
    scope_start = 0
    start_match = None
    structured_start_match = None
    start_line = None

    if args.scope_latest_run:
        structured_candidates = [(i, STRUCT_START_RE.search(line)) for i, line in enumerate(all_lines)]
        structured_candidates = [(i, m) for i, m in structured_candidates if m]
        plain_candidates = [(i, START_RE.search(line)) for i, line in enumerate(all_lines)]
        plain_candidates = [(i, m) for i, m in plain_candidates if m]
        if structured_candidates:
            scope_start, structured_start_match = structured_candidates[-1]
            start_line = all_lines[scope_start]
        elif plain_candidates:
            scope_start, start_match = plain_candidates[-1]
            start_line = all_lines[scope_start]
        elif args.require_start:
            scope_start = 0

    lines = all_lines[scope_start:]
    if structured_start_match is None:
        for line in lines:
            m = STRUCT_START_RE.search(line)
            if m:
                structured_start_match = m
                if start_line is None: start_line = line
                break
    if start_match is None:
        for line in lines:
            m = START_RE.search(line)
            if m:
                start_match = m
                if start_line is None: start_line = line
                break
    oc = [line for line in lines if 'Ocean Canvas' in line or '(Ocean Canvas)' in line]
    failures: list[str] = []
    warnings: list[str] = []

    if args.require_start and start_match is None and structured_start_match is None:
        failures.append('matching Pregen start line not found in selected run scope')
    if args.require_structured_evidence and structured_start_match is None:
        failures.append('structured PREGEN-ACCEPTANCE-START evidence not found')

    start_chunks = start_width = start_height = None
    start_center_x = start_center_z = None
    world_uuid = None
    structured_start_build = None
    if structured_start_match is not None:
        structured_start_build = structured_start_match.group(1)
        world_uuid = structured_start_match.group(2).lower()
        start_chunks = int(structured_start_match.group(3))
        start_width = start_height = int(structured_start_match.group(4))
        start_center_x = int(structured_start_match.group(5))
        start_center_z = int(structured_start_match.group(6))
    elif start_match is not None:
        start_chunks, start_width, start_height = map(int, start_match.groups())
    if start_chunks is not None:
        if args.expected_chunks is not None and start_chunks != args.expected_chunks:
            failures.append(f'started chunk total {start_chunks} != expected {args.expected_chunks}')
        if args.expected_size_blocks is not None and (start_width != args.expected_size_blocks or start_height != args.expected_size_blocks):
            failures.append(f'started block size {start_width}x{start_height} != expected {args.expected_size_blocks}x{args.expected_size_blocks}')
    if args.expected_center_x is not None or args.expected_center_z is not None:
        if structured_start_match is None:
            failures.append('expected center coordinates require structured PREGEN-ACCEPTANCE-START evidence')
        else:
            if args.expected_center_x is not None and start_center_x != args.expected_center_x:
                failures.append(f'start centerX {start_center_x} != expected {args.expected_center_x}')
            if args.expected_center_z is not None and start_center_z != args.expected_center_z:
                failures.append(f'start centerZ {start_center_z} != expected {args.expected_center_z}')

    builds = [m.group(1) for line in oc for m in BUILD_RE.finditer(line)]
    if args.expected_build:
        seen = sum(1 for b in builds if b == args.expected_build)
        if seen == 0:
            failures.append(f'expected build {args.expected_build} never appears in selected Ocean Canvas telemetry')
        foreign = sorted({b for b in builds if b != args.expected_build})
        if foreign:
            message='selected run also contains telemetry from other builds: ' + ', '.join(foreign)
            if args.require_structured_evidence:
                failures.append(message)
            else:
                warnings.append(message)
        if structured_start_build is not None and structured_start_build != args.expected_build:
            failures.append(f'structured start build {structured_start_build} != expected {args.expected_build}')

    structured_done_matches = [(i, STRUCT_DONE_RE.search(line)) for i, line in enumerate(lines)]
    structured_done_matches = [(i, m) for i, m in structured_done_matches if m]
    if args.require_structured_evidence and not structured_done_matches:
        failures.append('structured PREGEN-ACCEPTANCE-DONE evidence not found')
    if structured_done_matches:
        _, structured_done = structured_done_matches[-1]
        done_build, done_world_uuid, done_chunks = structured_done.group(1), structured_done.group(2).lower(), int(structured_done.group(3))
        if world_uuid is not None and done_world_uuid != world_uuid:
            failures.append(f'structured completion world UUID {done_world_uuid} != start world UUID {world_uuid}')
        if args.expected_build and done_build != args.expected_build:
            failures.append(f'structured completion build {done_build} != expected {args.expected_build}')
        if args.expected_chunks is not None and done_chunks != args.expected_chunks:
            failures.append(f'structured completion chunk total {done_chunks} != expected {args.expected_chunks}')

    done_matches = [(i, DONE_RE.search(line)) for i, line in enumerate(lines)]
    done_matches = [(i, m) for i, m in done_matches if m]
    if args.require_completion and not done_matches:
        failures.append('authoritative Pregen completion line not found')
    completed = None
    completion_line = None
    if done_matches:
        done_i, done = done_matches[-1]
        completion_line = lines[done_i]
        a, b = map(int, done.groups())
        completed = b
        if a != b:
            failures.append(f'completion line is internally inconsistent: {a}/{b}')
        if args.expected_chunks is not None and b != args.expected_chunks:
            failures.append(f'completed chunk total {b} != expected {args.expected_chunks}')
    elif args.expected_chunks is not None and args.require_completion:
        failures.append('expected chunk count was supplied but no authoritative completion line exists')

    duration_hours = None
    if start_line is not None and completion_line is not None:
        duration_hours = elapsed_hours(start_line, completion_line)
    if args.max_hours is not None:
        if duration_hours is None:
            failures.append('max-hours was supplied but run duration could not be derived from start/completion timestamps')
        elif duration_hours > args.max_hours:
            failures.append(f'run duration {duration_hours:.3f}h exceeds maximum {args.max_hours:.3f}h')

    backlog = [line for line in oc if 'FOREVER-WORLD-BACKLOG-REGRESSION' in line]
    risk = [line for line in oc if 'FOREVER-WORLD-THROUGHPUT-RISK' in line]
    if backlog:
        failures.append(f'net-confirmed backlog regression occurred {len(backlog)} time(s)')
    if risk and not args.allow_throughput_risk:
        failures.append(f'overnight throughput-risk alarm occurred {len(risk)} time(s)')
    elif risk:
        warnings.append(f'throughput-risk alarm occurred {len(risk)} time(s) but was allowed')

    savequit_failed = [line for line in oc if 'SAVE-QUIT-TICKET-DEACTIVATE' in line and 'FAILED' in line]
    if savequit_failed:
        failures.append(f'Save & Quit ticket deactivation failed {len(savequit_failed)} time(s)')

    post = [line for line in oc if 'POST-JOB-VISUAL-INTEGRITY build=' in line]
    if args.require_completion and not post:
        failures.append('post-job visual-integrity summary not found')
    if post:
        d = pairs(post[-1])
        for key in ('pendingLight', 'activeRelightTickets'):
            v = as_int(d, key)
            if v is None:
                failures.append(f'last visual-integrity summary lacks numeric {key}')
            elif v != 0:
                failures.append(f'last visual-integrity summary has {key}={v}, expected 0')
        for key in ('engineLightIncorrect', 'skyFieldBad', 'heightBad', 'physicalBad'):
            v = as_int(d, key)
            if v is not None and v != 0:
                failures.append(f'last visual-integrity summary has {key}={v}, expected 0')

    summaries = [line for line in oc if 'LIGHT-DIAG SUMMARY build=' in line]
    if summaries:
        d = pairs(summaries[-1])
        for key in ('relightFailed', 'relightTicketsActive'):
            v = as_int(d, key)
            if v is None:
                failures.append(f'last LIGHT-DIAG SUMMARY lacks numeric {key}')
            elif v != 0:
                failures.append(f'last LIGHT-DIAG SUMMARY has {key}={v}, expected 0')
    else:
        warnings.append('no LIGHT-DIAG SUMMARY found; rely on post-job integrity plus in-game inspection')

    oc_error = [line for line in oc if re.search(r'\bERROR\b', line)]
    if oc_error:
        failures.append(f'Ocean Canvas ERROR log entries in selected run: {len(oc_error)}')

    adaptive = [tuple(map(int, m.groups())) for line in oc if (m := ADAPTIVE_RE.search(line))]
    completed_rates = [a for a, _ in adaptive]
    forward_rates = [b for _, b in adaptive]

    result = {
        'schema': 2,
        'log': str(args.log),
        'log_sha256': sha256(args.log),
        'scope_latest_run': bool(args.scope_latest_run),
        'scope_start_line': scope_start + 1 if start_match is not None else None,
        'ocean_canvas_lines': len(oc),
        'builds_seen': sorted(set(builds)),
        'expected_build': args.expected_build,
        'world_uuid': world_uuid,
        'structured_start_seen': structured_start_match is not None,
        'structured_done_seen': bool(structured_done_matches),
        'start_chunks': start_chunks,
        'start_size_blocks': [start_width, start_height] if start_width is not None else None,
        'start_center_blocks': [start_center_x, start_center_z] if start_center_x is not None else None,
        'authoritative_completion_chunks': completed,
        'duration_hours': duration_hours,
        'max_hours': args.max_hours,
        'post_job_integrity_summaries': len(post),
        'light_diagnostic_summaries': len(summaries),
        'backlog_regressions': len(backlog),
        'throughput_risk_alarms': len(risk),
        'save_quit_ticket_failures': len(savequit_failed),
        'ocean_canvas_errors': len(oc_error),
        'adaptive_samples': len(adaptive),
        'adaptive_completed_cps_mean': mean(completed_rates) if completed_rates else None,
        'adaptive_completed_cps_median': median(completed_rates) if completed_rates else None,
        'adaptive_forward_cps_mean': mean(forward_rates) if forward_rates else None,
        'warnings': warnings,
        'failures': failures,
        'passed': not failures,
    }
    return (0 if not failures else 1), result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('log', type=Path)
    ap.add_argument('--expected-build', default=None, help='e.g. v253.121.0')
    ap.add_argument('--expected-chunks', type=int, default=None)
    ap.add_argument('--expected-size-blocks', type=int, default=None, help='full square width, not command radius')
    ap.add_argument('--expected-center-x', type=int, default=None)
    ap.add_argument('--expected-center-z', type=int, default=None)
    ap.add_argument('--require-completion', action='store_true', default=False)
    ap.add_argument('--require-start', action='store_true', default=False)
    ap.add_argument('--scope-latest-run', action='store_true', default=False,
                    help='audit only from the latest structured acceptance start (or legacy start line) onward')
    ap.add_argument('--require-structured-evidence', action='store_true', default=False,
                    help='require build/world-bound PREGEN-ACCEPTANCE-START/DONE markers')
    ap.add_argument('--max-hours', type=float, default=None)
    ap.add_argument('--allow-throughput-risk', action='store_true')
    ap.add_argument('--json-output', type=Path, default=None)
    args = ap.parse_args()

    rc, result = audit(args)
    print('Ocean Canvas runtime acceptance log audit')
    print(f'  log: {args.log}')
    print(f'  log sha256: {result["log_sha256"]}')
    print(f'  selected Ocean Canvas lines: {result["ocean_canvas_lines"]}')
    print(f'  builds seen: {", ".join(result["builds_seen"]) if result["builds_seen"] else "none"}')
    print(f'  world UUID: {result["world_uuid"] if result["world_uuid"] else "not observed"}')
    print(f'  authoritative completion: {result["authoritative_completion_chunks"] if result["authoritative_completion_chunks"] is not None else "not observed"}')
    if result['duration_hours'] is not None:
        print(f'  wall duration: {result["duration_hours"]:.3f}h')
    print(f'  post-job integrity summaries: {result["post_job_integrity_summaries"]}')
    print(f'  light diagnostic summaries: {result["light_diagnostic_summaries"]}')
    print(f'  backlog regressions: {result["backlog_regressions"]}; throughput-risk alarms: {result["throughput_risk_alarms"]}')
    if result['adaptive_samples']:
        print(f'  adaptive samples: {result["adaptive_samples"]}; completed median={result["adaptive_completed_cps_median"]:.1f} chunks/s')
    if result['warnings']:
        print('WARNINGS:')
        for x in result['warnings']:
            print('  - ' + x)
    if result['failures']:
        print('FAIL:')
        for x in result['failures']:
            print('  - ' + x)
    else:
        print('PASS: selected run telemetry satisfies the automated acceptance predicates.')
        print('NOTE: visual world inspection, restart/rejoin checks, and gameplay QA remain separate required evidence.')

    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    return rc


if __name__ == '__main__':
    raise SystemExit(main())

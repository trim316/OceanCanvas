#!/usr/bin/env python3
from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')
old_fmt = 'pressureRetryQueued={} action=read-only-terminal-debt-attribution",'
new_fmt = 'pressureRetryQueued={} pressureTail={} action=read-only-terminal-debt-attribution",'
old_args = 'session.retryLedger.ordinarySize(), session.retryLedger.quarantineSize(), session.retryLedger.pressureParkSize());'
new_args = ('session.retryLedger.ordinarySize(), session.retryLedger.quarantineSize(), session.retryLedger.pressureParkSize(), '
            'OceanCanvasPressureTailDiagnosticLine.format(tick, lightRecoverySession().pressureParkUntilTick, session.retryLedger));')

fmt_count = s.count(old_fmt)
args_count = s.count(old_args)
if fmt_count == 0 and new_fmt in s and new_args in s:
    print('R1-253 product wiring already present; validating idempotently')
    raise SystemExit(0)
if fmt_count != 1:
    raise SystemExit(f'expected exactly one LIGHT-TAIL format anchor, found {fmt_count}')
if args_count != 1:
    raise SystemExit(f'expected exactly one LIGHT-TAIL argument anchor, found {args_count}')

s = s.replace(old_fmt, new_fmt, 1).replace(old_args, new_args, 1)
p.write_text(s, encoding='utf-8')
print('R1-253 wired read-only authoritative pressure-tail diagnostic into LIGHT-TAIL-ATTRIBUTION')

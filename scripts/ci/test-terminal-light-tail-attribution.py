from pathlib import Path

src = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
required = [
    'logTerminalLightTailAttribution(world);',
    'if (active <= 0 || active > 8) return;',
    'session.pendingTicks.forEachKey(packed -> {',
    'session.relightResidencyLedger.contains(packed)',
    'lightRecoverySession().skyBackoffUntilTick.containsKey(packed)',
    'lightRecoverySession().pressureParkUntilTick.containsKey(packed)',
    'session.preLightPhysicalAuditState.containsKey(packed)',
    'session.strictSkyProofState.containsKey(packed)',
    'session.stage0FingerprintState.containsKey(packed)',
    'session.lightSweepState.containsKey(packed)',
    'LIGHT-TAIL-ATTRIBUTION',
    'action=read-only-terminal-debt-attribution',
]
for token in required:
    if token not in src:
        raise SystemExit(f'missing R1-207 invariant: {token}')

# Diagnostics must remain observational: the method body may query state and log,
# but may not schedule, wake, retire, release, acquire, mutate maps, or mark proof.
start = src.index('private static void logTerminalLightTailAttribution')
end = src.index('\n\t/**\n\t * Resends authoritative block data', start)
body = src[start:end]
for forbidden in ['.put(', '.remove(', '.clear(', 'scheduleLightSync(', 'wakePressure',
                  'releaseLightRelightResidencyTicket(', 'ensureLightRelightResidencyTicket(',
                  'markChunkLighting', 'incrementAndGet(', 'incrementCapped(']:
    if forbidden in body:
        raise SystemExit(f'R1-207 diagnostics became mutating: {forbidden}')

# The existing aggregate and strict debt stores remain intact; R1-207 does not
# change acceptance, timeout, residency, or finalizer budgets.
for invariant in ['LIGHT-DIAG AGGREGATE', 'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS',
                  'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX', 'skyBackoffUntilTick',
                  'pressureParkUntilTick']:
    if invariant not in src:
        raise SystemExit(f'pre-existing safety invariant missing: {invariant}')
print('R1-207 terminal light tail attribution regression PASS')

from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')
needle = '''\t\tlogCooperativeLightTelemetry("AGGREGATE");\n\t}\n\n\t/**\n\t * Resends authoritative block data'''
replacement = '''\t\tlogCooperativeLightTelemetry("AGGREGATE");
\t\tlogTerminalLightTailAttribution(world);
\t}

\t/** R1-207: when strict active debt has collapsed to a tiny terminal tail, expose
\t * the exact scheduler/proof ownership of every remaining key. This is read-only
\t * diagnostics: it neither wakes, retires, reprioritizes nor acquires residency.
\t * Keeping the threshold at eight prevents large campaigns from turning this into
\t * an O(n) log surface while making RED/GREEN exact-SHA tails comparable. */
\tprivate static void logTerminalLightTailAttribution(ServerLevel world) {
\t\tOceanCanvasLightFinalizerSession session = lightFinalizerSession();
\t\tint active = session.pendingTicks.size();
\t\tif (active <= 0 || active > 8) return;
\t\tlong tick = world.getGameTime();
\t\tsession.pendingTicks.forEachKey(packed -> {
\t\t\tint pass = session.pendingPasses.getOrDefault(packed, 0);
\t\t\tint delay = session.pendingTicks.get(packed);
\t\t\tint escalation = lightRecoverySession().verifyEscalations.get(packed);
\t\t\tboolean ticket = session.relightResidencyLedger.contains(packed);
\t\t\tboolean skyBackoff = lightRecoverySession().skyBackoffUntilTick.containsKey(packed);
\t\t\tboolean pressurePark = lightRecoverySession().pressureParkUntilTick.containsKey(packed);
\t\t\tboolean partialPhysical = session.preLightPhysicalAuditState.containsKey(packed)
\t\t\t\t\t|| session.prePublishPhysicalAuditState.containsKey(packed);
\t\t\tboolean partialProof = session.strictSkyProofState.containsKey(packed)
\t\t\t\t\t|| session.completedStrictSkyProof.containsKey(packed);
\t\t\tboolean partialFingerprint = session.stage0FingerprintState.containsKey(packed)
\t\t\t\t\t|| session.stage1FingerprintState.containsKey(packed)
\t\t\t\t\t|| session.postProofFingerprintState.containsKey(packed)
\t\t\t\t\t|| session.hardResetFingerprintState.containsKey(packed);
\t\t\tboolean partialSweep = session.lightSweepState.containsKey(packed);
\t\t\tOceanCanvas.LOGGER.info("(Ocean Canvas) LIGHT-TAIL-ATTRIBUTION build={} tick={} chunk={},{} activeTail={} pendingDelay={} pass={} escalation={} ticket={} skyBackoff={} pressurePark={} partialPhysical={} partialProof={} partialFingerprint={} partialSweep={} ordinaryRetryQueued={} quarantineRetryQueued={} pressureRetryQueued={} action=read-only-terminal-debt-attribution",
\t\t\t\t\tnet.oceancanvas.mod.OceanCanvas.VERSION, tick, ChunkPos.getX(packed), ChunkPos.getZ(packed), active,
\t\t\t\t\tdelay, pass, escalation, ticket, skyBackoff, pressurePark, partialPhysical, partialProof,
\t\t\t\t\tpartialFingerprint, partialSweep, session.retryLedger.ordinarySize(), session.retryLedger.quarantineSize(), session.retryLedger.pressureParkSize());
\t\t});
\t}

\t/**
\t * Resends authoritative block data'''
if s.count(replacement) == 1:
    pass
elif s.count(needle) != 1:
    raise SystemExit(f'expected one aggregate insertion point, found {s.count(needle)}')
else:
    s = s.replace(needle, replacement)
p.write_text(s, encoding='utf-8')
assert 'LIGHT-TAIL-ATTRIBUTION' in s
assert 'if (active <= 0 || active > 8) return;' in s
print('R1-207 terminal light tail attribution transform PASS')

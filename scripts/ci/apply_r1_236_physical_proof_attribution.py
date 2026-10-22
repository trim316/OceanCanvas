from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text(encoding='utf-8')

fast_old = '''        if (!lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)\n                && firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get()) != null) return false;'''
fast_new = '''        if (!lightFinalizerSession().preLightPhysicalAuditComplete.contains(packed)\n                && timedPhysicalProfileMismatch("FAST_CERT_FALLBACK", world, chunk, OceanCanvasConfig.get()) != null) return false;'''
post_old = '''\t\t\t\tPhysicalProfileMismatch physical = firstPhysicalProfileMismatch(world, chunk, OceanCanvasConfig.get());\n\t\t\t\tif (physical != null) {\n\t\t\t\t\tphysicalBad++;'''
post_new = '''\t\t\t\tPhysicalProfileMismatch physical = timedPhysicalProfileMismatch("POST_JOB_GATE", world, chunk, OceanCanvasConfig.get());\n\t\t\t\tif (physical != null) {\n\t\t\t\t\tphysicalBad++;'''
anchor = '''\tprivate static PhysicalProfileMismatch firstPhysicalProfileMismatch(\n\t\t\tServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {'''
helper = '''\tprivate static final java.util.concurrent.atomic.AtomicLong FAST_CERT_PHYSICAL_PROOF_CALLS = new java.util.concurrent.atomic.AtomicLong();\n\tprivate static final java.util.concurrent.atomic.AtomicLong FAST_CERT_PHYSICAL_PROOF_TOTAL_NS = new java.util.concurrent.atomic.AtomicLong();\n\tprivate static final java.util.concurrent.atomic.AtomicLong FAST_CERT_PHYSICAL_PROOF_MAX_NS = new java.util.concurrent.atomic.AtomicLong();\n\tprivate static final java.util.concurrent.atomic.AtomicLong POST_JOB_PHYSICAL_PROOF_CALLS = new java.util.concurrent.atomic.AtomicLong();\n\tprivate static final java.util.concurrent.atomic.AtomicLong POST_JOB_PHYSICAL_PROOF_TOTAL_NS = new java.util.concurrent.atomic.AtomicLong();\n\tprivate static final java.util.concurrent.atomic.AtomicLong POST_JOB_PHYSICAL_PROOF_MAX_NS = new java.util.concurrent.atomic.AtomicLong();\n\n\tprivate static PhysicalProfileMismatch timedPhysicalProfileMismatch(\n\t\t\tString path, ServerLevel world, LevelChunk chunk, OceanCanvasConfig config) {\n\t\tlong started = System.nanoTime();\n\t\tPhysicalProfileMismatch result = firstPhysicalProfileMismatch(world, chunk, config);\n\t\tlong elapsed = Math.max(0L, System.nanoTime() - started);\n\t\tjava.util.concurrent.atomic.AtomicLong calls = "FAST_CERT_FALLBACK".equals(path)\n\t\t\t\t? FAST_CERT_PHYSICAL_PROOF_CALLS : POST_JOB_PHYSICAL_PROOF_CALLS;\n\t\tjava.util.concurrent.atomic.AtomicLong total = "FAST_CERT_FALLBACK".equals(path)\n\t\t\t\t? FAST_CERT_PHYSICAL_PROOF_TOTAL_NS : POST_JOB_PHYSICAL_PROOF_TOTAL_NS;\n\t\tjava.util.concurrent.atomic.AtomicLong max = "FAST_CERT_FALLBACK".equals(path)\n\t\t\t\t? FAST_CERT_PHYSICAL_PROOF_MAX_NS : POST_JOB_PHYSICAL_PROOF_MAX_NS;\n\t\tlong count = calls.incrementAndGet();\n\t\ttotal.addAndGet(elapsed);\n\t\tmax.accumulateAndGet(elapsed, Math::max);\n\t\t// Diagnostic-only cadence: bounded logging, no proof result or authority change.\n\t\tif ((count & 255L) == 0L || elapsed >= 25_000_000L) {\n\t\t\tOceanCanvas.LOGGER.info("(Ocean Canvas) PHYSICAL_PROOF_ATTRIBUTION path={} calls={} totalMs={} maxMs={} lastMs={} mismatch={}",\n\t\t\t\t\tpath, count, total.get() / 1_000_000L, max.get() / 1_000_000L, elapsed / 1_000_000L, result != null);\n\t\t}\n\t\treturn result;\n\t}\n\n''' + anchor

for old, new, label in [(fast_old, fast_new, 'fast-cert fallback'), (post_old, post_new, 'post-job gate')]:
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'expected exactly one {label} callsite, found {n}')
    s = s.replace(old, new, 1)

if s.count(anchor) != 1:
    raise SystemExit(f'expected exactly one physical proof method anchor, found {s.count(anchor)}')
s = s.replace(anchor, helper, 1)

# Fail closed if attribution accidentally changes the authoritative proof body/call count.
if s.count('firstPhysicalProfileMismatch(') < 6:
    raise SystemExit('unexpected loss of authoritative physical proof callsites')
if s.count('timedPhysicalProfileMismatch("FAST_CERT_FALLBACK"') != 1:
    raise SystemExit('fast-cert attribution not unique')
if s.count('timedPhysicalProfileMismatch("POST_JOB_GATE"') != 1:
    raise SystemExit('post-job attribution not unique')

p.write_text(s, encoding='utf-8')
print('R1-236 attribution transform applied: exact proof retained; two synchronous paths timed independently')

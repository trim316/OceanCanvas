from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\t\tboolean finalizerWallBudgetExhausted = System.nanoTime() - finalizerTickStartedNanos >= LIGHT_FINALIZER_TICK_TIME_BUDGET_NS;
\t\t\tif (activeWorkBudget <= 0 || finalizerWallBudgetExhausted) {
\t\t\t\tif (finalizerWallBudgetExhausted) lightTelemetrySession().LIGHT_DIAG_FINALIZER_WALL_BUDGET_DEFERRALS.incrementAndGet();
\t\t\t\telse lightTelemetrySession().LIGHT_DIAG_FINALIZER_ACTIVE_WORK_DEFERRALS.incrementAndGet();
\t\t\t\tint deferTicks = 1 + Math.floorMod((int)(packed ^ (packed >>> 32)), 4);
\t\t\t\tlightFinalizerSession().pendingTicks.put(packed, deferTicks);
\t\t\t\tlightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
\t\t\t\tcontinue;
\t\t\t}'''
new = '''\t\t\tboolean finalizerWallBudgetExhausted = System.nanoTime() - finalizerTickStartedNanos >= LIGHT_FINALIZER_TICK_TIME_BUDGET_NS;
\t\t\tif (finalizerWallBudgetExhausted) {
\t\t\t\t// The work window is only a sampled view; pendingTicks still owns every
\t\t\t\t// unprocessed item. Once the unchanged 8 ms wall budget is exhausted,
\t\t\t\t// stop this tick instead of rewriting every remaining due item with a
\t\t\t\t// synthetic 1-4 tick delay. That rewrite was pure scheduler churn and
\t\t\t\t// could starve already-due proof work without adding any safety bound.
\t\t\t\tlightTelemetrySession().LIGHT_DIAG_FINALIZER_WALL_BUDGET_DEFERRALS.incrementAndGet();
\t\t\t\tlightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
\t\t\t\tbreak;
\t\t\t}
\t\t\tif (activeWorkBudget <= 0) {
\t\t\t\tlightTelemetrySession().LIGHT_DIAG_FINALIZER_ACTIVE_WORK_DEFERRALS.incrementAndGet();
\t\t\t\tint deferTicks = 1 + Math.floorMod((int)(packed ^ (packed >>> 32)), 4);
\t\t\t\tlightFinalizerSession().pendingTicks.put(packed, deferTicks);
\t\t\t\tlightTelemetrySession().LIGHT_DIAG_WORK_BUDGET_DEFERRALS.incrementAndGet();
\t\t\t\tcontinue;
\t\t\t}'''
if new not in s:
    if s.count(old) != 1:
        raise SystemExit(f'expected exactly one repair anchor, found {s.count(old)}')
    p.write_text(s.replace(old, new))

wf = Path('.github/workflows/r1-145-exact-throughput.yml')
w = wf.read_text()
anchor = '      - work/r1-171-finalizer-budget-attribution\n'
addition = anchor + '      - work/r1-172-stop-wall-budget-reschedule-churn\n'
if addition not in w:
    if w.count(anchor) != 1:
        raise SystemExit('exact-throughput trigger anchor mismatch')
    wf.write_text(w.replace(anchor, addition))

from pathlib import Path
p=Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s=p.read_text()
old='\t\tint delayedPressureWork = lightFinalizerSession().pendingTicks.countValuesAtLeast(1);'
new='\t\tint delayedPressureWork = lightFinalizerSession().pendingTicks.sumKeys(\n\t\t\t\tpacked -> lightFinalizerSession().pendingTicks.get(packed) > 0 ? 1 : 0);'
if s.count(old)!=1:
    raise SystemExit(f'expected exactly one unsupported delayed-count call, found {s.count(old)}')
if 'pendingTicks.sumKeys(' not in s:
    raise SystemExit('required bounded primitive-map sumKeys API is absent from exact source')
p.write_text(s.replace(old,new))

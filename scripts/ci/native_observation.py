"""Bounded passive native evidence; no GC request, budget or heap change."""
from pathlib import Path
from host_sampler_phase import next_sampler_phase

def native_observation_flags(out):
    out=Path(out);phase=next_sampler_phase(out)
    prefix=out/f'native-phase-{phase}'
    return [f'-XX:StartFlightRecording=filename={prefix}.jfr,settings=profile,maxsize=64m,maxage=2m,dumponexit=true',
            f'-Xlog:gc*:file={prefix}-gc.log:time,uptime,level,tags:filecount=2,filesize=8M']

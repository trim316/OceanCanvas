"""Bounded passive native evidence; no GC request, budget or heap change."""
import os
from pathlib import Path
from host_sampler_phase import next_sampler_phase

def native_observation_flags(out):
    out=Path(out);phase=next_sampler_phase(out)
    prefix=out/f'native-phase-{phase}'
    probe = [f'-Doceancanvas.fixture.finalizerStageProbe={(out / "finalizer-stages.jsonl").resolve()}'] if os.environ.get('OC_FINALIZER_STAGE_PROBE') == 'true' else []
    if os.environ.get('OC_NATIVE_READ_PROBE') == 'true':
        probe.append(f'-Doceancanvas.fixture.nativeReadProbe={(out / "native-read-events.jsonl").resolve()}')
    return probe + [f'-XX:StartFlightRecording=filename={prefix}.jfr,settings=profile,maxsize=64m,maxage=2m,dumponexit=true',
            f'-Xlog:gc*:file={prefix}-gc.log:time,uptime,level,tags:filecount=2,filesize=8M']

"""Bounded numeric-only Linux host attribution. Never reads command lines or environment."""
import argparse,json,time,os,subprocess
from pathlib import Path

MAX_RECORDS=2000
MAX_BYTES=8*1024*1024
MAX_READ=4096

def numbers(text):
    out={}
    for line in text.splitlines():
        parts=line.split()
        if not parts:continue
        # Only known numeric counters; arbitrary names/text are never emitted.
        if parts[0] in {'usage_usec','user_usec','system_usec','nr_periods','nr_throttled','throttled_usec','oom','oom_kill','pgfault','pgmajfault','ctxt','procs_running','procs_blocked'} and len(parts)==2:
            try:out[parts[0]]=int(parts[1])
            except ValueError:pass
        elif parts[0] in {'some','full'}:
            vals={}
            for field in parts[1:]:
                k,sep,v=field.partition('=')
                if sep and k in {'avg10','avg60','avg300','total'}:
                    try:vals[k]=float(v) if k!='total' else int(v)
                    except ValueError:pass
            out[parts[0]]=vals
        elif parts[0]=='cpu':
            try:out['cpu']=[int(v) for v in parts[1:11]]
            except ValueError:pass
    return out

def read_fixed(path):
    try:
        with open(path,'r',encoding='ascii',errors='replace') as f:return f.read(MAX_READ)
    except (OSError,ValueError):return None

def process_identity(pid,read=read_fixed):
    text=read(f'/proc/{pid}/stat')
    if text is None:return None
    # comm may contain spaces and parentheses. Do not retain or emit it.
    fields=text[text.rfind(')')+2:].split()
    try:return int(fields[19]) # starttime is field22, state begins field3
    except (ValueError,IndexError):return None

def snapshot(pid,read=read_fixed):
    result={}
    for label,path in [('cpu','/proc/stat'),('cpuPressure','/proc/pressure/cpu'),('ioPressure','/proc/pressure/io'),('memoryPressure','/proc/pressure/memory'),('cgroupCpu','/sys/fs/cgroup/cpu.stat'),('cgroupMemory','/sys/fs/cgroup/memory.events')]:
        raw=read(path);result[label]=None if raw is None else numbers(raw)
    raw=read(f'/proc/{pid}/schedstat')
    try:result['javaSchedstat']=None if raw is None else [int(v) for v in raw.split()[:3]]
    except ValueError:result['javaSchedstat']=None
    return result

def collect(pid,out,stop,*,read=read_fixed,mono=time.monotonic_ns,wall=time.time_ns,sleep=time.sleep,limit=MAX_RECORDS,max_bytes=MAX_BYTES):
    identity=process_identity(pid,read)
    if identity is None:raise RuntimeError('Java numeric process identity unavailable')
    count=0;used=0;previous=None
    with open(out,'xb') as f:
        while count<limit:
            now=mono();same_process=process_identity(pid,read)==identity;terminal=stop.exists() or not same_process or count==limit-1
            row={'monotonicNs':now,'wallNs':wall(),'elapsedSincePreviousNs':None if previous is None else now-previous,'pid':pid,'startTicks':identity,'terminal':terminal,'counters':snapshot(pid,read) if same_process else None}
            data=(json.dumps(row,separators=(',',':'))+'\n').encode('ascii')
            if used+len(data)>max_bytes:break
            f.write(data);f.flush();used+=len(data);count+=1;previous=now
            if terminal:break
            sleep(1.0)
    return count,used

def stop_sampler(process,stop):
    stop.touch()
    try:process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=5)
    return process.returncode

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--pid',type=int,required=True);a.add_argument('--output',type=Path,required=True);a.add_argument('--stop',type=Path,required=True);v=a.parse_args()
    collect(v.pid,v.output,v.stop)

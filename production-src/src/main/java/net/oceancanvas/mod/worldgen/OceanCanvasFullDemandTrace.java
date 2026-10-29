package net.oceancanvas.mod.worldgen;
/** Read-only diagnostics; never consulted for ownership, admission or completion. */
final class OceanCanvasFullDemandTrace {
 enum Phase { QUEUED, CALLING_NATIVE, WAITING_NATIVE, NATIVE_MAIN_ENTERED, NATIVE_MAIN_RETURNED, NATIVE_FUTURE_DONE, NATIVE_COMPLETED, HANDOFF_QUEUED, HANDOFF_RUNNING, TERMINAL }
 private Phase phase=Phase.QUEUED;
 private final long[] stamps=new long[Phase.values().length];
 OceanCanvasFullDemandTrace(long now){stamps[0]=now;}
 synchronized void advance(Phase next,long now){
  if(next.ordinal()<=phase.ordinal())return;
  stamps[next.ordinal()]=now;phase=next;
 }
 synchronized String snapshot(long now){
  StringBuilder s=new StringBuilder("phase=").append(phase).append(" ageNs=").append(span(now,stamps[0])).append(" phaseAgeNs=").append(span(now,stamps[phase.ordinal()]));
  for(Phase p:Phase.values())if(p.ordinal()<=phase.ordinal()&&stamps[p.ordinal()]!=0)s.append(' ').append(p).append("AtNs=").append(stamps[p.ordinal()]);
  return s.toString();
 }
 private static long span(long now,long then){long d=now-then;return d<0?-1:d;}
}

package net.oceancanvas.mod.worldgen;
import java.util.function.BooleanSupplier;import java.util.function.LongSupplier;import java.util.function.Consumer;
/** Invocation-local attribution only; no value is consulted by terrain policy. */
final class OceanCanvasCarveTrace {
 enum Phase { ENTRY, SETUP, LAVA, COLUMNS, FINALIZE }
 private final long started;private long last;private Phase phase=Phase.ENTRY;private final long[] elapsed=new long[Phase.values().length];
 boolean owned,physicalTarget,physicalTracked,repair,visible,whole,batch,cooperative,eligibilityReached;
 int relocated=-1,revalidations=-1;boolean returned,completed;
 OceanCanvasCarveTrace(long now){started=last=now;}
 void phase(Phase next,long now){elapsed[phase.ordinal()]+=span(now,last);last=now;phase=next;}
 long total(long now){return span(now,started);}
 boolean run(BooleanSupplier body,LongSupplier clock,Consumer<OceanCanvasCarveTrace> sink){
  try{boolean result=body.getAsBoolean();returned=true;completed=result;return result;}
  finally{long now=clock.getAsLong();elapsed[phase.ordinal()]+=span(now,last);last=now;sink.accept(this);}
 }
 String snapshot(){return "phase="+phase+" totalNs="+total(last)+" phaseNs="+java.util.Arrays.toString(elapsed)+" returned="+returned+" completed="+completed+" owned="+owned+" physicalTarget="+physicalTarget+" physicalTracked="+physicalTracked+" repair="+repair+" visible="+visible+" whole="+whole+" eligibilityReached="+eligibilityReached+" batch="+batch+" cooperative="+cooperative+" relocated="+relocated+" globalRevalidations="+revalidations;}
 long finishedTotal(){return total(last);}
 private static long span(long now,long before){long n=now-before;return n<0?0:n;}
}

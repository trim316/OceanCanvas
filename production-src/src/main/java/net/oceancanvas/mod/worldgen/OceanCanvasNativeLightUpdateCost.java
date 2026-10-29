package net.oceancanvas.mod.worldgen;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
/** Cumulative attribution only. Allocation is sampled every 64 calls; no enablement or GC requests. */
public final class OceanCanvasNativeLightUpdateCost {
 public static final OceanCanvasNativeLightUpdateCost GLOBAL=new OceanCanvasNativeLightUpdateCost(System::nanoTime,OceanCanvasNativeLightUpdateCost::allocated);
 private final LongSupplier clock,allocation;
 private final AtomicLong calls=new AtomicLong(),tasks=new AtomicLong(),zero=new AtomicLong(),small=new AtomicLong(),medium=new AtomicLong(),full=new AtomicLong(),nanos=new AtomicLong(),maxNanos=new AtomicLong(),samples=new AtomicLong(),bytes=new AtomicLong(),unavailable=new AtomicLong();
 OceanCanvasNativeLightUpdateCost(LongSupplier clock,LongSupplier allocation){this.clock=clock;this.allocation=allocation;}
 private static long allocated(){var bean=java.lang.management.ManagementFactory.getThreadMXBean();return bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported() && b.isThreadAllocatedMemoryEnabled()?b.getCurrentThreadAllocatedBytes():-1;}
 private static long read(LongSupplier s){try{return s.getAsLong();}catch(RuntimeException failure){return -1;}}
 public void observe(int queued,Runnable original){
  long call=calls.incrementAndGet();int batch=Math.min(Math.max(0,queued),1000);tasks.addAndGet(batch);
  (batch==0?zero:batch<128?small:batch<1000?medium:full).incrementAndGet();
  boolean sample=(call&63)==1;long start=read(clock),before=sample?read(allocation):-1;
  try{original.run();}finally{
   long end=read(clock);if(start>=0&&end>=start){long d=end-start;nanos.addAndGet(d);maxNanos.accumulateAndGet(d,Math::max);}
   if(sample){long after=read(allocation);if(before>=0&&after>=before){samples.incrementAndGet();bytes.addAndGet(after-before);}else unavailable.incrementAndGet();}
  }
 }
 public String snapshot(){return "calls="+calls.get()+" processedTaskUpperBound="+tasks.get()+" zero="+zero.get()+" batch1to127="+small.get()+" batch128to999="+medium.get()+" batch1000="+full.get()+" totalNs="+nanos.get()+" maxNs="+maxNanos.get()+" allocationSamples="+samples.get()+" sampledBytes="+bytes.get()+" allocationUnavailable="+unavailable.get();}
}

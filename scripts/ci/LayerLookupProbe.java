package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.util.concurrent.atomic.AtomicInteger;
public final class LayerLookupProbe implements ModInitializer {
 public static volatile boolean recording;
 public static final AtomicInteger ids=new AtomicInteger();
 private int ticks;
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{if(++ticks>=2000)recording=true;});}
 public static final class Sample {
  public final int id=ids.incrementAndGet();
  public final LayerCacheModel cache16=new LayerCacheModel(16),cache64=new LayerCacheModel(64);
  public long calls,nativeHits,nulls,invalidations;public volatile boolean done;public boolean headHit;
  public void invalidate(){if(done)return;invalidations++;cache16.invalidate();cache64.invalidate();}
  public void record(long key,boolean present){if(done||id>6)return;   calls++;if(headHit)nativeHits++;if(!present)nulls++;cache16.access(key);cache64.access(key);
   if(calls==1000000){done=true;System.out.println("LAYER_LOOKUP_SAMPLE id="+id+" calls="+calls+" nativeHits="+nativeHits+" nulls="+nulls+" invalidations="+invalidations+" direct16Hits="+cache16.hits+" direct64Hits="+cache64.hits+" diagnosticOnly=true");}
  }
 }
}

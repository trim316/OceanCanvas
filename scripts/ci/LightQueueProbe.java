package net.oceancanvas.test;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
public final class LightQueueProbe implements ModInitializer {
 public static final boolean RETAIN=Boolean.getBoolean("oceancanvas.test.retainLightQueues");
 public static final AtomicInteger marked=new AtomicInteger();
 public static final LongAdder grows=new LongAdder(),shrinks=new LongAdder(),bytes=new LongAdder();
 private int ticks;
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{if(++ticks%200==0)
  System.out.println("LIGHT_QUEUE_SAMPLE ticks="+ticks+" retain="+RETAIN+" marked="+marked.get()+" grows="+grows.sum()+" shrinks="+shrinks.sum()+" resizedArrayBytes="+bytes.sum()+" diagnosticOnly=true");});}
 public static void resize(int oldLength,int newLength){if(newLength>oldLength)grows.increment();else shrinks.increment();bytes.add(8L*newLength);}
}

package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;
public final class CarvePressureProbe implements ModInitializer {
 private static final Map<Long,Integer> calls=new HashMap<>();
 private static long count,total,max,repeat,watchdog,holds,checks,over70,over100,over250;
 private static long tick,lastCarveTick=-100,lastCarveNs;
 private static final ThreadLocal<Long> started=ThreadLocal.withInitial(()->0L);
 public static void begin(){started.set(System.nanoTime());}
 public static void end(ServerLevel world,LevelChunk chunk,boolean ok){
  long elapsed=System.nanoTime()-started.get();count++;total+=elapsed;max=Math.max(max,elapsed);
  long key=((long)chunk.getPos().x()<<32)^(chunk.getPos().z()&0xffffffffL);
  int n=calls.merge(key,1,Integer::sum);if(n>1)repeat++;
  if(elapsed>=70000000L)over70++;if(elapsed>=100000000L)over100++;if(elapsed>=250000000L)over250++;
  lastCarveTick=tick;lastCarveNs=elapsed;
  if(count<=12||(n>1&&repeat<=100)||elapsed>=250000000L)System.out.println("CARVE_PRESSURE_CALL tick="+tick+" chunk="+chunk.getPos()+" ordinal="+n+" elapsedNs="+elapsed+" returned="+ok);
 }
 public static void watchdog(){watchdog++;}
 public static void pressure(boolean held){checks++;if(held)holds++;if(held&&tick-lastCarveTick<=1&&lastCarveNs>=70000000L)System.out.println("CARVE_PRESSURE_AFTER_CARVE tick="+tick+" elapsedNs="+lastCarveNs);}
 private static void dump(){System.out.println("CARVE_PRESSURE_SNAPSHOT tick="+tick+" calls="+count+" unique="+calls.size()+" repeat="+repeat+" totalNs="+total+" maxNs="+max+" over70="+over70+" over100="+over100+" over250="+over250+" watchdogAttempts="+watchdog+" pressureChecks="+checks+" held="+holds+" verdict=HOLD");}
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{tick++;if(tick==1||tick%200==0)dump();});}
}

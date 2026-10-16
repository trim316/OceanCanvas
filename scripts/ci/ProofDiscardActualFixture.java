package net.oceancanvas.test;
import java.lang.reflect.*;
public class ProofDiscardActualFixture {
 static Object construct(Class<?> c,Class<?>[] types,Object...args)throws Exception{var k=c.getDeclaredConstructor(types);k.setAccessible(true);return k.newInstance(args);}
 static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 public static void main(String[] args)throws Exception{
  Class<?> sc=Class.forName("net.oceancanvas.mod.worldgen.OceanCanvasLightFinalizerSession");
  Object s=construct(sc,new Class<?>[0]);
  Class<?> stateClass=Class.forName("net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener$LightSweepState");
  Object state=construct(stateClass,new Class<?>[]{net.minecraft.world.level.chunk.LevelChunk.class,boolean.class},null,true);
  set(state,"checks",321L);set(state,"columnCursor",123);
  Object map=field(s,"lightSweepState");Method put=map.getClass().getDeclaredMethod("put",long.class,Object.class);put.setAccessible(true);put.invoke(map,9L,state);
  var counter=new ProofDiscardCounter();counter.observe(s,9,()->"actual-state");counter.observe(s,10,()->{throw new AssertionError();});
  if(counter.discardedChecks!=321||counter.partialSweeps!=1||!field(state,"checks").equals(321L))throw new AssertionError();
  System.out.println("PROOF_DISCARD_ACTUAL_FIXTURE_PASS actual-session/map/state read-only");
 }
}

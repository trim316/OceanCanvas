package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
/** Read-only session observation in an explicitly copied diagnostic world. */
public final class TailLedgerProbe implements ModInitializer {
 private int ticks;
 private boolean failed, done;
 private static final long TARGET=124554051598L;
 private static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception {
  for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try {
   Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
  }catch(NoSuchMethodException ignored){}
  return "unsupported";
 }
 private static Object session(Class<?> c,String name)throws Exception {Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(null);}
 private static String describe(Object value)throws Exception {
  if(value==null)return "null";
  if(value instanceof java.util.concurrent.atomic.AtomicLong a)return Long.toString(a.get());
  if(value instanceof Number||value instanceof Boolean)return value.toString();
  var parts=new ArrayList<String>();
  for(String name:new String[]{"size","ordinarySize","quarantineSize","pressureParkSize","genericDormantSize","activeCount"}) {
   Object v=invoke(value,name,new Class<?>[0]);if(!v.equals("unsupported"))parts.add(name+"="+v);
  }
  for(String name:new String[]{"get","contains","containsKey"}) {
   Object v=invoke(value,name,new Class<?>[]{long.class},TARGET);if(!Objects.equals(v,"unsupported"))parts.add(name+"="+v);
  }
  if(value instanceof Map<?,?> map){parts.add("size="+map.size());parts.add("target="+map.get(TARGET));}
  return String.join(",",parts);
 }
 private static void dump(String label,Object value)throws Exception {
  for(Field f:value.getClass().getDeclaredFields()) {
   if(Modifier.isStatic(f.getModifiers())||f.getType().isArray())continue;
   f.setAccessible(true);String text=describe(f.get(value));
   if(!text.isEmpty())System.out.println("TAIL_LEDGER "+label+"."+f.getName()+" "+text);
  }
 }
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{
  if(done||failed||!Files.exists(Path.of("run-tail-ledger-probe.flag")))return;
  ticks++;
  try {
   if(ticks==1||ticks%200==0){
    var world=server.overworld();var live=world.getChunkSource().getChunkNow(14,29);
    System.out.println("TAIL_LEDGER_SNAPSHOT ticks="+ticks+" gameTime="+world.getGameTime()+" target=14,29 loaded="+(live!=null));
    Class<?> c=Class.forName("net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener");
    for(String name:new String[]{"lightFinalizerSession","lightRecoverySession","pregenSession"})dump(name,session(c,name));
   }
   if(ticks>=3600){done=true;System.out.println("TAIL_LEDGER_DONE verdict=HOLD freshWorld=false");}
  }catch(Throwable error){failed=true;error.printStackTrace();System.out.println("TAIL_LEDGER_FAILED "+error);}
 });}
}

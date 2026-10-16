package net.oceancanvas.test;
import java.lang.reflect.*;
import java.util.*;
/** Read-only aggregation of partial sweep state discarded by a native reset. */
public final class ProofDiscardCounter {
 public long resets,partialSweeps,discardedChecks,pathSamples;
 public final Map<String,Long> paths=new TreeMap<>();
 private static final ClassValue<Map<String,Field>> fields=new ClassValue<>(){
  protected Map<String,Field> computeValue(Class<?> c){Map<String,Field> m=new HashMap<>();for(Field f:c.getDeclaredFields()){f.setAccessible(true);m.put(f.getName(),f);}return m;}
 };
 private static final ClassValue<Method> getters=new ClassValue<>(){
  protected Method computeValue(Class<?> c){try{Method m=c.getDeclaredMethod("get",long.class);m.setAccessible(true);return m;}catch(Exception e){throw new IllegalStateException(e);}}
 };
 private static Object field(Object o,String n)throws Exception{Field f=fields.get(o.getClass()).get(n);if(f==null)throw new NoSuchFieldException(n);return f.get(o);}
 public void observe(Object session,long key,java.util.function.Supplier<String> path)throws Exception{
  resets++;
  Object map=field(session,"lightSweepState");Method get=getters.get(map.getClass());
  Object state=get.invoke(map,key);if(state==null)return;
  long checks=((Number)field(state,"checks")).longValue();int cursor=((Number)field(state,"columnCursor")).intValue();
  if(cursor>=256)throw new IllegalStateException("Completed sweep observed in reset state");
  partialSweeps++;discardedChecks+=checks;
  if(pathSamples<1024){pathSamples++;paths.merge(path.get(),1L,Long::sum);}
 }
}

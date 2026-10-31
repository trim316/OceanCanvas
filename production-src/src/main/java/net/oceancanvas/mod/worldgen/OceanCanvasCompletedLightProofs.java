package net.oceancanvas.mod.worldgen;
import java.util.*;
/** Resident completed-proof watches only: no world, chunk, I/O, tickets or callback queue. */
final class OceanCanvasCompletedLightProofs {
 private record Entry(OceanCanvasNativeLightProofRevisions.Guard guard,long stamp){}
 private final Map<Long,Entry> entries=new HashMap<>();
 void retain(long chunk,OceanCanvasNativeLightProofRevisions.Guard guard,long stamp){
  var previous=entries.put(chunk,new Entry(guard,stamp));if(previous!=null&&previous.guard!=guard)previous.guard.close();
 }
 /** Main-thread caller dirties durable metadata/rearms existing work only when this returns true. */
 boolean takeInvalidated(long chunk){var entry=entries.get(chunk);if(entry==null||!entry.guard.changedSince(entry.stamp))return false;entries.remove(chunk);entry.guard.close();return true;}
 boolean remove(long chunk){var entry=entries.remove(chunk);if(entry==null)return false;entry.guard.close();return true;}
 long[] chunks(){long[] keys=new long[entries.size()];int i=0;for(long key:entries.keySet())keys[i++]=key;return keys;}
 void clear(){for(var e:entries.values())e.guard.close();entries.clear();}
 int size(){return entries.size();}
}

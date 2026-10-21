package net.oceancanvas.mod.worldgen;
import java.util.HashMap;
/** Session-only publication fence; restart requires fresh native settlement observation. */
final class OceanCanvasFallingSettlementLedger {
 private final HashMap<Long,Long> zeroSince=new HashMap<>();
 boolean observe(long chunk,long tick,boolean entityTicking,boolean falling){
  if(!entityTicking||falling){zeroSince.remove(chunk);return false;}
  Long start=zeroSince.get(chunk);
  if(start==null||tick<start){zeroSince.put(chunk,tick);return false;}
  return tick-start>=40L;
 }
 void remove(long chunk){zeroSince.remove(chunk);}
 void clear(){zeroSince.clear();}
}

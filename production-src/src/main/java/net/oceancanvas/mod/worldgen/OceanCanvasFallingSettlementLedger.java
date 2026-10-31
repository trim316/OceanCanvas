package net.oceancanvas.mod.worldgen;
import java.util.HashMap;
import java.util.HashSet;
/** Session-only publication fence; restart requires fresh native settlement observation. */
final class OceanCanvasFallingSettlementLedger {
 private final HashMap<Long,Long> zeroSince=new HashMap<>();
 private final HashSet<Long> waiting=new HashSet<>();
 boolean waitingForSettlement(long chunk){return waiting.contains(chunk);}
 boolean observe(long chunk,long tick,boolean entityTicking,boolean falling){
  waiting.add(chunk);
  if(!entityTicking||falling){zeroSince.remove(chunk);return false;}
  Long start=zeroSince.get(chunk);
  if(start==null||tick<start){zeroSince.put(chunk,tick);return false;}
  if(tick-start<40L)return false;
  waiting.remove(chunk);return true;
 }
 void remove(long chunk){zeroSince.remove(chunk);waiting.remove(chunk);}
 void clear(){zeroSince.clear();waiting.clear();}
}

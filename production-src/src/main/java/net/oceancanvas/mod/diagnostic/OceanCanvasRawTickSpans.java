package net.oceancanvas.mod.diagnostic;
import jdk.jfr.*;
/** Raw hook spans for attribution only; no governor reads this state. Server-thread confined. */
public final class OceanCanvasRawTickSpans {
 public static final OceanCanvasRawTickSpans GLOBAL=new OceanCanvasRawTickSpans(8192);
 private final int limit;private long sequence,start=-1,flattenerStart=-1,flattenerEnd=-1,observed,emitted,dropped;
 public OceanCanvasRawTickSpans(int limit){this.limit=Math.max(0,limit);}
 public void begin(long sequence,long now){this.sequence=sequence;start=now;flattenerStart=flattenerEnd=-1;}
 public void flattener(long from,long to){if(start>=0&&from>=start&&to>=from){flattenerStart=from;flattenerEnd=to;}}
 public record Span(long sequence,long startNs,long endNs,long rawNs,long beforeFlattenerNs,long flattenerNs,long afterFlattenerNs){}
 public Span end(long now){long from=start;start=-1;if(from<0||now<from)return null;long raw=now-from;if(raw<=50_000_000L)return null;observed++;if(emitted>=limit){dropped++;return null;}emitted++;boolean valid=flattenerStart>=from&&flattenerEnd>=flattenerStart&&flattenerEnd<=now;return new Span(sequence,from,now,raw,valid?flattenerStart-from:-1,valid?flattenerEnd-flattenerStart:-1,valid?now-flattenerEnd:-1);}
 public void finishAndRecord(long now){Span s=end(now);if(s==null)return;var e=new RawTickEvent();if(!e.isEnabled())return;e.sequence=s.sequence();e.startNs=s.startNs();e.endNs=s.endNs();e.rawNs=s.rawNs();e.beforeFlattenerNs=s.beforeFlattenerNs();e.flattenerNs=s.flattenerNs();e.afterFlattenerNs=s.afterFlattenerNs();e.commit();}
 public String snapshot(){return "slowObserved="+observed+" admitted="+emitted+" dropped="+dropped+" eventLimit="+limit;}
 public void reset(){sequence=observed=emitted=dropped=0;start=flattenerStart=flattenerEnd=-1;}
 @Name("oceancanvas.RawTickSpan") @Label("Ocean Canvas raw slow tick hook span") @Category("Ocean Canvas") @StackTrace(false) @Enabled(true)
 static final class RawTickEvent extends Event {public long sequence,startNs,endNs,rawNs,beforeFlattenerNs,flattenerNs,afterFlattenerNs;}
}

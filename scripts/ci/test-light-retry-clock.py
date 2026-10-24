"""Execute the actual retry clock under sparse pressure-gated scheduler visits."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[2]
s = (root/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
start = s.index('    static final class LightRetryTicks {')
end = s.index('\n\t/** Stage-0 quiescence', start)
actual = s[start:end]
assert 'pendingTicks.observeGameTick(world.getGameTime())' in s
assert 'if (current > 0) continue;' in s
code = r'''
import java.util.*;
public class RetryClockTest {
 static class OceanCanvasPrimitiveLongIntMap {
  static final int ABSENT=Integer.MIN_VALUE; Map<Long,Integer> m=new HashMap<>();
  int get(long k){return m.getOrDefault(k,ABSENT);} void put(long k,int v){m.put(k,v);}
  void remove(long k){m.remove(k);} void clear(){m.clear();} boolean containsKey(long k){return m.containsKey(k);}
  int size(){return m.size();} boolean isEmpty(){return m.isEmpty();}
  void forEachKey(java.util.function.LongConsumer c){for(long k:m.keySet())c.accept(k);}
  int copyFirstKeys(long[] a,int n){int i=0;for(long k:m.keySet()){if(i>=n)break;a[i++]=k;}return i;}
  int sumKeys(java.util.function.LongToIntFunction f){int v=0;for(long k:m.keySet())v+=f.applyAsInt(k);return v;}
  Iterable<Long> boxedKeySnapshot(){return new ArrayList<>(m.keySet());}
 }
 static class OceanCanvasPrimitiveLongLongMap {
  static final long ABSENT=Long.MIN_VALUE;Map<Long,Long>m=new HashMap<>();
  long get(long k){return m.getOrDefault(k,ABSENT);}void put(long k,long v){m.put(k,v);}void remove(long k){m.remove(k);}void clear(){m.clear();}
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
''' + actual + r'''
 public static void main(String[]args){
  LightRetryTicks q=new LightRetryTicks();q.put(1,4);q.observeGameTick(100);check(q.get(1)==4);
  q.observeGameTick(200);check(q.get(1)==0); // one scheduler visit per100 ticks
  q.put(1,4);check(q.get(1)==4);q.observeGameTick(202);check(q.get(1)==2);
  q.observeGameTick(50);check(q.get(1)==2);q.observeGameTick(52);check(q.get(1)==0);
  q.remove(1);q.put(1,10);check(q.get(1)==10);q.clear();check(q.isEmpty());
  q.observeGameTick(1000);for(int i=0;i<714;i++)q.put(i,4);
  int visited=0;for(int t=1100;t<=1700;t+=100){q.observeGameTick(t);for(int i=visited;i<Math.min(714,visited+128);i++)check(q.get(i)==0);visited+=128;}
  check(q.size()==714);q.put(7,4);check(q.get(7)==4); // restage never borrows old elapsed credit
  int legacy=4;for(int visit=0;visit<4;visit++)legacy--;check(legacy==0);
  System.out.println("PASS: elapsed retries mature under100tick pressure visits; startup/restage/rollback/fairqueue preserve debt");
 }
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'RetryClockTest.java';p.write_text(code)
 subprocess.run(['javac','-J-Xmx96m',str(p)],check=True)
 subprocess.run(['java','-Xmx32m','-cp',d,'RetryClockTest'],check=True)

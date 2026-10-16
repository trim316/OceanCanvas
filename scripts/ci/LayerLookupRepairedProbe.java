import net.oceancanvas.test.*;
import java.util.concurrent.*;
public class LayerLookupRepairedProbe {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void onThread(Runnable r)throws Exception{FutureTask<Void> f=new FutureTask<>(r,null);Thread t=new Thread(f);t.start();f.get(10,TimeUnit.SECONDS);t.join();}
 public static void main(String[] args)throws Exception{
  LayerLookupProbe.ids.set(0);
  LayerLookupAccess g=new LayerLookupAccess();LayerLookupProbe.Sample s=new LayerLookupProbe.Sample();
  for(int i=0;i<10;i++)onThread(()->{
   check(g.enter());check(g.enter());s.invalidate();g.leave();check(g.held());
   for(int j=0;j<100000;j++){s.headHit=(j%2==0);s.record(7,j%4!=0);}g.leave();check(!g.held());
  });
  check(!g.failed&&s.done&&s.calls==1000000&&s.nativeHits==500000&&s.nulls==250000&&s.invalidations==10);
  check(s.cache16.hits==999990&&s.cache64.hits==999990);
  s.record(8,false);s.invalidate();check(s.calls==1000000&&s.invalidations==10);
  for(int i=0;i<6;i++)new LayerLookupProbe.Sample();
  LayerLookupProbe.Sample excluded=new LayerLookupProbe.Sample();excluded.record(9,true);check(excluded.calls==0);
  LayerLookupAccess overlap=new LayerLookupAccess();CountDownLatch claimed=new CountDownLatch(1),release=new CountDownLatch(1);
  FutureTask<Void> owner=new FutureTask<>(()->{check(overlap.enter());claimed.countDown();check(release.await(10,TimeUnit.SECONDS));overlap.leave();return null;});
  Thread t=new Thread(owner);t.start();check(claimed.await(10,TimeUnit.SECONDS));check(!overlap.enter());check(overlap.failed);overlap.leave();release.countDown();owner.get(10,TimeUnit.SECONDS);t.join();check(!overlap.enter());
  System.out.println("PASS actual Sample million cap, six-map exclusion, exact counters/models, migration, nested invalidation, nonblocking overlap; worker failures propagated");
 }
}

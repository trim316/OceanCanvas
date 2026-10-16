package net.oceancanvas.test;
import java.util.*;
public class ProofDiscardCounterFixture {
 static class M {final Map<Long,Object> m=new HashMap<>(); Object get(long k){return m.get(k);}}
 static class S {final M lightSweepState=new M();}
 static class Cursor {long checks;int columnCursor;Cursor(long c,int p){checks=c;columnCursor=p;}}
 public static void main(String[] args)throws Exception{
  var c=new ProofDiscardCounter();var a=new S();var b=new S();a.lightSweepState.m.put(7L,new Cursor(123,40));b.lightSweepState.m.put(7L,new Cursor(19,2));
  c.observe(a,8,()->{throw new AssertionError("empty reset captured path");});
  c.observe(a,7,()->"physical");c.observe(b,7,()->"decoration");
  if(c.resets!=3||c.partialSweeps!=2||c.discardedChecks!=142)throw new AssertionError();
  if(((Cursor)a.lightSweepState.m.get(7L)).checks!=123||a.lightSweepState.m.size()!=1)throw new AssertionError("mutation");
  a.lightSweepState.m.remove(7L);c.observe(a,7,()->{throw new AssertionError("successful removal mistaken for discard");});
  for(int i=0;i<2000;i++)c.observe(b,7,()->"bounded");
  if(c.pathSamples!=1024||c.paths.values().stream().mapToLong(Long::longValue).sum()!=1024)throw new AssertionError("cap");
  System.out.println("PROOF_DISCARD_COUNTER_FIXTURE_PASS empty/partial/successful-removal/dimension-isolation/read-only/path-cap");
 }
}

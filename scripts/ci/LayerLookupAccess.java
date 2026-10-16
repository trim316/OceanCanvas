package net.oceancanvas.test;
import java.util.concurrent.atomic.AtomicLong;
/** Nonblocking observation guard; never controls native execution. */
public final class LayerLookupAccess {
 private final AtomicLong owner=new AtomicLong();
 private int depth;
 public volatile boolean failed;
 public boolean enter(){
  if(failed)return false;
  long thread=Thread.currentThread().threadId();
  if(owner.get()==thread){depth++;return true;}
  if(!owner.compareAndSet(0,thread)){failed=true;return false;}
  depth=1;
  return true;
 }
 public void leave(){
  if(owner.get()!=Thread.currentThread().threadId())return;
  if(--depth==0)owner.set(0);
 }
 public boolean held(){return owner.get()==Thread.currentThread().threadId();}
}

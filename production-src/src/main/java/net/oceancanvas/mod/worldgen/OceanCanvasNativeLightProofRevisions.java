package net.oceancanvas.mod.worldgen;
import java.util.*;import it.unimi.dsi.fastutil.longs.LongSet;import net.minecraft.core.SectionPos;import net.minecraft.world.level.ChunkPos;
/** Revision metadata only. Native map publication is never cancelled, delayed by I/O, or replaced. */
public final class OceanCanvasNativeLightProofRevisions {
 private OceanCanvasNativeLightProofRevisions(){}
 private static final Map<Object,Tracker> TRACKERS=new WeakHashMap<>();
 private static final class Tracker {int publishing;final Map<Long,Set<Guard>> watchers=new HashMap<>();}
 public static final class Guard implements AutoCloseable {
  private final Tracker tracker;private final long[] chunks;private long revision;private boolean closed;
  private Guard(Tracker t,long[] c){tracker=t;chunks=c.clone();}
  public long stamp(){synchronized(tracker){return closed||tracker.publishing!=0?-1:revision;}}
  public boolean unchanged(long stamp){synchronized(tracker){return stamp>=0&&!closed&&tracker.publishing==0&&revision==stamp;}}
  /** Detect relevant publication even while an unrelated/native swap remains busy. */
  public boolean changedSince(long stamp){synchronized(tracker){return stamp<0||closed||revision!=stamp;}}
  /** The action may change certificate metadata only; no native lighting calls, world loads, I/O or waits. */
  public boolean commitIfUnchanged(long stamp,Runnable metadataCommit){synchronized(tracker){if(stamp<0||closed||tracker.publishing!=0||revision!=stamp)return false;metadataCommit.run();return true;}}
  @Override public void close(){synchronized(tracker){if(closed)return;closed=true;revision++;for(long c:chunks){var s=tracker.watchers.get(c);if(s!=null){s.remove(this);if(s.isEmpty())tracker.watchers.remove(c);}}}}
 }
 public static Guard watch(Object nativeChunkSource,long[] chunks){
  Objects.requireNonNull(nativeChunkSource);Tracker t;synchronized(TRACKERS){t=TRACKERS.computeIfAbsent(nativeChunkSource,k->new Tracker());}
  synchronized(t){var g=new Guard(t,chunks);for(long c:chunks)t.watchers.computeIfAbsent(c,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(g);return g;}
 }
 public static final class Publication implements AutoCloseable {
  private final Tracker tracker;private boolean closed;
  private Publication(Tracker t){tracker=t;}
  @Override public void close(){if(tracker==null||closed)return;synchronized(tracker){if(closed)return;closed=true;tracker.publishing--;if(tracker.publishing<0)throw new IllegalStateException("unbalanced native publication");}}
 }
 /** Runs BEFORE visibleSectionData publication, not in the later queued onLightUpdate callback. */
 public static Publication begin(Object source,LongSet changed,LongSet affected){
  Tracker t;synchronized(TRACKERS){t=TRACKERS.computeIfAbsent(source,k->new Tracker());}
  synchronized(t){t.publishing++;if(!t.watchers.isEmpty()){invalidate(t,changed);invalidate(t,affected);}return new Publication(t);}
 }
 private static void invalidate(Tracker t,LongSet sections){for(var i=sections.iterator();i.hasNext();){long s=i.nextLong();var guards=t.watchers.get(ChunkPos.pack(SectionPos.x(s),SectionPos.z(s)));if(guards!=null)for(Guard g:guards)g.revision++;}}
}

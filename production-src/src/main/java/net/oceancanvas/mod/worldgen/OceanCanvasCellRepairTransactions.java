package net.oceancanvas.mod.worldgen;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Nonblocking exact-receipt write/consume barriers. Only the server caller mutates this map.
 * Worker tasks hold bytes and paths, never a world/chunk/server. Cold consume markers defeat
 * replay even when SavedData still contains a pending record after a crash. */
public final class OceanCanvasCellRepairTransactions {
 public enum State { PENDING, AUTHORIZED, CONSUMED, FAILED }
 private record Key(String world,String operation,long pos,String evidence,String origin) { }
 private record Frame(CompletableFuture<State> future, boolean consuming) { }
 private final Map<Key,Frame> frames=new HashMap<>();
 private final Executor executor;
 public OceanCanvasCellRepairTransactions(Executor executor){this.executor=executor;}
 private static Key key(OceanCanvasCellRepairReceipts.Receipt r){return new Key(r.world(),r.operation(),r.position(),r.evidence(),r.origin());}
 private static byte[] payload(OceanCanvasCellRepairReceipts.Receipt r,boolean consumed){
  var value=new OceanCanvasCellRepairReceipts.Receipt(r.world(),r.operation(),r.position(),r.evidence(),r.origin(),consumed);
  return OceanCanvasCellRepairReceipts.RECEIPT_CODEC.encodeStart(JsonOps.INSTANCE,value).getOrThrow().toString().getBytes(StandardCharsets.UTF_8);
 }
 private static Path path(Path root,OceanCanvasCellRepairReceipts.Receipt r,byte[] bytes){return root.toAbsolutePath().normalize().resolve(r.world()).resolve(r.operation()).resolve(OceanCanvasReceiptFileBarrier.sha(bytes)+".json");}
 private static State state(Frame f){
  if(!f.future().isDone())return State.PENDING;
  if(!OceanCanvasReceiptFileBarrier.ready(f.future()))return State.FAILED;
  return f.future().getNow(State.FAILED);
 }
 public State authority(Path root,OceanCanvasCellRepairReceipts.Receipt r){
  Key k=key(r);Frame f=frames.get(k);if(f!=null)return state(f);
  byte[] pending=payload(r,false), consumed=payload(r,true);
  var future=CompletableFuture.supplyAsync(()->{
   try{
    if(r.origin().equals("OPERATOR_PREVIOUS_SOURCE_WATER")) {
     Path proof=root.toAbsolutePath().normalize().resolve(r.world()).resolve(r.operation()).resolve(r.evidence()+".json");
     byte[] bytes;
     try(var in=Files.newInputStream(proof,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(OceanCanvasOperatorCellEvidence.MAX_BYTES+1);}
     if(!OceanCanvasOperatorCellEvidence.decode(bytes,r.evidence()).matches(r))throw new java.io.IOException("Operator evidence scope mismatch");
    }
    Path marker=path(root,r,consumed);
    if(Files.exists(marker)){OceanCanvasReceiptFileBarrier.verify(marker,consumed);return State.CONSUMED;}
    OceanCanvasReceiptFileBarrier.writeImmutable(path(root,r,pending),pending);
    // Do not authorize against a concurrently published consume marker.
    if(Files.exists(marker)){OceanCanvasReceiptFileBarrier.verify(marker,consumed);return State.CONSUMED;}
    return r.consumed()?State.FAILED:State.AUTHORIZED;
   }catch(Exception e){throw new CompletionException(e);}
  },executor);
  f=new Frame(future,false);frames.put(k,f);return state(f);
 }
 public State consume(Path root,OceanCanvasCellRepairReceipts.Receipt r){
  Key k=key(r);State current=authority(root,r);Frame existing=frames.get(k);
  if(current==State.CONSUMED||current==State.FAILED||current==State.PENDING)return current;
  if(existing.consuming())return state(existing);
  byte[] marker=payload(r,true);
  var f=CompletableFuture.supplyAsync(()->{try{OceanCanvasReceiptFileBarrier.writeImmutable(path(root,r,marker),marker);return State.CONSUMED;}catch(Exception e){throw new CompletionException(e);}},executor);
  frames.put(k,new Frame(f,true));return state(frames.get(k));
 }
}

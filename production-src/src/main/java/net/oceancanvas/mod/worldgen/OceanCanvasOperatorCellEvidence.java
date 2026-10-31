package net.oceancanvas.mod.worldgen;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** An explicit operator attestation, not automatic trust in historical seals or arbitrary stone.
 * The permission-checked confirm command authorizes this exact digest and cell. Parent artifact
 * digests are retained for independent external validation; this parser does not claim to reprove
 * an entire historical archive inside the server tick. */
public record OceanCanvasOperatorCellEvidence(String world,String operation,long position,
        String parentArchive,String failedArchive) {
 public static final int MAX_BYTES=8192;
 public static void requireDigest(String digest){if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact lowercase SHA256 required");}
 public static OceanCanvasOperatorCellEvidence decode(byte[] bytes,String digest){
  requireDigest(digest);if(bytes.length>MAX_BYTES||!OceanCanvasReceiptFileBarrier.sha(bytes).equals(digest))throw new IllegalArgumentException("Evidence digest/size mismatch");
  JsonObject o=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
  if(!o.keySet().equals(Set.of("world","operation","position","previous","expected","replacement","parentArchive","failedArchive")))throw new IllegalArgumentException("Unexpected evidence fields");
  String world=o.get("world").getAsString(),op=o.get("operation").getAsString();UUID.fromString(world);UUID.fromString(op);
  if(!o.get("previous").getAsString().equals("minecraft:water[level=0]")||!o.get("expected").getAsString().equals("minecraft:stone")||!o.get("replacement").getAsString().equals("minecraft:water[level=0]"))throw new IllegalArgumentException("Only attested source-water to stone recovery is supported");
  String parent=o.get("parentArchive").getAsString(),failed=o.get("failedArchive").getAsString();requireDigest(parent);requireDigest(failed);
  if(parent.equals(failed))throw new IllegalArgumentException("Distinct before/failed archive lineage required");
  String rawPosition=o.get("position").getAsString();long position=Long.parseLong(rawPosition);
  if(!Long.toString(position).equals(rawPosition))throw new IllegalArgumentException("Canonical integer cell position required");
  return new OceanCanvasOperatorCellEvidence(world,op,position,parent,failed);
 }
 public static byte[] read(Path imports,String digest)throws IOException {
  requireDigest(digest);Path file=imports.resolve(digest+".json");
  try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
   byte[] bytes=in.readNBytes(MAX_BYTES+1);decode(bytes,digest);return bytes;
  }
 }
 public OceanCanvasCellRepairReceipts.Receipt receipt(String digest){requireDigest(digest);return new OceanCanvasCellRepairReceipts.Receipt(world,operation,position,digest,"OPERATOR_PREVIOUS_SOURCE_WATER",false);}
 public boolean matches(OceanCanvasCellRepairReceipts.Receipt r){return world.equals(r.world())&&operation.equals(r.operation())&&position==r.position();}
}

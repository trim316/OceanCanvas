package net.oceancanvas.mod.worldgen;
import java.util.function.IntSupplier;
/** Mirrors native save ordering; no work, wait or I/O is performed by this coordinator itself. */
public final class OceanCanvasLightProofSaveBoundary {
 private OceanCanvasLightProofSaveBoundary(){}
 public static void run(Runnable beforeMetadata,Runnable originalSave,IntSupplier afterChunks,Runnable persistLateDirty){
  beforeMetadata.run();Throwable nativeFailure=null;
  try{originalSave.run();}catch(RuntimeException|Error e){nativeFailure=e;throw e;}
  finally{try{if(afterChunks.getAsInt()>0)persistLateDirty.run();}catch(RuntimeException|Error e){if(nativeFailure!=null)nativeFailure.addSuppressed(e);else throw e;}}
 }
}

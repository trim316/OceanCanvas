package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasReceiptFileBarrierTest {
 @TempDir Path root;
 static final String W="514cea34-7311-4a1e-bd2a-a0884a3fa686", O="ff43fd55-e271-47ae-94ea-53b21c137d27";
 @Test void actualFileBytesVerifiedBeforeReady() throws Exception {var f=new OceanCanvasReceiptFileBarrier(Runnable::run).publish(root,W,O,"receipt".getBytes());assertTrue(OceanCanvasReceiptFileBarrier.ready(f));assertArrayEquals("receipt".getBytes(),Files.readAllBytes(f.get()));}
 @Test void writeExceptionCannotMasqueradeAsSuccessfulBarrier(){var f=new OceanCanvasReceiptFileBarrier(Runnable::run,(p,b)->{throw new java.io.IOException("disk fault");}).publish(root,W,O,new byte[]{1});assertFalse(OceanCanvasReceiptFileBarrier.ready(f));assertTrue(f.isCompletedExceptionally());}
 @Test void swallowedWriterFailureDetectedByReadback(){var f=new OceanCanvasReceiptFileBarrier(Runnable::run,(p,b)->{}).publish(root,W,O,new byte[]{1});assertFalse(OceanCanvasReceiptFileBarrier.ready(f));}
 @Test void payloadMutationWhileQueuedCannotAlterAuthority() throws Exception {List<Runnable> q=new ArrayList<>();byte[] bytes={1};var f=new OceanCanvasReceiptFileBarrier(q::add).publish(root,W,O,bytes);bytes[0]=2;assertFalse(OceanCanvasReceiptFileBarrier.ready(f));q.getFirst().run();assertArrayEquals(new byte[]{1},Files.readAllBytes(f.get()));}
 @Test void corruptedExistingRecordIsRefusedNotOverwritten() throws Exception {var b=new OceanCanvasReceiptFileBarrier(Runnable::run);var p=b.publish(root,W,O,new byte[]{1}).get();Files.write(p,new byte[]{9});var f=b.publish(root,W,O,new byte[]{1});assertFalse(OceanCanvasReceiptFileBarrier.ready(f));assertArrayEquals(new byte[]{9},Files.readAllBytes(p));}
 @Test void repeatedIdenticalReceiptReusesImmutableRecord() throws Exception {var b=new OceanCanvasReceiptFileBarrier(Runnable::run);assertEquals(b.publish(root,W,O,new byte[]{1}).get(),b.publish(root,W,O,new byte[]{1}).get());}
 @Test void cancelledOrPendingAuthorityCannotPass(){var f=new CompletableFuture<>();assertFalse(OceanCanvasReceiptFileBarrier.ready(f));f.cancel(false);assertFalse(OceanCanvasReceiptFileBarrier.ready(f));assertFalse(OceanCanvasReceiptFileBarrier.ready(null));}
 @Test void malformedWorldCannotEscapeRoot(){assertThrows(IllegalArgumentException.class,()->new OceanCanvasReceiptFileBarrier(Runnable::run).publish(root,"../escape",O,new byte[]{1}));}
}

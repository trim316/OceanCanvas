package net.oceancanvas.mod.performance;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
/** A cached positive count can only delay readiness. Zero is always from a fresh native scan. */
public final class OceanCanvasPositiveStartupPoll {
    private Object identity;
    private long completedAt;
    private int positive;
    private long scans, holds;
    public int poll(Object counter, LongSupplier clock, IntSupplier nativeScan) {
        long now=clock.getAsLong();
        long elapsed=now-completedAt;
        if(identity==counter && positive>0 && elapsed>=0L && elapsed<50_000_000L) {holds++;return positive;}
        positive=0;identity=null; // A native exception must never leave an eligible cached answer.
        int result=nativeScan.getAsInt();scans++;
        if(result>0) {completedAt=clock.getAsLong();identity=counter;positive=result;}
        return result;
    }
    public long scans(){return scans;}
    public long holds(){return holds;}
}

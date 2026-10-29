package net.oceancanvas.mod.diagnostic;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.function.BiFunction;
/** Bounded one-thread diagnostic; never scans all threads or locked heap objects. */
final class OceanCanvasThreadSnapshot {
    static final int MAX_DEPTH = 64;
    static final int MAX_CHARS = 32768;
    static String capture(long id) {
        return capture(id, (target, depth) -> ManagementFactory.getThreadMXBean().getThreadInfo(target, depth));
    }
    static String capture(long id, BiFunction<Long,Integer,ThreadInfo> read) {
        if (id <= 0) return "SERVER-THREAD-SNAPSHOT unavailable=missing-server-thread-id\n";
        long started=System.nanoTime();
        try {
            ThreadInfo info=read.apply(id,MAX_DEPTH);
            if(info==null) return "SERVER-THREAD-SNAPSHOT unavailable=thread-ended targetId="+id+"\n";
            StringBuilder out=new StringBuilder(4096);
            out.append("SERVER-THREAD-SNAPSHOT bounded=true locks=false targetId=").append(id)
                    .append(" state=").append(info.getThreadState()).append('\n');
            StackTraceElement[] frames=info.getStackTrace();
            for(int i=0;i<Math.min(frames.length,MAX_DEPTH);i++) {
                String frame="\tat "+frames[i]+"\n";
                if(out.length()+frame.length()>MAX_CHARS-128) {out.append("character-bound-reached\n");break;}
                out.append(frame);
            }
            out.append("captureNs=").append(System.nanoTime()-started).append('\n');
            return out.toString();
        } catch(Throwable failure) {
            return "SERVER-THREAD-SNAPSHOT unavailable="+failure.getClass().getSimpleName()+" targetId="+id+"\n";
        }
    }
}

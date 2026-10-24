package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in loopback-only read API for OC-F256. It serves immutable snapshots copied on the Minecraft
 * server thread. Socket workers never call Minecraft APIs and accept GET only.
 *
 * <p>v253.79 bounds both dimensions of the local transport: request lines are capped before
 * allocation and connection handling uses a small bounded worker pool. A client that opens a
 * slow socket can therefore delay only one worker for the configured socket timeout; it cannot
 * stall the accept loop or build an unbounded executor queue.</p>
 */
public final class OceanCanvasReadOnlyApi {
    private static final int MAX_REQUEST_LINE_BYTES = 4096;
    private static final int SOCKET_TIMEOUT_MS = 2000;
    private static final int MAX_WORKERS = 4;
    private static final int MAX_QUEUED_CONNECTIONS = 16;

    private static final AtomicReference<String> STATE = new AtomicReference<>("{\"status\":\"unpublished\"}\n");
    private static final AtomicReference<String> PROJECTS = new AtomicReference<>("[]\n");
    private static final AtomicReference<String> HEALTH = new AtomicReference<>("{\"status\":\"unpublished\"}\n");
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private static volatile ServerSocket socket;
    private static volatile Thread acceptThread;
    private static volatile ThreadPoolExecutor workers;

    private OceanCanvasReadOnlyApi() { }

    public static synchronized int start(ServerLevel world) throws IOException {
        publish(world);
        if (socket != null && !socket.isClosed()) return socket.getLocalPort();

        ServerSocket s = new ServerSocket(0, MAX_QUEUED_CONNECTIONS, InetAddress.getLoopbackAddress());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, MAX_WORKERS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_CONNECTIONS),
                r -> {
                    Thread t = new Thread(r, "OceanCanvas-readonly-api-worker-" + WORKER_IDS.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);

        socket = s;
        workers = pool;
        Thread t = new Thread(() -> serve(s, pool), "OceanCanvas-readonly-api-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
        return s.getLocalPort();
    }

    public static synchronized void stop() {
        ServerSocket s = socket;
        ThreadPoolExecutor pool = workers;
        socket = null;
        acceptThread = null;
        workers = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    public static synchronized boolean running() { return socket != null && !socket.isClosed(); }
    public static synchronized int port() { return running() ? socket.getLocalPort() : -1; }

    /** Must be called on the Minecraft server thread. */
    public static void publish(ServerLevel world) {
        var work = OceanCanvasWorkspaceData.get(world);
        var plan = OceanCanvasPlanningData.get(world);
        var prog = OceanCanvasProgramData.get(world);
        var health = OceanCanvasWorldHealthScorecard.snapshot(world);

        StringBuilder p = new StringBuilder("[");
        int n = 0;
        for (var v : work.projects()) {
            if (n++ > 0) p.append(',');
            p.append("{\"id\":\"").append(js(v.id())).append("\",\"name\":\"").append(js(v.name()))
                    .append("\",\"status\":\"").append(js(v.status())).append("\",\"region\":\"")
                    .append(js(v.regionName())).append("\"}");
        }
        p.append("]\n");
        PROJECTS.set(p.toString());

        StringBuilder h = new StringBuilder("{\"attention\":").append(health.attentionCount())
                .append(",\"unverified\":").append(health.unverifiedCount()).append(",\"components\":[");
        n = 0;
        for (var c : health.components()) {
            if (n++ > 0) h.append(',');
            h.append("{\"id\":\"").append(js(c.id())).append("\",\"state\":\"").append(c.state())
                    .append("\",\"detail\":\"").append(js(c.detail())).append("\"}");
        }
        h.append("]}\n");
        HEALTH.set(h.toString());
        STATE.set("{\"schema\":1,\"world\":\"" + js(world.dimension().toString()) + "\",\"projects\":"
                + work.projects().size() + ",\"tasks\":" + work.tasks().size() + ",\"planObjects\":"
                + plan.objects().size() + ",\"programArtifacts\":" + prog.entries().size()
                + ",\"programEvidence\":" + prog.evidence().size() + "}\n");
    }

    private static void serve(ServerSocket s, ThreadPoolExecutor pool) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                c.setSoTimeout(SOCKET_TIMEOUT_MS);
                try {
                    pool.execute(() -> {
                        try (c) { handle(c); }
                        catch (IOException ignored) { }
                    });
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    try { c.close(); } catch (IOException ignored) { }
                }
            } catch (IOException ex) {
                if (!s.isClosed()) {
                    try { Thread.sleep(100L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static void handle(Socket c) throws IOException {
        String request;
        try {
            request = readAsciiLine(c.getInputStream(), MAX_REQUEST_LINE_BYTES);
        } catch (RequestLineTooLong tooLong) {
            writeResponse(c, 414, "{\"error\":\"request line too long\"}\n");
            return;
        }
        if (request == null) return;

        String[] f = request.split(" ", 3);
        String method = f.length > 0 ? f[0].toUpperCase(Locale.ROOT) : "";
        String path = f.length > 1 ? f[1] : "/";
        String body;
        int code;
        if (!"GET".equals(method)) { code = 405; body = "{\"error\":\"read-only; GET required\"}\n"; }
        else if ("/v1/state".equals(path) || "/".equals(path)) { code = 200; body = STATE.get(); }
        else if ("/v1/projects".equals(path)) { code = 200; body = PROJECTS.get(); }
        else if ("/v1/health".equals(path)) { code = 200; body = HEALTH.get(); }
        else { code = 404; body = "{\"error\":\"not found\"}\n"; }
        writeResponse(c, code, body);
    }

    static String readAsciiLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(256, maxBytes));
        while (true) {
            int value = in.read();
            if (value < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
            if (value == '\n') break;
            if (value == '\r') continue;
            if (bytes.size() >= maxBytes) throw new RequestLineTooLong();
            bytes.write(value);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void writeResponse(Socket c, int code, String body) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String status = switch (code) {
                case 200 -> "OK";
                case 405 -> "Method Not Allowed";
                case 414 -> "URI Too Long";
                default -> "Not Found";
            };
            w.write("HTTP/1.1 " + code + " " + status + "\r\n");
            w.write("Content-Type: application/json; charset=utf-8\r\n");
            w.write("Content-Length: " + bytes.length + "\r\n");
            w.write("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            w.write(body);
            w.flush();
        }
    }

    private static final class RequestLineTooLong extends IOException { }

    private static String js(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

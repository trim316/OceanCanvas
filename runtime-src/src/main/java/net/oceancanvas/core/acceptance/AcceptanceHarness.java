package net.oceancanvas.core.acceptance;

import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkStage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;

/**
 * Test-only restart gate for the v0.2 single-chunk runtime acceptance campaign.
 *
 * <p>This state is deliberately non-authoritative: chunk lifecycle truth remains
 * in {@code transitions.journal}. The harness merely ensures that a test session
 * advances at most one durable lifecycle transition, then requires an actual
 * server restart before the next transition may run.</p>
 */
public final class AcceptanceHarness {
    private static final int SCHEMA_VERSION = 1;

    private final Path path;
    private final String operationId;
    private final ChunkKey chunk;
    private String awaitingRestartStage;
    private int verifiedRestarts;
    private int sessionsOpened;
    private boolean finalRestartVerified;

    private AcceptanceHarness(Path path, String operationId, ChunkKey chunk,
                              String awaitingRestartStage, int verifiedRestarts,
                              int sessionsOpened, boolean finalRestartVerified) {
        this.path = path;
        this.operationId = operationId;
        this.chunk = chunk;
        this.awaitingRestartStage = awaitingRestartStage == null ? "" : awaitingRestartStage;
        this.verifiedRestarts = verifiedRestarts;
        this.sessionsOpened = sessionsOpened;
        this.finalRestartVerified = finalRestartVerified;
    }

    public static OpenResult open(Path path, String operationId, ChunkKey chunk, ChunkStage currentStage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(currentStage, "currentStage");

        AcceptanceHarness h;
        boolean restartVerified = false;
        boolean finalVerifiedNow = false;

        if (Files.exists(path)) {
            Properties p = load(path);
            int schema = parseInt(p, "schemaVersion");
            if (schema != SCHEMA_VERSION) throw new IOException("unsupported acceptance harness schema " + schema);
            String existingOperation = p.getProperty("operationId", "");
            int x = parseInt(p, "chunkX");
            int z = parseInt(p, "chunkZ");
            if (!operationId.equals(existingOperation) || x != chunk.x() || z != chunk.z()) {
                throw new IOException("acceptance harness identity mismatch: existing=" + existingOperation + "@" + x + "," + z
                        + " expected=" + operationId + "@" + chunk.x() + "," + chunk.z());
            }
            h = new AcceptanceHarness(path, operationId, chunk,
                    p.getProperty("awaitingRestartStage", "").trim(),
                    parseIntOr(p, "verifiedRestarts", 0),
                    parseIntOr(p, "sessionsOpened", 0),
                    Boolean.parseBoolean(p.getProperty("finalRestartVerified", "false")));
        } else {
            h = new AcceptanceHarness(path, operationId, chunk, "", 0, 0, false);
        }

        h.sessionsOpened++;

        if (!h.awaitingRestartStage.isBlank()) {
            ChunkStage expected;
            try { expected = ChunkStage.valueOf(h.awaitingRestartStage); }
            catch (IllegalArgumentException e) { throw new IOException("invalid awaitingRestartStage " + h.awaitingRestartStage, e); }
            if (expected != currentStage) {
                throw new IOException("acceptance restart gate mismatch: expected journal stage " + expected + " but resumed at " + currentStage);
            }
            h.awaitingRestartStage = "";
            h.verifiedRestarts++;
            restartVerified = true;
            if (currentStage == ChunkStage.COMPLETE) {
                h.finalRestartVerified = true;
                finalVerifiedNow = true;
            }
        }

        h.save();
        return new OpenResult(h, restartVerified, finalVerifiedNow);
    }

    public synchronized boolean shouldHold() {
        return !awaitingRestartStage.isBlank();
    }

    public synchronized void holdAfterTransition(ChunkStage stage) throws IOException {
        Objects.requireNonNull(stage, "stage");
        if (!awaitingRestartStage.isBlank()) {
            throw new IOException("acceptance harness is already awaiting restart at " + awaitingRestartStage);
        }
        awaitingRestartStage = stage.name();
        save();
    }

    public synchronized String awaitingRestartStage() { return awaitingRestartStage; }
    public synchronized int verifiedRestarts() { return verifiedRestarts; }
    public synchronized int sessionsOpened() { return sessionsOpened; }
    public synchronized boolean finalRestartVerified() { return finalRestartVerified; }

    private synchronized void save() throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Properties p = new Properties();
        p.setProperty("schemaVersion", Integer.toString(SCHEMA_VERSION));
        p.setProperty("operationId", operationId);
        p.setProperty("chunkX", Integer.toString(chunk.x()));
        p.setProperty("chunkZ", Integer.toString(chunk.z()));
        p.setProperty("awaitingRestartStage", awaitingRestartStage);
        p.setProperty("verifiedRestarts", Integer.toString(verifiedRestarts));
        p.setProperty("sessionsOpened", Integer.toString(sessionsOpened));
        p.setProperty("finalRestartVerified", Boolean.toString(finalRestartVerified));
        // Never truncate authoritative-on-disk acceptance evidence: a crash
        // between truncate and force would otherwise destroy the restart hold.
        // The journal is lifecycle authority; this persisted gate must still
        // survive interrupted writes without fabricating a fresh campaign.
        Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            p.store(out, "Ocean Canvas Core runtime acceptance harness. Non-authoritative test state.");
        }
        try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) { ch.force(true); }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Refuse to replace a valid earlier hold via an unsafe in-place copy.
            // A pending temp remains diagnostic evidence for recovery.
            throw new IOException("atomic acceptance-state replacement unavailable; refusing unsafe replacement", e);
        }
    }

    private static Properties load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }

    private static int parseInt(Properties p, String key) throws IOException {
        try { return Integer.parseInt(p.getProperty(key)); }
        catch (RuntimeException e) { throw new IOException("acceptance harness missing/invalid " + key, e); }
    }

    private static int parseIntOr(Properties p, String key, int fallback) throws IOException {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); }
        catch (RuntimeException e) { throw new IOException("acceptance harness invalid " + key, e); }
    }

    public record OpenResult(AcceptanceHarness harness, boolean restartVerified, boolean finalRestartVerifiedNow) {}
}

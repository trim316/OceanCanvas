package net.oceancanvas.core.config;

import net.oceancanvas.core.pipeline.OperationMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Restart-era configuration.
 *
 * <p>SAFE_HOLD remains the default. v0.2.15 adds one deliberately awkward,
 * destructive single-chunk test gate. CORE_AUTHORING does nothing unless all
 * three conditions are true: mode=CORE_AUTHORING, singleChunkEnabled=true, and
 * singleChunkConfirm exactly matches the target-specific confirmation token.
 * v0.2.15 adds an opt-in restart acceptance harness that can pause after every
 * durable transition without gaining any additional world-mutation authority.</p>
 */
public record CoreConfig(
        OperationMode mode,
        int canvasSize,
        int centerX,
        int centerZ,
        int waterSurfaceY,
        int oceanFloorY,
        int oceanFloorVariation,
        boolean expansionEnabled,
        boolean singleChunkEnabled,
        int singleChunkX,
        int singleChunkZ,
        String singleChunkConfirm,
        int maxBlockWritesPerTick,
        int maxChecksPerTick,
        int stageWallBudgetMicros,
        int physicalSettleTicks,
        int lightSettleTicks,
        boolean acceptanceHarnessEnabled) {

    public static final String FILE_NAME = "oceancanvas-core.properties";

    public CoreConfig {
        singleChunkConfirm = singleChunkConfirm == null ? "" : singleChunkConfirm.trim();
    }

    public static CoreConfig defaults() {
        return new CoreConfig(OperationMode.SAFE_HOLD, 20_000, 0, 0, 62, 25, 5, true,
                false, 0, 0, "", 256, 1024, 3_000, 40, 40, false);
    }

    public static CoreConfig loadOrCreate(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve(FILE_NAME);
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        }
        CoreConfig d = defaults();
        CoreConfig c = new CoreConfig(
                parseMode(p.getProperty("mode"), d.mode),
                positive(p, "canvasSize", d.canvasSize),
                integer(p, "centerX", d.centerX),
                integer(p, "centerZ", d.centerZ),
                integer(p, "waterSurfaceY", d.waterSurfaceY),
                integer(p, "oceanFloorY", d.oceanFloorY),
                nonNegative(p, "oceanFloorVariation", d.oceanFloorVariation),
                bool(p, "expansionEnabled", d.expansionEnabled),
                bool(p, "singleChunkEnabled", d.singleChunkEnabled),
                integer(p, "singleChunkX", d.singleChunkX),
                integer(p, "singleChunkZ", d.singleChunkZ),
                p.getProperty("singleChunkConfirm", d.singleChunkConfirm),
                boundedPositive(p, "maxBlockWritesPerTick", d.maxBlockWritesPerTick, 1, 4096),
                boundedPositive(p, "maxChecksPerTick", d.maxChecksPerTick, 16, 16384),
                boundedPositive(p, "stageWallBudgetMicros", d.stageWallBudgetMicros, 250, 20_000),
                boundedPositive(p, "physicalSettleTicks", d.physicalSettleTicks, 1, 20 * 60),
                boundedPositive(p, "lightSettleTicks", d.lightSettleTicks, 1, 20 * 60),
                bool(p, "acceptanceHarnessEnabled", d.acceptanceHarnessEnabled));
        c.validate();
        c.write(file); // normalize/migrate older restart-era config files with new guarded keys
        return c;
    }

    public String expectedSingleChunkConfirm() {
        return "ERASE_CHUNK_" + singleChunkX + "_" + singleChunkZ;
    }

    public boolean singleChunkAuthorityEnabled() {
        return mode == OperationMode.CORE_AUTHORING
                && singleChunkEnabled
                && expectedSingleChunkConfirm().equals(singleChunkConfirm);
    }

    private void validate() {
        if (canvasSize <= 0 || canvasSize % 16 != 0) {
            throw new IllegalArgumentException("canvasSize must be positive and chunk-aligned");
        }
        if (oceanFloorY >= waterSurfaceY) {
            throw new IllegalArgumentException("oceanFloorY must be below waterSurfaceY");
        }
        if (oceanFloorVariation < 0) {
            throw new IllegalArgumentException("oceanFloorVariation must be >= 0");
        }
    }

    private void write(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("mode", mode.name());
        p.setProperty("canvasSize", Integer.toString(canvasSize));
        p.setProperty("centerX", Integer.toString(centerX));
        p.setProperty("centerZ", Integer.toString(centerZ));
        p.setProperty("waterSurfaceY", Integer.toString(waterSurfaceY));
        p.setProperty("oceanFloorY", Integer.toString(oceanFloorY));
        p.setProperty("oceanFloorVariation", Integer.toString(oceanFloorVariation));
        p.setProperty("expansionEnabled", Boolean.toString(expansionEnabled));
        p.setProperty("singleChunkEnabled", Boolean.toString(singleChunkEnabled));
        p.setProperty("singleChunkX", Integer.toString(singleChunkX));
        p.setProperty("singleChunkZ", Integer.toString(singleChunkZ));
        p.setProperty("singleChunkConfirm", singleChunkConfirm);
        p.setProperty("maxBlockWritesPerTick", Integer.toString(maxBlockWritesPerTick));
        p.setProperty("maxChecksPerTick", Integer.toString(maxChecksPerTick));
        p.setProperty("stageWallBudgetMicros", Integer.toString(stageWallBudgetMicros));
        p.setProperty("physicalSettleTicks", Integer.toString(physicalSettleTicks));
        p.setProperty("lightSettleTicks", Integer.toString(lightSettleTicks));
        p.setProperty("acceptanceHarnessEnabled", Boolean.toString(acceptanceHarnessEnabled));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Ocean Canvas Core v0.2.15. SAFE_HOLD by default. Acceptance harness is opt-in and non-authoritative.");
        }
    }

    private static OperationMode parseMode(String raw, OperationMode fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return OperationMode.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
    private static int positive(Properties p, String key, int fallback) {
        int value = integer(p, key, fallback); return value > 0 ? value : fallback;
    }
    private static int nonNegative(Properties p, String key, int fallback) {
        int value = integer(p, key, fallback); return value >= 0 ? value : fallback;
    }
    private static int boundedPositive(Properties p, String key, int fallback, int min, int max) {
        int value = integer(p, key, fallback); return value >= min && value <= max ? value : fallback;
    }
    private static int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String raw = p.getProperty(key); return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }
}

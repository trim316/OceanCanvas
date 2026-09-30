package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.OperationMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Separate explicit opt-in for the future four-chunk disposable scale canary.
 * This is admission only: no runtime is registered from this class.
 */
public final class FourChunkCanaryAdmission {
    public static final String FILE_NAME = "oceancanvas-four-chunk-canary.properties";

    private FourChunkCanaryAdmission() {}

    public static Optional<FourChunkCanaryPlan> load(Path configDir, CoreConfig core) throws IOException {
        Properties p = loadProperties(configDir);
        if (p == null || !"true".equals(p.getProperty("enabled"))) return Optional.empty();
        if (core.mode() != OperationMode.CORE_AUTHORING
                || !core.expansionEnabled() || core.singleChunkEnabled()
                || core.acceptanceHarnessEnabled()) {
            throw new IOException("four-chunk canary requires separate CORE_AUTHORING expansion, "
                    + "disabled single-chunk and disabled acceptance harness");
        }

        // Scale lanes are mutually exclusive; one config cannot silently
        // widen another already-armed destructive scope.
        if (TwoChunkCanaryAdmission.explicitlyEnabled(configDir)
                || NineChunkCanaryAdmission.explicitlyEnabled(configDir)
                || SixteenChunkCanaryAdmission.explicitlyEnabled(configDir)) {
            throw new IOException("four-chunk canary cannot overlap another enabled scale consent");
        }

        try {
            ChunkKey nw = new ChunkKey(parse(p, "northWestX"), parse(p, "northWestZ"));
            ChunkKey ne = new ChunkKey(parse(p, "northEastX"), parse(p, "northEastZ"));
            ChunkKey sw = new ChunkKey(parse(p, "southWestX"), parse(p, "southWestZ"));
            ChunkKey se = new ChunkKey(parse(p, "southEastX"), parse(p, "southEastZ"));
            requireConfirm(p, "northWestConfirm", nw);
            requireConfirm(p, "northEastConfirm", ne);
            requireConfirm(p, "southWestConfirm", sw);
            requireConfirm(p, "southEastConfirm", se);

            FourChunkCanaryPlan plan = new FourChunkCanaryPlan(nw, ne, sw, se);
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            for (ChunkKey key : plan.orderedChunks()) {
                if (!bounds.contains(key.x(), key.z())) {
                    throw new IOException("four-chunk target outside configured Canvas bounds: " + key);
                }
            }
            return Optional.of(plan);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid four-chunk canary admission", e);
        }
    }

    public static boolean explicitlyEnabled(Path configDir) throws IOException {
        Properties p = loadProperties(configDir);
        return p != null && "true".equals(p.getProperty("enabled"));
    }

    private static Properties loadProperties(Path configDir) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate four-chunk consent property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(file)) {
            try { p.load(in); }
            catch (IllegalArgumentException e) {
                throw new IOException("ambiguous four-chunk destructive consent", e);
            }
        }
        return p;
    }

    public static String isolatedChunkDirectory(ChunkKey key) {
        return "chunk_" + key.x() + "_" + key.z();
    }

    private static void requireConfirm(Properties p, String key, ChunkKey chunk) throws IOException {
        String expected = "ERASE_CHUNK_" + chunk.x() + "_" + chunk.z();
        if (!expected.equals(p.getProperty(key))) {
            throw new IOException("exact destructive confirmation required for " + key);
        }
    }

    private static int parse(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || !value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("missing/invalid four-chunk coordinate " + key);
        }
        return Integer.parseInt(value);
    }
}

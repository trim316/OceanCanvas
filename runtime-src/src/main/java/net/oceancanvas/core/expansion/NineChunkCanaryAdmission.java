package net.oceancanvas.core.expansion;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.OperationMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;

/**
 * Separate explicit opt-in for the disposable nine-chunk scale canary.
 * Absence, incomplete, overlapping or ambiguous configuration is inert/refused.
 */
public final class NineChunkCanaryAdmission {
    public static final String FILE_NAME = "oceancanvas-nine-chunk-canary.properties";

    private NineChunkCanaryAdmission() {}

    public static Optional<NineChunkCanaryPlan> load(Path configDir, CoreConfig core) throws IOException {
        Properties p = loadProperties(configDir);
        if (p == null || !"true".equals(p.getProperty("enabled"))) return Optional.empty();

        if (core.mode() != OperationMode.CORE_AUTHORING
                || !core.expansionEnabled() || core.singleChunkEnabled()
                || core.acceptanceHarnessEnabled()) {
            throw new IOException("nine-chunk canary requires separate CORE_AUTHORING expansion, "
                    + "disabled single-chunk and disabled acceptance harness");
        }
        if (TwoChunkCanaryAdmission.explicitlyEnabled(configDir)
                || FourChunkCanaryAdmission.explicitlyEnabled(configDir)
                || SixteenChunkCanaryAdmission.explicitlyEnabled(configDir)
                || BoundedCampaignAdmission.explicitlyEnabled(configDir)) {
            throw new IOException("nine-chunk canary cannot overlap another enabled scale consent");
        }

        try {
            ArrayList<ChunkKey> chunks = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                ChunkKey key = new ChunkKey(parse(p, "chunk" + i + "X"),
                        parse(p, "chunk" + i + "Z"));
                String expected = "ERASE_CHUNK_" + key.x() + "_" + key.z();
                if (!expected.equals(p.getProperty("chunk" + i + "Confirm"))) {
                    throw new IOException("exact destructive confirmation required for chunk" + i);
                }
                chunks.add(key);
            }
            NineChunkCanaryPlan plan = new NineChunkCanaryPlan(chunks);
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            for (ChunkKey key : plan.orderedChunks()) {
                if (!bounds.contains(key.x(), key.z())) {
                    throw new IOException("nine-chunk target outside configured Canvas bounds: " + key);
                }
            }
            return Optional.of(plan);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid nine-chunk canary admission", e);
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
                    throw new IllegalArgumentException("duplicate nine-chunk consent property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(file)) {
            try { p.load(in); }
            catch (IllegalArgumentException e) {
                throw new IOException("ambiguous nine-chunk destructive consent", e);
            }
        }
        return p;
    }

    private static int parse(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || !value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("missing/invalid nine-chunk coordinate " + key);
        }
        return Integer.parseInt(value);
    }

    public static String isolatedChunkDirectory(ChunkKey key) {
        return "chunk_" + key.x() + "_" + key.z();
    }
}

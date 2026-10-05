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
 * Separate destructive opt-in for the experimental two-chunk canary.
 * Absence, incomplete or ambiguous configuration never authorizes mutation.
 * No production world can enter this lane from the existing single-chunk gate.
 */
public final class TwoChunkCanaryAdmission {
    public static final String FILE_NAME = "oceancanvas-two-chunk-canary.properties";
    private TwoChunkCanaryAdmission() {}

    public static Optional<TwoChunkCanaryPlan> load(Path configDir, CoreConfig core) throws IOException {
        Properties p = loadProperties(configDir);
        if (p == null || !"true".equals(p.getProperty("enabled"))) return Optional.empty();
        // Only a distinct canary config can authorize this lane. It cannot
        // share another scale lane, the existing single-chunk permission or
        // the single-chunk acceptance harness.
        if (FourChunkCanaryAdmission.explicitlyEnabled(configDir)
                || NineChunkCanaryAdmission.explicitlyEnabled(configDir)
                || SixteenChunkCanaryAdmission.explicitlyEnabled(configDir)
                || BoundedCampaignAdmission.explicitlyEnabled(configDir)) {
            throw new IOException("two-chunk canary cannot overlap another enabled scale consent");
        }
        if (core.mode() != OperationMode.CORE_AUTHORING
                || !core.expansionEnabled() || core.singleChunkEnabled()
                || core.acceptanceHarnessEnabled()) {
            throw new IOException("two-chunk canary requires separate CORE_AUTHORING expansion, "
                    + "disabled single-chunk and disabled single-chunk acceptance harness");
        }
        try {
            ChunkKey first = new ChunkKey(parse(p, "firstX"), parse(p, "firstZ"));
            ChunkKey second = new ChunkKey(parse(p, "secondX"), parse(p, "secondZ"));
            if (!("ERASE_CHUNK_" + first.x() + "_" + first.z()).equals(p.getProperty("firstConfirm"))
                    || !("ERASE_CHUNK_" + second.x() + "_" + second.z()).equals(p.getProperty("secondConfirm"))) {
                throw new IOException("both exact destructive chunk confirmation tokens required");
            }
            TwoChunkCanaryPlan plan = new TwoChunkCanaryPlan(first, second);
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            if (!bounds.contains(first.x(), first.z()) || !bounds.contains(second.x(), second.z())) {
                throw new IOException("two-chunk target outside configured Canvas bounds");
            }
            return Optional.of(plan);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid two-chunk canary admission", e);
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
                    throw new IllegalArgumentException("duplicate two-chunk consent property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(file)) {
            try { p.load(in); }
            catch (IllegalArgumentException e) {
                throw new IOException("ambiguous two-chunk destructive consent", e);
            }
        }
        return p;
    }

    private static int parse(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || !value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("missing/invalid two-chunk coordinate " + key);
        }
        return Integer.parseInt(value);
    }

    /** Independent immutable directory for a chunk's journal and preimage. */
    public static String isolatedChunkDirectory(ChunkKey key) {
        return "chunk_" + key.x() + "_" + key.z();
    }
}

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
 * Explicit opt-in for bounded campaign-scale recovery proof.
 * One exact square is authorized; absent or ambiguous configuration is inert.
 */
public final class BoundedCampaignAdmission {
    public static final String FILE_NAME = "oceancanvas-campaign-canary.properties";

    private BoundedCampaignAdmission() {}

    public static Optional<BoundedCampaignPlan> load(Path configDir, CoreConfig core) throws IOException {
        Properties p = loadProperties(configDir);
        if (p == null || !"true".equals(p.getProperty("enabled"))) return Optional.empty();

        if (core.mode() != OperationMode.CORE_AUTHORING
                || !core.expansionEnabled() || core.singleChunkEnabled()
                || core.acceptanceHarnessEnabled()) {
            throw new IOException("campaign canary requires separate CORE_AUTHORING expansion, "
                    + "disabled single-chunk and disabled acceptance harness");
        }
        if (TwoChunkCanaryAdmission.explicitlyEnabled(configDir)
                || FourChunkCanaryAdmission.explicitlyEnabled(configDir)
                || NineChunkCanaryAdmission.explicitlyEnabled(configDir)
                || SixteenChunkCanaryAdmission.explicitlyEnabled(configDir)) {
            throw new IOException("campaign canary cannot overlap another enabled scale consent");
        }

        try {
            int side = parse(p, "sideChunks");
            ChunkKey northWest = new ChunkKey(parse(p, "northWestX"), parse(p, "northWestZ"));
            BoundedCampaignPlan plan = BoundedCampaignPlan.squareEastSouthOf(northWest, side);
            String expected = "ERASE_CAMPAIGN_" + side + "X" + side
                    + "_FROM_" + northWest.x() + "_" + northWest.z();
            if (!expected.equals(p.getProperty("confirm"))) {
                throw new IOException("exact destructive campaign confirmation required: " + expected);
            }
            var bounds = OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(
                    core.canvasSize(), core.centerX(), core.centerZ());
            for (ChunkKey key : plan.orderedChunks()) {
                if (!bounds.contains(key.x(), key.z())) {
                    throw new IOException("campaign target outside configured Canvas bounds: " + key);
                }
            }
            return Optional.of(plan);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid bounded campaign admission", e);
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
                    throw new IllegalArgumentException("duplicate campaign consent property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(file)) {
            try { p.load(in); }
            catch (IllegalArgumentException e) {
                throw new IOException("ambiguous campaign destructive consent", e);
            }
        }
        return p;
    }

    private static int parse(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || !value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("missing/invalid campaign coordinate " + key);
        }
        return Integer.parseInt(value);
    }

    public static String isolatedChunkDirectory(ChunkKey key) {
        return "chunk_" + key.x() + "_" + key.z();
    }
}

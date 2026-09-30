package net.oceancanvas.core.restore;

import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.OperationMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Separate, additive consent for experimental block-entity recovery.
 *
 * This file can never grant chunk mutation authority by itself. It is valid
 * only when the ordinary single-chunk destructive gate is already exact for
 * the same target. Absence means block entities remain refused.
 */
public final class BlockEntityRecoveryAdmission {
    public static final String FILE_NAME = "oceancanvas-block-entity-recovery.properties";

    private BlockEntityRecoveryAdmission() {}

    public static Optional<ChunkKey> load(Path configDir, CoreConfig core) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return Optional.empty();

        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate block-entity recovery consent property: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(file)) {
            try {
                p.load(in);
            } catch (IllegalArgumentException e) {
                throw new IOException("ambiguous block-entity recovery consent", e);
            }
        }

        if (!"true".equals(p.getProperty("enabled"))) return Optional.empty();
        if (core.mode() != OperationMode.CORE_AUTHORING || !core.singleChunkAuthorityEnabled()
                || core.expansionEnabled() || core.acceptanceHarnessEnabled()) {
            throw new IOException("block-entity recovery requires exact single-chunk CORE_AUTHORING, "
                    + "disabled expansion and disabled acceptance harness");
        }

        ChunkKey configured = new ChunkKey(core.singleChunkX(), core.singleChunkZ());
        try {
            ChunkKey consented = new ChunkKey(parse(p, "chunkX"), parse(p, "chunkZ"));
            if (!configured.equals(consented)) {
                throw new IOException("block-entity recovery target differs from authorized single chunk");
            }
            String expected = "RECOVER_BLOCK_ENTITIES_CHUNK_" + consented.x() + "_" + consented.z();
            if (!expected.equals(p.getProperty("confirm"))) {
                throw new IOException("exact block-entity recovery confirmation token required");
            }
            return Optional.of(consented);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid block-entity recovery consent", e);
        }
    }

    private static int parse(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || !value.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("missing/invalid block-entity recovery coordinate " + key);
        }
        return Integer.parseInt(value);
    }
}

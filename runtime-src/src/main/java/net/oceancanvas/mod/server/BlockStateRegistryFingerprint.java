package net.oceancanvas.mod.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Exact runtime mapping fingerprint for BlockState integer IDs used by schema-2
 * recovery preimages. Any ID-to-state drift must stop recovery before tickets.
 */
public final class BlockStateRegistryFingerprint {
    private BlockStateRegistryFingerprint() {}

    public record Identity(String sha256, int stateCount) {}

    public static Identity compute() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int size = Block.BLOCK_STATE_REGISTRY.size();
            for (int id = 0; id < size; id++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (state == null || Block.getId(state) != id) {
                    throw new IllegalStateException("non-contiguous or unstable block-state registry at id " + id);
                }
                String blockId = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                String stateText = state.toString();
                updateInt(digest, id);
                updateString(digest, blockId);
                updateString(digest, stateText);
            }
            return new Identity(HexFormat.of().formatHex(digest.digest()), size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable for block-state registry identity", e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }
}

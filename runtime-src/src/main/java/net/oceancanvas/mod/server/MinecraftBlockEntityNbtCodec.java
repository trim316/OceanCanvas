package net.oceancanvas.mod.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.oceancanvas.core.restore.BlockEntityBackupContract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Minecraft-facing codec for exact block-entity NBT bytes.
 *
 * This class deliberately grants NO mutation authority. The runtime continues
 * refusing block entities until capture + sidecar + restore + cold-restart
 * evidence are integrated and pass.
 */
public final class MinecraftBlockEntityNbtCodec {
    private MinecraftBlockEntityNbtCodec() {}

    public record Captured(String typeId, byte[] nbt) {
        public Captured {
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(nbt, "nbt");
            if (nbt.length == 0 || nbt.length > BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES) {
                throw new IllegalArgumentException("block-entity NBT exceeds sidecar entry bound");
            }
            nbt = nbt.clone();
        }
        @Override public byte[] nbt() { return nbt.clone(); }
    }

    public static Captured capture(BlockEntity entity, HolderLookup.Provider registries)
            throws IOException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(registries, "registries");
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        if (id == null) {
            throw new IOException("unregistered block-entity type");
        }
        CompoundTag tag = entity.saveWithoutMetadata(registries);
        return new Captured(id.toString(), canonicalBytes(tag));
    }

    public static void apply(BlockEntity entity, String expectedTypeId, byte[] bytes,
            HolderLookup.Provider registries) throws IOException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(expectedTypeId, "expectedTypeId");
        Objects.requireNonNull(registries, "registries");
        ResourceLocation actual = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        if (actual == null || !actual.toString().equals(expectedTypeId)) {
            throw new IOException("block-entity type mismatch during NBT replay");
        }
        entity.loadWithComponents(decode(bytes), registries);
        entity.setChanged();
    }

    /** Uncompressed NBT is stable input for the sidecar's own checksum envelope. */
    public static byte[] canonicalBytes(CompoundTag tag) throws IOException {
        Objects.requireNonNull(tag, "tag");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(tag, out);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length == 0 || bytes.length > BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES) {
            throw new IOException("serialized block-entity NBT exceeds entry bound");
        }
        return bytes;
    }

    public static CompoundTag decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > BlockEntityBackupContract.MAX_ENTRY_NBT_BYTES) {
            throw new IOException("block-entity NBT input exceeds entry bound");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(in);
            if (in.available() != 0) {
                throw new IOException("block-entity NBT contains trailing bytes");
            }
            return tag;
        }
    }
}

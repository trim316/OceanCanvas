package net.oceancanvas.mod.server;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.restore.BiomePreimageStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Adapter foundation. No operator command or terrain pipeline invokes this yet. */
public final class BiomePreimageAdapter {
    private BiomePreimageAdapter() {}

    public static BiomePreimageStore.Preimage capture(LevelChunk chunk, String operation,
                                                       String blockPreimageSha256) throws IOException {
        int min = chunk.getSectionYFromSectionIndex(0) * 4;
        int max = chunk.getSectionYFromSectionIndex(chunk.getSections().length - 1) * 4 + 3;
        ArrayList<String> keys = new ArrayList<>((max - min + 1) * 16);
        for (int y = min; y <= max; y++) {
            var section = chunk.getSection(chunk.getSectionIndex(y * 4));
            int localY = Math.floorMod(y, 4);
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    var key = section.getBiomes().get(x, localY, z).unwrapKey();
                    if (key.isEmpty()) throw new IOException("unregistered biome cannot be recovered exactly");
                    keys.add(key.orElseThrow().identifier().toString());
                }
            }
        }
        return new BiomePreimageStore.Preimage(operation,
                new ChunkKey(chunk.getPos().x(), chunk.getPos().z()), blockPreimageSha256, min, max, keys);
    }

    /** Caller must already possess verified operation/chunk recovery authority. */
    public static void restoreVerified(ServerLevel world, LevelChunk chunk,
                                        BiomePreimageStore.Preimage preimage) throws IOException {
        if (!world.getServer().isSameThread()) throw new IOException("biome restore requires server thread");
        int min = chunk.getSectionYFromSectionIndex(0) * 4;
        int max = chunk.getSectionYFromSectionIndex(chunk.getSections().length - 1) * 4 + 3;
        if (!preimage.chunk().equals(new ChunkKey(chunk.getPos().x(), chunk.getPos().z()))
                || min != preimage.minQuartY() || max != preimage.maxQuartY())
            throw new IOException("biome restore chunk or height mismatch");

        // Resolve ALL keys and validate ALL destination palettes before the first mutation.
        Map<String, Holder<Biome>> resolved = new HashMap<>();
        for (String key : preimage.biomeKeys()) {
            if (resolved.containsKey(key)) continue;
            int colon = key.indexOf(':');
            var resource = ResourceKey.create(Registries.BIOME,
                    Identifier.fromNamespaceAndPath(key.substring(0, colon), key.substring(colon + 1)));
            var holder = world.registryAccess().lookupOrThrow(Registries.BIOME).get(resource);
            if (holder.isEmpty()) throw new IOException("captured biome registry key unavailable: " + key);
            resolved.put(key, holder.orElseThrow());
        }
        for (var section : chunk.getSections()) {
            if (!(section.getBiomes() instanceof PalettedContainer<?>))
                throw new IOException("biome palette is not writable");
        }
        for (int y = min; y <= max; y++) {
            var section = chunk.getSection(chunk.getSectionIndex(y * 4));
            @SuppressWarnings("unchecked")
            var palette = (PalettedContainer<Holder<Biome>>) section.getBiomes();
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    palette.set(x, Math.floorMod(y, 4), z, resolved.get(preimage.biomeAt(x, y, z)));
                }
            }
        }
        chunk.markUnsaved();
        if (!capture(chunk, preimage.operationId(), preimage.blockPreimageSha256()).equals(preimage))
            throw new IOException("restored biome cells differ from immutable preimage");
    }
}


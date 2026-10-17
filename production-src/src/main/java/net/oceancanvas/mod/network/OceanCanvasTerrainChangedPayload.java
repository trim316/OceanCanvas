package net.oceancanvas.mod.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Server-to-client batched terrain invalidation used by optional compatibility adapters.
 * Chunk positions are packed with {@code ChunkPos.pack}; one packet can cover many
 * mutations so large Pregen/Rewipe/Restore jobs do not become packet-per-chunk floods.
 */
public record OceanCanvasTerrainChangedPayload(List<Long> chunks, String kind)
        implements CustomPacketPayload {
    public static final int MAX_CHUNKS_PER_PACKET = 512;

    public OceanCanvasTerrainChangedPayload {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (chunks.size() > MAX_CHUNKS_PER_PACKET) {
            throw new IllegalArgumentException("Too many terrain-change chunks in one packet: " + chunks.size());
        }
    }

    public static final CustomPacketPayload.Type<OceanCanvasTerrainChangedPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "terrain_changed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasTerrainChangedPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.kind() == null ? "" : payload.kind());
                buf.writeVarInt(payload.chunks().size());
                for (long packed : payload.chunks()) buf.writeLong(packed);
            },
            buf -> {
                String kind = ByteBufCodecs.STRING_UTF8.decode(buf);
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_CHUNKS_PER_PACKET) {
                    throw new IllegalArgumentException("Invalid terrain-change batch size: " + count);
                }
                List<Long> chunks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) chunks.add(buf.readLong());
                return new OceanCanvasTerrainChangedPayload(chunks, kind);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

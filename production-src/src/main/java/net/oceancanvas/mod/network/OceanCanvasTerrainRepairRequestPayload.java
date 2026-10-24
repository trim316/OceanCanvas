package net.oceancanvas.mod.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request for a fresh authoritative chunk+light snapshot.
 *
 * <p>This is intentionally bounded and server-validated. The client only asks
 * after Ocean Canvas's near-field verifier has observed the same stale light
 * field on consecutive ticks. The server only serves chunks already tracked by
 * that player and never loads a chunk to satisfy this request.</p>
 */
public record OceanCanvasTerrainRepairRequestPayload(List<Long> chunks)
        implements CustomPacketPayload {
    public static final int MAX_CHUNKS_PER_PACKET = 64;

    public OceanCanvasTerrainRepairRequestPayload {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (chunks.size() > MAX_CHUNKS_PER_PACKET) {
            throw new IllegalArgumentException("Too many terrain-light repair chunks in one packet: " + chunks.size());
        }
    }

    public static final CustomPacketPayload.Type<OceanCanvasTerrainRepairRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "terrain_light_repair"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasTerrainRepairRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.chunks().size());
                for (long packed : payload.chunks()) buf.writeLong(packed);
            },
            buf -> {
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_CHUNKS_PER_PACKET) {
                    throw new IllegalArgumentException("Invalid terrain-light repair batch size: " + count);
                }
                List<Long> chunks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) chunks.add(buf.readLong());
                return new OceanCanvasTerrainRepairRequestPayload(chunks);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

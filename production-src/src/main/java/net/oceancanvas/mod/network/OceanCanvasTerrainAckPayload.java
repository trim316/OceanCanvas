package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client -> server diagnostic acknowledgement for a terrain invalidation batch.
 * This is deliberately telemetry only: server correctness never depends on an
 * optional renderer/cache mod acknowledging a packet.
 */
public record OceanCanvasTerrainAckPayload(
        String kind,
        int receivedChunks,
        int vanillaLoadedChunks,
        int voxyIngestedChunks,
        int voxyPendingChunks,
        boolean voxyEnabled,
        int skySamples,
        int skyAboveMin,
        int skyAboveMax,
        int skyWaterMin,
        int skyWaterMax,
        int skyAboveNot15,
        long skyHash)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OceanCanvasTerrainAckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "terrain_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasTerrainAckPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.kind() == null ? "" : payload.kind());
                buf.writeVarInt(Math.max(0, payload.receivedChunks()));
                buf.writeVarInt(Math.max(0, payload.vanillaLoadedChunks()));
                buf.writeVarInt(Math.max(0, payload.voxyIngestedChunks()));
                buf.writeVarInt(Math.max(0, payload.voxyPendingChunks()));
                buf.writeBoolean(payload.voxyEnabled());
                buf.writeVarInt(Math.max(0, payload.skySamples()));
                buf.writeVarInt(payload.skyAboveMin());
                buf.writeVarInt(payload.skyAboveMax());
                buf.writeVarInt(payload.skyWaterMin());
                buf.writeVarInt(payload.skyWaterMax());
                buf.writeVarInt(Math.max(0, payload.skyAboveNot15()));
                buf.writeLong(payload.skyHash());
            },
            buf -> new OceanCanvasTerrainAckPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

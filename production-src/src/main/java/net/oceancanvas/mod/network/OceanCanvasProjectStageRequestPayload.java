package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Changes project stage from the canonical map UI; currently used for Archive/Unlock. */
public record OceanCanvasProjectStageRequestPayload(String name, String stage) implements CustomPacketPayload {
    public static final Type<OceanCanvasProjectStageRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "project_stage_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasProjectStageRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> { ByteBufCodecs.STRING_UTF8.encode(buf, payload.name()); ByteBufCodecs.STRING_UTF8.encode(buf, payload.stage()); },
            buf -> new OceanCanvasProjectStageRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

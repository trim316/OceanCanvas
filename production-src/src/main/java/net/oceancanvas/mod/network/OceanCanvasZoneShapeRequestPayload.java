package net.oceancanvas.mod.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Client-to-server request to edit a region's exact chunk footprint. */
public record OceanCanvasZoneShapeRequestPayload(String name, String operation, List<Long> chunks, List<Integer> vertices, String previewToken)
        implements CustomPacketPayload {
    public OceanCanvasZoneShapeRequestPayload(String name, String operation, List<Long> chunks) { this(name, operation, chunks, List.of(), ""); }
    public OceanCanvasZoneShapeRequestPayload(String name, String operation, List<Long> chunks, List<Integer> vertices) { this(name, operation, chunks, vertices, ""); }
    public static final int MAX_CHUNKS = 65_536;
    public static final int MAX_VERTEX_INTS = 8_192;

    private static int checkedDecodeCount(RegistryFriendlyByteBuf buf, int count, int max, int bytesPerElement, String label) {
        if (count < 0 || count > max) throw new IllegalArgumentException("Ocean Canvas " + label + " count out of bounds: " + count + " (max " + max + ")");
        long required = (long) count * bytesPerElement;
        if (required > buf.readableBytes()) throw new IllegalArgumentException("Ocean Canvas truncated " + label + " payload");
        return count;
    }

    private static int checkedEncodeCount(int count, int max, String label) {
        if (count < 0 || count > max) throw new IllegalArgumentException("Ocean Canvas " + label + " count out of bounds: " + count + " (max " + max + ")");
        return count;
    }

    public static final CustomPacketPayload.Type<OceanCanvasZoneShapeRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_shape_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneShapeRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.operation());
                buf.writeInt(checkedEncodeCount(payload.chunks().size(), MAX_CHUNKS, "shape chunk"));
                for (long packed : payload.chunks()) buf.writeLong(packed);
                buf.writeInt(checkedEncodeCount(payload.vertices().size(), MAX_VERTEX_INTS, "shape vertex-int"));
                for (int value : payload.vertices()) buf.writeInt(value);
                ByteBufCodecs.STRING_UTF8.encode(buf,payload.previewToken()==null?"":payload.previewToken());
            },
            buf -> {
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                String operation = ByteBufCodecs.STRING_UTF8.decode(buf);
                int count = checkedDecodeCount(buf, buf.readInt(), MAX_CHUNKS, Long.BYTES, "shape chunk");
                List<Long> chunks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) chunks.add(buf.readLong());
                int vertexCount = checkedDecodeCount(buf, buf.readInt(), MAX_VERTEX_INTS, Integer.BYTES, "shape vertex-int");
                List<Integer> vertices = new ArrayList<>(vertexCount);
                for (int i = 0; i < vertexCount; i++) vertices.add(buf.readInt());
                return new OceanCanvasZoneShapeRequestPayload(name, operation, chunks, vertices, ByteBufCodecs.STRING_UTF8.decode(buf));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

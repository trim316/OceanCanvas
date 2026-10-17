package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Server-to-client: what the running pregen/reset/expand job is doing
 * right now, so the map can show it happening.
 *
 * <p>Watching a large job progress used to mean reading a percentage in
 * chat and taking it on faith. Drawing the job's actual region on the map,
 * with a sweep line at the cursor's real position, turns that into
 * something you can watch - which is both more reassuring on a job that
 * will run for several minutes and genuinely more informative, since it
 * shows you WHERE the work is rather than only how much is left.</p>
 *
 * <p><b>Broadcast far more often than the zone/structure layers</b>
 * (roughly once a second rather than once every five), because it is the
 * one thing on this screen that is actually moving, and a sweep line that
 * jumps in five-second steps would look broken rather than live. It can
 * afford that rate precisely because it is tiny - a handful of ints and a
 * short string - unlike the zone and structure lists.</p>
 *
 * <p>{@code scopeName} is the authoritative named Region for region-scoped jobs. The client
 * must prefer it over guessing from the moving cursor: overlapping Regions and polygon envelopes
 * make cursor-based inference ambiguous. It is empty for canvas-wide/radius operations.</p>
 *
 * <p>{@code kind} is empty when nothing is running, which is also the
 * signal for the client to clear the overlay. Chunk coordinates
 * throughout, matching how a job actually walks its region.</p>
 */
public record OceanCanvasJobStatusPayload(
		String kind, String scopeName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
		int cursorChunkX, int cursorChunkZ, long submittedChunks, long totalChunks
) implements CustomPacketPayload {

	/** The "nothing is running" value - see the class doc. */
	public static final OceanCanvasJobStatusPayload IDLE =
			new OceanCanvasJobStatusPayload("", "", 0, 0, 0, 0, 0, 0, 0L, 0L);

	public boolean isRunning() {
		return !kind.isEmpty();
	}

	public static final CustomPacketPayload.Type<OceanCanvasJobStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "job_status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasJobStatusPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.kind() == null ? "" : payload.kind());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.scopeName() == null ? "" : payload.scopeName());
				buf.writeInt(payload.minChunkX());
				buf.writeInt(payload.minChunkZ());
				buf.writeInt(payload.maxChunkX());
				buf.writeInt(payload.maxChunkZ());
				buf.writeInt(payload.cursorChunkX());
				buf.writeInt(payload.cursorChunkZ());
				buf.writeLong(payload.submittedChunks());
				buf.writeLong(payload.totalChunks());
			},
			buf -> new OceanCanvasJobStatusPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readInt(),
					buf.readLong(), buf.readLong())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

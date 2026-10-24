package net.oceancanvas.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client: every protected structure box the mod is currently
 * preserving, so the map screen can draw a structures layer.
 *
 * <p>This is the layer that makes the map read as a world-inspection tool
 * rather than only a zone editor - the same role a structure overlay
 * plays in Chunkbase, except sourced from the mod's own real, persisted
 * protection data instead of from re-deriving anything from the seed.
 * Everything here is a box this mod actually promised not to carve
 * through, which is a more useful and more honest thing to show than a
 * predicted structure position: if it is on this layer, it is protected,
 * right now, at those coordinates.</p>
 *
 * <p><b>Type is inferred from volume, and labelled honestly as such.</b>
 * {@code OceanCanvasProtectedData} stores boxes, not kinds - adding a
 * parallel kinds list would be a schema change to the single most
 * load-bearing persisted class in this project, which has a real history
 * of pain (see its own "Rev 5" note) and is not worth it for a cosmetic
 * label. A one-block box is a relocated buried treasure chest, since that
 * is the only single-block thing this mod ever protects; anything larger
 * is a shipwreck or a guaranteed starter structure. The client says
 * "structure" rather than naming a specific one where it cannot actually
 * know, instead of guessing confidently.</p>
 *
 * <p>Sent on the same schedule as {@link OceanCanvasZoneSyncPayload},
 * from the same broadcast, so the two layers can never disagree about
 * what moment they describe. Same hand-rolled codec shape as every other
 * payload here - see that class's doc for why.</p>
 */
public record OceanCanvasStructureSyncPayload(List<StructureEntry> structures) implements CustomPacketPayload {

	public record StructureEntry(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int kindCode) {

		/** Volume-based kind inference - see the class doc for why this is derived rather than stored. */
		public boolean isSingleBlock() {
			return minX == maxX && minY == maxY && minZ == maxZ;
		}

		public int centerX() {
			return (minX + maxX) / 2;
		}

		public int centerZ() {
			return (minZ + maxZ) / 2;
		}
	}

	/**
	 * Hard cap on how many boxes a single broadcast will carry. A canvas
	 * that has been explored for a long time can accumulate a lot of
	 * protected structures, and this packet goes to every player every few
	 * seconds - an unbounded list would quietly turn into real, recurring
	 * bandwidth for a decorative layer. The map says so plainly when the
	 * cap is hit rather than silently drawing a partial picture.
	 */
	public static final int MAX_ENTRIES = 2_000;

	public static final CustomPacketPayload.Type<OceanCanvasStructureSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "structure_sync"));

	private static final StreamCodec<ByteBuf, StructureEntry> ENTRY_CODEC = StreamCodec.of(
			(buf, entry) -> {
				buf.writeInt(entry.minX());
				buf.writeInt(entry.minY());
				buf.writeInt(entry.minZ());
				buf.writeInt(entry.maxX());
				buf.writeInt(entry.maxY());
				buf.writeInt(entry.maxZ());
				buf.writeByte(entry.kindCode());
			},
			buf -> new StructureEntry(buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readUnsignedByte())
	);

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasStructureSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeInt(payload.structures().size());
				for (StructureEntry entry : payload.structures()) {
					ENTRY_CODEC.encode(buf, entry);
				}
			},
			buf -> {
				int count = buf.readInt();
				// Clamped, not merely pre-sized. The count comes off the
				// wire, so a buggy or hostile sender claiming a huge one
				// would otherwise have the client allocate until the buffer
				// underflows. The server never sends more than the cap.
				int safeCount = Math.max(0, Math.min(count, MAX_ENTRIES));
				List<StructureEntry> entries = new ArrayList<>(safeCount);
				for (int i = 0; i < safeCount; i++) {
					entries.add(ENTRY_CODEC.decode(buf));
				}
				return new OceanCanvasStructureSyncPayload(entries);
			}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

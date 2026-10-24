package net.oceancanvas.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Real feature request: minimap integration showing protected zones,
 * toggleable. There was zero networking anywhere in this project before
 * this - zones ({@link net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones})
 * are server-side {@code SavedData}, completely invisible to any
 * client-side code (including a minimap mod's plugin) without an
 * explicit sync mechanism. This is that mechanism: the server sends the
 * full current zone list (plus the canvas's own bounds, so a minimap
 * integration can draw that too) to every connected client, both on
 * join and periodically afterward (see {@code OceanCanvasNetworking}'s
 * broadcast call site) - periodic rather than change-triggered, since
 * hooking every single zone-mutating call site (create/remove/enable/
 * disable/transfer, several command files) would be much more invasive
 * for a feature where a few seconds of staleness after an admin action
 * is a complete non-issue.
 *
 * <p><b>Deliberately hand-rolled encode/decode instead of {@code
 * StreamCodec.composite(...)}</b>: {@code ZoneEntry} has 8 fields, and
 * this project doesn't have a confirmed answer for how many arguments
 * {@code StreamCodec.composite} actually supports on this exact 26.2
 * build (real Mojang code has historically capped it well under 8).
 * Manual {@code StreamCodec.of(encoder, decoder)} sidesteps that
 * uncertainty entirely with plain sequential buffer reads/writes -
 * more verbose, but nothing here depends on an unconfirmed arity
 * limit.</p>
 *
 * <p><b>Unverified against a real 26.2 compile</b>, same as every other
 * first-attempt feature in this project - this is the very first
 * networking code written for it. {@code CustomPacketPayload}/{@code
 * StreamCodec}/{@code PayloadTypeRegistry} have been the standard,
 * fairly stable Fabric/vanilla networking shape for a while, so this is
 * closer to Cloth Config's "mature API, not yet checked against this
 * build" risk category than to a guessed-at Mojang internal rename -
 * but still genuinely unconfirmed.</p>
 *
 * <p><b>Two canvas-level fields added for the map screen:</b> {@code
 * taperWidthBlocks} (0 when the soft edge transition is off) and {@code
 * flattenedChunks}. Both exist because the screen must not read {@code
 * OceanCanvasConfig} or {@code OceanCanvasProtectedData} directly - those
 * are server state, and on a dedicated server the client's own copy of
 * the config file is a different file that may say something completely
 * different. Sending them means the map draws the taper ring and the
 * "% carved" readout from what is actually true on the server, not from
 * what the client happens to believe.</p>
 *
 * <p><b>{@code ZoneEntry} carries a region's whole rule set</b>, not just
 * its bounds: the owner, the structure rules, and any biome the region
 * paints - everything the map screen needs to render a region's full
 * state without a second round-trip.</p>
 *
 * <p><b>Structure rules travel as one packed string</b> ({@code
 * "shipwreck=FORCE_ON,ocean_ruin=FORCE_OFF"}, empty when the region has
 * none) rather than as a nested list. That is deliberate: this is a
 * hand-rolled {@code ByteBuf} codec with no collection support of its own,
 * so a nested list would mean hand-rolling length-prefixed sub-entries
 * too - real code to get subtly wrong for no benefit, given rules are few
 * and short. It also means adding a structure kind changes nothing here
 * at all. {@link #formatRules}/{@link #parseRules} are the single
 * definition of that format, used by both ends, so the two can never
 * disagree about it.</p>
 */
public record OceanCanvasZoneSyncPayload(
		int canvasMinX, int canvasMinZ, int canvasMaxX, int canvasMaxZ,
		int taperWidthBlocks, long flattenedChunks,
		List<ZoneEntry> zones
) implements CustomPacketPayload {

	public record ZoneRun(int z, int minX, int maxX) {
		public long sizeLong() { return Math.max(0L, (long) maxX - (long) minX + 1L); }
	}

	public record ZoneEntry(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean enabled,
			String ownerName, String structureRules, String biomeOverride, boolean suppressHostileMobs, String color, List<Long> chunks, List<ZoneRun> chunkRuns, List<Integer> shapeVertices) {

		public long explicitChunkCount() {
			if (chunks != null && !chunks.isEmpty()) return chunks.size();
			long total = 0L;
			if (chunkRuns != null) for (ZoneRun run : chunkRuns) {
				long add = run == null ? 0L : run.sizeLong();
				if (Long.MAX_VALUE - total < add) return Long.MAX_VALUE;
				total += add;
			}
			return total;
		}

		/** This region's rule for one structure kind, or {@code "INHERIT"} when it has none. */
		public String ruleFor(String structureKindId) {
			return parseRules(structureRules).getOrDefault(structureKindId, "INHERIT");
		}

		/** True when the region paints a biome - {@code biomeOverride} is empty rather than null over the wire. */
		public boolean hasBiomeOverride() {
			return biomeOverride != null && !biomeOverride.isEmpty();
		}

		/** True when the region carries a custom display colour - {@code color} is empty rather than null over the wire. */
		public boolean hasColor() {
			return color != null && !color.isEmpty();
		}
	}

	/**
	 * Packs structure rules into the wire format - see the class doc.
	 * Entries whose value is {@code INHERIT} are omitted, since absence
	 * already means exactly that.
	 */
	public static String formatRules(java.util.Map<String, String> rules) {
		StringBuilder builder = new StringBuilder();
		for (java.util.Map.Entry<String, String> entry : rules.entrySet()) {
			if (entry.getValue() == null || "INHERIT".equals(entry.getValue())) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return builder.toString();
	}

	/**
	 * Unpacks the wire format. Malformed fragments are skipped rather than
	 * throwing: this runs on the client's network path, and a single bad
	 * character should degrade one rule display, not drop the packet and
	 * with it the whole zone list.
	 */
	public static java.util.Map<String, String> parseRules(String packed) {
		if (packed == null || packed.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<String, String> rules = new java.util.LinkedHashMap<>();
		for (String fragment : packed.split(",")) {
			int equals = fragment.indexOf('=');
			if (equals > 0 && equals < fragment.length() - 1) {
				rules.put(fragment.substring(0, equals), fragment.substring(equals + 1));
			}
		}
		return rules;
	}


	/** Hard wire limits: region editing is administrative input, not an unbounded allocator. */
	public static final int MAX_ZONES = 2_048;
	public static final int MAX_CHUNKS_PER_ZONE = 65_536; // legacy/small-mask compatibility
	public static final int MAX_CHUNK_RUNS_PER_ZONE = 65_536;
	public static final int MAX_VERTEX_INTS = 8_192;

	private static int checkedCount(int count, int max, String label, ByteBuf buf, int bytesPerElement) {
		if (count < 0 || count > max) throw new IllegalArgumentException("Ocean Canvas " + label + " count out of bounds: " + count + " (max " + max + ")");
		long required = (long) count * bytesPerElement;
		if (required > buf.readableBytes()) throw new IllegalArgumentException("Ocean Canvas truncated " + label + " payload: needs " + required + " bytes, has " + buf.readableBytes());
		return count;
	}

	private static int checkedEncodeCount(int count, int max, String label) {
		if (count < 0 || count > max) throw new IllegalArgumentException("Ocean Canvas " + label + " count out of bounds: " + count + " (max " + max + ")");
		return count;
	}

	public static final CustomPacketPayload.Type<OceanCanvasZoneSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_sync"));

	private static final StreamCodec<ByteBuf, ZoneEntry> ZONE_ENTRY_CODEC = StreamCodec.of(
			(buf, entry) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, entry.name());
				buf.writeInt(entry.minX());
				buf.writeInt(entry.minY());
				buf.writeInt(entry.minZ());
				buf.writeInt(entry.maxX());
				buf.writeInt(entry.maxY());
				buf.writeInt(entry.maxZ());
				buf.writeBoolean(entry.enabled());
				// Empty string, not null - this is a raw ByteBuf codec
				// with no null-handling of its own, same convention
				// OceanCanvasPlayerZones' own Codec already uses for an
				// absent owner.
				ByteBufCodecs.STRING_UTF8.encode(buf, entry.ownerName() == null ? "" : entry.ownerName());
				// Same defensive empty-not-null default as ownerName above -
				// an NPE thrown while encoding, on the server's network
				// thread, is a genuinely unpleasant place to debug from.
				ByteBufCodecs.STRING_UTF8.encode(buf,
						entry.structureRules() == null ? "" : entry.structureRules());
				ByteBufCodecs.STRING_UTF8.encode(buf,
						entry.biomeOverride() == null ? "" : entry.biomeOverride());
				buf.writeBoolean(entry.suppressHostileMobs());
				ByteBufCodecs.STRING_UTF8.encode(buf, entry.color() == null ? "" : entry.color());
				int chunkCount = checkedEncodeCount(entry.chunks() == null ? 0 : entry.chunks().size(), MAX_CHUNKS_PER_ZONE, "zone chunk");
				buf.writeInt(chunkCount);
				if (entry.chunks() != null) for (long packed : entry.chunks()) buf.writeLong(packed);
				int runCount = checkedEncodeCount(entry.chunkRuns() == null ? 0 : entry.chunkRuns().size(), MAX_CHUNK_RUNS_PER_ZONE, "zone chunk-run");
				buf.writeInt(runCount);
				if (entry.chunkRuns() != null) for (ZoneRun run : entry.chunkRuns()) { buf.writeInt(run.z()); buf.writeInt(run.minX()); buf.writeInt(run.maxX()); }
                int vertexCount = checkedEncodeCount(entry.shapeVertices() == null ? 0 : entry.shapeVertices().size(), MAX_VERTEX_INTS, "zone vertex-int");
                buf.writeInt(vertexCount);
                if (entry.shapeVertices() != null) for (int value : entry.shapeVertices()) buf.writeInt(value);
			},
			buf -> {
				String name = ByteBufCodecs.STRING_UTF8.decode(buf);
				int minX = buf.readInt();
				int minY = buf.readInt();
				int minZ = buf.readInt();
				int maxX = buf.readInt();
				int maxY = buf.readInt();
				int maxZ = buf.readInt();
				boolean enabled = buf.readBoolean();
				String ownerName = ByteBufCodecs.STRING_UTF8.decode(buf);
				String structureRules = ByteBufCodecs.STRING_UTF8.decode(buf);
				String biomeOverride = ByteBufCodecs.STRING_UTF8.decode(buf);
				boolean suppressHostileMobs = buf.readBoolean();
				String color = ByteBufCodecs.STRING_UTF8.decode(buf);
				int chunkCount = checkedCount(buf.readInt(), MAX_CHUNKS_PER_ZONE, "zone chunk", buf, Long.BYTES);
				List<Long> chunks = new ArrayList<>(chunkCount);
				for (int i = 0; i < chunkCount; i++) chunks.add(buf.readLong());
				int runCount = checkedCount(buf.readInt(), MAX_CHUNK_RUNS_PER_ZONE, "zone chunk-run", buf, Integer.BYTES * 3);
				List<ZoneRun> chunkRuns = new ArrayList<>(runCount);
				for (int i = 0; i < runCount; i++) {
					int z = buf.readInt(), runMinX = buf.readInt(), runMaxX = buf.readInt();
					if (runMinX > runMaxX) throw new IllegalArgumentException("Ocean Canvas invalid zone chunk-run " + runMinX + ".." + runMaxX);
					chunkRuns.add(new ZoneRun(z, runMinX, runMaxX));
				}
                int vertexCount = checkedCount(buf.readInt(), MAX_VERTEX_INTS, "zone vertex-int", buf, Integer.BYTES);
                List<Integer> shapeVertices = new ArrayList<>(vertexCount);
                for (int i = 0; i < vertexCount; i++) shapeVertices.add(buf.readInt());
				return new ZoneEntry(name, minX, minY, minZ, maxX, maxY, maxZ, enabled,
						ownerName.isEmpty() ? null : ownerName, structureRules, biomeOverride, suppressHostileMobs,
						color.isEmpty() ? null : color, chunks, chunkRuns, shapeVertices);
			}
	);

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeInt(payload.canvasMinX());
				buf.writeInt(payload.canvasMinZ());
				buf.writeInt(payload.canvasMaxX());
				buf.writeInt(payload.canvasMaxZ());
				buf.writeInt(payload.taperWidthBlocks());
				buf.writeLong(payload.flattenedChunks());
				buf.writeInt(checkedEncodeCount(payload.zones().size(), MAX_ZONES, "zone"));
				for (ZoneEntry entry : payload.zones()) {
					ZONE_ENTRY_CODEC.encode(buf, entry);
				}
			},
			buf -> {
				int canvasMinX = buf.readInt();
				int canvasMinZ = buf.readInt();
				int canvasMaxX = buf.readInt();
				int canvasMaxZ = buf.readInt();
				int taperWidthBlocks = buf.readInt();
				long flattenedChunks = buf.readLong();
				int zoneCount = buf.readInt();
				if (zoneCount < 0 || zoneCount > MAX_ZONES) throw new IllegalArgumentException("Ocean Canvas zone count out of bounds: " + zoneCount + " (max " + MAX_ZONES + ")");
				List<ZoneEntry> zones = new ArrayList<>(zoneCount);
				for (int i = 0; i < zoneCount; i++) {
					zones.add(ZONE_ENTRY_CODEC.decode(buf));
				}
				return new OceanCanvasZoneSyncPayload(canvasMinX, canvasMinZ, canvasMaxX, canvasMaxZ,
						taperWidthBlocks, flattenedChunks, zones);
			}
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

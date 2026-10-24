package net.oceancanvas.mod.operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable operation-history facade used by destructive Ocean Canvas workflows and the admin UI.
 *
 * <p>Earlier builds kept a second process-global in-memory deque for the dashboard. That duplicated
 * the persisted {@link OceanCanvasOperationHistoryData} source of truth and could bleed across
 * integrated-server sessions. The facade now writes and reads only the bounded per-world history;
 * transient JVM-global action state no longer exists.</p>
 */
public final class OceanCanvasActionLog {


	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private OceanCanvasActionLog() {
	}

	/** Records one destructive action in the bounded persisted world history. */
	public static void record(ServerLevel world, String kind, ServerPlayer requestedBy, String detail) {
		if (world == null) return;
		String requesterName = requestedBy == null ? "console" : requestedBy.getGameProfile().name();
		Scope scope = currentScope(world, kind, detail);
		OceanCanvasOperationHistoryData.get(world).record(kind, "STARTED", requesterName, detail,
				scope.type(), scope.id(), scope.minChunkX(), scope.minChunkZ(), scope.maxChunkX(), scope.maxChunkZ(), scope.descriptor());
		net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(world)
				.capture(world, "start-" + kind, "Operation start checkpoint");
	}

	public static void recordLifecycle(ServerLevel world, String kind, String phase,
			ServerPlayer requestedBy, String detail) {
		if (world == null) return;
		String requesterName = requestedBy == null ? "system" : requestedBy.getGameProfile().name();
		Scope scope = currentScope(world, kind, detail);
		// Terminal lifecycle callbacks can run after the active controller has already
		// withdrawn its overlay. Reuse the most recent scoped entry for the same kind
		// so the entire historical operation remains spatially inspectable.
		if (!scope.valid()) {
			for (OceanCanvasOperationHistoryData.Entry entry : OceanCanvasOperationHistoryData.get(world).recent()) {
				if (entry.kind().equalsIgnoreCase(kind) && entry.hasScope()) {
					scope = new Scope(entry.scopeType(), entry.scopeId(), entry.minChunkX(), entry.minChunkZ(), entry.maxChunkX(), entry.maxChunkZ(), entry.scopeDescriptor());
					break;
				}
			}
		}
		OceanCanvasOperationHistoryData.get(world).record(kind, phase, requesterName, detail,
				scope.type(), scope.id(), scope.minChunkX(), scope.minChunkZ(), scope.maxChunkX(), scope.maxChunkZ(), scope.descriptor());
		if ("COMPLETED".equalsIgnoreCase(phase) || "CANCELLED".equalsIgnoreCase(phase) || "FAILED".equalsIgnoreCase(phase)) {
			try {
				net.oceancanvas.mod.project.OceanCanvasStewardshipReportService.write(world, "operation " + kind + " " + phase);
			} catch (java.io.IOException ex) {
				net.oceancanvas.mod.OceanCanvas.LOGGER.warn("(Ocean Canvas) Could not write stewardship report after {} {}: {}", kind, phase, ex.toString());
			}
		}
	}

	private static Scope currentScope(ServerLevel world, String kind, String detail) {
		String type=scopeType(detail),id=regionId(detail);
		OceanCanvasTerrainOperationView.Overlay overlay = OceanCanvasTerrainOperationView.overlaySnapshot();
		if (overlay != null) {
			return describeScope(world,new Scope(type,id,overlay.minChunkX(), overlay.minChunkZ(), overlay.maxChunkX(), overlay.maxChunkZ(),""));
		}
		return Scope.NONE;
	}

	/** Immutable historical geometry. Prefer the exact polygon; arbitrary chunk masks use row-run RLE. */
	private static Scope describeScope(ServerLevel world, Scope base) {
		if (!base.valid()) return base;
		String descriptor="RECT:"+base.minChunkX()+","+base.minChunkZ()+","+base.maxChunkX()+","+base.maxChunkZ();
		if (world!=null && "REGION".equalsIgnoreCase(base.type()) && !base.id().isBlank()) {
			var zone=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(base.id());
			if(zone!=null){
				var vertices=zone.shapeVertices();
				if(vertices!=null&&vertices.size()>=6){
					StringBuilder d=new StringBuilder("POLY:");for(int i=0;i<vertices.size();i++){if(i>0)d.append(',');d.append(vertices.get(i));}descriptor=d.toString();
				}else if(zone.chunks()!=null&&!zone.chunks().isEmpty()) descriptor=encodeChunkRuns(zone.chunks());
			}
		}
		return new Scope(base.type(),base.id(),base.minChunkX(),base.minChunkZ(),base.maxChunkX(),base.maxChunkZ(),descriptor);
	}

	private static String encodeChunkRuns(java.util.Set<Long> chunks){
		var byZ=new java.util.TreeMap<Integer,java.util.List<Integer>>();for(long p:chunks)byZ.computeIfAbsent(net.minecraft.world.level.ChunkPos.getZ(p),k->new java.util.ArrayList<>()).add(net.minecraft.world.level.ChunkPos.getX(p));
		StringBuilder s=new StringBuilder("RUNS:");boolean first=true;for(var e:byZ.entrySet()){var xs=e.getValue();java.util.Collections.sort(xs);int a=xs.get(0),b=a;for(int i=1;i<=xs.size();i++){int x=i<xs.size()?xs.get(i):Integer.MIN_VALUE;if(i<xs.size()&&x<=b+1){b=x;continue;}if(!first)s.append(';');first=false;s.append(e.getKey()).append(':').append(a).append('-').append(b);if(i<xs.size()){a=b=x;}}}return s.toString();
	}

	private static String scopeType(String detail) {
		return regionId(detail).isBlank() ? "CHUNK_RECT" : "REGION";
	}

	private static String regionId(String detail) {
		if (detail == null) return "";
		int start = detail.indexOf("region '");
		if (start < 0) return "";
		start += 8;
		int end = detail.indexOf('\'', start);
		return end > start ? detail.substring(start, end) : "";
	}

	private record Scope(String type, String id, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, String descriptor) {
		private static final Scope NONE = new Scope("", "", 1, 1, 0, 0, "");
		boolean valid() { return !type.isBlank() && minChunkX <= maxChunkX && minChunkZ <= maxChunkZ; }
	}

	/** Durable history view used by admin/recovery UX after restart. */
	public static List<String> recentFormatted(ServerLevel world) {
		if (world == null) return List.of();
		List<String> lines = new ArrayList<>();
		for (OceanCanvasOperationHistoryData.Entry entry : OceanCanvasOperationHistoryData.get(world).recent()) {
			lines.add(TIME_FORMAT.format(Instant.ofEpochMilli(entry.epochMillis())) + " - "
					+ entry.kind() + " " + entry.phase().toLowerCase(java.util.Locale.ROOT)
					+ " by " + entry.requester() + " (" + entry.detail() + ")");
		}
		return lines;
	}

}

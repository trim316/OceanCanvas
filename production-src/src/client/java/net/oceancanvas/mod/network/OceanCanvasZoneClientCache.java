package net.oceancanvas.mod.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;

/**
 * Client-side half of the zone-sync feature - see {@link
 * OceanCanvasZoneSyncPayload}'s class doc for the full story. Just a
 * simple static holder for whatever the server most recently sent -
 * anything client-side that wants to know about regions (the map screen,
 * {@code OceanCanvasJourneyMapIntegration}, or something else later, e.g.
 * Xaero's Minimap if it ever gets a real public overlay API) reads from
 * here instead of needing its own packet-handling logic.
 *
 * <p>Now holds four separate feeds rather than one, matching what the
 * server actually sends: the region list, the protected-structure list,
 * the running job, and the one-off feedback line. They are deliberately
 * kept apart instead of merged into one snapshot, because they arrive on
 * genuinely different schedules - job status roughly once a second
 * because it moves, the region and structure lists every few seconds
 * because they do not, and feedback only in direct answer to something
 * the player just did - and folding them together would mean either
 * sending the slow ones far too often or showing a job that lags a
 * second behind itself.</p>
 *
 * <p>Feedback is the odd one out and is stamped with its arrival time,
 * because it is the only feed whose value is meant to stop being shown.
 * A region list stays true until it is replaced; "that isn't a biome this
 * world knows about" is true for a moment and then is just clutter over
 * the map. The timestamp lives here, at the point of arrival, rather than
 * in the drawing code, so that a screen opened ten minutes later does not
 * present a stale rejection as though it had just happened.</p>
 */
public final class OceanCanvasZoneClientCache {

	private static volatile OceanCanvasZoneSyncPayload latest = null;
	private static volatile OceanCanvasStructureSyncPayload latestStructures = null;
	private static volatile OceanCanvasJobStatusPayload latestJob = null;
	private static volatile Feedback latestFeedback = null;
	private static volatile OceanCanvasConfigSyncPayload latestConfig = null;
	private static volatile OceanCanvasProjectSyncPayload latestProject = null;
	private static volatile OceanCanvasPlanningSyncPayload latestPlanning = null;
	private static final java.util.concurrent.atomic.AtomicLong planningGeneration = new java.util.concurrent.atomic.AtomicLong();
	private static volatile OceanCanvasWorkspaceSyncPayload latestWorkspace = null;
	private static final OceanCanvasMultipartSync.Assembler PROJECT_SYNC_ASSEMBLER = new OceanCanvasMultipartSync.Assembler(OceanCanvasMultipartSync.PROJECT_MAX_LOGICAL_BYTES);
	private static final OceanCanvasMultipartSync.Assembler PLANNING_SYNC_ASSEMBLER = new OceanCanvasMultipartSync.Assembler(OceanCanvasMultipartSync.PLANNING_MAX_LOGICAL_BYTES);
	private static final OceanCanvasMultipartSync.Assembler WORKSPACE_SYNC_ASSEMBLER = new OceanCanvasMultipartSync.Assembler(OceanCanvasMultipartSync.WORKSPACE_MAX_LOGICAL_BYTES);
	private static volatile boolean nextSessionNotified = false;
	private static volatile OceanCanvasInspectResponsePayload latestInspection = null;
	private static volatile OceanCanvasAnalysisResponsePayload latestAnalysis = null;
	private static volatile OceanCanvasHealthResponsePayload latestHealth = null;
	private static volatile OperationPreview latestOperationPreview = null;
	private static volatile String latestPhysicalHealth = "";
    private static volatile net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot latestPerformance = net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot.idle();
    private static volatile long performanceReceivedNanos;
    private static volatile net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View latestQueue=net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View.empty();
    // v253.78: decode aggregate text feeds exactly once when they arrive. UI/render getters
    // consume immutable parsed rows/maps instead of repeatedly splitting the same payload.
    private static volatile java.util.Map<String,String> latestConfigMap = java.util.Map.of();
    private static volatile java.util.List<String[]> projectRows = java.util.List.of();
    private static volatile java.util.List<String[]> planningRows = java.util.List.of();
    private static volatile java.util.List<String[]> workspaceRows = java.util.List.of();
    private static final java.util.concurrent.atomic.AtomicLong malformedAggregateRows = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * A feedback line plus the moment it arrived, so the screen can fade
	 * it out. {@code receivedAtMs} is wall-clock rather than a tick count
	 * on purpose: this is only ever compared against another reading of
	 * the same clock a few seconds later to decide an alpha, it must keep
	 * counting while the game is paused behind an open screen, and it is
	 * never persisted or sent anywhere.
	 */
	public record Feedback(String message, boolean error, long receivedAtMs) {
	}

	private OceanCanvasZoneClientCache() {
	}

	public static void register() {
		// Plain assignment, no main-thread hop: every one of these fields
		// is a volatile reference to an immutable payload, and the only
		// readers are render-thread draws that simply take whatever the
		// most recent one is. There is no read-modify-write to race on.
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSyncPayload.TYPE,
				(payload, context) -> latest = payload);
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasStructureSyncPayload.TYPE,
				(payload, context) -> latestStructures = payload);
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasJobStatusPayload.TYPE,
				(payload, context) -> latestJob = payload);
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasConfigSyncPayload.TYPE,
				(payload, context) -> { latestConfig = payload; latestConfigMap = parseConfig(payload == null ? null : payload.packed()); });
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasProjectSyncPayload.TYPE,
				(payload, context) -> {
					String complete = PROJECT_SYNC_ASSEMBLER.accept(payload.packed());
					if (complete != null) { latestProject = new OceanCanvasProjectSyncPayload(complete); projectRows = parseRows(complete); }
				});
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasPlanningSyncPayload.TYPE,
				(payload, context) -> {
					String complete = PLANNING_SYNC_ASSEMBLER.accept(payload.packed());
					if (complete != null) { latestPlanning = new OceanCanvasPlanningSyncPayload(complete); planningRows = parseRows(complete); planningGeneration.incrementAndGet(); }
				});
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasWorkspaceSyncPayload.TYPE,
				(payload, context) -> {
                    String complete = WORKSPACE_SYNC_ASSEMBLER.accept(payload.packed());
                    if (complete == null) return;
                    latestWorkspace = new OceanCanvasWorkspaceSyncPayload(complete);
                    workspaceRows = parseRows(complete);
                    if(!nextSessionNotified){
                        var items=nextSessionItems();
                        if(!items.isEmpty()){
                            nextSessionNotified=true;
                            context.client().execute(()->{if(context.client().player!=null){
                                String first=items.get(0).label();String msg="Ocean Canvas · Next session: "+first+(items.size()>1?" +"+(items.size()-1)+" more":"")+". Open Projects → Workbench to resume.";
                                context.client().gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(msg),false);
                            }});
                        }
                    }
                });
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasInspectResponsePayload.TYPE,
				(payload, context) -> latestInspection = payload);
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasAnalysisResponsePayload.TYPE,
				(payload, context) -> latestAnalysis = payload);
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasOperationPreviewResponsePayload.TYPE,
				(payload, context) -> latestOperationPreview=parseOperationPreview(payload.packed()));
	ClientPlayNetworking.registerGlobalReceiver(OceanCanvasHealthResponsePayload.TYPE,
				(payload, context) -> {
                    if(payload.packed()!=null && payload.packed().startsWith("P\t")) latestPhysicalHealth=payload.packed();
                    else latestHealth=payload;
                });
        ClientPlayNetworking.registerGlobalReceiver(OceanCanvasPregenTelemetryPayload.TYPE,(payload,context)->{
            latestPerformance=net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot.decode(payload.packed());
            performanceReceivedNanos=System.nanoTime();
        });
        ClientPlayNetworking.registerGlobalReceiver(OceanCanvasPregenQueuePayload.TYPE,(payload,context)->
                latestQueue=net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View.decode(payload.packed()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler,client)-> resetSession());
		ClientPlayNetworking.registerGlobalReceiver(OceanCanvasFeedbackPayload.TYPE,
				(payload, context) -> latestFeedback = new Feedback(
						payload.message() == null ? "" : payload.message(),
						payload.error(),
						System.currentTimeMillis()));
	}

	/** Null until the first packet has actually arrived from the server (shortly after joining a world with this mod). */
	public static OceanCanvasZoneSyncPayload latest() {
		return latest;
	}

	public static java.util.Map<String, String> config() { return latestConfigMap; }

    private static java.util.Map<String,String> parseConfig(String packed) {
        if (packed == null || packed.isBlank()) return java.util.Map.of();
        java.util.LinkedHashMap<String,String> out = new java.util.LinkedHashMap<>();
        for (String part : packed.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq > 0 && eq < part.length()-1) out.put(part.substring(0,eq), part.substring(eq+1));
            else if (!part.isBlank()) malformedAggregateRows.incrementAndGet();
        }
        return java.util.Map.copyOf(out);
    }

    private static java.util.List<String[]> parseRows(String packed) {
        if (packed == null || packed.isBlank()) return java.util.List.of();
        String[] lines = packed.split("\n", -1);
        if (lines.length > 65_536) { malformedAggregateRows.incrementAndGet(); return java.util.List.of(); }
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length > 64) { malformedAggregateRows.incrementAndGet(); continue; }
            out.add(fields);
        }
        return java.util.List.copyOf(out);
    }

    private static java.util.List<String[]> rows(OceanCanvasProjectSyncPayload ignored){ return projectRows; }
    private static java.util.List<String[]> rows(OceanCanvasPlanningSyncPayload ignored){ return planningRows; }
    private static java.util.List<String[]> rows(OceanCanvasWorkspaceSyncPayload ignored){ return workspaceRows; }

    /** Clears every server-derived client field so reconnecting cannot display stale state. */
    public static void resetSession() {
        latest = null; latestStructures = null; latestJob = null; latestFeedback = null; latestConfig = null;
        latestProject = null; latestPlanning = null; latestWorkspace = null; latestInspection = null; latestAnalysis = null; latestHealth = null;
        latestOperationPreview = null; latestPhysicalHealth = ""; latestConfigMap = java.util.Map.of();
        projectRows = java.util.List.of(); planningRows = java.util.List.of(); workspaceRows = java.util.List.of();
        latestPerformance = net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot.idle(); performanceReceivedNanos = 0L;
        latestQueue = net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View.empty(); nextSessionNotified = false;
        planningGeneration.incrementAndGet();
        PROJECT_SYNC_ASSEMBLER.reset(); PLANNING_SYNC_ASSEMBLER.reset(); WORKSPACE_SYNC_ASSEMBLER.reset();
    }

    public static long malformedAggregateRowCount() { return malformedAggregateRows.get(); }

	/** Empty list if no data has arrived yet, rather than forcing every caller to null-check. */
	public static List<OceanCanvasZoneSyncPayload.ZoneEntry> zones() {
		OceanCanvasZoneSyncPayload payload = latest;
		return payload == null ? List.of() : payload.zones();
	}

	/** Protected structure boxes for the map's structures layer - empty until the first broadcast arrives. */
	public static List<OceanCanvasStructureSyncPayload.StructureEntry> structures() {
		OceanCanvasStructureSyncPayload payload = latestStructures;
		return payload == null ? List.of() : payload.structures();
	}

	/**
	 * The most recent feedback line, or {@code null} if none has arrived
	 * or the last one is older than {@code maxAgeMs}. The age check lives
	 * here so every caller gets the same answer to "is this still worth
	 * showing" - and callers that want to fade it can ask for the record
	 * and do the arithmetic themselves.
	 */
	public static Feedback feedback(long maxAgeMs) {
		Feedback current = latestFeedback;
		if (current == null) {
			return null;
		}
		return System.currentTimeMillis() - current.receivedAtMs() > maxAgeMs ? null : current;
	}

	/**
	 * Drops the current feedback line early - used when the player takes
	 * a new action, so an old answer never sits over the map looking like
	 * a reply to something it was not.
	 */
	public static String analysisPacked() {
		OceanCanvasAnalysisResponsePayload p=latestAnalysis; return p==null||p.packed()==null?"":p.packed();
	}
    public static String healthPacked() {
        OceanCanvasHealthResponsePayload p=latestHealth; return p==null||p.packed()==null?"":p.packed();
    }
    public static String physicalHealthPacked() { return latestPhysicalHealth; }
    public static void clearPhysicalHealth() { latestPhysicalHealth=""; }
    public static net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.Snapshot performance(){return latestPerformance;}
    public static boolean performanceStale(){return performanceReceivedNanos==0 || System.nanoTime()-performanceReceivedNanos>5_000_000_000L;}
    public static net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View queue(){return latestQueue;}
    public static void clearQueue(){latestQueue=net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View.empty();}

	public static void clearFeedback() {
		latestFeedback = null;
	}

	public record OperationPreview(String kind,String target,String argument,String token,boolean blocked,int chunks,int processed,int canvas,int vanilla,int custom,int unknown,int linkedProjects,int linkedTasks,String compatibilitySignature,String summary,String blockers,String warnings,String resourceForecast,String resourceRisk,String workflowGate,String dryRunDiff,long receivedAtMs){
		public boolean matches(String k,String t,String a){return kind.equalsIgnoreCase(k==null?"":k)&&target.equals(t==null?"":t)&&argument.equals(a==null?"":a);}
		public boolean stale(long maxAgeMs){return System.currentTimeMillis()-receivedAtMs>maxAgeMs;}
	}
	private static OperationPreview parseOperationPreview(String packed){
		try{String[] p=(packed==null?"":packed).split("\t",-1);if(p.length<17)return null;return new OperationPreview(p[0],decode64(p[1]),decode64(p[2]),p[3],Boolean.parseBoolean(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7]),Integer.parseInt(p[8]),Integer.parseInt(p[9]),Integer.parseInt(p[10]),Integer.parseInt(p[11]),Integer.parseInt(p[12]),decode64(p[13]),decode64(p[14]),decode64(p[15]),decode64(p[16]),p.length>17?decode64(p[17]):"",p.length>18?decode64(p[18]):"",p.length>19?decode64(p[19]):"",p.length>20?decode64(p[20]):"",System.currentTimeMillis());}catch(Exception ex){return null;}
	}
	public static OperationPreview operationPreview(){return latestOperationPreview;}
	public static void clearOperationPreview(){latestOperationPreview=null;}

	/** Local-only validation feedback for map controls that can reject input before a packet is sent. */
	public static void pushLocalFeedback(String message, boolean error) {
		latestFeedback = new Feedback(message == null ? "" : message, error, System.currentTimeMillis());
	}


	public record ProjectRegion(String name, String stage, String notes, String templateId, String lifecycle) { }
	public record ProjectTemplate(String id, String displayName) { }
	public record PlanningReference(String id, String name, String assetId, boolean visible, double opacity,
			boolean locked, int minX, int minZ, int maxX, int maxZ, double rotation, int drawOrder, String registrationPoints) { }
	public record PlanningVector(String id, String type, String name, boolean visible, boolean locked,
			int drawOrder, String parentId, String points, int strokeArgb, int fillArgb, double widthBlocks,
			String scenarioId, boolean implemented, String elevationProfile, String guideData) {
        public boolean smooth(){ return guideData != null && java.util.Arrays.stream(guideData.split(";")).anyMatch(t -> t.equalsIgnoreCase("SMOOTH")); }
        public boolean bezier(){ return guideData != null && java.util.Arrays.stream(guideData.split(";")).anyMatch(t -> t.startsWith("BEZIER=")); }
    }
    public record PlanGroup(String id,String name,String parentId,boolean visible,boolean locked,double opacity,String category,int drawOrder) { }
    public record ViewPreset(String id,String name,String visibleGroups,String hiddenGroups,String visibleReferences,String hiddenReferences,String visibleReferenceSets,String hiddenReferenceSets) { }
    public record ReferenceSet(String id,String name,String referenceIds,boolean visible,boolean locked,int drawOrder,double opacity) { }
    public record PlanBookmark(String id,String name,int centerX,int centerZ,double zoom,String selectedObjectId,String presetId) { }
    public record BlueprintViewpoint(String id,String name,double x,double y,double z,float yaw,float pitch,String projection,String depth,float opacity) { }
    public record P4Artifact(String id,String kind,String name,String targetId,String points,double valueA,double valueB,String text,long createdAt) { }
    public record P4Finding(String feature,String targetId,int severity,int x,int z,String message) { }
    public record TerrainAsset(String id,String name,int minX,int minZ,int maxX,int maxZ,int seaLevel,String status,int revisionCount,String approvedGaea,String approvedWorldPainter,String placementData,String notes,String revisions,String planningObjectIds) { }
    public record P5Transform(String id,String name,String sourceTool,String targetTool,int originX,int originZ,double scale,double rotation,boolean flipX,boolean flipZ,int seaLevel,int floorY,String originConvention,long updatedAt) { }
    public record P5Recipe(String id,String name,String targetTool,String transformId,String boundsSource,int widthPx,int heightPx,String layerIds,String namingRule,String validationSteps,long updatedAt) { }
    public record P5Placement(String id,String name,String projectId,String terrainAssetId,String fileName,String fileHash,int originX,int originY,int originZ,int rotation,String mirror,String status,String version,String dependencies,String notes,long updatedAt) { }
    public record P5Quarantine(String id,String fileName,String fileHash,String targetType,String targetId,int minX,int minZ,int maxX,int maxZ,int widthPx,int heightPx,double blocksPerPixel,int seaLevel,String orientation,String status,String issues,long createdAt) { }
    public record P5MarkerSchema(String id,String name,String scope,String fields,String icon,String style,String notes,long updatedAt) { }
    public record P5Finding(String feature,String targetId,int severity,String message) { }
    public record P5FormatCapability(String target,String elevation,String vectors,String semantics,String groups,String anchors,String notes,String coordinates,String fingerprint) { }
    public record P5PluginCapability(String modId,boolean present,String version,String capabilities,String mode) { }
    public record ProgramFeature(String phase,String id,String name) { }
    public record ProgramEntry(String id,String phase,String featureId,String kind,String subjectId,String label,int x,int z,String state,String payload,String author,long createdAt,long updatedAt) { }
    public record ProgramEvidence(String id,String phase,String featureId,String subjectId,String state,int severity,String summary,int x,int z,long observedAt) { }
    public record ProgramPresence(String uuid,String name,String dimension,int x,int y,int z) { }
    public record ProgramCursor(String uuid,String name,String dimension,int x,int z,String subjectId,long updatedAt) { }
    public record TerrainRevision(String assetId,String id,String stage,String fileName,String fileHash,int minX,int minZ,int maxX,int maxZ,int widthPx,int heightPx,double blocksPerPixel,int seaLevel,String orientation,long createdAt,String notes) {
        public String reviewState(){var m=java.util.regex.Pattern.compile("REVIEW status=([A-Z]+)").matcher(notes==null?"":notes);return m.find()?m.group(1):"";}
        public double metric(String key){var m=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(key)+"=([-+0-9.EeNaInf]+)").matcher(notes==null?"":notes);if(!m.find())return Double.NaN;try{return Double.parseDouble(m.group(1));}catch(Exception e){return Double.NaN;}}
        public String userNote(){var m=java.util.regex.Pattern.compile("(?:^| )userNote=([^ ]+)").matcher(notes==null?"":notes);if(!m.find())return "";try{return new String(java.util.Base64.getUrlDecoder().decode(m.group(1)),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){return "";}}
    }

	private static String decode64(String raw) {
		if (raw == null || raw.isEmpty()) return "";
		try { return new String(java.util.Base64.getUrlDecoder().decode(raw), java.nio.charset.StandardCharsets.UTF_8); }
		catch (IllegalArgumentException ex) { return ""; }
	}

	public static String currentProject() {
		OceanCanvasProjectSyncPayload p = latestProject;
		if (p == null || p.packed() == null) return "";
		for (String[] f : rows(p)) {
			if (f.length >= 2 && "C".equals(f[0])) return decode64(f[1]);
		}
		return "";
	}

	public static String pregenProfile(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return "BALANCED";
        for(String[] f:rows(p)){if(f.length>=2&&"P".equals(f[0]))return f[1];}
        return "BALANCED";
    }

    public record PregenBenchmark(double chunksPerSecond,double tickMs,int preferredOutstanding,long updatedAt,
                                  int calibrationVersion,int successfulRuns,int samples,String observedProfile) {
        public boolean usable(){return calibrationVersion==net.oceancanvas.mod.performance.OceanCanvasPregenMetrics.CALIBRATION_VERSION
                &&successfulRuns>0&&samples>=10&&!observedProfile.isBlank();}
    }
    public static PregenBenchmark pregenBenchmark(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return null;
        for(String[] f:rows(p)){
            if(f.length>=9&&"B".equals(f[0]))try{return new PregenBenchmark(Double.parseDouble(f[1]),Double.parseDouble(f[2]),Integer.parseInt(f[3]),Long.parseLong(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),f[8]);}catch(Exception ignored){}
        }
        return null;
    }

    public record RecoveryCompatibility(boolean readOnly, String reason) { }
    public static RecoveryCompatibility recoveryCompatibility() {
        OceanCanvasProjectSyncPayload p=latestProject;
        if(p==null||p.packed()==null)return new RecoveryCompatibility(false,"");
        for(String[] f:rows(p)){
            if(f.length>=2&&"R".equals(f[0])){
                boolean readOnly="READ_ONLY".equals(f[1]);
                return new RecoveryCompatibility(readOnly,f.length>=3?decode64(f[2]):"");
            }
        }
        return new RecoveryCompatibility(false,"");
    }

    public record RecoveryHistory(long epochMillis,String kind,String phase,String requester,String detail,
                                  String scopeType,String scopeId,int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,String scopeDescriptor) {
        public boolean hasScope(){return scopeType!=null&&!scopeType.isBlank()&&minChunkX<=maxChunkX&&minChunkZ<=maxChunkZ;}
    }
    public record RecoverySnapshot(String id,long epochMillis,String label,String reason,int regions,long canvasStates,int physicalSeals) { }
    public record CompatibilityMatrixRow(String capability,String status,long epochMillis,String evidence) { }
    public static java.util.List<CompatibilityMatrixRow> compatibilityMatrix(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<CompatibilityMatrixRow>();
        for(String[] f:rows(p)){if(f.length>=5&&"V".equals(f[0]))try{out.add(new CompatibilityMatrixRow(decode64(f[1]),f[2],Long.parseLong(f[3]),decode64(f[4])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);
    }

    public static java.util.List<RecoveryHistory> recoveryHistory(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return java.util.List.of();
        java.util.List<RecoveryHistory> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){
            if(f.length>=6&&"H".equals(f[0]))try{
                String scopeType=f.length>=12?f[6]:"",scopeId=f.length>=12?decode64(f[7]):"";
                int minX=f.length>=12?Integer.parseInt(f[8]):1,minZ=f.length>=12?Integer.parseInt(f[9]):1,maxX=f.length>=12?Integer.parseInt(f[10]):0,maxZ=f.length>=12?Integer.parseInt(f[11]):0;
                String descriptor=f.length>=13?decode64(f[12]):"";
                out.add(new RecoveryHistory(Long.parseLong(f[1]),decode64(f[2]),f[3],decode64(f[4]),decode64(f[5]),scopeType,scopeId,minX,minZ,maxX,maxZ,descriptor));
            }catch(Exception ignored){}
        }
        return java.util.List.copyOf(out);
    }

    public static java.util.List<RecoverySnapshot> recoverySnapshots(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return java.util.List.of();
        java.util.List<RecoverySnapshot> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){
            if(f.length>=8&&"S".equals(f[0]))try{out.add(new RecoverySnapshot(decode64(f[1]),Long.parseLong(f[2]),decode64(f[3]),decode64(f[4]),Integer.parseInt(f[5]),Long.parseLong(f[6]),Integer.parseInt(f[7])));}catch(Exception ignored){}
        }
        return java.util.List.copyOf(out);
    }

    public static java.util.List<String> recoveryLatestDiff(){
        OceanCanvasProjectSyncPayload p=latestProject;if(p==null||p.packed()==null)return java.util.List.of();
        java.util.List<String> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=2&&"D".equals(f[0]))out.add(decode64(f[1]));
        }
        return java.util.List.copyOf(out);
    }

    public static java.util.List<ProjectTemplate> projectTemplates() {
		OceanCanvasProjectSyncPayload p = latestProject;
		if (p == null || p.packed() == null) return java.util.List.of();
		java.util.List<ProjectTemplate> out = new java.util.ArrayList<>();
		for (String[] f : rows(p)) {
			if (f.length >= 3 && "T".equals(f[0])) out.add(new ProjectTemplate(f[1], decode64(f[2])));
		}
		return java.util.List.copyOf(out);
	}

	public static java.util.Map<String, ProjectRegion> projectRegions() {
		OceanCanvasProjectSyncPayload p = latestProject;
		if (p == null || p.packed() == null || p.packed().isBlank()) return java.util.Map.of();
		java.util.Map<String, ProjectRegion> out = new java.util.LinkedHashMap<>();
		for (String[] f : rows(p)) {
			if (f.length < 4 || "C".equals(f[0]) || "T".equals(f[0]) || "P".equals(f[0]) || "B".equals(f[0]) || "R".equals(f[0])
					|| "H".equals(f[0]) || "S".equals(f[0]) || "D".equals(f[0]) || "V".equals(f[0])) continue;
			String name = decode64(f[0]);
			out.put(name.toLowerCase(java.util.Locale.ROOT), new ProjectRegion(name, f[1], decode64(f[2]), f[3], f.length>4?f[4]:"PLANNED"));
		}
		return java.util.Map.copyOf(out);
	}

	public static ProjectRegion projectRegion(String name) {
		if (name == null) return null;
		return projectRegions().get(name.toLowerCase(java.util.Locale.ROOT));
	}

	public static java.util.List<PlanningReference> planningReferences() {
		OceanCanvasPlanningSyncPayload p = latestPlanning;
		if (p == null || p.packed() == null || p.packed().isBlank()) return java.util.List.of();
		java.util.List<PlanningReference> out = new java.util.ArrayList<>();
		for (String[] f : rows(p)) {
			if (f.length < 13 || !"R".equals(f[0])) continue;
			try { out.add(new PlanningReference(decode64(f[1]), decode64(f[2]), decode64(f[3]), Boolean.parseBoolean(f[4]),
					Double.parseDouble(f[5]), Boolean.parseBoolean(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]),
					Integer.parseInt(f[9]), Integer.parseInt(f[10]), Double.parseDouble(f[11]), Integer.parseInt(f[12]), f.length > 13 ? decode64(f[13]) : "")); }
			catch (RuntimeException ignored) { }
		}
		return java.util.List.copyOf(out);
	}

	public static java.util.List<PlanGroup> planGroups(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<PlanGroup> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=9&&"G".equals(f[0]))try{out.add(new PlanGroup(decode64(f[1]),decode64(f[2]),decode64(f[3]),Boolean.parseBoolean(f[4]),Boolean.parseBoolean(f[5]),Double.parseDouble(f[6]),f[7],Integer.parseInt(f[8])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<ViewPreset> viewPresets(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<ViewPreset> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=5&&"E".equals(f[0]))out.add(new ViewPreset(decode64(f[1]),decode64(f[2]),decode64(f[3]),decode64(f[4]),f.length>5?decode64(f[5]):"",f.length>6?decode64(f[6]):"",f.length>7?decode64(f[7]):"",f.length>8?decode64(f[8]):""));}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<ReferenceSet> referenceSets(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<ReferenceSet> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=7&&"Q".equals(f[0]))try{out.add(new ReferenceSet(decode64(f[1]),decode64(f[2]),decode64(f[3]),Boolean.parseBoolean(f[4]),Boolean.parseBoolean(f[5]),Integer.parseInt(f[6]),f.length>7?Double.parseDouble(f[7]):1D));}catch(RuntimeException ignored){}}
        out.sort(java.util.Comparator.comparingInt(ReferenceSet::drawOrder).thenComparing(ReferenceSet::id));return java.util.List.copyOf(out);
    }
    public static java.util.List<PlanBookmark> planBookmarks(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<PlanBookmark> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=8&&"K".equals(f[0]))try{out.add(new PlanBookmark(decode64(f[1]),decode64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Double.parseDouble(f[5]),decode64(f[6]),decode64(f[7])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }

    public static long planningGeneration(){return planningGeneration.get();}
    public static java.util.List<BlueprintViewpoint> blueprintViewpoints(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<BlueprintViewpoint> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=11&&"Y".equals(f[0]))try{out.add(new BlueprintViewpoint(decode64(f[1]),decode64(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Double.parseDouble(f[5]),Float.parseFloat(f[6]),Float.parseFloat(f[7]),f[8],f[9],Float.parseFloat(f[10])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<TerrainAsset> terrainAssets(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<TerrainAsset> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=14&&"A".equals(f[0]))try{out.add(new TerrainAsset(decode64(f[1]),decode64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),f[8],Integer.parseInt(f[9]),decode64(f[10]),decode64(f[11]),decode64(f[12]),decode64(f[13]),f.length>14?decode64(f[14]):"",f.length>15?decode64(f[15]):""));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }

    public static java.util.List<TerrainRevision> terrainRevisions(String assetId){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();java.util.List<TerrainRevision> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=17&&"T".equals(f[0]))try{String aid=decode64(f[1]);if(!aid.equals(assetId))continue;out.add(new TerrainRevision(aid,decode64(f[2]),f[3],decode64(f[4]),decode64(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Integer.parseInt(f[10]),Integer.parseInt(f[11]),Double.parseDouble(f[12]),Integer.parseInt(f[13]),decode64(f[14]),Long.parseLong(f[15]),decode64(f[16])));}catch(RuntimeException ignored){}}
        out.sort(java.util.Comparator.comparingLong(TerrainRevision::createdAt));return java.util.List.copyOf(out);
    }

	public static java.util.List<PlanningVector> planningVectors() {
		OceanCanvasPlanningSyncPayload p = latestPlanning;
		if (p == null || p.packed() == null || p.packed().isBlank()) return java.util.List.of();
		java.util.List<PlanningVector> out = new java.util.ArrayList<>();
		for (String[] f : rows(p)) {
			if (f.length < 9 || !"V".equals(f[0])) continue;
			try {
				int stroke = f.length > 9 ? Integer.parseInt(f[9]) : 0xD055FFFF;
				int fill = f.length > 10 ? Integer.parseInt(f[10]) : 0x2055FFFF;
				double width = f.length > 11 ? Double.parseDouble(f[11]) : 0.0D;
				String scenario = f.length > 12 ? decode64(f[12]) : "";
				boolean implemented = f.length > 13 && Boolean.parseBoolean(f[13]);
				String elevation = f.length > 14 ? decode64(f[14]) : "";
                String guide = f.length > 15 ? decode64(f[15]) : "";
				out.add(new PlanningVector(decode64(f[1]), f[2], decode64(f[3]), Boolean.parseBoolean(f[4]),
						Boolean.parseBoolean(f[5]), Integer.parseInt(f[6]), decode64(f[7]), f[8], stroke, fill, width, scenario, implemented, elevation, guide));
			}
			catch (RuntimeException ignored) { }
		}
		return java.util.List.copyOf(out);
	}


    public static java.util.List<P4Artifact> p4Artifacts(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P4Artifact>();
        for(String[] f:rows(p)){if(f.length==10&&"4A".equals(f[0]))try{out.add(new P4Artifact(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),decode64(f[5]),Double.parseDouble(f[6]),Double.parseDouble(f[7]),decode64(f[8]),Long.parseLong(f[9])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<P4Finding> p4Findings(){
        OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P4Finding>();
        for(String[] f:rows(p)){if(f.length==7&&"4F".equals(f[0]))try{out.add(new P4Finding(f[1],decode64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),decode64(f[6])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }

    public static java.util.List<P5Transform> p5Transforms(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5Transform>();for(String[] f:rows(p)){if(f.length==15&&"5T".equals(f[0]))try{out.add(new P5Transform(decode64(f[1]),decode64(f[2]),f[3],f[4],Integer.parseInt(f[5]),Integer.parseInt(f[6]),Double.parseDouble(f[7]),Double.parseDouble(f[8]),Boolean.parseBoolean(f[9]),Boolean.parseBoolean(f[10]),Integer.parseInt(f[11]),Integer.parseInt(f[12]),decode64(f[13]),Long.parseLong(f[14])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5Recipe> p5Recipes(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5Recipe>();for(String[] f:rows(p)){if(f.length==12&&"5R".equals(f[0]))try{out.add(new P5Recipe(decode64(f[1]),decode64(f[2]),f[3],decode64(f[4]),f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),decode64(f[8]),decode64(f[9]),decode64(f[10]),Long.parseLong(f[11])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5Placement> p5Placements(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5Placement>();for(String[] f:rows(p)){if(f.length==17&&"5L".equals(f[0]))try{out.add(new P5Placement(decode64(f[1]),decode64(f[2]),decode64(f[3]),decode64(f[4]),decode64(f[5]),decode64(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Integer.parseInt(f[10]),f[11],f[12],decode64(f[13]),decode64(f[14]),decode64(f[15]),Long.parseLong(f[16])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5Quarantine> p5Quarantine(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5Quarantine>();for(String[] f:rows(p)){if(f.length==18&&"5Q".equals(f[0]))try{out.add(new P5Quarantine(decode64(f[1]),decode64(f[2]),decode64(f[3]),f[4],decode64(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Integer.parseInt(f[10]),Integer.parseInt(f[11]),Double.parseDouble(f[12]),Integer.parseInt(f[13]),decode64(f[14]),f[15],decode64(f[16]),Long.parseLong(f[17])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5MarkerSchema> p5MarkerSchemas(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5MarkerSchema>();for(String[] f:rows(p)){if(f.length==9&&"5M".equals(f[0]))try{out.add(new P5MarkerSchema(decode64(f[1]),decode64(f[2]),f[3],decode64(f[4]),decode64(f[5]),f[6],decode64(f[7]),Long.parseLong(f[8])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5Finding> p5Findings(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5Finding>();for(String[] f:rows(p)){if(f.length==5&&"5F".equals(f[0]))try{out.add(new P5Finding(f[1],decode64(f[2]),Integer.parseInt(f[3]),decode64(f[4])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<P5FormatCapability> p5FormatCapabilities(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5FormatCapability>();for(String[] f:rows(p)){if(f.length==10&&"5C".equals(f[0]))out.add(new P5FormatCapability(f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9]));}return java.util.List.copyOf(out);}
    public static java.util.List<P5PluginCapability> p5PluginCapabilities(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<P5PluginCapability>();for(String[] f:rows(p)){if(f.length==6&&"5P".equals(f[0]))try{out.add(new P5PluginCapability(f[1],Boolean.parseBoolean(f[2]),decode64(f[3]),decode64(f[4]),f[5]));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<ProgramFeature> programFeatures(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<ProgramFeature>();for(String[] f:rows(p)){if(f.length==4&&"PX".equals(f[0]))out.add(new ProgramFeature(f[1],f[2],decode64(f[3])));}return java.util.List.copyOf(out);}
    public static java.util.List<ProgramEntry> programEntries(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<ProgramEntry>();for(String[] f:rows(p)){if(f.length==14&&"PE".equals(f[0]))try{out.add(new ProgramEntry(decode64(f[1]),f[2],f[3],f[4],decode64(f[5]),decode64(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),f[9],decode64(f[10]),decode64(f[11]),Long.parseLong(f[12]),Long.parseLong(f[13])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<ProgramEvidence> programEvidence(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<ProgramEvidence>();for(String[] f:rows(p)){if(f.length==11&&"PV".equals(f[0]))try{out.add(new ProgramEvidence(decode64(f[1]),f[2],f[3],decode64(f[4]),f[5],Integer.parseInt(f[6]),decode64(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Long.parseLong(f[10])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<ProgramPresence> programPresence(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<ProgramPresence>();for(String[] f:rows(p)){if(f.length==7&&"PP".equals(f[0]))try{out.add(new ProgramPresence(f[1],decode64(f[2]),decode64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<ProgramCursor> programCursors(){OceanCanvasPlanningSyncPayload p=latestPlanning;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<ProgramCursor>();for(String[] f:rows(p)){if(f.length==8&&"PC".equals(f[0]))try{out.add(new ProgramCursor(f[1],decode64(f[2]),decode64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),decode64(f[6]),Long.parseLong(f[7])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static ProgramEvidence latestProgramEvidence(String featureId){ProgramEvidence hit=null;for(var e:programEvidence())if(e.featureId().equals(featureId)&&(hit==null||e.observedAt()>hit.observedAt()))hit=e;return hit;}
    public static ProgramEntry latestProgramEntry(String featureId){ProgramEntry hit=null;for(var e:programEntries())if(e.featureId().equals(featureId)&&(hit==null||e.updatedAt()>hit.updatedAt()))hit=e;return hit;}
    public static boolean programFeatureActive(String featureId){var e=latestProgramEntry(featureId);return e!=null&&!"STOPPED".equals(e.state())&&!"DISABLED".equals(e.state());}


	public record WorkspaceTask(String id, String title, int x, int z, String status, int priority, String regionName, String planningObjectId, String notes,
            String projectId, String parentTaskId, String dependencies, String checklist, double weight, String phaseId) { }
	public record WorkspaceChecklistItem(String id,String text,boolean complete) { }
	public static java.util.List<WorkspaceChecklistItem> workspaceChecklist(WorkspaceTask task){
		if(task==null||task.checklist()==null||task.checklist().isBlank())return java.util.List.of();
		java.util.List<WorkspaceChecklistItem> out=new java.util.ArrayList<>();
		for(String raw:task.checklist().split(";")){String[] f=raw.split(",",3);if(f.length<3)continue;try{out.add(new WorkspaceChecklistItem(decode64(f[0]),decode64(f[2]),Boolean.parseBoolean(f[1])));}catch(RuntimeException ignored){}}
		return java.util.List.copyOf(out);
	}
    public record WorkspaceProject(String id, String name, String status, String regionName, String templateId, String notes, double progress,
            String parentProjectId, String planningObjectIds, String terrainAssetIds, String milestones,String phases,String blockers,String currentTaskId,String milestoneRecords) { }
    public record WorkspacePhase(String id,String name,String status,int order){public boolean complete(){return "COMPLETE".equals(status)||"SKIPPED".equals(status);}}
    public record WorkspaceBlocker(String id,String text,boolean resolved,String scopeType,String scopeId){}
    public record WorkspaceMilestone(String id,String name,String status,String phaseId,String taskId,String notes,int progress,String targetDate){public boolean complete(){return "COMPLETE".equals(status)||"SKIPPED".equals(status);}}
    public record NextSessionItem(String id,String kind,String taskId,String projectId,String label,int x,int z,String regionName,long createdAt){}
    public record PipelineSnapshot(String id,String projectId,long epochMillis,String label,int plans,int implemented,int assets,int revisions,int gaea,int worldPainter,int placement,int tasks,int complete,int ready,int phasesComplete,int milestonesComplete,int blockers){}
    public record PrototypePlot(String id,String projectId,String name,int minX,int minZ,int maxX,int maxZ,String status,String terrainAssetId,long createdAt){public int width(){return maxX-minX+1;}public int depth(){return maxZ-minZ+1;}}
    public record TransitionZone(String id,String projectId,String name,int minX,int minZ,int maxX,int maxZ,int featherBlocks,boolean elevation,boolean biome,boolean water,boolean rivers,boolean coastline,String status){}
    public static java.util.List<PrototypePlot> prototypePlots(String projectId){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<PrototypePlot>();for(String[] f:rows(p)){if(f.length==11&&"E".equals(f[0]))try{var v=new PrototypePlot(decode64(f[1]),decode64(f[2]),decode64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),f[8],decode64(f[9]),Long.parseLong(f[10]));if(v.projectId().equals(projectId))out.add(v);}catch(RuntimeException ignored){}}out.sort(java.util.Comparator.comparingLong(PrototypePlot::createdAt).reversed());return java.util.List.copyOf(out);}
    public static java.util.List<TransitionZone> transitionZones(String projectId){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<TransitionZone>();for(String[] f:rows(p)){if(f.length==15&&"Z".equals(f[0]))try{var v=new TransitionZone(decode64(f[1]),decode64(f[2]),decode64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Boolean.parseBoolean(f[9]),Boolean.parseBoolean(f[10]),Boolean.parseBoolean(f[11]),Boolean.parseBoolean(f[12]),Boolean.parseBoolean(f[13]),f[14]);if(v.projectId().equals(projectId))out.add(v);}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<PipelineSnapshot> pipelineSnapshots(String projectId){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<PipelineSnapshot>();for(String[] f:rows(p)){if(f.length==18&&"R".equals(f[0]))try{var v=new PipelineSnapshot(decode64(f[1]),decode64(f[2]),Long.parseLong(f[3]),decode64(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Integer.parseInt(f[10]),Integer.parseInt(f[11]),Integer.parseInt(f[12]),Integer.parseInt(f[13]),Integer.parseInt(f[14]),Integer.parseInt(f[15]),Integer.parseInt(f[16]),Integer.parseInt(f[17]));if(v.projectId().equals(projectId))out.add(v);}catch(RuntimeException ignored){}}out.sort(java.util.Comparator.comparingLong(PipelineSnapshot::epochMillis).reversed());return java.util.List.copyOf(out);}
    public record AtlasStop(String id,String kind,String targetId,String title,int x,int z,int order){}
    public record AtlasRoute(String id,String name,String description,java.util.List<AtlasStop> stops){}
    public static java.util.List<AtlasRoute> atlasRoutes(){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<AtlasRoute>();for(String[] f:rows(p)){if(f.length==5&&"Y".equals(f[0]))try{var stops=new java.util.ArrayList<AtlasStop>();String packedStops=decode64(f[4]);if(!packedStops.isBlank())for(String raw:packedStops.split(";")){String[] s=raw.split(",",-1);if(s.length==7)stops.add(new AtlasStop(s[0],s[1],decode64(s[2]),decode64(s[3]),Integer.parseInt(s[4]),Integer.parseInt(s[5]),Integer.parseInt(s[6])));}stops.sort(java.util.Comparator.comparingInt(AtlasStop::order));out.add(new AtlasRoute(decode64(f[1]),decode64(f[2]),decode64(f[3]),java.util.List.copyOf(stops)));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<WorkspaceMilestone> workspaceMilestones(WorkspaceProject p){if(p==null||p.milestoneRecords()==null||p.milestoneRecords().isBlank())return java.util.List.of();var out=new java.util.ArrayList<WorkspaceMilestone>();for(String raw:p.milestoneRecords().split(";")){String[] f=raw.split(",",8);if(f.length<3)continue;try{out.add(new WorkspaceMilestone(f[0],decode64(f[1]),f[2],f.length>3?decode64(f[3]):"",f.length>4?decode64(f[4]):"",f.length>5?decode64(f[5]):"",f.length>6?Integer.parseInt(f[6]):0,f.length>7?decode64(f[7]):""));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static java.util.List<WorkspacePhase> workspacePhases(WorkspaceProject p){if(p==null||p.phases()==null||p.phases().isBlank())return java.util.List.of();var out=new java.util.ArrayList<WorkspacePhase>();for(String raw:p.phases().split(";")){String[] f=raw.split(",",4);if(f.length<4)continue;try{out.add(new WorkspacePhase(f[0],decode64(f[1]),f[2],Integer.parseInt(f[3])));}catch(RuntimeException ignored){}}out.sort(java.util.Comparator.comparingInt(WorkspacePhase::order));return java.util.List.copyOf(out);}
    public static java.util.List<WorkspaceBlocker> workspaceBlockers(WorkspaceProject p){if(p==null||p.blockers()==null||p.blockers().isBlank())return java.util.List.of();var out=new java.util.ArrayList<WorkspaceBlocker>();for(String raw:p.blockers().split(";")){String[] f=raw.split(",",5);if(f.length<3)continue;try{out.add(new WorkspaceBlocker(f[0],decode64(f[2]),Boolean.parseBoolean(f[1]),f.length>3?f[3]:"PROJECT",f.length>4?decode64(f[4]):""));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
	public record WorkspaceScenario(String id, String name, boolean visible) { }
	public record AtlasFeature(String id, String type, String name, int x, int z, String regionName, String color) { }
	public static java.util.List<AtlasFeature> atlasFeatures() {
		OceanCanvasWorkspaceSyncPayload p=latestWorkspace;
		if(p==null||p.packed()==null||p.packed().isBlank())return java.util.List.of();
		java.util.List<AtlasFeature> out=new java.util.ArrayList<>();
		for(String[] f:rows(p)){
			if(f.length!=8||!"F".equals(f[0]))continue;
			try{out.add(new AtlasFeature(decode64(f[1]),f[2],decode64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),decode64(f[6]),f[7]));}
			catch(RuntimeException ignored){}
		}
		return java.util.List.copyOf(out);
	}

	public static java.util.List<WorkspaceTask> workspaceTasks() {
		OceanCanvasWorkspaceSyncPayload p = latestWorkspace;
		if (p == null || p.packed() == null) return java.util.List.of();
		java.util.List<WorkspaceTask> out = new java.util.ArrayList<>();
		for (String[] f : rows(p)) {
			if (f.length < 10 || !"T".equals(f[0])) continue;
			try { out.add(new WorkspaceTask(decode64(f[1]),decode64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),f[5],Integer.parseInt(f[6]),decode64(f[7]),decode64(f[8]),decode64(f[9]),
                    f.length>10?decode64(f[10]):"",f.length>11?decode64(f[11]):"",f.length>12?decode64(f[12]):"",f.length>13?decode64(f[13]):"",f.length>14?Double.parseDouble(f[14]):1.0D,f.length>15?decode64(f[15]):"")); } catch(RuntimeException ignored) { }
		}
		return java.util.List.copyOf(out);
	}
	public static java.util.List<WorkspaceProject> workspaceProjects() {
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();
        java.util.List<WorkspaceProject> out=new java.util.ArrayList<>();
        for(String[] f:rows(p)){if(f.length>=8&&"P".equals(f[0])){try{out.add(new WorkspaceProject(decode64(f[1]),decode64(f[2]),f[3],decode64(f[4]),decode64(f[5]),decode64(f[6]),Double.parseDouble(f[7]),f.length>8?decode64(f[8]):"",f.length>9?decode64(f[9]):"",f.length>10?decode64(f[10]):"",f.length>11?decode64(f[11]):"",f.length>12?decode64(f[12]):"",f.length>13?decode64(f[13]):"",f.length>14?decode64(f[14]):"",f.length>15?decode64(f[15]):""));}catch(RuntimeException ignored){}}}
        return java.util.List.copyOf(out);
    }
    public static String worldMapMode(){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return "ALL";for(String[] f:rows(p)){if(f.length>=2&&"M".equals(f[0]))return f[1];}return "ALL";}
    public static java.util.List<NextSessionItem> nextSessionItems(){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<NextSessionItem>();for(String[] f:rows(p)){if(f.length>=10&&"N".equals(f[0]))try{out.add(new NextSessionItem(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),decode64(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),decode64(f[8]),Long.parseLong(f[9])));}catch(RuntimeException ignored){}}return java.util.List.copyOf(out);}
    public static String activeWorkProject(){OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return "";for(String[] f:rows(p)){if(f.length>=2&&"W".equals(f[0]))return decode64(f[1]);}return "";}

	public static String workspaceSessionNote() {
		OceanCanvasWorkspaceSyncPayload p=latestWorkspace; if(p==null||p.packed()==null)return "";
		for(String[] f:rows(p)){if(f.length>=3&&"A".equals(f[0]))return decode64(f[2]);} return "";
	}
	public static String activeScenario() {
		OceanCanvasWorkspaceSyncPayload p=latestWorkspace; if(p==null||p.packed()==null)return "";
		for(String[] f:rows(p)){if(f.length>=2&&"A".equals(f[0]))return decode64(f[1]);} return "";
	}
	public static java.util.List<WorkspaceScenario> scenarios() {
		OceanCanvasWorkspaceSyncPayload p=latestWorkspace; if(p==null||p.packed()==null)return java.util.List.of();
		java.util.List<WorkspaceScenario> out=new java.util.ArrayList<>(); for(String[] f:rows(p)){if(f.length>=4&&"S".equals(f[0]))out.add(new WorkspaceScenario(decode64(f[1]),decode64(f[2]),Boolean.parseBoolean(f[3])));} return java.util.List.copyOf(out);
	}

    public record CandidateEdit(String id,String targetType,String targetId,String observationId,String editType,String status,int x,int y,int z,String proposedTargetType,String proposedTargetId,String candidateGeometry,String baseGeometryFingerprint,String instruction,String rationale,String comparisonNote,long createdAt,long updatedAt) { }
    public static java.util.List<CandidateEdit> candidateEdits(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<CandidateEdit>();
        for(String[] f:rows(p)){if(!"C".equals(f[0]))continue;try{if(f.length==14)out.add(new CandidateEdit(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),f[5],f[6],Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),"","","","",decode64(f[10]),decode64(f[11]),"",Long.parseLong(f[12]),Long.parseLong(f[13])));else if(f.length==17)out.add(new CandidateEdit(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),f[5],f[6],Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),f[10],decode64(f[11]),"","",decode64(f[12]),decode64(f[13]),decode64(f[14]),Long.parseLong(f[15]),Long.parseLong(f[16])));else if(f.length==18)out.add(new CandidateEdit(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),f[5],f[6],Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),f[10],decode64(f[11]),decode64(f[12]),"",decode64(f[13]),decode64(f[14]),decode64(f[15]),Long.parseLong(f[16]),Long.parseLong(f[17])));else if(f.length==19)out.add(new CandidateEdit(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),f[5],f[6],Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),f[10],decode64(f[11]),decode64(f[12]),f[13],decode64(f[14]),decode64(f[15]),decode64(f[16]),Long.parseLong(f[17]),Long.parseLong(f[18])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static String geometryFingerprint(String raw){if(raw==null||raw.isBlank())return "";try{var md=java.security.MessageDigest.getInstance("SHA-256");byte[] d=md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));var b=new StringBuilder(64);for(byte x:d)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return b.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static String candidateBaseState(CandidateEdit c){if(c==null||c.baseGeometryFingerprint().isBlank())return "BASE UNKNOWN";if(!"PLAN".equals(c.proposedTargetType()))return "BASE UNKNOWN";String current=planningVectors().stream().filter(p->p.id().equalsIgnoreCase(c.proposedTargetId())).map(PlanningVector::points).findFirst().orElse("");if(current.isBlank())return "TARGET MISSING";return c.baseGeometryFingerprint().equals(geometryFingerprint(current))?"BASE CURRENT":"STALE BASE";}
    public static java.util.List<CandidateEdit> candidateEditsFor(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return candidateEdits().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(CandidateEdit::updatedAt).reversed()).toList();}
    public record WorldObservation(String id,String targetType,String targetId,String observationType,String status,int x,int y,int z,String revisionRef,String notes,long observedAt,long updatedAt) { }
    public static java.util.List<WorldObservation> worldObservations(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<WorldObservation>();
        for(String[] f:rows(p)){if(f.length!=13||!"O".equals(f[0]))continue;try{out.add(new WorldObservation(decode64(f[1]),f[2],decode64(f[3]),f[4],f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),decode64(f[9]),decode64(f[10]),Long.parseLong(f[11]),Long.parseLong(f[12])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<WorldObservation> worldObservationsFor(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return worldObservations().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(WorldObservation::observedAt).reversed()).toList();}
    public record WorldEvent(String id,String targetType,String targetId,String eventType,String title,String detail,String actor,String sourceRef,long happenedAt,long createdAt) { }
    public static java.util.List<WorldEvent> worldEvents(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<WorldEvent>();
        for(String[] f:rows(p)){if(f.length!=11||!"E".equals(f[0]))continue;try{out.add(new WorldEvent(decode64(f[1]),f[2],decode64(f[3]),f[4],decode64(f[5]),decode64(f[6]),decode64(f[7]),decode64(f[8]),Long.parseLong(f[9]),Long.parseLong(f[10])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<WorldEvent> worldEventsFor(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return worldEvents().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(WorldEvent::happenedAt).reversed()).toList();}
    public record DesignRevision(String id,String targetType,String targetId,String sourceCandidateId,String actor,String beforeGeometry,String afterGeometry,String beforeFingerprint,String afterFingerprint,String note,long createdAt) { }
    public static java.util.List<DesignRevision> designRevisions(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<DesignRevision>();
        for(String[] f:rows(p)){if(f.length!=12||!"D".equals(f[0]))continue;try{out.add(new DesignRevision(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),decode64(f[5]),decode64(f[6]),decode64(f[7]),f[8],f[9],decode64(f[10]),Long.parseLong(f[11])));}catch(RuntimeException ignored){}}
        return java.util.List.copyOf(out);
    }
    public static java.util.List<DesignRevision> designRevisionsFor(String targetType,String targetId){String tt=targetType==null?"PLAN":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return designRevisions().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(DesignRevision::createdAt).reversed()).toList();}
    public record WorldRelationship(String id,String fromType,String fromId,String relation,String toType,String toId,String notes,long updatedAt) { }
    public static java.util.List<WorldRelationship> worldRelationships(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<WorldRelationship>();
        for(String[] f:rows(p)){if(f.length!=9||!"R".equals(f[0]))continue;try{out.add(new WorldRelationship(decode64(f[1]),f[2],decode64(f[3]),f[4],f[5],decode64(f[6]),decode64(f[7]),Long.parseLong(f[8])));}catch(RuntimeException ignored){}}
        out.sort(java.util.Comparator.comparing(WorldRelationship::fromType).thenComparing(WorldRelationship::fromId).thenComparing(WorldRelationship::relation));return java.util.List.copyOf(out);
    }
    public static java.util.List<WorldRelationship> worldRelationshipsFor(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return worldRelationships().stream().filter(v->(v.fromType().equals(tt)&&v.fromId().equals(tid))||(v.toType().equals(tt)&&v.toId().equals(tid))).toList();}
    public record WorldClaim(String id,String targetType,String targetId,String context,String key,String value,String epistemicState,String evidenceType,String evidenceRef,String notes,long updatedAt) { }
    public static java.util.List<WorldClaim> worldClaims(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<WorldClaim>();
        for(String[] f:rows(p)){if(f.length!=12||!"K".equals(f[0]))continue;try{out.add(new WorldClaim(decode64(f[1]),f[2],decode64(f[3]),f[4],decode64(f[5]),decode64(f[6]),f[7],f[8],decode64(f[9]),decode64(f[10]),Long.parseLong(f[11])));}catch(RuntimeException ignored){}}
        out.sort(java.util.Comparator.comparing(WorldClaim::targetType).thenComparing(WorldClaim::targetId).thenComparing(WorldClaim::context).thenComparing(WorldClaim::key));return java.util.List.copyOf(out);
    }
    public static java.util.List<WorldClaim> worldClaims(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return worldClaims().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).toList();}
    public record AuthorshipProvenance(String id,String targetType,String targetId,String layer,String authorship,String source,String notes,long updatedAt) { }
    public static java.util.List<AuthorshipProvenance> authorshipProvenance(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();var out=new java.util.ArrayList<AuthorshipProvenance>();
        for(String[] f:rows(p)){if(f.length!=9||!"A".equals(f[0]))continue;try{out.add(new AuthorshipProvenance(decode64(f[1]),f[2],decode64(f[3]),f[4],f[5],decode64(f[6]),decode64(f[7]),Long.parseLong(f[8])));}catch(RuntimeException ignored){}}
        out.sort(java.util.Comparator.comparing(AuthorshipProvenance::targetType).thenComparing(AuthorshipProvenance::targetId).thenComparing(AuthorshipProvenance::layer));return java.util.List.copyOf(out);
    }
    public static java.util.List<AuthorshipProvenance> authorshipProvenance(String targetType,String targetId){String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');return authorshipProvenance().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).toList();}
    public record IntentResolution(String id,String targetType,String targetId,int level,String rationale,long updatedAt) {
        public String levelName(){return switch(level){case 0->"Unknown";case 1->"Broad";case 2->"Geographic";case 3->"Regional";case 4->"Detailed";default->"Authored";};}
    }
    public static java.util.List<IntentResolution> intentResolutions(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();
        var out=new java.util.ArrayList<IntentResolution>();
        for(String[] f:rows(p)){if(f.length!=7||!"I".equals(f[0]))continue;
            try{out.add(new IntentResolution(decode64(f[1]),f[2],decode64(f[3]),Integer.parseInt(f[4]),decode64(f[5]),Long.parseLong(f[6])));}catch(RuntimeException ignored){}
        }
        out.sort(java.util.Comparator.comparing(IntentResolution::targetType).thenComparing(IntentResolution::targetId));
        return java.util.List.copyOf(out);
    }
    public static IntentResolution intentResolution(String targetType,String targetId){
        String tt=targetType==null?"PROJECT":targetType.trim().toUpperCase(java.util.Locale.ROOT),tid=targetId==null?"":targetId.trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');
        for(var v:intentResolutions())if(v.targetType().equals(tt)&&v.targetId().equals(tid))return v;return null;
    }

    public record WorldPolicy(String id,String scopeType,String scopeId,String key,String value,boolean enabled,String rationale,long updatedAt)
            implements net.oceancanvas.mod.project.OceanCanvasWorldPolicyEvaluator.PolicyLike { }
    public static java.util.List<WorldPolicy> worldPolicies(){
        OceanCanvasWorkspaceSyncPayload p=latestWorkspace;if(p==null||p.packed()==null)return java.util.List.of();
        var out=new java.util.ArrayList<WorldPolicy>();
        for(String[] f:rows(p)){
            if((f.length!=8&&f.length!=9)||!"W".equals(f[0]))continue;
            try{out.add(new WorldPolicy(decode64(f[1]),f[2],decode64(f[3]),decode64(f[4]),decode64(f[5]),Boolean.parseBoolean(f[6]),decode64(f[7]),f.length>8?Long.parseLong(f[8]):0L));}
            catch(RuntimeException ignored){}
        }
        out.sort(java.util.Comparator.comparing(WorldPolicy::scopeType).thenComparing(WorldPolicy::key).thenComparing(WorldPolicy::id));
        return java.util.List.copyOf(out);
    }
    public static java.util.List<WorldPolicy> worldPolicies(String scopeType,String scopeId){
        String st=scopeType==null?"WORLD":scopeType.trim().toUpperCase(java.util.Locale.ROOT),sid=scopeId==null?"":scopeId.trim().toLowerCase(java.util.Locale.ROOT);
        if("WORLD".equals(st))sid="";
        final String fsid=sid;
        return worldPolicies().stream().filter(v->v.scopeType().equals(st)&&v.scopeId().equals(fsid)).toList();
    }

    public static java.util.List<net.oceancanvas.mod.project.OceanCanvasWorldPolicyEvaluator.Decision> effectiveWorldPolicies(String regionId,String projectId,String workAreaId){
        return net.oceancanvas.mod.project.OceanCanvasWorldPolicyEvaluator.evaluate(worldPolicies(),
                net.oceancanvas.mod.project.OceanCanvasWorldPolicyEvaluator.Context.of(regionId,projectId,workAreaId));
    }

	public static String inspectionPacked() {
		OceanCanvasInspectResponsePayload p = latestInspection;
		return p == null ? "" : p.packed();
	}

	/** The running pregen/reset/expand job, or {@code null} when nothing is running or nothing has been heard yet. */
	public static OceanCanvasJobStatusPayload job() {
		OceanCanvasJobStatusPayload payload = latestJob;
		return payload != null && payload.isRunning() ? payload : null;
	}
}

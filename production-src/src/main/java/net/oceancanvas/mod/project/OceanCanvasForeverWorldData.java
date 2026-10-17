package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bounded, non-destructive metadata for forever-world experimentation and seams.
 * This data never edits chunks by itself. Prototype promotion/discard and Transition Zone
 * review are deliberate workflow state changes; actual terrain mutation remains behind the
 * existing execution/preflight systems.
 */
public final class OceanCanvasForeverWorldData extends SavedData {
    public static final int MAX_PROTOTYPES=128, MAX_TRANSITIONS=128, MAX_PROTECTED_BUILDS=16384, MAX_WORLD_POLICIES=256, MAX_INTENT_RESOLUTIONS=2048, MAX_AUTHORSHIP_PROVENANCE=4096, MAX_WORLD_CLAIMS=8192, MAX_WORLD_RELATIONSHIPS=8192, MAX_WORLD_OBSERVATIONS=16384, MAX_WORLD_EVENTS=16384, MAX_CANDIDATE_EDITS=8192, MAX_DESIGN_REVISIONS=8192;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"forever_world_data");

    public record PrototypePlot(String id,String projectId,String name,int minX,int minZ,int maxX,int maxZ,
                                String status,String terrainAssetId,String notes,long createdAt,long updatedAt) {
        public PrototypePlot {
            projectId=cleanId(projectId); name=cleanName(name,"Prototype Plot"); status=parsePrototypeStatus(status);
            terrainAssetId=cleanId(terrainAssetId); notes=limit(notes,512); int ax=Math.min(minX,maxX),bx=Math.max(minX,maxX),az=Math.min(minZ,maxZ),bz=Math.max(minZ,maxZ);minX=ax;maxX=bx;minZ=az;maxZ=bz;
        }
        public int width(){return maxX-minX+1;} public int depth(){return maxZ-minZ+1;}
    }
    public record TransitionZone(String id,String projectId,String name,int minX,int minZ,int maxX,int maxZ,
                                 int featherBlocks,boolean elevation,boolean biome,boolean water,boolean rivers,boolean coastline,
                                 String status,String notes,long createdAt,long updatedAt) {
        public TransitionZone {
            projectId=cleanId(projectId);name=cleanName(name,"Transition Zone");status=parseTransitionStatus(status);notes=limit(notes,512);
            featherBlocks=Math.max(16,Math.min(4096,featherBlocks));int ax=Math.min(minX,maxX),bx=Math.max(minX,maxX),az=Math.min(minZ,maxZ),bz=Math.max(minZ,maxZ);minX=ax;maxX=bx;minZ=az;maxZ=bz;
        }
    }




    /** Durable, non-destructive stewardship rule. Scope inheritance is resolved by callers:
     * WORLD -> REGION -> PROJECT -> WORK_AREA. Policies never mutate chunks by themselves. */
    public record WorldPolicy(String id,String scopeType,String scopeId,String key,String value,
                              boolean enabled,String rationale,long createdAt,long updatedAt)
            implements OceanCanvasWorldPolicyEvaluator.PolicyLike {
        public WorldPolicy {
            id=cleanId(id); scopeType=parsePolicyScope(scopeType); scopeId=cleanId(scopeId);
            key=cleanPolicyKey(key); value=limit(value,256); rationale=limit(rationale,512);
            if("WORLD".equals(scopeType))scopeId="";
        }
    }

    /**
     * Declared authoring specificity for a stable World Model target.
     * L0 Unknown, L1 Broad, L2 Geographic, L3 Regional, L4 Detailed, L5 Authored.
     * Higher is not inherently better and this metadata never edits terrain.
     */
    public record IntentResolution(String id,String targetType,String targetId,int level,String rationale,long createdAt,long updatedAt) {
        public IntentResolution {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);
            level=Math.max(0,Math.min(5,level));rationale=limit(rationale,512);
            if(targetId.isBlank())throw new IllegalArgumentException("intent target ID required");
        }
        public String levelName(){return switch(level){case 0->"UNKNOWN";case 1->"BROAD";case 2->"GEOGRAPHIC";case 3->"REGIONAL";case 4->"DETAILED";default->"AUTHORED";};}
    }

    /**
     * Where a target/layer's actual detail came from. This is independent of Resolution of Intent:
     * approving externally generated detail does not relabel it as manually Authored.
     */
    public record AuthorshipProvenance(String id,String targetType,String targetId,String layer,String authorship,
                                       String source,String notes,long createdAt,long updatedAt) {
        public AuthorshipProvenance {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);
            layer=parseAuthorshipLayer(layer);authorship=parseAuthorship(authorship);
            source=limit(source,256);notes=limit(notes,512);
            if(targetId.isBlank())throw new IllegalArgumentException("authorship target ID required");
        }
    }

    /**
     * Evidence-backed statement about a stable World Model target.
     * Context separates Technical Reality, Design Intent, Player Experience and World Lore.
     * Confidence is semantic (KNOWN/BELIEVED/RUMORED/DISPUTED/UNKNOWN), never a fake percentage.
     */
    public record WorldClaim(String id,String targetType,String targetId,String context,String key,String value,
                             String epistemicState,String evidenceType,String evidenceRef,String notes,
                             long createdAt,long updatedAt) {
        public WorldClaim {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);
            context=parseKnowledgeContext(context);key=cleanPolicyKey(key);value=limit(value,512);
            epistemicState=parseEpistemicState(epistemicState);evidenceType=parseEvidenceType(evidenceType);
            evidenceRef=limit(evidenceRef,256);notes=limit(notes,512);
            if(targetId.isBlank())throw new IllegalArgumentException("claim target ID required");
            if(key.isBlank())throw new IllegalArgumentException("claim key required");
        }
    }

    /** Typed edge between two stable World Object references. */
    public record WorldRelationship(String id,String fromType,String fromId,String relation,String toType,String toId,
                                    String notes,long createdAt,long updatedAt) {
        public WorldRelationship {
            id=cleanId(id);fromType=parseIntentTargetType(fromType);fromId=cleanId(fromId);
            relation=parseRelationshipType(relation);toType=parseIntentTargetType(toType);toId=cleanId(toId);notes=limit(notes,512);
            if(fromId.isBlank()||toId.isBlank())throw new IllegalArgumentException("relationship endpoints required");
            if(fromType.equals(toType)&&fromId.equals(toId))throw new IllegalArgumentException("relationship cannot point to itself");
        }
    }

    /** Spatial/player observation attached to a stable World Object. Observations are evidence, not automatic design changes. */
    public record WorldObservation(String id,String targetType,String targetId,String observationType,String status,
                                   int x,int y,int z,String revisionRef,String notes,long observedAt,long updatedAt) {
        public WorldObservation {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);
            observationType=parseObservationType(observationType);status=parseObservationStatus(status);
            revisionRef=limit(revisionRef,128);notes=limit(notes,768);
            if(targetId.isBlank())throw new IllegalArgumentException("observation target ID required");
        }
    }

    /** Append-oriented historical event attached to a stable World Object. */
    public record WorldEvent(String id,String targetType,String targetId,String eventType,String title,String detail,
                             String actor,String sourceRef,long happenedAt,long createdAt) {
        public WorldEvent {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);
            eventType=parseWorldEventType(eventType);title=cleanName(title,"World Event");detail=limit(detail,1024);
            actor=limit(actor,128);sourceRef=limit(sourceRef,256);
            if(targetId.isBlank())throw new IllegalArgumentException("event target ID required");
        }
    }

    /** Reviewable design suggestion created from field evidence. Never mutates canonical Design by itself. */
    public record CandidateEdit(String id,String targetType,String targetId,String observationId,String editType,String status,
                                int x,int y,int z,String proposedTargetType,String proposedTargetId,String candidateGeometry,String baseGeometryFingerprint,String instruction,String rationale,String comparisonNote,long createdAt,long updatedAt) {
        public CandidateEdit {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);observationId=cleanId(observationId);
            editType=parseCandidateEditType(editType);status=parseCandidateEditStatus(status);proposedTargetType=proposedTargetType==null||proposedTargetType.isBlank()?"":parseIntentTargetType(proposedTargetType);proposedTargetId=cleanId(proposedTargetId);candidateGeometry=limit(candidateGeometry,16384);baseGeometryFingerprint=limit(baseGeometryFingerprint,64).toLowerCase(java.util.Locale.ROOT);if(!baseGeometryFingerprint.isBlank()&&!baseGeometryFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("candidate base fingerprint must be SHA-256 hex");instruction=limit(instruction,768);rationale=limit(rationale,768);comparisonNote=limit(comparisonNote,768);
            if(targetId.isBlank())throw new IllegalArgumentException("candidate target ID required");
            if(proposedTargetType.isBlank()!=proposedTargetId.isBlank())throw new IllegalArgumentException("candidate proposed target requires both type and ID");
        }
    }

    /** Durable immutable snapshot pair for canonical Design changes. Geometry only; physical terrain is never stored or changed here. */
    public record DesignRevision(String id,String targetType,String targetId,String sourceCandidateId,String actor,
                                 String beforeGeometry,String afterGeometry,String beforeFingerprint,String afterFingerprint,
                                 String note,long createdAt) {
        public DesignRevision {
            id=cleanId(id);targetType=parseIntentTargetType(targetType);targetId=cleanId(targetId);sourceCandidateId=cleanId(sourceCandidateId);
            actor=limit(actor,128);beforeGeometry=limit(beforeGeometry,16384);afterGeometry=limit(afterGeometry,16384);
            beforeFingerprint=limit(beforeFingerprint,64).toLowerCase(Locale.ROOT);afterFingerprint=limit(afterFingerprint,64).toLowerCase(Locale.ROOT);note=limit(note,768);
            if(targetId.isBlank())throw new IllegalArgumentException("design revision target ID required");
            if(!beforeFingerprint.matches("[0-9a-f]{64}")||!afterFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("design revision fingerprints must be SHA-256 hex");
        }
    }

    public record ProtectedBuild(long chunkKey,String projectId,String label,long createdAt) {
        public ProtectedBuild { projectId=cleanId(projectId); label=cleanName(label,"Protected Build"); }
        public int chunkX(){return (int)chunkKey;} public int chunkZ(){return (int)(chunkKey>>>32);}
    }

    private final List<String> prototypesPacked, transitionsPacked, protectedBuildsPacked, worldPoliciesPacked, intentResolutionsPacked, authorshipProvenancePacked, worldClaimsPacked, worldRelationshipsPacked, worldObservationsPacked, worldEventsPacked, candidateEditsPacked, designRevisionsPacked;
    private static final Codec<OceanCanvasForeverWorldData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.listOf().optionalFieldOf("prototypes",List.of()).forGetter(d->d.prototypesPacked),
            Codec.STRING.listOf().optionalFieldOf("transitions",List.of()).forGetter(d->d.transitionsPacked),
            Codec.STRING.listOf().optionalFieldOf("protectedBuilds",List.of()).forGetter(d->d.protectedBuildsPacked),
            Codec.STRING.listOf().optionalFieldOf("worldPolicies",List.of()).forGetter(d->d.worldPoliciesPacked),
            Codec.STRING.listOf().optionalFieldOf("intentResolutions",List.of()).forGetter(d->d.intentResolutionsPacked),
            Codec.STRING.listOf().optionalFieldOf("authorshipProvenance",List.of()).forGetter(d->d.authorshipProvenancePacked),
            Codec.STRING.listOf().optionalFieldOf("worldClaims",List.of()).forGetter(d->d.worldClaimsPacked),
            Codec.STRING.listOf().optionalFieldOf("worldRelationships",List.of()).forGetter(d->d.worldRelationshipsPacked),
            Codec.STRING.listOf().optionalFieldOf("worldObservations",List.of()).forGetter(d->d.worldObservationsPacked),
            Codec.STRING.listOf().optionalFieldOf("worldEvents",List.of()).forGetter(d->d.worldEventsPacked),
            Codec.STRING.listOf().optionalFieldOf("candidateEdits",List.of()).forGetter(d->d.candidateEditsPacked),
            Codec.STRING.listOf().optionalFieldOf("designRevisions",List.of()).forGetter(d->d.designRevisionsPacked)
    ).apply(i,OceanCanvasForeverWorldData::new));
    public static final SavedDataType<OceanCanvasForeverWorldData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasForeverWorldData::new,CODEC,null);
    public OceanCanvasForeverWorldData(){this(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());}
    private OceanCanvasForeverWorldData(List<String> p,List<String> t,List<String> b,List<String> w,List<String> intents,List<String> authorship,List<String> claims,List<String> relationships,List<String> observations,List<String> events,List<String> candidates,List<String> revisions){prototypesPacked=new ArrayList<>(tail(p,MAX_PROTOTYPES));transitionsPacked=new ArrayList<>(tail(t,MAX_TRANSITIONS));protectedBuildsPacked=new ArrayList<>(tail(b,MAX_PROTECTED_BUILDS));worldPoliciesPacked=new ArrayList<>(tail(w,MAX_WORLD_POLICIES));intentResolutionsPacked=new ArrayList<>(tail(intents,MAX_INTENT_RESOLUTIONS));authorshipProvenancePacked=new ArrayList<>(tail(authorship,MAX_AUTHORSHIP_PROVENANCE));worldClaimsPacked=new ArrayList<>(tail(claims,MAX_WORLD_CLAIMS));worldRelationshipsPacked=new ArrayList<>(tail(relationships,MAX_WORLD_RELATIONSHIPS));worldObservationsPacked=new ArrayList<>(tail(observations,MAX_WORLD_OBSERVATIONS));worldEventsPacked=new ArrayList<>(tail(events,MAX_WORLD_EVENTS));candidateEditsPacked=new ArrayList<>(tail(candidates,MAX_CANDIDATE_EDITS));designRevisionsPacked=new ArrayList<>(tail(revisions,MAX_DESIGN_REVISIONS));}
    public static OceanCanvasForeverWorldData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}

    public List<PrototypePlot> prototypes(){var out=new ArrayList<PrototypePlot>();for(String s:prototypesPacked){var v=decodePrototype(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<PrototypePlot> prototypes(String projectId){String id=cleanId(projectId);return prototypes().stream().filter(v->v.projectId().equals(id)).toList();}
    public List<TransitionZone> transitions(){var out=new ArrayList<TransitionZone>();for(String s:transitionsPacked){var v=decodeTransition(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<TransitionZone> transitions(String projectId){String id=cleanId(projectId);return transitions().stream().filter(v->v.projectId().equals(id)).toList();}
    public List<ProtectedBuild> protectedBuilds(){var out=new ArrayList<ProtectedBuild>();for(String s:protectedBuildsPacked){var v=decodeProtectedBuild(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<CandidateEdit> candidateEdits(){var out=new ArrayList<CandidateEdit>();for(String s:candidateEditsPacked){var v=decodeCandidateEdit(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<CandidateEdit> candidateEditsFor(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return candidateEdits().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(CandidateEdit::updatedAt).reversed()).toList();}
    public CandidateEdit candidateEdit(String id){String rid=cleanId(id);return candidateEdits().stream().filter(v->v.id().equals(rid)).findFirst().orElse(null);}
    public CandidateEdit setCandidateStatus(String id,String status,String comparisonNote){var v=candidateEdit(id);if(v==null)return null;return putCandidateEdit(v.id(),v.targetType(),v.targetId(),v.observationId(),v.editType(),status,v.x(),v.y(),v.z(),v.proposedTargetType(),v.proposedTargetId(),v.candidateGeometry(),v.baseGeometryFingerprint(),v.instruction(),v.rationale(),comparisonNote==null?v.comparisonNote():comparisonNote);}

    public CandidateEdit putCandidateEdit(String id,String targetType,String targetId,String observationId,String editType,String status,int x,int y,int z,String proposedTargetType,String proposedTargetId,String candidateGeometry,String baseGeometryFingerprint,String instruction,String rationale,String comparisonNote){
        String rid=cleanId(id),tt=parseIntentTargetType(targetType),tid=cleanId(targetId);long now=System.currentTimeMillis();
        for(int i=0;i<candidateEditsPacked.size();i++){var v=decodeCandidateEdit(candidateEditsPacked.get(i));if(v!=null&&!rid.isBlank()&&v.id().equals(rid)){String nextStatus=status;if((!java.util.Objects.equals(v.candidateGeometry(),candidateGeometry)||!java.util.Objects.equals(v.proposedTargetType(),proposedTargetType)||!java.util.Objects.equals(v.proposedTargetId(),cleanId(proposedTargetId))||!java.util.Objects.equals(v.baseGeometryFingerprint(),baseGeometryFingerprint))&&("REVIEWED".equals(v.status())||"APPROVED".equals(v.status())))nextStatus="DRAFT";var n=new CandidateEdit(v.id(),tt,tid,observationId,editType,nextStatus,x,y,z,proposedTargetType,proposedTargetId,candidateGeometry,baseGeometryFingerprint,instruction,rationale,comparisonNote,v.createdAt(),now);candidateEditsPacked.set(i,encodeCandidateEdit(n));setDirty();return n;}}
        if(candidateEditsPacked.size()>=MAX_CANDIDATE_EDITS)throw new IllegalArgumentException("candidate-edit limit reached (8192)");
        var n=new CandidateEdit(newId("candidate"),tt,tid,observationId,editType,status,x,y,z,proposedTargetType,proposedTargetId,candidateGeometry,baseGeometryFingerprint,instruction,rationale,comparisonNote,now,now);candidateEditsPacked.add(encodeCandidateEdit(n));setDirty();return n;
    }
    public boolean removeCandidateEdit(String id){String rid=cleanId(id);for(int i=0;i<candidateEditsPacked.size();i++){var v=decodeCandidateEdit(candidateEditsPacked.get(i));if(v!=null&&v.id().equals(rid)){candidateEditsPacked.remove(i);setDirty();return true;}}return false;}
    public List<WorldObservation> worldObservations(){var out=new ArrayList<WorldObservation>();for(String s:worldObservationsPacked){var v=decodeWorldObservation(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<WorldObservation> worldObservationsFor(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return worldObservations().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).toList();}
    public WorldObservation putWorldObservation(String id,String targetType,String targetId,String observationType,String status,int x,int y,int z,String revisionRef,String notes,long observedAt){
        String rid=cleanId(id),tt=parseIntentTargetType(targetType),tid=cleanId(targetId);long now=System.currentTimeMillis();if(observedAt<=0)observedAt=now;
        for(int i=0;i<worldObservationsPacked.size();i++){var v=decodeWorldObservation(worldObservationsPacked.get(i));if(v!=null&&!rid.isBlank()&&v.id().equals(rid)){var n=new WorldObservation(v.id(),tt,tid,observationType,status,x,y,z,revisionRef,notes,observedAt,now);worldObservationsPacked.set(i,encodeWorldObservation(n));setDirty();return n;}}
        if(worldObservationsPacked.size()>=MAX_WORLD_OBSERVATIONS)throw new IllegalArgumentException("world-observation limit reached (16384)");
        var n=new WorldObservation(newId("observation"),tt,tid,observationType,status,x,y,z,revisionRef,notes,observedAt,now);worldObservationsPacked.add(encodeWorldObservation(n));setDirty();return n;
    }
    public boolean removeWorldObservation(String id){String rid=cleanId(id);for(int i=0;i<worldObservationsPacked.size();i++){var v=decodeWorldObservation(worldObservationsPacked.get(i));if(v!=null&&v.id().equals(rid)){worldObservationsPacked.remove(i);setDirty();return true;}}return false;}
    public List<WorldEvent> worldEvents(){var out=new ArrayList<WorldEvent>();for(String s:worldEventsPacked){var v=decodeWorldEvent(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<WorldEvent> worldEventsFor(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return worldEvents().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(WorldEvent::happenedAt).reversed()).toList();}
    public WorldEvent addWorldEvent(String targetType,String targetId,String eventType,String title,String detail,String actor,String sourceRef,long happenedAt){
        if(worldEventsPacked.size()>=MAX_WORLD_EVENTS)throw new IllegalArgumentException("world-event limit reached (16384)");long now=System.currentTimeMillis();if(happenedAt<=0)happenedAt=now;
        var n=new WorldEvent(newId("event"),targetType,targetId,eventType,title,detail,actor,sourceRef,happenedAt,now);worldEventsPacked.add(encodeWorldEvent(n));setDirty();return n;
    }
    public List<DesignRevision> designRevisions(){var out=new ArrayList<DesignRevision>();for(String s:designRevisionsPacked){var v=decodeDesignRevision(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<DesignRevision> designRevisionsFor(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return designRevisions().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).sorted(java.util.Comparator.comparingLong(DesignRevision::createdAt).reversed()).toList();}
    public DesignRevision addDesignRevision(String targetType,String targetId,String sourceCandidateId,String actor,String beforeGeometry,String afterGeometry,String beforeFingerprint,String afterFingerprint,String note){
        if(designRevisionsPacked.size()>=MAX_DESIGN_REVISIONS)throw new IllegalArgumentException("design-revision limit reached (8192)");var n=new DesignRevision(newId("revision"),targetType,targetId,sourceCandidateId,actor,beforeGeometry,afterGeometry,beforeFingerprint,afterFingerprint,note,System.currentTimeMillis());designRevisionsPacked.add(encodeDesignRevision(n));setDirty();return n;
    }
    public List<WorldRelationship> worldRelationships(){var out=new ArrayList<WorldRelationship>();for(String s:worldRelationshipsPacked){var v=decodeWorldRelationship(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<WorldRelationship> worldRelationshipsFor(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return worldRelationships().stream().filter(v->(v.fromType().equals(tt)&&v.fromId().equals(tid))||(v.toType().equals(tt)&&v.toId().equals(tid))).toList();}
    public WorldRelationship putWorldRelationship(String id,String fromType,String fromId,String relation,String toType,String toId,String notes){
        String rid=cleanId(id),ft=parseIntentTargetType(fromType),fid=cleanId(fromId),rel=parseRelationshipType(relation),tt=parseIntentTargetType(toType),tid=cleanId(toId);long now=System.currentTimeMillis();
        int at=-1;WorldRelationship old=null;
        for(int i=0;i<worldRelationshipsPacked.size();i++){var v=decodeWorldRelationship(worldRelationshipsPacked.get(i));if(v==null)continue;if((!rid.isBlank()&&v.id().equals(rid))||(rid.isBlank()&&v.fromType().equals(ft)&&v.fromId().equals(fid)&&v.relation().equals(rel)&&v.toType().equals(tt)&&v.toId().equals(tid))){at=i;old=v;break;}}
        if(old!=null){var n=new WorldRelationship(old.id(),ft,fid,rel,tt,tid,notes,old.createdAt(),now);worldRelationshipsPacked.set(at,encodeWorldRelationship(n));setDirty();return n;}
        if(worldRelationshipsPacked.size()>=MAX_WORLD_RELATIONSHIPS)throw new IllegalArgumentException("world-relationship limit reached (8192)");
        var n=new WorldRelationship(newId("relation"),ft,fid,rel,tt,tid,notes,now,now);worldRelationshipsPacked.add(encodeWorldRelationship(n));setDirty();return n;
    }
    public boolean removeWorldRelationship(String id){String rid=cleanId(id);for(int i=0;i<worldRelationshipsPacked.size();i++){var v=decodeWorldRelationship(worldRelationshipsPacked.get(i));if(v!=null&&v.id().equals(rid)){worldRelationshipsPacked.remove(i);setDirty();return true;}}return false;}
    public List<WorldClaim> worldClaims(){var out=new ArrayList<WorldClaim>();for(String s:worldClaimsPacked){var v=decodeWorldClaim(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<WorldClaim> worldClaims(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return worldClaims().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).toList();}
    public WorldClaim putWorldClaim(String id,String targetType,String targetId,String context,String key,String value,String epistemicState,String evidenceType,String evidenceRef,String notes){
        String rid=cleanId(id),tt=parseIntentTargetType(targetType),tid=cleanId(targetId),ctx=parseKnowledgeContext(context),k=cleanPolicyKey(key);long now=System.currentTimeMillis();
        int at=-1;WorldClaim old=null;
        for(int i=0;i<worldClaimsPacked.size();i++){var v=decodeWorldClaim(worldClaimsPacked.get(i));if(v==null)continue;if((!rid.isBlank()&&v.id().equals(rid))||(rid.isBlank()&&v.targetType().equals(tt)&&v.targetId().equals(tid)&&v.context().equals(ctx)&&v.key().equals(k))){at=i;old=v;break;}}
        if(old!=null){var n=new WorldClaim(old.id(),tt,tid,ctx,k,value,epistemicState,evidenceType,evidenceRef,notes,old.createdAt(),now);worldClaimsPacked.set(at,encodeWorldClaim(n));setDirty();return n;}
        if(worldClaimsPacked.size()>=MAX_WORLD_CLAIMS)throw new IllegalArgumentException("world-claim limit reached (8192)");
        var n=new WorldClaim(newId("claim"),tt,tid,ctx,k,value,epistemicState,evidenceType,evidenceRef,notes,now,now);worldClaimsPacked.add(encodeWorldClaim(n));setDirty();return n;
    }
    public boolean removeWorldClaim(String id){String rid=cleanId(id);for(int i=0;i<worldClaimsPacked.size();i++){var v=decodeWorldClaim(worldClaimsPacked.get(i));if(v!=null&&v.id().equals(rid)){worldClaimsPacked.remove(i);setDirty();return true;}}return false;}
    public List<AuthorshipProvenance> authorshipProvenance(){var out=new ArrayList<AuthorshipProvenance>();for(String s:authorshipProvenancePacked){var v=decodeAuthorshipProvenance(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<AuthorshipProvenance> authorshipProvenance(String targetType,String targetId){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);return authorshipProvenance().stream().filter(v->v.targetType().equals(tt)&&v.targetId().equals(tid)).toList();}
    public AuthorshipProvenance authorshipProvenance(String targetType,String targetId,String layer){String tt=parseIntentTargetType(targetType),tid=cleanId(targetId),l=parseAuthorshipLayer(layer);for(var v:authorshipProvenance())if(v.targetType().equals(tt)&&v.targetId().equals(tid)&&v.layer().equals(l))return v;return null;}
    public AuthorshipProvenance putAuthorshipProvenance(String id,String targetType,String targetId,String layer,String authorship,String source,String notes){
        String tt=parseIntentTargetType(targetType),tid=cleanId(targetId),l=parseAuthorshipLayer(layer),rid=cleanId(id);long now=System.currentTimeMillis();
        int at=-1;AuthorshipProvenance old=null;
        for(int i=0;i<authorshipProvenancePacked.size();i++){var v=decodeAuthorshipProvenance(authorshipProvenancePacked.get(i));if(v==null)continue;if((!rid.isBlank()&&v.id().equals(rid))||(rid.isBlank()&&v.targetType().equals(tt)&&v.targetId().equals(tid)&&v.layer().equals(l))){at=i;old=v;break;}}
        if(old!=null){var n=new AuthorshipProvenance(old.id(),tt,tid,l,authorship,source,notes,old.createdAt(),now);authorshipProvenancePacked.set(at,encodeAuthorshipProvenance(n));setDirty();return n;}
        if(authorshipProvenancePacked.size()>=MAX_AUTHORSHIP_PROVENANCE)throw new IllegalArgumentException("authorship-provenance limit reached (4096)");
        var n=new AuthorshipProvenance(newId("authorship"),tt,tid,l,authorship,source,notes,now,now);authorshipProvenancePacked.add(encodeAuthorshipProvenance(n));setDirty();return n;
    }
    public boolean removeAuthorshipProvenance(String id){String rid=cleanId(id);for(int i=0;i<authorshipProvenancePacked.size();i++){var v=decodeAuthorshipProvenance(authorshipProvenancePacked.get(i));if(v!=null&&v.id().equals(rid)){authorshipProvenancePacked.remove(i);setDirty();return true;}}return false;}
    public List<IntentResolution> intentResolutions(){var out=new ArrayList<IntentResolution>();for(String s:intentResolutionsPacked){var v=decodeIntentResolution(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public IntentResolution intentResolution(String targetType,String targetId){
        String tt=parseIntentTargetType(targetType),tid=cleanId(targetId);
        for(var v:intentResolutions())if(v.targetType().equals(tt)&&v.targetId().equals(tid))return v;return null;
    }
    public IntentResolution putIntentResolution(String id,String targetType,String targetId,int level,String rationale){
        String tt=parseIntentTargetType(targetType),tid=cleanId(targetId),rid=cleanId(id);long now=System.currentTimeMillis();
        if(tid.isBlank())throw new IllegalArgumentException("intent target ID required");
        int at=-1;IntentResolution old=null;
        for(int i=0;i<intentResolutionsPacked.size();i++){var v=decodeIntentResolution(intentResolutionsPacked.get(i));if(v==null)continue;if((!rid.isBlank()&&v.id().equals(rid))||(rid.isBlank()&&v.targetType().equals(tt)&&v.targetId().equals(tid))){at=i;old=v;break;}}
        if(old!=null){var n=new IntentResolution(old.id(),tt,tid,level,rationale,old.createdAt(),now);intentResolutionsPacked.set(at,encodeIntentResolution(n));setDirty();return n;}
        if(intentResolutionsPacked.size()>=MAX_INTENT_RESOLUTIONS)throw new IllegalArgumentException("intent-resolution limit reached (2048)");
        var n=new IntentResolution(newId("intent"),tt,tid,level,rationale,now,now);intentResolutionsPacked.add(encodeIntentResolution(n));setDirty();return n;
    }
    public boolean removeIntentResolution(String id){String rid=cleanId(id);for(int i=0;i<intentResolutionsPacked.size();i++){var v=decodeIntentResolution(intentResolutionsPacked.get(i));if(v!=null&&v.id().equals(rid)){intentResolutionsPacked.remove(i);setDirty();return true;}}return false;}
    public List<WorldPolicy> worldPolicies(){var out=new ArrayList<WorldPolicy>();for(String s:worldPoliciesPacked){var v=decodeWorldPolicy(s);if(v!=null)out.add(v);}return List.copyOf(out);}
    public List<WorldPolicy> worldPolicies(String scopeType,String scopeId){String st=parsePolicyScope(scopeType),sid=cleanId(scopeId);return worldPolicies().stream().filter(v->v.scopeType().equals(st)&&v.scopeId().equals("WORLD".equals(st)?"":sid)).toList();}
    public WorldPolicy putWorldPolicy(String id,String scopeType,String scopeId,String key,String value,boolean enabled,String rationale){
        String pid=cleanId(id);long now=System.currentTimeMillis();
        if(pid.isBlank())pid=newId("policy");
        for(int i=0;i<worldPoliciesPacked.size();i++){var old=decodeWorldPolicy(worldPoliciesPacked.get(i));if(old!=null&&old.id().equals(pid)){var n=new WorldPolicy(pid,scopeType,scopeId,key,value,enabled,rationale,old.createdAt(),now);worldPoliciesPacked.set(i,encodeWorldPolicy(n));setDirty();return n;}}
        if(worldPoliciesPacked.size()>=MAX_WORLD_POLICIES)throw new IllegalArgumentException("world-policy limit reached (256)");
        var n=new WorldPolicy(pid,scopeType,scopeId,key,value,enabled,rationale,now,now);worldPoliciesPacked.add(encodeWorldPolicy(n));setDirty();return n;
    }
    public boolean removeWorldPolicy(String id){String pid=cleanId(id);for(int i=0;i<worldPoliciesPacked.size();i++){var v=decodeWorldPolicy(worldPoliciesPacked.get(i));if(v!=null&&v.id().equals(pid)){worldPoliciesPacked.remove(i);setDirty();return true;}}return false;}
    public List<OceanCanvasWorldPolicyEvaluator.Decision> evaluateWorldPolicies(String regionId,String projectId,String workAreaId){
        return OceanCanvasWorldPolicyEvaluator.evaluate(worldPolicies(),OceanCanvasWorldPolicyEvaluator.Context.of(regionId,projectId,workAreaId));
    }
    public OceanCanvasWorldPolicyEvaluator.Decision evaluateWorldPolicy(String key,String regionId,String projectId,String workAreaId){
        return OceanCanvasWorldPolicyEvaluator.evaluateKey(worldPolicies(),OceanCanvasWorldPolicyEvaluator.Context.of(regionId,projectId,workAreaId),key);
    }
    public ProtectedBuild protectedBuild(long chunkKey){for(var v:protectedBuilds())if(v.chunkKey()==chunkKey)return v;return null;}
    public boolean isProtectedBuild(long chunkKey){return protectedBuild(chunkKey)!=null;}
    public ProtectedBuild protectBuild(long chunkKey,String projectId,String label){
        for(int i=0;i<protectedBuildsPacked.size();i++){var v=decodeProtectedBuild(protectedBuildsPacked.get(i));if(v!=null&&v.chunkKey()==chunkKey){var n=new ProtectedBuild(chunkKey,projectId,label,v.createdAt());protectedBuildsPacked.set(i,encodeProtectedBuild(n));setDirty();return n;}}
        if(protectedBuildsPacked.size()>=MAX_PROTECTED_BUILDS)throw new IllegalArgumentException("protected-build chunk limit reached (16384)");
        var n=new ProtectedBuild(chunkKey,projectId,label,System.currentTimeMillis());protectedBuildsPacked.add(encodeProtectedBuild(n));setDirty();return n;
    }
    public boolean unprotectBuild(long chunkKey){for(int i=0;i<protectedBuildsPacked.size();i++){var v=decodeProtectedBuild(protectedBuildsPacked.get(i));if(v!=null&&v.chunkKey()==chunkKey){protectedBuildsPacked.remove(i);setDirty();return true;}}return false;}

    public PrototypePlot createPrototype(String projectId,String name,int centerX,int centerZ,int size){
        if(prototypesPacked.size()>=MAX_PROTOTYPES)throw new IllegalArgumentException("prototype plot limit reached (128)");
        if(size!=512&&size!=1024)throw new IllegalArgumentException("prototype size must be 512 or 1024 blocks");
        int half=size/2;long now=System.currentTimeMillis();var p=new PrototypePlot(newId("prototype"),projectId,name,centerX-half,centerZ-half,centerX+half-1,centerZ+half-1,"ACTIVE","","",now,now);prototypesPacked.add(encodePrototype(p));setDirty();return p;
    }
    public void setPrototypeStatus(String id,String status){int at=prototypeIndex(id);var p=decodePrototype(prototypesPacked.get(at));String s=parsePrototypeStatus(status);prototypesPacked.set(at,encodePrototype(new PrototypePlot(p.id(),p.projectId(),p.name(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),s,p.terrainAssetId(),p.notes(),p.createdAt(),System.currentTimeMillis())));setDirty();}
    public void linkPrototypeAsset(String id,String assetId){int at=prototypeIndex(id);var p=decodePrototype(prototypesPacked.get(at));prototypesPacked.set(at,encodePrototype(new PrototypePlot(p.id(),p.projectId(),p.name(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),p.status(),assetId,p.notes(),p.createdAt(),System.currentTimeMillis())));setDirty();}

    public TransitionZone createTransition(String projectId,String name,int minX,int minZ,int maxX,int maxZ,int featherBlocks){
        if(transitionsPacked.size()>=MAX_TRANSITIONS)throw new IllegalArgumentException("Transition Zone limit reached (128)");
        long now=System.currentTimeMillis();var z=new TransitionZone(newId("transition"),projectId,name,minX,minZ,maxX,maxZ,featherBlocks,true,true,true,true,true,"PLANNED","",now,now);transitionsPacked.add(encodeTransition(z));setDirty();return z;
    }
    public void setTransitionStatus(String id,String status){int at=transitionIndex(id);var z=decodeTransition(transitionsPacked.get(at));transitionsPacked.set(at,encodeTransition(copy(z,z.featherBlocks(),parseTransitionStatus(status),z.elevation(),z.biome(),z.water(),z.rivers(),z.coastline())));setDirty();}
    public void setTransitionFeather(String id,int feather){int at=transitionIndex(id);var z=decodeTransition(transitionsPacked.get(at));transitionsPacked.set(at,encodeTransition(copy(z,feather,z.status(),z.elevation(),z.biome(),z.water(),z.rivers(),z.coastline())));setDirty();}
    public void toggleTransitionLayer(String id,String layer){int at=transitionIndex(id);var z=decodeTransition(transitionsPacked.get(at));boolean e=z.elevation(),b=z.biome(),w=z.water(),r=z.rivers(),c=z.coastline();switch(layer.toUpperCase(Locale.ROOT)){case "ELEVATION"->e=!e;case "BIOME"->b=!b;case "WATER"->w=!w;case "RIVERS"->r=!r;case "COASTLINE"->c=!c;default->throw new IllegalArgumentException("unknown Transition Zone layer");}transitionsPacked.set(at,encodeTransition(copy(z,z.featherBlocks(),z.status(),e,b,w,r,c)));setDirty();}
    private static TransitionZone copy(TransitionZone z,int f,String s,boolean e,boolean b,boolean w,boolean r,boolean c){return new TransitionZone(z.id(),z.projectId(),z.name(),z.minX(),z.minZ(),z.maxX(),z.maxZ(),f,e,b,w,r,c,s,z.notes(),z.createdAt(),System.currentTimeMillis());}

    private int prototypeIndex(String id){for(int i=0;i<prototypesPacked.size();i++){var p=decodePrototype(prototypesPacked.get(i));if(p!=null&&p.id().equals(id))return i;}throw new IllegalArgumentException("unknown prototype plot");}
    private int transitionIndex(String id){for(int i=0;i<transitionsPacked.size();i++){var z=decodeTransition(transitionsPacked.get(i));if(z!=null&&z.id().equals(id))return i;}throw new IllegalArgumentException("unknown Transition Zone");}
    private static String encodePrototype(PrototypePlot p){return String.join("\t",p.id(),b64(p.projectId()),b64(p.name()),Integer.toString(p.minX()),Integer.toString(p.minZ()),Integer.toString(p.maxX()),Integer.toString(p.maxZ()),p.status(),b64(p.terrainAssetId()),b64(p.notes()),Long.toString(p.createdAt()),Long.toString(p.updatedAt()));}
    private static PrototypePlot decodePrototype(String s){try{String[] f=s.split("\t",-1);if(f.length!=12)return null;return new PrototypePlot(f[0],unb64(f[1]),unb64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),f[7],unb64(f[8]),unb64(f[9]),Long.parseLong(f[10]),Long.parseLong(f[11]));}catch(RuntimeException e){return null;}}
    private static String encodeTransition(TransitionZone z){return String.join("\t",z.id(),b64(z.projectId()),b64(z.name()),Integer.toString(z.minX()),Integer.toString(z.minZ()),Integer.toString(z.maxX()),Integer.toString(z.maxZ()),Integer.toString(z.featherBlocks()),Boolean.toString(z.elevation()),Boolean.toString(z.biome()),Boolean.toString(z.water()),Boolean.toString(z.rivers()),Boolean.toString(z.coastline()),z.status(),b64(z.notes()),Long.toString(z.createdAt()),Long.toString(z.updatedAt()));}
    private static TransitionZone decodeTransition(String s){try{String[] f=s.split("\t",-1);if(f.length!=17)return null;return new TransitionZone(f[0],unb64(f[1]),unb64(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Boolean.parseBoolean(f[8]),Boolean.parseBoolean(f[9]),Boolean.parseBoolean(f[10]),Boolean.parseBoolean(f[11]),Boolean.parseBoolean(f[12]),f[13],unb64(f[14]),Long.parseLong(f[15]),Long.parseLong(f[16]));}catch(RuntimeException e){return null;}}
    public String clientPrototype(PrototypePlot p){return String.join("\t","E",b64(p.id()),b64(p.projectId()),b64(p.name()),Integer.toString(p.minX()),Integer.toString(p.minZ()),Integer.toString(p.maxX()),Integer.toString(p.maxZ()),p.status(),b64(p.terrainAssetId()),Long.toString(p.createdAt()));}
    public String clientTransition(TransitionZone z){return String.join("\t","Z",b64(z.id()),b64(z.projectId()),b64(z.name()),Integer.toString(z.minX()),Integer.toString(z.minZ()),Integer.toString(z.maxX()),Integer.toString(z.maxZ()),Integer.toString(z.featherBlocks()),Boolean.toString(z.elevation()),Boolean.toString(z.biome()),Boolean.toString(z.water()),Boolean.toString(z.rivers()),Boolean.toString(z.coastline()),z.status());}
    private static String encodeCandidateEdit(CandidateEdit v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),b64(v.observationId()),v.editType(),v.status(),Integer.toString(v.x()),Integer.toString(v.y()),Integer.toString(v.z()),v.proposedTargetType(),b64(v.proposedTargetId()),b64(v.candidateGeometry()),v.baseGeometryFingerprint(),b64(v.instruction()),b64(v.rationale()),b64(v.comparisonNote()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static CandidateEdit decodeCandidateEdit(String s){try{String[] f=s.split("\\t",-1);if(f.length==13)return new CandidateEdit(f[0],f[1],unb64(f[2]),unb64(f[3]),f[4],f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),"","","","",unb64(f[9]),unb64(f[10]),"",Long.parseLong(f[11]),Long.parseLong(f[12]));if(f.length==16)return new CandidateEdit(f[0],f[1],unb64(f[2]),unb64(f[3]),f[4],f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),f[9],unb64(f[10]),"","",unb64(f[11]),unb64(f[12]),unb64(f[13]),Long.parseLong(f[14]),Long.parseLong(f[15]));if(f.length==17)return new CandidateEdit(f[0],f[1],unb64(f[2]),unb64(f[3]),f[4],f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),f[9],unb64(f[10]),unb64(f[11]),"",unb64(f[12]),unb64(f[13]),unb64(f[14]),Long.parseLong(f[15]),Long.parseLong(f[16]));if(f.length!=18)return null;return new CandidateEdit(f[0],f[1],unb64(f[2]),unb64(f[3]),f[4],f[5],Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),f[9],unb64(f[10]),unb64(f[11]),f[12],unb64(f[13]),unb64(f[14]),unb64(f[15]),Long.parseLong(f[16]),Long.parseLong(f[17]));}catch(RuntimeException e){return null;}}
    public String clientCandidateEdit(CandidateEdit v){return String.join("\t","C",b64(v.id()),v.targetType(),b64(v.targetId()),b64(v.observationId()),v.editType(),v.status(),Integer.toString(v.x()),Integer.toString(v.y()),Integer.toString(v.z()),v.proposedTargetType(),b64(v.proposedTargetId()),b64(v.candidateGeometry()),v.baseGeometryFingerprint(),b64(v.instruction()),b64(v.rationale()),b64(v.comparisonNote()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static String encodeWorldObservation(WorldObservation v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),v.observationType(),v.status(),Integer.toString(v.x()),Integer.toString(v.y()),Integer.toString(v.z()),b64(v.revisionRef()),b64(v.notes()),Long.toString(v.observedAt()),Long.toString(v.updatedAt()));}
    private static WorldObservation decodeWorldObservation(String s){try{String[] f=s.split("\\t",-1);if(f.length!=12)return null;return new WorldObservation(f[0],f[1],unb64(f[2]),f[3],f[4],Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),unb64(f[8]),unb64(f[9]),Long.parseLong(f[10]),Long.parseLong(f[11]));}catch(RuntimeException e){return null;}}
    public String clientWorldObservation(WorldObservation v){return String.join("\t","O",b64(v.id()),v.targetType(),b64(v.targetId()),v.observationType(),v.status(),Integer.toString(v.x()),Integer.toString(v.y()),Integer.toString(v.z()),b64(v.revisionRef()),b64(v.notes()),Long.toString(v.observedAt()),Long.toString(v.updatedAt()));}
    private static String encodeDesignRevision(DesignRevision v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),b64(v.sourceCandidateId()),b64(v.actor()),b64(v.beforeGeometry()),b64(v.afterGeometry()),v.beforeFingerprint(),v.afterFingerprint(),b64(v.note()),Long.toString(v.createdAt()));}
    private static DesignRevision decodeDesignRevision(String s){try{String[] f=s.split("\\t",-1);if(f.length!=11)return null;return new DesignRevision(f[0],f[1],unb64(f[2]),unb64(f[3]),unb64(f[4]),unb64(f[5]),unb64(f[6]),f[7],f[8],unb64(f[9]),Long.parseLong(f[10]));}catch(RuntimeException e){return null;}}
    public String clientDesignRevision(DesignRevision v){return String.join("\t","D",b64(v.id()),v.targetType(),b64(v.targetId()),b64(v.sourceCandidateId()),b64(v.actor()),b64(v.beforeGeometry()),b64(v.afterGeometry()),v.beforeFingerprint(),v.afterFingerprint(),b64(v.note()),Long.toString(v.createdAt()));}
    private static String encodeWorldEvent(WorldEvent v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),v.eventType(),b64(v.title()),b64(v.detail()),b64(v.actor()),b64(v.sourceRef()),Long.toString(v.happenedAt()),Long.toString(v.createdAt()));}
    private static WorldEvent decodeWorldEvent(String s){try{String[] f=s.split("\\t",-1);if(f.length!=10)return null;return new WorldEvent(f[0],f[1],unb64(f[2]),f[3],unb64(f[4]),unb64(f[5]),unb64(f[6]),unb64(f[7]),Long.parseLong(f[8]),Long.parseLong(f[9]));}catch(RuntimeException e){return null;}}
    public String clientWorldEvent(WorldEvent v){return String.join("\t","E",b64(v.id()),v.targetType(),b64(v.targetId()),v.eventType(),b64(v.title()),b64(v.detail()),b64(v.actor()),b64(v.sourceRef()),Long.toString(v.happenedAt()),Long.toString(v.createdAt()));}
    private static String encodeWorldRelationship(WorldRelationship v){return String.join("\t",v.id(),v.fromType(),b64(v.fromId()),v.relation(),v.toType(),b64(v.toId()),b64(v.notes()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static WorldRelationship decodeWorldRelationship(String s){try{String[] f=s.split("\\t",-1);if(f.length!=9)return null;return new WorldRelationship(f[0],f[1],unb64(f[2]),f[3],f[4],unb64(f[5]),unb64(f[6]),Long.parseLong(f[7]),Long.parseLong(f[8]));}catch(RuntimeException e){return null;}}
    public String clientWorldRelationship(WorldRelationship v){return String.join("\t","R",b64(v.id()),v.fromType(),b64(v.fromId()),v.relation(),v.toType(),b64(v.toId()),b64(v.notes()),Long.toString(v.updatedAt()));}
    private static String encodeWorldClaim(WorldClaim v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),v.context(),b64(v.key()),b64(v.value()),v.epistemicState(),v.evidenceType(),b64(v.evidenceRef()),b64(v.notes()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static WorldClaim decodeWorldClaim(String s){try{String[] f=s.split("\\t",-1);if(f.length!=12)return null;return new WorldClaim(f[0],f[1],unb64(f[2]),f[3],unb64(f[4]),unb64(f[5]),f[6],f[7],unb64(f[8]),unb64(f[9]),Long.parseLong(f[10]),Long.parseLong(f[11]));}catch(RuntimeException e){return null;}}
    public String clientWorldClaim(WorldClaim v){return String.join("\t","K",b64(v.id()),v.targetType(),b64(v.targetId()),v.context(),b64(v.key()),b64(v.value()),v.epistemicState(),v.evidenceType(),b64(v.evidenceRef()),b64(v.notes()),Long.toString(v.updatedAt()));}
    private static String encodeAuthorshipProvenance(AuthorshipProvenance v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),v.layer(),v.authorship(),b64(v.source()),b64(v.notes()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static AuthorshipProvenance decodeAuthorshipProvenance(String s){try{String[] f=s.split("\\t",-1);if(f.length!=9)return null;return new AuthorshipProvenance(f[0],f[1],unb64(f[2]),f[3],f[4],unb64(f[5]),unb64(f[6]),Long.parseLong(f[7]),Long.parseLong(f[8]));}catch(RuntimeException e){return null;}}
    public String clientAuthorshipProvenance(AuthorshipProvenance v){return String.join("\t","A",b64(v.id()),v.targetType(),b64(v.targetId()),v.layer(),v.authorship(),b64(v.source()),b64(v.notes()),Long.toString(v.updatedAt()));}
    private static String encodeIntentResolution(IntentResolution v){return String.join("\t",v.id(),v.targetType(),b64(v.targetId()),Integer.toString(v.level()),b64(v.rationale()),Long.toString(v.createdAt()),Long.toString(v.updatedAt()));}
    private static IntentResolution decodeIntentResolution(String s){try{String[] f=s.split("\\t",-1);if(f.length!=7)return null;return new IntentResolution(f[0],f[1],unb64(f[2]),Integer.parseInt(f[3]),unb64(f[4]),Long.parseLong(f[5]),Long.parseLong(f[6]));}catch(RuntimeException e){return null;}}
    public String clientIntentResolution(IntentResolution v){return String.join("\t","I",b64(v.id()),v.targetType(),b64(v.targetId()),Integer.toString(v.level()),b64(v.rationale()),Long.toString(v.updatedAt()));}
    private static String encodeWorldPolicy(WorldPolicy p){return String.join("\t",p.id(),p.scopeType(),b64(p.scopeId()),b64(p.key()),b64(p.value()),Boolean.toString(p.enabled()),b64(p.rationale()),Long.toString(p.createdAt()),Long.toString(p.updatedAt()));}
    private static WorldPolicy decodeWorldPolicy(String s){try{String[] f=s.split("\\t",-1);if(f.length!=9)return null;return new WorldPolicy(f[0],f[1],unb64(f[2]),unb64(f[3]),unb64(f[4]),Boolean.parseBoolean(f[5]),unb64(f[6]),Long.parseLong(f[7]),Long.parseLong(f[8]));}catch(RuntimeException e){return null;}}
    public String clientWorldPolicy(WorldPolicy p){return String.join("\t","W",b64(p.id()),p.scopeType(),b64(p.scopeId()),b64(p.key()),b64(p.value()),Boolean.toString(p.enabled()),b64(p.rationale()),Long.toString(p.updatedAt()));}
    private static String encodeProtectedBuild(ProtectedBuild b){return b.chunkKey()+"\t"+b64(b.projectId())+"\t"+b64(b.label())+"\t"+b.createdAt();}
    private static ProtectedBuild decodeProtectedBuild(String s){try{String[] f=s.split("\t",-1);if(f.length!=4)return null;return new ProtectedBuild(Long.parseLong(f[0]),unb64(f[1]),unb64(f[2]),Long.parseLong(f[3]));}catch(RuntimeException e){return null;}}
    public String clientProtectedBuild(ProtectedBuild b){return String.join("\t","B",Long.toString(b.chunkKey()),b64(b.projectId()),b64(b.label()),Long.toString(b.createdAt()));}

    private static String parseCandidateEditType(String s){String v=s==null?"REVIEW_AREA":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("WIDEN","NARROW","ADD_RELIEF","REDUCE_RELIEF","OPEN_VIEW","ADJUST_ROUTE","ADD_VARIATION","SMOOTH_TRANSITION","FIX_WATER","RESOLVE_STRUCTURE_CONFLICT","PRESERVE","REVIEW_AREA").contains(v)?v:"REVIEW_AREA";}
    private static String parseCandidateEditStatus(String s){String v=s==null?"DRAFT":s.trim().toUpperCase(Locale.ROOT);return Set.of("DRAFT","REVIEWED","APPROVED","REJECTED","SUPERSEDED").contains(v)?v:"DRAFT";}
    private static String parseObservationType(String s){String v=s==null?"NOTE":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("NOTE","TOO_NARROW","TOO_WIDE","TOO_FLAT","TOO_STEEP","VIEW_BLOCKED","GREAT_VIEW","ROUTE_AWKWARD","REPETITIVE","TRANSITION_ABRUPT","WATER_ISSUE","STRUCTURE_CONFLICT","KEEP_THIS","PHYSICAL_VERIFICATION","DISCOVERY").contains(v)?v:"NOTE";}
    private static String parseObservationStatus(String s){String v=s==null?"OPEN":s.trim().toUpperCase(Locale.ROOT);return Set.of("OPEN","REVIEWED","ACCEPTED","RESOLVED","DISMISSED").contains(v)?v:"OPEN";}
    private static String parseWorldEventType(String s){String v=s==null?"NOTE":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("NOTE","CREATED","EDITED","DELETED","RESHAPED","RESIZED","DESIGN_CHANGED","COMMITTED","DEPLOYED","VERIFIED","DISCOVERED","SURVEYED","CONSTRUCTION","MILESTONE","RESTORED","REWIPED","MIGRATED","RENAMED","HISTORIC").contains(v)?v:"NOTE";}
    private static String parseRelationshipType(String s){String v=s==null?"RELATED_TO":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("RELATED_TO","CONTAINS","PART_OF","CONNECTS","DRAINS_FROM","DRAINS_TO","IMPLEMENTS","REALIZES","DEPENDS_ON","BLOCKS","PROTECTS","PROTECTED_BY","REFERENCES","DERIVED_FROM","LOCATED_IN","SERVES","CROSSES","REPLACES","HISTORICALLY_LINKED").contains(v)?v:"RELATED_TO";}
    private static String parseKnowledgeContext(String s){String v=s==null?"TECHNICAL_REALITY":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("TECHNICAL_REALITY","DESIGN_INTENT","PLAYER_EXPERIENCE","WORLD_LORE").contains(v)?v:"TECHNICAL_REALITY";}
    private static String parseEpistemicState(String s){String v=s==null?"KNOWN":s.trim().toUpperCase(Locale.ROOT);return Set.of("KNOWN","BELIEVED","RUMORED","DISPUTED","UNKNOWN").contains(v)?v:"KNOWN";}
    private static String parseEvidenceType(String s){String v=s==null?"NONE":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("NONE","PHYSICAL_SCAN","PLAYER_VISIT","FIELD_SURVEY","DESIGN_RECORD","IMPORT","SCREENSHOT","HISTORY","MANUAL_NOTE").contains(v)?v:"NONE";}
    private static String parseAuthorship(String s){String v=s==null?"UNKNOWN":s.trim().toUpperCase(Locale.ROOT);return Set.of("AUTHORED","ASSISTED","EXTERNAL","VANILLA","EMERGENT","HISTORIC","UNKNOWN").contains(v)?v:"UNKNOWN";}
    private static String parseAuthorshipLayer(String s){String v=s==null?"WHOLE":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');return Set.of("WHOLE","ELEVATION","WATER","BIOME","SURFACE","VEGETATION","STRUCTURES","INFRASTRUCTURE","UNDERGROUND").contains(v)?v:"WHOLE";}
    private static String parseIntentTargetType(String s){String v=s==null?"PROJECT":s.trim().toUpperCase(Locale.ROOT);return Set.of("WORLD","REGION","PROJECT","PLAN","TERRAIN_ASSET","FEATURE","WORK_AREA").contains(v)?v:"PROJECT";}
    private static String parsePolicyScope(String s){String v=s==null?"WORLD":s.trim().toUpperCase(Locale.ROOT);return Set.of("WORLD","REGION","PROJECT","WORK_AREA").contains(v)?v:"WORLD";}
    private static String cleanPolicyKey(String s){String v=s==null?"":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');if(v.isBlank())throw new IllegalArgumentException("policy key required");return v.length()>64?v.substring(0,64):v;}
    private static String parsePrototypeStatus(String s){String v=s==null?"ACTIVE":s.trim().toUpperCase(Locale.ROOT);return Set.of("ACTIVE","READY","PROMOTED","DISCARDED").contains(v)?v:"ACTIVE";}
    private static String parseTransitionStatus(String s){String v=s==null?"PLANNED":s.trim().toUpperCase(Locale.ROOT);return Set.of("PLANNED","REVIEW","APPROVED","ARCHIVED").contains(v)?v:"PLANNED";}
    private static String newId(String p){return p+"_"+UUID.randomUUID().toString().substring(0,8);}private static String cleanId(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT).replace(' ','_');}private static String cleanName(String s,String d){String v=s==null?"":s.trim();if(v.isBlank())v=d;if(v.length()>96)v=v.substring(0,96);return v;}private static String limit(String s,int n){String v=s==null?"":s;return v.length()>n?v.substring(0,n):v;}private static <T> List<T> tail(List<T> v,int max){if(v==null)return List.of();return v.subList(Math.max(0,v.size()-max),v.size());}private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}private static String unb64(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
}

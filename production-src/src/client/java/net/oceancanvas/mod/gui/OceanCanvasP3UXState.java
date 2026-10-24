package net.oceancanvas.mod.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.mod.network.OceanCanvasZoneClientCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared client-side UX state introduced by the P3 Map / UX Platform tranche.
 *
 * <p>This class deliberately owns view preferences only: units, complexity, colour-safe map
 * encodings, workspace layout, onboarding intent and a bounded local activity journal. World and
 * project truth continues to come from the synchronized server models. The split is important:
 * closing a screen may change a preferred layout, but can never be the only place a Region,
 * Project or operation exists.</p>
 */
public final class OceanCanvasP3UXState {
    public enum Complexity { BEGINNER, ADVANCED, DEVELOPER }
    public enum UnitProfile { BLOCKS, CHUNKS, KILOMETERS, TRAVEL_TIME, PROJECT_RELATIVE }
    public enum ColorMode { DEFAULT, DEUTERANOPIA, PROTANOPIA, TRITANOPIA, HIGH_CONTRAST }
    public enum Maturity { STABLE, PREVIEW, DEVELOPER, REQUIRES_BACKUP, EXPENSIVE }
    public enum RiskTier { SAFE, REVERSIBLE, DESTRUCTIVE, VERSION_SENSITIVE }

    public record WorkspaceLayout(String name,String tab,boolean focus,Set<String> panels,Set<String> layers) {
        public WorkspaceLayout {
            name=safe(name);tab=safe(tab).toUpperCase(Locale.ROOT);
            panels=Set.copyOf(panels==null?Set.of():panels);layers=Set.copyOf(layers==null?Set.of():layers);
        }
    }
    public record Activity(long at,String severity,String title,String detail,String deepLink) {
        public Activity { severity=safe(severity);title=safe(title);detail=safe(detail);deepLink=safe(deepLink); }
    }
    public record DeepLink(Map<String,String> values) {
        public String get(String key){return values.getOrDefault(key,"");}
        public boolean has(String key){return !get(key).isBlank();}
    }

    private static final int MAX_ACTIVITY=80;
    private static final Path PREFS=FabricLoader.getInstance().getConfigDir().resolve("oceancanvas-ui.properties");
    private static final Path ACTIVITY=FabricLoader.getInstance().getConfigDir().resolve("oceancanvas-ui-activity.tsv");
    private static final Properties props=new Properties();
    private static final ArrayDeque<Activity> activity=new ArrayDeque<>();
    private static boolean loaded=false;
    private static long lastFeedbackAt=-1L;
    private static String runningKind="";
    private static long runningSubmitted=0L,runningTotal=0L;

    private OceanCanvasP3UXState(){}

    public static synchronized void load(){
        if(loaded)return;loaded=true;
        try{Files.createDirectories(PREFS.getParent());if(Files.isRegularFile(PREFS))try(var in=Files.newInputStream(PREFS)){props.load(in);}}catch(IOException ignored){}
        loadActivity();
    }
    private static void loadActivity(){
        if(!Files.isRegularFile(ACTIVITY))return;
        try{
            for(String line:Files.readAllLines(ACTIVITY,StandardCharsets.UTF_8)){
                String[] p=line.split("\t",-1);if(p.length<5)continue;
                try{activity.addLast(new Activity(Long.parseLong(p[0]),p[1],b64d(p[2]),b64d(p[3]),b64d(p[4])));}catch(RuntimeException ignored){}
            }
            while(activity.size()>MAX_ACTIVITY)activity.removeFirst();
        }catch(IOException ignored){}
    }
    private static synchronized void save(){
        try{Files.createDirectories(PREFS.getParent());try(var out=Files.newOutputStream(PREFS)){props.store(out,"Ocean Canvas client UX preferences — no world/domain truth is stored here");}}catch(IOException ignored){}
    }
    private static void saveActivity(){
        try{
            Files.createDirectories(ACTIVITY.getParent());StringBuilder b=new StringBuilder();
            for(Activity a:activity)b.append(a.at()).append('\t').append(a.severity()).append('\t').append(b64(a.title())).append('\t').append(b64(a.detail())).append('\t').append(b64(a.deepLink())).append('\n');
            Files.writeString(ACTIVITY,b.toString(),StandardCharsets.UTF_8);
        }catch(IOException ignored){}
    }

    public static Complexity complexity(){load();return parseEnum(Complexity.class,props.getProperty("complexity"),Complexity.ADVANCED);}
    public static void setComplexity(Complexity v){load();props.setProperty("complexity",v.name());save();}
    public static Complexity cycleComplexity(){Complexity[] v=Complexity.values();Complexity n=v[(complexity().ordinal()+1)%v.length];setComplexity(n);return n;}
    public static UnitProfile units(){load();return parseEnum(UnitProfile.class,props.getProperty("units"),UnitProfile.BLOCKS);}
    public static void setUnits(UnitProfile v){load();props.setProperty("units",v.name());save();}
    public static UnitProfile cycleUnits(){UnitProfile[] v=UnitProfile.values();UnitProfile n=v[(units().ordinal()+1)%v.length];setUnits(n);return n;}
    public static ColorMode colorMode(){load();return parseEnum(ColorMode.class,props.getProperty("colorMode"),ColorMode.DEFAULT);}
    public static void setColorMode(ColorMode v){load();props.setProperty("colorMode",v.name());save();}
    public static ColorMode cycleColorMode(){ColorMode[] v=ColorMode.values();ColorMode n=v[(colorMode().ordinal()+1)%v.length];setColorMode(n);return n;}
    public static String goal(){load();return props.getProperty("goal","");}
    public static boolean goalPromptPending(){return goal().isBlank();}
    public static void setGoal(String goal){load();props.setProperty("goal",safe(goal).toUpperCase(Locale.ROOT));save();}
    public static String activeLayout(){load();return props.getProperty("activeLayout","PLANNING");}
    public static void setActiveLayout(String layout){load();props.setProperty("activeLayout",safe(layout).toUpperCase(Locale.ROOT));save();}
    public static boolean heatmapEnabled(){load();return Boolean.parseBoolean(props.getProperty("heatmap","false"));}
    public static void setHeatmapEnabled(boolean v){load();props.setProperty("heatmap",Boolean.toString(v));save();}
    public static int heatmapWindowMinutes(){
        load();try{int v=Integer.parseInt(props.getProperty("heatmapWindowMinutes","10"));return v==60||v==360? v:10;}catch(NumberFormatException ex){return 10;}
    }
    public static int cycleHeatmapWindow(){int v=heatmapWindowMinutes();int n=v==10?60:v==60?360:10;props.setProperty("heatmapWindowMinutes",Integer.toString(n));save();return n;}

    public static String formatCoordinate(int blocks,int relativeOrigin){
        return switch(units()){
            case BLOCKS -> String.format(Locale.US,"%,d",blocks);
            case CHUNKS -> Math.floorDiv(blocks,16)+" ch";
            case KILOMETERS -> String.format(Locale.US,"%.3f km",blocks/1000.0);
            case TRAVEL_TIME -> signedTime(blocks-relativeOrigin);
            case PROJECT_RELATIVE -> String.format(Locale.US,"%+d",blocks-relativeOrigin);
        };
    }
    public static String formatDistance(double blocks){
        double a=Math.abs(blocks);
        return switch(units()){
            case BLOCKS, PROJECT_RELATIVE -> String.format(Locale.US,"%,.0f blocks",a);
            case CHUNKS -> String.format(Locale.US,"%.1f chunks",a/16.0);
            case KILOMETERS -> String.format(Locale.US,"%.2f km",a/1000.0);
            case TRAVEL_TIME -> travelTime(a);
        };
    }
    private static String signedTime(double blocks){return (blocks<0?"−":"+")+travelTime(Math.abs(blocks));}
    private static String travelTime(double blocks){
        long sec=Math.round(blocks/4.317); // vanilla walk speed, no sprint/jump/path penalty claim
        if(sec<60)return sec+"s walk";long min=sec/60;if(min<60)return min+"m walk";return (min/60)+"h "+(min%60)+"m walk";
    }

    /** Colour-safe overlay roles plus non-colour pattern IDs consumed by the map renderer. */
    public static int colorFor(String role,int fallback){
        ColorMode m=colorMode();if(m==ColorMode.DEFAULT)return fallback;
        String r=safe(role).toLowerCase(Locale.ROOT);
        if(m==ColorMode.HIGH_CONTRAST)return switch(r){case "region"->0xFFFFFFFF;case "plan"->0xFFFFFF00;case "project"->0xFF00FFFF;case "structure"->0xFFFF00FF;case "change"->0xFFFFA500;default->fallback;};
        // Okabe-Ito inspired role separation. The pattern code remains distinct even when two hues
        // become hard to distinguish, so information never relies on hue alone.
        return switch(m){
            case DEUTERANOPIA -> switch(r){case "region"->0xFF56B4E9;case "plan"->0xFFE69F00;case "project"->0xFFCC79A7;case "structure"->0xFF0072B2;case "change"->0xFFF0E442;default->fallback;};
            case PROTANOPIA -> switch(r){case "region"->0xFF0072B2;case "plan"->0xFFF0E442;case "project"->0xFF56B4E9;case "structure"->0xFFCC79A7;case "change"->0xFFE69F00;default->fallback;};
            case TRITANOPIA -> switch(r){case "region"->0xFF009E73;case "plan"->0xFFD55E00;case "project"->0xFFCC79A7;case "structure"->0xFF000000;case "change"->0xFFE69F00;default->fallback;};
            default -> fallback;
        };
    }
    public static int patternFor(String role){return switch(safe(role).toLowerCase(Locale.ROOT)){case "region"->1;case "plan"->2;case "project"->3;case "structure"->4;case "change"->5;default->0;};}

    public static Maturity maturityFor(String text){
        String s=safe(text).toLowerCase(Locale.ROOT);
        if(s.contains("harness")||s.contains("fault")||s.contains("developer")||s.contains("diagnostic"))return Maturity.DEVELOPER;
        if(s.contains("restore")||s.contains("delete")||s.contains("migration")||s.contains("upgrade"))return Maturity.REQUIRES_BACKUP;
        if(s.contains("pregen")||s.contains("physical scan")||s.contains("verify all"))return Maturity.EXPENSIVE;
        if(s.contains("shape")||s.contains("vertex")||s.contains("path")||s.contains("waypoint")||s.contains("phase")||s.contains("heatmap")||s.contains("composer")||s.contains("mini-map"))return Maturity.PREVIEW;
        return Maturity.STABLE;
    }
    public static String maturityShort(Maturity m){return switch(m){case STABLE->"S";case PREVIEW->"P";case DEVELOPER->"D";case REQUIRES_BACKUP->"B";case EXPENSIVE->"E";};}
    public static RiskTier riskFor(String text){
        String s=safe(text).toLowerCase(Locale.ROOT);
        if(s.contains("restore")||s.contains("upgrade")||s.contains("migration")||s.contains("rehearsal"))return RiskTier.VERSION_SENSITIVE;
        if(s.contains("rewipe")||s.contains("undo"))return RiskTier.REVERSIBLE;
        if(s.contains("delete")||s.contains("remove")||s.contains("pregen")||s.contains("repair")||s.contains("clear"))return RiskTier.DESTRUCTIVE;
        return RiskTier.SAFE;
    }
    public static String riskLabel(RiskTier r){return switch(r){case SAFE->"SAFE";case REVERSIBLE->"REVERSIBLE";case DESTRUCTIVE->"DESTRUCTIVE";case VERSION_SENSITIVE->"VERSION-SENSITIVE";};}
    public static String decorateRisk(String tip){
        if(tip==null||tip.isBlank())return tip;RiskTier r=riskFor(tip);
        return "["+riskLabel(r)+"] "+tip;
    }

    public static String safeDefault(String key){
        String k=safe(key);
        if(Set.of("shipwrecksEnabled","buriedTreasureEnabled").contains(k))
            return "Default: Default/Inherit — preserve or relocate vanilla placements, but do not force extras. Always adds the supported distribution; Never clears future managed placements.";
        if(Set.of("naturalOceanRuinsProtected","naturalOceanMonumentsProtected","naturalRuinedPortalsProtected").contains(k))
            return "Default: Never — preserves the established blank-Canvas behavior. Default/Always can keep or actively distribute these structures and visibly changes managed terrain.";
        return switch(k){
            case "canvasSize" -> "Default/recommended: 20,000 blocks. Changing it moves the managed boundary and changes the number of chunks operations may touch.";
            case "centerX","centerZ" -> "Default/recommended: 0. Changing the center redefines which chunks belong to the managed Canvas.";
            case "expansionEnabled" -> "Default: enabled. Disable it when this world must not grow the managed Canvas beyond its current boundary.";
            case "oceanFloorY" -> "Default: Y 25. Changing it changes the destructive excavation target and every downstream terrain-height assumption.";
            case "oceanFloorVariation" -> "Default: 5 blocks. Higher values increase floor relief; lower values make the base Canvas more uniform.";
            case "oceanFloorTransitionThickness" -> "Default: 4 blocks. Reducing it thins the guaranteed solid seal beneath the excavated floor.";
            case "taperEnabled" -> "Default: disabled. Enable only when a soft Canvas edge is intended; it changes terrain across the transition band.";
            case "taperWidthChunks" -> "Default: 8 chunks. Larger values consume more chunks in the edge transition; smaller values make the transition steeper.";
            case "pregenEnabled" -> "Default/recommended for active Canvas work: enabled. Disabling prevents Pregen/Rewipe/Restore job execution and can stop a running job.";
            case "pregenChunksPerTick" -> "Default: 4 chunks/tick before adaptive limits. Raising it can increase MSPT, heap and storage pressure; the adaptive controller may still cap it lower.";
            case "foreverWorldTargetHours" -> "Default: 8 hours. This is an SLO/ETA target, not a forced scheduler rate; lower values make throughput warnings more demanding.";
            case "flattenerChunksPerTick" -> "Default: 16 chunks/tick. Raising it increases live flattening pressure and can compete with Pregen and ordinary world work.";
            case "backupEnabled" -> "Default/recommended: enabled. Disabling removes automatic safety backups for qualifying destructive jobs.";
            case "backupThresholdChunks" -> "Default: 500 chunks. Raising it allows larger destructive jobs to start without an automatic backup.";
            case "backupRetentionCount" -> "Default: 5 backups. Lower values reduce rollback history; higher values consume more disk space.";
            case "undoDepthPerPlayer" -> "Default: 3 Rewipe entries per player. Lower values reduce reversible history; this does not make Pregen or Restore undoable.";
            case "hudEnabled" -> "Default: disabled. Enabling adds the ambient boundary HUD when that client surface is available; it does not change world state.";
            case "journeyMapOverlayEnabled" -> "Default: disabled. This is legacy compatibility state; Ocean Canvas' built-in map remains the authoritative editor.";
            case "worldBorderSyncEnabled" -> "Default/recommended: disabled unless the Canvas should constrain vanilla travel. Enabling moves the real Minecraft world border.";
            case "biomeMaskEnabled" -> "Default: enabled. Disabling stops the global ocean biome mask; region overrides remain more precise when enabled.";
            case "biomeMaskBiome" -> "Default: minecraft:ocean. Changing it changes the global biome assigned where no region override supersedes it.";
            case "guaranteeSpawnOceanRuin" -> "Default: disabled. Enabling forces an additional starter ocean ruin near spawn in qualifying generation.";
            default -> "Recommended: keep the current/default value unless the project requires a deliberate change. Impactful changes are previewed before commit.";
        };
    }

    public static WorkspaceLayout defaultLayout(String name){
        String n=safe(name).toUpperCase(Locale.ROOT);Set<String> shell=Set.of("header","navigation","tools","right","coords","compass","operation");
        return switch(n){
            case "BUILDING" -> new WorkspaceLayout(n,"PROJECTS",false,shell,Set.of("terrain","regions","plans","projects","markers","coords"));
            case "SURVEY" -> new WorkspaceLayout(n,"REGIONS",false,shell,Set.of("terrain","regions","structures","shipwrecks","monuments","portals","biomes","markers","coords"));
            case "STEWARDSHIP" -> new WorkspaceLayout(n,"REGIONS",false,shell,Set.of("terrain","regions","projects","structures","shipwrecks","monuments","portals","coords"));
            case "DEBUGGING" -> new WorkspaceLayout(n,"LAYERS",false,shell,Set.of("terrain","regions","plans","projects","blueprints","markers","structures","shipwrecks","monuments","portals","grid","coords","biomes","refimg"));
            default -> new WorkspaceLayout("PLANNING","PLANS",false,shell,Set.of("terrain","regions","plans","blueprints","markers","coords","refimg"));
        };
    }
    public static WorkspaceLayout layout(String name){
        load();String n=safe(name).toUpperCase(Locale.ROOT),raw=props.getProperty("layout."+n,"");if(raw.isBlank())return defaultLayout(n);
        try{String[] p=raw.split("\\|",-1);return new WorkspaceLayout(n,p[0],Boolean.parseBoolean(p[1]),csvSet(p[2]),csvSet(p[3]));}catch(RuntimeException ex){return defaultLayout(n);}
    }
    public static void saveLayout(WorkspaceLayout l){
        load();props.setProperty("layout."+l.name().toUpperCase(Locale.ROOT),l.tab()+"|"+l.focus()+"|"+String.join(",",new TreeSet<>(l.panels()))+"|"+String.join(",",new TreeSet<>(l.layers())));setActiveLayout(l.name());save();
    }

    public static synchronized void tick(){
        load();
        var f=OceanCanvasZoneClientCache.feedback(60_000L);
        if(f!=null&&f.receivedAtMs()!=lastFeedbackAt){lastFeedbackAt=f.receivedAtMs();recordActivity(f.error()?"ERROR":"INFO",f.error()?"Attention":"Ocean Canvas",f.message(),deepLink(Map.of("ops",f.error()?"HEALTH":"QUEUE")));}
        var j=OceanCanvasZoneClientCache.job();
        if(j!=null){
            if(runningKind.isBlank()||!runningKind.equals(j.kind()))recordActivity("ACTIVE",title(j.kind())+" started",j.totalChunks()+" chunks",deepLink(Map.of("ops","DIAGNOSTICS")));
            runningKind=j.kind();runningSubmitted=j.submittedChunks();runningTotal=j.totalChunks();
        }else if(!runningKind.isBlank()){
            String detail=runningSubmitted+" / "+runningTotal+" chunks submitted before the operation left the active feed.";
            recordActivity(runningTotal>0&&runningSubmitted>=runningTotal?"COMPLETE":"INFO",title(runningKind)+" finished",detail,deepLink(Map.of("ops","DIAGNOSTICS")));
            runningKind="";runningSubmitted=runningTotal=0L;
        }
    }
    public static synchronized void recordActivity(String severity,String title,String detail,String deepLink){
        load();Activity a=new Activity(System.currentTimeMillis(),severity,title,detail,deepLink);
        Activity last=activity.peekLast();if(last!=null&&last.severity().equals(a.severity())&&last.title().equals(a.title())&&last.detail().equals(a.detail())&&a.at()-last.at()<1500L)return;
        activity.addLast(a);while(activity.size()>MAX_ACTIVITY)activity.removeFirst();saveActivity();
    }
    public static synchronized List<Activity> activities(){load();ArrayList<Activity> out=new ArrayList<>(activity);Collections.reverse(out);return List.copyOf(out);}
    public static int attentionCount(){load();long seen=0L;try{seen=Long.parseLong(props.getProperty("attentionSeenAt","0"));}catch(NumberFormatException ignored){}int n=0;for(Activity a:activities())if(a.at()>seen&&(a.severity().equals("ERROR")||a.severity().equals("WARN")))n++;return n;}
    public static void acknowledgeAttention(){load();props.setProperty("attentionSeenAt",Long.toString(System.currentTimeMillis()));save();}

    public static String deepLink(Map<String,String> values){
        StringBuilder b=new StringBuilder("oc://");boolean first=true;
        for(var e:values.entrySet()){if(e.getValue()==null||e.getValue().isBlank())continue;if(!first)b.append('&');first=false;b.append(url(e.getKey())).append('=').append(url(e.getValue()));}
        return b.toString();
    }
    public static DeepLink parseDeepLink(String raw){
        String s=safe(raw).trim();if(!s.startsWith("oc://"))return new DeepLink(Map.of());
        LinkedHashMap<String,String> out=new LinkedHashMap<>();String body=s.substring(5);
        for(String part:body.split("&")){int eq=part.indexOf('=');if(eq<=0)continue;out.put(unurl(part.substring(0,eq)),unurl(part.substring(eq+1)));}
        return new DeepLink(Map.copyOf(out));
    }

    /**
     * Export the current composed vector/map context without mutating the world.
     *
     * <p>The output is deliberately 2x the on-screen design resolution while preserving the exact
     * viewport crop. Vector overlays therefore become print/atlas friendly without changing which
     * world area the user composed. The live terrain raster is not scraped from the framebuffer;
     * the SVG calls that limitation out instead of pretending an unavailable layer was exported.</p>
     */
    public static Path exportMapComposer(double viewX,double viewZ,double blocksPerPixel,int width,int height,Set<String> visibleLayers,String titleText){
        try{
            Path dir=FabricLoader.getInstance().getGameDir().resolve("oceancanvas").resolve("exports");Files.createDirectories(dir);
            String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());Path out=dir.resolve("map-composer-"+stamp+".svg");
            int exportScale=2,outW=Math.max(1,width*exportScale),outH=Math.max(1,height*exportScale);
            double exportBpp=blocksPerPixel/exportScale,left=viewX-width*.5*blocksPerPixel,top=viewZ-height*.5*blocksPerPixel;
            StringBuilder s=new StringBuilder();s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(outW).append("\" height=\"").append(outH).append("\" viewBox=\"0 0 ").append(outW).append(' ').append(outH).append("\">\n");
            s.append("<rect width=\"100%\" height=\"100%\" fill=\"#071015\"/>\n");
            if(visibleLayers.contains("grid")){
                double worldWidth=width*blocksPerPixel;double step=worldWidth>12000?1024:worldWidth>4000?512:worldWidth>1200?256:64;
                double minGX=Math.floor(left/step)*step,maxGX=left+width*blocksPerPixel,minGZ=Math.floor(top/step)*step,maxGZ=top+height*blocksPerPixel;
                for(double gx=minGX;gx<=maxGX;gx+=step){double x=(gx-left)/exportBpp;s.append("<line x1=\"").append(f(x)).append("\" y1=\"0\" x2=\"").append(f(x)).append("\" y2=\"").append(outH).append("\" stroke=\"#132027\" stroke-width=\"1\"/>\n");}
                for(double gz=minGZ;gz<=maxGZ;gz+=step){double y=(gz-top)/exportBpp;s.append("<line x1=\"0\" y1=\"").append(f(y)).append("\" x2=\"").append(outW).append("\" y2=\"").append(f(y)).append("\" stroke=\"#132027\" stroke-width=\"1\"/>\n");}
            }
            if(visibleLayers.contains("biomes"))for(var z:OceanCanvasZoneClientCache.zones())if(z.hasBiomeOverride()){
                double x=(z.minX()-left)/exportBpp,y=(z.minZ()-top)/exportBpp,w=(z.maxX()-z.minX())/exportBpp,h=(z.maxZ()-z.minZ())/exportBpp;if(x+w<0||y+h<0||x>outW||y>outH)continue;
                s.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\" fill=\"#009E73\" fill-opacity=\".08\" stroke=\"#009E73\" stroke-opacity=\".55\" stroke-dasharray=\"10 8\"/>\n");
            }
            if(visibleLayers.contains("regions"))for(var z:OceanCanvasZoneClientCache.zones()){
                double x=(z.minX()-left)/exportBpp,y=(z.minZ()-top)/exportBpp,w=(z.maxX()-z.minX())/exportBpp,h=(z.maxZ()-z.minZ())/exportBpp;if(x+w<0||y+h<0||x>outW||y>outH)continue;
                String c=hex(colorFor("region",0xFF1FC4EF));s.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\" fill=\"none\" stroke=\"").append(c).append("\" stroke-width=\"4\"/>\n");
                s.append("<text x=\"").append(f(x+8)).append("\" y=\"").append(f(y+28)).append("\" fill=\"#e8edf0\" font-family=\"sans-serif\" font-size=\"20\">").append(xml(z.name())).append("</text>\n");
            }
            if(visibleLayers.contains("refimg"))for(var r:OceanCanvasZoneClientCache.planningReferences())if(r.visible()){
                double x=(r.minX()-left)/exportBpp,y=(r.minZ()-top)/exportBpp,w=(r.maxX()-r.minX())/exportBpp,h=(r.maxZ()-r.minZ())/exportBpp;if(x+w<0||y+h<0||x>outW||y>outH)continue;
                s.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\" fill=\"#C86EE8\" fill-opacity=\".06\" stroke=\"#C86EE8\" stroke-opacity=\".75\" stroke-dasharray=\"14 10\" stroke-width=\"3\"/>\n");
            }
            if(visibleLayers.contains("plans"))for(var v:OceanCanvasZoneClientCache.planningVectors()){
                String pts=svgPoints(v.points(),left,top,exportBpp);if(pts.isBlank())continue;
                s.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"").append(hex(colorFor("plan",0xFFC86EE8))).append("\" stroke-width=\"4\"/>\n");
            }
            if(visibleLayers.contains("projects"))for(var p:OceanCanvasZoneClientCache.workspaceProjects()){
                for(var z:OceanCanvasZoneClientCache.zones())if(z.name().equalsIgnoreCase(p.regionName())){double cx=(((z.minX()+z.maxX())*.5)-left)/exportBpp,cy=(((z.minZ()+z.maxZ())*.5)-top)/exportBpp;if(cx<0||cy<0||cx>outW||cy>outH)break;String c=hex(colorFor("project",0xFF55C97A));s.append("<rect x=\"").append(f(cx-10)).append("\" y=\"").append(f(cy-10)).append("\" width=\"20\" height=\"20\" fill=\"none\" stroke=\"").append(c).append("\" stroke-width=\"4\"/>\n");s.append("<text x=\"").append(f(cx+18)).append("\" y=\"").append(f(cy+7)).append("\" fill=\"#e8edf0\" font-family=\"sans-serif\" font-size=\"18\">").append(xml(p.name())).append("</text>\n");break;}
            }
            if(visibleLayers.contains("markers"))for(var m:OceanCanvasZoneClientCache.atlasFeatures()){
                double cx=(m.x()-left)/exportBpp,cy=(m.z()-top)/exportBpp;if(cx<0||cy<0||cx>outW||cy>outH)continue;s.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"7\" fill=\"#FFFFFF\"/><text x=\"").append(f(cx+14)).append("\" y=\"").append(f(cy+6)).append("\" fill=\"#dce5e9\" font-family=\"sans-serif\" font-size=\"16\">").append(xml(m.name())).append("</text>\n");
            }
            if(visibleLayers.contains("structures"))for(var e:OceanCanvasZoneClientCache.structures()){
                boolean show=switch(e.kindCode()){case 1->visibleLayers.contains("shipwrecks");case 2->visibleLayers.contains("monuments");case 3->visibleLayers.contains("portals");default->true;};if(!show)continue;
                double cx=(((e.minX()+e.maxX())*.5)-left)/exportBpp,cy=(((e.minZ()+e.maxZ())*.5)-top)/exportBpp;if(cx<0||cy<0||cx>outW||cy>outH)continue;String c=hex(colorFor("structure",0xFFE0B25A));s.append("<polygon points=\"").append(f(cx)).append(',').append(f(cy-10)).append(' ').append(f(cx+10)).append(',').append(f(cy)).append(' ').append(f(cx)).append(',').append(f(cy+10)).append(' ').append(f(cx-10)).append(',').append(f(cy)).append("\" fill=\"none\" stroke=\"").append(c).append("\" stroke-width=\"3\"/>\n");
            }
            s.append("<rect x=\"0\" y=\"0\" width=\"").append(outW).append("\" height=\"112\" fill=\"#000000\" fill-opacity=\".72\"/>\n");
            s.append("<text x=\"36\" y=\"52\" fill=\"#e8edf0\" font-family=\"sans-serif\" font-size=\"34\">").append(xml(titleText.isBlank()?"Ocean Canvas Map":titleText)).append("</text>\n");
            s.append("<text x=\"36\" y=\"88\" fill=\"#8a949a\" font-family=\"sans-serif\" font-size=\"20\">Center ").append((int)Math.round(viewX)).append(", ").append((int)Math.round(viewZ)).append(" · crop ").append(width).append("×").append(height).append(" logical px · ").append(xml(colorMode().name())).append("</text>\n");
            double scaleBlocks=Math.max(1.0,exportBpp*200.0);
            s.append("<g transform=\"translate(36,").append(outH-140).append(")\"><line x1=\"0\" y1=\"12\" x2=\"200\" y2=\"12\" stroke=\"#e8edf0\" stroke-width=\"3\"/><line x1=\"0\" y1=\"5\" x2=\"0\" y2=\"19\" stroke=\"#e8edf0\" stroke-width=\"3\"/><line x1=\"200\" y1=\"5\" x2=\"200\" y2=\"19\" stroke=\"#e8edf0\" stroke-width=\"3\"/><text x=\"220\" y=\"20\" fill=\"#c8d1d6\" font-family=\"sans-serif\" font-size=\"20\">").append(xml(formatDistance(scaleBlocks))).append("</text></g>\n");
            s.append("<g transform=\"translate(36,").append(outH-86).append(")\"><rect width=\"").append(Math.min(outW-72,1320)).append("\" height=\"52\" fill=\"#000\" fill-opacity=\".78\" stroke=\"#26333a\"/><text x=\"18\" y=\"24\" fill=\"#c8d1d6\" font-family=\"sans-serif\" font-size=\"18\">Legend · Layers: ").append(xml(String.join(", ",new TreeSet<>(visibleLayers)))).append("</text><text x=\"18\" y=\"44\" fill=\"#748087\" font-family=\"sans-serif\" font-size=\"14\">Vector export · live terrain raster is not embedded</text></g>\n</svg>\n");
            Files.writeString(out,s.toString(),StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("map-composer-"+stamp+".txt"),"Ocean Canvas map composer\nGenerated: "+Instant.now()+"\nOutput: "+outW+"x"+outH+" SVG (2x logical UI resolution)\nView center: "+viewX+", "+viewZ+"\nViewport crop: "+width+"x"+height+" logical pixels at "+blocksPerPixel+" blocks/pixel\nVisible layers: "+String.join(",",visibleLayers)+"\nUnits: "+units()+"\nColour mode: "+colorMode()+"\nTerrain raster embedded: false\n",StandardCharsets.UTF_8);
            recordActivity("COMPLETE","Map composition exported",out.getFileName().toString(),deepLink(Map.of("tab","LAYERS")));return out;
        }catch(IOException ex){recordActivity("ERROR","Map composition export failed",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(),deepLink(Map.of("tab","LAYERS")));return null;}
    }
    private static String svgPoints(String raw,double left,double top,double bpp){StringBuilder out=new StringBuilder();for(String pair:safe(raw).split(";")){String[] p=pair.split(",");if(p.length<2)continue;try{double x=(Double.parseDouble(p[0])-left)/bpp,y=(Double.parseDouble(p[1])-top)/bpp;if(out.length()>0)out.append(' ');out.append(f(x)).append(',').append(f(y));}catch(NumberFormatException ignored){}}return out.toString();}
    private static String f(double v){return String.format(Locale.US,"%.2f",v);}
    private static String hex(int argb){return String.format(Locale.ROOT,"#%06X",argb&0xFFFFFF);}
    private static String xml(String s){return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String url(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(safe(s).getBytes(StandardCharsets.UTF_8));}
    private static String unurl(String s){try{return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}catch(IllegalArgumentException ex){return "";}}
    private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(safe(s).getBytes(StandardCharsets.UTF_8));}
    private static String b64d(String s){try{return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}catch(IllegalArgumentException ex){return "";}}
    private static Set<String> csvSet(String s){if(s==null||s.isBlank())return Set.of();LinkedHashSet<String> out=new LinkedHashSet<>();for(String p:s.split(","))if(!p.isBlank())out.add(p.trim());return Set.copyOf(out);}
    private static <T extends Enum<T>> T parseEnum(Class<T> type,String raw,T fallback){String value=safe(raw);if(value.isEmpty())return fallback;for(T constant:type.getEnumConstants())if(constant.name().equalsIgnoreCase(value))return constant;return fallback;}
    private static String title(String s){String v=safe(s).replace('_',' ').toLowerCase(Locale.ROOT);StringBuilder b=new StringBuilder();for(String p:v.split(" "))if(!p.isBlank())b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');return b.toString().trim();}
    private static String safe(String s){return s==null?"":s.trim();}
}

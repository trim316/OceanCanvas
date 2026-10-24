package net.oceancanvas.mod.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.oceancanvas.mod.network.*;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.*;
import java.util.Base64;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Ocean Canvas reference-parity UI.
 *
 * <p>This screen is hand-rendered from the authoritative bundled HTML reference
 * ({@code docs/ui-reference/Ocean Canvas-authoritative.html}) instead of inheriting any
 * legacy Ocean Canvas screen implementation. The HTML's exact 1440x820 geometry constants
 * are preserved verbatim; do not re-derive them from Minecraft font metrics.</p>
 *
 * <p><b>v253.29 wiring contract.</b> Up to v253.28 this file was a faithful <i>transcription</i>
 * of that reference: the panels rendered fixed mockup strings and most controls did nothing.
 * Every visible surface is now bound to real synchronized data
 * ({@link OceanCanvasZoneClientCache}) and every visible control sends its real request payload.
 * Two rules follow from that and must not be regressed:</p>
 * <ol>
 *   <li><b>No invented data.</b> A field the server does not actually carry is not rendered at
 *       all - it is never filled with a plausible-looking constant. Where the reference shows a
 *       field Ocean Canvas has no backing store for (a per-project Target/Start Date), the row is
 *       replaced by one the data really does support, not faked.</li>
 *   <li><b>No dead affordances.</b> Anything drawn to look interactive is registered in the
 *       frame's {@link Hit} list with a real action and a hover tooltip. Draw and click geometry
 *       come from the same call, so a control cannot drift out of its own hit rectangle - the
 *       defect class that left the v253.28 Plans/Projects tabs entirely unclickable.</li>
 * </ol>
 *
 * <p>Destructive operations (Rewipe, Restore to Vanilla, Delete Region) are click-to-arm and
 * click-again-to-confirm, per the roadmap's "destructive actions must be explicit" invariant.</p>
 */
public final class OceanCanvasGroundZeroScreen extends Screen {
    private static final int CY=0xFF1FC4EF, BLACK=0xFF000000, PANEL=0xFF000000, BORDER=0xFF262626,
            TAB_BORDER=0xFF2E2E2E, DIM=0xFF8A8F93, TEXT=0xFFDCDCDC, BRIGHT=0xFFF0F0F0,
            DARK=0xFF0D1013, ROW=0xFF1C1C1C, GREEN=0xFF3DDC84,
            PURPLE=0xFFC86EE8, GOLD=0xFFE0B25A, RED=0xFFE05A5A;
    private static final String[] TABS={"REGIONS","PLANS","PROJECTS","LAYERS"};
    /* Final approved tool contract. Keep creation/editing depth in the contextual right panel;
       the left rail stays intentionally small and identical to the approved four-screen reference. */
    private static final String[][][] TOOLS={
            {{"Select","select"},{"Pan","pan"},{"Shape","shape"},{"Vertex","vertex"},{"Path","path"},{"Waypoint","waypoint"}},
            {{"Select","select"},{"Pan","pan"},{"Shape","shape"},{"Path","path"},{"Waypoint","waypoint"}},
            {{"Select","select"},{"Pan","pan"},{"Add Phase","plus"}},
            {{"Select","select"},{"Pan","pan"},{"Toggle","toggle"},{"Waypoint","waypoint"}}
    };
    private static final String[][] LAYERS={
            {"regions","region","Regions"},{"plans","plan","Plans"},{"projects","project","Projects"},{"blueprints","blueprint","Blueprints / Traces"},{"markers","marker","Markers"},
            {"structures","caret","Structures"},{"shipwrecks","shipwreck","Shipwrecks"},{"monuments","monument","Monuments"},{"portals","portal","Ruined Portals"},
            {"grid","grid","Grid"},{"coords","coords","Coordinates"},{"terrain","terrain","Terrain"},{"biomes","biome","Biomes"},{"refimg","image","Reference Images"},
            {"health","shield","Health Diagnostics"},{"health_light","coords","Lighting Findings"},{"health_liquid","terrain","Liquid Findings"},{"pregen_state","queue","Pregen State"}
    };

    /** One interactive rectangle registered by the draw pass and consumed by the click pass. */
    private record Hit(int x,int y,int w,int h,String tip,Runnable action) { }

    private final Screen parent;
    private String tab="LAYERS", overlay=null, toast=null;
    private final Map<String,String> activeTool=new HashMap<>();
    private final Map<String,Boolean> layerOn=new LinkedHashMap<>();
    private boolean panelOpen=true, uiMinimized=false, collapsed=false, structOpen=true, snap=true;
    private final Map<String,Boolean> panelVisible=new LinkedHashMap<>();
    private int coordX=0,coordZ=0;
    private long toastUntil=0L;

    /** Live selection. Held by identity (zone name / group id / project id), resolved every frame. */
    private String selectedRegion="", selectedPlan="", selectedProject="", selectedReference="", selectedWaypoint="";
    private boolean autoSelected=false;

    /** Operations surface: the bottom-centre card expands into live Pregen/queue/health data. */
    private boolean opsOpen=false; private String opsTab="DIAGNOSTICS";

    /**
     * Explain-this-state (OC-F059, OC-F124, OC-F239). Holds {@code "<kind>|<raw server string>"}
     * for the state the user asked about, or null.
     *
     * <p>The controller already broadcasts a precise machine-readable reason for every hold, but
     * the strings are internal jargon — "Preemptive no-ready hold (stale frontier)" tells a player
     * nothing. Each explanation is keyed on the server's <i>exact</i> string and derived from the
     * predicate that sets it in {@code PregenManager}, so the thresholds quoted are the real
     * constants. An unrecognised state must fall through to an explicit "no explanation on file"
     * showing the raw text — never a plausible-sounding generic one, which is the same failure as
     * v253.31's {@code operationLabel()} silently renaming an unknown operation.</p>
     */
    private String explain=null;

    /** Global bottom-toolbar inspector. The next unclaimed map click requests server-authored state. */
    private boolean inspectMode=false;
    /** Advanced capabilities recovered after the UI reset, kept inside the approved four-tab shell. */
    private String workbenchTab="RECOVERY", selectedTerrainAsset="", selectedReferenceSet="";
    /** Historical spatial evidence (OC-F265): exact scope of a persisted operation, never inferred from current selection. */
    private boolean operationGhostEnabled=false; private int operationGhostIndex=0;
    private int archaeologyIndex=0;
    private int harnessFaultProfileIndex=0;
    /** P1-W3 progressive-disclosure tools; both are client view state only. */
    private boolean focusMode=false,beforeAfterEnabled=false,beforeAfterDragging=false; private double beforeAfterDivider=0.5D; private String beforeAfterSnapshotId="";
    private String p1RecipeSpec="Maintenance|HEALTH_SCAN>BOUNDARY_AUDIT>STEWARDSHIP_REPORT"; private int p1RecipeIndex=0,p1TargetY=64;
    /** Workbench is a clipped, scrollable surface; advanced capability count must never force hidden controls. */
    private int workbenchScroll=0; private boolean workbenchClipActive=false; private int workbenchClipTop=0,workbenchClipBottom=0;

    /** Open inline disclosure inside the right panel, or null. */
    private String disclosure=null;
    /** Page index for any list inside a disclosure that is longer than its window. */
    private int listPage=0;

    /** Inline text/number editing. {@code editKey} names the field; {@code editBuf} is its buffer. */
    private String editKey=null; private String editBuf="";

    /**
     * Command palette / universal search (OC-F051, OC-F054, OC-F233, OC-F237).
     *
     * <p>One keystroke reaches every action and every synchronised object. The entry list is a
     * memo rather than a per-frame rebuild: each client-cache accessor re-parses its whole packed
     * payload, and the palette reads nine of them, so rebuilding on every frame would put that
     * parse cost in the render path. It is rebuilt when the query changes, when a sync arrives
     * ({@code planningGeneration}), or after {@link #PALETTE_REFRESH_MS}.</p>
     *
     * <p>Unavailable commands are listed with the reason they cannot run rather than being hidden.
     * A user who cannot find Rewipe learns nothing; one who sees "Select a region first" does.</p>
     */
    /**
     * Task the workbench TASKS tab is editing. Everything it edits was already implemented
     * server-side and reachable by nothing — see scripts/parity-baseline.txt.
     */
    private String selectedTask="";
    private String selectedPhase="", selectedMilestone="";

    /** Shared amount + offset for the geometry operators; the server takes one scalar per action. */
    private double geomAmount=1.0D; private String geomTranslate="0,0";

    /** Durable Workbench draft values for workflows that need typed metadata before an action runs. */
    private String projectImportFile="";
    /** P4 Creative Planning view/draft state. Canonical artifacts are server-persisted; these values are UI-only. */
    private String p4Overlay="NONE",p4DraftText="",p4TerrainIntent="RIDGE / HEIGHT",p4MotifName="Terrain motif";
    private String p5TransformName="OC → WorldPainter",p5TransformSpec="OCEAN_CANVAS|WORLDPAINTER|0|0|1|0|false|false|63|-25|WORLD_XZ_NORTH_UP";
    private String p5RecipeName="WorldPainter Round Trip",p5RecipeSpec="WORLDPAINTER|oc_worldpainter|PROJECT|4096|4096|coastline,mountains,rivers,lakes,biomes|{project}-worldpainter-{timestamp}|HEIGHTMAP_CONTRACT,FINGERPRINT,SEAMS,ROUND_TRIP";
    private String p5ImportFile="returned-heightmap.png",p5ImportMeta="-1024|-1024|1023|1023|2048|2048|1|63|NORTH_UP";
    private String p5PlacementName="Placement",p5PlacementSpec="| |terrain.litematic||0|64|0|0|NONE|PLANNED|1||";
    private String p5MarkerName="Custom Marker",p5MarkerSpec="PLANNING|name,type,priority|waypoint|DEFAULT|User-defined marker schema";
    /** P6-P10 reusable optional parameter/note. Each feature documents its own safe interpretation. */
    private String programInput="";
    private boolean programCursorShare=false;
    private long lastProgramCursorPublishMs=0L;
    private boolean p4Alternatives=true;
    private String terrainRevisionSpec="";
    private String terrainHeightmapSpec="";
    private String terrainPlacementSpec="";
    private String terrainReviewSpec="";
    private String knowledgeClaimKey="", knowledgeClaimValue="";
    private String knowledgeAuthorshipSource="Ocean Canvas Workbench";

    private String paletteQuery=""; private int paletteIndex=0, paletteScroll=0;
    /** Map-composer title is view-local draft state; crop and layers come from the live viewport. */
    private String mapComposerTitle="";
    private List<PaletteEntry> paletteCache=List.of();
    private String paletteCacheQuery=null; private long paletteCacheGen=-1L, paletteCacheAt=0L;
    private final List<String> recentPalette=new ArrayList<>();
    private static final long PALETTE_REFRESH_MS=750L;
    private static final int PALETTE_W=760, PALETTE_H=470, PALETTE_ROWS=11, PALETTE_ROW_H=30;

    /**
     * One palette row. {@code unavailable} is a human-readable reason, or null when the entry can
     * actually run — the same "no dead affordances" rule the rest of this screen follows, extended
     * so that a listed-but-refusable command must say why.
     */
    private record PaletteEntry(String kind,String title,String subtitle,String hint,
                                String unavailable,String searchText,Runnable run) { }

    /** Two-step confirmation for destructive operations. */
    private String armed=null; private long armedUntil=0L;

    /** Region geometry drag. While dragging, the gx/gz fields preview the edit; release commits a resize. */
    private String dragMode=null; private double dragStartX,dragStartY;
    private int gx1,gz1,gx2,gz2; private boolean geometryLive=false;

    /** Rubber-band rectangle is retained only for rectangular fallback operations. */
    private boolean drawing=false; private double drawAX,drawAZ,drawBX,drawBZ;

    /** Click-by-click geometry capture for the approved Shape/Path tools. Values are x,z pairs. */
    private final List<Integer> gestureVertices=new ArrayList<>();
    private String gestureKind=null;
    /** Arbitrary-region vertex drag state; rectangle resize keeps using geometryLive/gx*. */
    private int polygonDragIndex=-1;
    private List<Integer> polygonEditVertices=List.of();
    private int selectedRegionVertex=0;
    /** Region vertex coordinates are shown in numbered Xn/Zn rows. Keep the list compact with paging. */
    private static final int REGION_VERTEX_ROWS=2;
    private int regionVertexPage=0;
    /**
     * Geometry edits are staged while the server computes the exact REGION_GEOMETRY impact.
     * This fixes the old "press Enter, field closes, nothing changes" failure mode: the edited
     * coordinates remain visible and a protected/linked Region gets an explicit Apply/Cancel row.
     */
    private record PendingRegionGeometry(String regionName,boolean polygon,int minX,int minZ,int maxX,int maxZ,
                                         List<Integer> vertices,String argument,long requestedAtMs) { }
    private PendingRegionGeometry pendingRegionGeometry=null;
    /** Selected Plan object and live vertex edit state. Select exposes exact vertices without adding a fifth Plans rail tool. */
    private String selectedPlanObject="";
    private int selectedPlanVertex=0,planDragIndex=-1;
    private List<Integer> planEditVertices=List.of();
    private boolean planEditClosed=false;

    /** P1-P6 integrated workflow state. These are view/editor choices only; canonical data remains server-authored. */
    private int snapStep=16;
    private String projectFilter="ALL"; // ALL / READY / BLOCKED / ATTENTION
    private String selectedHealthFinding="";
    /** A polygon clone is created as a rectangle first, then its exact shape is applied after server sync. */
    private String pendingRegionShapeName="";
    private List<Integer> pendingRegionShapeVertices=List.of();

    /**
     * A follow-up that needs the server-assigned id of something this screen just created.
     * {@code planning create} and {@code task_add} both mint their own id server-side and the
     * response is a whole-state resync, so the link (object → plan layer, task → project) is
     * completed on the first sync in which the new record appears, then dropped. Without this a
     * shape drawn on the Plans tab would silently land outside the selected layer.
     */
    private record PendingLink(String kind,String match,String target,long deadline) { }
    private PendingLink pendingLink=null;

    /**
     * True during the right panel's measure pass. Every drawing and hit-registration primitive
     * no-ops, so the panel layout can be run once to find where its content actually ends and
     * once to paint it. Before v253.29 each panel's background height was a hand-tuned constant
     * and the Plans panel's content already ran roughly 38px past the bottom of its own backing.
     */
    private boolean measuring=false;
    private final List<Hit> hits=new ArrayList<>();
    private final OceanCanvasMapTerrain mapTerrain = new OceanCanvasMapTerrain();
    private OceanCanvasMapRasterRenderer mapRaster = new OceanCanvasMapRasterRenderer(mapTerrain);
    private boolean mapCentered=false;
    private double mapViewX=0.0,mapViewZ=0.0,mapBlocksPerPixel=8.0;
    private boolean mapPanning=false; private double mapPanStartX,mapPanStartY,mapPanViewX,mapPanViewZ;

    /** P3 shared UX state: persisted preferences live in OceanCanvasP3UXState; these are render-session caches only. */
    private int activityScroll=0;
    private long changeScanAt=0L;
    private final Map<String,String> changeFingerprints=new HashMap<>();
    private final Map<String,ChangeMark> recentChanges=new LinkedHashMap<>();
    private record ChangeMark(String kind,String id,int minX,int minZ,int maxX,int maxZ,long changedAt){}

    public OceanCanvasGroundZeroScreen(Screen parent){
        super(Component.literal("Ocean Canvas")); this.parent=parent;
        OceanCanvasP3UXState.load();
        for(String t:TABS) activeTool.put(t,"Select");
        for(String[] r:LAYERS) layerOn.put(r[0], !(r[0].equals("refimg")||r[0].startsWith("health")||r[0].equals("pregen_state")));
        for(String k:new String[]{"header","navigation","tools","right","coords","compass","operation"}) panelVisible.put(k,true);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        // Screens can be reused as a parent (for example Settings -> Back). removed()
        // releases the expensive raster worker/texture, so recreate that resource on
        // re-entry rather than leaking it or returning with a permanently closed map.
        if(mapRaster==null) mapRaster=new OceanCanvasMapRasterRenderer(mapTerrain);
        if(!mapCentered && minecraft!=null && minecraft.player!=null){
            mapViewX=minecraft.player.getX(); mapViewZ=minecraft.player.getZ(); mapCentered=true;
            coordX=(int)Math.round(mapViewX); coordZ=(int)Math.round(mapViewZ);
        }
        if(overlay==null && OceanCanvasP3UXState.goalPromptPending()) overlay="goal_picker";
    }

    private static final int DESIGN_W=1440, DESIGN_H=820;
    /* Render the exact HTML coordinate space as one unit. Minecraft's Screen.width/height are
       GUI-scaled logical pixels, not physical window pixels, so the whole 1440x820 design must
       be transformed to fit those logical bounds. This keeps every edge visible at every GUI
       scale/window size and scales Minecraft text together with the chrome. */
    private float uiScale(){return Math.min(width/(float)DESIGN_W,height/(float)DESIGN_H);}
    private float uiOffsetX(){return (width-DESIGN_W*uiScale())*0.5f;}
    private float uiOffsetY(){return (height-DESIGN_H*uiScale())*0.5f;}
    private double ux(double screenX){return (screenX-uiOffsetX())/uiScale();}
    private double uy(double screenY){return (screenY-uiOffsetY())/uiScale();}
    private int R(int x){return x;}
    private boolean hitBox(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}

    // ---------------------------------------------------------------- draw primitives
    private void fill(GuiGraphicsExtractor g,int x,int y,int w,int h,int c){if(measuring)return;g.fill(x,y,x+w,y+h,c);}
    private void border(GuiGraphicsExtractor g,int x,int y,int w,int h,int c){
        fill(g,x,y,w,1,c);fill(g,x,y+h-1,w,1,c);fill(g,x,y,1,h,c);fill(g,x+w-1,y,1,h,c);
    }
    private void text(GuiGraphicsExtractor g,String s,int x,int y,int c){if(measuring)return;g.text(font,Component.literal(s),x,y,c,false);}
    private void center(GuiGraphicsExtractor g,String s,int x,int y,int w,int c){if(measuring)return;g.centeredText(font,Component.literal(s),x+w/2,y,c);}
    private void right(GuiGraphicsExtractor g,String s,int rightEdge,int y,int c){text(g,s,rightEdge-font.width(s),y,c);}
    private void hline(GuiGraphicsExtractor g,int x,int y,int w){fill(g,x,y,w,1,ROW);}
    private void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int c){int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,err=dx+dy;while(true){fill(g,x0,y0,1,1,c);if(x0==x1&&y0==y1)break;int e2=2*err;if(e2>=dy){err+=dy;x0+=sx;}if(e2<=dx){err+=dx;y0+=sy;}}}
    private void line2(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int c){line(g,x0,y0,x1,y1,c);line(g,x0+1,y0,x1+1,y1,c);}
    /** Solid triangle via horizontal-scanline rasterisation, built only from {@link #fill}
     *  rectangles - this screen never touches the raw GL/tesselator pipeline, so a real polygon
     *  fill has to be assembled from the same axis-aligned primitive everything else here uses. */
    private void fillTriangle(GuiGraphicsExtractor g,double ax,double ay,double bx,double by,double cx,double cy,int color){
        int minY=(int)Math.floor(Math.min(ay,Math.min(by,cy))),maxY=(int)Math.ceil(Math.max(ay,Math.max(by,cy)));
        double[][] edges={{ax,ay,bx,by},{bx,by,cx,cy},{cx,cy,ax,ay}};
        for(int py=minY;py<=maxY;py++){
            double sy=py+0.5; double lo=Double.NaN,hi=Double.NaN;
            for(double[] e:edges){
                double x0=e[0],y0=e[1],x1=e[2],y1=e[3];
                if((y0<=sy&&y1>sy)||(y1<=sy&&y0>sy)){
                    double t=(sy-y0)/(y1-y0), x=x0+t*(x1-x0);
                    if(Double.isNaN(lo)){lo=x;hi=x;} else {lo=Math.min(lo,x);hi=Math.max(hi,x);}
                }
            }
            if(Double.isNaN(lo)) continue;
            int xl=(int)Math.round(lo), xr=(int)Math.round(hi);
            if(xr>xl) fill(g,xl,py,xr-xl,1,color);
        }
    }
    /** Trim to a pixel budget so real world data can never overflow the reference panel width. */
    private String trim(String s,int maxWidth){
        if(s==null) return "";
        if(font.width(s)<=maxWidth) return s;
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){
            if(font.width(b.toString()+s.charAt(i)+"…")>maxWidth) break;
            b.append(s.charAt(i));
        }
        return b+"…";
    }
    private void icon(GuiGraphicsExtractor g,String k,int x,int y,int c){
        switch(k){
            case "region" -> {border(g,x+2,y+2,12,12,c);fill(g,x+7,y+3,2,10,c);fill(g,x+3,y+7,10,2,c);}
            case "plan","terrain" -> {line(g,x+1,y+13,x+5,y+7,c);line(g,x+5,y+7,x+8,y+10,c);line(g,x+8,y+10,x+11,y+4,c);line(g,x+11,y+4,x+15,y+13,c);fill(g,x+1,y+13,15,2,c);fill(g,x+10,y+7,2,2,c);}
            case "project" -> {fill(g,x+2,y+1,3,14,c);fill(g,x+5,y+2,9,6,c);fill(g,x+5,y+8,6,2,c);}
            case "layers","toggle" -> {fill(g,x+2,y+2,12,3,c);fill(g,x+2,y+7,12,3,c);fill(g,x+2,y+12,12,3,c);}
            case "select" -> {line2(g,x+2,y+1,x+12,y+8,c);line2(g,x+2,y+1,x+5,y+14,c);line2(g,x+5,y+9,x+10,y+14,c);fill(g,x+6,y+7,7,2,c);}
            case "pan" -> {line(g,x+8,y+1,x+8,y+15,c);line(g,x+1,y+8,x+15,y+8,c);line(g,x+8,y+1,x+5,y+4,c);line(g,x+8,y+1,x+11,y+4,c);line(g,x+8,y+15,x+5,y+12,c);line(g,x+8,y+15,x+11,y+12,c);line(g,x+1,y+8,x+4,y+5,c);line(g,x+1,y+8,x+4,y+11,c);line(g,x+15,y+8,x+12,y+5,c);line(g,x+15,y+8,x+12,y+11,c);}
            case "marquee" -> {for(int i=2;i<14;i+=4){fill(g,x+i,y+2,2,1,c);fill(g,x+i,y+13,2,1,c);fill(g,x+2,y+i,1,2,c);fill(g,x+13,y+i,1,2,c);}}
            case "draw","edit" -> {line2(g,x+2,y+13,x+11,y+4,c);line2(g,x+4,y+15,x+13,y+6,c);fill(g,x+11,y+3,3,4,c);fill(g,x+1,y+13,5,2,c);}
            case "plus" -> {fill(g,x+7,y+2,2,12,c);fill(g,x+2,y+7,12,2,c);}
            case "minus" -> fill(g,x+2,y+7,12,2,c);
            case "shape" -> border(g,x+2,y+2,12,12,c);
            case "path" -> {line(g,x+1,y+11,x+4,y+7,c);line(g,x+4,y+7,x+7,y+9,c);line(g,x+7,y+9,x+10,y+4,c);line(g,x+10,y+4,x+15,y+6,c);}
            case "vertex" -> {fill(g,x+7,y+1,2,14,c);fill(g,x+1,y+7,14,2,c);fill(g,x+6,y+6,4,4,c);}
            case "projectAdd" -> {border(g,x+1,y+1,14,14,c);fill(g,x+7,y+4,2,8,c);fill(g,x+4,y+7,8,2,c);}
            case "milestone" -> {line(g,x+8,y+1,x+15,y+8,c);line(g,x+15,y+8,x+8,y+15,c);line(g,x+8,y+15,x+1,y+8,c);line(g,x+1,y+8,x+8,y+1,c);}
            case "task" -> {border(g,x+1,y+1,14,14,c);line(g,x+4,y+8,x+7,y+11,c);line(g,x+7,y+11,x+13,y+4,c);}
            case "waypoint" -> {border(g,x+4,y+2,8,8,c);fill(g,x+7,y+5,2,2,c);line(g,x+4,y+9,x+8,y+15,c);line(g,x+12,y+9,x+8,y+15,c);}
            case "blueprint" -> {border(g,x+2,y+3,11,10,c);line(g,x+5,y+1,x+15,y+1,c);line(g,x+15,y+1,x+15,y+11,c);}
            case "marker","coords" -> {border(g,x+4,y+4,8,8,c);fill(g,x+7,y+1,2,14,c);fill(g,x+1,y+7,14,2,c);}
            case "caret" -> {line(g,x+3,y+5,x+8,y+10,c);line(g,x+8,y+10,x+13,y+5,c);}
            case "shipwreck" -> {line(g,x+2,y+10,x+14,y+10,c);line(g,x+4,y+10,x+6,y+14,c);line(g,x+6,y+14,x+12,y+14,c);line(g,x+12,y+14,x+14,y+10,c);fill(g,x+7,y+4,2,6,c);line(g,x+9,y+5,x+13,y+8,c);}
            case "monument" -> {line(g,x+2,y+6,x+8,y+2,c);line(g,x+8,y+2,x+14,y+6,c);fill(g,x+2,y+6,12,2,c);fill(g,x+3,y+9,2,5,c);fill(g,x+7,y+9,2,5,c);fill(g,x+11,y+9,2,5,c);fill(g,x+2,y+14,12,1,c);}
            case "portal" -> {border(g,x+3,y+1,10,14,c);border(g,x+5,y+3,6,10,c);}
            case "grid" -> {border(g,x+1,y+1,14,14,c);fill(g,x+5,y+1,1,14,c);fill(g,x+10,y+1,1,14,c);fill(g,x+1,y+5,14,1,c);fill(g,x+1,y+10,14,1,c);}
            case "biome" -> {border(g,x+3,y+3,10,10,c);fill(g,x+5,y+5,3,3,c);fill(g,x+9,y+9,2,2,c);}
            case "image" -> {border(g,x+1,y+2,14,12,c);fill(g,x+4,y+5,2,2,c);line(g,x+3,y+12,x+7,y+8,c);line(g,x+7,y+8,x+10,y+11,c);line(g,x+10,y+11,x+13,y+7,c);}
            case "shield" -> {fill(g,x+3,y+2,10,2,c);fill(g,x+3,y+3,2,6,c);fill(g,x+11,y+3,2,6,c);line2(g,x+4,y+8,x+8,y+14,c);line2(g,x+11,y+8,x+8,y+14,c);}
            case "info" -> {border(g,x+3,y+3,10,10,c);fill(g,x+7,y+5,2,2,c);fill(g,x+7,y+8,2,4,c);}
            case "gear" -> {border(g,x+5,y+5,6,6,c);fill(g,x+7,y+7,2,2,c);fill(g,x+6,y+1,4,4,c);fill(g,x+6,y+11,4,4,c);fill(g,x+1,y+6,4,4,c);fill(g,x+11,y+6,4,4,c);fill(g,x+3,y+3,3,3,c);fill(g,x+10,y+10,3,3,c);fill(g,x+10,y+3,3,3,c);fill(g,x+3,y+10,3,3,c);}
            case "close" -> {line(g,x+2,y+2,x+14,y+14,c);line(g,x+14,y+2,x+2,y+14,c);}
            case "check" -> {line(g,x+3,y+8,x+6,y+11,c);line(g,x+6,y+11,x+12,y+4,c);}
            case "pause" -> {fill(g,x+3,y+2,4,12,c);fill(g,x+10,y+2,4,12,c);}
            case "play" -> {for(int i=0;i<12;i++){fill(g,x+3+i/2,y+2+i,1,1,c);if(i%2==0)fill(g,x+4+i/2,y+2+i,1,1,c);}}
            case "queue" -> {fill(g,x+2,y+3,12,2,c);fill(g,x+2,y+7,12,2,c);fill(g,x+2,y+11,12,2,c);}
            case "restore" -> {line(g,x+3,y+7,x+8,y+2,c);line(g,x+3,y+7,x+8,y+12,c);line(g,x+3,y+7,x+14,y+7,c);}
            case "rewipe" -> {line(g,x+4,y+4,x+12,y+4,c);line(g,x+12,y+4,x+12,y+12,c);line(g,x+12,y+12,x+4,y+12,c);line(g,x+4,y+12,x+2,y+10,c);line(g,x+4,y+12,x+6,y+10,c);}
            case "trash" -> {fill(g,x+2,y+3,12,2,c);fill(g,x+6,y+1,4,2,c);border(g,x+4,y+5,8,10,c);fill(g,x+7,y+7,1,6,c);fill(g,x+9,y+7,1,6,c);}
            case "teleport" -> {border(g,x+2,y+2,12,12,c);fill(g,x+7,y+4,2,8,c);fill(g,x+5,y+7,6,2,c);}
        }
    }
    private void say(String s){toast=s;toastUntil=System.currentTimeMillis()+2400;}

    // ---------------------------------------------------------------- hit registry
    private void hit(int x,int y,int w,int h,String tip,Runnable action){
        if(measuring)return;
        tip=OceanCanvasP3UXState.decorateRisk(tip);
        if(workbenchClipActive){
            int top=Math.max(y,workbenchClipTop),bottom=Math.min(y+h,workbenchClipBottom);
            if(bottom<=top)return;
            hits.add(new Hit(x,top,w,bottom-top,tip,action));return;
        }
        hits.add(new Hit(x,y,w,h,tip,action));
    }
    /** Registers a rectangle that swallows clicks without doing anything (panel backing). */
    private void blocker(int x,int y,int w,int h){if(measuring)return;hits.add(new Hit(x,y,w,h,null,null));}

    // ---------------------------------------------------------------- live data accessors
    private List<OceanCanvasZoneSyncPayload.ZoneEntry> zones(){return OceanCanvasZoneClientCache.zones();}

    /**
     * Whether map objects should register click-to-select targets this frame.
     *
     * <p>A gesture tool owns the canvas while it is active: with Draw, Marquee, Path, Add Vertex,
     * Add Task, Waypoint or Pan selected, a click starting on top of a region must begin that
     * gesture rather than silently re-selecting whatever it landed on.</p>
     */
    private boolean mapSelectionActive(){
        String t=activeTool.getOrDefault(tab,"Select");
        return t.equals("Select");
    }
    /** Map objects of a kind are selectable on their own tab, and on Layers where all are shown. */
    private boolean selectable(String kindTab){
        return mapSelectionActive() && (tab.equals(kindTab)||tab.equals("LAYERS"));
    }

    private OceanCanvasZoneSyncPayload.ZoneEntry region(){
        for(var z:zones()) if(z.name().equalsIgnoreCase(selectedRegion)) return z;
        return null;
    }
    private OceanCanvasZoneClientCache.PlanGroup plan(){
        for(var p:OceanCanvasZoneClientCache.planGroups()) if(p.id().equals(selectedPlan)) return p;
        return null;
    }
    private OceanCanvasZoneClientCache.PlanningVector planObject(){
        for(var v:OceanCanvasZoneClientCache.planningVectors()) if(v.id().equals(selectedPlanObject)) return v;
        return null;
    }
    private OceanCanvasZoneClientCache.WorkspaceProject project(){
        for(var p:OceanCanvasZoneClientCache.workspaceProjects()) if(p.id().equals(selectedProject)) return p;
        return null;
    }
    private OceanCanvasZoneClientCache.AtlasFeature waypoint(){
        for(var f:OceanCanvasZoneClientCache.atlasFeatures()) if(f.id().equals(selectedWaypoint)) return f;
        return null;
    }
    /** Plan vectors belonging to the selected group. */
    private List<OceanCanvasZoneClientCache.PlanningVector> planVectors(String groupId){
        var out=new ArrayList<OceanCanvasZoneClientCache.PlanningVector>();
        for(var v:OceanCanvasZoneClientCache.planningVectors()) if(groupId.equals(v.parentId())) out.add(v);
        return out;
    }
    /** Picks an initial selection once per screen so the panels open populated, never on mock data. */
    private void autoSelect(){
        if(autoSelected) return; autoSelected=true;
        var zs=zones();
        if(!zs.isEmpty()){
            double bestD=Double.MAX_VALUE; String best=zs.get(0).name();
            for(var z:zs){
                double cx=(z.minX()+z.maxX())/2.0-mapViewX, cz=(z.minZ()+z.maxZ())/2.0-mapViewZ;
                double d=cx*cx+cz*cz; if(d<bestD){bestD=d;best=z.name();}
            }
            selectedRegion=best;
        }
        var groups=OceanCanvasZoneClientCache.planGroups();
        if(!groups.isEmpty()) selectedPlan=groups.get(0).id();
        String active=OceanCanvasZoneClientCache.activeWorkProject();
        var projects=OceanCanvasZoneClientCache.workspaceProjects();
        for(var p:projects) if(p.id().equals(active)) selectedProject=p.id();
        if(selectedProject.isEmpty()&&!projects.isEmpty()) selectedProject=projects.get(0).id();
    }
    private int canvasSize(){
        try{ return Integer.parseInt(OceanCanvasZoneClientCache.config().getOrDefault("canvasSize","20000")); }
        catch(NumberFormatException e){ return 20000; }
    }
    private static String prettyId(String id){
        if(id==null||id.isBlank()) return "";
        String s=id.contains(":")?id.substring(id.indexOf(':')+1):id;
        s=s.replace('_',' ');
        StringBuilder b=new StringBuilder();
        boolean up=true;
        for(char c:s.toCharArray()){ b.append(up?Character.toUpperCase(c):c); up=(c==' '); }
        return b.toString();
    }
    private static String title(String enumName){
        if(enumName==null||enumName.isBlank()) return "Planned";
        return prettyId(enumName.toLowerCase(Locale.ROOT));
    }
    private static int statusColor(String status){
        String s=status==null?"":status.toUpperCase(Locale.ROOT);
        return switch(s){ case "ACTIVE","IN_PROGRESS","READY","COMPLETE" -> GREEN; case "BLOCKED" -> RED; case "ARCHIVED","SKIPPED" -> DIM; default -> GOLD; };
    }
    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload){
        if(minecraft!=null&&minecraft.getConnection()!=null) ClientPlayNetworking.send(payload);
    }
    private void planning(String action,String id,String a1,String a2){send(new OceanCanvasPlanningEditRequestPayload(action,id,a1==null?"":a1,a2==null?"":a2));}
    private void p4Put(String kind,String name,String target,String points,double valueA,double valueB,String text){
        String spec=b64(name)+"\t"+b64(target)+"\t"+b64(points)+"\t"+String.format(Locale.US,"%.4f",valueA)+"\t"+String.format(Locale.US,"%.4f",valueB)+"\t"+b64(text);
        planning("p4_put","",kind,spec);
    }
    private String p4SelectedPoints(boolean geometry){var v=planObject();if(geometry&&v!=null&&!v.points().isBlank())return v.points();return ((int)Math.round(mapViewX))+","+((int)Math.round(mapViewZ));}
    private String p4SelectedTarget(){if(!selectedPlanObject.isBlank())return selectedPlanObject;if(!selectedReference.isBlank())return selectedReference;return "world";}
    private void workspace(String action,String id,String a1,String a2){send(new OceanCanvasWorkspaceEditRequestPayload(action,id,a1==null?"":a1,a2==null?"":a2));}
    private void projectMeta(String key,String value){var z=region();if(z==null){say("Select a region first");return;}send(new OceanCanvasProjectEditRequestPayload(z.name(),key,value==null?"":value));}

    /** Two-step destructive confirmation. Returns true when the caller should perform the action. */
    private boolean confirm(String key,String prompt){
        long now=System.currentTimeMillis();
        if(key.equals(armed)&&now<armedUntil){ armed=null; armedUntil=0L; return true; }
        armed=key; armedUntil=now+5000L; say(prompt);
        return false;
    }
    private boolean isArmed(String key){return key.equals(armed)&&System.currentTimeMillis()<armedUntil;}

    /** Server-authored dry run is the first confirmation step; a matching fresh token is the second. */
    private String operationPreviewToken(String kind,String target,String argument){
        var p=OceanCanvasZoneClientCache.operationPreview();
        if(p==null||p.stale(30_000L)||!p.matches(kind,target,argument)||p.blocked())return null;
        return p.token();
    }
    private boolean ensureOperationPreview(String kind,String target,String argument){
        var p=OceanCanvasZoneClientCache.operationPreview();
        if(p!=null&&!p.stale(30_000L)&&p.matches(kind,target,argument)){
            if(p.blocked()){say(p.summary());return false;}
            return true;
        }
        OceanCanvasZoneClientCache.clearOperationPreview();
        send(new OceanCanvasOperationPreviewRequestPayload(kind,target,argument==null?"":argument));
        say("Server dry run requested — review the impact, then click again to confirm");
        return false;
    }

    /** The staged edit for this Region, if any. */
    private PendingRegionGeometry pendingGeometryFor(OceanCanvasZoneSyncPayload.ZoneEntry z){
        if(z==null||pendingRegionGeometry==null)return null;
        return z.name().equalsIgnoreCase(pendingRegionGeometry.regionName())?pendingRegionGeometry:null;
    }
    /** Geometry shown in the map/panel while a server impact preview is pending. */
    private List<Integer> regionDisplayVertices(OceanCanvasZoneSyncPayload.ZoneEntry z){
        var pending=pendingGeometryFor(z);
        if(pending!=null&&pending.polygon())return pending.vertices();
        return z==null||z.shapeVertices()==null?List.of():cleanVertices(z.shapeVertices());
    }
    /** minX,minZ,maxX,maxZ shown while a rectangle edit is staged. */
    private int[] regionDisplayBounds(OceanCanvasZoneSyncPayload.ZoneEntry z){
        var pending=pendingGeometryFor(z);
        if(pending!=null&&!pending.polygon())return new int[]{pending.minX(),pending.minZ(),pending.maxX(),pending.maxZ()};
        return z==null?new int[]{0,0,0,0}:new int[]{z.minX(),z.minZ(),z.maxX(),z.maxZ()};
    }
    private void stageRegionRectangleGeometry(String name,int minX,int minZ,int maxX,int maxZ){
        String argument=rectGeometryArgument(minX,minZ,maxX,maxZ);
        pendingRegionGeometry=new PendingRegionGeometry(name,false,minX,minZ,maxX,maxZ,List.of(),argument,System.currentTimeMillis());
        requestPendingRegionGeometryPreview();
    }
    private void stageRegionPolygonGeometry(String name,List<Integer> raw){
        List<Integer> vertices=cleanVertices(raw);
        String argument=polygonGeometryArgument(vertices);
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(int i=0;i+1<vertices.size();i+=2){minX=Math.min(minX,vertices.get(i));maxX=Math.max(maxX,vertices.get(i));minZ=Math.min(minZ,vertices.get(i+1));maxZ=Math.max(maxZ,vertices.get(i+1));}
        pendingRegionGeometry=new PendingRegionGeometry(name,true,minX,minZ,maxX,maxZ,List.copyOf(vertices),argument,System.currentTimeMillis());
        requestPendingRegionGeometryPreview();
    }
    /**
     * Coordinate/vertex edits use the same server-authoritative dry run as every other world
     * operation, but unlike the old UI they keep the requested coordinates visible while it runs.
     */
    private void requestPendingRegionGeometryPreview(){
        var pending=pendingRegionGeometry;if(pending==null)return;
        var preview=OceanCanvasZoneClientCache.operationPreview();
        if(preview!=null&&!preview.stale(30_000L)&&preview.matches("REGION_GEOMETRY",pending.regionName(),pending.argument())){
            resolvePendingRegionGeometry();return;
        }
        OceanCanvasZoneClientCache.clearOperationPreview();
        send(new OceanCanvasOperationPreviewRequestPayload("REGION_GEOMETRY",pending.regionName(),pending.argument()));
        say("Checking Region geometry impact… edited coordinates are staged");
    }
    private OceanCanvasZoneSyncPayload.ZoneEntry zoneByName(String name){
        if(name==null||name.isBlank())return null;
        for(var z:zones())if(z.name().equalsIgnoreCase(name))return z;
        return null;
    }
    private boolean pendingGeometryNeedsExplicitConfirm(OceanCanvasZoneSyncPayload.ZoneEntry z,OceanCanvasZoneClientCache.OperationPreview preview){
        return (z!=null&&z.enabled())||preview.linkedProjects()>0||preview.linkedTasks()>0||(preview.warnings()!=null&&!preview.warnings().isBlank());
    }
    /** Auto-applies low-risk draft edits; protected/linked Regions remain visibly staged for Apply. */
    private void resolvePendingRegionGeometry(){
        var pending=pendingRegionGeometry;if(pending==null)return;
        var preview=OceanCanvasZoneClientCache.operationPreview();
        if(preview==null||preview.stale(30_000L)||!preview.matches("REGION_GEOMETRY",pending.regionName(),pending.argument()))return;
        if(preview.blocked())return;
        var z=zoneByName(pending.regionName());
        if(!pendingGeometryNeedsExplicitConfirm(z,preview))submitPendingRegionGeometry();
    }
    private boolean submitPendingRegionGeometry(){
        var pending=pendingRegionGeometry;if(pending==null)return false;
        var preview=OceanCanvasZoneClientCache.operationPreview();
        if(preview==null||preview.stale(30_000L)||!preview.matches("REGION_GEOMETRY",pending.regionName(),pending.argument())){
            requestPendingRegionGeometryPreview();return false;
        }
        if(preview.blocked()){say(preview.summary());return false;}
        String token=preview.token();
        if(pending.polygon()){
            if(cleanVertices(pending.vertices()).size()<6){say("Polygon needs at least 3 vertices");return false;}
            send(new OceanCanvasZoneShapeRequestPayload(pending.regionName(),"REPLACE",List.of(),pending.vertices(),token));
        }else{
            send(new OceanCanvasZoneResizeRequestPayload(pending.regionName(),pending.minX(),pending.minZ(),pending.maxX(),pending.maxZ(),token));
        }
        String name=pending.regionName();pendingRegionGeometry=null;OceanCanvasZoneClientCache.clearOperationPreview();
        say("Applied Region geometry change to "+name);return true;
    }
    private void cancelPendingRegionGeometry(){
        if(pendingRegionGeometry==null)return;
        pendingRegionGeometry=null;OceanCanvasZoneClientCache.clearOperationPreview();say("Region geometry change cancelled");
    }

    // ---------------------------------------------------------------- render
    /** Completes a deferred link once the created record shows up in a sync. */
    private void resolvePendingLink(){
        PendingLink link=pendingLink;
        if(link==null) return;
        if(System.currentTimeMillis()>link.deadline()){ pendingLink=null; return; }
        if(link.kind().equals("plan_group")){
            for(var v:OceanCanvasZoneClientCache.planningVectors()){
                if((v.parentId()==null||v.parentId().isBlank())&&link.match().equals(v.points())){
                    planning("group",v.id(),link.target(),"");
                    pendingLink=null; return;
                }
            }
        }else if(link.kind().equals("task_project")){
            for(var t:OceanCanvasZoneClientCache.workspaceTasks()){
                if((t.projectId()==null||t.projectId().isBlank())&&link.match().equals(t.x()+","+t.z())){
                    workspace("task_project",t.id(),link.target(),"");
                    pendingLink=null; return;
                }
            }
        }
    }

    /** Applies the exact polygon of a just-created Region clone after the server has assigned/synced it. */
    private void resolvePendingRegionShape(){
        if(pendingRegionShapeName.isBlank()||pendingRegionShapeVertices.isEmpty()) return;
        for(var z:zones()){
            if(!z.name().equalsIgnoreCase(pendingRegionShapeName)) continue;
            List<Integer> wanted=cleanVertices(pendingRegionShapeVertices);
            List<Integer> current=cleanVertices(z.shapeVertices());
            if(!wanted.equals(current)) sendPolygonShape(z.name(),wanted,true);
            pendingRegionShapeName=""; pendingRegionShapeVertices=List.of();
            return;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        // Samples broadcast telemetry at most once a second; the trend invariants, the decay
        // signal and the signature matcher all need history the snapshot alone does not carry.
        OceanCanvasDiagnosticModel.tick();
        OceanCanvasP3UXState.tick();
        autoSelect();
        resolvePendingLink();
        resolvePendingRegionShape();
        resolvePendingRegionGeometry();
        updateChangeHeatmap();
        hits.clear();
        g.fill(0,0,width,height,BLACK);
        float scale=uiScale(), ox=uiOffsetX(), oy=uiOffsetY();
        int dmx=(int)Math.floor(ux(mx)), dmy=(int)Math.floor(uy(my));
        g.pose().pushMatrix();
        g.pose().translate(ox,oy);
        g.pose().scale(scale,scale);
        if(Boolean.TRUE.equals(layerOn.get("terrain"))) drawLiveWorldMap(g); else fill(g,0,0,DESIGN_W,DESIGN_H,BLACK);
        if(Boolean.TRUE.equals(layerOn.get("grid"))) drawGrid(g);
        drawMapLayers(g);
        if(!focusMode) drawPlayerMarker(g);
        if(drawing) drawRubberBand(g);
        if(!gestureVertices.isEmpty()) drawGesturePreview(g);
        if(uiMinimized){
            drawRestoreUi(g);
        }else{
            if(panelVisible.get("header")) drawHeader(g);
            if(panelVisible.get("navigation")){ drawTabs(g); drawBreadcrumb(g); }
            if(!focusMode&&panelVisible.get("tools")) drawToolRail(g);
            if(!focusMode&&panelVisible.get("compass") && !tab.equals("LAYERS")) drawCompass(g);
            if(panelVisible.get("coords") && Boolean.TRUE.equals(layerOn.get("coords"))) drawCoords(g);
            if(!focusMode&&panelVisible.get("right") && panelOpen) drawRightPanel(g); else if(!focusMode&&panelVisible.get("right")) drawPanelReopen(g);
            if(!focusMode&&panelVisible.get("operation") && (OceanCanvasP3UXState.complexity()!=OceanCanvasP3UXState.Complexity.BEGINNER || OceanCanvasZoneClientCache.job()!=null)) drawOperations(g);
            drawBottomToolbar(g);
            if("panels".equals(overlay)) drawPanelVisibility(g);
            if(needsVisibilityRecovery()) drawVisibilityRecovery(g);
            if("workbench".equals(overlay)) drawWorkbench(g);
            if("help".equals(overlay)) drawHelp(g);
            if("palette".equals(overlay)) drawPalette(g);
            if("activity".equals(overlay)) drawActivityCenter(g);
            if("p3ux".equals(overlay)) drawP3UxPanel(g);
            if("goal_picker".equals(overlay)) drawGoalPicker(g);
            if(explain!=null) drawExplain(g);
            if(editKey!=null) drawContextMiniMapLens(g);
            drawServerFeedback(g);
            if(toast!=null && System.currentTimeMillis()<toastUntil) drawToast(g); else if(toast!=null) toast=null;
            drawTooltip(g,dmx,dmy);
        }
        g.pose().popMatrix();
    }

    /** Topmost registered rectangle under the cursor, or null. Later registrations win. */
    private Hit topHit(double mx,double my){
        for(int i=hits.size()-1;i>=0;i--){ Hit h=hits.get(i); if(hitBox(mx,my,h.x(),h.y(),h.w(),h.h())) return h; }
        return null;
    }
    private void drawTooltip(GuiGraphicsExtractor g,int mx,int my){
        Hit h=topHit(mx,my);
        if(h==null||h.tip()==null||h.tip().isBlank()) return;
        String s=h.tip();
        int w=font.width(s)+12, x=Math.min(mx+12,DESIGN_W-w-4), y=Math.max(4,my-20);
        fill(g,x,y,w,17,0xF0000000); border(g,x,y,w,17,CY); text(g,s,x+6,y+5,TEXT);
    }

    // ---------------------------------------------------------------- map
    private void drawLiveWorldMap(GuiGraphicsExtractor g){
        if(minecraft==null || minecraft.level==null){fill(g,0,0,DESIGN_W,DESIGN_H,BLACK);return;}
        mapTerrain.tickAndMaybeExpire(System.currentTimeMillis());
        drawLiveTerrainFallback(g);
        OceanCanvasMapRasterRenderer.Frame frame=mapRaster.frame(DESIGN_W,DESIGN_H,mapViewX,mapViewZ,mapBlocksPerPixel);
        if(frame!=null && Math.abs(frame.blocksPerPixel()-mapBlocksPerPixel)<=Math.max(0.002,mapBlocksPerPixel*0.002)){
            int dx=(int)Math.round((frame.viewX()-mapViewX)/mapBlocksPerPixel + (DESIGN_W-frame.width())*0.5);
            int dy=(int)Math.round((frame.viewZ()-mapViewZ)/mapBlocksPerPixel + (DESIGN_H-frame.height())*0.5);
            g.blit(RenderPipelines.GUI_TEXTURED,frame.texture(),dx,dy,0,0,frame.width(),frame.height(),frame.width(),frame.height());
        }
    }
    private void drawLiveTerrainFallback(GuiGraphicsExtractor g){
        int step=OceanCanvasMapTerrain.stepForCellSize(mapBlocksPerPixel,3);
        double cell=Math.max(3.0,step/mapBlocksPerPixel);
        int cols=(int)Math.ceil(DESIGN_W/cell)+2,rows=(int)Math.ceil(DESIGN_H/cell)+2;
        double left=mapViewX-DESIGN_W*0.5*mapBlocksPerPixel,top=mapViewZ-DESIGN_H*0.5*mapBlocksPerPixel;
        int startX=Math.floorDiv((int)Math.floor(left),step)*step,startZ=Math.floorDiv((int)Math.floor(top),step)*step;
        for(int rz=0;rz<rows;rz++){int wz=startZ+rz*step;int sy=(int)Math.floor((wz-top)/mapBlocksPerPixel);int ey=(int)Math.ceil((wz+step-top)/mapBlocksPerPixel);
            for(int cx=0;cx<cols;cx++){int wx=startX+cx*step;int sx=(int)Math.floor((wx-left)/mapBlocksPerPixel);int ex=(int)Math.ceil((wx+step-left)/mapBlocksPerPixel);
                int c=mapTerrain.colorAt(minecraft.level,wx,wz,step);if(c!=OceanCanvasMapTerrain.UNKNOWN)fill(g,sx,sy,Math.max(1,ex-sx),Math.max(1,ey-sy),c);
            }
        }
    }
    private int mapScreenX(double worldX){return (int)Math.round(DESIGN_W*0.5+(worldX-mapViewX)/mapBlocksPerPixel);}
    private int mapScreenY(double worldZ){return (int)Math.round(DESIGN_H*0.5+(worldZ-mapViewZ)/mapBlocksPerPixel);}
    private double mapWorldX(double designX){return mapViewX+(designX-DESIGN_W*0.5)*mapBlocksPerPixel;}
    private double mapWorldZ(double designY){return mapViewZ+(designY-DESIGN_H*0.5)*mapBlocksPerPixel;}
    private void drawRestoreUi(GuiGraphicsExtractor g){
        int x=DESIGN_W-62,y=14;fill(g,x,y,44,44,0xE6000000);border(g,x,y,44,44,CY);icon(g,"layers",x+14,y+14,CY);
        hit(x,y,44,44,"Restore the Ocean Canvas interface",()->{uiMinimized=false;say("UI restored");});
    }
    private void drawPanelReopen(GuiGraphicsExtractor g){
        // Stacks below the panel-visibility recovery control rather than colliding with it when
        // both recovery affordances are on screen at once.
        int x=DESIGN_W-62,y=needsVisibilityRecovery()?118:72;
        fill(g,x,y,44,44,0xE6000000);border(g,x,y,44,44,CY);icon(g,"layers",x+14,y+14,CY);
        hit(x,y,44,44,"Reopen the "+tab.charAt(0)+tab.substring(1).toLowerCase(Locale.ROOT)+" panel",()->{panelOpen=true;collapsed=false;});
    }
    private void drawGrid(GuiGraphicsExtractor g){
        int step=64; for(int x=0;x<=DESIGN_W;x+=step) fill(g,x,0,1,DESIGN_H,0xFF0E1417); for(int y=0;y<=DESIGN_H;y+=step) fill(g,0,y,DESIGN_W,1,0xFF0E1417);
    }
    private void drawMapLayers(GuiGraphicsExtractor g){
        if(beforeAfterEnabled) drawBeforeAfterOverlay(g);
        if(focusMode){
            drawFocusedRegionLayer(g);
            if(Boolean.TRUE.equals(layerOn.get("plans"))) drawPlansLayer(g);
            if(Boolean.TRUE.equals(layerOn.get("projects"))) drawProjectsLayer(g);
            drawFocusChip(g);
            return;
        }
        if(Boolean.TRUE.equals(layerOn.get("refimg"))) drawReferenceImagesLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("regions"))) drawSyncedRegions(g);
        if(Boolean.TRUE.equals(layerOn.get("biomes"))) drawBiomeOverrideLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("plans"))) drawPlansLayer(g);
        drawP4PlanningOverlay(g);
        if(Boolean.TRUE.equals(layerOn.get("blueprints"))) drawBlueprintsLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("projects"))) drawProjectsLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("markers"))) drawMarkersLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("health"))||Boolean.TRUE.equals(layerOn.get("health_light"))||Boolean.TRUE.equals(layerOn.get("health_liquid"))) drawHealthFindingsLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("pregen_state"))) drawQueuedPregenLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("structures")) && (Boolean.TRUE.equals(layerOn.get("shipwrecks"))||Boolean.TRUE.equals(layerOn.get("monuments"))||Boolean.TRUE.equals(layerOn.get("portals")))) drawStructuresLayer(g);
        if(Boolean.TRUE.equals(layerOn.get("regions"))) drawSelectedRegionCanvas(g);
        drawActiveOperationRegionOverlay(g);
        if(operationGhostEnabled) drawOperationGhostOverlay(g);
        if(OceanCanvasP3UXState.heatmapEnabled()) drawChangeHeatmap(g);
        drawProgramAnalyticOverlays(g);
        drawProgramAnnotations(g);
        if(OceanCanvasZoneClientCache.programFeatureActive("OC-F070")||OceanCanvasZoneClientCache.programFeatureActive("OC-F252")) drawProgramPresence(g);
    }

    private OceanCanvasZoneClientCache.WorkspaceTask focusedTask(){
        for(var t:OceanCanvasZoneClientCache.workspaceTasks())if(t.id().equals(selectedTask))return t;
        return null;
    }
    private String focusedProjectId(){var t=focusedTask();return t!=null&&!t.projectId().isBlank()?t.projectId():selectedProject;}
    private String focusedRegionName(){var t=focusedTask();if(t!=null&&!t.regionName().isBlank())return t.regionName();var p=project();return p==null?selectedRegion:p.regionName();}
    private String focusedPlanObjectId(){var t=focusedTask();return t==null?"":t.planningObjectId();}
    private boolean focusIncludesPlan(String objectId){
        String tid=focusedPlanObjectId();if(!tid.isBlank())return tid.equals(objectId);
        String pid=focusedProjectId();if(pid.isBlank())return objectId.equals(selectedPlanObject);
        for(var p:OceanCanvasZoneClientCache.workspaceProjects())if(p.id().equals(pid)){
            for(String id:p.planningObjectIds().split(","))if(id.trim().equals(objectId))return true;
        }
        return false;
    }
    private void drawFocusedRegionLayer(GuiGraphicsExtractor g){
        String name=focusedRegionName();if(name==null||name.isBlank())return;
        for(var z:zones())if(z.name().equalsIgnoreCase(name)){
            int l=mapScreenX(z.minX()),t=mapScreenY(z.minZ()),r=mapScreenX(z.maxX()),b=mapScreenY(z.maxZ());
            if(r<0||b<0||l>DESIGN_W||t>DESIGN_H)return;fill(g,l,t,Math.max(1,r-l),Math.max(1,b-t),0x221FC4EF);border(g,l,t,Math.max(1,r-l),Math.max(1,b-t),CY);return;
        }
    }
    private void drawFocusChip(GuiGraphicsExtractor g){
        String label="FOCUS · "+(focusedTask()!=null?focusedTask().title():(project()!=null?project().name():"selection"));
        int w=Math.min(360,font.width(label)+54),x=DESIGN_W-w-18,y=14;fill(g,x,y,w,26,0xF0000000);border(g,x,y,w,26,CY);text(g,trim(label,w-36),x+8,y+9,CY);text(g,"×",x+w-20,y+8,BRIGHT);
        hit(x,y,w,26,"Exit Focus Mode",()->focusMode=false);
    }
    private void drawBeforeAfterOverlay(GuiGraphicsExtractor g){
        if(beforeAfterSnapshotId==null||beforeAfterSnapshotId.isBlank())return;String[] row=null;
        for(String[] n:healthRows("N"))if(n.length>=8&&beforeAfterSnapshotId.equals(n[1])){row=n;break;}if(row==null)return;
        int divider=(int)Math.round(DESIGN_W*Math.max(.05,Math.min(.95,beforeAfterDivider)));String packed;
        try{packed=decodeB64(row[7]);}catch(RuntimeException ex){return;}
        for(String raw:packed.split(",")){int eq=raw.indexOf('=');if(eq<=0)continue;try{
            long key=Long.parseUnsignedLong(raw.substring(0,eq));String state=raw.substring(eq+1);int cx=(int)key,cz=(int)(key>>>32);
            int l=mapScreenX(cx*16),r=mapScreenX(cx*16+16),t=mapScreenY(cz*16),b=mapScreenY(cz*16+16);int left=Math.max(0,Math.min(l,r)),right=Math.min(divider,Math.max(l,r));if(right<=left)continue;int top=Math.max(0,Math.min(t,b)),bottom=Math.min(DESIGN_H,Math.max(t,b));if(bottom<=top)continue;
            int c=switch(state){case "CANVAS"->0x8837B7D1;case "VANILLA"->0x885497C7;case "CUSTOM_OR_MODIFIED"->0x88E0B25A;default->0x664D5358;};fill(g,left,top,right-left,bottom-top,c);
        }catch(RuntimeException ignored){}}
        fill(g,divider-1,0,2,DESIGN_H,0xFFE0E0E0);fill(g,12,52,122,20,0xD9000000);text(g,"BEFORE",20,59,BRIGHT);fill(g,divider+12,52,122,20,0xD9000000);text(g,"CURRENT",divider+20,59,BRIGHT);
    }

    private record ArchaeologyEvent(long at,String title,String detail,String actor,OceanCanvasZoneClientCache.RecoveryHistory operation,OceanCanvasZoneClientCache.WorldEvent worldEvent){}
    private List<ArchaeologyEvent> archaeologyTimeline(){
        var out=new ArrayList<ArchaeologyEvent>();
        for(var h:OceanCanvasZoneClientCache.recoveryHistory())out.add(new ArchaeologyEvent(h.epochMillis(),title(h.kind())+" · "+title(h.phase()),h.detail(),h.requester(),h,null));
        for(var e:OceanCanvasZoneClientCache.worldEvents())if(java.util.Set.of("REGION","PLAN","STRUCTURE","TERRAIN_ASSET","PROJECT").contains(e.targetType()))out.add(new ArchaeologyEvent(e.happenedAt(),title(e.targetType())+" · "+title(e.eventType())+" · "+e.title(),e.detail(),e.actor(),null,e));
        out.sort(java.util.Comparator.comparingLong(ArchaeologyEvent::at).reversed());return List.copyOf(out);
    }
    private void focusArchaeologyEvent(ArchaeologyEvent e){
        if(e==null)return;
        if(e.operation()!=null&&e.operation().hasScope()){var scoped=scopedRecoveryHistory();int at=scoped.indexOf(e.operation());if(at>=0){operationGhostIndex=at;operationGhostEnabled=true;}mapViewX=((e.operation().minChunkX()+e.operation().maxChunkX()+1)*16.0)/2.0;mapViewZ=((e.operation().minChunkZ()+e.operation().maxChunkZ()+1)*16.0)/2.0;say("Focused historical operation scope");return;}
        var w=e.worldEvent();if(w!=null&&"REGION".equals(w.targetType())){String id=w.targetId();var z=zones().stream().filter(v->v.name().toLowerCase(Locale.ROOT).replace(' ','_').equals(id)).findFirst().orElse(null);if(z!=null){mapViewX=(z.minX()+z.maxX())/2.0;mapViewZ=(z.minZ()+z.maxZ())/2.0;selectedRegion=z.name();say("Focused historical region "+z.name());return;}}
        say("This history event has no persisted spatial scope to focus");
    }

    private List<OceanCanvasZoneClientCache.RecoveryHistory> scopedRecoveryHistory(){
        return OceanCanvasZoneClientCache.recoveryHistory().stream().filter(OceanCanvasZoneClientCache.RecoveryHistory::hasScope).toList();
    }
    private void drawOperationGhostOverlay(GuiGraphicsExtractor g){
        var scoped=scopedRecoveryHistory();if(scoped.isEmpty())return;
        operationGhostIndex=Math.floorMod(operationGhostIndex,scoped.size());var h=scoped.get(operationGhostIndex);
        final int ghost=0xFFFFB347;
        boolean exactDrawn=drawGhostDescriptor(g,h.scopeDescriptor(),ghost);
        // Backward compatibility for history schema <=3: only old entries may consult the
        // current region geometry. Schema-4 entries always carry immutable historical geometry.
        if(!exactDrawn&&"REGION".equalsIgnoreCase(h.scopeType())&&!h.scopeId().isBlank()){
            var z=zones().stream().filter(v->v.name().equalsIgnoreCase(h.scopeId())).findFirst().orElse(null);
            if(z!=null&&z.shapeVertices()!=null&&z.shapeVertices().size()>=6){
                var v=z.shapeVertices();int firstX=0,firstY=0,prevX=0,prevY=0;
                for(int i=0;i+1<v.size();i+=2){int sx=mapScreenX(v.get(i)),sy=mapScreenY(v.get(i+1));if(i==0){firstX=sx;firstY=sy;}else line2(g,prevX,prevY,sx,sy,ghost);prevX=sx;prevY=sy;}
                line2(g,prevX,prevY,firstX,firstY,ghost);exactDrawn=true;
            }
        }
        if(!exactDrawn){
            int x0=mapScreenX(h.minChunkX()*16.0),z0=mapScreenY(h.minChunkZ()*16.0);
            int x1=mapScreenX((h.maxChunkX()+1)*16.0),z1=mapScreenY((h.maxChunkZ()+1)*16.0);
            int l=Math.min(x0,x1),r=Math.max(x0,x1),t=Math.min(z0,z1),b=Math.max(z0,z1);
            line2(g,l,t,r,t,ghost);line2(g,r,t,r,b,ghost);line2(g,r,b,l,b,ghost);line2(g,l,b,l,t,ghost);
        }
        String label="GHOST "+title(h.kind())+" · "+title(h.phase())+(h.scopeId().isBlank()?"":" · "+h.scopeId());
        int lx=Math.max(8,Math.min(DESIGN_W-font.width(label)-16,mapScreenX(h.minChunkX()*16.0)+6));
        int ly=Math.max(8,Math.min(DESIGN_H-22,mapScreenY(h.minChunkZ()*16.0)-18));
        fill(g,lx-4,ly-3,font.width(label)+8,16,0xD9000000);text(g,label,lx,ly,ghost);
    }

    /** Draw immutable operation-history geometry captured at operation start (OC-F265). */
    private boolean drawGhostDescriptor(GuiGraphicsExtractor g,String descriptor,int color){
        if(descriptor==null||descriptor.isBlank())return false;
        try{
            if(descriptor.startsWith("POLY:")){
                String[] f=descriptor.substring(5).split(",");if(f.length<6||(f.length&1)!=0)return false;
                int firstX=0,firstY=0,prevX=0,prevY=0;for(int i=0;i+1<f.length;i+=2){int wx=Integer.parseInt(f[i]),wz=Integer.parseInt(f[i+1]);int sx=mapScreenX(wx),sy=mapScreenY(wz);if(i==0){firstX=sx;firstY=sy;}else line2(g,prevX,prevY,sx,sy,color);prevX=sx;prevY=sy;}line2(g,prevX,prevY,firstX,firstY,color);return true;
            }
            if(descriptor.startsWith("RUNS:")){
                String raw=descriptor.substring(5);if(raw.isBlank())return false;for(String run:raw.split(";")){int c=run.indexOf(':'),dash=run.indexOf('-',c+1);if(c<1||dash<0)continue;int z=Integer.parseInt(run.substring(0,c)),x0=Integer.parseInt(run.substring(c+1,dash)),x1=Integer.parseInt(run.substring(dash+1));int l=mapScreenX(x0*16.0),r=mapScreenX((x1+1)*16.0),t=mapScreenY(z*16.0),b=mapScreenY((z+1)*16.0);int minX=Math.min(l,r),maxX=Math.max(l,r),minY=Math.min(t,b),maxY=Math.max(t,b);line2(g,minX,minY,maxX,minY,color);line2(g,maxX,minY,maxX,maxY,color);line2(g,maxX,maxY,minX,maxY,color);line2(g,minX,maxY,minX,minY,color);}return true;
            }
            if(descriptor.startsWith("RECT:")){String[] f=descriptor.substring(5).split(",");if(f.length!=4)return false;int x0=Integer.parseInt(f[0]),z0=Integer.parseInt(f[1]),x1=Integer.parseInt(f[2]),z1=Integer.parseInt(f[3]);int l=mapScreenX(x0*16.0),r=mapScreenX((x1+1)*16.0),t=mapScreenY(z0*16.0),b=mapScreenY((z1+1)*16.0);int minX=Math.min(l,r),maxX=Math.max(l,r),minY=Math.min(t,b),maxY=Math.max(t,b);line2(g,minX,minY,maxX,minY,color);line2(g,maxX,minY,maxX,maxY,color);line2(g,maxX,maxY,minX,maxY,color);line2(g,minX,maxY,minX,minY,color);return true;}
        }catch(RuntimeException ignored){}return false;
    }
    private String worldMapMode(){String v=OceanCanvasZoneClientCache.worldMapMode();return v==null||v.isBlank()?"ALL":v;}
    private boolean regionMatchesWorldMode(String regionName){
        String mode=worldMapMode();if("ALL".equals(mode))return true;
        var meta=OceanCanvasZoneClientCache.projectRegion(regionName);String life=meta==null?"PLANNED":meta.lifecycle();
        if("UNFINISHED".equals(mode))return !"COMPLETE".equals(life);
        return mode.equals(life);
    }
    private int lifecycleColor(String regionName,boolean selected,boolean enabled){
        if(selected)return CY;var meta=OceanCanvasZoneClientCache.projectRegion(regionName);String life=meta==null?"PLANNED":meta.lifecycle();
        return switch(life){case "COMPLETE"->GREEN;case "PARTIAL"->GOLD;case "ABANDONED"->0xAAE25B5B;case "RESTORED"->0xAA8FD8F0;case "PLANNED"->enabled?0xAA1FC4EF:0x66808080;default->enabled?0xAA1FC4EF:0x66808080;};
    }
    private boolean planMatchesWorldMode(boolean implemented){
        String mode=worldMapMode();return switch(mode){case "COMPLETE"->implemented;case "UNFINISHED","PLANNED","PARTIAL"->!implemented;case "ABANDONED","RESTORED"->false;default->true;};
    }
    private void cycleWorldMapMode(){
        String[] modes={"ALL","UNFINISHED","PLANNED","PARTIAL","ABANDONED","RESTORED","COMPLETE"};String cur=worldMapMode();int i=0;for(;i<modes.length;i++)if(modes[i].equals(cur))break;String next=modes[(i+1)%modes.length];workspace("world_map_mode","",next,"");say("World map mode → "+title(next));
    }

    /** Every synced region. Arbitrary regions render their real polygon/chunk footprint, never only the envelope. */
    private void drawSyncedRegions(GuiGraphicsExtractor g){
        for(var z:zones()){
            boolean sel=z.name().equalsIgnoreCase(selectedRegion);
            if(!sel&&!regionMatchesWorldMode(z.name()))continue;
            int col=OceanCanvasP3UXState.colorFor("region",lifecycleColor(z.name(),sel,z.enabled()));
            List<Integer> verts=z.shapeVertices()==null?List.of():z.shapeVertices();
            if(verts.size()>=6){
                int minSX=Integer.MAX_VALUE,minSY=Integer.MAX_VALUE,maxSX=Integer.MIN_VALUE,maxSY=Integer.MIN_VALUE;
                int firstX=0,firstY=0,prevX=0,prevY=0;
                for(int i=0;i+1<verts.size();i+=2){
                    int sx=mapScreenX(verts.get(i)),sy=mapScreenY(verts.get(i+1));
                    if(i==0){firstX=sx;firstY=sy;}else line(g,prevX,prevY,sx,sy,col);
                    prevX=sx;prevY=sy;minSX=Math.min(minSX,sx);minSY=Math.min(minSY,sy);maxSX=Math.max(maxSX,sx);maxSY=Math.max(maxSY,sy);
                }
                line(g,prevX,prevY,firstX,firstY,col);
                if(minSX==Integer.MAX_VALUE||maxSX<0||maxSY<0||minSX>DESIGN_W||minSY>DESIGN_H) continue;
                if(maxSX-minSX>=42&&maxSY-minSY>=14) text(g,trim(z.name(),maxSX-minSX-8),minSX+4,minSY+4,sel?CY:0xCCE2E2E2);
                if(selectable("REGIONS") && !(sel&&tab.equals("REGIONS"))){
                    final String name=z.name();
                    hit(minSX-5,minSY-5,Math.max(10,maxSX-minSX+10),Math.max(10,maxSY-minSY+10),z.name()+" - click to select",()->{
                        selectedRegion=name;selectedRegionVertex=0;tab="REGIONS";disclosure=null;armed=null;cancelEdit();say("Selected region "+name);
                    });
                }
                continue;
            }
            int l=mapScreenX(z.minX()),r=mapScreenX(z.maxX()),t=mapScreenY(z.minZ()),b=mapScreenY(z.maxZ());
            if(r<0||b<0||l>DESIGN_W||t>DESIGN_H) continue;
            int w=Math.max(1,r-l),h=Math.max(1,b-t);
            border(g,l,t,w,h,col);drawRolePattern(g,"region",l,t,w,h,col);
            if(w>=42&&h>=14) text(g,trim(z.name(),w-8),l+4,t+4,sel?CY:0xCCE2E2E2);
            if(!selectable("REGIONS") || (sel && tab.equals("REGIONS"))) continue;
            final String name=z.name();
            hit(l,t,w,h,z.name()+" - click to select"+(z.enabled()?"":" (draft)"),()->{
                selectedRegion=name;selectedRegionVertex=0;tab="REGIONS";disclosure=null;armed=null;cancelEdit();say("Selected region "+name);
            });
        }
    }
    /**
     * v253.69.7: the running Region operation is a first-class map layer.
     *
     * <p>The server now sends the Region identity captured when the operation starts. Never infer
     * it from the moving cursor: an overlapping Region can contain that cursor even though it is
     * not the operation's scope. The active outline is rendered even when the ordinary Regions
     * layer is hidden so a destructive/terrain operation can never become spatially invisible.</p>
     */
    private void drawActiveOperationRegionOverlay(GuiGraphicsExtractor g){
        var job=OceanCanvasZoneClientCache.job(); if(job==null)return;
        String scope=job.scopeName()==null?"":job.scopeName().trim();
        var z=scope.isBlank()?null:zones().stream().filter(v->v.name().equalsIgnoreCase(scope)).findFirst().orElse(null);
        final int active=GOLD;
        int minSX=Integer.MAX_VALUE,minSY=Integer.MAX_VALUE,maxSX=Integer.MIN_VALUE,maxSY=Integer.MIN_VALUE;

        // Named Region jobs use the exact Region geometry. Command/radius/Canvas jobs do not own a
        // saved Region, so fall back to the authoritative chunk bounds that the server broadcasts.
        // This makes /oceancanvas pregen just as visible on the map as a UI-started Region Pregen.
        if(z!=null){
            List<Integer> verts=z.shapeVertices()==null?List.of():z.shapeVertices();
            if(verts.size()>=6){
                int firstX=0,firstY=0,prevX=0,prevY=0;
                for(int i=0;i+1<verts.size();i+=2){
                    int sx=mapScreenX(verts.get(i)),sy=mapScreenY(verts.get(i+1));
                    if(i==0){firstX=sx;firstY=sy;}else{line2(g,prevX,prevY,sx,sy,active);line(g,prevX-1,prevY,sx-1,sy,active);}
                    prevX=sx;prevY=sy;minSX=Math.min(minSX,sx);minSY=Math.min(minSY,sy);maxSX=Math.max(maxSX,sx);maxSY=Math.max(maxSY,sy);
                }
                line2(g,prevX,prevY,firstX,firstY,active);line(g,prevX-1,prevY,firstX-1,firstY,active);
            }else{
                int l=mapScreenX(z.minX()),r=mapScreenX(z.maxX()),t=mapScreenY(z.minZ()),b=mapScreenY(z.maxZ());
                minSX=Math.min(l,r);maxSX=Math.max(l,r);minSY=Math.min(t,b);maxSY=Math.max(t,b);
                fill(g,minSX,minSY,Math.max(1,maxSX-minSX),Math.max(1,maxSY-minSY),0x18FFB347);
                border(g,minSX,minSY,Math.max(1,maxSX-minSX),Math.max(1,maxSY-minSY),active);
                border(g,minSX+1,minSY+1,Math.max(1,maxSX-minSX-2),Math.max(1,maxSY-minSY-2),active);
            }
        }else{
            int minCx=Math.min(job.minChunkX(),job.maxChunkX()),maxCx=Math.max(job.minChunkX(),job.maxChunkX());
            int minCz=Math.min(job.minChunkZ(),job.maxChunkZ()),maxCz=Math.max(job.minChunkZ(),job.maxChunkZ());
            int l=mapScreenX(minCx*16),r=mapScreenX((maxCx+1)*16),t=mapScreenY(minCz*16),b=mapScreenY((maxCz+1)*16);
            minSX=Math.min(l,r);maxSX=Math.max(l,r);minSY=Math.min(t,b);maxSY=Math.max(t,b);
            fill(g,minSX,minSY,Math.max(1,maxSX-minSX),Math.max(1,maxSY-minSY),0x10FFB347);
            border(g,minSX,minSY,Math.max(1,maxSX-minSX),Math.max(1,maxSY-minSY),active);
            border(g,minSX+1,minSY+1,Math.max(1,maxSX-minSX-2),Math.max(1,maxSY-minSY-2),active);
        }

        // Always show the chunk the scheduler is currently affecting. This remains useful even
        // when the full command scope extends beyond the current viewport.
        int cursorL=mapScreenX(job.cursorChunkX()*16),cursorR=mapScreenX((job.cursorChunkX()+1)*16);
        int cursorT=mapScreenY(job.cursorChunkZ()*16),cursorB=mapScreenY((job.cursorChunkZ()+1)*16);
        int cL=Math.min(cursorL,cursorR),cR=Math.max(cursorL,cursorR),cT=Math.min(cursorT,cursorB),cB=Math.max(cursorT,cursorB);
        if(cR>=0&&cB>=0&&cL<=DESIGN_W&&cT<=DESIGN_H){
            fill(g,cL,cT,Math.max(2,cR-cL),Math.max(2,cB-cT),0x55FFB347);
            border(g,cL,cT,Math.max(2,cR-cL),Math.max(2,cB-cT),BRIGHT);
        }

        if(minSX==Integer.MAX_VALUE)return;
        long total=Math.max(1L,job.totalChunks()),done=Math.max(0L,Math.min(total,job.submittedChunks()));
        int pct=(int)Math.round(done*100.0/total);
        String target=z!=null?z.name():(scope.isBlank()?"COMMAND SCOPE":scope);
        String label=operationLabel(job.kind()).toUpperCase(Locale.ROOT)+" ACTIVE · "+target+" · "+pct+"%";
        int bw=Math.min(390,font.width(label)+20);
        int bx=Math.max(8,Math.min(DESIGN_W-bw-8,minSX+6)),by=Math.max(62,Math.min(DESIGN_H-94,minSY+6));
        fill(g,bx,by,bw,24,0xEE000000);border(g,bx,by,bw,24,active);text(g,trim(label,bw-14),bx+7,by+9,active);
        if(z!=null){
            final String regionName=z.name();
            hit(bx,by,bw,24,"Select the Region currently being affected",()->{selectedRegion=regionName;selectedRegionVertex=0;regionVertexPage=0;tab="REGIONS";panelOpen=true;collapsed=false;disclosure=null;say("Selected active Region "+regionName);});
        }else{
            hit(bx,by,bw,24,"Open live diagnostics for this command-started operation",()->{opsOpen=true;opsTab="DIAGNOSTICS";say("Showing live "+operationLabel(job.kind())+" command scope");});
        }
    }

    /** The selected region's editable geometry. Polygon regions expose their actual vertices. */
    private void drawSelectedRegionCanvas(GuiGraphicsExtractor g){
        var z=region(); if(z==null) return;
        List<Integer> verts=polygonDragIndex>=0?polygonEditVertices:regionDisplayVertices(z);
        if(verts.size()>=6){
            int firstX=0,firstY=0,prevX=0,prevY=0,minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
            for(int i=0;i+1<verts.size();i+=2){
                int sx=mapScreenX(verts.get(i)),sy=mapScreenY(verts.get(i+1));
                if(i==0){firstX=sx;firstY=sy;}else line(g,prevX,prevY,sx,sy,CY);
                prevX=sx;prevY=sy;minX=Math.min(minX,sx);minY=Math.min(minY,sy);maxX=Math.max(maxX,sx);maxY=Math.max(maxY,sy);
                if(tab.equals("REGIONS")){
                    int idx=i/2;int c=idx==Math.floorMod(selectedRegionVertex,Math.max(1,verts.size()/2))?BRIGHT:CY;
                    fill(g,sx-5,sy-5,10,10,c);
                }
            }
            line(g,prevX,prevY,firstX,firstY,CY);
            if(minX!=Integer.MAX_VALUE&&maxX-minX>=150&&maxY-minY>=90){
                int bx=(minX+maxX)/2-78,by=(minY+maxY)/2-58;
                fill(g,bx,by,156,43,BLACK);border(g,bx,by,156,43,0xFF2C3134);center(g,trim(z.name(),148),bx,by+9,156,0xFFE6E6E6);
                icon(g,"region",bx+34,by+23,z.enabled()?CY:DIM);text(g,z.enabled()?"PROTECTED":"DRAFT",bx+55,by+26,z.enabled()?CY:DIM);
            }
            return;
        }
        int[] shownBounds=regionDisplayBounds(z);
        int rx1=geometryLive?gx1:shownBounds[0], rz1=geometryLive?gz1:shownBounds[1], rx2=geometryLive?gx2:shownBounds[2], rz2=geometryLive?gz2:shownBounds[3];
        int L=mapScreenX(rx1),T=mapScreenY(rz1),Rr=mapScreenX(rx2),Bb=mapScreenY(rz2);
        int W=Rr-L,H=Bb-T; if(W<=0||H<=0) return;
        int rc=OceanCanvasP3UXState.colorFor("region",CY);fill(g,L,T,W,H,0x0D000000|(rc&0x00FFFFFF)); border(g,L,T,W,H,rc);drawRolePattern(g,"region",L,T,W,H,rc);
        fill(g,L+W/2-7,T+H/2,15,1,CY); fill(g,L+W/2,T+H/2-7,1,15,CY);
        if(W>=170&&H>=110){
            int bx=L+W/2-78,by=T+H/2-58;fill(g,bx,by,156,43,BLACK);border(g,bx,by,156,43,0xFF2C3134);
            center(g,trim(z.name(),148),bx,by+9,156,0xFFE6E6E6);icon(g,"region",bx+34,by+23,z.enabled()?CY:DIM);text(g,z.enabled()?"PROTECTED":"DRAFT",bx+55,by+26,z.enabled()?CY:DIM);
        }
        if(tab.equals("REGIONS")){
            int[][] hs={{L,T},{L+W/2,T},{L+W,T},{L+W,T+H/2},{L+W,T+H},{L+W/2,T+H},{L,T+H},{L,T+H/2}};
            for(int[] q:hs) fill(g,q[0]-5,q[1]-5,10,10,CY);
        }
    }

    private void drawGesturePreview(GuiGraphicsExtractor g){
        int n=gestureVertices.size()/2;if(n==0)return;int px=0,py=0,fx=0,fy=0;
        for(int i=0;i<n;i++){
            int sx=mapScreenX(gestureVertices.get(i*2)),sy=mapScreenY(gestureVertices.get(i*2+1));
            if(i==0){fx=sx;fy=sy;}else line(g,px,py,sx,sy,CY);
            fill(g,sx-4,sy-4,8,8,BRIGHT);px=sx;py=sy;
        }
        if(gestureKind!=null&&gestureKind.endsWith("shape")&&n>=3) line(g,px,py,fx,fy,CY);
        String hint=(gestureKind!=null&&gestureKind.endsWith("shape")?"Shape":"Path")+" · "+n+" vertices · Enter/double-click to finish · Esc to cancel";
        fill(g,18,126,Math.min(520,font.width(hint)+16),20,0xE6000000);text(g,hint,26,132,CY);
    }

    /**
     * OC-F060 general map declutter rule, applied uniformly to every point-marker layer
     * (structures, Atlas markers, Blueprint viewpoints). At any zoom, two markers whose screen
     * positions would visually overlap are noise, not information; this suppresses one that lands
     * within {@code minSpacingPx} of a marker already drawn in the same pass, keeping the first
     * (each layer's own iteration order already reflects its priority). It does not aggregate
     * markers into a count/cluster badge - v251 deliberately removed structure-cluster count
     * badges from the map, and this does not reintroduce them. No thinning happens at close zoom.
     */
    private int declutterSpacingPx(){ return mapBlocksPerPixel>=16?14:mapBlocksPerPixel>=4?9:0; }
    private boolean declutterSkip(List<int[]> drawnScreenPositions,int x,int y){
        int spacing=declutterSpacingPx();
        if(spacing<=0) return false;
        for(int[] p:drawnScreenPositions) if(Math.abs(p[0]-x)<spacing&&Math.abs(p[1]-y)<spacing) return true;
        drawnScreenPositions.add(new int[]{x,y});
        return false;
    }
    private void drawStructuresLayer(GuiGraphicsExtractor g){
        var drawn=new ArrayList<int[]>();
        for(var e:OceanCanvasZoneClientCache.structures()){
            int x=mapScreenX(e.centerX()),y=mapScreenY(e.centerZ()); if(x<0||y<0||x>=DESIGN_W||y>=DESIGN_H) continue;
            String k=switch(e.kindCode()){case 1->"shipwreck";case 2->"monument";case 3->"portal";default->null;};
            int sc=OceanCanvasP3UXState.colorFor("structure",GOLD);if(k==null){fill(g,x-2,y,5,1,sc);fill(g,x,y-2,1,5,sc);continue;}
            if((k.equals("shipwreck")&&!Boolean.TRUE.equals(layerOn.get("shipwrecks")))
                    ||(k.equals("monument")&&!Boolean.TRUE.equals(layerOn.get("monuments")))
                    ||(k.equals("portal")&&!Boolean.TRUE.equals(layerOn.get("portals")))) continue;
            if(declutterSkip(drawn,x,y)) continue;
            icon(g,k,x-8,y-8,sc);
        }
    }
    private void drawMarkersLayer(GuiGraphicsExtractor g){
        var drawn=new ArrayList<int[]>();
        for(var m:OceanCanvasZoneClientCache.atlasFeatures()){
            int x=mapScreenX(m.x()),y=mapScreenY(m.z()); if(x<0||y<0||x>=DESIGN_W||y>=DESIGN_H) continue;
            if(declutterSkip(drawn,x,y)) continue;
            boolean sel=m.id().equals(selectedWaypoint);
            icon(g,"waypoint",x-8,y-8,sel?BRIGHT:waypointColor(m.color()));
            if(mapSelectionActive()){
                final String fid=m.id();
                hit(x-8,y-8,16,16,m.name()+" ("+title(m.type())+")",()->{
                    selectedWaypoint=fid; tab="LAYERS"; disclosure=null; armed=null; cancelEdit(); say("Selected "+m.name());
                });
            }
        }
    }
    /**
     * The player, drawn as a small triangle that actually points where they are facing - ported
     * from the archived legacy UI's {@code OceanCanvasMapScreen#drawPlayerMarker}, dropped when this screen
     * replaced it and never carried forward. Yaw 0 faces +Z in Minecraft, which is south, which
     * is down-screen on a north-up map; the vector below follows from that directly. Always
     * shown when a player exists - there is no layer toggle for your own position, matching
     * every other Minecraft/JourneyMap-style map.
     */
    private void drawPlayerMarker(GuiGraphicsExtractor g){
        if(minecraft==null||minecraft.player==null) return;
        double sx=mapScreenX(minecraft.player.getX()), sy=mapScreenY(minecraft.player.getZ());
        if(sx<-12||sy<-12||sx>DESIGN_W+12||sy>DESIGN_H+12){ drawOffscreenPlayerHint(g,sx,sy); return; }
        double a=Math.toRadians(minecraft.player.getYRot());
        double fx=-Math.sin(a), fy=Math.cos(a), rx=Math.cos(a), ry=Math.sin(a);
        double tx=sx+fx*7, ty=sy+fy*7;
        double lx=sx-fx*5+rx*5, ly=sy-fy*5+ry*5;
        double rxp=sx-fx*5-rx*5, ryp=sy-fy*5-ry*5;
        fillTriangle(g,tx,ty,lx,ly,rxp,ryp,0xFFFFFFFF);
        double itx=sx+fx*4.6, ity=sy+fy*4.6;
        double ilx=sx-fx*2.3+rx*3, ily=sy-fy*2.3+ry*3;
        double irx=sx-fx*2.3-rx*3, iry=sy-fy*2.3-ry*3;
        fillTriangle(g,itx,ity,ilx,ily,irx,iry,CY);
    }
    /** When the player is panned off the current view, a small chevron pinned to the edge points
     *  back toward them, so a 20,000-block canvas never leaves you feeling lost. */
    private void drawOffscreenPlayerHint(GuiGraphicsExtractor g,double sx,double sy){
        int cx=(int)Math.max(8,Math.min(DESIGN_W-8,sx)), cy=(int)Math.max(8,Math.min(DESIGN_H-8,sy));
        double angle=Math.atan2(sy-cy,sx-cx);
        double dx=Math.cos(angle), dy=Math.sin(angle), px=-dy, py=dx;
        int tipX=(int)Math.round(cx+dx*6), tipY=(int)Math.round(cy+dy*6);
        int leftX=(int)Math.round(cx-dx*3+px*5), leftY=(int)Math.round(cy-dy*3+py*5);
        int rightX=(int)Math.round(cx-dx*3-px*5), rightY=(int)Math.round(cy-dy*3-py*5);
        line2(g,leftX,leftY,tipX,tipY,CY); line2(g,rightX,rightY,tipX,tipY,CY);
    }
    /** The eight-name palette shared with region colours (blank/unrecognised falls back to the accent). */
    private int waypointColor(String name){
        if(name==null) return CY;
        return switch(name){
            case "RED"->0xFFE05A5A; case "ORANGE"->0xFFE0965A; case "YELLOW"->0xFFE0D65A; case "LIME"->0xFF8CE05A;
            case "CYAN"->CY; case "BLUE"->0xFF5A8CE0; case "PURPLE"->PURPLE; case "PINK"->0xFFE05AC8;
            default->CY;
        };
    }
    private void drawPlansLayer(GuiGraphicsExtractor g){
        for(var v:OceanCanvasZoneClientCache.planningVectors()){
            if(!v.visible()||v.points()==null) continue;
            if(focusMode&&!focusIncludesPlan(v.id()))continue;
            boolean sel=!selectedPlan.isEmpty()&&selectedPlan.equals(v.parentId());
            if(!sel&&!planMatchesWorldMode(v.implemented()))continue;
            int col=OceanCanvasP3UXState.colorFor("plan",sel?0xFF1FC4EF:(v.implemented()?0xAA55C97A:0xCC1FC4EF));
            String[] pts=v.points().split(";"); Integer px=null,py=null; int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
            for(String q:pts){
                String[] a=q.trim().split(","); if(a.length<2) continue;
                try{
                    int x=mapScreenX(Double.parseDouble(a[0])),y=mapScreenY(Double.parseDouble(a[1]));
                    if(px!=null){line(g,px,py,x,y,col);if(OceanCanvasP3UXState.colorMode()!=OceanCanvasP3UXState.ColorMode.DEFAULT){int mx=(px+x)/2,my=(py+y)/2;fill(g,mx-2,my-2,4,4,col);}}
                    px=x;py=y;
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                }catch(NumberFormatException ignored){}
            }
            if(minX<=maxX&&programPlanLabelVisible(v)){
                String label=trim(v.name(),Math.max(64,Math.min(180,maxX-minX+80)));
                int lx=Math.max(4,Math.min(DESIGN_W-font.width(label)-6,minX+4));
                int ly=Math.max(4,Math.min(DESIGN_H-12,minY+4));
                fill(g,lx-3,ly-2,font.width(label)+6,12,0xB8000000);text(g,label,lx,ly,TEXT);
            }
            if(selectable("PLANS")&&minX<=maxX&&v.parentId()!=null&&!v.parentId().isBlank()){
                final String gid=v.parentId(),vid=v.id();
                hit(minX-4,minY-4,Math.max(8,maxX-minX+8),Math.max(8,maxY-minY+8),v.name()+" - select object",()->{
                    selectedPlan=gid; selectedPlanObject=vid; selectedPlanVertex=0; tab="PLANS"; disclosure="plan.details"; armed=null; cancelEdit(); say("Selected "+v.name());
                });
            }
            if(tab.equals("PLANS")&&mapSelectionActive()&&selectedPlanObject.equals(v.id())){
                List<Integer> edit=planningVertexList(v.points());
                for(int i=0;i+1<edit.size();i+=2){
                    int sx=mapScreenX(edit.get(i)),sy=mapScreenY(edit.get(i+1)),idx=i/2;
                    fill(g,sx-5,sy-5,10,10,idx==selectedPlanVertex?BRIGHT:CY);
                    final int vi=idx; final String vid=v.id(); final boolean closed=planningClosed(v.points()); final List<Integer> captured=edit;
                    hit(sx-7,sy-7,14,14,"Drag vertex "+(vi+1),()->{
                        selectedPlanObject=vid; selectedPlanVertex=vi; planDragIndex=vi; planEditVertices=new ArrayList<>(captured); planEditClosed=closed;
                    });
                }
            }
        }
    }
    /** P4 planning annotations and advisory analysis. Nothing here mutates terrain. */
    private void drawP4PlanningOverlay(GuiGraphicsExtractor g){
        for(var a:OceanCanvasZoneClientCache.p4Artifacts()){
            if(a.points()==null||a.points().isBlank())continue;int col=switch(a.kind()){
                case "NEGATIVE_SPACE"->0xCCB45CFF;case "TERRAIN_INTENT"->0xCCF2B84B;case "BIOME_TRANSITION"->0xCC55D6A8;
                case "ECOLOGICAL_CORRIDOR"->0xCC67C587;case "VIEW_CORRIDOR","LANDMARK_SIGHTLINE"->0xCCF4E18A;
                case "TERRAIN_STORY_BEAT"->0xCCE77DAF;case "SCALE_STAMP"->0xCC8FD8F0;default->0xAA9AA7B0;};
            Integer px=null,py=null;for(String raw:a.points().split(";")){String[] f=raw.split(",");if(f.length!=2)continue;try{int x=mapScreenX(Double.parseDouble(f[0])),y=mapScreenY(Double.parseDouble(f[1]));if(px!=null)line(g,px,py,x,y,col);else fill(g,x-3,y-3,6,6,col);px=x;py=y;}catch(NumberFormatException ignored){}}
        }
        if(p4Alternatives){String active=OceanCanvasZoneClientCache.activeScenario();for(var v:OceanCanvasZoneClientCache.planningVectors()){if(v.scenarioId()==null||v.scenarioId().isBlank())continue;int col=v.scenarioId().equals(active)?0xEE36D8FF:0x99E76DFF;Integer px=null,py=null;for(String raw:v.points().split(";")){String[] f=raw.split(",");if(f.length<2)continue;try{int x=mapScreenX(Double.parseDouble(f[0])),y=mapScreenY(Double.parseDouble(f[1]));if(px!=null)line(g,px,py,x,y,col);px=x;py=y;}catch(NumberFormatException ignored){}}}}
        if(!"NONE".equals(p4Overlay)){for(var f:OceanCanvasZoneClientCache.p4Findings()){if(!"ALL".equals(p4Overlay)&&!f.feature().equals(p4Overlay))continue;int x=mapScreenX(f.x()),y=mapScreenY(f.z());if(x<-20||y<-20||x>DESIGN_W+20||y>DESIGN_H+20)continue;int col=f.severity()>=70?0xCCFF5E67:f.severity()>=35?0xCCF2B84B:0xBB55C97A;int r=Math.max(3,Math.min(10,3+f.severity()/12));fill(g,x-r,y-r,r*2,r*2,col);if(mapSelectionActive())hit(x-r-2,y-r-2,r*2+4,r*2+4,title(f.feature())+": "+f.message(),()->say(f.message()));}}
    }

    /** Reference image layers render their registered footprint, at their own stored opacity. */
    private void drawReferenceImagesLayer(GuiGraphicsExtractor g){
        for(var r:OceanCanvasZoneClientCache.planningReferences()){
            if(!r.visible()) continue;
            drawReferenceImageSamples(g,r);
            double[] rb=referenceWorldBoundsForMap(r);
            int l=mapScreenX(rb[0]),t=mapScreenY(rb[1]),rr=mapScreenX(rb[2]),b=mapScreenY(rb[3]);
            int w=Math.max(1,rr-l),h=Math.max(1,b-t);
            if(rr<0||b<0||l>DESIGN_W||t>DESIGN_H) continue;
            int alpha=(int)Math.round(Math.max(0.05,Math.min(1.0,r.opacity()))*90)<<24;
            fill(g,l,t,w,h,alpha|0x00C86EE8);
            border(g,l,t,w,h,r.id().equals(selectedReference)?CY:0x99C86EE8);
            if(w>=48&&h>=14) text(g,trim(r.name(),w-8),l+4,t+4,r.id().equals(selectedReference)?CY:0xCCE0D0FF);
            if(selectable("PLANS")){final String rid=r.id(),name=r.name();hit(l,t,w,h,"Reference image: "+name,()->{selectedReference=rid;selectedPlanObject="";tab="PLANS";panelOpen=true;collapsed=false;disclosure="plan.references";armed=null;cancelEdit();say("Selected reference "+name);});}
        }
    }
    /** World footprint used by both the map reference raster and its selection border. */
    private double[] referenceWorldBoundsForMap(OceanCanvasZoneClientCache.PlanningReference ref){
        try{
            var reg=net.oceancanvas.mod.planning.OceanCanvasReferenceRegistration.parse(ref.registrationPoints());
            if(reg.size()>=2){var t=net.oceancanvas.mod.planning.OceanCanvasReferenceRegistration.solve(reg);var info=net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.assetInfo(ref.assetId());return net.oceancanvas.mod.planning.OceanCanvasReferenceRegistration.worldBounds(t,info.width(),info.height());}
        }catch(Exception ignored){}
        double minX=Math.min(ref.minX(),ref.maxX()),maxX=Math.max(ref.minX(),ref.maxX()),minZ=Math.min(ref.minZ(),ref.maxZ()),maxZ=Math.max(ref.minZ(),ref.maxZ());
        double cx=(minX+maxX)*.5,cz=(minZ+maxZ)*.5,w=maxX-minX,h=maxZ-minZ,r=Math.toRadians(ref.rotation()),c=Math.abs(Math.cos(r)),ss=Math.abs(Math.sin(r));
        double rw=w*c+h*ss,rh=h*c+w*ss;return new double[]{cx-rw*.5,cz-rh*.5,cx+rw*.5,cz+rh*.5};
    }
    /**
     * Draws the actual imported reference over the 2D map without inventing a second GPU texture API.
     * Sampling is bounded to roughly 2k cells/reference/frame and uses the same disposable rotated/
     * affine asset cache as the in-world Blueprint renderer. If an asset is unavailable, the existing
     * footprint/border still renders so the planning workflow fails soft rather than disappearing.
     */
    private void drawReferenceImageSamples(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.PlanningReference ref){
        if(ref==null||ref.assetId()==null||ref.assetId().isBlank()||!net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.exists(ref.assetId()))return;
        try{
            var reg=net.oceancanvas.mod.planning.OceanCanvasReferenceRegistration.parse(ref.registrationPoints());
            var asset=reg.size()>=2?net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.affineRegisteredAsset(ref.assetId(),ref.registrationPoints()):net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.rotatedAsset(ref.assetId(),ref.rotation());
            double[] wb=referenceWorldBoundsForMap(ref);int l=mapScreenX(wb[0]),t=mapScreenY(wb[1]),r=mapScreenX(wb[2]),b=mapScreenY(wb[3]);if(r<=0||b<=0||l>=DESIGN_W||t>=DESIGN_H)return;
            int sw=Math.max(1,r-l),sh=Math.max(1,b-t),area=Math.max(1,sw*sh);int step=Math.max(4,(int)Math.ceil(Math.sqrt(area/2048.0)));
            double displayPerSource=Math.max(sw/(double)Math.max(1,asset.width()),sh/(double)Math.max(1,asset.height()));int lod=0;while(lod+1<asset.levels()&&displayPerSource*Math.pow(2,lod)<0.8)lod++;
            int startX=Math.max(0,l),startY=Math.max(0,t),endX=Math.min(DESIGN_W,r),endY=Math.min(DESIGN_H,b);double opacity=Math.max(.05,Math.min(1,ref.opacity()));
            for(int sy=startY;sy<endY;sy+=step)for(int sx=startX;sx<endX;sx+=step){double u=(sx+step*.5-l)/(double)sw,v=(sy+step*.5-t)/(double)sh;if(u<0||u>=1||v<0||v>=1)continue;int argb=net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.sampleArgb(asset.cacheId(),lod,u,v);int a=(argb>>>24)&255;if(a<8)continue;a=(int)Math.round(a*opacity);fill(g,sx,sy,Math.min(step,endX-sx),Math.min(step,endY-sy),(argb&0x00FFFFFF)|(Math.max(0,Math.min(255,a))<<24));}
        }catch(Exception ignored){}
    }

    /** Spatial health evidence uses only server-authored chunk coordinates; aggregate diagnostics are never painted as fake map locations. */
    private void drawHealthFindingsLayer(GuiGraphicsExtractor g){
        String packed=OceanCanvasZoneClientCache.healthPacked();if(packed==null||packed.isBlank())return;
        boolean all=Boolean.TRUE.equals(layerOn.get("health")), light=Boolean.TRUE.equals(layerOn.get("health_light")), liquid=Boolean.TRUE.equals(layerOn.get("health_liquid"));
        for(String line:packed.split("\n")){
            String[] f=line.split("\t",-1);if(f.length<4)continue;
            int cx,cz,col;String label,detail="";
            try{
                if("F".equals(f[0])&&f.length>=8){cx=Integer.parseInt(f[1]);cz=Integer.parseInt(f[2]);label=prettyId(f[3]);detail=decodeB64(f[7]);col="STATE_MISMATCH".equals(f[3])?RED:GOLD;}
                else if("M".equals(f[0])&&f.length>=4){cx=Integer.parseInt(f[1]);cz=Integer.parseInt(f[2]);int ordinal=Integer.parseInt(f[3]);label="Physical Health";col=ordinal==0?GREEN:(ordinal==1?GOLD:DIM);}
                else continue;
            }catch(RuntimeException ex){continue;}
            String evidence=(label+" "+detail).toLowerCase(Locale.ROOT);boolean matches=all||(light&&(evidence.contains("light")||evidence.contains("sky")))||(liquid&&(evidence.contains("fluid")||evidence.contains("liquid")||evidence.contains("water")));
            if(!matches)continue;
            int l=mapScreenX(cx*16),t=mapScreenY(cz*16),r=mapScreenX(cx*16+16),b=mapScreenY(cz*16+16);int w=Math.max(2,r-l),h=Math.max(2,b-t);if(r<0||b<0||l>DESIGN_W||t>DESIGN_H)continue;
            fill(g,l,t,w,h,(col&0x00FFFFFF)|0x33000000);border(g,l,t,w,h,col);final String finding=cx+","+cz+" · "+label+(detail.isBlank()?"":" · "+detail);
            if(mapSelectionActive())hit(l,t,w,h,finding,()->{selectedHealthFinding=finding;opsOpen=true;opsTab="HEALTH";say("Health finding at chunk "+cx+", "+cz);});
        }
    }
    /** Queue footprints are resolved by Region name. If a Region no longer exists, the queue remains visible in Operations without inventing bounds. */
    private void drawQueuedPregenLayer(GuiGraphicsExtractor g){
        var q=OceanCanvasZoneClientCache.queue();if(q==null)return;
        for(var it:q.items()){
            OceanCanvasZoneSyncPayload.ZoneEntry zone=null;for(var z:zones())if(z.name().equalsIgnoreCase(it.region())){zone=z;break;}if(zone==null)continue;
            int col=queueStateColor(it.state());List<Integer> shape=cleanVertices(zone.shapeVertices());
            if(shape.size()>=6){Integer px=null,py=null;for(int i=0;i+1<shape.size();i+=2){int x=mapScreenX(shape.get(i)),y=mapScreenY(shape.get(i+1));if(px!=null)line(g,px,py,x,y,col);px=x;py=y;}if(px!=null)line(g,px,py,mapScreenX(shape.get(0)),mapScreenY(shape.get(1)),col);}
            else{int l=mapScreenX(zone.minX()),t=mapScreenY(zone.minZ()),r=mapScreenX(zone.maxX()),b=mapScreenY(zone.maxZ());border(g,l,t,Math.max(2,r-l),Math.max(2,b-t),col);}
            int cx=mapScreenX((zone.minX()+zone.maxX())/2.0),cy=mapScreenY((zone.minZ()+zone.maxZ())/2.0);if(cx>0&&cx<DESIGN_W&&cy>0&&cy<DESIGN_H)text(g,trim(it.region()+" · "+title(it.state()),150),cx+5,cy+5,col);
        }
    }

    /** Blueprint viewpoints are client-only overlay anchors; the map shows where each one sits. */
    private void drawBlueprintsLayer(GuiGraphicsExtractor g){
        var drawn=new ArrayList<int[]>();
        for(var v:OceanCanvasZoneClientCache.blueprintViewpoints()){
            int x=mapScreenX(v.x()),y=mapScreenY(v.z()); if(x<0||y<0||x>=DESIGN_W||y>=DESIGN_H) continue;
            if(declutterSkip(drawn,x,y)) continue;
            icon(g,"blueprint",x-8,y-8,0xFF8FD8F0);
            if(mapSelectionActive()) hit(x-8,y-8,16,16,"Blueprint viewpoint: "+v.name(),()->say("Blueprint viewpoint "+v.name()));
        }
    }
    /** Regions that paint a biome name their override, so the Biomes layer is real, not decorative. */
    private void drawBiomeOverrideLayer(GuiGraphicsExtractor g){
        for(var z:zones()){
            if(!z.hasBiomeOverride()) continue;
            int l=mapScreenX(z.minX()),t=mapScreenY(z.minZ()),r=mapScreenX(z.maxX()),b=mapScreenY(z.maxZ());
            if(r<0||b<0||l>DESIGN_W||t>DESIGN_H) continue;
            int w=Math.max(1,r-l),h=Math.max(1,b-t);
            fill(g,l,t,w,h,0x2200C08A);
            String label=prettyId(z.biomeOverride());
            if(w>=font.width(label)+12&&h>=26) text(g,label,l+4,t+16,0xCC7DE8C0);
        }
    }
    private void drawProjectsLayer(GuiGraphicsExtractor g){
        for(var p:OceanCanvasZoneClientCache.workspaceProjects()){
            if(focusMode&&!p.id().equals(focusedProjectId()))continue;
            if(p.regionName()==null||p.regionName().isBlank()) continue;
            for(var z:zones()) if(z.name().equalsIgnoreCase(p.regionName())){
                int x=mapScreenX((z.minX()+z.maxX())/2.0),y=mapScreenY((z.minZ()+z.maxZ())/2.0);
                if(x<-16||y<-16||x>DESIGN_W+16||y>DESIGN_H+16) break;
                icon(g,"project",x-8,y-8,OceanCanvasP3UXState.colorFor("project",GREEN));
                if(!selectable("PROJECTS")) break;
                final String pid=p.id();
                hit(x-9,y-9,18,18,p.name()+" - click to select this project",()->{
                    selectedProject=pid; tab="PROJECTS"; disclosure=null; armed=null; cancelEdit(); say("Selected project");
                });
                break;
            }
        }
    }
    private void drawRubberBand(GuiGraphicsExtractor g){
        int l=mapScreenX(Math.min(drawAX,drawBX)),t=mapScreenY(Math.min(drawAZ,drawBZ));
        int r=mapScreenX(Math.max(drawAX,drawBX)),b=mapScreenY(Math.max(drawAZ,drawBZ));
        int w=Math.max(1,r-l),h=Math.max(1,b-t);
        fill(g,l,t,w,h,0x221FC4EF); border(g,l,t,w,h,CY);
        String s=Math.abs((int)(drawBX-drawAX))+" x "+Math.abs((int)(drawBZ-drawAZ))+" blocks";
        text(g,s,l+4,Math.max(4,t-12),CY);
    }
    private boolean needsVisibilityRecovery(){
        return !"panels".equals(overlay) && !Boolean.TRUE.equals(panelVisible.get("tools"));
    }
    private void drawVisibilityRecovery(GuiGraphicsExtractor g){
        int x=DESIGN_W-62,y=66; fill(g,x,y,44,44,0xF0000000); border(g,x,y,44,44,CY);
        icon(g,"toggle",x+14,y+14,CY);
        hit(x,y,44,44,"Panel visibility",()->overlay="panels");
    }
    private void drawPanelVisibility(GuiGraphicsExtractor g){
        int x=128,y=162,w=220,h=246;fill(g,x,y,w,h,0xF0000000);border(g,x,y,w,h,CY);
        blocker(x,y,w,h);
        text(g,"PANEL VISIBILITY",x+13,y+13,CY);
        icon(g,"close",x+w-28,y+10,DIM); hit(x+w-34,y+4,30,30,"Close",()->overlay=null);
        String[][] rows={{"header","Header"},{"navigation","Top Navigation"},{"tools","Tool Rail"},{"right","Right Panel"},{"coords","Coordinates"},{"compass","Compass"},{"operation","Operation Status"}};
        int yy=y+38;
        for(String[] r:rows){
            hline(g,x+12,yy-5,w-24); text(g,r[1],x+14,yy+4,TEXT); check(g,x+w-31,yy+1,Boolean.TRUE.equals(panelVisible.get(r[0])));
            final String k=r[0],label=r[1];
            hit(x+12,yy-4,w-24,26,(Boolean.TRUE.equals(panelVisible.get(k))?"Hide ":"Show ")+label,()->{
                panelVisible.put(k,!Boolean.TRUE.equals(panelVisible.get(k)));
                say(label+(Boolean.TRUE.equals(panelVisible.get(k))?" shown":" hidden"));
            });
            yy+=28;
        }
        text(g,"Close with X or Esc",x+14,y+h-19,DIM);
    }

    // ---------------------------------------------------------------- header / chrome
    private void drawHeader(GuiGraphicsExtractor g){
        fill(g,18,14,424,106,PANEL);border(g,18,14,424,106,TAB_BORDER);
        blocker(18,14,424,106);
        border(g,32,26,38,38,CY); fill(g,36,30,13,13,0xFF1AA8CD);fill(g,52,30,13,13,0xFF0D5A70);fill(g,36,46,13,13,0xFF0D5A70);fill(g,52,46,13,13,0xFF1AA8CD);
        text(g,"OCEAN CANVAS",82,29,0xFFF2F2F2);
        int size=canvasSize();
        text(g,String.format(Locale.US,"%,d × %,d Canvas",size,size),82,50,0xFF8D9296);

        int regions=zones().size(), plans=OceanCanvasZoneClientCache.planGroups().size(), projects=OceanCanvasZoneClientCache.workspaceProjects().size();
        icon(g,"region",31,73,CY); text(g,regions+(regions==1?" region":" regions"),51,77,0xFF8D9296);
        hit(28,70,92,22,"Go to Regions",()->{tab="REGIONS";overlay=null;});
        icon(g,"plan",128,73,CY); text(g,plans+(plans==1?" plan":" plans"),148,77,0xFF8D9296);
        hit(125,70,92,22,"Go to Plans",()->{tab="PLANS";overlay=null;});
        icon(g,"project",221,73,CY); text(g,projects+(projects==1?" project":" projects"),241,77,0xFF8D9296);
        hit(218,70,100,22,"Go to Projects",()->{tab="PROJECTS";overlay=null;});
        int attention=OceanCanvasP3UXState.attentionCount();
        text(g,"ACTIVITY"+(attention>0?" "+attention:""),330,77,attention>0?GOLD:DIM);
        hit(324,70,104,22,"Activity center — jobs, completions, warnings and export results",()->overlay="activity".equals(overlay)?null:"activity");

        String line;
        if(tab.equals("PLANS")){ var p=plan(); line=p==null?"No plan layer yet":"Active Plan: "+p.name(); }
        else { var p=project(); line=p==null?"No project yet":"Active Project: "+p.name(); }
        text(g,trim(line,396),32,98,CY);
    }
    /** Exact CSS-derived tab widths at the 1440px reference viewport.
     *  Do NOT derive these from Minecraft font metrics: the HTML uses Helvetica/Arial
     *  with 1.3px tracking on the labels, so font.width() shifts the entire toolbar. */
    private int tabWidth(String t){return switch(t){case "REGIONS"->118;case "PLANS"->99;case "PROJECTS"->128;default->108;};}
    private int tabGroupWidth(){int tabs=0;for(String t:TABS)tabs+=tabWidth(t);return tabs-(TABS.length-1);}
    private int topNavX(){return (DESIGN_W-tabGroupWidth())/2;}
    private int topControlsX(){return DESIGN_W-18-(44*3+9*2);}
    private void drawTabs(GuiGraphicsExtractor g){
        int x=topNavX(), activeX=-1,activeW=0;
        for(String t:TABS){
            int w=tabWidth(t);boolean on=t.equals(tab);
            fill(g,x,14,w,44,on?0x171FC4EF:BLACK);border(g,x,14,w,44,TAB_BORDER);
            int ix=x+18,iy=28;icon(g,tabIcon(t),ix,iy,on?CY:0xFFC4C9CC);text(g,t,ix+25,31,on?CY:0xFFC4C9CC);
            if(on){activeX=x;activeW=w;}
            final String target=t;
            hit(x,14,w,44,tabTip(t),()->{tab=target;overlay=null;collapsed=false;disclosure=null;listPage=0;cancelEdit();});
            x+=w-1;
        }
        int gear=topControlsX();
        drawSquareIcon(g,gear,14,"gear",0xFFCFCFCF,false);
        hit(gear,14,44,44,"Ocean Canvas settings",()->{ if(minecraft!=null) minecraft.gui.setScreen(new OceanCanvasSettingsScreen(this)); });
        drawSquareText(g,gear+53,14,"?","help".equals(overlay)?CY:0xFFCFCFCF,"help".equals(overlay));
        hit(gear+53,14,44,44,"Keyboard shortcuts",()->overlay="help".equals(overlay)?null:"help");
        drawSquareIcon(g,gear+106,14,"close",0xFFCFCFCF,false);
        hit(gear+106,14,44,44,"Minimize the interface",()->{uiMinimized=true;overlay=null;});
        if(activeX>=0)border(g,activeX,14,activeW,44,CY); // selected tab always wins all four shared edges
    }
    // v253.68.2: the reference's REGIONS tab icon is a plain dashed square (matching the toolbar's
    // own "shape" glyph), not the crosshair-in-a-box the "region" icon key draws elsewhere for the
    // map hover badge and the header's region count - those two spots keep "region" unchanged.
    private String tabIcon(String t){return switch(t){case "REGIONS"->"shape";case "PLANS"->"plan";case "PROJECTS"->"project";default->"layers";};}
    private String tabTip(String t){return switch(t){
        case "REGIONS"->"Regions - canvas areas and their operations";
        case "PLANS"->"Plans - planning layers drawn over the world";
        case "PROJECTS"->"Projects - phases, milestones and tasks";
        default->"Layers - what the map draws"; };}
    private void drawSquareIcon(GuiGraphicsExtractor g,int x,int y,String k,int c,boolean on){fill(g,x,y,44,44,on?0x171FC4EF:BLACK);border(g,x,y,44,44,on?CY:TAB_BORDER);icon(g,k,x+14,y+14,c);}
    private void drawSquareText(GuiGraphicsExtractor g,int x,int y,String s,int c,boolean on){fill(g,x,y,44,44,on?0x171FC4EF:BLACK);border(g,x,y,44,44,on?CY:TAB_BORDER);center(g,s,x,y+18,44,c);}
    private int tabIndex(){for(int i=0;i<TABS.length;i++)if(TABS[i].equals(tab))return i;return 3;}

    private void drawToolRail(GuiGraphicsExtractor g){
        String[][] ts=TOOLS[tabIndex()];
        boolean icons=tab.equals("REGIONS");
        int x=18,y=162,w=icons?62:96,h=icons?46:54;
        fill(g,x,y,w,h*ts.length,BLACK);
        int activeY=-1;
        for(int i=0;i<ts.length;i++){
            int yy=y+i*h;boolean on=activeTool.get(tab).equals(ts[i][0]);
            fill(g,x,yy,w,h,on?0x171FC4EF:BLACK);border(g,x,yy,w,h,BORDER);
            if(icons){icon(g,ts[i][1],x+w/2-8,yy+15,on?CY:0xFFC4C9CC);}
            else{icon(g,ts[i][1],x+w/2-8,yy+7,on?CY:0xFFC4C9CC);center(g,ts[i][0],x,yy+31,w,on?CY:0xFFC4C9CC);}
            var maturity=OceanCanvasP3UXState.maturityFor(ts[i][0]);
            text(g,OceanCanvasP3UXState.maturityShort(maturity),x+w-10,yy+4,maturity==OceanCanvasP3UXState.Maturity.STABLE?DIM:GOLD);
            if(on)activeY=yy;
            final String toolName=ts[i][0];
            hit(x,yy,w,h,toolTip(toolName)+" · "+maturity.name().replace('_',' '),()->selectTool(toolName));
        }
        if(activeY>=0)border(g,x,activeY,w,h,CY); // active tool owns a complete four-edge outline
    }
    private String toolTip(String t){
        return switch(t){
            case "Select" -> "Select (V) - click a region, plan or project on the map";
            case "Pan" -> "Pan (Space) - drag the world map";
            case "Shape" -> tab.equals("REGIONS")
                    ? "Shape - click polygon vertices; double-click or Enter to create the region"
                    : "Shape - click polygon vertices; double-click or Enter to create a planning shape";
            case "Vertex" -> "Vertex - click a selected region edge to insert a vertex; drag existing vertices to refine it";
            case "Path" -> "Path - click path vertices; double-click or Enter to finish";
            case "Add Phase" -> "Add Phase - append a phase to the selected project";
            case "Toggle" -> "Toggle (L) - panel visibility";
            case "Waypoint" -> "Waypoint - place an atlas marker at the next map click";
            default -> t;
        };
    }
    /** Tools that act immediately do so here; gesture tools clear any stale half-finished capture. */
    private void selectTool(String t){
        if(!Objects.equals(activeTool.get(tab),t)) cancelGesture();
        activeTool.put(tab,t);
        cancelEdit();
        switch(t){
            case "Toggle" -> overlay="panels".equals(overlay)?null:"panels";
            case "Shape" -> say(tab.equals("REGIONS")
                    ? "Click polygon vertices; double-click or press Enter to create the region"
                    : "Click polygon vertices; double-click or press Enter to finish the shape");
            case "Vertex" -> {
                var z=region();
                if(z==null){say("Select a region first");return;}
                say("Click an edge to insert a vertex, or drag an existing vertex");
            }
            case "Path" -> say("Click path vertices; double-click or press Enter to finish");
            case "Add Phase" -> {
                var p=project(); if(p==null){say("Select a project first");return;}
                beginEdit("project.phase_add","New phase");
            }
            case "Waypoint" -> say("Click the map to place a waypoint");
            default -> say(t+" tool active");
        }
    }

    private void drawCompass(GuiGraphicsExtractor g){
        int x=panelOpen?DESIGN_W-396-58:DESIGN_W-46-58,y=104;
        fill(g,x,y,58,58,BLACK);border(g,x,y,58,58,0xFF33383B);center(g,"N",x,y-14,58,0xFFB9BEC2);
        for(int i=0;i<10;i++)fill(g,x+29-i/2,y+12+i,1+i,1,0xFFD94B4B);
        for(int i=0;i<10;i++)fill(g,x+24+i/2,y+36-i,10-i,1,0xFFC9CED2);
        hit(x,y-16,58,74,"Recentre on the player",()->{
            if(minecraft!=null&&minecraft.player!=null){
                mapViewX=minecraft.player.getX(); mapViewZ=minecraft.player.getZ(); say("Recentred on player");
            }
        });
    }
    private void drawCoords(GuiGraphicsExtractor g){
        // Keep the complete two-row coordinates card above the permanent bottom toolbar.
        int y=DESIGN_H-34-59-36;
        fill(g,18,y,300,59,BLACK);border(g,18,y,300,59,BORDER);
        blocker(18,y,300,59);
        int ox=contextOriginX(),oz=contextOriginZ();
        text(g,"X:",31,y+12,0xFF7F8489);text(g,trim(OceanCanvasP3UXState.formatCoordinate(coordX,ox),53),49,y+12,0xFFE4E4E4);
        text(g,"Z:",108,y+12,0xFF7F8489);text(g,trim(OceanCanvasP3UXState.formatCoordinate(coordZ,oz),54),126,y+12,0xFFE4E4E4);
        text(g,"Y:",186,y+12,0xFF7F8489);text(g,trim(surfaceLabel(),110),204,y+12,0xFFE4E4E4);
        text(g,"Biome:",31,y+34,0xFF7F8489);text(g,trim(biomeLabel(),110),75,y+34,0xFFE4E4E4);
        text(g,"Light:",190,y+34,0xFF7F8489);text(g,skyLightLabel(),228,y+34,0xFFE4E4E4);
    }
    /** Real surface height under the cursor, or an honest dash when that chunk is not loaded. */
    private String surfaceLabel(){
        if(minecraft==null||minecraft.level==null) return "—";
        int y=mapTerrain.surfaceYAt(minecraft.level,coordX,coordZ);
        if(y==Integer.MIN_VALUE) return "—";
        return y+(y<=62?" (Ocean)":" (Land)");
    }
    private String biomeLabel(){
        if(minecraft==null||minecraft.level==null) return "—";
        if(!minecraft.level.hasChunk(coordX>>4,coordZ>>4)) return "—";
        int y=mapTerrain.surfaceYAt(minecraft.level,coordX,coordZ);
        if(y==Integer.MIN_VALUE) y=63;
        var holder=minecraft.level.getBiome(new BlockPos(coordX,y,coordZ));
        return holder.unwrapKey().map(k->prettyId(k.identifier().getPath())).orElse("—");
    }
    private String skyLightLabel(){
        if(minecraft==null||minecraft.level==null) return "—";
        if(!minecraft.level.hasChunk(coordX>>4,coordZ>>4)) return "—";
        int y=mapTerrain.surfaceYAt(minecraft.level,coordX,coordZ);
        if(y==Integer.MIN_VALUE) return "—";
        return String.valueOf(minecraft.level.getBrightness(LightLayer.SKY,new BlockPos(coordX,y,coordZ)));
    }

    // ---------------------------------------------------------------- right panel shell
    private static final int PANEL_X=1074, PANEL_W=348, CONTENT_X=1088, CONTENT_W=320, CONTENT_RIGHT=1408;
    private static final int PANEL_TOP=72, PANEL_BOTTOM_MAX=DESIGN_H-34;
    /** Rows shown at once in a project phase/milestone list before it pages. */
    private static final int PROJECT_LIST_WINDOW=6;

    private void panelBase(GuiGraphicsExtractor g,int h){
        fill(g,R(PANEL_X),PANEL_TOP,PANEL_W,h,BLACK);border(g,R(PANEL_X),PANEL_TOP,PANEL_W,h,BORDER);
        blocker(R(PANEL_X),PANEL_TOP,PANEL_W,h);
    }
    /**
     * Lays the active panel out twice: once measuring, to learn where its content really ends,
     * then paints a backing sized to that before laying it out for real. Panel heights are
     * therefore derived from content rather than guessed, and content can no longer spill past
     * its own background when a disclosure or a longer list opens.
     */
    private void drawRightPanel(GuiGraphicsExtractor g){
        measuring=true;
        int contentEnd=layoutRightPanel(g);
        measuring=false;
        int h=Math.max(120,Math.min(contentEnd+20-PANEL_TOP,PANEL_BOTTOM_MAX-PANEL_TOP));
        panelBase(g,h);
        layoutRightPanel(g);
    }
    /** Returns the y at which the active panel's content ends. */
    private int layoutRightPanel(GuiGraphicsExtractor g){
        return switch(tab){
            case "LAYERS" -> drawLayers(g);
            case "PLANS" -> drawPlans(g);
            case "PROJECTS" -> drawProjects(g);
            default -> drawRegions(g);
        };
    }
    private void heading(GuiGraphicsExtractor g,String s){text(g,s,R(CONTENT_X),87,CY);}
    private void collapseControl(GuiGraphicsExtractor g){
        text(g,collapsed?"⌄":"⌃",R(1394),87,0xFF9AA0A4);
        hit(R(1380),80,30,30,collapsed?"Expand panel":"Collapse panel",()->collapsed=!collapsed);
    }
    private void check(GuiGraphicsExtractor g,int x,int y,boolean on){
        if(on){fill(g,x,y,14,14,CY);icon(g,"check",x-1,y-1,BLACK);} else border(g,x,y,14,14,0xFF3A3F42);
    }
    private void pair(GuiGraphicsExtractor g,String a,String b,int y){
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,a,R(CONTENT_X),y+10,DIM);
        right(g,trim(b,CONTENT_W-font.width(a)-16),R(CONTENT_RIGHT),y+10,0xFFE2E2E2);
    }
    /** A disclosure row that opens an inline section. Always interactive - never a dead chevron. */
    private void disclosureRow(GuiGraphicsExtractor g,String label,String value,int y,String key,String tip){
        boolean open=key.equals(disclosure);
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,label,R(CONTENT_X),y+11,TEXT);
        if(value!=null&&!value.isBlank()) right(g,trim(value,150),R(1392),y+11,DIM);
        text(g,open?"⌄":"›",R(1397),y+11,0xFF7F8489);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,tip,()->{disclosure=open?null:key;listPage=0;});
    }
    private void actionRow(GuiGraphicsExtractor g,int x,int y,int w,int h,String glyphLabel,int accent,String tip,Runnable action){
        button(g,x,y,w,h,glyphLabel,accent);
        hit(x,y,w,h,tip,action);
    }
    /** Compact action used inside contextual disclosures; visually identical to the main action rows. */
    private void miniAction(GuiGraphicsExtractor g,int x,int y,int w,String label,int accent,String tip,Runnable action){
        actionRow(g,x,y,w,24,label,accent,tip,action);
    }
    private void button(GuiGraphicsExtractor g,int x,int y,int w,int h,String s,int accent){
        fill(g,x,y,w,h,DARK);border(g,x,y,w,h,accent==CY?0xFF232323:accent);
        String k=null,label=s;
        if(s.startsWith("▶")){k="play";label=s.substring(1).trim();}
        else if(s.startsWith("⏸")){k="pause";label=s.substring(1).trim();}
        else if(s.startsWith("✕")){k="close";label=s.substring(1).trim();}
        else if(s.startsWith("☰")){k="queue";label=s.substring(1).trim();}
        else if(s.startsWith("↻")){k="rewipe";label=s.substring(1).trim();}
        else if(s.startsWith("↩")){k="restore";label=s.substring(1).trim();}
        else if(s.startsWith("⊕")){k="plus";label=s.substring(1).trim();}
        else if(s.startsWith("⚙")){k="gear";label=s.substring(1).trim();}
        else if(s.startsWith("☢")){k="trash";label=s.substring(1).trim();}
        else if(s.startsWith("→")){k="teleport";label=s.substring(1).trim();}
        else if(s.startsWith("☲")){k="waypoint";label=s.substring(1).trim();}
        if(k!=null){icon(g,k,x+10,y+(h-16)/2,accent);text(g,trim(label,w-46),x+34,y+(h-8)/2,accent==CY?TEXT:accent);}
        else text(g,trim(label,w-22),x+11,y+(h-8)/2,accent==CY?TEXT:accent);
    }
    private String fmt(int n){return String.format(Locale.US,"%,d",n);}
    private String fmt(long n){return String.format(Locale.US,"%,d",n);}
    private void emptyState(GuiGraphicsExtractor g,String title,String hintA,String hintB){
        text(g,title,R(CONTENT_X),126,0xFFE2E2E2);
        text(g,trim(hintA,CONTENT_W),R(CONTENT_X),148,DIM);
        if(hintB!=null) text(g,trim(hintB,CONTENT_W),R(CONTENT_X),164,DIM);
    }

    // ---------------------------------------------------------------- LAYERS panel
    private int drawLayers(GuiGraphicsExtractor g){
        if(!selectedWaypoint.isEmpty() && waypoint()!=null) return drawSelectedWaypoint(g);
        heading(g,"LAYER MANAGER");
        int y=116;
        for(int i=0;i<LAYERS.length;i++){
            String[] r=LAYERS[i];
            if(i==6&&!structOpen){i=8;continue;}
            boolean sub=i>=6&&i<=8;
            int ix=R(1090)+(sub?20:0);
            icon(g,r[1],ix,y+2,sub?0xFFC9C9C9:TEXT);
            text(g,r[2],ix+27,y+5,sub?0xFFC9C9C9:TEXT);
            check(g,R(1391),y+7,Boolean.TRUE.equals(layerOn.get(r[0])));
            final String key=r[0],label=r[2];
            if(i==5){
                hit(R(CONTENT_X),y,45,29,structOpen?"Collapse structure layers":"Expand structure layers",()->structOpen=!structOpen);
            }
            hit(R(1133),y,275,29,(Boolean.TRUE.equals(layerOn.get(key))?"Hide ":"Show ")+label+" on the map",()->toggleLayer(key,label));
            y+=29;
        }
        var waypoints=OceanCanvasZoneClientCache.atlasFeatures();
        disclosureRow(g,"Waypoints",waypoints.isEmpty()?"None":String.valueOf(waypoints.size()),y,"layers.waypoints",
                "Every placed waypoint, nearest first, with its distance from you");
        y+=35;
        if("layers.waypoints".equals(disclosure)){
            actionRow(g,R(1098),y,CONTENT_W-10,26,"⊕   Add Waypoint at Player",CY,"Drop a waypoint at your current position",()->{
                if(minecraft==null||minecraft.player==null){say("No player in this world");return;}
                workspace("atlas_add","",nextWaypointName(),(int)Math.round(minecraft.player.getX())+","+(int)Math.round(minecraft.player.getZ()));
                say("Waypoint placed at your position");
            });
            y+=32;
            if(waypoints.isEmpty()){
                text(g,"No waypoints yet — pick Waypoint in the tool rail, then click the map.",R(1098),y+10,DIM); y+=24;
            }else{
                double px=minecraft!=null&&minecraft.player!=null?minecraft.player.getX():mapViewX;
                double pz=minecraft!=null&&minecraft.player!=null?minecraft.player.getZ():mapViewZ;
                var sorted=new ArrayList<>(waypoints);
                sorted.sort(Comparator.comparingDouble(w->Math.hypot(w.x()-px,w.z()-pz)));
                for(var w:sorted){
                    hline(g,R(1098),y,CONTENT_W-10);
                    icon(g,"waypoint",R(1098),y+4,waypointColor(w.color()));
                    text(g,trim(w.name(),150),R(1120),y+10,0xFFC9C9C9);
                    int dist=(int)Math.round(Math.hypot(w.x()-px,w.z()-pz));
                    right(g,fmt(dist)+"m "+bearingLabel(w.x()-px,w.z()-pz),R(CONTENT_RIGHT),y+10,DIM);
                    final String fid=w.id();
                    hit(R(1098),y+2,CONTENT_W-10,24,"Select "+w.name(),()->{selectedWaypoint=fid;disclosure=null;armed=null;cancelEdit();say("Selected "+w.name());});
                    y+=26;
                }
            }
            y+=6;
        }
        hline(g,R(CONTENT_X),y+5,CONTENT_W);
        text(g,"World Map Mode",R(CONTENT_X),y+20,TEXT);right(g,title(worldMapMode()),R(1388),y+20,CY);text(g,"›",R(1397),y+20,0xFF7F8489);
        final int modeY=y+11;hit(R(CONTENT_X),modeY,CONTENT_W,26,"Filter the atlas by planned, partial, abandoned, restored or complete world state",this::cycleWorldMapMode);
        y+=31;
        hline(g,R(CONTENT_X),y+5,CONTENT_W);
        text(g,"Layer Settings",R(CONTENT_X),y+20,TEXT);
        text(g,"›",R(1397),y+20,0xFF7F8489);
        final int rowY=y+11;
        hit(R(CONTENT_X),rowY,CONTENT_W,26,"Open Ocean Canvas settings",()->{ if(minecraft!=null) minecraft.gui.setScreen(new OceanCanvasSettingsScreen(this)); });
        hline(g,R(CONTENT_X),rowY+30,CONTENT_W);
        text(g,"Stewardship Workbench",R(CONTENT_X),rowY+45,TEXT);text(g,"›",R(1397),rowY+45,CY);
        hit(R(CONTENT_X),rowY+34,CONTENT_W,27,"Recovery, planning library, forever-world and knowledge tools",()->openWorkbench("RECOVERY"));
        return rowY+65;
    }
    /** 8-point compass bearing from a delta in world blocks. North is -Z, east is +X, matching
     *  the player marker's own yaw convention below. */
    private String bearingLabel(double dx,double dz){
        double deg=Math.toDegrees(Math.atan2(dx,-dz)); if(deg<0) deg+=360;
        String[] pts={"N","NE","E","SE","S","SW","W","NW"};
        return pts[(int)Math.round(deg/45.0)%8];
    }
    // ---------------------------------------------------------------- SELECTED WAYPOINT panel
    private int drawSelectedWaypoint(GuiGraphicsExtractor g){
        var w=waypoint();
        heading(g,"SELECTED WAYPOINT");
        text(g,"‹ Layer Manager",R(1290),87,CY);
        hit(R(1250),80,146,20,"Back to the Layer Manager",()->{selectedWaypoint="";disclosure=null;cancelEdit();});
        int y=117;
        if("waypoint.name".equals(editKey)){
            drawEditBox(g,R(CONTENT_X),y-6,236,22);
        }else{
            text(g,trim(w.name(),160),R(CONTENT_X),y,BRIGHT);
            icon(g,"edit",R(1255),y-3,CY);
            hit(R(1249),y-8,28,26,"Rename this waypoint",()->beginEdit("waypoint.name",w.name()));
        }
        y+=28;
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Position (Blocks)",R(CONTENT_X),y,DIM);
        y+=22;
        coordBox(g,"X",w.x(),R(CONTENT_X),y,"waypoint.x"); coordBox(g,"Z",w.z(),R(1249),y,"waypoint.z");
        y+=44;
        if(minecraft!=null&&minecraft.player!=null){
            hline(g,R(CONTENT_X),y,CONTENT_W);
            double dist=Math.hypot(w.x()-minecraft.player.getX(),w.z()-minecraft.player.getZ());
            text(g,"Distance",R(CONTENT_X),y+10,DIM);
            right(g,fmt((int)Math.round(dist))+"m "+bearingLabel(w.x()-minecraft.player.getX(),w.z()-minecraft.player.getZ()),R(CONTENT_RIGHT),y+10,0xFFE2E2E2);
            y+=27;
        }
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Colour",R(CONTENT_X),y+10,DIM);
        String curColor=w.color()==null||w.color().isBlank()?"Default":title(w.color());
        right(g,curColor,R(1388),y+10,waypointColor(w.color()));
        text(g,"›",R(1397),y+10,0xFF7F8489);
        final String wid=w.id();
        hit(R(CONTENT_X),y+2,CONTENT_W,26,"Cycle this waypoint's map colour",()->{
            String[] palette={"","RED","ORANGE","YELLOW","LIME","CYAN","BLUE","PURPLE","PINK"};
            String cur=w.color()==null?"":w.color();
            int idx=0; for(int i=0;i<palette.length;i++) if(palette[i].equalsIgnoreCase(cur)) idx=i;
            String next=palette[(idx+1)%palette.length];
            workspace("atlas_color",wid,next,"");
            say(next.isEmpty()?"Waypoint colour reset to default":"Waypoint colour → "+title(next));
        });
        y+=35;
        boolean delArmed=isArmed("delete:waypoint:"+wid);
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,delArmed?"☢   Confirm Delete":"☢   Delete Waypoint",delArmed?RED:GOLD,
                "Remove this waypoint",()->{
                    if(confirm("delete:waypoint:"+wid,"Click Delete again to confirm")){
                        workspace("atlas_delete",wid,"","");
                        selectedWaypoint=""; say("Deleted waypoint");
                    }
                });
        y+=41;
        return y;
    }
    private void toggleLayer(String k,String label){
        boolean nv=!Boolean.TRUE.equals(layerOn.get(k));
        if(k.equals("structures")){
            layerOn.put(k,nv);layerOn.put("shipwrecks",nv);layerOn.put("monuments",nv);layerOn.put("portals",nv);
        }else{
            layerOn.put(k,nv);
            if((k.equals("shipwrecks")||k.equals("monuments")||k.equals("portals"))&&nv) layerOn.put("structures",true);
        }
        if(nv&&k.startsWith("health")&&(OceanCanvasZoneClientCache.healthPacked()==null||OceanCanvasZoneClientCache.healthPacked().isBlank())){send(new OceanCanvasHealthRequestPayload("scan"));say(label+" enabled — requesting Health evidence…");return;}
        if(nv&&(k.equals("health_light")||k.equals("health_liquid")))say(label+" enabled — only server evidence with spatial chunk coordinates is painted; aggregate counters remain in Operations → Health");
        else say(label+(nv?" shown":" hidden"));
    }

    // ---------------------------------------------------------------- REGIONS panel
    private int drawRegions(GuiGraphicsExtractor g){
        var z=region();
        if(z==null){
            heading(g,"SELECTED REGION");
            if(selectedRegion!=null&&!selectedRegion.isBlank()){
                emptyState(g,"Creating "+selectedRegion,"Waiting for the server to confirm the new draft Region",
                        "and its exact geometry. It will remain selected when ready.");
            }else{
                emptyState(g,zones().isEmpty()?"No regions yet":"No region selected",
                        "Press R, or pick Shape in the tool rail,","click polygon vertices, then press Enter or double-click to finish.");
            }
            actionRow(g,R(CONTENT_X),200,CONTENT_W,32,"⊕   New Region at View Centre",CY,
                    "Create a 1,024 x 1,024 draft region at the centre of the current view",this::createRegionAtViewCentre);
            return 232;
        }
        heading(g,"SELECTED REGION");
        collapseControl(g);
        if(collapsed) return 104;

        var activeJob=OceanCanvasZoneClientCache.job();
        boolean running=activeJob!=null;
        boolean activeRegionOp=activeJob!=null&&activeJob.scopeName()!=null&&activeJob.scopeName().equalsIgnoreCase(z.name());
        boolean activePregenHere=activeRegionOp&&activeJob.kind()!=null&&activeJob.kind().toLowerCase(Locale.ROOT).contains("pregen");
        var opCompat=OceanCanvasZoneClientCache.recoveryCompatibility();
        final String opLock = opCompat.readOnly()
                ? (opCompat.reason().isBlank()?"This save is read-only for Ocean Canvas":opCompat.reason())
                : null;

        int y=117;
        // Name + rename
        if("region.name".equals(editKey)){
            drawEditBox(g,R(CONTENT_X),y-6,236,22);
        }else{
            text(g,trim(z.name(),160),R(CONTENT_X),y,BRIGHT);
            icon(g,"edit",R(1255),y-3,CY);
            hit(R(1249),y-8,28,26,"Rename this region",()->beginEdit("region.name",z.name()));
        }
        // Region chooser when there is more than one
        if(zones().size()>1){
            text(g,"▾",R(1290),y,DIM);
            hit(R(1282),y-8,28,26,"Switch region ("+zones().size()+" total)",this::cycleRegion);
        }
        y+=28;

        // Protection + shape
        hline(g,R(CONTENT_X),y,CONTENT_W);
        icon(g,"shield",R(CONTENT_X),y+7,z.enabled()?CY:DIM);
        text(g,z.enabled()?"PROTECTED":"DRAFT",R(1111),y+11,z.enabled()?CY:DIM);
        hit(R(CONTENT_X),y+2,150,26,activeRegionOp?regionOperationLockTip(z):(z.enabled()?"Protected regions stay protected; use Rewipe or Restore for terrain lifecycle changes":"Protect this draft without running Pregen"),
                ()->{
                    if(activeRegionOp){say(regionOperationLockTip(z));return;}
                    if(z.enabled()){say("Already protected — use Rewipe or Restore instead of unprotecting");return;}
                    send(new OceanCanvasZoneSetEnabledRequestPayload(z.name(),true));say("Protecting draft "+z.name()+" without Pregen");
                });
        String shapeLabel=z.shapeVertices()!=null&&!z.shapeVertices().isEmpty()?"Polygon ("+(z.shapeVertices().size()/2)+")"
                :(z.maxX()-z.minX())==(z.maxZ()-z.minZ())?"Square":"Rectangle";
        icon(g,"shape",R(1308),y+7,0xFFCFCFCF);
        text(g,shapeLabel,R(1330),y+11,0xFFCFCFCF);
        hit(R(1300),y+2,108,26,"Region footprint shape, derived from its real geometry",()->say("Shape: "+shapeLabel));
        y+=42;

        // v253.69.7 primary Region action: Pregen belongs with the selected Region identity,
        // not below disclosures where it can fall off the visible right panel. The server-authored
        // preview/confirm contract remains unchanged. Protected Regions are explicitly skipped.
        boolean topPregenReady=operationPreviewToken("PREGEN",z.name(),"")!=null;
        String topPregenLabel=activePregenHere?"⏸   Pregen Running · Auto-Protect":(z.enabled()?"✓   Pregen Complete · Protected":(topPregenReady?"▶   Confirm Pregen & Protect":"▶   Pregen & Protect Region"));
        int topPregenAccent=activePregenHere?GOLD:(z.enabled()?GREEN:(opLock!=null||running?DIM:(topPregenReady?GREEN:CY)));
        String topPregenTip=activePregenHere?"This Region is being pregenerated; its exact geometry is locked and protection activates automatically after the physical/lighting completion gates pass"
                :(z.enabled()?"This Region is already pregenerated and protected; use Rewipe if you intentionally want to clear it again"
                :(opLock!=null?opLock:(running?"Another Ocean Canvas operation is already running":"Pregen the exact Region footprint, then automatically protect it only after verified completion")));
        actionRow(g,R(CONTENT_X),y,CONTENT_W,33,topPregenLabel,topPregenAccent,topPregenTip,()->{
            if(activePregenHere){opsOpen=true;opsTab="DIAGNOSTICS";say("Showing live Pregen for "+z.name());return;}
            if(z.enabled()){say("Already pregenerated and protected — use Rewipe only if you intentionally want to clear it again");return;}
            if(opLock!=null){say(opLock);return;}
            if(running){say("An operation is already running in "+jobRegionName(activeJob));return;}
            if(!ensureOperationPreview("PREGEN",z.name(),""))return;
            String token=operationPreviewToken("PREGEN",z.name(),"");if(token==null)return;
            OceanCanvasZoneClientCache.clearOperationPreview();send(new OceanCanvasZonePregenRequestPayload(z.name(),token));say("Pregen requested for "+z.name()+" — it will auto-protect only after verified completion");
        });
        y+=41;

        // Coordinates - rectangles expose X1/Z1 + X2/Z2; polygons expose numbered Xn/Zn rows.
        // Every vertex remains numerically editable, with paging only to keep the right panel compact.
        var pendingGeom=pendingGeometryFor(z);
        List<Integer> displayedVertices=regionDisplayVertices(z);
        boolean polygon=displayedVertices.size()>=6;
        int[] shownBounds=regionDisplayBounds(z);
        int rx1=geometryLive?gx1:shownBounds[0], rz1=geometryLive?gz1:shownBounds[1], rx2=geometryLive?gx2:shownBounds[2], rz2=geometryLive?gz2:shownBounds[3];
        if(polygon){
            int count=displayedVertices.size()/2; selectedRegionVertex=Math.floorMod(selectedRegionVertex,count);
            int pages=Math.max(1,(count+REGION_VERTEX_ROWS-1)/REGION_VERTEX_ROWS);regionVertexPage=Math.max(0,Math.min(regionVertexPage,pages-1));
            int first=regionVertexPage*REGION_VERTEX_ROWS,last=Math.min(count,first+REGION_VERTEX_ROWS);
            text(g,"Vertices (Blocks)",R(CONTENT_X),y,DIM);
            right(g,(first+1)+"–"+last+" / "+count,R(1354),y,DIM);
            if(pages>1){
                icon(g,"minus",R(1364),y-4,regionVertexPage>0?CY:DIM); hit(R(1360),y-8,22,24,"Previous vertex coordinate page",()->{regionVertexPage=Math.max(0,regionVertexPage-1);cancelEdit();});
                icon(g,"plus",R(1390),y-4,regionVertexPage<pages-1?CY:DIM); hit(R(1386),y-8,22,24,"Next vertex coordinate page",()->{regionVertexPage=Math.min(pages-1,regionVertexPage+1);cancelEdit();});
            }
            y+=22;
            for(int vi=first;vi<last;vi++){
                int vx=displayedVertices.get(vi*2),vz=displayedVertices.get(vi*2+1);
                coordBox(g,"X"+(vi+1),vx,R(CONTENT_X),y,"region.vertex."+vi+".x");
                coordBox(g,"Z"+(vi+1),vz,R(1249),y,"region.vertex."+vi+".z");
                y+=34;
            }
            int bw=(CONTENT_W-12)/3;
            miniAction(g,R(CONTENT_X),y,bw,"Insert",activeRegionOp?DIM:CY,activeRegionOp?regionOperationLockTip(z):"Use Vertex on the map and click an edge to insert a point",()->{if(activeRegionOp){say(regionOperationLockTip(z));return;}activeTool.put("REGIONS","Vertex");say("Vertex insert active — click the Region edge");});
            miniAction(g,R(CONTENT_X)+bw+6,y,bw,"Delete V"+(selectedRegionVertex+1),activeRegionOp||count<=3?DIM:GOLD,activeRegionOp?regionOperationLockTip(z):(count<=3?"A polygon needs at least three vertices":"Delete the selected Region vertex"),()->{if(activeRegionOp){say(regionOperationLockTip(z));return;}deleteSelectedRegionVertex();});
            miniAction(g,R(CONTENT_X)+(bw+6)*2,y,bw,"Snap "+(snap?snapStep+"b":"Off"),CY,"Cycle exact vertex snap: 1 / 8 / 16 / 32 blocks",this::cycleSnapStep);
            y+=36;
        }else{
            text(g,"Position (Blocks)",R(CONTENT_X),y,DIM);
            icon(g,"info",R(1392),y-4,CY);
            hit(R(1386),y-8,26,24,"Click any coordinate, type the new value, then press Enter",()->say("Coordinate edits stay staged until their exact server impact is accepted"));
            y+=22;
            coordBox(g,"X1",rx1,R(CONTENT_X),y,"region.x1"); coordBox(g,"Z1",rz1,R(1249),y,"region.z1");
            y+=34;
            coordBox(g,"X2",rx2,R(CONTENT_X),y,"region.x2"); coordBox(g,"Z2",rz2,R(1249),y,"region.z2");
            y+=36;
            miniAction(g,R(CONTENT_X),y,CONTENT_W,"Snap "+(snap?snapStep+" blocks":"Off"),CY,"Cycle map geometry snap: 1 / 8 / 16 / 32 blocks",this::cycleSnapStep);
            y+=34;
        }

        if(pendingGeom!=null){
            hline(g,R(CONTENT_X),y,CONTENT_W);y+=9;
            var gp=OceanCanvasZoneClientCache.operationPreview();
            boolean matching=gp!=null&&!gp.stale(30_000L)&&gp.matches("REGION_GEOMETRY",pendingGeom.regionName(),pendingGeom.argument());
            text(g,"GEOMETRY CHANGE STAGED",R(CONTENT_X),y,matching&&gp.blocked()?RED:GOLD);y+=18;
            if(!matching){
                text(g,"Checking exact affected chunks…",R(CONTENT_X),y,DIM);y+=20;
                miniAction(g,R(CONTENT_X),y,CONTENT_W,"Cancel Staged Change",DIM,"Discard the edited coordinates",this::cancelPendingRegionGeometry);y+=34;
            }else if(gp.blocked()){
                text(g,trim(gp.summary(),CONTENT_W),R(CONTENT_X),y,RED);y+=20;
                miniAction(g,R(CONTENT_X),y,CONTENT_W,"Cancel Staged Change",RED,"Discard the blocked geometry edit",this::cancelPendingRegionGeometry);y+=34;
            }else{
                String detail=gp.warnings()==null||gp.warnings().isBlank()?"Exact scope: "+fmt(gp.chunks())+" chunks":trim(gp.warnings(),CONTENT_W);
                text(g,detail,R(CONTENT_X),y,gp.warnings()==null||gp.warnings().isBlank()?DIM:GOLD);y+=20;
                int bw=(CONTENT_W-6)/2;
                miniAction(g,R(CONTENT_X),y,bw,"Apply Geometry Change",GREEN,"Apply these exact staged coordinates using the server-approved scope",this::submitPendingRegionGeometry);
                miniAction(g,R(CONTENT_X)+bw+6,y,bw,"Cancel",DIM,"Discard the staged coordinates",this::cancelPendingRegionGeometry);y+=34;
            }
        }

        // Size - computed from the real footprint envelope; chunk count is exact for arbitrary shapes.
        text(g,"Size",R(CONTENT_X),y,DIM); y+=18;
        int wBlocks=rx2-rx1, dBlocks=rz2-rz1;
        text(g,fmt(wBlocks)+" × "+fmt(dBlocks)+" blocks",R(CONTENT_X),y,0xFFE2E2E2); y+=17;
        int cw=Math.max(1,(int)Math.ceil(wBlocks/16.0)), cd=Math.max(1,(int)Math.ceil(dBlocks/16.0));
        var geometryPreview=OceanCanvasZoneClientCache.operationPreview();
        long chunkCount=pendingGeom!=null?(polygon && geometryPreview!=null && geometryPreview.matches("REGION_GEOMETRY",z.name(),pendingGeom.argument())
                ? geometryPreview.chunks():(long)cw*cd)
                :(z.explicitChunkCount()>0L?z.explicitChunkCount():(long)cw*cd);
        text(g,fmt(chunkCount)+" chunks ("+cw+" × "+cd+")",R(CONTENT_X),y,0xFFE2E2E2);
        y+=28;

        // Structure rules - real per-region overrides
        int custom=0;
        for(var k:OceanCanvasStructureKind.values()) if(!"INHERIT".equals(z.ruleFor(k.id()))) custom++;
        disclosureRow(g,"Structures",custom==0?"Default":"Custom ("+custom+")",y,"region.structures",
                "Per-region structure rules: Default, Always or Never");
        y+=35;
        if("region.structures".equals(disclosure)){
            for(var k:OceanCanvasStructureKind.values()){
                String rule=z.ruleFor(k.id());
                String label=switch(rule){case "FORCE_ON"->"Always";case "FORCE_OFF"->"Never";default->"Default";};
                int col=switch(rule){case "FORCE_ON"->CY;case "FORCE_OFF"->RED;default->DIM;};
                hline(g,R(1098),y,CONTENT_W-10);
                text(g,trim(k.displayName(),190),R(1098),y+10,0xFFC9C9C9);
                right(g,label,R(CONTENT_RIGHT),y+10,col);
                final String kindId=k.id(); final String next=switch(rule){case "INHERIT"->"FORCE_ON";case "FORCE_ON"->"FORCE_OFF";default->"INHERIT";};
                final String nextLabel=switch(next){case "FORCE_ON"->"Always";case "FORCE_OFF"->"Never";default->"Default";};
                hit(R(1098),y+2,CONTENT_W-10,24,k.displayName()+": "+label+" → "+nextLabel,()->{
                    send(new OceanCanvasZoneSetOverrideRequestPayload(z.name(),kindId,next));
                    say(k.displayName()+" → "+nextLabel);
                });
                y+=26;
            }
            y+=6;
        }

        disclosureRow(g,"Canvas Settings",z.hasBiomeOverride()?prettyId(z.biomeOverride()):"Default",y,"region.canvas",
                "Region biome painting and mob rules");
        y+=35;
        if("region.canvas".equals(disclosure)){
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Biome override",R(1098),y+10,0xFFC9C9C9);
            right(g,z.hasBiomeOverride()?trim(prettyId(z.biomeOverride()),120):"None",R(CONTENT_RIGHT),y+10,z.hasBiomeOverride()?CY:DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Type a biome id, or clear it to inherit",
                    ()->beginEdit("region.biome",z.hasBiomeOverride()?z.biomeOverride():"minecraft:"));
            y+=26;
            if("region.biome".equals(editKey)) { drawEditBox(g,R(1098),y-4,CONTENT_W-10,22); y+=26; }
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Suppress hostile mobs",R(1098),y+10,0xFFC9C9C9);
            right(g,z.suppressHostileMobs()?"On":"Off",R(CONTENT_RIGHT),y+10,z.suppressHostileMobs()?CY:DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,(z.suppressHostileMobs()?"Allow":"Suppress")+" hostile mob spawning in this region",()->{
                send(new OceanCanvasZoneSetMobRuleRequestPayload(z.name(),!z.suppressHostileMobs()));
                say(z.suppressHostileMobs()?"Allowing hostile mobs":"Suppressing hostile mobs");
            });
            y+=26;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Global canvas settings",R(1098),y+10,0xFFC9C9C9);
            text(g,"›",R(1397),y+10,DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Open the global Ocean Canvas settings screen",
                    ()->{ if(minecraft!=null) minecraft.gui.setScreen(new OceanCanvasSettingsScreen(this)); });
            y+=32;
        }

        y+=9;
        text(g,"OPERATIONS",R(CONTENT_X),y,CY);
        y+=23;
        // A destructive world operation is never confirmed from a vague toast. Keep the exact
        // server-authored dry run visible beside the action until it expires or is consumed.
        var regionPreview=OceanCanvasZoneClientCache.operationPreview();
        if(regionPreview!=null&&!regionPreview.stale(30_000L)&&regionPreview.target().equals(z.name())
                && (regionPreview.kind().equals("PREGEN")||regionPreview.kind().equals("REWIPE")||regionPreview.kind().equals("RESTORE")||regionPreview.kind().equals("REGION_GEOMETRY"))){
            int pc=regionPreview.blocked()?RED:GREEN;
            text(g,"SERVER DRY RUN  ·  "+regionPreview.kind()+"  ·  "+(regionPreview.blocked()?"BLOCKED":"READY"),R(CONTENT_X),y,pc); y+=18;
            text(g,"Exact scope "+fmt(regionPreview.chunks())+" chunks  ·  C "+fmt(regionPreview.canvas())+"  V "+fmt(regionPreview.vanilla())
                    +"  M "+fmt(regionPreview.custom())+"  U "+fmt(regionPreview.unknown()),R(CONTENT_X),y,0xFFC9C9C9); y+=17;
            if(regionPreview.linkedProjects()>0||regionPreview.linkedTasks()>0){
                text(g,"Linked: "+regionPreview.linkedProjects()+" project(s), "+regionPreview.linkedTasks()+" task(s)",R(CONTENT_X),y,DIM); y+=17;
            }
            String previewDetail=regionPreview.blocked()?regionPreview.blockers():regionPreview.warnings();
            if(previewDetail!=null&&!previewDetail.isBlank()){ text(g,trim(previewDetail,CONTENT_W),R(CONTENT_X),y,regionPreview.blocked()?RED:GOLD); y+=17; }
            if(regionPreview.resourceForecast()!=null&&!regionPreview.resourceForecast().isBlank()){text(g,trim("Forecast ["+regionPreview.resourceRisk()+"] "+regionPreview.resourceForecast(),CONTENT_W),R(CONTENT_X),y,regionPreview.resourceRisk().equalsIgnoreCase("HIGH")?GOLD:DIM);y+=17;}
            if(regionPreview.dryRunDiff()!=null&&!regionPreview.dryRunDiff().isBlank()){text(g,trim("Dry-run diff · "+regionPreview.dryRunDiff(),CONTENT_W),R(CONTENT_X),y,DIM);y+=17;}
            if(!regionPreview.blocked()){ text(g,"Click the matching operation again to confirm this exact state.",R(CONTENT_X),y,DIM); y+=17; }
            hline(g,R(CONTENT_X),y,CONTENT_W); y+=10;
        }
        /* v253.38 compatibility lock is computed near the top because Pregen is now a primary
           selected-Region action. Rewipe/Restore below reuse the same server-authored lock. */
        boolean rewipeArmed=operationPreviewToken("REWIPE",z.name(),"")!=null;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,33,rewipeArmed?"↻   Confirm Rewipe":"↻   Rewipe Region",opLock!=null?DIM:(rewipeArmed?RED:PURPLE),
                opLock!=null?opLock:withReversibility("Return every chunk in "+z.name()+" to blank Ocean Canvas state","rewipe"),
                ()->{ if(opLock!=null){say(opLock);return;} if(!ensureOperationPreview("REWIPE",z.name(),""))return; String token=operationPreviewToken("REWIPE",z.name(),""); if(token==null)return; OceanCanvasZoneClientCache.clearOperationPreview(); send(new OceanCanvasZoneRewipeRequestPayload(z.name(),token)); say("Rewipe requested for "+z.name()); });
        y+=41;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,33,"☰   Queue Pregen",opLock!=null?DIM:CY,
                opLock!=null?opLock:"Add "+z.name()+" to the Pregen queue instead of starting it now",()->{
                    if(opLock!=null){say(opLock);return;}
                    workspace("queue_add","",z.name(),"");
                    say("Queued "+z.name()+" — open Operations to review and run");
                });
        y+=41;
        boolean restoreArmed=operationPreviewToken("RESTORE",z.name(),"")!=null;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,33,restoreArmed?"↩   Confirm Restore":"↩   Restore to Vanilla",opLock!=null?DIM:(restoreArmed?RED:GOLD),
                opLock!=null?opLock:withReversibility("Return every chunk in "+z.name()+" to vanilla generation","restore"),
                ()->{ if(opLock!=null){say(opLock);return;} if(!ensureOperationPreview("RESTORE",z.name(),""))return; String token=operationPreviewToken("RESTORE",z.name(),""); if(token==null)return; OceanCanvasZoneClientCache.clearOperationPreview(); send(new OceanCanvasZoneRestoreRequestPayload(z.name(),token)); say("Restore requested for "+z.name()); });
        y+=47;

        disclosureRow(g,"Advanced",null,y,"region.advanced","Teleport, duplicate, colour and delete");
        y+=35;
        if(!"region.advanced".equals(disclosure)) return y;
        {
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,"→   Go Here",CY,"Teleport to the centre of "+z.name(),
                    ()->{send(new OceanCanvasZoneTeleportRequestPayload(z.name()));say("Teleporting to "+z.name());});
            y+=36;
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,"⊕   Duplicate Region",CY,"Copy "+z.name()+" alongside itself",()->{
                int w=z.maxX()-z.minX();
                send(new OceanCanvasZoneDuplicateRequestPayload(z.name(),nextRegionName(z.name()),
                        z.maxX()+16,z.minY(),z.minZ(),z.maxX()+16+w,z.maxY(),z.maxZ()));
                say("Duplicated "+z.name());
            });
            y+=36;
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,"☲   Region Colour",CY,"Cycle this region's label colour",()->{
                // v253.68.2: the server only ever accepts the 8 named colours below
                // (OceanCanvasPlayerZones#VALID_REGION_COLORS) - this used to cycle hex strings
                // that failed server-side validation on every single click.
                String[] palette={"","RED","ORANGE","YELLOW","LIME","CYAN","BLUE","PURPLE","PINK"};
                String cur=z.hasColor()?z.color():"";
                int idx=0; for(int i=0;i<palette.length;i++) if(palette[i].equalsIgnoreCase(cur)) idx=i;
                String next=palette[(idx+1)%palette.length];
                send(new OceanCanvasZoneSetColorRequestPayload(z.name(),next));
                say(next.isEmpty()?"Region colour cleared":"Region colour → "+title(next));
            });
            y+=36;
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,"⊕   Duplicate as Draft",activeRegionOp?DIM:CY,activeRegionOp?regionOperationLockTip(z):"Copy this Region definition and exact polygon as a new unprotected draft",()->{if(activeRegionOp){say(regionOperationLockTip(z));return;}duplicateSelectedRegion();});
            y+=36;
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,"⚙   Stewardship Workbench",CY,"Recovery history, policy and world-authorship tools",()->openWorkbench("RECOVERY"));
            y+=36;
            boolean delArmed=isArmed("delete:"+z.name());
            actionRow(g,R(CONTENT_X),y,CONTENT_W,30,delArmed?"☢   Confirm Delete":"☢   Delete Region",delArmed?RED:GOLD,
                    withReversibility("Remove the region definition. Terrain already written is not reverted.","region_delete"),
                    ()->{ if(activeRegionOp){say(regionOperationLockTip(z));return;} if(confirm("delete:"+z.name(),"Click Delete again to confirm")){
                        send(new OceanCanvasZoneRemoveRequestPayload(z.name()));
                        selectedRegion=""; autoSelected=false; disclosure=null; say("Deleted "+z.name()); } });
            y+=30;
        }
        return y;
    }
    private void coordBox(GuiGraphicsExtractor g,String label,int v,int x,int y,String key){
        text(g,label,x,y+8,DIM);
        boolean editing=key.equals(editKey);
        fill(g,x+31,y,121,26,DARK);border(g,x+31,y,121,26,editing?CY:BORDER);
        String shown=editing?editBuf+"_":String.valueOf(v);
        center(g,trim(shown,113),x+31,y+9,121,editing?CY:0xFFE6E6E6);
        if(editing) blocker(x+31,y,121,26);
        else hit(x+31,y,121,26,"Click to edit, Enter to apply, Esc to cancel",()->beginEdit(key,String.valueOf(v)));
    }
    private void drawEditBox(GuiGraphicsExtractor g,int x,int y,int w,int h){
        fill(g,x,y,w,h,DARK);border(g,x,y,w,h,CY);
        text(g,trim(editBuf+"_",w-10),x+5,y+(h-8)/2,CY);
        blocker(x,y,w,h);
    }
    private void cycleRegion(){
        var zs=zones(); if(zs.isEmpty()) return;
        int idx=0; for(int i=0;i<zs.size();i++) if(zs.get(i).name().equalsIgnoreCase(selectedRegion)) idx=i;
        selectedRegion=zs.get((idx+1)%zs.size()).name();
        disclosure=null; armed=null; cancelEdit(); say("Selected region "+selectedRegion);
    }
    private String nextWaypointName(){
        Set<String> taken=new HashSet<>();
        for(var f:OceanCanvasZoneClientCache.atlasFeatures()) taken.add(f.name().toLowerCase(Locale.ROOT));
        for(int i=1;i<1000;i++){
            String candidate="Waypoint "+i;
            if(!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return "Waypoint "+System.currentTimeMillis();
    }
    private String nextRegionName(String base){
        Set<String> taken=new HashSet<>();
        for(var z:zones()) taken.add(z.name().toLowerCase(Locale.ROOT));
        String stem=base==null||base.isBlank()?"Region":base;
        for(int i=2;i<1000;i++){
            String candidate=stem+"_"+i;
            if(!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return stem+"_"+System.currentTimeMillis();
    }
    private boolean regionOperationLocked(OceanCanvasZoneSyncPayload.ZoneEntry z){
        if(z==null)return false;var j=OceanCanvasZoneClientCache.job();return j!=null&&j.scopeName()!=null&&j.scopeName().equalsIgnoreCase(z.name());
    }
    private String regionOperationLockTip(OceanCanvasZoneSyncPayload.ZoneEntry z){
        if(!regionOperationLocked(z))return "";var j=OceanCanvasZoneClientCache.job();return operationLabel(j.kind())+" owns this Region's exact chunk mask. Geometry, identity and protection are locked until the operation finishes.";
    }
    private void cycleSnapStep(){
        int[] steps={1,8,16,32};int at=0;for(int i=0;i<steps.length;i++)if(steps[i]==snapStep)at=i;snapStep=steps[(at+1)%steps.length];snap=true;say("Snap → "+snapStep+" blocks");
    }
    private void duplicateSelectedRegion(){
        var z=region();if(z==null){say("Select a Region first");return;}if(regionOperationLocked(z)){say(regionOperationLockTip(z));return;}
        int minY=(minecraft!=null&&minecraft.level!=null?minecraft.level.getMinY():-64),maxY=(minecraft!=null&&minecraft.level!=null?minecraft.level.getMaxY():319)+1;String name=nextRegionName(z.name()+" Copy");
        send(new OceanCanvasZoneCreateRequestPayload(name,z.minX(),minY,z.minZ(),z.maxX(),maxY,z.maxZ()));
        List<Integer> shape=cleanVertices(z.shapeVertices());if(shape.size()>=6){pendingRegionShapeName=name;pendingRegionShapeVertices=List.copyOf(shape);}
        selectedRegion=name;tab="REGIONS";panelOpen=true;collapsed=false;disclosure=null;activeTool.put("REGIONS","Select");say("Duplicating "+z.name()+" as draft "+name);
    }
    private String createRegion(int minX,int minZ,int maxX,int maxZ){
        // Full build height, matching what "/oceancanvas protect here" already does - the map can
        // only ever express a footprint, and silently guessing a vertical range would be worse.
        int minY=(minecraft!=null&&minecraft.level!=null?minecraft.level.getMinY():-64);
        int maxY=(minecraft!=null&&minecraft.level!=null?minecraft.level.getMaxY():319)+1;
        String name=nextRegionName("Region");
        send(new OceanCanvasZoneCreateRequestPayload(name,minX,minY,minZ,maxX,maxY,maxZ));
        // Select optimistically by NAME only: a refused create simply leaves nothing selected
        // rather than showing a region that does not exist.
        selectedRegion=name; tab="REGIONS"; disclosure=null; panelOpen=true; collapsed=false;
        activeTool.put("REGIONS","Select");
        say("Created draft region "+name+" ("+fmt(maxX-minX)+" × "+fmt(maxZ-minZ)+") — review rules, then Pregen & Protect");
        return name;
    }
    private void createRegionAtViewCentre(){
        int cx=(int)Math.round(mapViewX), cz=(int)Math.round(mapViewZ);
        createRegion(cx-512,cz-512,cx+512,cz+512);
    }

    // ---------------------------------------------------------------- PLANS panel
    private int drawPlans(GuiGraphicsExtractor g){
        var p=plan();
        if(p==null){
            heading(g,"SELECTED PLAN");
            emptyState(g,OceanCanvasZoneClientCache.planGroups().isEmpty()?"No plan layers yet":"No plan layer selected",
                    "A plan layer groups the paths and shapes","you draw over the world.");
            actionRow(g,R(CONTENT_X),200,CONTENT_W,32,"⊕   New Plan Layer",CY,"Create a plan layer",
                    ()->beginEdit("plan.add","New Layer"));
            if("plan.add".equals(editKey)) drawEditBox(g,R(CONTENT_X),240,CONTENT_W,22);
            return 264;
        }
        var vectors=planVectors(p.id());
        int implementedCount=0; for(var v:vectors) if(v.implemented()) implementedCount++;
        final int implemented=implementedCount;
        heading(g,"SELECTED PLAN");
        collapseControl(g);
        if(collapsed) return 104;

        int y=117;
        if("plan.name".equals(editKey)){
            drawEditBox(g,R(CONTENT_X),y-6,236,22);
        }else{
            text(g,trim(p.name(),150),R(CONTENT_X),y,BRIGHT);
            icon(g,"edit",R(1244),y-3,CY);
            hit(R(1238),y-8,28,26,"Rename this plan layer",()->beginEdit("plan.name",p.name()));
        }
        if(OceanCanvasZoneClientCache.planGroups().size()>1){
            text(g,"▾",R(1280),y,DIM);
            hit(R(1272),y-8,28,26,"Switch plan layer",this::cyclePlan);
        }
        y+=30;

        String resolvedParent="Top level";
        for(var q:OceanCanvasZoneClientCache.planGroups()) if(q.id().equals(p.parentId())) resolvedParent=q.name();
        final String parentName=resolvedParent;
        pair(g,"Plan Group",parentName,y);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,"Parent layer folder",()->say("Parent: "+parentName));
        y+=31;

        String status=vectors.isEmpty()?"Empty":implemented==vectors.size()?"Implemented":implemented>0?"In Progress":"Planned";
        int statusCol=vectors.isEmpty()?DIM:implemented==vectors.size()?GREEN:implemented>0?GOLD:DIM;
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Status",R(CONTENT_X),y+10,DIM);
        right(g,status,R(CONTENT_RIGHT),y+10,0xFFE2E2E2);
        text(g,"●",R(CONTENT_RIGHT)-font.width(status)-14,y+10,statusCol);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,implemented+" of "+vectors.size()+" objects marked implemented",
                ()->say(implemented+"/"+vectors.size()+" implemented"));
        y+=31;

        // Opacity - a real draggable slider bound to group_opacity
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Opacity",R(CONTENT_X),y+10,DIM);
        int pct=(int)Math.round(Math.max(0.05,Math.min(1.0,p.opacity()))*100);
        fill(g,R(1165),y+14,168,2,0xFF2E3236);
        fill(g,R(1165),y+14,(int)(168*pct/100.0),2,CY);
        fill(g,R(1165)+(int)(168*pct/100.0)-5,y+10,11,11,CY);
        right(g,pct+"%",R(1400),y+8,0xFFE2E2E2);
        final int sliderY=y;
        hit(R(1165),y+6,168,20,"Drag to set layer opacity",()->{});
        // click-position handling for the slider happens in mouseClicked via sliderRect
        sliderRectX=R(1165); sliderRectY=sliderY+6; sliderRectW=168; sliderRectH=20; sliderGroup=p.id();
        y+=31;

        pair(g,"Category",prettyId(p.category()),y);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,"Cycle CUSTOM / BIOME / STRUCTURE",()->{
            String[] cats={"CUSTOM","BIOME","STRUCTURE"};
            int idx=0; for(int i=0;i<cats.length;i++) if(cats[i].equalsIgnoreCase(p.category())) idx=i;
            String next=cats[(idx+1)%cats.length];
            planning("group_update",p.id(),p.name(),(p.parentId()==null?"":p.parentId())+"\t"+p.opacity()+"\t"+next+"\t"+p.drawOrder());
            say("Category → "+prettyId(next));
        });
        y+=31;

        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Visible",R(CONTENT_X),y+10,DIM);
        right(g,p.visible()?"Shown":"Hidden",R(1400),y+8,p.visible()?CY:DIM);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,(p.visible()?"Hide":"Show")+" this layer in the world and on the map",()->{
            planning("group_visible",p.id(),String.valueOf(!p.visible()),"");
            say(p.visible()?"Layer hidden":"Layer shown");
        });
        y+=33;

        var selectedObj=planObject();
        String selectedObjLabel=selectedObj!=null&&p.id().equals(selectedObj.parentId())?trim(selectedObj.name(),150):"None";
        disclosureRow(g,"Selected Object",selectedObjLabel,y,"plan.object","Exact vertices, transforms, width, lock and advanced geometry");
        y+=31;
        if("plan.object".equals(disclosure)){ y=drawPlanObjectContext(g,p,selectedObj,y); return y; }

        disclosureRow(g,"Details",vectors.size()+(vectors.size()==1?" object":" objects"),y,"plan.details","List the objects in this layer");
        y+=31;
        if("plan.details".equals(disclosure)){
            if(vectors.isEmpty()){ text(g,"This layer has no objects yet.",R(1098),y+6,DIM); y+=26; }
            int off=pageOffset(vectors.size(),6);
            for(int i=off;i<Math.min(off+6,vectors.size());i++){
                var v=vectors.get(i);
                hline(g,R(1098),y,CONTENT_W-10);
                text(g,trim(v.name(),190),R(1098),y+10,0xFFC9C9C9);
                right(g,v.implemented()?"Built":"Planned",R(CONTENT_RIGHT),y+10,v.implemented()?GREEN:DIM);
                final String vid=v.id(); final boolean built=v.implemented();
                hit(R(1098),y+2,CONTENT_W-84,24,"Select "+v.name()+" for exact vertex editing",()->{selectedPlanObject=vid;selectedPlanVertex=0;say("Selected "+v.name());});
                hit(R(CONTENT_RIGHT)-70,y+2,70,24,(built?"Mark not built":"Mark as built")+": "+v.name(),()->{
                    planning("implemented",vid,String.valueOf(!built),"");
                    say(built?"Marked not built":"Marked built");
                });
                y+=26;
            }
            y=pageFooter(g,y,vectors.size(),6);
            if(selectedObj!=null&&p.id().equals(selectedObj.parentId())) y=drawPlanVertexEditor(g,selectedObj,y+4);
        }

        disclosureRow(g,"Reference Images",OceanCanvasZoneClientCache.planningReferences().size()+"",y,"plan.references","Import, place, register, lock and group reference images");
        y+=31;
        if("plan.references".equals(disclosure)){ y=drawPlanReferencesContext(g,y); return y; }

        disclosureRow(g,"Saved Views",(OceanCanvasZoneClientCache.planBookmarks().size()+OceanCanvasZoneClientCache.viewPresets().size())+"",y,"plan.views","Map bookmarks and visibility presets");
        y+=31;
        if("plan.views".equals(disclosure)){ y=drawPlanViewsContext(g,y); return y; }

        disclosureRow(g,"Terrain Planning",OceanCanvasZoneClientCache.terrainAssets().size()+" assets",y,"plan.terrain","Elevation, hydrology, candidates, heightmaps and Gaea/WorldPainter handoff");
        y+=31;
        if("plan.terrain".equals(disclosure)){ y=drawPlanTerrainContext(g,p,selectedObj,y); return y; }

        disclosureRow(g,"Edit Plan",p.locked()?"Locked":"Unlocked",y,"plan.edit","Lock, solo or reorder this layer");
        y+=31;
        if("plan.edit".equals(disclosure)){
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,p.locked()?"Unlock layer":"Lock layer",R(1098),y+10,0xFFC9C9C9);
            hit(R(1098),y+2,CONTENT_W-10,24,p.locked()?"Allow edits to this layer":"Prevent edits to this layer",()->{
                planning("group_lock",p.id(),String.valueOf(!p.locked()),"");
                say(p.locked()?"Layer unlocked":"Layer locked");
            });
            y+=26;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Solo this layer",R(1098),y+10,0xFFC9C9C9);
            hit(R(1098),y+2,CONTENT_W-10,24,"Hide every other layer",()->{planning("group_solo",p.id(),"","");say("Soloed "+p.name());});
            y+=30;
        }
        y+=15;

        text(g,"ACTIONS",R(CONTENT_X),y,CY);
        y+=23;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,"⊕   Duplicate Plan",CY,"Copy this layer and its settings",
                ()->{planning("group_duplicate",p.id(),"","");say("Duplicated "+p.name());});
        y+=40;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,"⊕   Export Plan",CY,"Write this layer's objects to the export directory",
                ()->{planning("export_plan",p.id(),p.id(),"");say("Exporting "+p.name()+"…");});
        y+=40;
        boolean clearArmed=isArmed("plan_clear:"+p.id());
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,clearArmed?"☢   Confirm Clear":"☢   Clear Plan",clearArmed?RED:CY,
                withReversibility("Delete every object in this layer ("+vectors.size()+")","plan_clear"),()->{
                    if(vectors.isEmpty()){say("This layer is already empty");return;}
                    if(confirm("plan_clear:"+p.id(),"Click Clear Plan again to confirm")){
                        StringBuilder ids=new StringBuilder();
                        for(var v:vectors){ if(ids.length()>0) ids.append(','); ids.append(v.id()); }
                        planning("batch_delete","",ids.toString(),"");
                        say("Cleared "+vectors.size()+" objects from "+p.name());
                    }
                });
        y+=46;
        disclosureRow(g,"Advanced",null,y,"plan.advanced","Draw order and new layers");
        y+=31;
        if(!"plan.advanced".equals(disclosure)) return y;
        {
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Draw order",R(1098),y+10,0xFFC9C9C9);
            right(g,String.valueOf(p.drawOrder()),R(1360),y+10,0xFFE2E2E2);
            icon(g,"minus",R(1372),y+3,CY);
            hit(R(1368),y+2,22,24,"Move this layer down the draw order",
                    ()->planning("group_update",p.id(),p.name(),(p.parentId()==null?"":p.parentId())+"\t"+p.opacity()+"\t"+p.category()+"\t"+(p.drawOrder()-1)));
            icon(g,"plus",R(1392),y+3,CY);
            hit(R(1388),y+2,22,24,"Move this layer up the draw order",
                    ()->planning("group_update",p.id(),p.name(),(p.parentId()==null?"":p.parentId())+"\t"+p.opacity()+"\t"+p.category()+"\t"+(p.drawOrder()+1)));
            y+=28;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"New plan layer",R(1098),y+10,0xFFC9C9C9);
            text(g,"›",R(1397),y+10,DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Create another plan layer",()->beginEdit("plan.add","New Layer"));
            y+=28;
            if("plan.add".equals(editKey)){ drawEditBox(g,R(1098),y-4,CONTENT_W-10,22); y+=26; }
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Planning Library & In-World View",R(1098),y+10,0xFFC9C9C9);text(g,"›",R(1397),y+10,CY);
            hit(R(1098),y+2,CONTENT_W-10,24,"Blueprint preview/trace, presets, reference sets, bookmarks and terrain revisions",()->openWorkbench("LIBRARY"));
            y+=28;
        }
        return y;
    }
    private int drawPlanVertexEditor(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.PlanningVector v,int y){
        List<Integer> pts=planningVertexList(v.points());int n=pts.size()/2;if(n==0)return y;selectedPlanVertex=Math.floorMod(selectedPlanVertex,n);int at=selectedPlanVertex*2;
        hline(g,R(1098),y,CONTENT_W-10);
        text(g,"Vertex",R(1098),y+10,DIM);
        text(g,(selectedPlanVertex+1)+" / "+n,R(1168),y+10,0xFFE2E2E2);
        text(g,"‹",R(1286),y+10,CY); hit(R(1278),y+2,24,24,"Previous vertex",()->selectedPlanVertex=Math.floorMod(selectedPlanVertex-1,n));
        text(g,"›",R(1318),y+10,CY); hit(R(1310),y+2,24,24,"Next vertex",()->selectedPlanVertex=(selectedPlanVertex+1)%n);
        y+=27;
        hline(g,R(1098),y,CONTENT_W-10);text(g,"X",R(1098),y+10,DIM);
        if("plan.vertex.x".equals(editKey))drawEditBox(g,R(1140),y+2,100,22);else{text(g,String.valueOf(pts.get(at)),R(1140),y+10,BRIGHT);hit(R(1134),y+2,112,24,"Edit X coordinate",()->beginEdit("plan.vertex.x",String.valueOf(pts.get(at))));}
        text(g,"Z",R(1260),y+10,DIM);
        if("plan.vertex.z".equals(editKey))drawEditBox(g,R(1290),y+2,100,22);else{text(g,String.valueOf(pts.get(at+1)),R(1290),y+10,BRIGHT);hit(R(1284),y+2,112,24,"Edit Z coordinate",()->beginEdit("plan.vertex.z",String.valueOf(pts.get(at+1))));}
        return y+32;
    }


    /** Modern contextual editor for the currently selected Plan object. */
    private int drawPlanObjectContext(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.PlanGroup p,OceanCanvasZoneClientCache.PlanningVector v,int y){
        if(v==null||p==null||!p.id().equals(v.parentId())){
            text(g,"Select a shape or path on the map.",R(1098),y+8,DIM);y+=30;
            miniAction(g,R(1098),y,150,"Shape",CY,"Switch to Shape and draw a closed Plan object",()->{activeTool.put("PLANS","Shape");say("Shape tool active");});
            miniAction(g,R(1258),y,150,"Path",CY,"Switch to Path and draw an open Plan object",()->{activeTool.put("PLANS","Path");say("Path tool active");});
            return y+32;
        }
        final String vid=v.id(); final boolean locked=v.locked();
        pair(g,"Type",title(v.type()),y);y+=28;
        pair(g,"Vertices",String.valueOf(planningVertexList(v.points()).size()/2),y);y+=28;
        pair(g,"Width",String.format(Locale.US,"%.1f blocks",v.widthBlocks()),y);y+=28;
        int by=y;
        miniAction(g,R(1098),by,96,v.visible()?"Hide":"Show",CY,"Toggle this object on the map",()->planning("visible",vid,String.valueOf(!v.visible()),""));
        miniAction(g,R(1206),by,96,locked?"Unlock":"Lock",CY,locked?"Allow edits":"Prevent geometry edits",()->planning("lock",vid,String.valueOf(!locked),""));
        miniAction(g,R(1314),by,94,v.implemented()?"Unbuild":"Built",v.implemented()?GOLD:CY,"Toggle implemented state",()->planning("implemented",vid,String.valueOf(!v.implemented()),""));
        y+=31;
        y=drawPlanVertexEditor(g,v,y);
        int sy=y;
        miniAction(g,R(1098),sy,96,"Insert",locked?DIM:CY,"Insert a vertex after the selected vertex",()->insertSelectedPlanVertex());
        miniAction(g,R(1206),sy,96,"Delete",locked?DIM:RED,"Delete the selected vertex while preserving minimum geometry",()->deleteSelectedPlanVertex());
        miniAction(g,R(1314),sy,94,"Snap "+snapStep, CY,"Cycle 1 / 8 / 16 / 32 block snapping",this::cycleSnapStep);
        y+=31;
        hline(g,R(1098),y,CONTENT_W-10);text(g,"TRANSFORM",R(1098),y+9,DIM);y+=25;
        int t1=y;
        miniAction(g,R(1098),t1,96,"Smooth",locked?DIM:CY,"Smooth the selected geometry",()->{if(locked){say("Unlock the Plan object first");return;}planning("geom_smooth",vid,"1","");});
        miniAction(g,R(1206),t1,96,"Scale",locked?DIM:CY,"Scale using the Amount value in Advanced Geometry",()->{if(locked){say("Unlock the Plan object first");return;}planning("geom_scale",vid,String.format(Locale.US,"%.4f",geomAmount),"");});
        miniAction(g,R(1314),t1,94,"Rotate",locked?DIM:CY,"Rotate using the Amount value in Advanced Geometry",()->{if(locked){say("Unlock the Plan object first");return;}planning("geom_rotate",vid,String.format(Locale.US,"%.4f",geomAmount),"");});
        y+=31;
        int t2=y;
        miniAction(g,R(1098),t2,96,"Move",locked?DIM:CY,"Move using the dx,dz value in Advanced Geometry",()->{if(locked){say("Unlock the Plan object first");return;}planning("geom_translate",vid,"",geomTranslate);});
        miniAction(g,R(1206),t2,96,"Expand",locked?DIM:CY,"Expand outward using the Amount value",()->{if(locked){say("Unlock the Plan object first");return;}planning("geom_expand",vid,String.format(Locale.US,"%.4f",Math.max(0,geomAmount)),"");});
        miniAction(g,R(1314),t2,94,"Duplicate",locked?DIM:CY,"Duplicate this geometry offset by the current snap step",()->{if(locked){say("Unlock the Plan object first");return;}planning("batch_duplicate","",vid,snapStep+","+snapStep);});
        y+=34;
        actionRow(g,R(1098),y,CONTENT_W-10,27,"Advanced Geometry",CY,"Bezier, variable width, cut/join, push/pull, exact transform amounts and topology",()->openWorkbench("GEOMETRY"));y+=34;
        boolean armedDelete=isArmed("plan_object_delete:"+vid);
        actionRow(g,R(1098),y,CONTENT_W-10,27,armedDelete?"Confirm Delete":"Delete Object",armedDelete?RED:GOLD,locked?"Unlock this object before deleting it":"Delete this Plan object",()->{if(locked){say("Unlock the Plan object first");return;}if(confirm("plan_object_delete:"+vid,"Click Delete Object again to confirm")){planning("delete",vid,"","");selectedPlanObject="";}});
        return y+33;
    }

    private void insertSelectedPlanVertex(){
        var v=planObject();if(v==null){say("Select a Plan object first");return;}if(v.locked()){say("Unlock the Plan object first");return;}
        ArrayList<Integer> pts=new ArrayList<>(planningVertexList(v.points()));int n=pts.size()/2;if(n<1)return;selectedPlanVertex=Math.floorMod(selectedPlanVertex,n);int next=selectedPlanVertex+1;
        int ax=pts.get(selectedPlanVertex*2),az=pts.get(selectedPlanVertex*2+1),bx,bz;
        if(next<n){bx=pts.get(next*2);bz=pts.get(next*2+1);}else if(planningClosed(v.points())){bx=pts.get(0);bz=pts.get(1);}else if(n>1){bx=ax+(ax-pts.get((n-2)*2));bz=az+(az-pts.get((n-2)*2+1));}else{bx=ax+snapStep;bz=az;}
        int nx=snapWorld((ax+bx)/2.0),nz=snapWorld((az+bz)/2.0);int at=(selectedPlanVertex+1)*2;pts.add(at,nx);pts.add(at+1,nz);selectedPlanVertex++;
        planning("points",v.id(),planningPoints(pts,planningClosed(v.points())),"");say("Inserted Plan vertex "+(selectedPlanVertex+1));
    }
    private void deleteSelectedPlanVertex(){
        var v=planObject();if(v==null){say("Select a Plan object first");return;}if(v.locked()){say("Unlock the Plan object first");return;}
        ArrayList<Integer> pts=new ArrayList<>(planningVertexList(v.points()));int n=pts.size()/2,min=planningClosed(v.points())?3:2;if(n<=min){say("This geometry needs at least "+min+" vertices");return;}selectedPlanVertex=Math.floorMod(selectedPlanVertex,n);pts.remove(selectedPlanVertex*2+1);pts.remove(selectedPlanVertex*2);selectedPlanVertex=Math.min(selectedPlanVertex,n-2);
        planning("points",v.id(),planningPoints(pts,planningClosed(v.points())),"");say("Deleted Plan vertex");
    }

    /** Reference-image management stays contextual to Plans; the wide Workbench is only for uncommon recovery/details. */
    private int drawPlanReferencesContext(GuiGraphicsExtractor g,int y){
        var refs=OceanCanvasZoneClientCache.planningReferences();
        actionRow(g,R(1098),y,CONTENT_W-10,27,"⊕ Import Reference",CY,"Import PNG/JPG and place it over the map",this::openReferenceFilePicker);y+=33;
        if(refs.isEmpty()){text(g,"No reference images imported.",R(1098),y+8,DIM);return y+30;}
        text(g,"REFERENCES",R(1098),y+7,DIM);y+=22;
        for(int i=0;i<Math.min(4,refs.size());i++){
            var r=refs.get(i);final String rid=r.id();boolean sel=rid.equals(selectedReference);
            hline(g,R(1098),y,CONTENT_W-10);text(g,(sel?"▶ ":"• ")+trim(r.name(),190),R(1098),y+9,sel?CY:TEXT);right(g,(r.visible()?"eye":"off")+(r.locked()?" · lock":""),R(CONTENT_RIGHT),y+9,DIM);
            hit(R(1098),y+1,CONTENT_W-10,24,"Select reference "+r.name(),()->{selectedReference=rid;selectedPlanObject="";say("Selected reference "+r.name());});y+=25;
        }
        OceanCanvasZoneClientCache.PlanningReference ref=null;for(var r:refs)if(r.id().equals(selectedReference))ref=r;
        if(ref!=null){
            final var rr=ref;final String rid=rr.id();final boolean locked=rr.locked();
            y+=3;pair(g,"Opacity",(int)Math.round(rr.opacity()*100)+"%",y);y+=27;
            int by=y;
            miniAction(g,R(1098),by,96,rr.visible()?"Hide":"Show",CY,"Toggle reference visibility",()->planning("reference_visible",rid,String.valueOf(!rr.visible()),""));
            miniAction(g,R(1206),by,96,locked?"Unlock":"Lock",CY,locked?"Allow transforms":"Prevent transforms",()->planning("reference_lock",rid,String.valueOf(!locked),""));
            miniAction(g,R(1314),by,94,"Opacity +",locked?DIM:CY,"Increase opacity by 10%",()->{if(locked){say("Unlock the reference first");return;}planning("reference_opacity",rid,String.format(Locale.US,"%.2f",Math.min(1,rr.opacity()+.1)),"");});y+=31;
            int by2=y;
            miniAction(g,R(1098),by2,96,"Opacity -",locked?DIM:CY,"Decrease opacity by 10%",()->{if(locked){say("Unlock the reference first");return;}planning("reference_opacity",rid,String.format(Locale.US,"%.2f",Math.max(.05,rr.opacity()-.1)),"");});
            miniAction(g,R(1206),by2,96,"Bounds",locked?DIM:CY,"Edit minX,minZ,maxX,maxZ",()->{if(locked){say("Unlock the reference first");return;}beginEdit("ref.transform",rr.minX()+","+rr.minZ()+","+rr.maxX()+","+rr.maxZ());});
            miniAction(g,R(1314),by2,94,"Rotate",locked?DIM:CY,"Edit rotation in degrees",()->{if(locked){say("Unlock the reference first");return;}beginEdit("ref.rotate",String.format(Locale.US,"%.1f",rr.rotation()));});y+=31;
            if("ref.transform".equals(editKey)||"ref.rotate".equals(editKey)||"ref.name".equals(editKey)||"ref.registration".equals(editKey)){drawEditBox(g,R(1098),y,CONTENT_W-10,22);y+=28;}
            int by3=y;
            miniAction(g,R(1098),by3,150,"Rename",locked?DIM:CY,"Rename this reference",()->{if(locked){say("Unlock the reference first");return;}beginEdit("ref.name",rr.name());});
            miniAction(g,R(1258),by3,150,"Register 2/3 pt",locked?DIM:CY,"Enter imageX,imageY,worldX,worldZ control points separated by semicolons",()->{if(locked){say("Unlock the reference first");return;}beginEdit("ref.registration",rr.registrationPoints()==null?"":rr.registrationPoints());});y+=31;
        }
        var sets=OceanCanvasZoneClientCache.referenceSets();y+=4;text(g,"REFERENCE SETS",R(1098),y+7,DIM);y+=22;
        int sw=96;miniAction(g,R(1098),y,CONTENT_W-10,"⊕ New Reference Set",CY,"Create a visibility/lock/opacity group for references",()->planning("refset_add","","Reference Set "+(sets.size()+1),""));y+=31;
        for(int i=0;i<Math.min(3,sets.size());i++){var set=sets.get(i);final String sid=set.id();boolean sel=sid.equals(selectedReferenceSet);hline(g,R(1098),y,CONTENT_W-10);text(g,(sel?"▶ ":"• ")+trim(set.name(),180),R(1098),y+9,sel?CY:TEXT);right(g,(int)Math.round(set.opacity()*100)+"%",R(CONTENT_RIGHT),y+9,DIM);hit(R(1098),y+1,CONTENT_W-10,24,"Select reference set",()->{selectedReferenceSet=sid;say("Selected reference set "+set.name());});y+=25;}
        OceanCanvasZoneClientCache.ReferenceSet set=null;for(var q:sets)if(q.id().equals(selectedReferenceSet))set=q;
        if(set!=null){final var ss=set;int by=y+2;miniAction(g,R(1098),by,96,ss.visible()?"Hide Set":"Show Set",CY,"Toggle every member reference",()->planning("refset_visible",ss.id(),String.valueOf(!ss.visible()),""));miniAction(g,R(1206),by,96,ss.locked()?"Unlock":"Lock",CY,"Lock/unlock reference-set membership",()->planning("refset_lock",ss.id(),String.valueOf(!ss.locked()),""));miniAction(g,R(1314),by,94,"Solo",CY,"Show only this reference set",()->planning("refset_solo",ss.id(),"",""));y+=33;if(!selectedReference.isBlank()){boolean member=ss.referenceIds()!=null&&ss.referenceIds().contains(selectedReference);actionRow(g,R(1098),y,CONTENT_W-10,27,member?"Remove Selected Reference":"Add Selected Reference",ss.locked()?DIM:CY,ss.locked()?"Unlock the set first":"Toggle selected reference membership",()->{if(ss.locked()){say("Unlock the Reference Set first");return;}planning("refset_member",ss.id(),selectedReference,"");});y+=33;}}
        return y;
    }

    private int drawPlanViewsContext(GuiGraphicsExtractor g,int y){
        var marks=OceanCanvasZoneClientCache.planBookmarks();var presets=OceanCanvasZoneClientCache.viewPresets();
        int by=y;miniAction(g,R(1098),by,150,"Save Map View",CY,"Save map centre, zoom and selected Plan object",()->beginEdit("plan.bookmark_add","View "+(marks.size()+1)));miniAction(g,R(1258),by,150,"Save Visibility",CY,"Save current Plan/reference visibility as a preset",()->beginEdit("plan.preset_add","Preset "+(presets.size()+1)));y+=31;
        if("plan.bookmark_add".equals(editKey)||"plan.preset_add".equals(editKey)){drawEditBox(g,R(1098),y,CONTENT_W-10,22);y+=28;}
        text(g,"MAP VIEWS",R(1098),y+7,DIM);y+=22;
        for(int i=0;i<Math.min(4,marks.size());i++){var b=marks.get(i);final var bb=b;hline(g,R(1098),y,CONTENT_W-10);text(g,trim(b.name(),190),R(1098),y+9,TEXT);right(g,"Go",R(CONTENT_RIGHT),y+9,CY);hit(R(1098),y+1,CONTENT_W-10,24,"Restore map centre, zoom and selected object",()->{mapViewX=bb.centerX();mapViewZ=bb.centerZ();mapBlocksPerPixel=Math.max(.5,Math.min(64,bb.zoom()));if(bb.selectedObjectId()!=null&&!bb.selectedObjectId().isBlank())selectedPlanObject=bb.selectedObjectId();say("View → "+bb.name());});y+=25;}
        text(g,"VISIBILITY PRESETS",R(1098),y+7,DIM);y+=22;
        for(int i=0;i<Math.min(4,presets.size());i++){var q=presets.get(i);final String qid=q.id();hline(g,R(1098),y,CONTENT_W-10);text(g,trim(q.name(),190),R(1098),y+9,TEXT);right(g,"Apply",R(CONTENT_RIGHT),y+9,CY);hit(R(1098),y+1,CONTENT_W-10,24,"Apply saved layer/reference visibility",()->planning("preset_apply",qid,"",""));y+=25;}
        return y;
    }

    /** Common terrain-planning actions stay in the Plan panel; comparisons and round-trip metadata use the expanded Workbench. */
    private int drawPlanTerrainContext(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.PlanGroup p,OceanCanvasZoneClientCache.PlanningVector v,int y){
        if(v==null||p==null||!p.id().equals(v.parentId())){text(g,"Select a Plan shape/path to attach terrain intent.",R(1098),y+8,DIM);y+=30;actionRow(g,R(1098),y,CONTENT_W-10,27,"Open Terrain Pipeline",CY,"Heightmaps, Gaea, WorldPainter and review metadata",()->openWorkbench("P5"));return y+33;}
        final String vid=v.id();final boolean locked=v.locked();int sea=63; // Minecraft overworld planning reference; terrain assets retain their own authoritative seaLevel metadata.
        text(g,"ELEVATION GUIDES",R(1098),y+7,DIM);y+=22;pair(g,"Profile",v.elevationProfile()==null||v.elevationProfile().isBlank()?"None":"Defined",y);y+=27;
        int e=y;miniAction(g,R(1098),e,96,"Ridge +64",locked?DIM:CY,"Planning metadata only: constant guide at sea level +64",()->{if(locked){say("Unlock the Plan object first");return;}planning("elevation_linear",vid,String.valueOf(sea+64),String.valueOf(sea+64));});miniAction(g,R(1206),e,96,"Valley -32",locked?DIM:CY,"Planning metadata only: constant guide at sea level -32",()->{if(locked){say("Unlock the Plan object first");return;}planning("elevation_linear",vid,String.valueOf(sea-32),String.valueOf(sea-32));});miniAction(g,R(1314),e,94,"Clear",locked?DIM:GOLD,"Remove elevation guidance without touching terrain",()->{if(locked){say("Unlock the Plan object first");return;}planning("elevation_clear",vid,"","");});y+=34;
        text(g,"HYDROLOGY",R(1098),y+7,DIM);y+=22;String role=guideValueClient(v.guideData(),"HYDRO_ROLE=");String next=switch(role){case "SOURCE"->"TRIBUTARY";case "TRIBUTARY"->"MAINSTEM";case "MAINSTEM"->"OUTLET";case "OUTLET"->"INFLOW";case "INFLOW"->"OUTFLOW";default->"SOURCE";};pair(g,"Role",role.isBlank()?"Unassigned":title(role),y);y+=27;var other=otherHydrologyPartner(vid,false);final String oid=other==null?"":other.id();int h=y;miniAction(g,R(1098),h,150,"Role → "+title(next),locked?DIM:CY,"Cycle river/path role metadata",()->{if(locked){say("Unlock the Plan object first");return;}planning("hydrology_meta",vid,"HYDRO_ROLE",next);});miniAction(g,R(1258),h,150,"Set Downstream",locked||oid.isBlank()?DIM:CY,"Link to another Plan object as downstream",()->{if(locked||oid.isBlank()){say("Another Plan path is required");return;}planning("hydrology_meta",vid,"HYDRO_DOWNSTREAM",oid);});y+=34;
        var candidates=OceanCanvasZoneClientCache.candidateEditsFor("PLAN",vid);var revs=OceanCanvasZoneClientCache.designRevisionsFor("PLAN",vid);text(g,"REVISIONS",R(1098),y+7,DIM);y+=22;pair(g,"Candidates / History",candidates.size()+" / "+revs.size(),y);y+=27;
        int c=y;miniAction(g,R(1098),c,150,"Create Candidate",locked?DIM:CY,"Create a non-destructive Candidate from current geometry",()->{if(locked){say("Unlock the Plan object first");return;}workspace("candidate_edit_put","","PLAN",packNewPlanCandidate(v,"",(int)Math.round(mapViewX),(int)Math.round(mapViewZ)));});miniAction(g,R(1258),c,150,"Compare / History",CY,"Open detailed Candidate and Design Revision review",()->openWorkbench("KNOWLEDGE"));y+=34;
        if(!candidates.isEmpty()){var cand=candidates.get(candidates.size()-1);final var cc=cand;int cy=y;if("DRAFT".equals(cc.status()))miniAction(g,R(1098),cy,150,"Approve Latest",CY,"Approve the latest Candidate after review",()->workspace("candidate_edit_put",cc.id(),cc.targetType(),packCandidate(cc,"APPROVED")));if("APPROVED".equals(cc.status()))miniAction(g,R(1098),cy,150,"Promote Latest",GREEN,"Promote approved Candidate to canonical Plan and record revision",()->workspace("candidate_promote_design",cc.id(),"",""));if(!revs.isEmpty()){var rv=revs.get(revs.size()-1);miniAction(g,R(1258),cy,150,"Recover Prior",GOLD,"Recover a historical Before geometry as a new Candidate",()->workspace("design_revision_restore_candidate",rv.id(),"",""));}y+=34;}
        var assets=OceanCanvasZoneClientCache.terrainAssets();text(g,"TERRAIN ASSETS",R(1098),y+7,DIM);y+=22;pair(g,"Assets",String.valueOf(assets.size()),y);y+=27;
        int[] bounds=planWorldBounds(v);int a=y;miniAction(g,R(1098),a,150,"Create Terrain Asset",bounds==null?DIM:CY,"Create a terrain handoff asset from this Plan object's world bounds",()->{if(bounds==null){say("Plan object needs geometry first");return;}planning("terrain_asset_add",vid,v.name()+" Terrain",bounds[0]+","+bounds[1]+","+bounds[2]+","+bounds[3]+","+sea);});miniAction(g,R(1258),a,150,"Open Terrain Pipeline",CY,"Heightmap revisions, Gaea/WorldPainter round trip and review",()->openWorkbench("P5"));y+=34;
        actionRow(g,R(1098),y,CONTENT_W-10,27,"Export Gaea Masks",CY,"Export this Plan layer's vector masks for Gaea",()->planning("export_gaea_masks",p.id(),p.name(),p.id()));
        return y+33;
    }

    private int sliderRectX=-1,sliderRectY=-1,sliderRectW=0,sliderRectH=0; private String sliderGroup=null;
    private void cyclePlan(){
        var groups=OceanCanvasZoneClientCache.planGroups(); if(groups.isEmpty()) return;
        int idx=0; for(int i=0;i<groups.size();i++) if(groups.get(i).id().equals(selectedPlan)) idx=i;
        selectedPlan=groups.get((idx+1)%groups.size()).id(); selectedPlanObject="";
        disclosure=null; armed=null; cancelEdit(); say("Selected plan layer");
    }

    /**
     * OC-F030 next-best-project view. A project with an immediately actionable task (see
     * {@link #blockingTasks}) ranks above one that is not; ties break by fewest open project
     * blockers, then by lowest progress-remaining first (closer to done finishes sooner).
     * COMPLETE/ARCHIVED projects are excluded - there is nothing left to suggest there. This is a
     * heuristic over already-synced fields, not a claim of the single correct project to work on.
     */
    private OceanCanvasZoneClientCache.WorkspaceProject suggestedNextProject(){
        OceanCanvasZoneClientCache.WorkspaceProject best=null; int bestRank=Integer.MAX_VALUE; double bestProgress=-1;
        for(var p:OceanCanvasZoneClientCache.workspaceProjects()){
            String st=p.status()==null?"":p.status().toUpperCase(Locale.ROOT);
            if(st.equals("COMPLETE")||st.equals("ARCHIVED")) continue;
            int openBlockers=0;
            for(var b:OceanCanvasZoneClientCache.workspaceBlockers(p)) if(!b.resolved()) openBlockers++;
            var tasks=new ArrayList<OceanCanvasZoneClientCache.WorkspaceTask>();
            for(var t:OceanCanvasZoneClientCache.workspaceTasks()) if(p.id().equals(t.projectId())) tasks.add(t);
            boolean hasReadyTask=false;
            for(var t:tasks){ if("COMPLETE".equals(t.status())) continue; if(blockingTasks(t,tasks).isEmpty()){ hasReadyTask=true; break; } }
            int rank=(hasReadyTask?0:1)*100+Math.min(openBlockers,99);
            if(best==null||rank<bestRank||(rank==bestRank&&p.progress()>bestProgress)){ best=p; bestRank=rank; bestProgress=p.progress(); }
        }
        return best;
    }

    // ---------------------------------------------------------------- PROJECTS panel
    private int drawProjects(GuiGraphicsExtractor g){
        var p=project();
        if(p==null){
            heading(g,"SELECTED PROJECT");
            emptyState(g,OceanCanvasZoneClientCache.workspaceProjects().isEmpty()?"No projects yet":"No project selected",
                    "A project tracks the phases, milestones","and tasks for one region.");
            actionRow(g,R(CONTENT_X),200,CONTENT_W,32,"⊕   New Project",CY,"Create a project",
                    ()->beginEdit("project.add","New project"));
            if("project.add".equals(editKey)) drawEditBox(g,R(CONTENT_X),240,CONTENT_W,22);
            return 264;
        }
        var phases=OceanCanvasZoneClientCache.workspacePhases(p);
        var milestones=OceanCanvasZoneClientCache.workspaceMilestones(p);
        var blockers=OceanCanvasZoneClientCache.workspaceBlockers(p);
        int totalTaskCount=0,doneTaskCount=0;
        for(var t:OceanCanvasZoneClientCache.workspaceTasks()){
            if(!p.id().equals(t.projectId())) continue;
            totalTaskCount++;
            if("COMPLETE".equals(t.status())||"SKIPPED".equals(t.status())) doneTaskCount++;
        }
        final int tasksTotal=totalTaskCount, tasksDone=doneTaskCount;
        heading(g,"SELECTED PROJECT");
        collapseControl(g);
        if(collapsed) return 104;

        int y=117;
        if("project.name".equals(editKey)){
            drawEditBox(g,R(CONTENT_X),y-6,236,22);
        }else{
            text(g,trim(p.name(),140),R(CONTENT_X),y,BRIGHT);
            icon(g,"edit",R(1200),y-3,CY);
            hit(R(1194),y-8,28,26,"Rename this project",()->beginEdit("project.name",p.name()));
        }
        if(OceanCanvasZoneClientCache.workspaceProjects().size()>1){
            text(g,"▾",R(1236),y,DIM);
            hit(R(1228),y-8,28,26,"Switch project",this::cycleProject);
        }
        boolean active=p.id().equals(OceanCanvasZoneClientCache.activeWorkProject());
        right(g,active?"ACTIVE":"SET ACTIVE",R(CONTENT_RIGHT),y,active?CY:DIM);
        hit(R(1300),y-6,110,22,active?"This is the active project":"Make this the active project",()->{
            if(active){say(p.name()+" is already active");return;}
            workspace("project_active",p.id(),"","");
            say("Active project → "+p.name());
        });
        y+=30;

        // OC-F030: only shown when a different open project would be a better use of the next
        // session - a project with a task ready right now, or otherwise fewer open blockers.
        var suggested=suggestedNextProject();
        if(suggested!=null&&!suggested.id().equals(p.id())){
            final String sid=suggested.id(); final String sname=suggested.name();
            hline(g,R(CONTENT_X),y,CONTENT_W);
            text(g,"Suggested next",R(CONTENT_X),y+10,DIM);
            right(g,trim(sname,220),R(CONTENT_RIGHT),y+10,GREEN);
            hit(R(CONTENT_X),y+2,CONTENT_W,26,"Open "+sname+" — has a ready task or fewer open blockers than "+p.name(),
                    ()->{ selectedProject=sid; disclosure=null; armed=null; cancelEdit(); say("Selected project "+sname); });
            y+=31;
        }

        // Status - cycles through the real ProjectStatus enum
        hline(g,R(CONTENT_X),y,CONTENT_W);
        text(g,"Status",R(CONTENT_X),y+10,DIM);
        String st=title(p.status());
        right(g,st,R(CONTENT_RIGHT),y+10,0xFFE2E2E2);
        text(g,"●",R(CONTENT_RIGHT)-font.width(st)-14,y+10,statusColor(p.status()));
        hit(R(CONTENT_X),y+2,CONTENT_W,26,"Cycle project status",()->{
            String[] all={"PLANNED","ACTIVE","BLOCKED","COMPLETE","ARCHIVED"};
            int idx=0; for(int i=0;i<all.length;i++) if(all[i].equalsIgnoreCase(p.status())) idx=i;
            String next=all[(idx+1)%all.length];
            workspace("project_status",p.id(),next,"");
            say("Status → "+title(next));
        });
        y+=31;

        // Progress - the server's own value, not a constant
        int pct=(int)Math.round(Math.max(0.0,Math.min(1.0,p.progress()))*100);
        pair(g,"Progress",pct+"%",y);
        y+=31;
        fill(g,R(CONTENT_X),y,CONTENT_W,3,0xFF1C1F21);
        fill(g,R(CONTENT_X),y,(int)Math.round(CONTENT_W*(pct/100.0)),3,CY);
        y+=12;

        String activePhase="—";
        for(var ph:phases) if(!ph.complete()){ activePhase=ph.name(); break; }
        if(activePhase.equals("—")&&!phases.isEmpty()) activePhase="All phases complete";
        pair(g,"Active Phase",activePhase,y);
        y+=31;

        // Region is the project's real anchor - the reference's Target/Start Date rows have no
        // backing store on WorkProject, so they are not rendered rather than invented.
        String regionName=p.regionName()==null||p.regionName().isBlank()?"Unassigned":p.regionName();
        pair(g,"Region",regionName,y);
        hit(R(CONTENT_X),y+2,CONTENT_W,26,p.regionName()==null||p.regionName().isBlank()
                ?"This project has no region yet":"Show "+p.regionName()+" on the map",()->{
            for(var z:zones()) if(z.name().equalsIgnoreCase(p.regionName())){
                selectedRegion=z.name(); tab="REGIONS";
                mapViewX=(z.minX()+z.maxX())/2.0; mapViewZ=(z.minZ()+z.maxZ())/2.0;
                say("Showing "+z.name()); return;
            }
            say("No matching region");
        });
        y+=31;

        disclosureRow(g,"Phases",phases.size()+"",y,"project.phases","Phase list and status");
        y+=31;
        if("project.phases".equals(disclosure)) y=drawPhaseList(g,p,phases,y);

        disclosureRow(g,"Milestones",milestones.size()+"",y,"project.milestones","Milestone list and status");
        y+=31;
        if("project.milestones".equals(disclosure)) y=drawMilestoneList(g,p,milestones,y);

        disclosureRow(g,"Tasks",tasksDone+" / "+tasksTotal,y,"project.tasks","Tasks linked to this project");
        y+=31;
        if("project.tasks".equals(disclosure)) y=drawTaskList(g,p,y);

        int openBlockers=0; for(var b:blockers) if(!b.resolved()) openBlockers++;
        disclosureRow(g,"Blockers",openBlockers==0?"None":openBlockers+" open",y,"project.blockers","Open blockers on this project");
        y+=31;
        if("project.blockers".equals(disclosure)) y=drawBlockerList(g,p,blockers,y);

        y+=15;
        text(g,"ACTIONS",R(CONTENT_X),y,CY);
        y+=23;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,"⊕   Add Phase",CY,"Append a phase to this project",
                ()->beginEdit("project.phase_add","New phase"));
        y+=40;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,"⊕   Add Milestone",CY,"Append a milestone to this project",
                ()->beginEdit("project.milestone_add","New milestone"));
        y+=40;
        actionRow(g,R(CONTENT_X),y,CONTENT_W,32,"⚙   Project Settings",CY,"Notes, export and delete",
                ()->{disclosure="project.settings".equals(disclosure)?null:"project.settings";});
        y+=40;
        if(editKey!=null&&editKey.startsWith("project.")&&!editKey.equals("project.name")){
            drawEditBox(g,R(CONTENT_X),y-6,CONTENT_W,22);
            y+=28;
        }
        if("project.settings".equals(disclosure)){
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Notes",R(1098),y+10,0xFFC9C9C9);
            right(g,p.notes()==null||p.notes().isBlank()?"None":trim(p.notes(),150),R(CONTENT_RIGHT),y+10,DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Edit project notes",()->beginEdit("project.notes",p.notes()==null?"":p.notes()));
            y+=26;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Export .oceanproject",R(1098),y+10,0xFFC9C9C9);
            text(g,"›",R(1397),y+10,DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Write this project out as a portable package",
                    ()->{workspace("project_export",p.id(),"","");say("Exporting "+p.name()+"…");});
            y+=26;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Pipeline checkpoint",R(1098),y+10,0xFFC9C9C9);
            text(g,"›",R(1397),y+10,DIM);
            hit(R(1098),y+2,CONTENT_W-10,24,"Record a pipeline snapshot of this project's current state",
                    ()->{workspace("project_pipeline_snapshot",p.id(),"Checkpoint","");say("Pipeline checkpoint saved");});
            y+=26;
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,"Forever-World Workbench",R(1098),y+10,0xFFC9C9C9);text(g,"›",R(1397),y+10,CY);
            hit(R(1098),y+2,CONTENT_W-10,24,"Prototype plots, transitions, scenarios, atlas routes and stewardship",()->openWorkbench("FOREVER"));
            y+=26;
            boolean delArmed=isArmed("project_delete:"+p.id());
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,delArmed?"Confirm delete project":"Delete project",R(1098),y+10,delArmed?RED:GOLD);
            hit(R(1098),y+2,CONTENT_W-10,24,withReversibility("Delete this project. Tasks and plans are not deleted.","project_delete"),()->{
                if(confirm("project_delete:"+p.id(),"Click Delete project again to confirm")){
                    workspace("project_delete",p.id(),"","");
                    selectedProject=""; autoSelected=false; disclosure=null; say("Deleted "+p.name());
                }
            });
            y+=26;
        }
        return y;
    }
    private int drawPhaseList(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.WorkspaceProject p,
                              List<OceanCanvasZoneClientCache.WorkspacePhase> phases,int y){
        if(phases.isEmpty()){ text(g,"No phases yet - use Add Phase.",R(1098),y+6,DIM); return y+26; }
        int off=pageOffset(phases.size(),PROJECT_LIST_WINDOW);
        for(int i=off;i<Math.min(off+PROJECT_LIST_WINDOW,phases.size());i++){
            var ph=phases.get(i);
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,trim((i+1)+". "+ph.name(),190),R(1098),y+10,0xFFC9C9C9);
            right(g,title(ph.status()),R(CONTENT_RIGHT),y+10,statusColor(ph.status()));
            final String phaseId=ph.id(); final String next=nextStatus(ph.status());
            hit(R(1098),y+2,CONTENT_W-10,24,ph.name()+": "+title(ph.status())+" → "+title(next),()->{
                workspace("project_phase_status",p.id(),phaseId,next);
                say(ph.name()+" → "+title(next));
            });
            y+=26;
        }
        y=pageFooter(g,y,phases.size(),PROJECT_LIST_WINDOW);
        return y+10;
    }
    private int drawMilestoneList(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.WorkspaceProject p,
                                  List<OceanCanvasZoneClientCache.WorkspaceMilestone> ms,int y){
        if(ms.isEmpty()){ text(g,"No milestones yet - use Add Milestone.",R(1098),y+6,DIM); return y+26; }
        int off=pageOffset(ms.size(),PROJECT_LIST_WINDOW);
        for(int i=off;i<Math.min(off+PROJECT_LIST_WINDOW,ms.size());i++){
            var m=ms.get(i);
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,trim(m.name(),170),R(1098),y+10,0xFFC9C9C9);
            String tail=m.targetDate()==null||m.targetDate().isBlank()?title(m.status()):title(m.status())+"  ·  "+m.targetDate();
            right(g,trim(tail,140),R(CONTENT_RIGHT),y+10,statusColor(m.status()));
            final String mid=m.id(); final String next=nextStatus(m.status());
            hit(R(1098),y+2,CONTENT_W-10,24,m.name()+": "+title(m.status())+" → "+title(next),()->{
                workspace("project_milestone_status",p.id(),mid,next);
                say(m.name()+" → "+title(next));
            });
            y+=26;
        }
        y=pageFooter(g,y,ms.size(),PROJECT_LIST_WINDOW);
        return y+10;
    }
    /**
     * OC-F027/F091 dependency-aware next-actionable surfacing. A task is READY when it is not
     * already COMPLETE and every id in its `dependencies` either does not resolve to a sibling
     * task (stale reference - cannot block) or resolves to one that is COMPLETE. SKIPPED
     * deliberately does not resolve a dependency: nothing in the stored data distinguishes a
     * permanent skip from a temporary one, and treating it as done would be invented semantics.
     * Reads only `task_dependencies`/`status`, both already synced - no new server data.
     */
    private List<OceanCanvasZoneClientCache.WorkspaceTask> blockingTasks(
            OceanCanvasZoneClientCache.WorkspaceTask t,List<OceanCanvasZoneClientCache.WorkspaceTask> siblings){
        String deps=t.dependencies();
        if(deps==null||deps.isBlank()) return List.of();
        var out=new ArrayList<OceanCanvasZoneClientCache.WorkspaceTask>();
        for(String d:deps.split(",")){
            String id=d.trim(); if(id.isEmpty()) continue;
            for(var o:siblings) if(o.id().equals(id)){ if(!"COMPLETE".equals(o.status())) out.add(o); break; }
        }
        return out;
    }
    private boolean taskMatchesProjectFilter(OceanCanvasZoneClientCache.WorkspaceTask t,List<OceanCanvasZoneClientCache.WorkspaceTask> siblings){
        String st=t.status()==null?"PLANNED":t.status().toUpperCase(Locale.ROOT);
        boolean done=st.equals("COMPLETE")||st.equals("SKIPPED");
        boolean blocked=st.equals("BLOCKED")||!blockingTasks(t,siblings).isEmpty();
        return switch(projectFilter){
            case "READY" -> !done&&!blocked;
            case "BLOCKED" -> !done&&blocked;
            case "ATTENTION" -> !done&&(blocked||t.priority()>=3);
            default -> true;
        };
    }
    private int drawTaskList(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.WorkspaceProject p,int y){
        ArrayList<OceanCanvasZoneClientCache.WorkspaceTask> tasks=new ArrayList<>();
        for(var t:OceanCanvasZoneClientCache.workspaceTasks()) if(p.id().equals(t.projectId())) tasks.add(t);
        int fy=y;
        String[] filters={"ALL","READY","BLOCKED","ATTENTION"};String[] labels={"All","Ready","Blocked","Attention"};
        for(int i=0;i<filters.length;i++){final String f=filters[i];int bx=R(1098)+i*78;miniAction(g,bx,fy,72,labels[i],projectFilter.equals(f)?CY:DIM,"Filter project tasks: "+labels[i],()->{projectFilter=f;listPage=0;});}
        y+=31;
        ArrayList<OceanCanvasZoneClientCache.WorkspaceTask> shown=new ArrayList<>();for(var t:tasks)if(taskMatchesProjectFilter(t,tasks))shown.add(t);
        if(shown.isEmpty()){
            text(g,tasks.isEmpty()?"No tasks yet.":"No tasks match "+title(projectFilter)+".",R(1098),y+6,DIM);
            y+=24;
        }else{
            OceanCanvasZoneClientCache.WorkspaceTask nextUp=null;
            for(var t:tasks){
                String st=t.status()==null?"":t.status().toUpperCase(Locale.ROOT);
                if(st.equals("COMPLETE")||st.equals("SKIPPED")||st.equals("BLOCKED")) continue;
                if(!blockingTasks(t,tasks).isEmpty()) continue;
                if(nextUp==null||t.priority()>nextUp.priority()) nextUp=t;
            }
            if(nextUp!=null&&("ALL".equals(projectFilter)||"READY".equals(projectFilter))){
                final var nt=nextUp;final String nid=nt.id();
                hline(g,R(1098),y,CONTENT_W-10);
                text(g,"▶ Next up",R(1098),y+10,GREEN);
                right(g,trim(nt.title(),220),R(CONTENT_RIGHT),y+10,GREEN);
                hit(R(1098),y+2,CONTENT_W-10,24,"Show "+nt.title()+" on the map — all dependencies are complete",
                        ()->{selectedTask=nid; openWorkbench("TASKS");mapViewX=nt.x();mapViewZ=nt.z();say("Showing next task: "+nt.title());});
                y+=26;
            }
            int off=pageOffset(shown.size(),PROJECT_LIST_WINDOW);
            for(int i=off;i<Math.min(off+PROJECT_LIST_WINDOW,shown.size());i++){
                var t=shown.get(i);
                hline(g,R(1098),y,CONTENT_W-10);
                text(g,trim(t.title(),160),R(1098),y+10,0xFFC9C9C9);
                var blockers=blockingTasks(t,tasks);
                boolean blocked=(!blockers.isEmpty()||"BLOCKED".equalsIgnoreCase(t.status()))&&!"COMPLETE".equalsIgnoreCase(t.status());
                String statusLabel=blocked?"⛔ "+title(t.status()):title(t.status());
                right(g,statusLabel,R(CONTENT_RIGHT),y+10,blocked?GOLD:statusColor(t.status()));
                final var tt=t;final String tid=t.id(); final String next=nextStatus(t.status());
                String rowTip=t.title()+": "+title(t.status())+" → "+title(next);
                if(blocked){StringBuilder names=new StringBuilder();for(var b:blockers){if(names.length()>0)names.append(", ");names.append(b.title());}if(names.length()>0) rowTip+="  (blocked by "+names+")";}
                hit(R(1098),y+2,CONTENT_W-142,24,rowTip,()->{workspace("task_status",tid,next,"");say(tt.title()+" → "+title(next));});
                text(g,"◎",R(CONTENT_RIGHT)-118,y+10,CY);
                hit(R(CONTENT_RIGHT)-126,y+2,22,24,"Center map on "+t.title(),()->{selectedTask=tid;mapViewX=tt.x();mapViewZ=tt.z();say("Showing "+tt.title());});
                text(g,"✎",R(CONTENT_RIGHT)-92,y+10,CY);
                hit(R(CONTENT_RIGHT)-100,y+2,22,24,"Edit "+t.title()+" in the expanded task workspace",()->{selectedTask=tid;openWorkbench("TASKS");});
                y+=26;
            }
            y=pageFooter(g,y,shown.size(),PROJECT_LIST_WINDOW);
        }
        hline(g,R(1098),y,CONTENT_W-10);
        text(g,"⊕  Add task at map centre",R(1098),y+10,CY);
        hit(R(1098),y+2,CONTENT_W-10,24,"Create a task at the current map centre and link it to this project",()->{
            int x=(int)Math.round(mapViewX),z=(int)Math.round(mapViewZ);String at=x+","+z;
            workspace("task_add","","Task",at);pendingLink=new PendingLink("task_project",at,p.id(),System.currentTimeMillis()+5000L);say("Task added at "+at);
        });
        return y+34;
    }

    private int drawBlockerList(GuiGraphicsExtractor g,OceanCanvasZoneClientCache.WorkspaceProject p,
                                List<OceanCanvasZoneClientCache.WorkspaceBlocker> bs,int y){
        if(bs.isEmpty()){
            text(g,"No blockers recorded.",R(1098),y+6,DIM);
            hit(R(1098),y,CONTENT_W-10,22,"Record a blocker on this project",()->beginEdit("project.blocker_add","Blocked by "));
            return y+26;
        }
        int off=pageOffset(bs.size(),6);
        for(int i=off;i<Math.min(off+6,bs.size());i++){
            var b=bs.get(i);
            hline(g,R(1098),y,CONTENT_W-10);
            text(g,trim(b.text(),200),R(1098),y+10,b.resolved()?DIM:0xFFC9C9C9);
            right(g,b.resolved()?"Resolved":"Open",R(CONTENT_RIGHT),y+10,b.resolved()?DIM:RED);
            final String bid=b.id();
            hit(R(1098),y+2,CONTENT_W-10,24,(b.resolved()?"Reopen":"Resolve")+": "+b.text(),()->{
                workspace("project_blocker_toggle",p.id(),bid,"");
                say(b.resolved()?"Blocker reopened":"Blocker resolved");
            });
            y+=26;
        }
        y=pageFooter(g,y,bs.size(),6);
        return y+10;
    }
    /**
     * Footer for a windowed list. There is no scissor-clipped scroll region in this layout, so a
     * long list pages rather than silently hiding its tail behind a dead "+N more" label.
     */
    private int pageFooter(GuiGraphicsExtractor g,int y,int total,int window){
        if(total<=window) return y;
        int pages=(total+window-1)/window, page=Math.min(listPage,pages-1);
        hline(g,R(1098),y,CONTENT_W-10);
        text(g,"Page "+(page+1)+" of "+pages,R(1098),y+8,DIM);
        right(g,"Show more ›",R(CONTENT_RIGHT),y+8,CY);
        hit(R(1098),y,CONTENT_W-10,22,"Show the next "+window+" of "+total,()->listPage=(listPage+1)%pages);
        return y+24;
    }
    /** First index of the visible window for a list of {@code total} entries. */
    private int pageOffset(int total,int window){
        if(total<=window) return 0;
        int pages=(total+window-1)/window;
        return Math.min(listPage,pages-1)*window;
    }
    private static String nextStatus(String s){
        String v=s==null?"PLANNED":s.toUpperCase(Locale.ROOT);
        return switch(v){ case "PLANNED"->"ACTIVE"; case "ACTIVE"->"COMPLETE"; case "COMPLETE"->"SKIPPED"; default->"PLANNED"; };
    }
    private void cycleProject(){
        var ps=OceanCanvasZoneClientCache.workspaceProjects(); if(ps.isEmpty()) return;
        int idx=0; for(int i=0;i<ps.size();i++) if(ps.get(i).id().equals(selectedProject)) idx=i;
        selectedProject=ps.get((idx+1)%ps.size()).id();
        disclosure=null; armed=null; cancelEdit();
        var next=project(); say("Selected project "+(next==null?"":next.name()));
    }

    // ---------------------------------------------------------------- status / overlays
    private String operationLabel(String kind){
        String k=kind==null?"":kind.toLowerCase(Locale.ROOT);
        if(k.contains("restore"))return "Restore";
        if(k.contains("rewipe")||k.contains("reset"))return "Rewipe";
        if(k.contains("expand"))return "Expand";
        return "Pregen";
    }
    /**
     * The operations surface.
     *
     * <p>The bottom-centre card is where the HTML reference already put running-job state, so it
     * is also where the rest of the operational truth belongs. Collapsed it is the reference card
     * (or, when idle, a compact chip in the same slot); expanded it carries the live Pregen
     * telemetry, the Pregen queue and the health report.</p>
     *
     * <p>Every number here was previously only observable by reading a server log after the fact.
     * The server already broadcasts {@code OceanCanvasPregenTelemetryPayload} to every player on a
     * fixed tick interval, so this costs no new traffic - it was simply never displayed.</p>
     */
    private void drawOperations(GuiGraphicsExtractor g){
        if(opsOpen) drawOperationsPanel(g); else drawOperationsCard(g);
    }

    private void drawOperationsCard(GuiGraphicsExtractor g){
        var job=OceanCanvasZoneClientCache.job();
        var perf=OceanCanvasZoneClientCache.performance();
        int w=404,x=(DESIGN_W-w)/2;
        if(job==null){
            // Idle chip. The reference only draws this slot during a job; keeping a compact
            // resting state here is what makes the operations data reachable when nothing is
            // running, without inventing a fifth tab the reference does not have.
            int h=30,y=DESIGN_H-34-h;
            fill(g,x,y,w,h,0xE6000000); border(g,x,y,w,h,BORDER);
            icon(g,"queue",x+11,y+7,CY);
            text(g,"OPERATIONS",x+34,y+11,CY);
            String rest=perf.active()?title(perf.phase()):"Idle";
            right(g,rest+"   ⌃",x+w-12,y+11,DIM);
            hit(x,y,w,h,"Open Pregen telemetry, queue and health",()->{opsOpen=true;});
            return;
        }
        long total=Math.max(1L,job.totalChunks()),done=Math.max(0L,Math.min(total,job.submittedChunks()));
        int pct=(int)Math.round(done*100.0/total);
        int h=56,y=DESIGN_H-34-h;
        fill(g,x,y,w,h,0xF0000000);border(g,x,y,w,h,BORDER);
        blocker(x,y,w,h);
        String label=operationLabel(job.kind());
        text(g,label+":",x+13,y+12,DIM);
        // Name the region the job is actually inside, rather than assuming the selected one.
        text(g,trim(jobRegionName(job),140),x+67,y+12,0xFFE6E6E6);
        text(g,pct+"%",x+229,y+12,CY);
        text(g,"("+fmt(done)+" / "+fmt(total)+" chunks)",x+255,y+12,DIM);
        fill(g,x+13,y+32,w-26,4,0xFF1C1F21);
        fill(g,x+13,y+32,(int)Math.round((w-26)*(done/(double)total)),4,CY);
        String foot="Working at chunk "+job.cursorChunkX()+", "+job.cursorChunkZ();
        if(perf.active()&&perf.chunksPerSecond()>0)
            foot+="   ·   "+String.format(Locale.US,"%.1f chunks/s",perf.chunksPerSecond());
        text(g,trim(foot,300),x+13,y+41,DIM);
        hit(x,y,w,h,"Open Pregen telemetry, queue and health",()->{opsOpen=true;});
        // Recentre stays available without leaving the collapsed card; registered after the
        // card-wide hit so the smaller target wins.
        icon(g,"waypoint",x+w-26,y+8,CY);
        hit(x+w-32,y+2,30,26,"Centre the map on the working chunk",()->{
            mapViewX=job.cursorChunkX()*16.0+8; mapViewZ=job.cursorChunkZ()*16.0+8;
            say("Following "+label.toLowerCase(Locale.ROOT));
        });
        text(g,"⌃",x+w-14,y+40,DIM);
    }

    // Sized for the tallest tab (Diagnostics with both the stale banner and a reason line).
    private static final int OPS_W=560, OPS_H=360;
    private int opsX(){return (DESIGN_W-OPS_W)/2;}
    private int opsY(){return DESIGN_H-34-OPS_H;}

    private void drawOperationsPanel(GuiGraphicsExtractor g){
        int x=opsX(),y=opsY();
        fill(g,x,y,OPS_W,OPS_H,0xF2000000); border(g,x,y,OPS_W,OPS_H,CY);
        blocker(x,y,OPS_W,OPS_H);
        text(g,"OPERATIONS",x+14,y+13,CY);
        icon(g,"close",x+OPS_W-28,y+10,DIM);
        hit(x+OPS_W-34,y+4,30,30,"Close operations",()->{opsOpen=false;});

        String[] tabs={"DIAGNOSTICS","INVARIANTS","STATES","SIGNALS","QUEUE","HEALTH"};
        int tx=x+78;
        for(String t:tabs){
            int tw=font.width(t)+22; boolean on=t.equals(opsTab);
            fill(g,tx,y+8,tw,24,on?0x171FC4EF:0xFF07090B); border(g,tx,y+8,tw,24,on?CY:BORDER);
            center(g,t,tx,y+16,tw,on?CY:0xFFB9BEC2);
            final String target=t;
            hit(tx,y+8,tw,24,opsTabTip(t),()->opsTab=target);
            tx+=tw+6;
        }
        hline(g,x+14,y+40,OPS_W-28);
        switch(opsTab){
            case "QUEUE" -> drawOpsQueue(g,x,y+50);
            case "HEALTH" -> drawOpsHealth(g,x,y+50);
            case "INVARIANTS" -> drawOpsInvariants(g,x,y+50);
            case "STATES" -> drawOpsStates(g,x,y+50);
            case "SIGNALS" -> drawOpsSignals(g,x,y+50);
            default -> drawOpsDiagnostics(g,x,y+50);
        }
    }
    /** OC-F043 / OC-F112. Verdicts and evidence come from OceanCanvasDiagnosticModel. */
    private void drawOpsInvariants(GuiGraphicsExtractor g,int x,int y){
        var invariants=OceanCanvasDiagnosticModel.invariants();
        int pass=0,warn=0,fail=0,unobservable=0;
        for(var i:invariants) switch(i.verdict()){
            case OceanCanvasDiagnosticModel.PASS -> pass++;
            case OceanCanvasDiagnosticModel.WARN -> warn++;
            case OceanCanvasDiagnosticModel.FAIL -> fail++;
            case OceanCanvasDiagnosticModel.NOT_OBSERVABLE -> unobservable++;
            default -> { }
        }
        text(g,pass+" pass · "+warn+" warn · "+fail+" fail · "+unobservable+" not observable",
                x+16,y,fail>0?RED:warn>0?GOLD:GREEN);
        y+=20;
        for(var i:invariants){
            int c=switch(i.verdict()){
                case OceanCanvasDiagnosticModel.PASS -> GREEN;
                case OceanCanvasDiagnosticModel.WARN -> GOLD;
                case OceanCanvasDiagnosticModel.FAIL -> RED;
                case OceanCanvasDiagnosticModel.NOT_OBSERVABLE -> DIM;
                default -> DIM; };
            text(g,i.verdict(),x+16,y,c);
            text(g,trim(i.statement(),OPS_W-160),x+16+96,y,
                    OceanCanvasDiagnosticModel.NOT_OBSERVABLE.equals(i.verdict())?DIM:TEXT);
            hit(x+14,y-3,OPS_W-28,17,i.evidence()+"  ·  Owner: "+i.owner(),()->{});
            y+=17;
            if(y>opsY()+OPS_H-24) break;
        }
    }

    /** OC-F225. Shared legal state machine; current phase is derived only from broadcast controller evidence. */
    private void drawOpsStates(GuiGraphicsExtractor g,int x,int y){
        var job=OceanCanvasZoneClientCache.job();
        String machineId="PREGEN", current="IDLE", evidence="No operation is currently broadcast.";
        if(job!=null){
            String kind=job.kind()==null?"":job.kind().toLowerCase(Locale.ROOT);
            machineId=kind.contains("restore")?"RESTORE":(kind.contains("rewipe")||kind.contains("reset"))?"REWIPE":"PREGEN";
            if("PREGEN".equals(machineId)){
                var p=OceanCanvasZoneClientCache.performance();
                current=p.active()&&!p.phase().isBlank()?p.phase().toUpperCase(Locale.ROOT):job.submittedChunks()<=0?"PREPARING":job.submittedChunks()<job.totalChunks()?"FEEDING":"FINAL_DRAIN";
            } else if("RESTORE".equals(machineId)) current=job.submittedChunks()<=0?"PREPARING":job.submittedChunks()<job.totalChunks()?"COPYING":"VERIFYING";
            else current=job.submittedChunks()<=0?"PREPARING":job.submittedChunks()<job.totalChunks()?"FEEDING":"FINAL_DRAIN";
            evidence="Broadcast "+job.kind()+" · "+fmt(job.submittedChunks())+" / "+fmt(job.totalChunks())+" chunks.";
        }
        var machine=net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.stateMachines().get(machineId);
        text(g,"Operation state machine",x+16,y,DIM); right(g,machine.displayName(),x+OPS_W-18,y,CY); y+=20;
        text(g,"CURRENT",x+16,y,DIM); text(g,current,x+86,y,job==null?DIM:CY); y+=16;
        text(g,trim(evidence,OPS_W-40),x+16,y,DIM); y+=22;
        var next=new java.util.ArrayList<>(machine.legalNext(current)); java.util.Collections.sort(next);
        text(g,"Legal next",x+16,y,DIM); text(g,next.isEmpty()?"—":String.join(" · ",next),x+86,y,next.isEmpty()?DIM:0xFFE2E2E2); y+=22;
        hline(g,x+16,y-5,OPS_W-32); text(g,"Declared transitions",x+16,y,DIM); y+=18;
        for(var t:machine.transitions()){
            boolean from=t.from().equals(current);
            int c=from?CY:0xFFB9BEC2;
            text(g,(from?"› ":"  ")+t.from()+" → "+t.to(),x+20,y,c);
            text(g,trim(t.reason(),250),x+280,y,from?0xFFE2E2E2:DIM);
            y+=15;if(y>opsY()+OPS_H-22)break;
        }
    }

    /** OC-F044 / OC-F113 / OC-F207. */
    private void drawOpsSignals(GuiGraphicsExtractor g,int x,int y){
        var risk=OceanCanvasDiagnosticModel.stallRisk();
        int rc=switch(risk.band()){case "HIGH"->RED;case "ELEVATED"->GOLD;case "LOW"->GREEN;default->DIM;};
        text(g,"Stall risk",x+16,y,DIM);
        right(g,risk.band()+"  "+risk.score()+"/100",x+OPS_W-18,y,rc);
        y+=16;
        int barW=OPS_W-36;
        fill(g,x+16,y,barW,6,0xFF14181B);
        fill(g,x+16,y,Math.max(1,barW*risk.score()/100),6,rc);
        hit(x+14,y-20,OPS_W-28,28,"A weighted heuristic over broadcast telemetry, not a prediction",()->{});
        y+=14;
        for(String c:risk.contributors()){ text(g,trim("• "+c,OPS_W-40),x+22,y,DIM); y+=15; }
        y+=6; hline(g,x+16,y,OPS_W-32); y+=8;

        var signatures=OceanCanvasDiagnosticModel.matchedSignatures();
        text(g,"Recognised signatures",x+16,y,DIM);
        right(g,signatures.isEmpty()?"none matched":signatures.size()+" matched",x+OPS_W-18,y,
                signatures.isEmpty()?GREEN:GOLD);
        y+=18;
        if(signatures.isEmpty()){
            text(g,"No known failure shape matches this run.",x+22,y,DIM); y+=15;
            text(g,"That is not a guarantee of health — only that nothing on file matches.",x+22,y,DIM); y+=15;
        } else for(var sig:signatures){
            text(g,trim(sig.name(),OPS_W-40),x+22,y,GOLD); y+=15;
            for(String line:wrap(sig.meaning(),OPS_W-56)){ text(g,line,x+30,y,DIM); y+=14; }
            text(g,trim("Likely: "+sig.subsystem(),OPS_W-56),x+30,y,0xFFB9BEC2); y+=14;
            for(String line:wrap("First evidence to capture: "+sig.firstEvidence(),OPS_W-56)){ text(g,line,x+30,y,DIM); y+=14; }
            y+=4;
            if(y>opsY()+OPS_H-70) break;
        }
        y+=2; hline(g,x+16,y,OPS_W-32); y+=8;
        var abort=OceanCanvasDiagnosticModel.abortSafety();
        text(g,"If cancelled now",x+16,y,DIM);
        right(g,abort.verdict(),x+OPS_W-18,y,"CLEAN CHECKPOINT".equals(abort.verdict())?GREEN:
                "NO OPERATION".equals(abort.verdict())?DIM:GOLD);
        y+=16;
        for(String line:wrap(abort.detail(),OPS_W-44)){ text(g,line,x+22,y,DIM); y+=14; }
        for(String r:abort.remaining()){ text(g,trim("• "+r,OPS_W-44),x+22,y,GOLD); y+=14; }
    }

    private String opsTabTip(String t){return switch(t){
        case "QUEUE" -> "The server-side Pregen queue: what is waiting, running or blocked";
        case "HEALTH" -> "Chunk-state and workflow-link integrity scan";
        case "INVARIANTS" -> "Core invariants with a verdict, the evidence behind it, and what is not observable";
        case "STATES" -> "Legal Pregen, Rewipe and Restore states/transitions, with the current observable controller phase";
        case "SIGNALS" -> "Stall risk, recognised failure signatures, and what cancelling now would leave";
        default -> "Live Pregen controller telemetry"; };}

    /** Two-column key/value row inside the operations panel. */
    private int opsRow(GuiGraphicsExtractor g,int x,int y,String a,String av,String b,String bv,int avCol,int bvCol){
        int half=(OPS_W-40)/2;
        text(g,a,x+16,y,DIM); right(g,trim(av,half-font.width(a)-14),x+16+half-8,y,avCol);
        if(b!=null){ text(g,b,x+24+half,y,DIM); right(g,trim(bv,half-font.width(b)-14),x+OPS_W-16,y,bvCol); }
        return y+22;
    }
    private static String secs(long s){
        if(s<0) return "—";
        if(s<60) return s+"s";
        if(s<3600) return (s/60)+"m "+(s%60)+"s";
        return (s/3600)+"h "+((s%3600)/60)+"m";
    }
    private void drawOpsDiagnostics(GuiGraphicsExtractor g,int x,int y){
        var p=OceanCanvasZoneClientCache.performance();
        boolean stale=OceanCanvasZoneClientCache.performanceStale();
        if(stale){
            text(g,"Telemetry is stale — the server has not sent an update in over 5 seconds.",x+16,y,GOLD);
            y+=22;
        }
        int phaseY=y;
        y=opsRow(g,x,y,"Phase",title(p.phase()),"Profile",p.profile().isBlank()?"—":prettyId(p.profile()),
                p.active()?CY:DIM,0xFFE2E2E2);
        explainMark(g,x+16+font.width("Phase")+6,phaseY,"phase",p.phase());
        if(p.reason()!=null&&!p.reason().isBlank()){
            String reasonText=trim(p.reason(),OPS_W-52);
            text(g,reasonText,x+16,y,0xFFB9BEC2);
            explainMark(g,x+16+font.width(reasonText)+8,y,"reason",p.reason());
            y+=22;
        }
        hline(g,x+14,y-4,OPS_W-28);
        y=opsRow(g,x,y+4,"Handled",fmt(p.handled())+" / "+fmt(p.total()),
                "Throughput",String.format(Locale.US,"%.2f chunks/s",p.chunksPerSecond()),
                0xFFE2E2E2,p.chunksPerSecond()>0?GREEN:(p.active()?RED:DIM));
        y=opsRow(g,x,y,"Skipped",fmt(p.skipped()),"Settled",fmt(p.settled()),0xFFE2E2E2,0xFFE2E2E2);
        hline(g,x+14,y-4,OPS_W-28);
        y=opsRow(g,x,y+4,"Admission rate",p.rate()+" / "+p.rateCap()+" per tick",
                "Outstanding",fmt(p.outstanding()),0xFFE2E2E2,0xFFE2E2E2);
        y=opsRow(g,x,y,"Queued",fmt(p.queued()),"Shared queue",fmt(p.sharedQueue()),0xFFE2E2E2,0xFFE2E2E2);
        y=opsRow(g,x,y,"Final-drain tickets",fmt(p.finalDrainTickets()),null,null,0xFFE2E2E2,0xFFE2E2E2);
        hline(g,x+14,y-4,OPS_W-28);
        // Negative tick figures are the encoder's "not measured" sentinel, not a real zero.
        String work=p.tickWorkMs()<0?"—":String.format(Locale.US,"%.1f ms",p.tickWorkMs());
        String iv=p.tickIntervalMs()<0?"—":String.format(Locale.US,"%.1f ms",p.tickIntervalMs());
        y=opsRow(g,x,y+4,"Tick work",work,"Tick interval",iv,
                p.tickWorkMs()>40?GOLD:0xFFE2E2E2,0xFFE2E2E2);
        String heap=p.heapMaxMiB()<=0?"—":fmt(p.heapUsedMiB())+" / "+fmt(p.heapMaxMiB())+" MiB ("
                +(int)Math.round(p.heapFraction()*100)+"%)";
        y=opsRow(g,x,y,"Heap",heap,"Elapsed",secs(p.elapsedSeconds()),
                p.heapFraction()>0.85?RED:p.heapFraction()>0.7?GOLD:0xFFE2E2E2,0xFFE2E2E2);
        String eta=p.etaLow()<0||p.etaHigh()<0?"—":secs(p.etaLow())+" – "+secs(p.etaHigh());
        y=opsRow(g,x,y,"No progress for",secs(p.noProgressSeconds()),"ETA",eta,
                p.noProgressSeconds()>30?RED:0xFFE2E2E2,0xFFE2E2E2);
        int etaY=y;
        y=opsRow(g,x,y,"ETA confidence",title(p.etaQuality()),null,null,DIM,DIM);
        explainMark(g,x+16+font.width("ETA confidence")+6,etaY,"eta",p.etaQuality());
        if(!p.active()){
            text(g,"No Pregen job is running. Values above are the last reported state.",x+16,y+4,DIM);
        }
    }
    private void drawOpsQueue(GuiGraphicsExtractor g,int x,int y){
        var q=OceanCanvasZoneClientCache.queue();
        text(g,trim(q.message(),OPS_W-150),x+16,y,0xFFC9C9C9);
        right(g,q.readOnly()?"READ-ONLY":q.armed()?"ARMED":"IDLE",x+OPS_W-16,y,q.armed()?CY:DIM);
        y+=24;
        int bw=(OPS_W-44)/3;
        actionRow(g,x+16,y,bw,26,"▶   Run queue",q.readOnly()?DIM:CY,
                q.readOnly()?"The queue is read-only right now"
                        :"Start or resume the queued Pregen entries (confirms revision "+q.revision()+")",
                ()->{
                    if(q.readOnly()){say("Queue is read-only");return;}
                    if(q.items().isEmpty()){say("The queue is empty");return;}
                    // The server requires Run to echo the revision the client is looking at, so a
                    // queue reordered since this view was rendered cannot be started by accident.
                    workspace("queue_run","",String.valueOf(q.revision()),"");
                    say("Run queue requested");
                });
        actionRow(g,x+22+bw,y,bw,26,"⏸   Pause",PURPLE,"Stop starting new entries after the current one",
                ()->{workspace("queue_pause","","","");say("Queue paused");});
        actionRow(g,x+28+bw*2,y,bw,26,"✕   Stop",GOLD,"Interrupt the running entry and pause the queue",
                ()->{ if(confirm("queue_stop","Click Stop again to confirm")){workspace("queue_stop","","","");say("Queue stopped");} });
        y+=34;
        hline(g,x+14,y,OPS_W-28); y+=8;
        var items=q.items();
        if(items.isEmpty()){
            text(g,"The Pregen queue is empty. Queue a region from its Operations section.",x+16,y+4,DIM);
        }
        int shown=Math.min(5,items.size());
        for(int i=0;i<shown;i++){
            var it=items.get(i);
            text(g,trim((i+1)+".  "+(it.region().isBlank()?it.id():it.region()),250),x+16,y+4,0xFFC9C9C9);
            right(g,fmt(it.chunks())+" chunks",x+16+380,y+4,DIM);
            right(g,it.state(),x+OPS_W-72,y+4,queueStateColor(it.state()));
            final String id=it.id();
            icon(g,"caret",x+OPS_W-58,y,DIM);
            hit(x+OPS_W-62,y-2,22,22,"Move down",()->workspace("queue_down",id,"",""));
            icon(g,"plus",x+OPS_W-36,y,DIM);
            hit(x+OPS_W-40,y-2,22,22,"Move up",()->workspace("queue_up",id,"",""));
            icon(g,"trash",x+OPS_W-16,y,GOLD);
            hit(x+OPS_W-20,y-2,20,22,"Remove this entry",()->{
                if(confirm("queue_remove:"+id,"Click again to remove this queue entry"))
                    workspace("queue_remove",id,"","");
            });
            if(it.detail()!=null&&!it.detail().isBlank()){
                text(g,trim(it.detail(),OPS_W-60),x+30,y+16,DIM); y+=32;
            } else y+=22;
        }
        if(items.size()>shown) text(g,"+ "+(items.size()-shown)+" more queued",x+16,y+4,DIM);
        actionRow(g,x+16,opsY()+OPS_H-36,150,26,"⚙   Refresh",CY,"Ask the server for the current queue",
                ()->{workspace("queue_status","","","");say("Refreshing queue…");});
    }
    private static int queueStateColor(String state){
        return switch(state==null?"":state){
            case "RUNNING" -> CY; case "BLOCKED","INTERRUPTED" -> RED;
            case "WAITING" -> GOLD; case "DONE","COMPLETE" -> GREEN; default -> DIM; };
    }
    /**
     * Health report. The packed wire format is the one
     * {@code OceanCanvasNetworking#handleHealth} writes:
     * {@code S} summary, {@code F} chunk findings, {@code L} link summary, {@code I} link issues.
     */
    private void drawOpsHealth(GuiGraphicsExtractor g,int x,int y){
        String packed=OceanCanvasZoneClientCache.healthPacked();
        int bw=(OPS_W-44)/3;
        actionRow(g,x+16,y,bw,26,"▶   Scan",CY,"Scan chunk states and workflow links",
                ()->{send(new OceanCanvasHealthRequestPayload("scan"));say("Health scan requested…");});
        actionRow(g,x+22+bw,y,bw,26,"⚙   Repair metadata",PURPLE,"Server dry-run first; repairs only provable metadata contradictions and never changes blocks",
                ()->{ if(!ensureOperationPreview("REPAIR_METADATA","WORLD",""))return;String token=operationPreviewToken("REPAIR_METADATA","WORLD","");if(token==null)return;OceanCanvasZoneClientCache.clearOperationPreview();
                    send(new OceanCanvasHealthRequestPayload("repair_metadata:"+token));say("Metadata repair requested…"); });
        actionRow(g,x+28+bw*2,y,bw,26,"☰   Bundle",CY,"Write a diagnostic bundle to disk for support",
                ()->{send(new OceanCanvasHealthRequestPayload("diagnostic_bundle"));say("Writing diagnostic bundle…");});
        y+=32;
        actionRow(g,x+16,y,(OPS_W-38)/2,24,"▤   Stewardship report",CY,"Write the bounded human-readable world stewardship report",
                ()->{send(new OceanCanvasHealthRequestPayload("stewardship_report"));say("Writing stewardship report…");});
        actionRow(g,x+22+(OPS_W-38)/2,y,(OPS_W-38)/2,24,"✓   Acknowledge canary",GOLD,"After reviewing an outside-boundary FAIL, acknowledge it without deleting the retained failure evidence",
                ()->{send(new OceanCanvasHealthRequestPayload("canary_ack"));say("Boundary-canary acknowledgement requested…");});
        y+=32;
        hline(g,x+14,y,OPS_W-28); y+=10;
        if(selectedHealthFinding!=null&&!selectedHealthFinding.isBlank()){text(g,"SELECTED MAP FINDING",x+16,y,CY);y+=17;text(g,trim(selectedHealthFinding,OPS_W-34),x+16,y,0xFFE2E2E2);y+=20;}
        if(packed==null||packed.isBlank()){
            text(g,"No health report yet — run Scan.",x+16,y,DIM);
            return;
        }
        int findings=0, issues=0;
        String[] summary=null, links=null;
        var findingRows=new ArrayList<String[]>();
        var issueRows=new ArrayList<String[]>();
        var worldRows=new ArrayList<String[]>();
        var watchdogRows=new ArrayList<String[]>();
        var ticketRows=new ArrayList<String[]>();
        String[] gateRow=null,ownerRow=null,ticketSummary=null,canaryRow=null;
        for(String line:packed.split("\n")){
            String[] f=line.split("\t",-1);
            if(f.length==0) continue;
            switch(f[0]){
                case "S" -> { if(f.length>=6) summary=f; }
                case "L" -> { if(f.length>=7) links=f; }
                case "F" -> { findings++; if(f.length>=8&&findingRows.size()<4) findingRows.add(f); }
                case "I" -> { issues++; if(f.length>=4&&issueRows.size()<3) issueRows.add(f); }
                case "W" -> { if(f.length>=5&&worldRows.size()<7) worldRows.add(f); }
                case "D" -> { if(f.length>=6&&watchdogRows.size()<6) watchdogRows.add(f); }
                case "G" -> { if(f.length>=3) gateRow=f; }
                case "O" -> { if(f.length>=5) ownerRow=f; }
                case "T" -> { if(f.length>=3) ticketSummary=f; }
                case "K" -> { if(f.length>=8&&ticketRows.size()<4) ticketRows.add(f); }
                case "C" -> { if(f.length>=6) canaryRow=f; }
                default -> { }
            }
        }
        if(summary!=null){
            int mismatches=parseIntOr(summary[3],0);
            y=opsRow(g,x,y,"Chunk states",summary[1],"Healthy",summary[2],0xFFE2E2E2,GREEN);
            y=opsRow(g,x,y,"Mismatches",summary[3],"Legacy unverified",summary[4],
                    mismatches>0?RED:GREEN,0xFFE2E2E2);
            if("true".equals(summary[5])) { text(g,"Results truncated — scan reports a capped sample.",x+16,y,GOLD); y+=20; }
        }
        if(links!=null){
            int issueCount=parseIntOr(links[5],0);
            y=opsRow(g,x,y,"Workflow links",links[1]+" projects, "+links[2]+" tasks",
                    "Link issues",links[5],0xFFE2E2E2,issueCount>0?RED:GREEN);
        }
        if(!worldRows.isEmpty()){
            hline(g,x+14,y-2,OPS_W-28); y+=8;
            text(g,"WORLD STEWARDSHIP",x+16,y,DIM); y+=18;
            for(String[] w:worldRows){
                String state=w[3]; int color="ATTENTION".equals(state)?RED:("BUSY".equals(state)?GOLD:("GOOD".equals(state)?GREEN:DIM));
                text(g,trim(decodeB64(w[2]),150),x+16,y,0xFFE2E2E2);
                right(g,state,x+OPS_W-16,y,color); y+=16;
            }
        }
        if(!watchdogRows.isEmpty()){
            hline(g,x+14,y-2,OPS_W-28); y+=8;
            text(g,"WATCHDOG MATRIX",x+16,y,DIM); y+=18;
            for(String[] d:watchdogRows){
                String state=d[3];int color="ATTENTION".equals(state)?RED:("IDLE".equals(state)?DIM:GREEN);
                text(g,trim(prettyId(d[1])+" · "+decodeB64(d[2]),275),x+16,y,0xFFE2E2E2);
                right(g,state,x+OPS_W-16,y,color);y+=16;
            }
        }
        if(gateRow!=null||ownerRow!=null||ticketSummary!=null||canaryRow!=null){
            hline(g,x+14,y-2,OPS_W-28);y+=8;text(g,"LIFECYCLE OWNERSHIP",x+16,y,DIM);y+=18;
            if(gateRow!=null){boolean pass=Boolean.parseBoolean(gateRow[1]);text(g,trim("Workflow gate · "+decodeB64(gateRow[2]),OPS_W-125),x+16,y,0xFFE2E2E2);right(g,pass?"PASS":"BLOCKED",x+OPS_W-16,y,pass?GREEN:RED);y+=17;}
            if(ownerRow!=null&&Boolean.parseBoolean(ownerRow[1])){text(g,trim("Owner · "+decodeB64(ownerRow[2])+" · "+decodeB64(ownerRow[3]),OPS_W-32),x+16,y,0xFFE2E2E2);y+=17;}
            if(canaryRow!=null){String state=canaryRow[1];text(g,trim("Boundary canary · "+decodeB64(canaryRow[5]),OPS_W-125),x+16,y,0xFFE2E2E2);right(g,state,x+OPS_W-16,y,"FAIL".equals(state)?RED:(state.contains("PASS")?GREEN:GOLD));y+=17;}
            if(ticketSummary!=null){text(g,"Tickets / leases",x+16,y,0xFFE2E2E2);right(g,ticketSummary[1]+" · oldest "+ticketSummary[2]+" ms",x+OPS_W-16,y,parseIntOr(ticketSummary[1],0)>0?GOLD:GREEN);y+=17;}
            for(String[] k:ticketRows){text(g,trim(decodeB64(k[1])+" · chunk "+k[2]+","+k[3]+" · "+k[4]+" ms",OPS_W-32),x+28,y,DIM);y+=15;}
        }
        hline(g,x+14,y-2,OPS_W-28); y+=8;
        if(findingRows.isEmpty()&&issueRows.isEmpty()){
            text(g,"No findings. "+findings+" chunk finding(s), "+issues+" link issue(s) reported.",x+16,y,DIM);
            return;
        }
        for(String[] f:findingRows){
            text(g,trim("chunk "+f[1]+", "+f[2]+"  "+f[3],250),x+16,y,0xFFC9C9C9);
            right(g,trim(decodeB64(f[7]),250),x+OPS_W-16,y,DIM);
            y+=20;
        }
        for(String[] i:issueRows){
            text(g,trim(i[1]+"  "+decodeB64(i[2]),250),x+16,y,0xFFC9C9C9);
            right(g,trim(decodeB64(i[3]),250),x+OPS_W-16,y,DIM);
            y+=20;
        }
        if(findings>findingRows.size()||issues>issueRows.size())
            text(g,"+ "+Math.max(0,findings-findingRows.size())+" more findings, "
                    +Math.max(0,issues-issueRows.size())+" more issues",x+16,y,DIM);
    }
    private static int parseIntOr(String raw,int fallback){
        try{ return Integer.parseInt(raw.trim()); }catch(RuntimeException e){ return fallback; }
    }
    /** URL-safe, unpadded base64 — the encoding every Ocean Canvas packed payload uses. */
    private static String decodeB64(String raw){
        if(raw==null||raw.isEmpty()) return "";
        try{ return new String(Base64.getUrlDecoder().decode(raw),java.nio.charset.StandardCharsets.UTF_8); }
        catch(IllegalArgumentException ex){ return ""; }
    }

    private String jobRegionName(OceanCanvasJobStatusPayload job){
        if(job.scopeName()!=null&&!job.scopeName().isBlank())return job.scopeName();
        // Command/radius Pregen has no Region owner. Never infer one from the moving cursor: the
        // cursor can pass through an unrelated saved Region even though the job's scope is Canvas.
        return "Canvas";
    }
    /** Bottom toolbar from the approved UI contract: Help precedes Inspect, then panel recovery. */
    private void drawBottomToolbar(GuiGraphicsExtractor g){
        int x=18,y=DESIGN_H-64,h=30,barW=340;
        fill(g,x,y,barW,h,0xE6000000);border(g,x,y,barW,h,BORDER);blocker(x,y,barW,h);
        fill(g,x,y,42,h,"help".equals(overlay)?0x171FC4EF:0xFF050708);border(g,x,y,42,h,"help".equals(overlay)?CY:BORDER);
        center(g,"?",x,y+11,42,"help".equals(overlay)?CY:TEXT);
        hit(x,y,42,h,"Help and keyboard shortcuts",()->overlay="help".equals(overlay)?null:"help");
        int ix=x+48;
        fill(g,ix,y,86,h,inspectMode?0x171FC4EF:0xFF050708);border(g,ix,y,86,h,inspectMode?CY:BORDER);
        center(g,"INSPECT",ix,y+11,86,inspectMode?CY:TEXT);
        hit(ix,y,86,h,inspectMode?"Inspector active — click the map to inspect":"Inspect blocks, chunks and Ocean Canvas state",()->{
            inspectMode=!inspectMode;
            if(inspectMode){cancelGesture();say("Inspect active — click the map");} else say("Inspect off");
        });
        int px=ix+92;
        fill(g,px,y,106,h,"panels".equals(overlay)?0x171FC4EF:0xFF050708);border(g,px,y,106,h,"panels".equals(overlay)?CY:BORDER);
        center(g,"PANELS",px,y+11,106,"panels".equals(overlay)?CY:TEXT);
        hit(px,y,106,h,"Show or hide Ocean Canvas panels",()->overlay="panels".equals(overlay)?null:"panels");
        int sx=px+112;
        boolean paletteOpen="palette".equals(overlay);
        fill(g,sx,y,88,h,paletteOpen?0x171FC4EF:0xFF050708);border(g,sx,y,88,h,paletteOpen?CY:BORDER);
        center(g,"SEARCH",sx,y+11,88,paletteOpen?CY:TEXT);
        hit(sx,y,88,h,"Command palette and universal search (Ctrl+K)",
                ()->{ if(paletteOpen) closePalette(); else openPalette(); });
        String inspected=OceanCanvasZoneClientCache.inspectionPacked();
        if(inspectMode&&!inspected.isBlank()){
            int w=520;
            fill(g,x,y-27,w,23,0xEE000000);border(g,x,y-27,w,23,CY);
            text(g,trim(inspected.replace('\n',' '),w-18),x+9,y-19,TEXT);
        }
    }

    /** Advanced capabilities remain modal so the four approved top-level tabs never drift. */
    /**
     * OC-F121 follow-through: the task editor. Every control here sends an action the server has
     * always handled and nothing has ever called — `task_title`, `task_notes`, `task_priority`,
     * `task_weight`, `task_parent`, `task_phase`, `task_dependencies`, `task_check_add`,
     * `task_check_toggle`, `task_check_remove`, `task_delete`, `task_done`.
     *
     * <p>`OceanCanvasNetworking` even carries a comment beside `task_status` saying full task
     * editing was "promised in the v44 accepted scope but never wired up". It stayed unwired for
     * roughly two hundred versions because nothing was watching for the gap. The parity gate now is.</p>
     *
     * <p>Server-side validation bounds are mirrored here so a refusal is prevented rather than
     * reported: priority 0-3, weight 0.1-100, notes 512 characters, a parent in the same project
     * and free of cycles, a phase belonging to the task's own project.</p>
     */
    private void drawWorkbenchProject(GuiGraphicsExtractor g,int x,int y){
        var p=project();
        if(p==null){y=wbSection(g,x,y,"PROJECT EXECUTION");text(g,"No project selected.",x,y,DIM);return;}
        final String pid=p.id();
        y=wbSection(g,x,y,"PROJECT EXECUTION — "+trim(p.name(),380));
        y=wbEditableLine(g,x,y,"Name",p.name(),"project.name",p.name());
        y=wbEditableLine(g,x,y,"Notes",p.notes().isBlank()?"None":trim(p.notes(),360),"project.notes",p.notes());
        int by=y;
        wbButton(g,x,by,190,"Apply default mission","Create a complete starter execution structure when the project is still empty",CY,()->workspace("project_mission_apply",pid,"world_build",""));
        wbButton(g,x+198,by,190,"Bookmark current task","Use the selected task as this project's resume point",CY,()->workspace("project_bookmark",pid,selectedTask,""));
        wbButton(g,x+396,by,190,"Link selected Plan","Replace project Plan links with the selected Plan object",selectedPlanObject.isBlank()?DIM:CY,()->{if(selectedPlanObject.isBlank()){say("Select a Plan object first");return;}workspace("project_plan_links",pid,selectedPlanObject,"");});
        String ta=selectedTerrainAsset;
        wbButton(g,x+594,by,190,"Link terrain asset","Replace project terrain links with the selected terrain asset",ta.isBlank()?DIM:CY,()->{if(ta.isBlank()){say("Select a terrain asset in Library first");return;}workspace("project_terrain_links",pid,ta,"");});
        y=by+38;

        y=wbSection(g,x,y,"NEXT SESSION");
        var resume=OceanCanvasZoneClientCache.nextSessionItems();
        if(resume.isEmpty()) text(g,"Nothing pinned. Pin a task or the current map centre for the next login.",x,y,DIM);
        for(var item:resume){final String iid=item.id();int iy=y;y=wbLine(g,x+12,y,"• "+trim(item.label(),280),title(item.kind())+" · "+item.x()+", "+item.z(),TEXT);hit(x+12,iy,WB_W-170,20,"Centre map on this next-session item",()->{mapViewX=item.x();mapViewZ=item.z();coordX=item.x();coordZ=item.z();say("Next-session: "+item.label());});wbButton(g,x+WB_W-145,iy,130,"Remove","Remove this next-session pin",RED,()->workspace("next_session_remove",iid,"",""));}
        int ny=y;wbButton(g,x,ny,190,"Pin selected task","Add the selected task to the next-session queue",selectedTask.isBlank()?DIM:CY,()->{if(selectedTask.isBlank()){say("Select a task first");return;}workspace("next_session_task",selectedTask,"","");});
        wbButton(g,x+198,ny,190,"Pin map centre","Save the current map centre as a next-session location",CY,()->workspace("next_session_location","","Map centre",((int)Math.round(mapViewX))+","+((int)Math.round(mapViewZ))));
        wbButton(g,x+396,ny,190,"Clear queue","Remove all next-session pins",resume.isEmpty()?DIM:RED,()->{if(resume.isEmpty())return;if(confirm("next_session_clear","Click Clear queue again to confirm"))workspace("next_session_clear","","","");});
        y=ny+38;

        y=wbSection(g,x,y,"PHASES");
        var phases=OceanCanvasZoneClientCache.workspacePhases(p);
        if(phases.isEmpty()) text(g,"No phases yet — Add Phase from the Projects rail or right panel.",x,y,DIM);
        for(var ph:phases){
            final String id=ph.id(); boolean sel=id.equals(selectedPhase); int yy=y;
            y=wbLine(g,x+12,y,(sel?"▶ ":"• ")+ph.name(),title(ph.status())+" · order "+ph.order(),sel?CY:TEXT);
            hit(x+12,yy,WB_W-76,20,"Select phase",()->{selectedPhase=id;selectedMilestone="";});
        }
        if(!selectedPhase.isBlank()){
            OceanCanvasZoneClientCache.WorkspacePhase ph=null;for(var q:phases)if(q.id().equals(selectedPhase))ph=q;
            if(ph!=null){final String phid=ph.id(), phname=ph.name();int py=y;
                wbButton(g,x,py,150,"Rename phase","Edit this phase name",CY,()->beginEdit("project.phase.rename",phname));
                wbButton(g,x+158,py,130,"Move up","Move this phase earlier",CY,()->workspace("project_phase_move",pid,phid,"UP"));
                wbButton(g,x+296,py,130,"Move down","Move this phase later",CY,()->workspace("project_phase_move",pid,phid,"DOWN"));
                wbButton(g,x+434,py,160,"Remove phase","Remove only when no task is assigned to it",RED,()->{if(confirm("phase_remove:"+phid,"Click Remove phase again to confirm"))workspace("project_phase_remove",pid,phid,"");});
                y=py+38;
                if("project.phase.rename".equals(editKey))drawEditBox(g,x,y,500,27);
                if("project.phase.rename".equals(editKey))y+=34;
            }
        }

        y=wbSection(g,x,y,"MILESTONES");
        var milestones=OceanCanvasZoneClientCache.workspaceMilestones(p);
        for(var m:milestones){final String id=m.id();boolean sel=id.equals(selectedMilestone);int yy=y;y=wbLine(g,x+12,y,(sel?"▶ ":"• ")+m.name(),m.progress()+"% · "+title(m.status()),sel?CY:TEXT);hit(x+12,yy,WB_W-76,20,"Select milestone",()->{selectedMilestone=id;selectedPhase="";});}
        if(!selectedMilestone.isBlank()){
            OceanCanvasZoneClientCache.WorkspaceMilestone m=null;for(var q:milestones)if(q.id().equals(selectedMilestone))m=q;
            if(m!=null){final String mid=m.id(), mname=m.name(), mtarget=(m.targetDate()==null?"":m.targetDate());int my=y;int nextProgress=Math.min(100,m.progress()+10);
                wbButton(g,x,my,170,"Rename milestone","Edit milestone name while preserving its metadata",CY,()->beginEdit("project.milestone.rename",mname));
                wbButton(g,x+178,my,170,"Progress +10%","Advance milestone progress without changing target date",CY,()->workspace("project_milestone_metadata",pid,mid,"PROGRESS="+nextProgress+"|TARGET="+mtarget));
                wbButton(g,x+356,my,170,"Legacy milestone","Also add this name to the compact project milestone list",CY,()->workspace("project_milestone_add",pid,mname,""));
                wbButton(g,x+534,my,170,"Remove milestone","Delete this milestone record",RED,()->{if(confirm("milestone_remove:"+mid,"Click Remove milestone again to confirm"))workspace("project_milestone_remove",pid,mid,"");});
                y=my+38;if("project.milestone.rename".equals(editKey)){drawEditBox(g,x,y,500,27);y+=34;}
            }
        }
        y=wbSection(g,x,y,"PROJECT RELATIONSHIPS");
        var projects=OceanCanvasZoneClientCache.workspaceProjects();
        String parent="";for(var q:projects)if(!q.id().equals(pid)){parent=q.id();break;}
        final String parentId=parent;
        wbButton(g,x,y,200,parentId.isBlank()?"No parent candidate":"Set first other project as parent","Set or clear this project's parent without creating a cycle",parentId.isBlank()?DIM:CY,()->{if(parentId.isBlank())say("Create another project first");else workspace("project_parent",pid,parentId,"");});
        wbButton(g,x+208,y,200,"Clear parent","Make this a top-level project",CY,()->workspace("project_parent",pid,"",""));
    }

    private void drawWorkbenchTasks(GuiGraphicsExtractor g,int x,int y){
        OceanCanvasZoneClientCache.WorkspaceTask task=null;
        for(var t:OceanCanvasZoneClientCache.workspaceTasks()) if(t.id().equals(selectedTask)) task=t;
        if(task==null){
            y=wbSection(g,x,y,"TASK EDITOR");
            text(g,"No task selected.",x,y,DIM); y+=20;
            text(g,"Open a project on the Projects tab and use the ✎ beside any task.",x,y,DIM);
            return;
        }
        final var t=task;
        final String tid=t.id();
        y=wbSection(g,x,y,"TASK — "+trim(t.title(),420));

        y=wbEditableLine(g,x,y,"Title",t.title(),"task.title",t.title());
        y=wbEditableLine(g,x,y,"Notes",t.notes()==null||t.notes().isBlank()?"—":t.notes(),"task.notes",
                t.notes()==null?"":t.notes());
        y=wbLine(g,x,y,"Position",t.x()+", "+t.z()+"  ·  Nether "+netherCoord(t.x())+", "+netherCoord(t.z()),DIM);
        int locateY=y; wbButton(g,x,locateY,190,"Show task on map","Center the map on this task while keeping it selected",CY,()->{mapViewX=t.x();mapViewZ=t.z();overlay=null;tab="PROJECTS";panelOpen=true;collapsed=false;say("Showing "+t.title());}); y=locateY+36;
        y=wbLine(g,x,y,"Status",title(t.status()),statusColor(t.status()));

        int py=y;
        y=wbLine(g,x,y,"Priority",priorityLabel(t.priority()),t.priority()>=2?GOLD:TEXT);
        int px=x+300;
        for(int level=0;level<=3;level++){
            final int lv=level;
            int bw=64;
            boolean on=t.priority()==level;
            fill(g,px,py-2,bw,20,on?0x171FC4EF:0xFF080A0C); border(g,px,py-2,bw,20,on?CY:BORDER);
            center(g,priorityLabel(level),px,py+3,bw,on?CY:TEXT);
            hit(px,py-2,bw,20,"Set priority to "+priorityLabel(lv),
                    ()->{ workspace("task_priority",tid,Integer.toString(lv),""); say("Priority → "+priorityLabel(lv)); });
            px+=bw+6;
        }
        y=wbEditableLine(g,x,y,"Weight",String.format(Locale.US,"%.1f",t.weight()),"task.weight",
                String.format(Locale.US,"%.1f",t.weight()));

        OceanCanvasZoneClientCache.WorkspaceProject project=null;
        for(var pr2:OceanCanvasZoneClientCache.workspaceProjects())
            if(pr2.id().equals(t.projectId())) project=pr2;
        String phaseName="—";
        var phases=OceanCanvasZoneClientCache.workspacePhases(project);
        for(var ph:phases) if(ph.id().equals(t.phaseId())) phaseName=ph.name();
        int phy=y;
        y=wbLine(g,x,y,"Phase",phaseName,phaseName.equals("—")?DIM:TEXT);
        if(!phases.isEmpty()){
            int cx=x+300;
            for(var ph:phases){
                final String pid=ph.id();
                int bw=Math.min(150,font.width(ph.name())+18);
                boolean on=pid.equals(t.phaseId());
                fill(g,cx,phy-2,bw,20,on?0x171FC4EF:0xFF080A0C); border(g,cx,phy-2,bw,20,on?CY:BORDER);
                center(g,trim(ph.name(),bw-8),cx,phy+3,bw,on?CY:TEXT);
                hit(cx,phy-2,bw,20,"Move this task to phase "+ph.name(),
                        ()->{ workspace("task_phase",tid,on?"":pid,""); say(on?"Phase cleared":"Phase → "+ph.name()); });
                cx+=bw+6;
                if(cx>wbX()+WB_W-140) break;
            }
        }

        // Dependencies and parent are stored as ids; siblings in the same project are the only
        // legal values, so they are offered as a list rather than a free-text id the user must know.
        var siblings=new ArrayList<OceanCanvasZoneClientCache.WorkspaceTask>();
        for(var o:OceanCanvasZoneClientCache.workspaceTasks())
            if(!o.id().equals(tid)&&java.util.Objects.equals(o.projectId(),t.projectId())) siblings.add(o);
        String parentName="—";
        for(var o:siblings) if(o.id().equals(t.parentTaskId())) parentName=o.title();
        y=wbLine(g,x,y,"Parent task",parentName,parentName.equals("—")?DIM:TEXT);

        String deps=t.dependencies()==null?"":t.dependencies();
        var depIds=new ArrayList<String>();
        for(String d:deps.split(",")) if(!d.isBlank()) depIds.add(d.trim());
        // OC-F027/F091: a listed dependency that is already COMPLETE no longer blocks anything,
        // so this reports the tasks actually still in the way rather than the raw id count.
        var unresolvedDeps=blockingTasks(t,siblings);
        String blockedByLabel=depIds.isEmpty()?"Nothing"
                :unresolvedDeps.isEmpty()?depIds.size()+" task"+(depIds.size()==1?"":"s")+" — all complete, ready"
                :unresolvedDeps.size()+" of "+depIds.size()+" still open";
        y=wbLine(g,x,y,"Blocked by",blockedByLabel,unresolvedDeps.isEmpty()?GREEN:GOLD);

        if(siblings.isEmpty()){
            y=wbLine(g,x,y,"Other tasks","None in this project — nothing to depend on",DIM);
        } else {
            y+=2;
            for(var o:siblings){
                final String oid=o.id(); final String otitle=o.title();
                boolean isDep=depIds.contains(oid);
                boolean isParent=oid.equals(t.parentTaskId());
                text(g,trim(o.title(),330),x+16,y,isDep?GOLD:0xFFC9C9C9);
                int bx=x+380;
                fill(g,bx,y-3,110,19,isDep?0x171FC4EF:0xFF080A0C); border(g,bx,y-3,110,19,isDep?GOLD:BORDER);
                center(g,isDep?"Blocking":"Block on",bx,y+2,110,isDep?GOLD:TEXT);
                hit(bx,y-3,110,19,isDep?"Stop treating "+otitle+" as a blocker":"Treat "+otitle+" as a blocker",()->{
                    var next=new ArrayList<>(depIds);
                    if(isDep) next.remove(oid); else next.add(oid);
                    workspace("task_dependencies",tid,String.join(",",next),"");
                    say(isDep?"No longer blocked by "+otitle:"Blocked by "+otitle);
                });
                bx+=118;
                fill(g,bx,y-3,110,19,isParent?0x171FC4EF:0xFF080A0C); border(g,bx,y-3,110,19,isParent?CY:BORDER);
                center(g,isParent?"Parent":"Set parent",bx,y+2,110,isParent?CY:TEXT);
                hit(bx,y-3,110,19,isParent?"Clear the parent task":"Make "+otitle+" the parent of this task",()->{
                    workspace("task_parent",tid,isParent?"":oid,"");
                    say(isParent?"Parent cleared":"Parent → "+otitle);
                });
                y+=22;
                if(y>wbY()+WB_H-160) break;
            }
        }

        y+=6;
        y=wbSection(g,x,y,"CHECKLIST");
        var items=OceanCanvasZoneClientCache.workspaceChecklist(t);
        if(items.isEmpty()){ text(g,"No checklist items.",x,y,DIM); y+=20; }
        for(var item:items){
            final String iid=item.id(); final String itext=item.text();
            text(g,item.complete()?"☑":"☐",x,y,item.complete()?GREEN:DIM);
            text(g,trim(item.text(),420),x+22,y,item.complete()?DIM:0xFFC9C9C9);
            hit(x-2,y-4,470,20,item.complete()?"Mark \""+itext+"\" incomplete":"Mark \""+itext+"\" complete",
                    ()->workspace("task_check_toggle",tid,iid,""));
            text(g,"✕",x+486,y,RED);
            hit(x+480,y-4,20,20,"Remove \""+itext+"\" from the checklist",
                    ()->{ if(confirm("checkrm:"+iid,"Click ✕ again to remove")) workspace("task_check_remove",tid,iid,""); });
            y+=20;
        }
        y=wbEditableLine(g,x,y,"Add item",editKey!=null&&editKey.equals("task.check")?"":"Click to type…","task.check","");

        y+=10;
        int by=y;
        wbButton(g,x,by,200,"COMPLETE".equals(t.status())?"Already complete":"Mark complete",
                "Set this task to COMPLETE",GREEN,()->{ workspace("task_done",tid,"",""); say(t.title()+" complete"); });
        boolean armed=isArmed("task_delete:"+tid);
        wbButton(g,x+210,by,200,armed?"Confirm delete":"Delete task",
                withReversibility("Delete this task permanently","project_delete"),armed?RED:GOLD,()->{
            if(confirm("task_delete:"+tid,"Click Delete task again to confirm")){
                workspace("task_delete",tid,"",""); selectedTask=""; say("Task deleted");
            }
        });
    }

    private static String priorityLabel(int p){ return switch(p){ case 0->"None"; case 1->"Low"; case 2->"High"; default->"Urgent"; }; }
    /**
     * OC-F196 Nether coordinate readout, partial. The real overworld:Nether ratio is 8:1, and
     * {@code Math.floorDiv} matches the game's own floor-based scaling (not truncation) so this
     * agrees with vanilla for negative coordinates too. Shown beside a task's position in the
     * workbench; there is deliberately no continuous on-map overlay layer - the persistent
     * coordinate HUD's geometry is the locked authoritative-HTML-parity contract from v253.15 and
     * is not extended here.
     */
    private static int netherCoord(int overworldCoord){ return Math.floorDiv(overworldCoord,8); }

    /** A workbench row whose value can be clicked to edit in place. */
    private int wbEditableLine(GuiGraphicsExtractor g,int x,int y,String label,String value,String key,String initial){
        boolean editing=key.equals(editKey);
        text(g,label,x,y,DIM);
        if(editing){ drawEditBox(g,x+300,y-5,420,20); }
        else{
            text(g,trim(value,420),x+300,y,TEXT);
            hit(x+296,y-5,428,20,"Click to edit "+label.toLowerCase(Locale.ROOT),()->beginEdit(key,initial));
        }
        return y+22;
    }

    /**
     * OC-F078 / OC-F162, and the second repayment on the parity debt: plan geometry operators.
     *
     * <p>Nine geometry actions plus rename, visible, lock, width and delete were implemented in
     * `OceanCanvasNetworking` — calling straight into `OceanCanvasGeometry`, with lock checks,
     * vertex-count validation and linked-endpoint propagation — and reachable by nothing.</p>
     *
     * <p><b>This stays inside the stated scope boundary.</b> These operate on <i>plan vectors</i>:
     * the shapes and paths drawn over the map. They move no blocks and sculpt no terrain. The
     * server's own refusal message calls it "sculpting its geometry", which is about the polygon,
     * not the world.</p>
     *
     * <p>Every bound shown here is the server's: smooth 1-3 passes, jagged and offset up to 4096
     * blocks, simplify up to 1024, resample spacing 1-4096, scale 0.01-100, rotate ±3600°, and a
     * result between the type's minimum and 512 vertices.</p>
     */
    /**
     * Third repayment on the parity debt: individual reference-image layers.
     *
     * <p>Reference <i>sets</i> were partly wired; the individual layers inside them were not.
     * `reference_rename`, `reference_visible`, `reference_lock`, `reference_opacity`,
     * `reference_move`, `reference_rotate`, `reference_transform`, `reference_asset_replace`,
     * `reference_delete`, `refset_member` and `refset_move` were all implemented and reachable by
     * nothing — so an imported image could be placed once and never adjusted again.</p>
     *
     * <p>{@code reference_registration} is now wired through a validated 2- or 3-point registration
     * editor in the Library completion section. It accepts exact image/world coordinate pairs and
     * refuses malformed or locked-layer submissions, so registration is a real precision workflow
     * rather than a parity-only button.</p>
     */
    private int drawReferenceLayerEditor(GuiGraphicsExtractor g,int x,int y){
        var refs=OceanCanvasZoneClientCache.planningReferences();
        y=wbSection(g,x,y,"REFERENCE IMAGES");
        if(refs.isEmpty()){
            text(g,"No reference images imported yet. Use Import Reference above.",x,y,DIM);
            return y+24;
        }
        if(selectedReference.isBlank()) selectedReference=refs.get(0).id();
        for(int i=0;i<refs.size()&&i<4;i++){
            var r=refs.get(i); final String rid=r.id(); int yy=y;
            boolean sel=rid.equals(selectedReference);
            y=wbLine(g,x+12,y,(sel?"▶ ":"• ")+r.name(),
                    (r.visible()?"Visible":"Hidden")+" · "+(int)Math.round(r.opacity()*100)+"%"
                            +(r.locked()?" · Locked":""),
                    sel?CY:(r.visible()?TEXT:DIM));
            hit(x+12,yy,WB_W-76,20,"Select "+r.name(),()->selectedReference=rid);
        }
        OceanCanvasZoneClientCache.PlanningReference ref=null;
        for(var r:refs) if(r.id().equals(selectedReference)) ref=r;
        if(ref==null) return y+8;
        final var rr=ref; final String rid=ref.id(); final boolean locked=ref.locked();
        final double opacity=ref.opacity();

        y=wbEditableLine(g,x,y,"Name",ref.name(),"ref.name",ref.name());
        y=wbLine(g,x,y,"Bounds",ref.minX()+", "+ref.minZ()+"  →  "+ref.maxX()+", "+ref.maxZ(),DIM);
        y=wbLine(g,x,y,"Rotation",String.format(Locale.US,"%.1f°",ref.rotation()),DIM);

        int by=y;
        wbButton(g,x,by,145,ref.visible()?"Hide":"Show","Toggle this reference on the map",CY,
                ()->planning("reference_visible",rid,String.valueOf(!rr.visible()),""));
        wbButton(g,x+153,by,145,locked?"Unlock":"Lock",
                locked?"Allow edits to this reference":"Protect this reference from edits",CY,
                ()->planning("reference_lock",rid,String.valueOf(!locked),""));
        wbButton(g,x+306,by,145,"Opacity "+(int)Math.round(opacity*100)+"%",
                locked?"Unlock this reference first":"Cycle opacity between 5% and 100%",locked?DIM:CY,()->{
            if(locked){ say("Unlock the reference layer before changing opacity"); return; }
            planning("reference_opacity",rid,String.valueOf(opacity>=0.95D?0.05D:Math.min(1.0D,opacity+0.20D)),"");
        });
        wbButton(g,x+459,by,145,"Bring Forward","Draw this reference above the next one",CY,
                ()->planning("reference_move",rid,"up",""));
        wbButton(g,x+612,by,145,"Send Back","Draw this reference below the previous one",CY,
                ()->planning("reference_move",rid,"down",""));
        y=by+38;

        y=wbEditableLine(g,x,y,"Rotate to (°)",String.format(Locale.US,"%.1f",ref.rotation()),
                "ref.rotate",String.format(Locale.US,"%.1f",ref.rotation()));
        y=wbEditableLine(g,x,y,"Bounds minX,minZ,maxX,maxZ",
                ref.minX()+","+ref.minZ()+","+ref.maxX()+","+ref.maxZ(),"ref.transform",
                ref.minX()+","+ref.minZ()+","+ref.maxX()+","+ref.maxZ());

        // Re-point a layer at a different imported image. The server requires a 64-hex asset id,
        // so the only safe source is another reference's asset - never a typed string.
        var otherAssets=new ArrayList<OceanCanvasZoneClientCache.PlanningReference>();
        for(var r:refs) if(!r.id().equals(rid)&&!r.assetId().equals(rr.assetId())) otherAssets.add(r);
        if(!otherAssets.isEmpty()){
            int ry=y; int cx2=x;
            text(g,"Replace image with",x,y,DIM); y+=18;
            for(int i=0;i<otherAssets.size()&&i<3;i++){
                var o=otherAssets.get(i); final String asset=o.assetId(); final String oname=o.name();
                wbButton(g,cx2,y,238,trim(oname,210),
                        locked?"Unlock this reference first":"Point this layer at the image imported as "+oname,
                        locked?DIM:CY,()->{
                    if(locked){ say("Unlock the reference layer before replacing its asset"); return; }
                    planning("reference_asset_replace",rid,asset,""); say("Image replaced");
                });
                cx2+=246;
            }
            y+=38; if(ry==y) y+=4;
        }

        var sets=OceanCanvasZoneClientCache.referenceSets();
        if(!sets.isEmpty()){
            text(g,"Reference sets",x,y,DIM); y+=18;
            int cx3=x;
            for(int i=0;i<sets.size()&&i<3;i++){
                var q=sets.get(i); final String sid=q.id(); final String sname=q.name();
                boolean member=q.referenceIds().contains(rid);
                wbButton(g,cx3,y,238,(member?"Remove from ":"Add to ")+trim(sname,150),
                        member?"Remove this reference from "+sname:"Add this reference to "+sname,CY,
                        ()->planning("refset_member",sid,rid,""));
                cx3+=246;
            }
            y+=38;
            var first=sets.get(0); final String fid=first.id();
            wbButton(g,x,y,180,"Set Order ↑","Move the first reference set up the draw order",CY,
                    ()->planning("refset_move",fid,"up",""));
            wbButton(g,x+188,y,180,"Set Order ↓","Move the first reference set down the draw order",CY,
                    ()->planning("refset_move",fid,"down",""));
            y+=38;
        }

        boolean delArmed=isArmed("reference_delete:"+rid);
        wbButton(g,x,y,238,delArmed?"Confirm delete":"Delete reference",
                locked?"Unlock this reference first":withReversibility("Remove this reference layer","plan_clear"),
                locked?DIM:(delArmed?RED:GOLD),()->{
            if(locked){ say("Unlock the reference layer before deleting it"); return; }
            if(confirm("reference_delete:"+rid,"Click Delete reference again to confirm")){
                planning("reference_delete",rid,"",""); selectedReference=""; say("Deleted "+rr.name());
            }
        });
        return y+38;
    }

    private void drawWorkbenchGeometry(GuiGraphicsExtractor g,int x,int y){
        var v=planObject();
        if(v==null){
            y=wbSection(g,x,y,"PLAN GEOMETRY");
            text(g,"No plan object selected.",x,y,DIM); y+=20;
            text(g,"Select a path or shape on the Plans tab, then reopen this tab.",x,y,DIM);
            return;
        }
        final String vid=v.id(); final boolean locked=v.locked();
        int pointCount=planningVertexList(v.points()).size()/2;
        y=wbSection(g,x,y,"PLAN GEOMETRY — "+trim(v.name(),380));
        y=wbEditableLine(g,x,y,"Name",v.name(),"geom.name",v.name());
        y=wbLine(g,x,y,"Type",title(v.type()),TEXT);
        y=wbLine(g,x,y,"Vertices",pointCount+" of 512 maximum",pointCount>460?GOLD:TEXT);

        int ty=y;
        y=wbLine(g,x,y,"Locked",locked?"Locked — geometry edits are refused":"Unlocked",locked?GOLD:GREEN);
        wbButton(g,x+300,ty-4,150,locked?"Unlock":"Lock",
                locked?"Allow geometry edits to this object":"Prevent edits to this object",CY,
                ()->{ planning("lock",vid,String.valueOf(!locked),""); say(locked?"Unlocked":"Locked"); });
        int vy=y;
        y=wbLine(g,x,y,"Visible",v.visible()?"Shown on the map":"Hidden",v.visible()?GREEN:DIM);
        wbButton(g,x+300,vy-4,150,v.visible()?"Hide":"Show","Toggle this object on the map",CY,
                ()->planning("visible",vid,String.valueOf(!v.visible()),""));
        y=wbEditableLine(g,x,y,"Width (blocks)",String.format(Locale.US,"%.1f",v.widthBlocks()),
                "geom.width",String.format(Locale.US,"%.1f",v.widthBlocks()));

        y+=8;
        y=wbSection(g,x,y,"SHAPE OPERATORS");
        if(locked){
            text(g,"Unlock this object to use the operators below.",x,y,GOLD); y+=22;
        }
        y=wbEditableLine(g,x,y,"Amount",String.format(Locale.US,"%.2f",geomAmount),
                "geom.amount",String.format(Locale.US,"%.2f",geomAmount));
        text(g,"Each operator states the range the server accepts for this value.",x,y,DIM); y+=24;

        String[][] ops={
            {"geom_smooth","Smooth","Rounds corners. Passes, 1-3."},
            {"geom_jagged","Jagged","Adds deterministic roughness. Blocks, 0-2048."},
            {"geom_expand","Expand","Offsets outward. Blocks, 0-4096."},
            {"geom_erode","Erode","Offsets inward. Blocks, 0-4096."},
            {"geom_simplify","Simplify","Drops vertices within a tolerance. Blocks, 0-1024."},
            {"geom_resample","Resample","Even vertex spacing. Blocks, 1-4096."},
            {"geom_scale","Scale","About the shape's centre. Factor, 0.01-100."},
            {"geom_rotate","Rotate","About the shape's centre. Degrees, -3600 to 3600."},
        };
        int col=0, rowY=y;
        for(String[] op:ops){
            final String action=op[0];
            int bx=x+col*248;
            wbButton(g,bx,rowY,238,op[1],
                    locked?"Unlock this object first":op[2]+"  ·  Amount "+String.format(Locale.US,"%.2f",geomAmount),
                    locked?DIM:CY,()->{
                if(locked){ say("Unlock the planning object before changing its geometry"); return; }
                planning(action,vid,String.format(Locale.US,"%.4f",geomAmount),"");
                say(op[1]+" applied to "+v.name());
            });
            col++;
            if(col==3){ col=0; rowY+=32; }
        }
        y=col==0?rowY:rowY+32;
        y+=6;

        y=wbEditableLine(g,x,y,"Translate dx,dz",geomTranslate,"geom.translate",geomTranslate);
        int mvY=y;
        wbButton(g,x,mvY,238,"Move by dx,dz",
                locked?"Unlock this object first":"Shift every vertex by the offset above",locked?DIM:CY,()->{
            if(locked){ say("Unlock the planning object before changing its geometry"); return; }
            String[] f=geomTranslate.split(",");
            if(f.length!=2){ say("Translate needs dx,dz — for example 128,-64"); return; }
            try{ Integer.parseInt(f[0].trim()); Integer.parseInt(f[1].trim()); }
            catch(NumberFormatException e){ say("Translate needs two whole numbers"); return; }
            planning("geom_translate",vid,"",f[0].trim()+","+f[1].trim());
            say("Moved "+v.name());
        });
        y=mvY+40;

        y=wbSection(g,x,y,"ADVANCED PATH & GUIDE TOOLS");
        text(g,"These controls expose the remaining server-backed path, Bezier, batch and terrain-guide tools.",x,y,DIM); y+=22;
        int advY=y;
        wbButton(g,x,advY,210,"Smooth rendering","Toggle non-destructive smooth path rendering",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object before changing path smoothing");return;}
            planning("smooth",vid,String.valueOf(!v.guideData().contains("SMOOTH")),"");
        });
        wbButton(g,x+218,advY,210,"Auto Bezier","Generate editable Bezier handles from the current vertices",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object before editing Bezier handles");return;} planning("bezier_auto",vid,"","");
        });
        wbButton(g,x+436,advY,210,"Clear Bezier","Remove Bezier handles without changing vertices",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object before editing Bezier handles");return;} planning("bezier_clear",vid,"","");
        });
        wbButton(g,x+654,advY,210,"Smooth copy","Create a new smooth copy of this object's current geometry",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;}
            var pts=planningVertexList(v.points()); planning("create_smooth","",v.type(),planningPoints(pts,planningClosed(v.points())));
        });
        y=advY+34;
        int adv2=y;
        wbButton(g,x,adv2,210,"Variable width","Set start/end width from Width and Amount",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("variable_width",vid,String.format(Locale.US,"%.2f",Math.max(0,v.widthBlocks())),String.format(Locale.US,"%.2f",Math.max(0,geomAmount)));
        });
        wbButton(g,x+218,adv2,210,"Elevation ramp","Set a linear elevation guide: 0 → Amount",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("elevation_linear",vid,"0",String.valueOf((int)Math.round(geomAmount)));
        });
        wbButton(g,x+436,adv2,210,"Clear elevation","Remove the object's elevation profile",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("elevation_clear",vid,"","");
        });
        wbButton(g,x+654,adv2,210,"Peak semantics","Set PEAK_Y terrain semantics using Amount",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} int yy=(int)Math.round(geomAmount);planning("terrain_semantics",vid,"PEAK_Y",yy+","+yy);
        });
        y=adv2+34;
        int adv3=y;
        wbButton(g,x,adv3,210,"Duplicate +16,+16","Duplicate this selection with a visible offset",CY,()->planning("batch_duplicate","",vid,"16,16"));
        wbButton(g,x+218,adv3,210,"Batch move dx,dz","Move this selection using Translate dx,dz",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("batch_move","",vid,geomTranslate);
        });
        wbButton(g,x+436,adv3,210,"Batch scale","Scale this selection around its combined center",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("batch_scale","",vid,String.format(Locale.US,"%.4f",geomAmount));
        });
        wbButton(g,x+654,adv3,210,"Batch rotate","Rotate this selection around its combined center",locked?DIM:CY,()->{
            if(locked){say("Unlock the planning object first");return;} planning("batch_rotate","",vid,String.format(Locale.US,"%.4f",geomAmount));
        });
        y=adv3+40;

        boolean delArmed=isArmed("plan_object_delete:"+vid);
        wbButton(g,x,y,238,delArmed?"Confirm delete":"Delete object",
                locked?"Unlock this object first":withReversibility("Delete this plan object","plan_clear"),
                locked?DIM:(delArmed?RED:GOLD),()->{
            if(locked){ say("Unlock the planning object before deleting it"); return; }
            if(confirm("plan_object_delete:"+vid,"Click Delete object again to confirm")){
                planning("delete",vid,"",""); selectedPlanObject=""; say("Deleted "+v.name());
            }
        });
        y+=44; drawWorkbenchGeometryCompletion(g,x,y,v);
    }


    /** v253.50: completes the region-metadata controls that survived the UI ground-zero reset only server-side. */
    private int drawWorkbenchRecoveryCompletion(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"REGION METADATA, TEMPLATES & SAVED CURSORS");
        var z=region();
        if(z==null){text(g,"Select a Region to edit stewardship metadata, templates and structure rules.",x,y,DIM);return y+24;}
        var meta=OceanCanvasZoneClientCache.projectRegion(z.name());
        String stage=meta==null?"RESERVED":meta.stage();String notes=meta==null?"":meta.notes();String templateId=meta==null?"":meta.templateId();
        y=wbEditableLine(g,x,y,"Region notes",notes.isBlank()?"None":notes,"region.meta.notes",notes);
        y=wbLine(g,x,y,"Region stage",title(stage),CY);
        String nextStage=switch(stage){case "RESERVED"->"TERRAIN_CONSTRUCTION";case "TERRAIN_CONSTRUCTION"->"DETAILING";case "DETAILING"->"COMPLETE";case "COMPLETE"->"ARCHIVED";default->"RESERVED";};
        int sy=y;
        wbButton(g,x,sy,195,"Stage → "+title(nextStage),"Advance the selected Region's stewardship stage",CY,()->projectMeta("stage",nextStage));
        wbButton(g,x+203,sy,195,"Save as template","Type a template name on the row below, then press Enter",CY,()->beginEdit("region.template_save",z.name()+" Template"));
        wbButton(g,x+406,sy,195,"Clear template","Detach the current Region template without changing current rules",GOLD,()->projectMeta("template",""));
        y=sy+36;
        if("region.template_save".equals(editKey))drawEditBox(g,x,y,500,27);else text(g,"Template: "+(templateId.isBlank()?"None":templateId),x,y,DIM);y+=34;
        var templates=OceanCanvasZoneClientCache.projectTemplates();
        if(!templates.isEmpty()){
            text(g,"Apply template",x,y,DIM);y+=18;int bx=x;
            for(int i=0;i<Math.min(3,templates.size());i++){var t=templates.get(i);final String tid=t.id();final String tn=t.displayName();wbButton(g,bx,y,238,trim(tn,210),"Apply this template's structure, biome, mob, color and protection rules",CY,()->projectMeta("template",tid));bx+=246;}y+=38;
        }
        y=wbSection(g,x,y,"STRUCTURE QUICK RULES");
        // These literal IDs are intentional. The main Region disclosure already supports every enum kind
        // dynamically; spelling the two historical parity gaps here also gives fast access to them.
        final String monumentKind="ocean_monument", portalKind="ruined_portal";
        String mon=z.ruleFor(monumentKind),port=z.ruleFor(portalKind);
        String monNext=nextStructureRule(mon),portNext=nextStructureRule(port);
        wbButton(g,x,y,245,"Monuments: "+structureRuleLabel(mon),"Cycle Ocean Monument rule to "+structureRuleLabel(monNext),CY,()->send(new OceanCanvasZoneSetOverrideRequestPayload(z.name(),monumentKind,monNext)));
        wbButton(g,x+253,y,245,"Ruined Portals: "+structureRuleLabel(port),"Cycle Ruined Portal rule to "+structureRuleLabel(portNext),CY,()->send(new OceanCanvasZoneSetOverrideRequestPayload(z.name(),portalKind,portNext)));
        y+=38;
        var q=OceanCanvasZoneClientCache.queue();
        y=wbLine(g,x,y,"Saved queue recovery cursor",q.recoveryId().isBlank()?"None":q.recoveryId(),q.recoveryId().isBlank()?GREEN:GOLD);
        if(!q.recoveryId().isBlank()){final String recovery=q.recoveryId();wbButton(g,x,y,260,"Discard saved queue cursor","Discard only the interrupted queue cursor; queued Regions and completed terrain remain intact",GOLD,()->{if(confirm("queue_discard_cursor:"+recovery,"Click Discard saved queue cursor again to confirm"))workspace("queue_discard_cursor",recovery,"","");});y+=38;}
        return y;
    }

    /** P1-W3 deep observability tools stay in the scrollable Workbench so the approved map shell remains clean. */
    private int drawWorkbenchP1W3(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"P1-W3 EVIDENCE, PROVENANCE & MAINTENANCE");
        int by=y;
        wbButton(g,x,by,165,"Refresh Evidence","Run the bounded health/evidence aggregation",CY,()->send(new OceanCanvasHealthRequestPayload("scan")));
        wbButton(g,x+173,by,165,"Boundary Audit","Compare intended Region geometry with explicit modified outside-ring chunks",CY,()->send(new OceanCanvasHealthRequestPayload("boundary_drift")));
        wbButton(g,x+346,by,165,"POI Integrity","Read-only modified-chunk structure/POI coherence scan; unloaded chunks stay unverified",CY,()->send(new OceanCanvasHealthRequestPayload("poi_integrity")));
        wbButton(g,x+519,by,185,"Accept Worldgen Baseline","Accept the current selected datapack/dependency/worldgen signature after review",GOLD,()->{if(confirm("p1_datapack_accept","Click Accept Worldgen Baseline again to confirm"))send(new OceanCanvasHealthRequestPayload("datapack_accept"));});
        y=by+40;
        String[] b=healthFirst("B");
        if(b!=null&&b.length>=6){boolean recorded=Boolean.parseBoolean(b[1]),changed=Boolean.parseBoolean(b[2]);y=wbLine(g,x,y,"Datapack / worldgen signature",!recorded?"No accepted baseline":changed?"CHANGED":"MATCH",!recorded||changed?GOLD:GREEN);y=wbLine(g,x+12,y,"Current signature",trim(b[3],24),DIM);}
        String[] v=healthFirst("V");if(v!=null&&v.length>=2)y=wbLine(g,x,y,"Vanilla baseline fingerprints",decodeB64(v[1]),decodeB64(v[1]).contains("loadedMismatch=0")?GREEN:GOLD);
        String[] cf=healthFirst("CF");if(cf!=null&&cf.length>=2)y=wbLine(g,x,y,"Evidence confidence",decodeB64(cf[1]),DIM);

        y+=4;y=wbSection(g,x,y,"QUEUE CAUSALITY");
        var qs=healthRows("Q");y=wbLine(g,x,y,"Outstanding targets",qs.isEmpty()?"None":qs.size()+" shown",qs.isEmpty()?GREEN:TEXT);
        for(int i=0;i<Math.min(6,qs.size());i++){String[] q=qs.get(i);if(q.length<8)continue;boolean stale=Boolean.parseBoolean(q[6]);y=wbLine(g,x+12,y,"chunk "+q[1]+","+q[2]+" · "+title(q[3]),title(q[4])+" · "+q[5]+" ms",stale?GOLD:DIM);if(stale)y=wbLine(g,x+26,y,"Dependency",decodeB64(q[7]),GOLD);}

        y+=4;y=wbSection(g,x,y,"HEALTH INBOX & CONFIDENCE");
        var incidents=healthRows("E");long open=incidents.stream().filter(r->r.length>5&&"OPEN".equals(r[5])).count();y=wbLine(g,x,y,"Open incidents",String.valueOf(open),open>0?GOLD:GREEN);
        for(int i=0;i<Math.min(6,incidents.size());i++){String[] e=incidents.get(i);if(e.length<8)continue;int c="AUTHORITATIVE".equals(e[4])?("CRITICAL".equals(e[2])||"HIGH".equals(e[2])?RED:GREEN):"USER_DEFINED".equals(e[4])?PURPLE:GOLD;String badge="AUTHORITATIVE".equals(e[4])?"[A]":"USER_DEFINED".equals(e[4])?"[U]":"[I]";int rowY=y;y=wbLine(g,x+12,y,badge+" "+title(e[3])+" · "+decodeB64(e[6]),title(e[4])+" / "+title(e[5]),c);final String iid=e[1];if("OPEN".equals(e[5]))hit(x+12,rowY,WB_W-82,20,"Click to acknowledge this health incident without deleting evidence",()->send(new OceanCanvasHealthRequestPayload("incident_state:"+iid+":ACKNOWLEDGED")));}

        y+=4;y=wbSection(g,x,y,"MAINTENANCE RECIPES");
        int editY=y;y=wbLine(g,x,y,"Recipe spec",trim(p1RecipeSpec,450),TEXT);hit(x+12,editY-3,WB_W-74,22,"Edit as Name|STEP>STEP. Destructive steps use PREGEN:Region / REWIPE:Region / RESTORE:Region.",()->beginEdit("p1.recipe_spec",p1RecipeSpec));if("p1.recipe_spec".equals(editKey))drawEditBox(g,x+170,editY-5,WB_W-240,27);
        wbButton(g,x,y,165,"Create Recipe","Create the typed recipe; all steps are server-validated",CY,()->send(new OceanCanvasHealthRequestPayload("recipe_add:"+p1RecipeSpec)));y+=38;
        var recipes=healthRows("R");if(!recipes.isEmpty()){p1RecipeIndex=Math.floorMod(p1RecipeIndex,recipes.size());String[] r=recipes.get(p1RecipeIndex);String rid=r[1],rn=r.length>2?decodeB64(r[2]):rid,steps=r.length>3?decodeB64(r[3]):"";y=wbLine(g,x,y,"Selected recipe",rn+" · "+trim(steps,360),CY);final String rrid=rid;final int rc=recipes.size();wbButton(g,x,y,105,"Previous","Select previous recipe",CY,()->p1RecipeIndex=Math.floorMod(p1RecipeIndex-1,rc));wbButton(g,x+113,y,105,"Next","Select next recipe",CY,()->p1RecipeIndex=(p1RecipeIndex+1)%rc);wbButton(g,x+226,y,150,"Run / Continue","Execute read-only steps until a destructive confirmation boundary",GREEN,()->send(new OceanCanvasHealthRequestPayload("recipe_run:"+rrid)));wbButton(g,x+384,y,150,"Resume","Resume after the normal destructive lifecycle has reached terminal state",CY,()->send(new OceanCanvasHealthRequestPayload("recipe_resume:"+rrid)));wbButton(g,x+542,y,150,"Delete","Delete this recipe definition, not its historical evidence",GOLD,()->{if(confirm("recipe_remove:"+rrid,"Click Delete again to confirm"))send(new OceanCanvasHealthRequestPayload("recipe_remove:"+rrid));});y+=38;}
        String[] run=healthFirst("Y");if(run!=null&&run.length>=5)y=wbLine(g,x,y,"Recipe run",title(run[3])+" · step "+run[2]+" · "+decodeB64(run[4]),"AWAITING_CONFIRMATION".equals(run[3])?GOLD:DIM);

        y+=4;y=wbSection(g,x,y,"WORLD STATES & BEFORE/AFTER");
        var z=region();if(z!=null){final String regionName=z.name();wbButton(g,x,y,190,"Capture Region State","Persist a bounded explicit terrain-state snapshot for the selected Region",CY,()->send(new OceanCanvasHealthRequestPayload("snapshot:"+regionName+":Region state")));y+=38;}
        var snaps=healthRows("N");if(!snaps.isEmpty()){if(beforeAfterSnapshotId.isBlank())beforeAfterSnapshotId=snaps.get(0)[1];int idx=0;for(int i=0;i<snaps.size();i++)if(snaps.get(i)[1].equals(beforeAfterSnapshotId)){idx=i;break;}String[] n=snaps.get(idx);final int sc=snaps.size();final int fi=idx;y=wbLine(g,x,y,"Before snapshot",decodeB64(n[2])+" · "+decodeB64(n[3]),Boolean.parseBoolean(n[5])?GREEN:GOLD);wbButton(g,x,y,130,"Previous State","Select older captured state",CY,()->beforeAfterSnapshotId=snaps.get(Math.floorMod(fi+1,sc))[1]);wbButton(g,x+138,y,130,"Next State","Select newer captured state",CY,()->beforeAfterSnapshotId=snaps.get(Math.floorMod(fi-1,sc))[1]);wbButton(g,x+276,y,180,beforeAfterEnabled?"Hide Swipe":"Show Before/After","Left side renders the captured explicit state; right side is the live/current map",CY,()->beforeAfterEnabled=!beforeAfterEnabled);wbButton(g,x+464,y,110,"25%","Move comparison divider",DIM,()->beforeAfterDivider=.25);wbButton(g,x+582,y,110,"75%","Move comparison divider",DIM,()->beforeAfterDivider=.75);y+=38;}

        y+=4;y=wbSection(g,x,y,"PROJECT VERIFICATION & MILESTONES");
        var pr=project();if(pr!=null){final String pid=pr.id();wbButton(g,x,y,190,"Verify Built Project","Compare implemented Plan objects with elevation intent and create punch-list deviations",CY,()->send(new OceanCanvasHealthRequestPayload("verify_project:"+pid)));if(!selectedMilestone.isBlank()){final String mid=selectedMilestone;wbButton(g,x+198,y,190,"Capture Milestone State","Capture Plan/Region/health/task state and diff it from the previous milestone state",CY,()->send(new OceanCanvasHealthRequestPayload("milestone_capture:"+pid+":"+mid)));}y+=38;}
        if(!selectedPlanObject.isBlank()){int ty=y;y=wbLine(g,x,y,"Terraform target Y",String.valueOf(p1TargetY),TEXT);hit(x+12,ty-3,250,22,"Edit target elevation used by the planning-only quantity estimator",()->beginEdit("p1.target_y",String.valueOf(p1TargetY)));if("p1.target_y".equals(editKey))drawEditBox(g,x+270,ty-5,120,27);final String oid=selectedPlanObject;wbButton(g,x,y,220,"Estimate Add / Remove","Bounded server-side surface sampling; no terrain mutation",CY,()->send(new OceanCanvasHealthRequestPayload("terraform_estimate:"+oid+":"+p1TargetY)));y+=38;}
        var dev=healthRows("X");if(!dev.isEmpty()){String[] d=dev.get(0);if(d.length>=6){y=wbLine(g,x,y,"Newest deviation",d[2]+" / "+d[3]+" · "+title(d[4]),GOLD);final String pid=d[2],oid=d[3];wbButton(g,x,y,150,"Accept","Intentional deviation; suppress as defect while preserving evidence",GREEN,()->send(new OceanCanvasHealthRequestPayload("deviation_state:"+pid+":"+oid+":ACCEPTED:Accepted from Workbench")));wbButton(g,x+158,y,150,"Temporary","Keep visible as temporary work",GOLD,()->send(new OceanCanvasHealthRequestPayload("deviation_state:"+pid+":"+oid+":TEMPORARY:Temporary from Workbench")));wbButton(g,x+316,y,150,"Defect","Classify as punch-list defect",RED,()->send(new OceanCanvasHealthRequestPayload("deviation_state:"+pid+":"+oid+":DEFECT:Defect from Workbench")));y+=38;}}

        y+=4;y=wbSection(g,x,y,"PROVENANCE & SESSION CONTINUITY");
        var chunkEvidence=healthRows("H");for(int i=0;i<Math.min(6,chunkEvidence.size());i++){String[] h=chunkEvidence.get(i);if(h.length<10)continue;String prov=h[4],biome=decodeB64(h[3]),gen=decodeB64(h[5]);int pc=prov.startsWith("USER_")?PURPLE:prov.startsWith("OC_")||"RESTORED".equals(prov)?CY:"INFERRED".equals(prov)?GOLD:GREEN;y=wbLine(g,x+12,y,"Biome · chunk "+h[1]+","+h[2],prettyId(biome)+" · "+title(prov)+" · "+(gen.isBlank()?"version unknown":gen),pc);}
        var legacy=healthRows("J");for(int i=0;i<Math.min(4,legacy.size());i++){String[] j=legacy.get(i);if(j.length>=4)y=wbLine(g,x+12,y,"Generation era "+decodeB64(j[1]),j[2]+" chunks · "+title(j[3]),"AUTHORITATIVE".equals(j[3])?GREEN:GOLD);}
        var structures=healthRows("U");for(int i=0;i<Math.min(4,structures.size());i++){String[] u=structures.get(i);if(u.length>=9)y=wbLine(g,x+12,y,"Structure · "+title(u[2])+" · "+title(u[3]),"("+u[4]+","+u[5]+")→("+u[6]+","+u[7]+")",u[3].contains("OC_")?CY:"USER_DEFINED".equals(u[3])?PURPLE:GOLD);}
        String[] recap=healthFirst("Z");if(recap!=null&&recap.length>=4)y=wbLine(g,x,y,"Previous session recap",decodeB64(recap[3]),TEXT);
        y+=4;wbButton(g,x,y,180,focusMode?"Exit Focus Mode":"Enter Focus Mode","Hide unrelated map layers/projects/diagnostics around the selected task/project",CY,()->focusMode=!focusMode);
        return y+38;
    }

    /** P1-W4 stewardship, review, session-planning and long-horizon maintenance tools. */
    private int drawWorkbenchP1W4(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"P1-W4 STEWARDSHIP, REVIEW & SESSION CONTROL");
        String[] perms=healthFirst("PM");
        if(perms!=null&&perms.length>=7){y=wbLine(g,x,y,"Role-scoped permissions","View "+yes(perms[1])+" · Edit "+yes(perms[2])+" · Projects "+yes(perms[3])+" · World ops "+yes(perms[4])+" · Diagnostics "+yes(perms[5])+" · Admin "+yes(perms[6]),CY);}

        y+=4;y=wbSection(g,x,y,"WORLD SCALE & STORAGE");
        String[] sc=healthFirst("SC");if(sc!=null&&sc.length>=9){y=wbLine(g,x,y,"Defined / Reserved / Protected",sc[1]+" / "+sc[2]+" / "+sc[3],TEXT);y=wbLine(g,x,y,"Canvas / Restored / Modified",sc[4]+" / "+sc[5]+" / "+sc[6],CY);y=wbLine(g,x,y,"Untouched estimate",sc[7]+" chunks",GREEN);}
        wbButton(g,x,y,190,"Capture Scale Snapshot","Persist the current strategic world-area categories for long-term trend comparison",CY,()->send(new OceanCanvasHealthRequestPayload("scale_capture")));
        wbButton(g,x+198,y,190,"Sample Storage","Measure bounded OC/region/POI/entity storage and update the long-term forecast",CY,()->send(new OceanCanvasHealthRequestPayload("storage_sample")));y+=38;
        String[] st=healthFirst("ST");if(st!=null&&st.length>=9){long total=parseLongOr(st[2],0),daily=parseLongOr(st[4],0),year=parseLongOr(st[6],0);y=wbLine(g,x,y,"Storage sample",formatBytesUi(total)+" · growth "+formatBytesUi(daily)+"/day · "+title(st[7]),"UNVERIFIED".equals(st[7])?GOLD:GREEN);y=wbLine(g,x,y,"365-day linear projection",formatBytesUi(year),"UNVERIFIED".equals(st[7])?GOLD:DIM);}

        y+=4;y=wbSection(g,x,y,"UPGRADE DIFF & LAYER DEPENDENCIES");
        String[] ud=healthFirst("UD");if(ud!=null&&ud.length>=7){int c="CHANGED".equals(ud[1])?GOLD:"MATCH".equals(ud[1])?GREEN:DIM;y=wbLine(g,x,y,"Upgrade diff",title(ud[1])+" · current "+decodeB64(ud[3])+(decodeB64(ud[4]).isBlank()?"":" · baseline "+decodeB64(ud[4])),c);if(!decodeB64(ud[5]).isBlank())y=wbLine(g,x+12,y,"Changes",trim(decodeB64(ud[5]),500),GOLD);}
        wbButton(g,x,y,210,"Accept Upgrade Baseline","Admin-only: record current Minecraft/dependency/datapack/managed-area evidence as the comparison baseline",GOLD,()->{if(confirm("p1w4_upgrade","Click Accept Upgrade Baseline again to confirm"))send(new OceanCanvasHealthRequestPayload("upgrade_accept"));});y+=38;
        String[] lg=healthFirst("LG");if(lg!=null&&lg.length>=5)y=wbLine(g,x,y,"Layer dependency graph",lg[1]+" nodes · "+lg[2]+" edges · "+lg[3]+" missing refs",parseIntOr(lg[3],0)>0?GOLD:GREEN);
        var edges=healthRows("LE");for(int i=0;i<Math.min(6,edges.size());i++){String[] e=edges.get(i);if(e.length>=4)y=wbLine(g,x+12,y,trim(decodeB64(e[1]),260),decodeB64(e[3])+" → "+trim(decodeB64(e[2]),230),DIM);}

        y+=4;y=wbSection(g,x,y,"SESSION PLAN");
        wbButton(g,x,y,140,"Plan 30 min","Generate a short task queue using readiness, priority, distance and MATERIAL: checklist readiness",CY,()->send(new OceanCanvasHealthRequestPayload("session_generate:30")));
        wbButton(g,x+148,y,140,"Plan 60 min","Generate a one-hour task queue",CY,()->send(new OceanCanvasHealthRequestPayload("session_generate:60")));
        wbButton(g,x+296,y,140,"Plan 120 min","Generate a two-hour task queue",CY,()->send(new OceanCanvasHealthRequestPayload("session_generate:120")));y+=38;
        String[] sp=healthFirst("SP");if(sp!=null&&sp.length>=8){String sid=sp[1],state=sp[4];y=wbLine(g,x,y,"Active session",decodeB64(sp[2])+" · "+sp[3]+" min · "+title(state)+" · "+sp[5]+"/"+sessionTaskCount(sp[6]),"ACTIVE".equals(state)?GREEN:"PAUSED".equals(state)?GOLD:DIM);y=wbLine(g,x+12,y,"Why these tasks",trim(decodeB64(sp[7]),520),DIM);final String fsid=sid;wbButton(g,x,y,120,"Start","Start/resume this persisted session plan",GREEN,()->send(new OceanCanvasHealthRequestPayload("session_action:"+fsid+"|START")));wbButton(g,x+128,y,120,"Pause","Pause without losing the cursor",GOLD,()->send(new OceanCanvasHealthRequestPayload("session_action:"+fsid+"|PAUSE")));wbButton(g,x+256,y,120,"Next task","Advance one checkpoint; completes after the last item",CY,()->send(new OceanCanvasHealthRequestPayload("session_action:"+fsid+"|NEXT")));wbButton(g,x+384,y,120,"Cancel","Cancel only the session plan; never world work",RED,()->{if(confirm("session_cancel:"+fsid,"Click Cancel again to confirm"))send(new OceanCanvasHealthRequestPayload("session_action:"+fsid+"|CANCEL"));});y+=38;}

        y+=4;y=wbSection(g,x,y,"RECENT CONTEXT STRIP");
        var rz=region();var rp=project();
        wbButton(g,x,y,170,"Pin selected Region","Persist the selected Region in recent context",rz==null?DIM:CY,()->{var q=region();if(q!=null)send(new OceanCanvasHealthRequestPayload("context_region:"+q.name()));});
        wbButton(g,x+178,y,170,"Pin selected Project","Persist the selected Project in recent context",rp==null?DIM:CY,()->{var q=project();if(q!=null)send(new OceanCanvasHealthRequestPayload("context_project:"+q.id()));});
        wbButton(g,x+356,y,170,"Pin selected Plan","Persist the selected Plan object in recent context",selectedPlanObject.isBlank()?DIM:CY,()->{if(!selectedPlanObject.isBlank())send(new OceanCanvasHealthRequestPayload("context_plan:"+selectedPlanObject));});y+=38;
        var contexts=healthRows("RC");for(int i=0;i<Math.min(8,contexts.size());i++){String[] c=contexts.get(i);if(c.length<8)continue;String type=c[2],sid=decodeB64(c[3]),label=decodeB64(c[4]);int cx=parseIntOr(c[5],0),cz=parseIntOr(c[6],0);int ry=y;y=wbLine(g,x+12,y,title(type)+" · "+label,cx+", "+cz,DIM);final String ft=type,fi=sid,fl=label;final int fx=cx,fz=cz;hit(x+12,ry-3,WB_W-85,22,"Jump to this recent context",()->{mapViewX=fx;mapViewZ=fz;coordX=fx;coordZ=fz;if("REGION".equals(ft))selectedRegion=fi;else if("PROJECT".equals(ft))selectedProject=fi;else if("PLAN".equals(ft))selectedPlanObject=fi;say("Recent context: "+fl);});}

        y+=4;y=wbSection(g,x,y,"SHARED REVIEW BOARD");
        if(rp!=null){final String pid=rp.id(),pl=rp.name();wbButton(g,x,y,180,"Review selected Project","Add the selected Project to the shared approval queue",CY,()->send(new OceanCanvasHealthRequestPayload("review_add:PROJECT|"+pid+"|"+pl+"|Review requested from Workbench")));}
        if(!selectedPlanObject.isBlank()){final String oid=selectedPlanObject;var po=OceanCanvasZoneClientCache.planningVectors().stream().filter(v->v.id().equals(oid)).findFirst().orElse(null);final String lab=po==null?oid:po.name();wbButton(g,x+188,y,180,"Review selected Plan","Add the selected Plan object to the shared approval queue",CY,()->send(new OceanCanvasHealthRequestPayload("review_add:PLAN|"+oid+"|"+lab+"|Review requested from Workbench")));}
        if(rz!=null){final String rn=rz.name();wbButton(g,x+376,y,180,"Review selected Region","Add the selected Region to the shared approval queue",CY,()->send(new OceanCanvasHealthRequestPayload("review_add:REGION|"+rn+"|"+rn+"|Review requested from Workbench")));}y+=38;
        var reviews=healthRows("RV");for(int i=0;i<Math.min(6,reviews.size());i++){String[] r=reviews.get(i);if(r.length<8)continue;String rid=r[1],state=r[5],label=decodeB64(r[4]);y=wbLine(g,x+12,y,label,title(r[2])+" · "+title(state),"OPEN".equals(state)||"CHANGES_REQUESTED".equals(state)?GOLD:GREEN);if("OPEN".equals(state)||"CHANGES_REQUESTED".equals(state)){final String fr=rid;wbButton(g,x+12,y,120,"Approve","Approve this shared review item",GREEN,()->send(new OceanCanvasHealthRequestPayload("review_decide:"+fr+"|APPROVED|Approved from Workbench")));wbButton(g,x+140,y,160,"Request changes","Keep it open with a changes-requested decision",GOLD,()->send(new OceanCanvasHealthRequestPayload("review_decide:"+fr+"|CHANGES_REQUESTED|Changes requested from Workbench")));wbButton(g,x+308,y,120,"Reject","Reject without deleting review history",RED,()->send(new OceanCanvasHealthRequestPayload("review_decide:"+fr+"|REJECTED|Rejected from Workbench")));y+=38;}}

        y+=4;y=wbSection(g,x,y,"RETIREMENT WORKFLOW");
        if(rp==null)y=wbLine(g,x,y,"Selected Project","None",DIM);else{final String pid=rp.id();y=wbLine(g,x,y,"Selected Project",rp.name()+" · "+title(rp.status()),TEXT);wbButton(g,x,y,160,"Archive only","Retire metadata/history and lock the Project without changing terrain",GOLD,()->{if(confirm("retire_archive:"+pid,"Click Archive only again to confirm"))send(new OceanCanvasHealthRequestPayload("retire_project:"+pid+"|ARCHIVE_ONLY|Archived from Workbench"));});wbButton(g,x+168,y,160,"Leave ruins","Archive the Project and explicitly preserve its physical remains",GOLD,()->{if(confirm("retire_ruins:"+pid,"Click Leave ruins again to confirm"))send(new OceanCanvasHealthRequestPayload("retire_project:"+pid+"|LEAVE_RUINS|Physical remains intentionally retained"));});wbButton(g,x+336,y,210,"Retire + Restore Region","Archive first, then require the normal Restore dry-run/confirmation for the linked Region",RED,()->{if(confirm("retire_restore:"+pid,"Click Retire + Restore Region again to confirm"))send(new OceanCanvasHealthRequestPayload("retire_project:"+pid+"|RESTORE_REGION|Restore requested after retirement"));});y+=38;}
        var retire=healthRows("RT");for(int i=0;i<Math.min(4,retire.size());i++){String[] r=retire.get(i);if(r.length>=8)y=wbLine(g,x+12,y,title(r[2])+" · "+decodeB64(r[3]),title(r[4])+" · "+title(r[5]),"AWAITING_CONFIRMATION".equals(r[5])?GOLD:GREEN);}

        y+=4;y=wbSection(g,x,y,"SCHEDULED MAINTENANCE");
        var maint=healthRows("MT");for(int i=0;i<Math.min(8,maint.size());i++){String[] m=maint.get(i);if(m.length<8)continue;boolean due="DUE".equals(m[6]);int row=y;y=wbLine(g,x+12,y,decodeB64(m[2]),due?("DUE · every "+m[3]+" days"):("Scheduled · every "+m[3]+" days"),due?GOLD:GREEN);final String mid=m[1];if(due)hit(x+12,row-3,WB_W-85,22,"Mark this maintenance recommendation completed and schedule its next due date",()->send(new OceanCanvasHealthRequestPayload("maintenance_complete:"+mid)));}
        return y+12;
    }

    private static String yes(String raw){return Boolean.parseBoolean(raw)?"yes":"no";}
    private static long parseLongOr(String raw,long fallback){try{return Long.parseLong(raw);}catch(Exception e){return fallback;}}
    private static String formatBytesUi(long v){double n=Math.max(0,v);String[] u={"B","KiB","MiB","GiB","TiB"};int i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return String.format(Locale.US,i==0?"%.0f %s":"%.1f %s",n,u[i]);}
    private static int sessionTaskCount(String b64){String s=decodeB64(b64);return s.isBlank()?0:s.split(",").length;}

    private List<String[]> healthRows(String tag){var out=new ArrayList<String[]>();String packed=OceanCanvasZoneClientCache.healthPacked();if(packed==null||packed.isBlank())return out;for(String line:packed.split("\\n")){String[] f=line.split("\\t",-1);if(f.length>0&&tag.equals(f[0]))out.add(f);}return out;}
    private String[] healthFirst(String tag){var r=healthRows(tag);return r.isEmpty()?null:r.get(0);}

    private static String nextStructureRule(String rule){return switch(rule){case "INHERIT"->"FORCE_ON";case "FORCE_ON"->"FORCE_OFF";default->"INHERIT";};}
    private static String structureRuleLabel(String rule){return switch(rule){case "FORCE_ON"->"Always";case "FORCE_OFF"->"Never";default->"Default";};}

    /** v253.50: completes reference registration, layer ordering/export and terrain revision metadata. */
    private int drawWorkbenchLibraryCompletion(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"REFERENCE REGISTRATION & VIEW PRESETS");
        var refs=OceanCanvasZoneClientCache.planningReferences();
        var ref=refs.stream().filter(r->r.id().equals(selectedReference)).findFirst().orElse(null);
        if(ref!=null){
            String initial=ref.registrationPoints()==null||ref.registrationPoints().isBlank()
                    ?"0,0,"+ref.minX()+","+ref.minZ()+";1,1,"+ref.maxX()+","+ref.maxZ():ref.registrationPoints();
            y=wbEditableLine(g,x,y,"Control points imageX,imageY,worldX,worldZ",ref.registrationPoints().isBlank()?"Click to register 2-3 point pairs":ref.registrationPoints(),"ref.registration",initial);
            y=wbLine(g,x,y,"Registration transform",ref.minX()+","+ref.minZ()+" → "+ref.maxX()+","+ref.maxZ()+" · "+String.format(Locale.US,"%.1f°",ref.rotation()),DIM);
        }else y=wbLine(g,x,y,"Reference registration","Select a reference layer above",DIM);
        var presets=OceanCanvasZoneClientCache.viewPresets();
        OceanCanvasZoneClientCache.ViewPreset deletable=null;for(int i=presets.size()-1;i>=0;i--){var p=presets.get(i);if(!p.id().equals("physical_geography")&&!p.id().equals("civilization")){deletable=p;break;}}
        if(deletable!=null){final String id=deletable.id(),name=deletable.name();wbButton(g,x,y,230,"Delete preset: "+trim(name,120),"Delete this user-created visibility preset",RED,()->{if(confirm("preset_delete:"+id,"Click Delete preset again to confirm"))planning("preset_delete",id,"","");});}
        var groups=OceanCanvasZoneClientCache.planGroups();var group=groups.stream().filter(q->q.id().equals(selectedPlan)).findFirst().orElse(null);
        if(group!=null){final String gid=group.id();int gy=y;wbButton(g,x+238,gy,150,"Layer ↑","Move selected Plan layer later/higher in draw order",CY,()->planning("group_move",gid,"up",""));wbButton(g,x+396,gy,150,"Layer ↓","Move selected Plan layer earlier/lower in draw order",CY,()->planning("group_move",gid,"down",""));wbButton(g,x+554,gy,210,"Export Gaea masks","Export visible geometry in this Plan layer as Gaea-ready SVG masks",CY,()->planning("export_gaea_masks",gid,"layer",group.name()));y=gy+38;}
        if(group==null&&!presets.isEmpty()){var p=presets.get(0);final String pid=p.id();wbButton(g,x,y,260,"Export preset Gaea masks","Export the first saved preset as isolated Gaea mask artwork",CY,()->planning("export_gaea_masks",pid,"preset",p.name()));y+=38;}

        y=wbSection(g,x,y,"TERRAIN REVISION REGISTRATION");
        if(selectedTerrainAsset.isBlank()){text(g,"Select or create a Terrain Asset above to register files, heightmaps and checkpoints.",x,y,DIM);return y+24;}
        var asset=OceanCanvasZoneClientCache.terrainAssets().stream().filter(a->a.id().equals(selectedTerrainAsset)).findFirst().orElse(null);if(asset==null)return y;
        final String aid=asset.id();
        y=wbEditableLine(g,x,y,"Revision stage,file,width,height,bpp",terrainRevisionSpec.isBlank()?"Click to enter real revision metadata":terrainRevisionSpec,"terrain.revision_spec",terrainRevisionSpec);
        int r1=y;wbButton(g,x,r1,230,"Register revision",terrainRevisionSpec.isBlank()?"Enter real revision metadata first":"Add metadata for a Gaea/WorldPainter revision; this never changes terrain",terrainRevisionSpec.isBlank()?DIM:CY,()->{if(terrainRevisionSpec.isBlank()){say("Enter revision metadata first");return;}planning("terrain_revision_add",aid,"Workbench revision",csvToTabs(terrainRevisionSpec));});y=r1+38;
        y=wbEditableLine(g,x,y,"Heightmap stage,file,w,h,bpp,sha256,bits,min,max,minY,maxY",terrainHeightmapSpec.isBlank()?"Click to enter analyzed heightmap metadata":terrainHeightmapSpec,"terrain.heightmap_spec",terrainHeightmapSpec);
        int r2=y;wbButton(g,x,r2,230,"Register analyzed heightmap",terrainHeightmapSpec.isBlank()?"Enter validated heightmap metadata first":"Register validated heightmap metadata and observed/mapped ranges",terrainHeightmapSpec.isBlank()?DIM:CY,()->{if(terrainHeightmapSpec.isBlank()){say("Enter validated heightmap metadata first");return;}planning("terrain_heightmap_revision",aid,"",csvToTabs(terrainHeightmapSpec));});y=r2+38;
        y=wbEditableLine(g,x,y,"Placement metadata",asset.placementData().isBlank()?(terrainPlacementSpec.isBlank()?"Click to enter placement/alignment metadata":terrainPlacementSpec):asset.placementData(),"terrain.placement_spec",asset.placementData().isBlank()?terrainPlacementSpec:asset.placementData());
        int r3=y;wbButton(g,x,r3,230,"Save placement",terrainPlacementSpec.isBlank()?"Enter placement/alignment metadata first":"Persist non-destructive terrain placement/alignment metadata",terrainPlacementSpec.isBlank()?DIM:CY,()->{if(terrainPlacementSpec.isBlank()){say("Enter placement metadata first");return;}planning("terrain_placement",aid,terrainPlacementSpec,"");});y=r3+38;
        y=wbEditableLine(g,x,y,"Review stage,file,sha256,loaded,missing,mean,max",terrainReviewSpec.isBlank()?"Click to enter measured review metadata":terrainReviewSpec,"terrain.review_spec",terrainReviewSpec);
        int r4=y;wbButton(g,x,r4,230,"Save review checkpoint",terrainReviewSpec.isBlank()?"Enter measured review metadata first":"Record a review checkpoint with load/deviation metrics",terrainReviewSpec.isBlank()?DIM:CY,()->{if(terrainReviewSpec.isBlank()){say("Enter review checkpoint metadata first");return;}planning("terrain_review_checkpoint",aid,"Workbench checkpoint",csvToTabs(terrainReviewSpec));});y=r4+38;
        return y;
    }

    /** v253.50: completes project import/creation, prototype linking, transition feather and journal workflows. */
    private int drawWorkbenchForeverCompletion(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"PROJECT PACKAGES & EXECUTION BRIDGES");
        y=wbEditableLine(g,x,y,"Project inbox file",projectImportFile.isBlank()?"Click to enter a .oceanproject filename":projectImportFile,"project.import_file",projectImportFile);
        var p=project();final String mergeId=p==null?"":p.id();int iy=y;
        wbButton(g,x,iy,205,"Import as new Project",projectImportFile.isBlank()?"Enter a package filename first":"Import a .oceanproject package from this world's oceancanvas/projects inbox",projectImportFile.isBlank()?DIM:CY,()->{if(projectImportFile.isBlank()){say("Enter a project package filename first");return;}workspace("project_import","",projectImportFile,"");});
        wbButton(g,x+213,iy,205,"Merge into active Project",p==null?"Select a Project first":(projectImportFile.isBlank()?"Enter a package filename first":"Additively merge this package into "+p.name()),p==null||projectImportFile.isBlank()?DIM:CY,()->{if(p==null){say("Select a Project first");return;}if(projectImportFile.isBlank()){say("Enter a project package filename first");return;}workspace("project_import_merge","",projectImportFile,mergeId);});
        wbButton(g,x+426,iy,205,"Journal checkpoint","Append a current-position project journal entry",CY,()->workspace("journal","","Workbench checkpoint",""));
        y=iy+38;
        var pv=planObject();
        if(pv!=null){final String planId=pv.id();final String regionName=region()==null?"":region().name();wbButton(g,x,y,260,"Create Project from selected Plan","Create/reuse an execution Project and starter task linked to this Plan object",CY,()->workspace("project_from_plan",planId,"Execute "+pv.name(),regionName));y+=38;}
        if(p!=null){
            var plots=OceanCanvasZoneClientCache.prototypePlots(p.id());var transitions=OceanCanvasZoneClientCache.transitionZones(p.id());
            if(!plots.isEmpty()&&!selectedTerrainAsset.isBlank()){var plot=plots.get(0);final String plotId=plot.id(),assetId=selectedTerrainAsset;wbButton(g,x,y,260,"Link Terrain Asset to Prototype","Attach the selected Terrain Asset to the newest Prototype Plot",CY,()->workspace("prototype_asset","",plotId,assetId));y+=38;}
            if(!transitions.isEmpty()){var t=transitions.get(0);final String tid=t.id();int f=Math.max(0,t.featherBlocks());int ty=y;wbButton(g,x,ty,205,"Feather −64","Reduce the newest Transition Zone blend width",CY,()->workspace("transition_feather","",tid,String.valueOf(Math.max(0,f-64))));wbButton(g,x+213,ty,205,"Feather +64","Increase the newest Transition Zone blend width",CY,()->workspace("transition_feather","",tid,String.valueOf(Math.min(16384,f+64))));y=ty+38;}
        }
        return y;
    }

    /** v253.50: completes the review/stewardship data model instead of leaving it command-only. */
    private int drawWorkbenchKnowledgeCompletion(GuiGraphicsExtractor g,int x,int y,String tt,String tid){
        y=wbSection(g,x,y,"CLAIMS, AUTHORSHIP & RELATIONSHIPS");
        y=wbEditableLine(g,x,y,"Claim key",knowledgeClaimKey.isBlank()?"Click to enter a claim key":knowledgeClaimKey,"knowledge.claim_key",knowledgeClaimKey);
        y=wbEditableLine(g,x,y,"Claim value",knowledgeClaimValue.isBlank()?"Click to enter the factual/design claim":knowledgeClaimValue,"knowledge.claim_value",knowledgeClaimValue);
        y=wbEditableLine(g,x,y,"Authorship source",knowledgeAuthorshipSource,"knowledge.authorship_source",knowledgeAuthorshipSource);
        final String targetType=tt,targetId=tid;
        int ky=y;
        wbButton(g,x,ky,205,"Save design claim",knowledgeClaimKey.isBlank()||knowledgeClaimValue.isBlank()?"Enter a real claim key and value first":"Create/update a KNOWN Design Intent claim using the fields above",knowledgeClaimKey.isBlank()||knowledgeClaimValue.isBlank()?DIM:CY,()->{if(knowledgeClaimKey.isBlank()||knowledgeClaimValue.isBlank()){say("Enter a claim key and value first");return;}workspace("world_claim_put","",targetType,b64(targetId)+"\tDESIGN_INTENT\t"+b64(knowledgeClaimKey)+"\t"+b64(knowledgeClaimValue)+"\tKNOWN\tMANUAL_NOTE\t"+b64("")+"\t"+b64("Created from Workbench"));});
        wbButton(g,x+213,ky,205,"Mark authored","Record WHOLE-layer authorship provenance for this World Object",CY,()->workspace("authorship_put","",targetType,b64(targetId)+"\tWHOLE\tAUTHORED\t"+b64(knowledgeAuthorshipSource)+"\t"+b64("Declared from Workbench")));
        var claims=OceanCanvasZoneClientCache.worldClaims(targetType,targetId);var authors=OceanCanvasZoneClientCache.authorshipProvenance(targetType,targetId);
        if(!claims.isEmpty()){var c=claims.get(claims.size()-1);final String cid=c.id();wbButton(g,x+426,ky,145,"Delete claim","Delete the latest claim on this target",RED,()->{if(confirm("world_claim_delete:"+cid,"Click Delete claim again to confirm"))workspace("world_claim_delete",cid,"","");});}
        if(!authors.isEmpty()){var a=authors.get(authors.size()-1);final String aid=a.id();wbButton(g,x+579,ky,145,"Delete authorship","Delete the latest authorship record",RED,()->{if(confirm("authorship_delete:"+aid,"Click Delete authorship again to confirm"))workspace("authorship_delete",aid,"","");});}
        y=ky+38;
        String[] other=alternateWorkbenchTarget(targetType,targetId);
        if(other!=null){final String ot=other[0],oid=other[1];int ry=y;wbButton(g,x,ry,260,"Relate to "+title(ot),"Create a RELATED_TO edge from this target to "+ot+" "+oid,CY,()->workspace("world_relationship_put","",targetType,b64(targetId)+"\tRELATED_TO\t"+ot+"\t"+b64(oid)+"\t"+b64("Linked from Workbench")+"\tVALIDATED"));var rels=OceanCanvasZoneClientCache.worldRelationshipsFor(targetType,targetId);if(!rels.isEmpty()){var rel=rels.get(rels.size()-1);final String rid=rel.id();wbButton(g,x+268,ry,230,"Delete latest relationship","Remove the newest relationship involving this target",RED,()->{if(confirm("world_relationship_delete:"+rid,"Click Delete relationship again to confirm"))workspace("world_relationship_delete",rid,"","");});}y=ry+38;}

        y=wbSection(g,x,y,"OBSERVATIONS, EVENTS & INTENT");
        int ox=(int)Math.round(mapViewX),oz=(int)Math.round(mapViewZ);int ey=y;
        wbButton(g,x,ey,230,"Add history event","Record a durable authored-world history event for this target",CY,()->workspace("world_event_add","",targetType,b64(targetId)+"\tNOTE\t"+b64("Workbench checkpoint")+"\t"+b64("Recorded from the Ocean Canvas Workbench")+"\t"+b64("")+"\t"+b64("workbench")+"\t"+System.currentTimeMillis()));
        var obs=OceanCanvasZoneClientCache.worldObservationsFor(targetType,targetId);if(!obs.isEmpty()){var o=obs.get(0);final String oid=o.id();wbButton(g,x+238,ey,230,"Delete latest observation","Remove the newest observation for this target",RED,()->{if(confirm("world_observation_delete:"+oid,"Click Delete observation again to confirm"))workspace("world_observation_delete",oid,"","");});}
        var intent=OceanCanvasZoneClientCache.intentResolution(targetType,targetId);if(intent!=null){final String iid=intent.id();wbButton(g,x+476,ey,230,"Clear intent resolution","Remove the declared Resolution of Intent for this target",GOLD,()->{if(confirm("intent_resolution_delete:"+iid,"Click Clear intent again to confirm"))workspace("intent_resolution_delete",iid,"","");});}
        y=ey+38;

        if("PLAN".equals(targetType)){
            var plan=OceanCanvasZoneClientCache.planningVectors().stream().filter(v->v.id().equals(targetId)).findFirst().orElse(null);
            if(plan!=null){
                y=wbSection(g,x,y,"CANDIDATE → DESIGN REVIEW");
                var candidates=OceanCanvasZoneClientCache.candidateEditsFor(targetType,targetId);var revisions=OceanCanvasZoneClientCache.designRevisionsFor(targetType,targetId);
                int cy=y;final String obsId=obs.isEmpty()?"":obs.get(0).id();
                wbButton(g,x,cy,190,"Draft from current Plan","Create a DRAFT Candidate carrying the current canonical geometry/fingerprint",CY,()->workspace("candidate_edit_put","","PLAN",packNewPlanCandidate(plan,obsId,ox,oz)));
                if(!candidates.isEmpty()){
                    var c=candidates.get(0);final String cid=c.id();final var cc=c;
                    wbButton(g,x+198,cy,160,"Approve Candidate","Mark the newest Candidate APPROVED while preserving its provenance",GREEN,()->workspace("candidate_edit_put",cid,c.targetType(),packCandidate(cc,"APPROVED")));
                    wbButton(g,x+366,cy,160,"Promote to Design","Promote an APPROVED, base-current Candidate into canonical Plan geometry",c.status().equals("APPROVED")?GREEN:DIM,()->{if(!c.status().equals("APPROVED")){say("Approve the Candidate before promotion");return;}workspace("candidate_promote_design",cid,"","");});
                    wbButton(g,x+534,cy,160,"Delete Candidate","Delete the newest Candidate record",RED,()->{if(confirm("candidate_edit_delete:"+cid,"Click Delete Candidate again to confirm"))workspace("candidate_edit_delete",cid,"","");});
                }
                y=cy+38;
                if(!revisions.isEmpty()){var rev=revisions.get(0);final String rid=rev.id();wbButton(g,x,y,260,"Restore revision as Candidate","Create a DRAFT Candidate from this historical Design revision; canonical Plan is not changed",CY,()->workspace("design_revision_restore_candidate",rid,"",""));y+=38;}
            }
        }
        return y;
    }

    /** v253.50: completes topology, manual Bezier, elevation, hydrology, scenario and ordering controls. */
    private int drawWorkbenchGeometryCompletion(GuiGraphicsExtractor g,int x,int y,OceanCanvasZoneClientCache.PlanningVector v){
        final String vid=v.id();final boolean locked=v.locked();var pts=planningVertexList(v.points());int n=pts.size()/2;
        y=wbSection(g,x,y,"TOPOLOGY, BEZIER & PUSH/PULL");
        boolean closed=isClosedPlanningType(v.type());
        int by=y;
        wbButton(g,x,by,190,"Manual Bezier handle","Write handles for the selected vertex using Amount as handle length",locked||n==0?DIM:CY,()->{if(locked||n==0){say("Unlock and select a Plan vertex first");return;}int i=Math.floorMod(selectedPlanVertex,n),px=pts.get(i*2),pz=pts.get(i*2+1),a=Math.max(1,(int)Math.round(Math.abs(geomAmount)));planning("bezier_handles",vid,i+","+(px-a)+","+pz+","+(px+a)+","+pz,"");});
        wbButton(g,x+198,by,160,"Cut middle segment","Split an open path into two Plan objects at its middle segment",locked||closed||n<3?DIM:CY,()->{if(locked||closed||n<3){say("Cut Path needs an unlocked open path with at least 3 vertices");return;}int seg=Math.max(0,Math.min(n-2,(n-2)/2));int ax=pts.get(seg*2),az=pts.get(seg*2+1),bx=pts.get((seg+1)*2),bz=pts.get((seg+1)*2+1);planning("cut_path",vid,String.valueOf(seg),((ax+bx)/2)+","+((az+bz)/2));});
        var other=openPartner(vid);final String otherId=other==null?"":other.id();
        wbButton(g,x+366,by,160,"Join next path","Join this unlocked open path with the next available unlocked open path",locked||closed||other==null?DIM:CY,()->{if(locked||closed||otherId.isBlank()){say("Another unlocked open path is required");return;}planning("join_paths","",vid+","+otherId,"");});
        wbButton(g,x+534,by,160,"Link endpoints","Snap and persist shared endpoint topology with another unlocked path",locked||other==null?DIM:CY,()->{if(locked||otherId.isBlank()){say("Another unlocked path is required");return;}planning("link_endpoints",vid,otherId,"");});
        y=by+38;
        int py=y;
        wbButton(g,x,py,230,"Push/Pull at map centre","Sculpt the nearest middle segment toward the current map centre; Amount controls influence",locked||n<2?DIM:CY,()->{if(locked||n<2){say("Push/Pull needs an unlocked path with at least 2 vertices");return;}int seg=Math.max(0,Math.min(n-2,(n-2)/2));planning("push_pull",vid,String.valueOf(seg),String.format(Locale.US,"%.2f,%.2f,%.2f",mapViewX,mapViewZ,Math.max(1D,Math.abs(geomAmount))));});
        wbButton(g,x+238,py,230,"3-point elevation profile","Set 0→Amount→0 elevation guidance across this Plan object",locked?DIM:CY,()->{if(locked){say("Unlock the Plan object first");return;}int peak=(int)Math.round(geomAmount);planning("elevation_profile",vid,"0,0;0.5,"+peak+";1,0","");});
        wbButton(g,x+476,py,230,"Order +1","Move this Plan object one draw-order step higher",locked?DIM:CY,()->{if(locked){say("Unlock the Plan object first");return;}planning("order",vid,String.valueOf(Math.min(10000,v.drawOrder()+1)),"");});
        y=py+38;

        y=wbSection(g,x,y,"HYDROLOGY & DESIGN SCENARIO");
        String role=guideValueClient(v.guideData(),"HYDRO_ROLE=");String nextRole=switch(role){case "SOURCE"->"TRIBUTARY";case "TRIBUTARY"->"MAINSTEM";case "MAINSTEM"->"OUTLET";case "OUTLET"->"INFLOW";case "INFLOW"->"OUTFLOW";default->"SOURCE";};
        int hy=y;wbButton(g,x,hy,190,"Hydrology → "+nextRole,"Cycle river/path hydrology role metadata",locked?DIM:CY,()->{if(locked){say("Unlock the Plan object first");return;}planning("hydrology_meta",vid,"HYDRO_ROLE",nextRole);});
        var downstream=otherHydrologyPartner(vid,false);final String downId=downstream==null?"":downstream.id();wbButton(g,x+198,hy,190,"Set downstream","Link hydrology downstream metadata to another selected-compatible Plan path",locked||downstream==null?DIM:CY,()->{if(locked||downId.isBlank()){say("Another Plan path is required");return;}planning("hydrology_meta",vid,"HYDRO_DOWNSTREAM",downId);});
        var catchment=otherHydrologyPartner(vid,true);final String catchId=catchment==null?"":catchment.id();wbButton(g,x+396,hy,190,"Set catchment","Link this Plan object to an existing CATCHMENT object",locked||catchment==null?DIM:CY,()->{if(locked||catchId.isBlank()){say("Create/select a CATCHMENT Plan object first");return;}planning("hydrology_meta",vid,"HYDRO_CATCHMENT",catchId);});
        String active=OceanCanvasZoneClientCache.activeScenario();wbButton(g,x+594,hy,190,active.isBlank()?"Clear scenario":"Use active scenario","Assign this Plan object to the active design scenario, or clear it when none is active",locked?DIM:CY,()->{if(locked){say("Unlock the Plan object first");return;}planning("scenario",vid,active,"");});
        y=hy+38;
        if(!v.scenarioId().isBlank()){wbButton(g,x,y,190,"Clear object scenario","Return this Plan object to the default design scenario",locked?DIM:GOLD,()->{if(locked){say("Unlock the Plan object first");return;}planning("scenario",vid,"","");});y+=38;}
        return y;
    }

    private static boolean isClosedPlanningType(String type){return Set.of("CONTINENT","LAKE","CATCHMENT","BIOME_AREA","FOREST","DESERT","SETTLEMENT","DISTRICT","BUILD","PORT","HARBOR","REGION","FREEFORM_AREA","TERRAIN_ZONE","PLATEAU","BASIN").contains(type==null?"":type.toUpperCase(Locale.ROOT));}
    private OceanCanvasZoneClientCache.PlanningVector openPartner(String id){for(var q:OceanCanvasZoneClientCache.planningVectors())if(!q.id().equals(id)&&!q.locked()&&!isClosedPlanningType(q.type())&&planningVertexList(q.points()).size()>=4)return q;return null;}
    private OceanCanvasZoneClientCache.PlanningVector otherHydrologyPartner(String id,boolean catchment){for(var q:OceanCanvasZoneClientCache.planningVectors())if(!q.id().equals(id)&&(!catchment||"CATCHMENT".equals(q.type())))return q;return null;}
    private static String guideValueClient(String guide,String prefix){if(guide!=null)for(String t:guide.split(";"))if(t.startsWith(prefix))return t.substring(prefix.length());return "";}
    private static String csvToTabs(String raw){if(raw==null)return "";return String.join("\t",java.util.Arrays.stream(raw.split(",",-1)).map(String::trim).toList());}

    private String[] alternateWorkbenchTarget(String type,String id){
        if(!selectedPlanObject.isBlank()&&!("PLAN".equals(type)&&selectedPlanObject.equals(id)))return new String[]{"PLAN",selectedPlanObject};
        var p=project();if(p!=null&&!("PROJECT".equals(type)&&p.id().equals(id)))return new String[]{"PROJECT",p.id()};
        var z=region();if(z!=null&&!("REGION".equals(type)&&z.name().equalsIgnoreCase(id)))return new String[]{"REGION",z.name()};
        if(!selectedTerrainAsset.isBlank()&&!("TERRAIN_ASSET".equals(type)&&selectedTerrainAsset.equals(id)))return new String[]{"TERRAIN_ASSET",selectedTerrainAsset};
        if(!"WORLD".equals(type))return new String[]{"WORLD","world"};
        for(var v:OceanCanvasZoneClientCache.planningVectors())return new String[]{"PLAN",v.id()};
        for(var q:OceanCanvasZoneClientCache.workspaceProjects())return new String[]{"PROJECT",q.id()};
        return null;
    }

    private String packNewPlanCandidate(OceanCanvasZoneClientCache.PlanningVector plan,String observationId,int x,int z){
        String geometry=plan.points()==null?"":plan.points();String fp=OceanCanvasZoneClientCache.geometryFingerprint(geometry);
        return b64(plan.id())+"\t"+b64(observationId)+"\tREVIEW_AREA\tDRAFT\t"+x+"\t0\t"+z+"\tPLAN\t"+b64(plan.id())+"\t"+b64(geometry)+"\t"+fp+"\t"+b64("Review selected Plan geometry")+"\t"+b64("Drafted from current canonical Plan")+"\t"+b64("")+"\t";
    }
    private String packCandidate(OceanCanvasZoneClientCache.CandidateEdit c,String status){
        return b64(c.targetId())+"\t"+b64(c.observationId())+"\t"+c.editType()+"\t"+status+"\t"+c.x()+"\t"+c.y()+"\t"+c.z()+"\t"+c.proposedTargetType()+"\t"+b64(c.proposedTargetId())+"\t"+b64(c.candidateGeometry())+"\t"+c.baseGeometryFingerprint()+"\t"+b64(c.instruction())+"\t"+b64(c.rationale())+"\t"+b64(c.comparisonNote())+"\t";
    }

    private void openWorkbench(String initial){
        String target=initial==null?"RECOVERY":initial.toUpperCase(Locale.ROOT);
        // Beginner mode never removes capabilities from the underlying system. It keeps the
        // recovery path available, but asks the user to deliberately reveal denser stewardship
        // surfaces before opening them. Search still lists those surfaces with this same reason.
        if(OceanCanvasP3UXState.complexity()==OceanCanvasP3UXState.Complexity.BEGINNER && !"RECOVERY".equals(target)){
            overlay="p3ux"; say("Switch to Advanced or Developer view to open "+title(target)+" workbench"); return;
        }
        workbenchTab=target;overlay="workbench";listPage=0;workbenchScroll=0;cancelEdit();
    }
    private static final int WB_W=930,WB_H=650;
    private int wbX(){return (DESIGN_W-WB_W)/2;}
    private int wbY(){return (DESIGN_H-WB_H)/2;}
    private int wbButton(GuiGraphicsExtractor g,int x,int y,int w,String label,String tip,int accent,Runnable action){
        fill(g,x,y,w,27,0xFF090C0F);border(g,x,y,w,27,accent);center(g,trim(label,w-12),x,y+10,w,accent);hit(x,y,w,27,tip,action);return y+33;
    }
    private int wbLine(GuiGraphicsExtractor g,int x,int y,String label,String value,int valueColor){
        text(g,trim(label,315),x,y,DIM);right(g,trim(value,555),wbX()+WB_W-26,y,valueColor);hline(g,x,y+17,wbX()+WB_W-26-x);return y+24;
    }
    private int wbSection(GuiGraphicsExtractor g,int x,int y,String label){text(g,label,x,y,CY);hline(g,x+font.width(label)+12,y+4,Math.max(10,wbX()+WB_W-26-(x+font.width(label)+12)));return y+23;}
    private String[] workbenchTarget(){
        if("PLANS".equals(tab)&&!selectedPlanObject.isBlank())return new String[]{"PLAN",selectedPlanObject};
        if("PROJECTS".equals(tab)){var p=project();if(p!=null)return new String[]{"PROJECT",p.id()};}
        if("REGIONS".equals(tab)){var z=region();if(z!=null)return new String[]{"REGION",z.name()};}
        if(!selectedPlanObject.isBlank())return new String[]{"PLAN",selectedPlanObject};
        var p=project();if(p!=null)return new String[]{"PROJECT",p.id()};
        var z=region();if(z!=null)return new String[]{"REGION",z.name()};
        if(!selectedTerrainAsset.isBlank())return new String[]{"TERRAIN_ASSET",selectedTerrainAsset};
        return new String[]{"WORLD","world"};
    }
    private static String b64(String raw){String v=raw==null?"":raw;return Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    /** Native desktop bridge for the approved Import Reference workflow. Runs off the render thread. */
    private void openReferenceFilePicker(){
        final var mc=this.minecraft;if(mc==null)return;
        Thread pickerThread=new Thread(()->{
            try{
                javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
                chooser.setDialogTitle("Import Ocean Canvas reference image");
                chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
                chooser.setAcceptAllFileFilterUsed(true);
                chooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Reference images (PNG, JPG, JPEG)","png","jpg","jpeg"));
                if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION){java.nio.file.Path picked=chooser.getSelectedFile().toPath();mc.execute(()->importReferencePath(picked));}
            }catch(Throwable ex){mc.execute(()->OceanCanvasZoneClientCache.pushLocalFeedback("Could not open the file browser: "+ex.getMessage(),true));}
        },"OceanCanvas-reference-picker");
        // v253.72.7: this convenience bridge must never keep the JVM alive after
        // Minecraft/Modrinth has otherwise completed shutdown.
        pickerThread.setDaemon(true);
        pickerThread.start();
    }
    private void importReferencePath(java.nio.file.Path path){
        try{
            var asset=net.oceancanvas.mod.planning.OceanCanvasReferenceAssetStore.importImage(path);
            double aspect=asset.height()<=0?1.0:asset.width()/(double)asset.height();
            double visibleWidth=Math.max(256.0,Math.min(20000.0,DESIGN_W*mapBlocksPerPixel*0.72)),visibleHeight=visibleWidth/aspect;
            int minX=(int)Math.round(mapViewX-visibleWidth/2),maxX=(int)Math.round(mapViewX+visibleWidth/2),minZ=(int)Math.round(mapViewZ-visibleHeight/2),maxZ=(int)Math.round(mapViewZ+visibleHeight/2);
            String name=path.getFileName().toString(),meta=name+"\t"+minX+"\t"+minZ+"\t"+maxX+"\t"+maxZ;
            planning("create_reference","",asset.assetId(),meta);
            layerOn.put("refimg",true);tab="PLANS";panelOpen=true;collapsed=false;disclosure="plan.references";
            OceanCanvasZoneClientCache.pushLocalFeedback("Imported and tiled reference: "+name+" ("+asset.levels()+" LOD levels)",false);
        }catch(Exception ex){OceanCanvasZoneClientCache.pushLocalFeedback("Reference import failed: "+ex.getMessage(),true);}
    }

    private void viewBlueprintInWorld(boolean trace){
        net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.setProjectionMode(trace
                ?net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.ProjectionMode.SURFACE
                :net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.ProjectionMode.SEA_LEVEL);
        net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.setDepthMode(net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.DepthMode.HYBRID);
        net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.setEnabled(true);
        if(minecraft!=null)minecraft.gui.setScreen(null);
    }
    private void drawWorkbench(GuiGraphicsExtractor g){
        int x=wbX(),y=wbY();
        fill(g,x,y,WB_W,WB_H,0xFA020406);border(g,x,y,WB_W,WB_H,CY);blocker(x,y,WB_W,WB_H);
        text(g,"OCEAN CANVAS WORKBENCH",x+18,y+17,CY);
        text(g,"Recovery, authored-world, planning-library and review capabilities",x+18,y+34,DIM);
        icon(g,"close",x+WB_W-31,y+13,DIM);hit(x+WB_W-38,y+6,34,34,"Close workbench",()->overlay=null);
        String[] tabs={"RECOVERY","LIBRARY","P4","P5","P6","P7","P8","P9","LAB","FOREVER","KNOWLEDGE","PROJECT","TASKS","GEOMETRY"};int tx=x+18,ty=y+55;
        for(String t:tabs){
            int tw=font.width(t)+26;boolean on=t.equals(workbenchTab);
            fill(g,tx,ty,tw,27,on?0x171FC4EF:0xFF080A0C);border(g,tx,ty,tw,27,on?CY:BORDER);center(g,t,tx,ty+10,tw,on?CY:TEXT);
            final String target=t;hit(tx,ty,tw,27,"Open "+title(t)+" tools",()->{workbenchTab=target;listPage=0;workbenchScroll=0;cancelEdit();});tx+=tw+7;
        }
        hline(g,x+18,y+91,WB_W-36);
        int clipTop=y+98,clipBottom=y+WB_H-18;
        float sc=uiScale();
        g.enableScissor((int)Math.floor(uiOffsetX()+(x+8)*sc),(int)Math.floor(uiOffsetY()+clipTop*sc),
                (int)Math.ceil(uiOffsetX()+(x+WB_W-8)*sc),(int)Math.ceil(uiOffsetY()+clipBottom*sc));
        workbenchClipActive=true;workbenchClipTop=clipTop;workbenchClipBottom=clipBottom;
        int bodyY=y+108-workbenchScroll;
        switch(workbenchTab){
            case "LIBRARY" -> drawWorkbenchLibrary(g,x+22,bodyY);
            case "P4" -> drawWorkbenchP4(g,x+22,bodyY);
            case "P5" -> drawWorkbenchP5(g,x+22,bodyY);
            case "P6","P7","P8","P9" -> drawWorkbenchProgram(g,x+22,bodyY,workbenchTab);
            case "LAB" -> drawWorkbenchProgram(g,x+22,bodyY,"P10");
            case "FOREVER" -> drawWorkbenchForever(g,x+22,bodyY);
            case "KNOWLEDGE" -> drawWorkbenchKnowledge(g,x+22,bodyY);
            case "PROJECT" -> drawWorkbenchProject(g,x+22,bodyY);
            case "TASKS" -> drawWorkbenchTasks(g,x+22,bodyY);
            case "GEOMETRY" -> drawWorkbenchGeometry(g,x+22,bodyY);
            default -> drawWorkbenchRecovery(g,x+22,bodyY);
        }
        workbenchClipActive=false;g.disableScissor();
        if(workbenchScroll>0)text(g,"↑ scroll for earlier tools",x+WB_W-198,y+99,DIM);
        text(g,"Mouse wheel scrolls this workbench",x+18,y+WB_H-14,DIM);
    }
    private void drawWorkbenchRecovery(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"RECOVERY & MACHINE PROFILE");
        var compat=OceanCanvasZoneClientCache.recoveryCompatibility();
        y=wbLine(g,x,y,"Compatibility",compat.readOnly()?"READ ONLY":"Writable",compat.readOnly()?GOLD:GREEN);
        if(!compat.reason().isBlank())y=wbLine(g,x,y,"Reason",compat.reason(),compat.readOnly()?GOLD:DIM);
        String profile=OceanCanvasZoneClientCache.pregenProfile();
        y=wbLine(g,x,y,"Pregen profile",profile,CY);
        var bench=OceanCanvasZoneClientCache.pregenBenchmark();
        if(bench==null)y=wbLine(g,x,y,"Machine calibration","No benchmark captured yet",DIM);
        else{
            y=wbLine(g,x,y,"Calibration",String.format(Locale.US,"%.1f chunks/s · %.1f ms/tick · outstanding %d",bench.chunksPerSecond(),bench.tickMs(),bench.preferredOutstanding()),bench.usable()?GREEN:GOLD);
            y=wbLine(g,x,y,"Evidence",bench.successfulRuns()+" runs · "+bench.samples()+" samples · "+(bench.usable()?"current":"historical/stale"),bench.usable()?GREEN:GOLD);
        }
        var layoutReport=OceanCanvasUILayoutSelfTest.run();
        y=wbLine(g,x,y,"UI layout self-test",layoutReport.summary(),layoutReport.pass()?GREEN:RED);
        y+=4;y=wbSection(g,x,y,"RELIABILITY HARNESS");
        String[] faultProfiles={"BASELINE","SLOW_LOAD","DELAYED_FUTURES","TICKET_LOSS","SAVE_QUIT","CANCELLATION","MEMORY_PRESSURE","STALLED_NEIGHBORS"};
        harnessFaultProfileIndex=Math.floorMod(harnessFaultProfileIndex,faultProfiles.length);String faultProfile=faultProfiles[harnessFaultProfileIndex];
        y=wbLine(g,x,y,"Isolated fault profile",title(faultProfile),GOLD);
        wbButton(g,x,y,180,"Previous Profile","Choose the previous isolated synthetic fault profile",CY,()->harnessFaultProfileIndex=Math.floorMod(harnessFaultProfileIndex-1,faultProfiles.length));
        wbButton(g,x+188,y,180,"Next Profile","Choose the next isolated synthetic fault profile",CY,()->harnessFaultProfileIndex=(harnessFaultProfileIndex+1)%faultProfiles.length);
        wbButton(g,x+376,y,210,"Run Isolated Profile","Run this synthetic profile without touching the world/controller",GOLD,()->workspace("harness_fault","",faultProfiles[harnessFaultProfileIndex],""));
        y+=38;
        wbButton(g,x,y,190,"Boundary Fuzz 5,000","Run deterministic polygon/chunk boundary fuzzing",CY,()->workspace("harness_boundary","","",""));
        wbButton(g,x+198,y,190,"Save/Quit Matrix","Run deterministic interruption/recovery rehearsal",CY,()->workspace("harness_torture","","",""));
        wbButton(g,x+396,y,190,"Crash Matrix","Run crash-consistency commit-point rehearsal",CY,()->workspace("harness_crash","","",""));
        wbButton(g,x+594,y,210,"Compatibility Fingerprint","Record/compare dependency and schema fingerprint",CY,()->workspace("harness_compatibility","","",""));
        y+=42;
        y=wbSection(g,x,y,"UPGRADE COMPATIBILITY MATRIX");
        var matrix=OceanCanvasZoneClientCache.compatibilityMatrix();long unverified=matrix.stream().filter(v->"UNVERIFIED".equals(v.status())).count();long failed=matrix.stream().filter(v->"FAILED".equals(v.status())).count();
        y=wbLine(g,x,y,"Current environment",failed>0?failed+" failed":unverified+" unverified",failed>0?RED:unverified>0?GOLD:GREEN);
        wbButton(g,x,y,250,"Record Offline Matrix","Run deterministic version-keyed checks; runtime-only rows stay unverified",CY,()->workspace("harness_matrix","","",""));y+=38;
        for(var row:matrix){int c=switch(row.status()){case "VALIDATED","OBSERVED"->GREEN;case "SIMULATED"->CY;case "FAILED"->RED;default->GOLD;};y=wbLine(g,x+12,y,row.capability(),title(row.status()),c);}
        y+=4;
        final String next=switch(profile){case "QUIET"->"BALANCED";case "BALANCED"->"OVERNIGHT";case "OVERNIGHT"->"CUSTOM";default->"QUIET";};
        y=wbButton(g,x,y,260,"Cycle profile → "+next,"Change the adaptive Pregen operating profile",CY,()->{workspace("pregen_profile","",next,"");say("Pregen profile → "+next);});
        y+=4;y=wbSection(g,x,y,"REGION STEWARDSHIP");
        String current=OceanCanvasZoneClientCache.currentProject();
        y=wbLine(g,x,y,"Project-data anchor",current.isBlank()?"None":current,current.isBlank()?DIM:TEXT);
        y=wbLine(g,x,y,"Stewardship region records",OceanCanvasZoneClientCache.projectRegions().size()+" regions",OceanCanvasZoneClientCache.projectRegions().isEmpty()?DIM:TEXT);
        var z=region();
        if(z!=null){var meta=OceanCanvasZoneClientCache.projectRegion(z.name());y=wbLine(g,x,y,"Selected region metadata",meta==null?"No stewardship metadata":title(meta.stage())+(meta.templateId().isBlank()?"":" · "+meta.templateId()),meta==null?DIM:CY);}
        else y=wbLine(g,x,y,"Selected region","None",DIM);
        var templates=OceanCanvasZoneClientCache.projectTemplates();
        y=wbLine(g,x,y,"Region templates",templates.size()+" available",templates.isEmpty()?DIM:TEXT);
        for(int i=0;i<Math.min(3,templates.size());i++){var t=templates.get(i);y=wbLine(g,x+12,y,"• "+t.displayName(),t.id(),DIM);}
        y+=4;y=wbSection(g,x,y,"RECOVERY EVIDENCE");
        var hist=OceanCanvasZoneClientCache.recoveryHistory();var snaps=OceanCanvasZoneClientCache.recoverySnapshots();var diff=OceanCanvasZoneClientCache.recoveryLatestDiff();
        y=wbLine(g,x,y,"Recovery history",hist.size()+" entries",hist.isEmpty()?DIM:TEXT);
        var scoped=scopedRecoveryHistory();
        y=wbLine(g,x,y,"Spatial operation evidence",scoped.size()+" scoped entries",scoped.isEmpty()?DIM:GOLD);
        if(!scoped.isEmpty()){
            operationGhostIndex=Math.floorMod(operationGhostIndex,scoped.size());var gh=scoped.get(operationGhostIndex);
            y=wbLine(g,x+12,y,"Ghost "+(operationGhostIndex+1)+" / "+scoped.size(),title(gh.kind())+" · "+title(gh.phase())+(gh.scopeId().isBlank()?"":" · "+gh.scopeId()),operationGhostEnabled?GOLD:DIM);
            final int ghostCount=scoped.size();
            wbButton(g,x+12,y,190,operationGhostEnabled?"Hide Operation Ghost":"Show Operation Ghost","Overlay the persisted historical operation scope on the map",GOLD,()->operationGhostEnabled=!operationGhostEnabled);
            wbButton(g,x+210,y,120,"Previous","Show the previous scoped operation",CY,()->{operationGhostIndex=Math.floorMod(operationGhostIndex-1,ghostCount);operationGhostEnabled=true;});
            wbButton(g,x+338,y,120,"Next","Show the next scoped operation",CY,()->{operationGhostIndex=(operationGhostIndex+1)%ghostCount;operationGhostEnabled=true;});
            y+=38;
        }
        var archaeology=archaeologyTimeline();
        y+=4;y=wbSection(g,x,y,"CHANGE ARCHAEOLOGY TIMELINE");
        y=wbLine(g,x,y,"Durable events",archaeology.size()+" operation/world events",archaeology.isEmpty()?DIM:TEXT);
        if(!archaeology.isEmpty()){
            archaeologyIndex=Math.floorMod(archaeologyIndex,archaeology.size());var ae=archaeology.get(archaeologyIndex);final int archaeologyCount=archaeology.size();final var selectedAe=ae;
            y=wbLine(g,x+12,y,(archaeologyIndex+1)+" / "+archaeology.size()+" · "+java.time.Instant.ofEpochMilli(ae.at()).toString(),trim(ae.title()+" · "+ae.actor(),390),GOLD);
            y=wbLine(g,x+12,y,"Evidence",trim(ae.detail(),430),DIM);
            wbButton(g,x+12,y,120,"Previous","Earlier/newer history navigation",CY,()->archaeologyIndex=Math.floorMod(archaeologyIndex-1,archaeologyCount));
            wbButton(g,x+140,y,120,"Next","Earlier/newer history navigation",CY,()->archaeologyIndex=(archaeologyIndex+1)%archaeologyCount);
            wbButton(g,x+268,y,190,"Focus / Show Scope","Focus persisted region/operation scope without inventing coordinates",GOLD,()->focusArchaeologyEvent(selectedAe));y+=38;
        }
        for(int i=Math.max(0,hist.size()-2);i<hist.size();i++){var h=hist.get(i);y=wbLine(g,x+12,y,"• "+title(h.kind())+" / "+title(h.phase()),trim(h.detail(),350),DIM);}
        y=wbLine(g,x,y,"Recovery snapshots",snaps.size()+" snapshots",snaps.isEmpty()?DIM:TEXT);
        for(int i=Math.max(0,snaps.size()-2);i<snaps.size();i++){var v=snaps.get(i);y=wbLine(g,x+12,y,"• "+v.label(),v.regions()+" regions · "+v.canvasStates()+" states · "+v.physicalSeals()+" seals",DIM);}
        y=wbLine(g,x,y,"Latest recovery diff",diff.isEmpty()?"No pending diff":diff.size()+" change records",diff.isEmpty()?GREEN:GOLD);
        y+=4; drawUndoCentre(g,x,y);
        y+=96; y=drawWorkbenchRecoveryCompletion(g,x,y); y+=8; y=drawWorkbenchP1W3(g,x,y); y+=8; drawWorkbenchP1W4(g,x,y);
    }
    private void drawWorkbenchP4(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"P4 · CREATIVE PLANNING CORE");
        y=wbLine(g,x,y,"Persisted P4 artifacts",String.valueOf(OceanCanvasZoneClientCache.p4Artifacts().size()),CY);
        y=wbLine(g,x,y,"Live advisory findings",String.valueOf(OceanCanvasZoneClientCache.p4Findings().size()),CY);
        var selected=planObject();String target=selected==null?"No Plan object selected":selected.name()+" · "+title(selected.type());
        y=wbLine(g,x,y,"Canonical geometry target",target,selected==null?GOLD:TEXT);
        int draftY=y; y=wbLine(g,x,y,"Draft note / intent",p4DraftText.isBlank()?"Click to edit":trim(p4DraftText,420),p4DraftText.isBlank()?DIM:TEXT);hit(x+12,draftY-3,WB_W-70,22,"Edit reusable P4 planning note",()->beginEdit("p4.draft",p4DraftText));if("p4.draft".equals(editKey))drawEditBox(g,x+430,draftY-5,430,27);
        int intentY=y;y=wbLine(g,x,y,"Terrain intent",p4TerrainIntent,CY);hit(x+12,intentY-3,WB_W-70,22,"Edit the semantic terrain intent painted onto selected geometry",()->beginEdit("p4.intent",p4TerrainIntent));if("p4.intent".equals(editKey))drawEditBox(g,x+430,intentY-5,430,27);
        int motifY=y;y=wbLine(g,x,y,"Motif name",p4MotifName,CY);hit(x+12,motifY-3,WB_W-70,22,"Edit the reusable motif name",()->beginEdit("p4.motif",p4MotifName));if("p4.motif".equals(editKey))drawEditBox(g,x+430,motifY-5,430,27);

        y+=6;y=wbSection(g,x,y,"AUTHORING · METADATA-FIRST, NO BLOCK PAINTING");int r=y;
        wbButton(g,x,r,205,"Negative Space","Persist selected shape as first-class strait/channel/void planning geometry",0xFFB45CFF,()->{if(selected==null){say("Select an area first");return;}p4Put("NEGATIVE_SPACE","Negative Space",selected.id(),selected.points(),0,0,p4DraftText);});
        wbButton(g,x+214,r,205,"Design Rationale","Attach rationale to the selected Plan object or map centre",CY,()->p4Put("DESIGN_RATIONALE","Design Rationale",p4SelectedTarget(),p4SelectedPoints(false),0,0,p4DraftText));
        wbButton(g,x+428,r,205,"Terrain Story Beat","Place a named terrain/story beat at the map centre",0xFFE77DAF,()->p4Put("TERRAIN_STORY_BEAT","Story Beat",p4SelectedTarget(),p4SelectedPoints(false),0,0,p4DraftText));
        wbButton(g,x+642,r,205,"Scale Stamp","Persist a map-scale reference stamp at the current centre",0xFF8FD8F0,()->p4Put("SCALE_STAMP","Scale "+String.format(Locale.US,"%.1f",mapBlocksPerPixel)+" b/px","world",p4SelectedPoints(false),mapBlocksPerPixel,0,p4DraftText));
        r+=35;
        wbButton(g,x,r,205,"Terrain Intent Brush","Paint semantic intent onto selected Plan geometry",0xFFF2B84B,()->{if(selected==null){say("Select a Plan shape/path first");return;}p4Put("TERRAIN_INTENT","Intent · "+p4TerrainIntent,selected.id(),selected.points(),0,0,p4TerrainIntent+(p4DraftText.isBlank()?"":" · "+p4DraftText));});
        wbButton(g,x+214,r,205,"Biome Transition","Compose / paint a biome transition along selected geometry",0xFF55D6A8,()->{if(selected==null){say("Select transition geometry first");return;}p4Put("BIOME_TRANSITION","Biome Transition",selected.id(),selected.points(),Math.max(16,selected.widthBlocks()),0,p4DraftText);});
        wbButton(g,x+428,r,205,"Ecology Corridor","Persist selected path/area as an ecological corridor",0xFF67C587,()->{if(selected==null){say("Select corridor geometry first");return;}p4Put("ECOLOGICAL_CORRIDOR","Ecological Corridor",selected.id(),selected.points(),Math.max(32,selected.widthBlocks()),0,p4DraftText);});
        wbButton(g,x+642,r,205,"Motif Library +","Save selected geometry as a reusable advisory motif",CY,()->{if(selected==null){say("Select motif geometry first");return;}p4Put("MOTIF",p4MotifName,selected.id(),selected.points(),0,0,p4DraftText);});
        r+=35;
        wbButton(g,x,r,205,"Sightline","Create landmark sightline from selected path endpoints",0xFFF4E18A,()->{if(selected==null){say("Select a path with endpoints first");return;}p4Put("LANDMARK_SIGHTLINE","Landmark Sightline",selected.id(),selected.points(),0,0,p4DraftText);});
        wbButton(g,x+214,r,205,"View Corridor","Create an authored view corridor from selected geometry",0xFFF4E18A,()->{if(selected==null){say("Select corridor geometry first");return;}p4Put("VIEW_CORRIDOR","View Corridor",selected.id(),selected.points(),0,0,p4DraftText);});
        wbButton(g,x+428,r,205,"Playable Budget","Persist a playable-space budget checkpoint; live analysis uses all areas + negative space",CY,()->p4Put("PLAYABLE_SPACE_BUDGET","Playable Space Budget","world",p4SelectedPoints(false),0,0,p4DraftText));
        wbButton(g,x+642,r,205,"Organic Subdivide","Create editable child cells from the selected area using canonical Plan geometry",CY,()->{if(selected==null){say("Select an area first");return;}planning("p4_organic_subdivide",selected.id(),"","");});
        r+=35;
        wbButton(g,x,r,205,"Reference Board +","Pin selected reference image into the P4 reference board",0xFFC86EE8,()->{if(selectedReference.isBlank()){say("Select a reference image first");return;}p4Put("REFERENCE_BOARD","Reference Board Item",selectedReference,p4SelectedPoints(false),0,0,p4DraftText);});
        wbButton(g,x+214,r,205,"Align Ref → Centre","Use current map centre as a world alignment landmark for selected reference",0xFFC86EE8,()->{if(selectedReference.isBlank()){say("Select and unlock a reference image first");return;}planning("p4_reference_anchor",selectedReference,String.valueOf((int)Math.round(mapViewX)),String.valueOf((int)Math.round(mapViewZ)));});
        wbButton(g,x+428,r,205,"Fork Alternative","Duplicate selected Plan object into a new design scenario",0xFFE76DFF,()->{if(selected==null){say("Select a Plan object first");return;}planning("p4_scenario_fork",selected.id(),"Alternative "+(OceanCanvasZoneClientCache.scenarios().size()+1),"");});
        wbButton(g,x+642,r,205,p4Alternatives?"Alternatives: ON":"Alternatives: OFF","Overlay A/B/C scenario geometry differences on the map",p4Alternatives?CY:DIM,()->p4Alternatives=!p4Alternatives);
        y=r+44;

        y=wbSection(g,x,y,"SCALE PREVIEW LADDER");int sy=y;
        wbButton(g,x,sy,205,"LOCAL · 1 b/px","Preview fine local scale",CY,()->mapBlocksPerPixel=1.0);
        wbButton(g,x+214,sy,205,"DISTRICT · 4 b/px","Preview district scale",CY,()->mapBlocksPerPixel=4.0);
        wbButton(g,x+428,sy,205,"REGION · 16 b/px","Preview region scale",CY,()->mapBlocksPerPixel=16.0);
        wbButton(g,x+642,sy,205,"WORLD · 64 b/px","Preview world scale",CY,()->mapBlocksPerPixel=64.0);
        y=sy+44;

        y=wbSection(g,x,y,"LIVE ANALYSIS / HEATMAP OVERLAYS");
        String[][] tools={{"COASTLINE_CONSTRAINT","Constraint Coastlines"},{"SYMMETRY_REPETITION","Symmetry / Repetition"},{"WATERSHED_CONFLICT","Watershed Conflicts"},{"HYDROLOGY_GRAPH","Hydrology Graph"},{"SETTLEMENT_SUITABILITY","Settlement Heatmap"},{"HARBOR_QUALITY","Harbor Quality"},{"DESIGN_TENSION","Design Tension"},{"WALKABILITY","Walkability Heatmap"},{"JOURNEY_RHYTHM","Journey Rhythm"},{"LANDMARK_SIGHTLINE","Landmark Sightlines"},{"VIEW_CORRIDOR","View Corridors"},{"ALL","All P4 Findings"}};
        int col=0;for(String[] t:tools){int bx=x+(col%4)*214,by=y+(col/4)*35;String key=t[0],label=t[1];wbButton(g,bx,by,205,label,"Toggle "+label+" advisory overlay",key.equals(p4Overlay)?BRIGHT:CY,()->p4Overlay=key.equals(p4Overlay)?"NONE":key);col++;}y+=((tools.length+3)/4)*35+8;
        y=wbLine(g,x,y,"Current overlay",p4Overlay,CY);

        y+=5;y=wbSection(g,x,y,"SERVER FINDINGS");var findings=OceanCanvasZoneClientCache.p4Findings();int shown=0;for(var f:findings){if(shown>=18)break;if(!"NONE".equals(p4Overlay)&&!"ALL".equals(p4Overlay)&&!f.feature().equals(p4Overlay))continue;int yy=y;String sev=f.severity()+"/100";y=wbLine(g,x+12,y,title(f.feature())+" · "+trim(f.message(),420),sev,f.severity()>=70?RED:f.severity()>=35?GOLD:GREEN);final int fx=f.x(),fz=f.z();hit(x+12,yy-3,WB_W-85,22,"Centre map on this P4 finding",()->{mapViewX=fx;mapViewZ=fz;coordX=fx;coordZ=fz;say(f.message());});shown++;}
        if(shown==0)y=wbLine(g,x+12,y,"No findings for this overlay","Clean / not applicable",GREEN);

        y+=5;y=wbSection(g,x,y,"PERSISTED P4 ARTIFACTS");var arts=OceanCanvasZoneClientCache.p4Artifacts();int start=Math.max(0,arts.size()-12);for(int i=start;i<arts.size();i++){var a=arts.get(i);int yy=y;y=wbLine(g,x+12,y,title(a.kind())+" · "+trim(a.name(),360),a.targetId().isBlank()?"WORLD":a.targetId(),TEXT);final String aid=a.id();hit(x+12,yy-3,WB_W-155,22,"Select this P4 artifact",()->say(a.name()+": "+a.text()));wbButton(g,x+WB_W-145,yy-4,120,"Delete","Delete this planning annotation only; canonical Plan geometry is untouched",RED,()->{if(confirm("p4_delete:"+aid,"Click Delete again to confirm"))planning("p4_delete",aid,"","");});}
    }

    private void drawWorkbenchP5(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"P5 · TERRAIN PIPELINE & ROUND TRIP");
        var assets=OceanCanvasZoneClientCache.terrainAssets();if(selectedTerrainAsset.isBlank()&&!assets.isEmpty())selectedTerrainAsset=assets.get(0).id();
        var asset=assets.stream().filter(a->a.id().equals(selectedTerrainAsset)).findFirst().orElse(null);
        y=wbLine(g,x,y,"Coordinate contract","v1 · X east, +Z south · inclusive bounds · explicit sea/floor · SHA-256 sidecars",CY);
        y=wbLine(g,x,y,"Pipeline registry",OceanCanvasZoneClientCache.p5Transforms().size()+" transforms · "+OceanCanvasZoneClientCache.p5Recipes().size()+" recipes · "+OceanCanvasZoneClientCache.p5Placements().size()+" placements",TEXT);
        y=wbLine(g,x,y,"Review state",OceanCanvasZoneClientCache.p5Quarantine().size()+" quarantine · "+OceanCanvasZoneClientCache.p5Findings().size()+" findings · "+OceanCanvasZoneClientCache.p5MarkerSchemas().size()+" marker schemas",TEXT);
        y=wbLine(g,x,y,"Terrain target",asset==null?"Select a Terrain Asset in LIBRARY":asset.name()+" · "+asset.revisionCount()+" revisions",asset==null?GOLD:TEXT);

        y+=5;y=wbSection(g,x,y,"HEIGHTMAP CONTRACT / FINGERPRINT / DRIFT");int r=y;final String aid=asset==null?"":asset.id();
        wbButton(g,x,r,205,"Contract Check","Validate resolution, world bounds, scale, sea level, orientation, bit depth and fingerprint metadata",asset==null?DIM:CY,()->{if(aid.isBlank()){say("Select a Terrain Asset first");return;}planning("p5_contract_check",aid,"","");});
        wbButton(g,x+214,r,205,"Fingerprint","Compute deterministic round-trip fingerprint for the latest revision",asset==null?DIM:CY,()->{if(aid.isBlank())return;planning("p5_fingerprint",aid,"","");});
        wbButton(g,x+428,r,205,"Round-trip Check","Compare oldest and latest revision for origin/scale/sea/orientation drift and format losses",asset==null?DIM:CY,()->{if(aid.isBlank())return;planning("p5_roundtrip_check",aid,"WORLDPAINTER","");});
        wbButton(g,x+642,r,205,"Tile Seam Check","Inspect adjacent Terrain Asset metadata and raster edges when files exist in the import inbox",CY,()->planning("p5_seams","","",""));r+=35;
        wbButton(g,x,r,205,"Sea-level Proposal","Propose a non-destructive Y normalization from asset sea level across standard Minecraft height range",asset==null?DIM:GOLD,()->{if(asset==null)return;planning("p5_sea_normalize","",asset.seaLevel()+","+asset.seaLevel()+",-64,320,-64,320","");});
        y=r+44;

        y=wbSection(g,x,y,"IMPORT QUARANTINE · EXTERNAL FILES NEVER AUTO-APPLY");
        int fy=y;y=wbLine(g,x,y,"Inbox filename",p5ImportFile,CY);hit(x+12,fy-3,WB_W-70,22,"Edit filename expected under <world>/oceancanvas/imports",()->beginEdit("p5.import_file",p5ImportFile));if("p5.import_file".equals(editKey))drawEditBox(g,x+430,fy-5,430,27);
        int my=y;y=wbLine(g,x,y,"Metadata","minX|minZ|maxX|maxZ|width|height|bpp|sea|orientation · "+trim(p5ImportMeta,260),DIM);hit(x+12,my-3,WB_W-70,22,"Edit import contract metadata",()->beginEdit("p5.import_meta",p5ImportMeta));if("p5.import_meta".equals(editKey))drawEditBox(g,x+430,my-5,430,27);
        int qy=y;wbButton(g,x,qy,260,"Quarantine + Validate","Hash and inspect the external file, then create a review record without touching terrain",asset==null?DIM:CY,()->{if(asset==null){say("Select a Terrain Asset first");return;}String[] f=p5ImportMeta.split("\\|",-1);if(f.length!=9){say("Import metadata needs 9 pipe-separated fields");return;}planning("p5_quarantine",asset.id(),p5ImportFile,String.join("\t",f));});y=qy+38;
        var qs=OceanCanvasZoneClientCache.p5Quarantine();for(int i=Math.max(0,qs.size()-5);i<qs.size();i++){var q=qs.get(i);int yy=y;y=wbLine(g,x+12,y,title(q.status())+" · "+q.fileName(),trim(q.issues().isBlank()?q.targetId():q.issues(),330),"BLOCKED".equals(q.status())?RED:"RECONCILED".equals(q.status())?GREEN:GOLD);final String qid=q.id();wbButton(g,x+WB_W-300,yy-4,132,"Register Return","Register validated return revision; no blocks are changed","VALIDATED".equals(q.status())?CY:DIM,()->{if(!"VALIDATED".equals(q.status())){say("Only validated quarantine items can be reconciled");return;}planning("p5_reconcile",qid,"REGISTER_RETURN","");});wbButton(g,x+WB_W-160,yy-4,132,"Accept Metadata","Reconcile returned bounds + sea metadata explicitly; no terrain mutation","VALIDATED".equals(q.status())?GOLD:DIM,()->{if(!"VALIDATED".equals(q.status()))return;planning("p5_reconcile",qid,"ALL_METADATA","");});}

        y+=5;y=wbSection(g,x,y,"COORDINATE TRANSFORM WIZARD");
        int tn=y;y=wbLine(g,x,y,"Profile name",p5TransformName,CY);hit(x+12,tn-3,WB_W-70,22,"Edit transform profile name",()->beginEdit("p5.transform_name",p5TransformName));if("p5.transform_name".equals(editKey))drawEditBox(g,x+430,tn-5,430,27);
        int ts=y;y=wbLine(g,x,y,"Transform spec",trim(p5TransformSpec,410),DIM);hit(x+12,ts-3,WB_W-70,22,"source|target|originX|originZ|scale|rotation|flipX|flipZ|sea|floor|origin convention",()->beginEdit("p5.transform_spec",p5TransformSpec));if("p5.transform_spec".equals(editKey))drawEditBox(g,x+430,ts-5,430,27);
        int ty=y;wbButton(g,x,ty,260,"Save Transform Profile","Persist the explicit coordinate transform used for repeatable external handoffs",CY,()->{String[] f=p5TransformSpec.split("\\|",-1);if(f.length!=11){say("Transform needs 11 pipe-separated fields");return;}planning("p5_transform_put","",p5TransformName,String.join("\t",f));});y=ty+38;
        for(var t:OceanCanvasZoneClientCache.p5Transforms())y=wbLine(g,x+12,y,"• "+t.name(),t.sourceTool()+" → "+t.targetTool()+" · origin "+t.originX()+","+t.originZ()+" · scale "+String.format(Locale.US,"%.4f",t.scale())+" · rot "+String.format(Locale.US,"%.1f",t.rotation()),DIM);

        y+=5;y=wbSection(g,x,y,"REPRODUCIBLE EXPORT RECIPES + CAPABILITY MATRIX");
        int rn=y;y=wbLine(g,x,y,"Recipe name",p5RecipeName,CY);hit(x+12,rn-3,WB_W-70,22,"Edit export recipe name",()->beginEdit("p5.recipe_name",p5RecipeName));if("p5.recipe_name".equals(editKey))drawEditBox(g,x+430,rn-5,430,27);
        int rs=y;y=wbLine(g,x,y,"Recipe spec",trim(p5RecipeSpec,410),DIM);hit(x+12,rs-3,WB_W-70,22,"target|transform|bounds|width|height|layers|naming|validation",()->beginEdit("p5.recipe_spec",p5RecipeSpec));if("p5.recipe_spec".equals(editKey))drawEditBox(g,x+430,rs-5,430,27);
        int ry=y;wbButton(g,x,ry,260,"Save Export Recipe","Persist target, transform, bounds, resolution, layers, naming and validation steps",CY,()->{String[] f=p5RecipeSpec.split("\\|",-1);if(f.length!=8){say("Recipe needs 8 pipe-separated fields");return;}planning("p5_recipe_put","",p5RecipeName,String.join("\t",f));});wbButton(g,x+268,ry,260,"Export Plan + P5 Sidecars","Write canonical Plan interchange with coordinate contract, transforms, recipes and compatibility report",CY,()->planning("export_plan","","p5-plan",""));y=ry+38;
        for(var c:OceanCanvasZoneClientCache.p5FormatCapabilities())y=wbLine(g,x+12,y,"• "+c.target(),"elev "+c.elevation()+" · vectors "+c.vectors()+" · coord "+c.coordinates()+" · fingerprint "+c.fingerprint(),DIM);
        for(var c:OceanCanvasZoneClientCache.p5PluginCapabilities())y=wbLine(g,x+12,y,"Plugin · "+c.modId(),(c.present()?"present "+c.version():"optional / absent")+" · "+c.capabilities(),c.present()?GREEN:DIM);

        y+=5;y=wbSection(g,x,y,"LITEMATICA PLACEMENT REGISTRY");
        int pn=y;y=wbLine(g,x,y,"Placement name",p5PlacementName,CY);hit(x+12,pn-3,WB_W-70,22,"Edit placement name",()->beginEdit("p5.placement_name",p5PlacementName));if("p5.placement_name".equals(editKey))drawEditBox(g,x+430,pn-5,430,27);
        int ps=y;y=wbLine(g,x,y,"Placement spec",trim(p5PlacementSpec,410),DIM);hit(x+12,ps-3,WB_W-70,22,"project|asset|file|sha256|x|y|z|rotation|mirror|status|version|dependencies|notes",()->beginEdit("p5.placement_spec",p5PlacementSpec));if("p5.placement_spec".equals(editKey))drawEditBox(g,x+430,ps-5,430,27);
        int py=y;wbButton(g,x,py,260,"Register Placement","Persist file/hash/origin/rotation/mirror/version/dependency placement metadata",CY,()->{String spec=p5PlacementSpec;if(spec.startsWith("|"))spec=selectedProject+spec;if(spec.split("\\|",-1).length==13&&spec.split("\\|",-1)[1].isBlank()&&!selectedTerrainAsset.isBlank()){String[] f=spec.split("\\|",-1);f[1]=selectedTerrainAsset;spec=String.join("|",f);}String[] f=spec.split("\\|",-1);if(f.length!=13){say("Placement needs 13 pipe-separated fields");return;}planning("p5_litematica_put","",p5PlacementName,String.join("\t",f));});y=py+38;
        for(var p:OceanCanvasZoneClientCache.p5Placements())y=wbLine(g,x+12,y,"• "+p.name(),p.fileName()+" · "+p.originX()+","+p.originY()+","+p.originZ()+" · "+p.rotation()+"° · "+p.status(),DIM);

        y+=5;y=wbSection(g,x,y,"CUSTOM MARKER SCHEMA");
        int mn=y;y=wbLine(g,x,y,"Schema name",p5MarkerName,CY);hit(x+12,mn-3,WB_W-70,22,"Edit custom marker schema name",()->beginEdit("p5.marker_name",p5MarkerName));if("p5.marker_name".equals(editKey))drawEditBox(g,x+430,mn-5,430,27);
        int ms=y;y=wbLine(g,x,y,"Schema spec",trim(p5MarkerSpec,410),DIM);hit(x+12,ms-3,WB_W-70,22,"scope|field1,field2|icon|style|notes",()->beginEdit("p5.marker_spec",p5MarkerSpec));if("p5.marker_spec".equals(editKey))drawEditBox(g,x+430,ms-5,430,27);
        int mby=y;wbButton(g,x,mby,260,"Save Marker Schema","Persist a data-only marker schema; fields are metadata, never executable code",CY,()->{String[] f=p5MarkerSpec.split("\\|",-1);if(f.length!=5){say("Marker schema needs 5 pipe-separated fields");return;}planning("p5_marker_schema_put","",p5MarkerName,String.join("\t",f));});y=mby+38;
        for(var m:OceanCanvasZoneClientCache.p5MarkerSchemas())y=wbLine(g,x+12,y,"• "+m.name(),m.scope()+" · "+m.fields()+" · "+m.icon()+" / "+m.style(),DIM);

        y+=5;y=wbSection(g,x,y,"P5 REGISTRY CLEANUP");
        var deletableTransforms=OceanCanvasZoneClientCache.p5Transforms().stream().filter(v->!v.id().equals("oc_worldpainter")&&!v.id().equals("oc_gaea")).toList();var dt=deletableTransforms.isEmpty()?null:deletableTransforms.get(deletableTransforms.size()-1);final String dtid=dt==null?"":dt.id();
        var deletableRecipes=OceanCanvasZoneClientCache.p5Recipes().stream().filter(v->!v.id().equals("worldpainter_roundtrip")).toList();var dr=deletableRecipes.isEmpty()?null:deletableRecipes.get(deletableRecipes.size()-1);final String drid=dr==null?"":dr.id();
        var dp=OceanCanvasZoneClientCache.p5Placements().isEmpty()?null:OceanCanvasZoneClientCache.p5Placements().get(OceanCanvasZoneClientCache.p5Placements().size()-1);final String dpid=dp==null?"":dp.id();
        var dq=OceanCanvasZoneClientCache.p5Quarantine().isEmpty()?null:OceanCanvasZoneClientCache.p5Quarantine().get(OceanCanvasZoneClientCache.p5Quarantine().size()-1);final String dqid=dq==null?"":dq.id();
        var dm=OceanCanvasZoneClientCache.p5MarkerSchemas().isEmpty()?null:OceanCanvasZoneClientCache.p5MarkerSchemas().get(OceanCanvasZoneClientCache.p5MarkerSchemas().size()-1);final String dmid=dm==null?"":dm.id();
        int dy=y;wbButton(g,x,dy,160,"Delete Transform","Delete latest user-created transform profile; built-in profiles are protected",dt==null?DIM:RED,()->{if(dtid.isBlank()){say("No user-created transform to delete");return;}if(confirm("p5_transform_delete:"+dtid,"Click Delete Transform again to confirm"))planning("p5_transform_delete",dtid,"","");});
        wbButton(g,x+168,dy,160,"Delete Recipe","Delete latest user-created export recipe; built-in recipe is protected",dr==null?DIM:RED,()->{if(drid.isBlank()){say("No user-created recipe to delete");return;}if(confirm("p5_recipe_delete:"+drid,"Click Delete Recipe again to confirm"))planning("p5_recipe_delete",drid,"","");});
        wbButton(g,x+336,dy,160,"Delete Placement","Delete latest Litematica placement registry entry",dp==null?DIM:RED,()->{if(dpid.isBlank()){say("No placement to delete");return;}if(confirm("p5_litematica_delete:"+dpid,"Click Delete Placement again to confirm"))planning("p5_litematica_delete",dpid,"","");});
        wbButton(g,x+504,dy,160,"Delete Quarantine","Delete latest quarantine review record only",dq==null?DIM:RED,()->{if(dqid.isBlank()){say("No quarantine record to delete");return;}if(confirm("p5_quarantine_delete:"+dqid,"Click Delete Quarantine again to confirm"))planning("p5_quarantine_delete",dqid,"","");});
        wbButton(g,x+672,dy,160,"Delete Marker","Delete latest custom marker schema",dm==null?DIM:RED,()->{if(dmid.isBlank()){say("No marker schema to delete");return;}if(confirm("p5_marker_schema_delete:"+dmid,"Click Delete Marker again to confirm"))planning("p5_marker_schema_delete",dmid,"","");});y=dy+38;

        y+=5;y=wbSection(g,x,y,"LIVE P5 FINDINGS");int shown=0;for(var f:OceanCanvasZoneClientCache.p5Findings()){if(shown++>=16)break;y=wbLine(g,x+12,y,title(f.feature())+" · "+trim(f.message(),420),f.severity()+"/100",f.severity()>=70?RED:f.severity()>=35?GOLD:GREEN);}if(shown==0)y=wbLine(g,x+12,y,"No P5 contract/drift/seam findings","Clean / not applicable",GREEN);
    }

    private String programSubject(){
        if(!selectedProject.isBlank())return selectedProject;
        if(!selectedPlanObject.isBlank())return selectedPlanObject;
        if(!selectedRegion.isBlank())return selectedRegion;
        return "world";
    }
    private void drawWorkbenchProgram(GuiGraphicsExtractor g,int x,int y,String phase){
        String heading=switch(phase){case "P6"->"PROJECTS · CONSTRUCTION · INFRASTRUCTURE";case "P7"->"FOREVER-WORLD ATLAS · WORLDBUILDING";case "P8"->"COLLABORATION · REVIEW · PRESENCE";case "P10"->"WORLD DESIGN LAB · CONSTRAINTS · EXPERIENCE · GOVERNANCE";default->"ADVANCED ANALYTICS · POLISH · CERTIFICATION";};
        y=wbSection(g,x,y,phase+" · "+heading);
        var features=OceanCanvasZoneClientCache.programFeatures().stream().filter(v->phase.equals(v.phase())).toList();
        long runs=OceanCanvasZoneClientCache.programEntries().stream().filter(v->phase.equals(v.phase())).count();
        long attention=OceanCanvasZoneClientCache.programEvidence().stream().filter(v->phase.equals(v.phase())&&v.severity()>=35).count();
        y=wbLine(g,x,y,"Roadmap capabilities",features.size()+" · "+runs+" persisted run(s) · "+attention+" attention finding(s)",attention>0?GOLD:GREEN);
        y=wbLine(g,x,y,"Context subject",programSubject(),CY);
        int inputY=y;y=wbLine(g,x,y,"Optional input / note",programInput.isBlank()?"Blank = safe defaults":trim(programInput,420),programInput.isBlank()?DIM:TEXT);
        hit(x+12,inputY-3,WB_W-75,22,"Edit the optional parameter used by the selected P6-P10 capability. Blank input always uses safe defaults.",()->beginEdit("program.input",programInput));
        if("program.input".equals(editKey))drawEditBox(g,x+430,inputY-5,430,27);
        int cy=y;wbButton(g,x,cy,180,"Clear Input","Clear reusable feature input",DIM,()->{programInput="";cancelEdit();say("Program input cleared");});
        wbButton(g,x+188,cy,230,"Centre as Context","Use current project/plan/region plus current player/map context",CY,()->say("Context → "+programSubject()));
        if("P8".equals(phase)){
            wbButton(g,x+426,cy,210,"Presence Refresh","Run presence capability and publish current multiplayer positions",CY,()->planning("program_run","OC-F070",programSubject(),programInput));
            wbButton(g,x+644,cy,205,programCursorShare?"Cursor Sharing ON":"Share Map Cursor","Opt in/out of transient collaborator map cursor sharing; cursors expire automatically and are never persisted",programCursorShare?GREEN:CY,()->{programCursorShare=!programCursorShare;if(programCursorShare){planning("program_run","OC-F252",programSubject(),"Opted in to live planning cursor presence");planning("program_cursor","",coordX+","+coordZ,programSubject());}else planning("program_cursor","","OFF","");say(programCursorShare?"Planning cursor sharing enabled":"Planning cursor sharing disabled");});
        }
        if("P9".equals(phase)){
            var ghost=OceanCanvasZoneClientCache.latestProgramEntry("OC-F009");
            wbButton(g,x+644,cy,205,"Accept Ghost","Explicitly accept the latest procedural suggestion ghost as a normal editable Plan path",ghost==null||ghost.payload().isBlank()?DIM:0xFFE76DFF,()->{
                var latest=OceanCanvasZoneClientCache.latestProgramEntry("OC-F009");
                if(latest==null||latest.payload().isBlank()){say("Run Procedural suggestion ghost first");return;}
                planning("create","","FREEFORM_LINE",latest.payload());say("Accepted suggestion ghost into an editable Plan path");
            });
        }
        if("P10".equals(phase)){
            String planId=selectedPlanObject;boolean hasPlan=planId!=null&&!planId.isBlank();
            wbButton(g,x+426,cy,132,"Freeze Plan","Freeze selected Plan geometry/content; visibility remains a view concern",hasPlan?GOLD:DIM,()->{if(selectedPlanObject.isBlank()){say("Select a Plan object first");return;}planning("program_run","OC-F287",selectedPlanObject,"FREEZE");});
            wbButton(g,x+566,cy,132,"Release Plan","Release selected Plan from the latest design freeze",hasPlan?GREEN:DIM,()->{if(selectedPlanObject.isBlank()){say("Select a Plan object first");return;}planning("program_run","OC-F287",selectedPlanObject,"RELEASE");});
            wbButton(g,x+706,cy,143,"Experience","Generate pacing checkpoints for the selected route/shape",hasPlan?CY:DIM,()->{if(selectedPlanObject.isBlank()){say("Select a Plan object first");return;}planning("program_run","OC-F292",selectedPlanObject,programInput);});
        }
        y=cy+42;
        y=wbSection(g,x,y,"CAPABILITIES · SERVER-AUTHORITATIVE, ADVISORY UNLESS EXISTING SAFE ACTIONS ARE EXPLICITLY USED");
        for(var f:features){
            var ev=OceanCanvasZoneClientCache.latestProgramEvidence(f.id());var en=OceanCanvasZoneClientCache.latestProgramEntry(f.id());
            int row=y;String state=ev==null?"Not run":title(ev.state())+(ev.severity()>0?" · "+ev.severity()+"/100":"");int color=ev==null?DIM:ev.severity()>=70?RED:ev.severity()>=35?GOLD:GREEN;
            y=wbLine(g,x+12,y,f.id()+" · "+f.name(),state,color);
            final String fid=f.id();wbButton(g,x+WB_W-154,row-4,130,"Run","Evaluate/persist this capability using the selected context and optional input",CY,()->planning("program_run",fid,programRunSubject(phase,fid),programInput));
            if(en!=null){final String eid=en.id();hit(x+12,row-3,WB_W-180,22,"Latest: "+(ev==null?en.state():ev.summary())+" · Shift is not required; Run creates a new auditable result.",()->say(ev==null?en.label():ev.summary()));}
        }
        y+=7;y=wbSection(g,x,y,"RECENT EVIDENCE");int shown=0;
        for(var ev:OceanCanvasZoneClientCache.programEvidence()){if(!phase.equals(ev.phase())||shown++>=12)continue;int row=y;y=wbLine(g,x+12,y,ev.featureId()+" · "+trim(ev.summary(),390),title(ev.state()),ev.severity()>=70?RED:ev.severity()>=35?GOLD:GREEN);final int fx=ev.x(),fz=ev.z();hit(x+12,row-3,WB_W-85,22,"Centre map on this evidence location",()->{mapViewX=fx;mapViewZ=fz;coordX=fx;coordZ=fz;say(ev.summary());});}
        if(shown==0)y=wbLine(g,x+12,y,"No "+phase+" evidence yet","Run a capability above",DIM);
        y+=7;y=wbSection(g,x,y,"RECENT PROGRAM ARTIFACTS");shown=0;
        var entries=OceanCanvasZoneClientCache.programEntries();for(int i=entries.size()-1;i>=0&&shown<10;i--){var e=entries.get(i);if(!phase.equals(e.phase()))continue;shown++;int row=y;y=wbLine(g,x+12,y,e.featureId()+" · "+trim(e.label(),340),title(e.state())+" · "+e.author(),TEXT);final String eid=e.id();wbButton(g,x+WB_W-154,row-4,130,"Delete","Delete this program artifact only; evidence and world terrain are preserved",RED,()->{if(confirm("program_delete:"+eid,"Click Delete again to confirm"))planning("program_delete",eid,"","");});}
    }

    private String programRunSubject(String phase,String featureId){
        if("P10".equals(phase)&&java.util.Set.of("OC-F283","OC-F284","OC-F285","OC-F287","OC-F292","OC-F296","OC-F304","OC-F308","OC-F309").contains(featureId)&&!selectedPlanObject.isBlank())return selectedPlanObject;
        return programSubject();
    }

    private boolean programPlanLabelVisible(OceanCanvasZoneClientCache.PlanningVector v){
        if(!OceanCanvasZoneClientCache.programFeatureActive("OC-F266"))return false;
        if(v.id().equals(selectedPlanObject)||focusMode&&focusIncludesPlan(v.id()))return true;
        double threshold=switch(v.type()){
            case "CONTINENT"->200D;
            case "REGION","CITY"->80D;
            case "SETTLEMENT","LANDMARK"->24D;
            case "ROAD","PORT","HARBOR","TRANSPORT_ROUTE"->8D;
            default->2D;
        };
        return mapBlocksPerPixel<=threshold;
    }
    private double programMetric(String payload,String key,double fallback){
        if(payload==null)return fallback;
        for(String raw:payload.split(";")){String[] f=raw.split("=",2);if(f.length==2&&f[0].trim().equalsIgnoreCase(key))try{return Double.parseDouble(f[1].trim());}catch(NumberFormatException ignored){}}
        return fallback;
    }
    private void drawProgramPointList(GuiGraphicsExtractor g,String packed,int color,boolean connect){
        if(packed==null||packed.isBlank())return;Integer px=null,py=null;
        for(String raw:packed.split(";")){String[] f=raw.trim().split(",",-1);if(f.length<2)continue;try{int sx=mapScreenX(Double.parseDouble(f[0])),sy=mapScreenY(Double.parseDouble(f[1]));if(connect&&px!=null)line(g,px,py,sx,sy,color);fill(g,sx-3,sy-3,6,6,color);px=sx;py=sy;}catch(NumberFormatException ignored){}}
    }
    /** P6-P10 map presentation for persisted advisory results. These overlays never mutate terrain. */
    private void drawProgramAnalyticOverlays(GuiGraphicsExtractor g){
        var ghost=OceanCanvasZoneClientCache.latestProgramEntry("OC-F009");
        if(ghost!=null&&!ghost.payload().isBlank())drawProgramPointList(g,ghost.payload(),0xCCE76DFF,true);

        var bridges=OceanCanvasZoneClientCache.latestProgramEntry("OC-F158");
        if(bridges!=null&&!bridges.payload().isBlank())drawProgramPointList(g,bridges.payload(),0xDDF2B84B,false);

        var iso=OceanCanvasZoneClientCache.latestProgramEntry("OC-F201");
        if(iso!=null){
            int[] radii={(int)Math.round(programMetric(iso.payload(),"walk",0)),(int)Math.round(programMetric(iso.payload(),"boat",0)),(int)Math.round(programMetric(iso.payload(),"rail",0)),(int)Math.round(programMetric(iso.payload(),"nether",0))};
            int[] cols={0x6655C97A,0x6655BDEB,0x66F2B84B,0x66E76DFF};
            for(int i=0;i<radii.length;i++){int r=radii[i];if(r<=0)continue;int l=mapScreenX(iso.x()-r),rr=mapScreenX(iso.x()+r),t=mapScreenY(iso.z()-r),b=mapScreenY(iso.z()+r);int x=Math.min(l,rr),y=Math.min(t,b),w=Math.max(1,Math.abs(rr-l)),h=Math.max(1,Math.abs(b-t));border(g,x,y,w,h,cols[i]);}
        }

        var staging=OceanCanvasZoneClientCache.latestProgramEntry("OC-F026");
        if(staging!=null){int r=(int)Math.round(programMetric(staging.payload(),"radius",0));if(r>0){int l=mapScreenX(staging.x()-r),rr=mapScreenX(staging.x()+r),t=mapScreenY(staging.z()-r),b=mapScreenY(staging.z()+r);border(g,Math.min(l,rr),Math.min(t,b),Math.max(1,Math.abs(rr-l)),Math.max(1,Math.abs(b-t)),0xAA8FD8F0);}}

        var preserve=OceanCanvasZoneClientCache.latestProgramEntry("OC-F282");
        if(preserve!=null){int r=(int)Math.round(programMetric(preserve.payload(),"radius",0));if(r>0){int l=mapScreenX(preserve.x()-r),rr=mapScreenX(preserve.x()+r),t=mapScreenY(preserve.z()-r),b=mapScreenY(preserve.z()+r);int bx=Math.min(l,rr),by=Math.min(t,b),bw=Math.max(1,Math.abs(rr-l)),bh=Math.max(1,Math.abs(b-t));border(g,bx,by,bw,bh,0xDDE0B25A);line(g,bx,by,bx+bw,by+bh,0x88E0B25A);line(g,bx+bw,by,bx,by+bh,0x88E0B25A);}}
        var corridor=OceanCanvasZoneClientCache.latestProgramEntry("OC-F283");if(corridor!=null)drawProgramPointList(g,corridor.payload(),0xDDF0F0F0,true);
        var sight=OceanCanvasZoneClientCache.latestProgramEntry("OC-F289");if(sight!=null)drawProgramPointList(g,sight.payload(),0xCC9FE870,true);
        var experience=OceanCanvasZoneClientCache.latestProgramEntry("OC-F292");if(experience!=null)drawProgramPointList(g,experience.payload(),0xDDE76DFF,true);
        var viewpoint=OceanCanvasZoneClientCache.latestProgramEntry("OC-F293");if(viewpoint!=null){int r=(int)Math.round(programMetric(viewpoint.payload(),"radius",0));if(r>0){int l=mapScreenX(viewpoint.x()-r),rr=mapScreenX(viewpoint.x()+r),t=mapScreenY(viewpoint.z()-r),b=mapScreenY(viewpoint.z()+r);border(g,Math.min(l,rr),Math.min(t,b),Math.max(1,Math.abs(rr-l)),Math.max(1,Math.abs(b-t)),0xAA55BDEB);}}
        var coverage=OceanCanvasZoneClientCache.latestProgramEntry("OC-F295");if(coverage!=null)drawProgramPointList(g,coverage.payload(),0xCC55C97A,false);

        if(OceanCanvasZoneClientCache.programFeatureActive("OC-F023")){
            var p=project();if(p!=null){var phases=OceanCanvasZoneClientCache.workspacePhases(p);String active=phases.stream().filter(v->!v.complete()).findFirst().map(OceanCanvasZoneClientCache.WorkspacePhase::id).orElse("");
                for(var t:OceanCanvasZoneClientCache.workspaceTasks()){if(!t.projectId().equals(p.id())||(!active.isBlank()&&!t.phaseId().isBlank()&&!t.phaseId().equals(active)))continue;int sx=mapScreenX(t.x()),sy=mapScreenY(t.z());if(sx<-10||sy<-10||sx>DESIGN_W+10||sy>DESIGN_H+10)continue;fill(g,sx-4,sy-4,8,8,0xCCF2B84B);if(mapSelectionActive())hit(sx-6,sy-6,12,12,"Active construction phase · "+t.title(),()->say(t.title()));}
            }
        }
    }

    private void drawProgramAnnotations(GuiGraphicsExtractor g){
        for(var e:OceanCanvasZoneClientCache.programEntries()){
            if(!("OC-F068".equals(e.featureId())||"OC-F251".equals(e.featureId())||"OC-F278".equals(e.featureId())||"OC-F012".equals(e.featureId())||"OC-F188".equals(e.featureId())))continue;
            int sx=mapScreenX(e.x()),sy=mapScreenY(e.z());if(sx<-20||sy<-20||sx>DESIGN_W+20||sy>DESIGN_H+20)continue;
            fill(g,sx-3,sy-3,7,7,0xDD1FC4EF);border(g,sx-5,sy-5,11,11,CY);text(g,trim(e.label(),110),sx+9,sy-4,TEXT);
        }
    }
    private void drawProgramPresence(GuiGraphicsExtractor g){
        if(OceanCanvasZoneClientCache.programFeatureActive("OC-F252"))for(var c:OceanCanvasZoneClientCache.programCursors()){
            int sx=mapScreenX(c.x()),sy=mapScreenY(c.z());if(sx<-30||sy<-30||sx>DESIGN_W+30||sy>DESIGN_H+30)continue;int col=0xFFE76DFF;
            line(g,sx-9,sy,sx+9,sy,col);line(g,sx,sy-9,sx,sy+9,col);border(g,sx-4,sy-4,9,9,col);text(g,c.name()+" · cursor",sx+12,sy-5,col);
        }
        if(!OceanCanvasZoneClientCache.programFeatureActive("OC-F070"))return;
        for(var p:OceanCanvasZoneClientCache.programPresence()){
            int sx=mapScreenX(p.x()),sy=mapScreenY(p.z());if(sx<-30||sy<-30||sx>DESIGN_W+30||sy>DESIGN_H+30)continue;
            int c=CY;fill(g,sx-4,sy-4,9,9,0xDD0A0F12);border(g,sx-6,sy-6,13,13,c);line(g,sx-8,sy,sx+8,sy,c);line(g,sx,sy-8,sx,sy+8,c);text(g,p.name()+" · Y"+p.y(),sx+11,sy-5,c);
        }
    }

    private void drawWorkbenchLibrary(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"IN-WORLD BLUEPRINT / TRACE");
        boolean on=net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.enabled();
        y=wbLine(g,x,y,"Overlay renderer",on?"Enabled":"Disabled",on?GREEN:DIM);
        wbButton(g,x,y,190,"View Preview in World","Project visible references at sea level",CY,()->viewBlueprintInWorld(false));
        wbButton(g,x+198,y,190,"View Trace in World","Project visible references over terrain",CY,()->viewBlueprintInWorld(true));
        wbButton(g,x+396,y,150,"Hide Overlay","Disable the in-world schematic overlay",DIM,()->{net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.setEnabled(false);say("In-world overlay hidden");});
        wbButton(g,x+554,y,250,"Save Current Viewpoint","Store the current player/view projection state",CY,()->{planning("viewpoint_add","","Viewpoint",net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.captureViewpointPayload());say("Viewpoint saved");});
        y+=38;y=wbSection(g,x,y,"VIEW LIBRARY");
        wbButton(g,x,y,175,"Import Reference","Choose a PNG/JPG reference and tile it into the planning library",CY,this::openReferenceFilePicker);
        wbButton(g,x+183,y,175,"Save View Preset","Capture current visibility as a preset",CY,()->planning("preset_add","","Preset "+(OceanCanvasZoneClientCache.viewPresets().size()+1),""));
        wbButton(g,x+366,y,175,"New Reference Set","Create a visible reference set",CY,()->planning("refset_add","","Reference Set "+(OceanCanvasZoneClientCache.referenceSets().size()+1),""));
        wbButton(g,x+549,y,175,"Save Map Bookmark","Bookmark this map centre, zoom and selected Plan object",CY,()->planning("bookmark_add",selectedPlanObject,"Bookmark "+(OceanCanvasZoneClientCache.planBookmarks().size()+1),(int)Math.round(mapViewX)+","+(int)Math.round(mapViewZ)+","+mapBlocksPerPixel));
        y+=38;
        var presets=OceanCanvasZoneClientCache.viewPresets();var sets=OceanCanvasZoneClientCache.referenceSets();var marks=OceanCanvasZoneClientCache.planBookmarks();var views=OceanCanvasZoneClientCache.blueprintViewpoints();
        y=wbLine(g,x,y,"Planning generation",String.valueOf(OceanCanvasZoneClientCache.planningGeneration()),DIM);
        y=wbLine(g,x,y,"Presets / Reference sets / Bookmarks / Viewpoints",presets.size()+" / "+sets.size()+" / "+marks.size()+" / "+views.size(),TEXT);
        for(int i=0;i<Math.min(2,presets.size());i++){var p=presets.get(i);final String id=p.id();int yy=y;y=wbLine(g,x+12,y,"Preset · "+p.name(),"Apply",CY);hit(x+12,yy,WB_W-76,20,"Apply saved visibility preset",()->planning("preset_apply",id,"",""));}
        for(int i=0;i<Math.min(2,sets.size());i++){var q=sets.get(i);final String id=q.id();final boolean visible=q.visible();int yy=y;boolean sel=id.equals(selectedReferenceSet);y=wbLine(g,x+12,y,(sel?"▶ Set · ":"Set · ")+q.name(),visible?"Visible":"Hidden",sel?CY:(visible?GREEN:DIM));hit(x+12,yy,WB_W-76,20,"Select this reference set",()->selectedReferenceSet=id);}
        if(selectedReferenceSet.isBlank()&&!sets.isEmpty())selectedReferenceSet=sets.get(0).id();
        var selectedSet=sets.stream().filter(q->q.id().equals(selectedReferenceSet)).findFirst().orElse(null);
        if(selectedSet!=null){
            final String sid=selectedSet.id();final boolean vis=selectedSet.visible(),locked=selectedSet.locked();final double op=selectedSet.opacity();
            wbButton(g,x+12,y,145,vis?"Hide Set":"Show Set","Toggle all references in this set",CY,()->planning("refset_visible",sid,String.valueOf(!vis),""));
            wbButton(g,x+165,y,145,locked?"Unlock Set":"Lock Set","Protect or unlock this reference set",CY,()->planning("refset_lock",sid,String.valueOf(!locked),""));
            wbButton(g,x+318,y,145,"Opacity "+(int)Math.round(op*100)+"%","Cycle reference-set opacity",CY,()->planning("refset_opacity",sid,String.valueOf(op>=0.95?0.35:Math.min(1.0,op+0.20)),""));
            wbButton(g,x+471,y,145,"Solo Set","Show only this reference set",CY,()->planning("refset_solo",sid,"",""));
            wbButton(g,x+624,y,145,"Delete Set","Delete this reference set metadata",RED,()->{if(confirm("refset_delete:"+sid,"Click Delete Set again to confirm"))planning("refset_delete",sid,"","");});
            y+=38;
        }
        for(int i=0;i<Math.min(2,marks.size());i++){
            var m=marks.get(i);int yy=y;final int cx=m.centerX(),cz=m.centerZ();final double zoom=m.zoom();final String obj=m.selectedObjectId();final String name=m.name();
            y=wbLine(g,x+12,y,"Bookmark · "+name,cx+", "+cz,CY);hit(x+12,yy,WB_W-76,20,"Jump the map to this saved bookmark",()->{mapViewX=cx;mapViewZ=cz;if(zoom>0)mapBlocksPerPixel=zoom;if(obj!=null&&!obj.isBlank())selectedPlanObject=obj;overlay=null;say("Opened bookmark "+name);});
        }
        for(int i=0;i<Math.min(2,views.size());i++){var v=views.get(i);int yy=y;final var vv=v;y=wbLine(g,x+12,y,"Viewpoint · "+v.name(),v.projection()+" / "+v.depth(),CY);hit(x+12,yy,WB_W-76,20,"Apply this stored in-world viewpoint",()->{net.oceancanvas.mod.planning.OceanCanvasBlueprintOverlay.applyViewpointState(vv);if(minecraft!=null)minecraft.gui.setScreen(null);});}
        if(!marks.isEmpty()){var m=marks.get(marks.size()-1);final String mid=m.id();wbButton(g,x+12,y,180,"Delete Latest Bookmark","Remove the newest saved map bookmark",RED,()->{if(confirm("bookmark_delete:"+mid,"Click Delete Bookmark again to confirm"))planning("bookmark_delete",mid,"","");});}
        if(!views.isEmpty()){var v=views.get(views.size()-1);final String vid=v.id();wbButton(g,x+200,y,180,"Delete Latest Viewpoint","Remove the newest stored viewpoint",RED,()->{if(confirm("viewpoint_delete:"+vid,"Click Delete Viewpoint again to confirm"))planning("viewpoint_delete",vid,"","");});}
        if(!marks.isEmpty()||!views.isEmpty())y+=38;
        y+=4; y=drawReferenceLayerEditor(g,x,y);
        y+=4;y=wbSection(g,x,y,"TERRAIN REVISION LIBRARY");
        var assets=OceanCanvasZoneClientCache.terrainAssets();
        y=wbLine(g,x,y,"Terrain assets",assets.size()+" assets",assets.isEmpty()?DIM:TEXT);
        final int tcx=(int)Math.round(mapViewX),tcz=(int)Math.round(mapViewZ),half=512;
        wbButton(g,x,y,250,"Create Terrain Asset Here","Create a 1024×1024 terrain asset linked to the selected Plan object",CY,()->planning("terrain_asset_add",selectedPlanObject,"Terrain Asset "+(OceanCanvasZoneClientCache.terrainAssets().size()+1),(tcx-half)+","+(tcz-half)+","+(tcx+half)+","+(tcz+half)+",63"));
        y+=38;
        if(selectedTerrainAsset.isBlank()&&!assets.isEmpty())selectedTerrainAsset=assets.get(0).id();
        for(int i=0;i<Math.min(3,assets.size());i++){var a=assets.get(i);final String id=a.id();int yy=y;boolean sel=id.equals(selectedTerrainAsset);y=wbLine(g,x+12,y,(sel?"▶ ":"• ")+a.name(),title(a.status())+" · "+a.revisionCount()+" revisions",sel?CY:DIM);hit(x+12,yy,WB_W-76,20,"Select terrain asset",()->selectedTerrainAsset=id);}
        if(!selectedTerrainAsset.isBlank()){
            var revs=OceanCanvasZoneClientCache.terrainRevisions(selectedTerrainAsset);y=wbLine(g,x,y,"Selected asset revisions",revs.size()+" revisions",revs.isEmpty()?DIM:TEXT);
            for(int i=Math.max(0,revs.size()-2);i<revs.size();i++){var r=revs.get(i);String notes=r.notes()==null?"":r.notes();String review="";var rm=java.util.regex.Pattern.compile("REVIEW status=([A-Z]+)").matcher(notes);if(rm.find())review=rm.group(1);y=wbLine(g,x+12,y,"• "+r.fileName(),title(r.stage())+(review.isBlank()?"":" · "+review),review.equals("APPROVED")?GREEN:DIM);}
            if(!revs.isEmpty()){
                var latest=revs.get(revs.size()-1);final String aid=selectedTerrainAsset,rid=latest.id(),stage=latest.stage();int ry=y;
                wbButton(g,x,ry,170,"Approve review","Mark latest revision review state APPROVED",GREEN,()->planning("terrain_review_state",aid,rid+":APPROVED",""));
                wbButton(g,x+178,ry,170,"Request revision","Mark latest revision review state REVISE",GOLD,()->planning("terrain_review_state",aid,rid+":REVISE",""));
                wbButton(g,x+356,ry,170,"Add review note","Attach a durable review note to the latest revision",CY,()->planning("terrain_review_note",aid,rid,"Reviewed from Workbench"));
                wbButton(g,x+534,ry,170,"Approve stage","Approve latest revision as Gaea or WorldPainter according to its stage",CY,()->planning("terrain_approve",aid,(stage.toLowerCase(Locale.ROOT).contains("world")?"worldpainter":"gaea")+":"+rid,""));
                y=ry+38;
            }
        }
        y+=6; drawWorkbenchLibraryCompletion(g,x,y);
    }
    private void drawWorkbenchForever(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"FOREVER-WORLD PROJECT PIPELINE");
        var p=project();String pid=p==null?"":p.id();
        y=wbLine(g,x,y,"Active workspace project",p==null?"None":p.name(),p==null?DIM:CY);
        if(p!=null){
            var plots=OceanCanvasZoneClientCache.prototypePlots(pid);var transitions=OceanCanvasZoneClientCache.transitionZones(pid);var snaps=OceanCanvasZoneClientCache.pipelineSnapshots(pid);
            y=wbLine(g,x,y,"Prototype plots",plots.size()+" · latest "+(plots.isEmpty()?"none":plots.get(0).width()+"×"+plots.get(0).depth()),plots.isEmpty()?DIM:TEXT);
            y=wbLine(g,x,y,"Transition zones",transitions.size()+" configured",transitions.isEmpty()?DIM:TEXT);
            y=wbLine(g,x,y,"Pipeline snapshots",snaps.size()+" checkpoints",snaps.isEmpty()?DIM:TEXT);
            if(!plots.isEmpty()){var pp=plots.get(0);final String plotId=pp.id();final String nextStatus=switch(pp.status()){case "ACTIVE"->"READY";case "READY"->"PROMOTED";case "PROMOTED"->"DISCARDED";default->"ACTIVE";};wbButton(g,x,y,190,"Prototype → "+nextStatus,"Advance/recycle the newest prototype workflow state",CY,()->workspace("prototype_status","",plotId,nextStatus));y+=33;}
            if(!transitions.isEmpty()){var tz=transitions.get(0);final String tzid=tz.id();final String nextStatus=switch(tz.status()){case "PLANNED"->"REVIEW";case "REVIEW"->"APPROVED";case "APPROVED"->"ARCHIVED";default->"PLANNED";};wbButton(g,x,y,190,"Transition → "+nextStatus,"Advance/recycle the newest transition workflow state",CY,()->workspace("transition_status","",tzid,nextStatus));wbButton(g,x+198,y,190,"Toggle Rivers Layer","Toggle river blending for the newest transition",CY,()->workspace("transition_layer","",tzid,"RIVERS"));y+=33;}
            final String projectId=pid;
            wbButton(g,x,y,190,"Create 512 Prototype","Create a 512×512 prototype plot centred on the player",CY,()->{workspace("prototype_create",projectId,"Prototype","512");say("Prototype plot created");});
            wbButton(g,x+198,y,190,"Create Transition","Create a 256-block transition around the linked region",CY,()->{workspace("transition_create",projectId,"Transition","256");say("Transition zone created");});
            wbButton(g,x+396,y,190,"Pipeline Snapshot","Capture current plan/terrain/task readiness",CY,()->{workspace("project_pipeline_snapshot",projectId,"Workbench checkpoint","");say("Pipeline checkpoint saved");});
            y+=38;
        }
        y=wbSection(g,x,y,"SCENARIOS & SESSION");
        String active=OceanCanvasZoneClientCache.activeScenario();var scenarios=OceanCanvasZoneClientCache.scenarios();
        y=wbLine(g,x,y,"Active scenario",active.isBlank()?"Default":active,active.isBlank()?DIM:CY);
        wbButton(g,x,y,220,"Create Scenario","Create and activate a new design scenario",CY,()->workspace("scenario_add","","Scenario "+(scenarios.size()+1),"Created from Stewardship Workbench"));
        y+=38;
        for(int i=0;i<Math.min(3,scenarios.size());i++){var sc=scenarios.get(i);int yy=y;final String id=sc.id();y=wbLine(g,x+12,y,(id.equals(active)?"▶ ":"• ")+sc.name(),sc.visible()?"Visible":"Hidden",id.equals(active)?CY:DIM);hit(x+12,yy,WB_W-76,20,"Make this scenario active",()->workspace("scenario_active",id,"",""));}
        String note=OceanCanvasZoneClientCache.workspaceSessionNote();
        y=wbLine(g,x,y,"Session note",note.isBlank()?"None":note,note.isBlank()?DIM:TEXT);
        int ny=y;y=wbButton(g,x,y,250,"Edit Session Note","Store a durable workspace session note",CY,()->beginEdit("workbench.session_note",note));
        if("workbench.session_note".equals(editKey))drawEditBox(g,x+260,ny,WB_W-326,27);
        y+=2;y=wbSection(g,x,y,"ATLAS & TASK READINESS");
        var routes=OceanCanvasZoneClientCache.atlasRoutes();
        y=wbLine(g,x,y,"Atlas routes",routes.size()+" routes",routes.isEmpty()?DIM:TEXT);
        for(int i=0;i<Math.min(2,routes.size());i++){var r=routes.get(i);y=wbLine(g,x+12,y,"• "+r.name(),r.stops().size()+" stops",DIM);}
        if(!routes.isEmpty()){
            var r=routes.get(0);final String routeId=r.id();int ay=y;int ax=(int)Math.round(mapViewX),az=(int)Math.round(mapViewZ);
            wbButton(g,x,ay,170,"Add map stop","Add current map centre to the first Atlas route",CY,()->workspace("atlas_route_add",routeId,"NOTE",""+b64("")+"	"+b64("Map stop")+"	"+ax+"	"+az));
            wbButton(g,x+178,ay,170,"Edit intro","Set a concise Atlas route introduction",CY,()->workspace("atlas_route_description",routeId,"Authored world route",""));
            if(!r.stops().isEmpty()){var stop=r.stops().get(0);final String stopId=stop.id();wbButton(g,x+356,ay,170,"Move first stop down","Reorder the first Atlas stop",CY,()->workspace("atlas_route_move",routeId,stopId,"DOWN"));wbButton(g,x+534,ay,170,"Remove first stop","Remove the first Atlas stop",GOLD,()->workspace("atlas_route_remove",routeId,stopId,""));}
            y=ay+38;
            wbButton(g,x,y,170,"Delete Atlas route","Delete the first route after confirmation",RED,()->{if(confirm("atlas_delete:"+routeId,"Click Delete Atlas route again to confirm"))workspace("atlas_route_delete",routeId,"","");});
            y+=38;
        }
        var tasks=OceanCanvasZoneClientCache.workspaceTasks();int checklistItems=0,checklistDone=0;
        for(var task:tasks)for(var item:OceanCanvasZoneClientCache.workspaceChecklist(task)){checklistItems++;if(item.complete())checklistDone++;}
        y=wbLine(g,x,y,"Task checklists",checklistDone+" / "+checklistItems+" items complete",checklistItems==0?DIM:(checklistDone==checklistItems?GREEN:TEXT));
        if(routes.isEmpty())wbButton(g,x,y,220,"Create Atlas Route","Create an empty authored-world navigation route",CY,()->{workspace("atlas_route_create","","World Route","");say("Atlas route created");});
        y+=44; drawWorkbenchForeverCompletion(g,x,y);
    }
    /**
     * OC-F013 naming consistency engine, over object kinds a player names freely: Projects and
     * Plan layers world-wide, and Tasks within the same project. Region names are excluded - a
     * region's name is its own identity elsewhere in the synced data (e.g. every
     * {@code WorkspaceProject.regionName()} reference), so the server already prevents that
     * collision and flagging it here would be a false positive, not a real gap. Exact match is
     * case-insensitive and trims whitespace; this only flags, it never renames anything.
     */
    private List<String> duplicateNameWarnings(){
        var warnings=new ArrayList<String>();
        collectDuplicates(OceanCanvasZoneClientCache.workspaceProjects().stream().map(OceanCanvasZoneClientCache.WorkspaceProject::name).toList(),
                "Project",warnings);
        collectDuplicates(OceanCanvasZoneClientCache.planGroups().stream().map(OceanCanvasZoneClientCache.PlanGroup::name).toList(),
                "Plan layer",warnings);
        Map<String,List<String>> titlesByProject=new LinkedHashMap<>();
        for(var t:OceanCanvasZoneClientCache.workspaceTasks())
            titlesByProject.computeIfAbsent(t.projectId(),k->new ArrayList<>()).add(t.title());
        for(var e:titlesByProject.entrySet()){
            String projectName=e.getKey();
            for(var p:OceanCanvasZoneClientCache.workspaceProjects()) if(p.id().equals(e.getKey())) projectName=p.name();
            collectDuplicates(e.getValue(),"Task in "+projectName,warnings);
        }
        return warnings;
    }
    private void collectDuplicates(List<String> names,String kind,List<String> out){
        Map<String,Integer> counts=new LinkedHashMap<>(); Map<String,String> original=new LinkedHashMap<>();
        for(String n:names){
            if(n==null) continue; String trimmed=n.trim(); if(trimmed.isEmpty()) continue;
            String key=trimmed.toLowerCase(Locale.ROOT);
            counts.merge(key,1,Integer::sum); original.putIfAbsent(key,trimmed);
        }
        for(var e:counts.entrySet()) if(e.getValue()>1) out.add(kind+" \""+original.get(e.getKey())+"\" used "+e.getValue()+" times");
    }
    private void drawWorkbenchKnowledge(GuiGraphicsExtractor g,int x,int y){
        String[] target=workbenchTarget();String tt=target[0],tid=target[1];
        y=wbSection(g,x,y,"WORLD STEWARDSHIP & DESIGN REVIEW");
        y=wbLine(g,x,y,"Target",tt+(tid.isBlank()?"":" · "+tid),CY);
        var observations=OceanCanvasZoneClientCache.worldObservationsFor(tt,tid);var candidates=OceanCanvasZoneClientCache.candidateEditsFor(tt,tid);var events=OceanCanvasZoneClientCache.worldEventsFor(tt,tid);
        var revisions=OceanCanvasZoneClientCache.designRevisionsFor(tt,tid);var relations=OceanCanvasZoneClientCache.worldRelationshipsFor(tt,tid);var claims=OceanCanvasZoneClientCache.worldClaims(tt,tid);var authors=OceanCanvasZoneClientCache.authorshipProvenance(tt,tid);
        y=wbLine(g,x,y,"Observations / Candidates / Events",observations.size()+" / "+candidates.size()+" / "+events.size(),TEXT);
        y=wbLine(g,x,y,"Design revisions / Relationships",revisions.size()+" / "+relations.size(),TEXT);
        y=wbLine(g,x,y,"Claims / Authorship records",claims.size()+" / "+authors.size(),TEXT);
        var intent=OceanCanvasZoneClientCache.intentResolution(tt,tid);y=wbLine(g,x,y,"Intent resolution",intent==null?"Unknown":intent.levelName(),intent==null?DIM:CY);
        String scope=tt.equals("REGION")?"REGION":tt.equals("PROJECT")?"PROJECT":"WORLD";
        var policies=OceanCanvasZoneClientCache.worldPolicies(scope,tid);var effective=OceanCanvasZoneClientCache.effectiveWorldPolicies(tt.equals("REGION")?tid:"",tt.equals("PROJECT")?tid:"","");
        y=wbLine(g,x,y,"Scoped / effective policies",policies.size()+" / "+effective.size(),effective.isEmpty()?DIM:TEXT);
        if(!candidates.isEmpty()){var c=candidates.get(0);String base=OceanCanvasZoneClientCache.candidateBaseState(c);y=wbLine(g,x,y,"Newest candidate",title(c.status())+" · "+base,"STALE BASE".equals(base)?GOLD:TEXT);}
        if(!observations.isEmpty()){var o=observations.get(0);y=wbLine(g,x,y,"Newest observation",title(o.observationType())+" · "+trim(o.notes(),350),DIM);}
        y+=4;y=wbSection(g,x,y,"GLOBAL KNOWLEDGE INDEX");
        y=wbLine(g,x,y,"All candidates / observations",OceanCanvasZoneClientCache.candidateEdits().size()+" / "+OceanCanvasZoneClientCache.worldObservations().size(),DIM);
        y=wbLine(g,x,y,"All events / design revisions",OceanCanvasZoneClientCache.worldEvents().size()+" / "+OceanCanvasZoneClientCache.designRevisions().size(),DIM);
        y=wbLine(g,x,y,"All relationships / claims",OceanCanvasZoneClientCache.worldRelationships().size()+" / "+OceanCanvasZoneClientCache.worldClaims().size(),DIM);
        y=wbLine(g,x,y,"All authorship / intent / policies",OceanCanvasZoneClientCache.authorshipProvenance().size()+" / "+OceanCanvasZoneClientCache.intentResolutions().size()+" / "+OceanCanvasZoneClientCache.worldPolicies().size(),DIM);
        y+=4;y=wbSection(g,x,y,"NAMING CONSISTENCY");
        var dupWarnings=duplicateNameWarnings();
        if(dupWarnings.isEmpty()){
            y=wbLine(g,x,y,"Duplicate names","None found across projects, plan layers or tasks",GREEN);
        }else{
            for(int i=0;i<Math.min(6,dupWarnings.size());i++) y=wbLine(g,x,y,"Duplicate name",dupWarnings.get(i),GOLD);
            if(dupWarnings.size()>6) y=wbLine(g,x,y,"…and more",(dupWarnings.size()-6)+" additional duplicate name"+(dupWarnings.size()-6==1?"":"s"),DIM);
        }
        y+=4;y=wbSection(g,x,y,"PHYSICAL / ANALYSIS EVIDENCE");
        String analysis=OceanCanvasZoneClientCache.analysisPacked(),physical=OceanCanvasZoneClientCache.physicalHealthPacked();
        y=wbLine(g,x,y,"Analysis state",analysis.isBlank()?"No analysis snapshot":trim(analysis.replace('\n',' '),440),analysis.isBlank()?DIM:TEXT);
        y=wbLine(g,x,y,"Physical health",physical.isBlank()?"No physical scan":trim(physical.replace('\n',' '),440),physical.isBlank()?DIM:TEXT);
        String fp="";if("PLAN".equals(tt)){var v=OceanCanvasZoneClientCache.planningVectors().stream().filter(q->q.id().equalsIgnoreCase(tid)).findFirst().orElse(null);if(v!=null)fp=OceanCanvasZoneClientCache.geometryFingerprint(v.points());}
        y=wbLine(g,x,y,"Geometry fingerprint",fp.isBlank()?"N/A":fp.substring(0,Math.min(16,fp.length()))+"…",DIM);
        int ox=(int)Math.round(mapViewX),oz=(int)Math.round(mapViewZ);final String type=tt,id=tid;
        wbButton(g,x,y,245,"Add Map Observation","Record an authored observation at the current map centre",CY,()->{
            String packed=b64(id)+"\tNOTE\tOPEN\t"+ox+"\t0\t"+oz+"\t"+b64("")+"\t"+b64("Map observation")+"\t"+System.currentTimeMillis();
            workspace("world_observation_put","",type,packed);say("Observation recorded");
        });
        wbButton(g,x+253,y,245,"Inspect Map Centre","Request server-authored state for the current map centre",CY,()->{send(new OceanCanvasInspectRequestPayload(ox,oz));inspectMode=true;say("Inspection requested");});
        if(!id.isBlank()){
            int nextIntent=intent==null?1:Math.min(5,intent.level()+1);if(intent!=null&&intent.level()>=5)nextIntent=0;final int level=nextIntent;
            wbButton(g,x+506,y,150,"Intent → L"+level,"Cycle declared authoring specificity for this target",CY,()->workspace("intent_resolution_put","",type,b64(id)+"\t"+level+"\t"+b64("Set from Stewardship Workbench")));
        }
        y+=38;
        final String policyScope=tt.equals("REGION")?"REGION":tt.equals("PROJECT")?"PROJECT":"WORLD";final String policyScopeId=policyScope.equals("WORLD")?"":tid;
        wbButton(g,x,y,245,"Add Stewardship Policy","Create a non-destructive review-required policy for this scope",CY,()->workspace("world_policy_put","",policyScope+"\t"+b64(policyScopeId)+"\t"+b64("review_required"),b64("true")+"\ttrue\t"+b64("Created from Stewardship Workbench")));
        if(!policies.isEmpty()){var pol=policies.get(policies.size()-1);final String polid=pol.id();wbButton(g,x+253,y,245,"Delete Latest Policy","Remove the newest scoped policy",RED,()->{if(confirm("policy_delete:"+polid,"Click Delete Policy again to confirm"))workspace("world_policy_delete",polid,"","");});}
        // Touch the global index explicitly so review/stewardship records remain discoverable even with no target selected.
        OceanCanvasZoneClientCache.worldPolicies();OceanCanvasZoneClientCache.intentResolutions();OceanCanvasZoneClientCache.authorshipProvenance();
        OceanCanvasZoneClientCache.worldClaims();OceanCanvasZoneClientCache.worldRelationships();OceanCanvasZoneClientCache.designRevisions();
        OceanCanvasZoneClientCache.worldEvents();OceanCanvasZoneClientCache.worldObservations();OceanCanvasZoneClientCache.candidateEdits();
        y+=44; drawWorkbenchKnowledgeCompletion(g,x,y,tt,tid);
    }


    // ------------------------------------------------- P3 map / UX platform
    private int contextOriginX(){
        var p=project(); if(p!=null&&!p.regionName().isBlank())for(var z:zones())if(z.name().equalsIgnoreCase(p.regionName()))return (z.minX()+z.maxX())/2;
        var z=region(); return z==null?0:(z.minX()+z.maxX())/2;
    }
    private int contextOriginZ(){
        var p=project(); if(p!=null&&!p.regionName().isBlank())for(var z:zones())if(z.name().equalsIgnoreCase(p.regionName()))return (z.minZ()+z.maxZ())/2;
        var z=region(); return z==null?0:(z.minZ()+z.maxZ())/2;
    }
    private Set<String> visibleLayerKeys(){var out=new LinkedHashSet<String>();for(var e:layerOn.entrySet())if(Boolean.TRUE.equals(e.getValue()))out.add(e.getKey());return Set.copyOf(out);}
    private Set<String> visiblePanelKeys(){var out=new LinkedHashSet<String>();for(var e:panelVisible.entrySet())if(Boolean.TRUE.equals(e.getValue()))out.add(e.getKey());return Set.copyOf(out);}
    private void applyWorkspaceLayout(String name){
        var l=OceanCanvasP3UXState.layout(name);tab=Arrays.asList(TABS).contains(l.tab())?l.tab():"LAYERS";focusMode=l.focus();
        for(String k:new ArrayList<>(panelVisible.keySet()))panelVisible.put(k,l.panels().contains(k));
        for(String k:new ArrayList<>(layerOn.keySet()))layerOn.put(k,l.layers().contains(k));
        // Terrain + panel recovery are safety affordances: a stored layout cannot create a blank, unrecoverable shell.
        layerOn.put("terrain",true);panelVisible.put("navigation",true);panelVisible.put("header",true);
        OceanCanvasP3UXState.setActiveLayout(l.name());overlay=null;collapsed=false;say("Workspace → "+title(l.name()));
    }
    private void saveWorkspaceLayout(String name){
        OceanCanvasP3UXState.saveLayout(new OceanCanvasP3UXState.WorkspaceLayout(name,tab,focusMode,visiblePanelKeys(),visibleLayerKeys()));
        say("Saved "+title(name)+" workspace layout");
    }
    private String currentDeepLink(){
        LinkedHashMap<String,String> v=new LinkedHashMap<>();v.put("tab",tab);v.put("region",selectedRegion);v.put("project",selectedProject);v.put("phase",selectedPhase);v.put("task",selectedTask);v.put("plan",selectedPlan);v.put("object",selectedPlanObject);v.put("x",Integer.toString((int)Math.round(mapViewX)));v.put("z",Integer.toString((int)Math.round(mapViewZ)));
        if("workbench".equals(overlay))v.put("workbench",workbenchTab);if(opsOpen)v.put("ops",opsTab);return OceanCanvasP3UXState.deepLink(v);
    }
    private boolean applyDeepLink(String raw){
        var d=OceanCanvasP3UXState.parseDeepLink(raw);if(d.values().isEmpty())return false;
        if(d.has("tab")&&Arrays.asList(TABS).contains(d.get("tab").toUpperCase(Locale.ROOT)))tab=d.get("tab").toUpperCase(Locale.ROOT);
        if(d.has("region"))selectedRegion=d.get("region");if(d.has("project"))selectedProject=d.get("project");if(d.has("phase"))selectedPhase=d.get("phase");if(d.has("task"))selectedTask=d.get("task");if(d.has("plan"))selectedPlan=d.get("plan");if(d.has("object"))selectedPlanObject=d.get("object");
        try{if(d.has("x"))mapViewX=Double.parseDouble(d.get("x"));if(d.has("z"))mapViewZ=Double.parseDouble(d.get("z"));}catch(NumberFormatException ignored){}
        if(d.has("ops")){opsOpen=true;opsTab=d.get("ops").toUpperCase(Locale.ROOT);}if(d.has("workbench"))openWorkbench(d.get("workbench").toUpperCase(Locale.ROOT));else overlay=null;
        say("Opened Ocean Canvas deep link");return true;
    }
    private String phaseName(String id){var p=project();if(p!=null)for(var ph:OceanCanvasZoneClientCache.workspacePhases(p))if(ph.id().equals(id))return ph.name();return "";}
    private String taskName(String id){for(var t:OceanCanvasZoneClientCache.workspaceTasks())if(t.id().equals(id))return t.title();return "";}
    private void drawBreadcrumb(GuiGraphicsExtractor g){
        int x=470,y=66,w=590,h=24;fill(g,x,y,w,h,0xE6000000);border(g,x,y,w,h,BORDER);blocker(x,y,w,h);
        ArrayList<String[]> seg=new ArrayList<>();seg.add(new String[]{"WORLD","WORLD"});
        if(!selectedRegion.isBlank())seg.add(new String[]{trim(selectedRegion,92),"REGION"});
        var p=project();if(p!=null)seg.add(new String[]{trim(p.name(),92),"PROJECT"});
        String ph=phaseName(selectedPhase);if(!ph.isBlank())seg.add(new String[]{trim(ph,78),"PHASE"});
        String tn=taskName(selectedTask);if(!tn.isBlank())seg.add(new String[]{trim(tn,88),"TASK"});
        var pl=plan();if(pl!=null&&(p==null||tn.isBlank()))seg.add(new String[]{trim(pl.name(),88),"PLAN"});
        int xx=x+8;for(int i=0;i<seg.size()&&xx<x+390;i++){
            String label=seg.get(i)[0],kind=seg.get(i)[1];if(i>0){text(g,"›",xx,y+8,DIM);xx+=12;}int sw=Math.min(100,font.width(label)+8);text(g,label,xx,y+8,i==seg.size()-1?CY:TEXT);final String k=kind;
            hit(xx-2,y+2,sw,h-4,"Breadcrumb: "+kind,()->{switch(k){case "WORLD"->{tab="LAYERS";overlay=null;}case "REGION"->{tab="REGIONS";overlay=null;}case "PROJECT","PHASE","TASK"->{tab="PROJECTS";overlay=null;}case "PLAN"->{tab="PLANS";overlay=null;}}});xx+=sw;
        }
        int ux=x+397;String unit=title(OceanCanvasP3UXState.units().name());text(g,trim(unit,68),ux,y+8,DIM);hit(ux-3,y+2,76,h-4,"Unit display profile (U) — click to cycle",()->{var u=OceanCanvasP3UXState.cycleUnits();say("Units → "+title(u.name()));});
        int cx=x+474;String complexity=title(OceanCanvasP3UXState.complexity().name());text(g,trim(complexity,76),cx,y+8,DIM);hit(cx-3,y+2,84,h-4,"Beginner / Advanced / Developer view — click to cycle",()->{var c=OceanCanvasP3UXState.cycleComplexity();say("View → "+title(c.name()));});
        text(g,"⋯",x+w-22,y+7,CY);hit(x+w-31,y+2,29,h-4,"P3 UX controls, layouts and export",()->overlay="p3ux".equals(overlay)?null:"p3ux");
    }

    private void drawGoalPicker(GuiGraphicsExtractor g){
        int w=700,h=414,x=(DESIGN_W-w)/2,y=(DESIGN_H-h)/2-22;fill(g,x,y,w,h,0xFA020507);border(g,x,y,w,h,CY);blocker(x,y,w,h);
        text(g,"WHAT DO YOU WANT TO DO FIRST?",x+24,y+23,CY);text(g,"This only chooses a starting workspace. It never changes world data or locks features.",x+24,y+45,DIM);
        String[][] goals={{"BLANK_CANVAS","Blank a Canvas","Regions + operation status, with destructive previews front and centre."},{"PLAN_TERRAIN","Plan terrain","Plans, references and blueprint overlays."},{"MANAGE_PROJECT","Manage a project","Projects, phases, tasks and implementation context."},{"SURVEY_WORLD","Survey the world","Regions, structures, biomes and inspection tools."},{"STEWARD_WORLD","Steward an existing world","Recovery, health and long-lived world context."}};
        int yy=y+78;for(String[] row:goals){fill(g,x+24,yy,w-48,50,DARK);border(g,x+24,yy,w-48,50,BORDER);text(g,row[1],x+38,yy+11,TEXT);text(g,trim(row[2],w-90),x+38,yy+29,DIM);final String id=row[0];final int layoutIndex=yy;
            hit(x+24,layoutIndex,w-48,50,"Choose "+row[1],()->{OceanCanvasP3UXState.setGoal(id);String layout=switch(id){case "PLAN_TERRAIN"->"PLANNING";case "MANAGE_PROJECT"->"BUILDING";case "SURVEY_WORLD"->"SURVEY";case "STEWARD_WORLD"->"STEWARDSHIP";default->"SURVEY";};applyWorkspaceLayout(layout);});yy+=58;}
        text(g,"You can change workspace layouts and complexity at any time from the breadcrumb ⋯ menu.",x+24,y+h-24,DIM);
    }

    private void drawP3UxPanel(GuiGraphicsExtractor g){
        int w=780,h=552,x=(DESIGN_W-w)/2,y=(DESIGN_H-h)/2-12;fill(g,x,y,w,h,0xFA020507);border(g,x,y,w,h,CY);blocker(x,y,w,h);text(g,"MAP / UX PLATFORM",x+22,y+20,CY);text(g,"P3 shared view preferences — world and project truth remain server-owned",x+22,y+39,DIM);
        int yy=y+68;text(g,"COMPLEXITY",x+22,yy,DIM);actionRow(g,x+160,yy-8,180,28,title(OceanCanvasP3UXState.complexity().name()),CY,"Cycle Beginner / Advanced / Developer view. Beginner suppresses idle operations and gates dense tools without removing them.",()->{var c=OceanCanvasP3UXState.cycleComplexity();say("View → "+title(c.name()));});
        text(g,"UNITS",x+390,yy,DIM);actionRow(g,x+475,yy-8,260,28,title(OceanCanvasP3UXState.units().name()),CY,"Cycle blocks, chunks, km, walk time and project-relative units",()->{var u=OceanCanvasP3UXState.cycleUnits();say("Units → "+title(u.name()));});
        yy+=42;text(g,"OVERLAY MODE",x+22,yy,DIM);actionRow(g,x+160,yy-8,180,28,title(OceanCanvasP3UXState.colorMode().name()),CY,"Cycle colour-vision-safe overlay palettes; map roles also retain shape/pattern encodings",()->{var c=OceanCanvasP3UXState.cycleColorMode();say("Overlay mode → "+title(c.name()));});
        boolean hm=OceanCanvasP3UXState.heatmapEnabled();actionRow(g,x+390,yy-8,175,28,"HEATMAP: "+(hm?"ON":"OFF"),hm?CY:DIM,"Show synchronized Region / Plan / Project changes (H)",()->{OceanCanvasP3UXState.setHeatmapEnabled(!hm);say("Change heatmap "+(!hm?"on":"off"));});
        int hw=OceanCanvasP3UXState.heatmapWindowMinutes();actionRow(g,x+575,yy-8,160,28,"WINDOW: "+(hw<60?hw+" MIN":hw==60?"1 HOUR":"6 HOURS"),DIM,"Cycle the recent-change observation window",()->{int m=OceanCanvasP3UXState.cycleHeatmapWindow();say("Heatmap window → "+(m<60?m+" min":m==60?"1 hour":"6 hours"));});
        yy+=50;text(g,"WORKSPACE LAYOUTS",x+22,yy,DIM);yy+=18;String[] layouts={"PLANNING","BUILDING","SURVEY","STEWARDSHIP","DEBUGGING"};int lx=x+22;for(String l:layouts){int bw=l.equals("STEWARDSHIP")?136:112;final String target=l;actionRow(g,lx,yy,bw,28,title(l),l.equals(OceanCanvasP3UXState.activeLayout())?CY:DIM,"Apply "+title(l)+" workspace",()->applyWorkspaceLayout(target));lx+=bw+8;}
        yy+=38;text(g,"Save current view:",x+22,yy+8,DIM);lx=x+135;for(String l:layouts){int bw=l.equals("STEWARDSHIP")?120:98;final String target=l;actionRow(g,lx,yy,bw,26,title(l),DIM,"Persist current panels/layers as "+title(l),()->saveWorkspaceLayout(target));lx+=bw+7;}
        yy+=52;text(g,"MATURITY",x+22,yy,DIM);String[] mats={"S Stable","P Preview","D Developer","B Requires Backup","E Expensive"};lx=x+100;for(String m:mats){text(g,m,lx,yy,m.startsWith("S")?TEXT:GOLD);lx+=font.width(m)+18;}
        yy+=36;text(g,"DEEP LINK",x+22,yy,DIM);String link=currentDeepLink();fill(g,x+105,yy-8,w-149,28,DARK);border(g,x+105,yy-8,w-149,28,BORDER);text(g,trim(link,w-170),x+114,yy,TEXT);hit(x+105,yy-8,w-149,28,"Current context deep link. Click to route it through Search, or paste another oc:// link there.",()->{paletteQuery=link;overlay="palette";paletteIndex=0;paletteCacheQuery=null;});
        yy+=38;text(g,"MAP TITLE",x+22,yy,DIM);if("p3.map_title".equals(editKey))drawEditBox(g,x+105,yy-8,w-149,28);else{fill(g,x+105,yy-8,w-149,28,DARK);border(g,x+105,yy-8,w-149,28,BORDER);text(g,trim(mapComposerTitle.isBlank()?"Automatic: Ocean Canvas · "+title(tab):mapComposerTitle,w-175),x+114,yy,mapComposerTitle.isBlank()?DIM:TEXT);hit(x+105,yy-8,w-149,28,"Set the export title; Enter applies, Esc cancels",()->beginEdit("p3.map_title",mapComposerTitle));}
        yy+=40;actionRow(g,x+22,yy,220,30,"EXPORT MAP COMPOSITION",CY,"Export this viewport crop with chosen visible layers, title, legend and scale as a non-mutating SVG",this::exportMapComposition);
        actionRow(g,x+252,yy,170,30,"ACTIVITY CENTER",CY,"Open jobs, completions, warnings and export results",()->{overlay="activity";activityScroll=0;});
        actionRow(g,x+432,yy,150,30,"RESET GOAL",DIM,"Show the first-run goal picker again",()->{OceanCanvasP3UXState.setGoal("");overlay="goal_picker";});
        actionRow(g,x+w-120,yy,98,30,"CLOSE",DIM,"Close",()->overlay=null);
        text(g,"Keyboard: U units · H heatmap · F focus · S snap · Ctrl+B activity · Ctrl+J this panel",x+22,y+h-24,DIM);
    }
    private void exportMapComposition(){
        String exportTitle=mapComposerTitle.isBlank()?"Ocean Canvas · "+title(tab):mapComposerTitle;
        var out=OceanCanvasP3UXState.exportMapComposer(mapViewX,mapViewZ,mapBlocksPerPixel,DESIGN_W,DESIGN_H,visibleLayerKeys(),exportTitle);
        if(out==null)say("Map export failed — see Activity");else say("Exported "+out.getFileName());
    }
    private void drawActivityCenter(GuiGraphicsExtractor g){
        int w=760,h=490,x=(DESIGN_W-w)/2,y=(DESIGN_H-h)/2-18;fill(g,x,y,w,h,0xFA020507);border(g,x,y,w,h,CY);blocker(x,y,w,h);text(g,"ACTIVITY CENTER",x+22,y+20,CY);text(g,"Active work, recent completions, warnings and export results",x+22,y+39,DIM);
        int listY=y+68;var job=OceanCanvasZoneClientCache.job();if(job!=null){long total=Math.max(1,job.totalChunks()),done=Math.max(0,Math.min(total,job.submittedChunks()));int pct=(int)Math.round(done*100.0/total);fill(g,x+22,listY,w-44,42,0x171FC4EF);border(g,x+22,listY,w-44,42,CY);text(g,"ACTIVE",x+30,listY+8,CY);text(g,operationLabel(job.kind())+" · "+jobRegionName(job),x+98,listY+8,BRIGHT);right(g,pct+"% · "+fmt(done)+" / "+fmt(total)+" chunks",x+w-32,listY+8,TEXT);text(g,"Click to open live diagnostics",x+98,listY+25,DIM);hit(x+22,listY,w-44,42,"Open the active operation",()->{opsOpen=true;opsTab="DIAGNOSTICS";overlay=null;});listY+=50;}
        var rows=OceanCanvasP3UXState.activities();if(rows.isEmpty()){text(g,"No completed or warning activity recorded yet.",x+22,listY+18,TEXT);text(g,"Ocean Canvas keeps a bounded client-visible journal; world logs remain authoritative.",x+22,listY+38,DIM);}else{
            int start=Math.min(activityScroll,Math.max(0,rows.size()-1)),yy=listY;DateTimeFormatter fmt=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());int visible=job==null?10:9;
            for(int i=start;i<Math.min(rows.size(),start+visible);i++){var a=rows.get(i);int c=a.severity().equals("ERROR")?RED:a.severity().equals("COMPLETE")?GREEN:a.severity().equals("ACTIVE")?CY:GOLD;fill(g,x+22,yy,w-44,34,DARK);border(g,x+22,yy,w-44,34,BORDER);text(g,fmt.format(Instant.ofEpochMilli(a.at())),x+30,yy+7,DIM);text(g,trim(a.title(),240),x+100,yy+7,c);text(g,trim(a.detail(),380),x+320,yy+7,TEXT);if(!a.deepLink().isBlank()){text(g,"→",x+w-42,yy+7,CY);final String link=a.deepLink();hit(x+22,yy,w-44,34,"Open related Ocean Canvas context",()->applyDeepLink(link));}yy+=37;}
        }
        text(g,"Scroll for older activity · Ctrl+B opens this center",x+22,y+h-24,DIM);actionRow(g,x+w-236,y+h-38,106,26,"MARK SEEN",DIM,"Acknowledge current warning/error attention count without deleting activity history",()->{OceanCanvasP3UXState.acknowledgeAttention();say("Attention items marked seen");});actionRow(g,x+w-120,y+h-38,98,26,"CLOSE",DIM,"Close activity center",()->overlay=null);
    }

    private int[] planWorldBounds(OceanCanvasZoneClientCache.PlanningVector v){int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;for(String pair:(v==null||v.points()==null?"":v.points()).split(";")){String[] p=pair.trim().split(",");if(p.length<2)continue;try{int x=(int)Math.round(Double.parseDouble(p[0])),z=(int)Math.round(Double.parseDouble(p[1]));minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);}catch(NumberFormatException ignored){}}return minX==Integer.MAX_VALUE?null:new int[]{minX,minZ,maxX,maxZ};}
    private void scanChange(String key,String fingerprint,String kind,String id,int minX,int minZ,int maxX,int maxZ,long now){String old=changeFingerprints.put(key,fingerprint);if(old!=null&&!old.equals(fingerprint))recentChanges.put(key,new ChangeMark(kind,id,minX,minZ,maxX,maxZ,now));}
    private void updateChangeHeatmap(){long now=System.currentTimeMillis();if(now-changeScanAt<1000L)return;changeScanAt=now;
        for(var z:zones())scanChange("R:"+z.name(),z.minX()+":"+z.minZ()+":"+z.maxX()+":"+z.maxZ()+":"+z.enabled()+":"+z.shapeVertices(),"region",z.name(),z.minX(),z.minZ(),z.maxX(),z.maxZ(),now);
        for(var v:OceanCanvasZoneClientCache.planningVectors()){int[] b=planWorldBounds(v);if(b!=null)scanChange("P:"+v.id(),v.points()+":"+v.visible()+":"+v.implemented()+":"+v.parentId(),"plan",v.id(),b[0],b[1],b[2],b[3],now);}
        for(var p:OceanCanvasZoneClientCache.workspaceProjects())for(var z:zones())if(z.name().equalsIgnoreCase(p.regionName())){scanChange("J:"+p.id(),p.status()+":"+p.progress()+":"+p.phases()+":"+p.milestones(),"project",p.id(),z.minX(),z.minZ(),z.maxX(),z.maxZ(),now);break;}
        long window=OceanCanvasP3UXState.heatmapWindowMinutes()*60_000L;recentChanges.entrySet().removeIf(e->now-e.getValue().changedAt()>window);
    }
    private void drawChangeHeatmap(GuiGraphicsExtractor g){long now=System.currentTimeMillis(),window=OceanCanvasP3UXState.heatmapWindowMinutes()*60_000L;int base=OceanCanvasP3UXState.colorFor("change",GOLD);for(var m:recentChanges.values()){long age=now-m.changedAt();if(age>window)continue;int l=mapScreenX(m.minX()),t=mapScreenY(m.minZ()),r=mapScreenX(m.maxX()),b=mapScreenY(m.maxZ());if(r<0||b<0||l>DESIGN_W||t>DESIGN_H)continue;int w=Math.max(3,r-l),h=Math.max(3,b-t);int alpha=(int)Math.max(20,90-age*70.0/Math.max(1L,window));fill(g,l,t,w,h,(alpha<<24)|(base&0x00FFFFFF));border(g,l,t,w,h,base);for(int xx=l-h;xx<r;xx+=18)line(g,xx,b,xx+h,t,(0x77000000)|(base&0x00FFFFFF));}}
    private void drawRolePattern(GuiGraphicsExtractor g,String role,int x,int y,int w,int h,int c){
        if(OceanCanvasP3UXState.colorMode()==OceanCanvasP3UXState.ColorMode.DEFAULT)return;int p=OceanCanvasP3UXState.patternFor(role),pc=(0x99000000)|(c&0x00FFFFFF);
        if(p==1){for(int i=0;i<Math.min(16,w);i+=4){fill(g,x+i,y,2,2,c);fill(g,x+w-2-i,y+h-2,2,2,c);}}
        else if(p==2){for(int xx=x-h;xx<x+w;xx+=16)line(g,xx,y+h,xx+h,y,pc);}
        else if(p==3){for(int xx=x+5;xx<x+w;xx+=14)fill(g,xx,y+4,1,Math.max(1,h-8),pc);for(int yy=y+5;yy<y+h;yy+=14)fill(g,x+4,yy,Math.max(1,w-8),1,pc);}
        else if(p==4){for(int yy=y+4;yy<y+h;yy+=10)for(int xx=x+4+(yy/10%2)*5;xx<x+w;xx+=10)fill(g,xx,yy,2,2,c);}
        else if(p==5){for(int xx=x-h;xx<x+w;xx+=14)line(g,xx,y+h,xx+h,y,pc);}
    }

    private void drawContextMiniMapLens(GuiGraphicsExtractor g){
        int w=208,h=132,x=DESIGN_W-366-w,y=DESIGN_H-214;fill(g,x,y,w,h,0xF0000000);border(g,x,y,w,h,CY);blocker(x,y,w,h);text(g,"CONTEXT LENS",x+9,y+8,CY);text(g,trim(editKey==null?"":editKey,w-86),x+90,y+8,DIM);
        int minX,maxX,minZ,maxZ;String label="Current view";var z=region();var v=planObject();
        if(v!=null&&editKey!=null&&editKey.startsWith("plan")){int[] b=planWorldBounds(v);if(b==null)return;minX=b[0];minZ=b[1];maxX=b[2];maxZ=b[3];label=v.name();}
        else if(z!=null){minX=z.minX();minZ=z.minZ();maxX=z.maxX();maxZ=z.maxZ();label=z.name();}
        else{minX=(int)mapViewX-128;maxX=(int)mapViewX+128;minZ=(int)mapViewZ-128;maxZ=(int)mapViewZ+128;}
        double bw=Math.max(1,maxX-minX),bh=Math.max(1,maxZ-minZ),scale=Math.min((w-28)/bw,(h-52)/bh);int rw=Math.max(4,(int)Math.round(bw*scale)),rh=Math.max(4,(int)Math.round(bh*scale)),rx=x+(w-rw)/2,ry=y+31+(h-45-rh)/2;fill(g,rx,ry,rw,rh,0x221FC4EF);border(g,rx,ry,rw,rh,OceanCanvasP3UXState.colorFor(v!=null?"plan":"region",CY));drawRolePattern(g,v!=null?"plan":"region",rx,ry,rw,rh,CY);text(g,trim(label,w-18),x+9,y+h-14,TEXT);
    }

    // ------------------------------------------------- command palette / universal search
    /** Opens the palette. Always starts empty so the default view is the full command list. */
    private void openPalette(){
        overlay="palette"; paletteQuery=""; paletteIndex=0; paletteScroll=0;
        paletteCacheQuery=null; cancelEdit();
    }
    private void closePalette(){ if("palette".equals(overlay)) overlay=null; }

    /** Records what the user actually ran, so the empty palette opens on their recent work. */
    private void notePaletteUse(String key){
        recentPalette.remove(key); recentPalette.add(0,key);
        while(recentPalette.size()>6) recentPalette.remove(recentPalette.size()-1);
    }

    private void paletteGoto(double worldX,double worldZ){
        mapViewX=worldX; mapViewZ=worldZ;
        coordX=(int)Math.round(worldX); coordZ=(int)Math.round(worldZ);
    }

    /**
     * Parses a coordinate the user typed. Accepts {@code "120 -340"}, {@code "120, -340"} and
     * {@code "120/-340"}. Returns null when the query is not a coordinate pair, so an ordinary
     * word search is never hijacked by a stray number.
     */
    private static int[] parseCoordinateQuery(String q){
        String s=q.trim().replace(',',' ').replace('/',' ').replace(';',' ');
        String[] parts=s.split("\\s+");
        if(parts.length!=2) return null;
        try{ return new int[]{Integer.parseInt(parts[0]),Integer.parseInt(parts[1])}; }
        catch(NumberFormatException e){ return null; }
    }

    private String requiresAdvanced(String label){
        return OceanCanvasP3UXState.complexity()==OceanCanvasP3UXState.Complexity.BEGINNER
                ? "Switch to Advanced or Developer view for "+label : null;
    }
    private String requiresDeveloper(String label){
        return OceanCanvasP3UXState.complexity()!=OceanCanvasP3UXState.Complexity.DEVELOPER
                ? "Switch to Developer view for "+label : null;
    }

    /** Every command the palette can run, with the real reason when one cannot. */
    private List<PaletteEntry> paletteCommands(){
        var out=new ArrayList<PaletteEntry>();
        var z=region();
        String noRegion = z==null ? "Select a region first" : null;
        boolean running = OceanCanvasZoneClientCache.job()!=null;
        var compat = OceanCanvasZoneClientCache.recoveryCompatibility();
        String readOnly = compat.readOnly()
                ? (compat.reason().isBlank() ? "This save is read-only for Ocean Canvas" : compat.reason())
                : null;

        for(String t:TABS){
            final String target=t;
            out.add(new PaletteEntry("Go","Go to "+title(t)+" tab","Top-level navigation","",null,
                    "tab "+t,()->{ tab=target; overlay=null; }));
        }
        int ti=tabIndex();
        for(String[] tool:TOOLS[ti]){
            final String name=tool[0];
            out.add(new PaletteEntry("Tool","Tool: "+name,"On the "+title(tab)+" tab","",null,
                    "tool "+name,()->{ selectTool(name); overlay=null; }));
        }
        for(String[] other:new String[][]{{"Shape","REGIONS"},{"Vertex","REGIONS"},{"Path","PLANS"},{"Waypoint","LAYERS"}}){
            boolean present=false;
            for(String[] tool:TOOLS[ti]) if(tool[0].equals(other[0])) present=true;
            if(present) continue;
            out.add(new PaletteEntry("Tool","Tool: "+other[0],"Belongs to another tab","",
                    "Not available on the "+title(tab)+" tab","tool "+other[0],()->{}));
        }

        for(String[] layer:LAYERS){
            final String key=layer[0]; final String label=layer[2];
            boolean on=Boolean.TRUE.equals(layerOn.get(key));
            out.add(new PaletteEntry("View","Toggle layer: "+label,on?"Visible":"Hidden","",null,
                    "layer "+label,()->{ layerOn.put(key,!Boolean.TRUE.equals(layerOn.get(key))); }));
        }
        out.add(new PaletteEntry("View","Recentre on player","Move the map to your position","C",
                minecraft==null||minecraft.player==null?"No player in this world":null,"recentre centre center player",()->{
            if(minecraft!=null&&minecraft.player!=null) paletteGoto(minecraft.player.getX(),minecraft.player.getZ());
            overlay=null;
        }));
        out.add(new PaletteEntry("View","Zoom in","Halve the blocks-per-pixel scale","",null,"zoom in",
                ()->mapBlocksPerPixel=Math.max(0.5,mapBlocksPerPixel*0.5)));
        out.add(new PaletteEntry("View","Zoom out","Double the blocks-per-pixel scale","",null,"zoom out",
                ()->mapBlocksPerPixel=Math.min(64.0,mapBlocksPerPixel*2.0)));
        out.add(new PaletteEntry("View","Show or hide panels","Panel visibility controls","",null,"panels",
                ()->overlay="panels"));
        out.add(new PaletteEntry("View","Toggle inspector","Click the map for server-authored state","",null,"inspect",()->{
            inspectMode=!inspectMode; if(inspectMode) cancelGesture();
            say(inspectMode?"Inspect active — click the map":"Inspect off"); overlay=null;
        }));

        for(String[] ops:new String[][]{{"DIAGNOSTICS","Live Pregen telemetry"},{"QUEUE","Pregen queue"},
                {"HEALTH","Health and workflow scan"},{"RECOVERY","Compatibility, history and snapshots"}}){
            final String target=ops[0];
            String unavailable="DIAGNOSTICS".equals(target)?requiresDeveloper("diagnostics")
                    : ("HEALTH".equals(target)?requiresAdvanced("health tools"):null);
            out.add(new PaletteEntry("Ops","Operations: "+title(target),ops[1],"",unavailable,
                    "operations "+target,()->{ opsOpen=true; opsTab=target; overlay=null; }));
        }
        for(String[] wb:new String[][]{{"RECOVERY","Recovery and stewardship tools"},{"LIBRARY","Presets, reference sets, terrain revisions"},
                {"FOREVER","Prototypes, transitions, scenarios, atlas routes"},{"KNOWLEDGE","Policies, claims and authorship"}}){
            final String target=wb[0];
            String unavailable="RECOVERY".equals(target)?null:requiresAdvanced(title(target)+" workbench");
            out.add(new PaletteEntry("Ops","Workbench: "+title(target),wb[1],"",unavailable,
                    "workbench "+target,()->openWorkbench(target)));
        }
        out.add(new PaletteEntry("UX","UX & map platform","Units, workspace layouts, complexity, colour-safe overlays and export","Ctrl+J",null,
                "p3 ux units layout complexity accessibility composer",()->overlay="p3ux"));
        out.add(new PaletteEntry("UX","Activity center","Active work, recent completions and attention items","Ctrl+B",null,
                "activity jobs warnings failures",()->{overlay="activity";activityScroll=0;}));
        out.add(new PaletteEntry("UX","Cycle unit profile","Blocks, chunks, kilometres, travel time or project-relative","U",null,
                "units blocks chunks kilometers travel relative",()->{var u=OceanCanvasP3UXState.cycleUnits();say("Units → "+title(u.name()));}));
        out.add(new PaletteEntry("UX","Toggle change heatmap","Recent Region, Plan and Project state changes","H",null,
                "change heatmap history map",()->{boolean on=!OceanCanvasP3UXState.heatmapEnabled();OceanCanvasP3UXState.setHeatmapEnabled(on);say("Change heatmap "+(on?"on":"off"));}));
        out.add(new PaletteEntry("UX","Export current map composition","Non-mutating SVG export of the current crop and visible vector layers","",null,
                "print export map composer svg atlas",this::exportMapComposition));

        final String rn = z==null ? "" : z.name();
        out.add(new PaletteEntry("Region","Pregen region"+(z==null?"":": "+rn),
                z==null?"Generate and carve every chunk in a region":"Generate and carve every chunk in "+rn,"Ctrl+G",
                noRegion!=null?noRegion:(running?"Another Ocean Canvas operation is already running":readOnly),
                "pregen region",()->{ if(!ensureOperationPreview("PREGEN",rn,""))return;String token=operationPreviewToken("PREGEN",rn,"");if(token==null)return;OceanCanvasZoneClientCache.clearOperationPreview();send(new OceanCanvasZonePregenRequestPayload(rn,token));say("Pregen requested for "+rn); }));
        out.add(new PaletteEntry("Region","Queue pregen"+(z==null?"":": "+rn),
                "Add the region to the Pregen queue instead of starting now","",
                noRegion!=null?noRegion:readOnly,"queue pregen",()->{
            workspace("queue_add","",rn,""); say("Queued "+rn+" — open Operations to review and run");
        }));
        boolean rewipeArmed = z!=null && operationPreviewToken("REWIPE",rn,"")!=null;
        out.add(new PaletteEntry("Region",rewipeArmed?"Confirm rewipe: "+rn:"Rewipe region"+(z==null?"":": "+rn),
                REV_REWIPE.label()+" — returns every chunk to blank Ocean Canvas state","",
                noRegion!=null?noRegion:readOnly,"rewipe region",()->{
            if(!ensureOperationPreview("REWIPE",rn,""))return;String token=operationPreviewToken("REWIPE",rn,"");if(token==null)return;OceanCanvasZoneClientCache.clearOperationPreview();send(new OceanCanvasZoneRewipeRequestPayload(rn,token));say("Rewipe requested for "+rn);
        }));
        boolean restoreArmed = z!=null && operationPreviewToken("RESTORE",rn,"")!=null;
        out.add(new PaletteEntry("Region",restoreArmed?"Confirm restore: "+rn:"Restore region to vanilla"+(z==null?"":": "+rn),
                REV_NONE.label()+" — returns every chunk to vanilla generation","",
                noRegion!=null?noRegion:readOnly,"restore vanilla region",()->{
            if(!ensureOperationPreview("RESTORE",rn,""))return;String token=operationPreviewToken("RESTORE",rn,"");if(token==null)return;OceanCanvasZoneClientCache.clearOperationPreview();send(new OceanCanvasZoneRestoreRequestPayload(rn,token));say("Restore requested for "+rn);
        }));
        out.add(new PaletteEntry("Region","Go here"+(z==null?"":": "+rn),"Teleport to the centre of the region","",
                noRegion,"teleport go here region",()->{ send(new OceanCanvasZoneTeleportRequestPayload(rn)); say("Teleporting to "+rn); overlay=null; }));
        out.add(new PaletteEntry("Region","Duplicate region"+(z==null?"":": "+rn),"Copy the region alongside itself","",
                noRegion,"duplicate region",()->{
            if(z==null) return;
            int w=z.maxX()-z.minX();
            send(new OceanCanvasZoneDuplicateRequestPayload(rn,nextRegionName(rn),
                    z.maxX()+16,z.minY(),z.minZ(),z.maxX()+16+w,z.maxY(),z.maxZ()));
            say("Duplicated "+rn); overlay=null;
        }));
        out.add(new PaletteEntry("Region",(z!=null&&z.enabled())?"Unprotect region: "+rn:"Protect region"+(z==null?"":": "+rn),
                "Toggle Ocean Canvas protection for the region","",noRegion,"protect region",()->{
            if(z==null) return;
            send(new OceanCanvasZoneSetEnabledRequestPayload(rn,!z.enabled()));
            say((z.enabled()?"Unprotecting ":"Protecting ")+rn); overlay=null;
        }));

        out.add(new PaletteEntry("Edit","Undo Plan edit","Undo the most recent Plan edit","Ctrl+Z",null,
                "undo plan edit",this::planUndo));
        out.add(new PaletteEntry("Edit","Redo Plan edit","Redo the most recently undone Plan edit","Ctrl+Y",null,
                "redo plan edit",this::planRedo));
        out.add(new PaletteEntry("Help","Undo and reversibility","What can be undone, and what cannot","",null,
                "undo reversibility irreversible",()->openWorkbench("RECOVERY")));
        out.add(new PaletteEntry("Help","Keyboard shortcuts","Open the shortcut list","",null,"help shortcuts keys",
                ()->overlay="help"));
        out.add(new PaletteEntry("Help","Ocean Canvas settings","Global canvas settings screen","",
                minecraft==null?"No client available":null,"settings config",()->{
            if(minecraft!=null) minecraft.gui.setScreen(new OceanCanvasSettingsScreen(this));
        }));
        return out;
    }

    /** Synchronised objects the user can jump to. Everything here comes from the client cache. */
    private List<PaletteEntry> paletteObjects(){
        var out=new ArrayList<PaletteEntry>();
        for(var z:zones()){
            final String name=z.name();
            double cx=(z.minX()+z.maxX())/2.0, cz=(z.minZ()+z.maxZ())/2.0;
            int w=z.maxX()-z.minX()+1, d=z.maxZ()-z.minZ()+1;
            var meta=OceanCanvasZoneClientCache.projectRegion(name);
            String sub=w+"×"+d+" blocks · "+(z.enabled()?"Protected":"Unprotected")
                    +(meta==null?"":" · "+title(meta.stage()));
            out.add(new PaletteEntry("Region",name,sub,"",null,"region "+name,()->{
                selectedRegion=name; tab="REGIONS"; paletteGoto(cx,cz); overlay=null; say("Selected "+name);
            }));
        }
        for(var g:OceanCanvasZoneClientCache.planGroups()){
            final String id=g.id(), name=g.name();
            out.add(new PaletteEntry("Plan",name,"Plan layer"+(g.category().isBlank()?"":" · "+title(g.category()))
                    +(g.visible()?"":" · hidden"),"",null,"plan layer "+name,()->{
                selectedPlan=id; selectedPlanObject=""; tab="PLANS"; overlay=null; say("Selected plan "+name);
            }));
        }
        for(var v:OceanCanvasZoneClientCache.planningVectors()){
            final String id=v.id(), name=v.name(), parent=v.parentId();
            out.add(new PaletteEntry("Plan",name,"Plan object · "+title(v.type()),"",null,"plan object "+name,()->{
                selectedPlanObject=id; if(parent!=null&&!parent.isBlank()) selectedPlan=parent;
                tab="PLANS"; overlay=null; say("Selected "+name);
            }));
        }
        for(var p:OceanCanvasZoneClientCache.workspaceProjects()){
            final String id=p.id(), name=p.name();
            out.add(new PaletteEntry("Project",name,"Project · "+title(p.status())
                    +(p.regionName().isBlank()?"":" · "+p.regionName()),"",null,"project "+name,()->{
                selectedProject=id; tab="PROJECTS"; overlay=null; say("Selected project "+name);
            }));
        }
        for(var t:OceanCanvasZoneClientCache.workspaceTasks()){
            final String taskTitle=t.title(); final int tx=t.x(), tz=t.z();
            final String owner=t.projectId();
            out.add(new PaletteEntry("Task",taskTitle,"Task · "+title(t.status())
                    +(t.regionName().isBlank()?"":" · "+t.regionName()),"",null,"task "+taskTitle,()->{
                if(owner!=null&&!owner.isBlank()) selectedProject=owner;
                tab="PROJECTS"; paletteGoto(tx,tz); overlay=null; say("Task: "+taskTitle);
            }));
        }
        for(var b:OceanCanvasZoneClientCache.planBookmarks()){
            final String name=b.name(); final int bx=b.centerX(), bz=b.centerZ(); final double zoom=b.zoom();
            out.add(new PaletteEntry("Bookmark",name,"Bookmark · "+bx+", "+bz,"",null,"bookmark "+name,()->{
                paletteGoto(bx,bz);
                if(zoom>0) mapBlocksPerPixel=Math.max(0.5,Math.min(64.0,zoom));
                overlay=null; say("Bookmark "+name);
            }));
        }
        for(var v:OceanCanvasZoneClientCache.blueprintViewpoints()){
            final String name=v.name(); final double vx=v.x(), vz=v.z();
            out.add(new PaletteEntry("Viewpoint",name,"Viewpoint · "+(int)Math.round(vx)+", "+(int)Math.round(vz),"",null,
                    "viewpoint "+name,()->{ paletteGoto(vx,vz); overlay=null; say("Viewpoint "+name); }));
        }
        for(var a:OceanCanvasZoneClientCache.terrainAssets()){
            final String id=a.id(), name=a.name();
            double cx=(a.minX()+a.maxX())/2.0, cz=(a.minZ()+a.maxZ())/2.0;
            out.add(new PaletteEntry("Terrain",name,"Terrain asset · "+title(a.status())
                    +" · "+a.revisionCount()+" revisions","",null,"terrain asset "+name,()->{
                selectedTerrainAsset=id; paletteGoto(cx,cz); openWorkbench("LIBRARY"); say("Terrain asset "+name);
            }));
        }
        for(var f:OceanCanvasZoneClientCache.atlasFeatures()){
            final String name=f.name(); final int fx=f.x(), fz=f.z(); final String fid=f.id();
            out.add(new PaletteEntry("Atlas",name,"Atlas "+title(f.type())
                    +(f.regionName().isBlank()?"":" · "+f.regionName()),"",null,"atlas waypoint "+name,()->{
                paletteGoto(fx,fz); selectedWaypoint=fid; tab="LAYERS"; disclosure=null; overlay=null; say("Selected "+name);
            }));
        }
        for(var r:OceanCanvasZoneClientCache.atlasRoutes()){
            final String name=r.name();
            if(r.stops().isEmpty()){
                out.add(new PaletteEntry("Atlas",name,"Route · no stops yet","","This route has no stops to travel to",
                        "route "+name,()->{}));
                continue;
            }
            final int rx=r.stops().get(0).x(), rz=r.stops().get(0).z(); final int n=r.stops().size();
            out.add(new PaletteEntry("Atlas",name,"Route · "+n+" stops","",null,"route "+name,()->{
                paletteGoto(rx,rz); overlay=null; say("Route "+name+" — first stop");
            }));
        }
        return out;
    }

    /** Command list plus object list, filtered and ranked against the query. */
    private List<PaletteEntry> paletteEntries(){
        long gen=OceanCanvasZoneClientCache.planningGeneration();
        long now=System.currentTimeMillis();
        if(paletteQuery.equals(paletteCacheQuery) && gen==paletteCacheGen && now-paletteCacheAt<PALETTE_REFRESH_MS)
            return paletteCache;

        var all=new ArrayList<PaletteEntry>(paletteCommands());
        all.addAll(paletteObjects());
        String q=paletteQuery.trim().toLowerCase(Locale.ROOT);

        var result=new ArrayList<PaletteEntry>();
        if(paletteQuery.trim().startsWith("oc://")){
            var dl=OceanCanvasP3UXState.parseDeepLink(paletteQuery.trim());
            String unavailable=dl.values().isEmpty()?"This Ocean Canvas deep link is malformed":null;
            result.add(new PaletteEntry("Link","Open Ocean Canvas deep link","Jump to the encoded tab, object, coordinate or operations surface","Enter",unavailable,
                    "deep link navigation",()->applyDeepLink(paletteQuery.trim())));
        }
        int[] coord=parseCoordinateQuery(paletteQuery);
        if(coord!=null){
            final int gxc=coord[0], gzc=coord[1];
            result.add(new PaletteEntry("Go","Go to "+gxc+", "+gzc,"Centre the map on these coordinates","",null,
                    "coordinate",()->{ paletteGoto(gxc,gzc); overlay=null; say("Centred on "+gxc+", "+gzc); }));
        }
        if(q.isEmpty()){
            for(String key:recentPalette)
                for(var e:all) if(paletteKey(e).equals(key)){ result.add(e); break; }
            for(var e:all) if(!result.contains(e)) result.add(e);
        }else{
            record Ranked(int score,int order,PaletteEntry e){}
            var ranked=new ArrayList<Ranked>();
            int order=0;
            for(var e:all){
                String hay=(e.title()+" "+e.subtitle()+" "+e.kind()+" "+e.searchText()).toLowerCase(Locale.ROOT);
                String t=e.title().toLowerCase(Locale.ROOT);
                int score;
                if(t.equals(q)) score=0;
                else if(t.startsWith(q)) score=1;
                else if(t.contains(q)) score=2;
                else if(hay.contains(q)) score=3;
                else if(paletteSubsequence(q,t)) score=4;
                else { order++; continue; }
                ranked.add(new Ranked(score,order++,e));
            }
            ranked.sort(Comparator.<Ranked>comparingInt(r->r.score()).thenComparingInt(r->r.order()));
            for(var r:ranked) result.add(r.e());
        }
        paletteCache=List.copyOf(result); paletteCacheQuery=paletteQuery;
        paletteCacheGen=gen; paletteCacheAt=now;
        if(paletteIndex>=paletteCache.size()) paletteIndex=Math.max(0,paletteCache.size()-1);
        return paletteCache;
    }
    private static String paletteKey(PaletteEntry e){ return e.kind()+"|"+e.title(); }
    /** Cheap fuzzy fallback so "rwp" still finds Rewipe. */
    private static boolean paletteSubsequence(String q,String target){
        int i=0;
        for(int j=0;j<target.length()&&i<q.length();j++) if(target.charAt(j)==q.charAt(i)) i++;
        return i==q.length();
    }

    private void runPaletteEntry(PaletteEntry e){
        if(e.unavailable()!=null){ say(e.unavailable()); return; }
        boolean wasArmed = armed!=null;
        notePaletteUse(paletteKey(e));
        e.run().run();
        // A destructive entry that just armed itself must stay reachable for its confirmation.
        if(!wasArmed && armed!=null){ paletteCacheQuery=null; return; }
        closePalette();
    }

    private void drawPalette(GuiGraphicsExtractor g){
        // Registered first so every later control wins topHit(); this only catches clicks that
        // land outside the panel, which dismiss rather than falling through to the map.
        hit(0,0,DESIGN_W,DESIGN_H,null,this::closePalette);
        int x=(DESIGN_W-PALETTE_W)/2, y=104;
        fill(g,x,y,PALETTE_W,PALETTE_H,0xF704070A); border(g,x,y,PALETTE_W,PALETTE_H,CY);
        blocker(x,y,PALETTE_W,PALETTE_H);
        text(g,"COMMAND PALETTE",x+20,y+16,CY);
        right(g,"Enter run · ↑↓ move · Esc close",x+PALETTE_W-20,y+16,DIM);
        icon(g,"close",x+PALETTE_W-30,y+12,DIM);
        hit(x+PALETTE_W-38,y+6,34,30,"Close palette",this::closePalette);

        int qy=y+38;
        fill(g,x+16,qy,PALETTE_W-32,30,0xFF090C0F); border(g,x+16,qy,PALETTE_W-32,30,BORDER);
        String shown=paletteQuery.isEmpty()?"Search regions, plans, projects, coordinates, commands or paste oc://…":paletteQuery;
        text(g,trim(shown,PALETTE_W-72),x+26,qy+10,paletteQuery.isEmpty()?DIM:BRIGHT);
        if(!paletteQuery.isEmpty()) fill(g,x+26+font.width(trim(paletteQuery,PALETTE_W-72)),qy+8,1,14,CY);

        var entries=paletteEntries();
        int listY=qy+40;
        if(entries.isEmpty()){
            text(g,"Nothing matches \""+trim(paletteQuery,320)+"\".",x+22,listY+8,DIM);
            text(g,"Search covers regions, plan layers and objects, projects, tasks, bookmarks,",x+22,listY+30,DIM);
            text(g,"viewpoints, terrain assets, atlas features and routes.",x+22,listY+48,DIM);
            return;
        }
        if(paletteIndex<paletteScroll) paletteScroll=paletteIndex;
        if(paletteIndex>=paletteScroll+PALETTE_ROWS) paletteScroll=paletteIndex-PALETTE_ROWS+1;
        paletteScroll=Math.max(0,Math.min(paletteScroll,Math.max(0,entries.size()-PALETTE_ROWS)));

        int shownRows=Math.min(PALETTE_ROWS,entries.size()-paletteScroll);
        for(int i=0;i<shownRows;i++){
            final int idx=paletteScroll+i;
            PaletteEntry e=entries.get(idx);
            int ry=listY+i*PALETTE_ROW_H;
            boolean sel=idx==paletteIndex;
            boolean off=e.unavailable()!=null;
            var risk=OceanCanvasP3UXState.riskFor(e.title()+" "+e.subtitle()+" "+e.searchText());
            int riskColor=switch(risk){case SAFE->0xFF4E7D5B;case REVERSIBLE->CY;case DESTRUCTIVE->RED;case VERSION_SENSITIVE->GOLD;};
            if(sel) fill(g,x+16,ry,PALETTE_W-32,PALETTE_ROW_H-2,0x171FC4EF);
            fill(g,x+16,ry,3,PALETTE_ROW_H-2,off?0xFF3A3A3A:(sel?CY:riskColor));
            text(g,e.kind(),x+28,ry+9,off?0xFF5F6467:DIM);
            var maturity=OceanCanvasP3UXState.maturityFor(e.title()+" "+e.subtitle()+" "+e.searchText());
            int badgeColor=switch(maturity){case STABLE->GREEN;case PREVIEW->CY;case DEVELOPER->GOLD;case REQUIRES_BACKUP->RED;case EXPENSIVE->GOLD;};
            String badge=OceanCanvasP3UXState.maturityShort(maturity);
            fill(g,x+86,ry+6,18,14,0xFF0B1013);border(g,x+86,ry+6,18,14,badgeColor);center(g,badge,x+86,ry+9,18,badgeColor);
            int titleX=x+112;
            text(g,trim(e.title(),290),titleX,ry+9,off?0xFF6E7376:(sel?BRIGHT:TEXT));
            String rightText = off ? e.unavailable() : (e.hint().isBlank()?e.subtitle():e.hint());
            right(g,trim(rightText,300),x+PALETTE_W-26,ry+9,off?GOLD:DIM);
            hit(x+16,ry,PALETTE_W-32,PALETTE_ROW_H-2,
                    off?e.unavailable():(e.subtitle().isBlank()?e.title():e.subtitle()),
                    ()->{ paletteIndex=idx; runPaletteEntry(entries.get(idx)); });
        }
        int footer=listY+PALETTE_ROWS*PALETTE_ROW_H+6;
        hline(g,x+16,footer-4,PALETTE_W-32);
        text(g,entries.size()+" result"+(entries.size()==1?"":"s")
                +(paletteScroll>0||entries.size()>PALETTE_ROWS?"  ·  "+(paletteScroll+1)+"–"+(paletteScroll+shownRows):""),
                x+22,footer+6,DIM);
        right(g,"Unavailable commands stay listed with their reason",x+PALETTE_W-26,footer+6,DIM);
    }

    private boolean palettePressed(int key,boolean ctrl){
        if(!"palette".equals(overlay)) return false;
        var entries=paletteEntries();
        switch(key){
            case GLFW.GLFW_KEY_ESCAPE -> { closePalette(); return true; }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if(ctrl) paletteQuery="";
                else if(!paletteQuery.isEmpty()) paletteQuery=paletteQuery.substring(0,paletteQuery.length()-1);
                paletteIndex=0; paletteScroll=0; return true;
            }
            case GLFW.GLFW_KEY_DOWN -> { if(!entries.isEmpty()) paletteIndex=Math.min(entries.size()-1,paletteIndex+1); return true; }
            case GLFW.GLFW_KEY_UP -> { paletteIndex=Math.max(0,paletteIndex-1); return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { if(!entries.isEmpty()) paletteIndex=Math.min(entries.size()-1,paletteIndex+PALETTE_ROWS); return true; }
            case GLFW.GLFW_KEY_PAGE_UP -> { paletteIndex=Math.max(0,paletteIndex-PALETTE_ROWS); return true; }
            case GLFW.GLFW_KEY_HOME -> { paletteIndex=0; return true; }
            case GLFW.GLFW_KEY_END -> { paletteIndex=Math.max(0,entries.size()-1); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if(paletteIndex>=0&&paletteIndex<entries.size()) runPaletteEntry(entries.get(paletteIndex));
                return true;
            }
            default -> { return true; } // the open palette owns every other key, including tool shortcuts
        }
    }

    // ------------------------------------------------- explain this state
    /**
     * One plain-language account of a controller state. Every field is derived from the code that
     * produces the state, not from guesswork; {@code watch} names the live figure that will move
     * when the state resolves, so the user knows what to look at rather than just waiting.
     */
    private record StateExplanation(String heading,String meaning,String waitingFor,String youCanDo,String watch) { }

    /** Reasons that carry a trailing count are matched by prefix. */
    private static boolean reasonIs(String raw,String prefix){ return raw.startsWith(prefix); }

    private StateExplanation explanationFor(String kind,String raw){
        var p=OceanCanvasZoneClientCache.performance();
        String r=raw==null?"":raw.trim();
        if("phase".equals(kind)) return switch(r){
            case "FEEDING" -> new StateExplanation("Phase: Feeding",
                    "The controller is admitting new chunks into the carve queue.",
                    "Nothing. This is the normal working state.",
                    "Nothing — let it run.",
                    "Throughput ("+String.format(Locale.US,"%.2f",p.chunksPerSecond())+" chunks/s) should stay above zero.");
            case "FINAL_DRAIN" -> new StateExplanation("Phase: Final drain",
                    "Every target chunk has been submitted. No new work is admitted from here.",
                    "The chunks Ocean Canvas already owns to finish carving and release their tickets.",
                    "Nothing — but this is the phase where a stall is most visible.",
                    "Final-drain tickets ("+fmt(p.finalDrainTickets())+") and Outstanding ("+fmt(p.outstanding())+") must both fall to zero.");
            case "THROTTLED" -> new StateExplanation("Phase: Throttled",
                    "Admission is closed this tick. The controller admitted no new chunks.",
                    "Whatever the Reason line names — that is the specific condition holding the inlet shut.",
                    "Read the Reason line below the phase; it is the actionable part, not this.",
                    "Admission rate ("+p.rate()+" / "+p.rateCap()+" per tick) returning above zero.");
            case "IDLE" -> new StateExplanation("Phase: Idle",
                    "No Pregen, Rewipe, Restore or Expand job is running.",
                    "Nothing.",
                    "Start a job from a region panel or the queue.",
                    "Nothing to watch while idle.");
            default -> null;
        };
        if("eta".equals(kind)) return switch(r){
            case "STEADY_SAMPLE" -> new StateExplanation("ETA confidence: steady",
                    "The fastest and slowest recent samples are within a factor of two, so the range is meaningful.",
                    "Nothing.","Nothing — the ETA range is trustworthy for now.",
                    "ETA confidence dropping to Variable sample means throughput became uneven.");
            case "VARIABLE_SAMPLE" -> new StateExplanation("ETA confidence: variable",
                    "Recent throughput samples differ by more than a factor of two, so the range is wide on purpose.",
                    "Nothing.","Treat the ETA as a rough band, not a time.",
                    "Throughput ("+String.format(Locale.US,"%.2f",p.chunksPerSecond())+" chunks/s) settling down.");
            case "WARMING_UP" -> new StateExplanation("ETA confidence: warming up",
                    "Fewer than ten samples, or under ten seconds of history. No estimate is offered yet.",
                    "Enough completions to measure a rate.","Nothing — wait about ten seconds.",
                    "Handled ("+fmt(p.handled())+" / "+fmt(p.total())+") advancing.");
            case "NO_RECENT_COMPLETIONS" -> new StateExplanation("ETA confidence: no recent completions",
                    "Nothing has retired for at least 30 seconds, so no honest estimate exists.",
                    "A chunk to finish carving and retire.",
                    "This is the signature of a real stall. Check the Reason line, then Outstanding and Final-drain tickets. If it persists for minutes, the run is worth a log.",
                    "No progress for ("+secs(p.noProgressSeconds())+") must reset to zero.");
            case "FINAL_DRAIN" -> new StateExplanation("ETA confidence: final drain",
                    "Submission is complete, so remaining time depends on owned chunks retiring rather than on a feed rate.",
                    "Owned targets to finish.","Nothing.",
                    "Outstanding ("+fmt(p.outstanding())+") falling to zero.");
            case "THROTTLED" -> new StateExplanation("ETA confidence: throttled",
                    "Admission is held, so a projection from the current rate would be misleading.",
                    "The condition named on the Reason line.","Read the Reason line.",
                    "Admission rate returning above zero.");
            case "FINALIZING" -> new StateExplanation("ETA confidence: finalizing",
                    "All counted work is submitted and settled; the job is closing out.",
                    "Completion checks, including the post-release lighting integrity gate.",
                    "Nothing — completion is gated on those checks passing three consecutive clean ticks.",
                    "The job ending, or the action bar reporting remaining lighting entries.");
            case "UNAVAILABLE" -> new StateExplanation("ETA confidence: unavailable",
                    "There is no active job to estimate.","Nothing.","Nothing.","Nothing to watch while idle.");
            default -> null;
        };
        if(!"reason".equals(kind)) return null;

        // Every branch below mirrors a predicate in PregenManager. The thresholds are its constants.
        if(r.equals("Feeding within profile limits")) return new StateExplanation("Feeding within profile limits",
                "No hold is active. The controller is admitting at the rate the current profile allows.",
                "Nothing.","Nothing.","Admission rate "+p.rate()+" / "+p.rateCap()+" per tick.");
        if(r.equals("Starting")) return new StateExplanation("Starting",
                "The job has begun but has not made its first admission decision yet.",
                "The first controller tick.","Nothing — this clears within a second.",
                "Phase changing away from its initial value.");
        if(r.equals("Tick cadence limit")) return new StateExplanation("Tick cadence limit",
                "The server's tick interval ran long, so Ocean Canvas stopped admitting to give the tick back.",
                "Server tick cadence to recover.",
                "Nothing from Ocean Canvas. If this dominates a run, the load is coming from the server or other mods, not from admission depth.",
                "Tick work and Tick interval on this tab.");
        if(r.equals("Heap pressure")) return new StateExplanation("Heap pressure",
                "Heap use reached 82%, the hard pause fraction. Admission stops rather than risk an out-of-memory failure.",
                "Garbage collection to bring heap use back down.",
                "If this recurs constantly, raise the JVM heap for the instance. Ocean Canvas will not admit its way out of it.",
                "Heap ("+(p.heapMaxMiB()<=0?"—":(int)Math.round(p.heapFraction()*100)+"%")+") falling back under 82%.");
        if(r.equals("Hard queue limit")) return new StateExplanation("Hard queue limit",
                "The outstanding queue hit its absolute ceiling — not the adaptive target, the hard cap.",
                "Owned chunks to retire and free queue slots.",
                "Nothing. Admission resumes on its own as chunks retire.",
                "Outstanding ("+fmt(p.outstanding())+") falling.");
        if(r.equals("Neighbor-stall circuit breaker (backlog recovering)")) return new StateExplanation(
                "Neighbor-stall circuit breaker",
                "Too much of the queue was waiting on neighbouring chunks that were loaded but not yet ticking, so the inlet closed to let the backlog clear.",
                "The residual queue to drain, or its stale share to fall to about a third. It deliberately stays engaged until then rather than releasing early and rebuilding the same stall.",
                "Nothing. Releasing this early is exactly the sawtooth failure v210 fixed.",
                "Queued ("+fmt(p.queued())+") falling, then admission resuming.");
        if(reasonIs(r,"Cold-load debt hard hold")) return new StateExplanation("Cold-load debt hard hold",
                "At least one target chunk has been waiting on a load for 10 seconds or more. That single sick target closes the inlet even while others retire normally.",
                "That target's chunk load to complete.",
                "Nothing immediately. Persistent cold-load debt points at disk or chunk-generation cost, not at Ocean Canvas admission.",
                "No progress for ("+secs(p.noProgressSeconds())+") and Outstanding ("+fmt(p.outstanding())+").");
        if(reasonIs(r,"Cold-load debt cohort hold")) return new StateExplanation("Cold-load debt cohort hold",
                "Four or more targets are loading, most of them stale, and the oldest has waited 5 seconds or more.",
                "That cohort of loads to complete.",
                "Nothing. This is a leading indicator — it closes the inlet before the backlog becomes a stall.",
                "Outstanding ("+fmt(p.outstanding())+") falling.");
        if(reasonIs(r,"Proactive health hold")) return new StateExplanation("Proactive health hold",
                "The controller's health trajectory — queue age, stale ratio, tick work and heap together — predicts a stall, so it held admission before one formed.",
                "The trajectory to improve.",
                "Nothing. This firing is the system working as intended, not a fault.",
                "Throughput and No progress for, on this tab.");
        if(reasonIs(r,"Preemptive no-ready hold")) return new StateExplanation("Preemptive no-ready hold",
                "At least eight chunks are in the carve lane, none is loading, none is ready, and a fifth or more are stale waiting on neighbours. Admitting more would only deepen a frontier that is not moving.",
                "Stale frontier chunks to reach the ticking tier so they can carve.",
                "Nothing. If this state persists for minutes with zero throughput, that is the stall shape worth capturing a log for.",
                "No progress for ("+secs(p.noProgressSeconds())+"); it must reset to zero.");
        if(reasonIs(r,"I/O pressure governor")) return new StateExplanation("I/O pressure governor",
                "Storage-side FULL-load debt is elevated or stalled independently of CPU, heap, or tick cadence, so the inlet is capped or closed rather than deepening a disk-bound queue.",
                "Pending FULL chunk loads to complete and their age/backlog to fall.",
                "Nothing in Ocean Canvas. Persistent I/O pressure is evidence to inspect storage/world-generation latency rather than raise admission.",
                "Outstanding ("+fmt(p.outstanding())+") and the storage-pressure state in Health > Performance falling.");
        if(reasonIs(r,"Resource budget")) return new StateExplanation("Explicit resource budget",
                "The machine-budget layer capped or closed admission because CPU tick work, heap use, transient ticket ownership, or more than one of those resources reached its explicit budget. The exact limiter is preserved in the Reason text.",
                "The named resource to regain headroom. This layer can only reduce the rate learned by the adaptive controller; it never raises it or bypasses recovery gates.",
                "Do not raise chunks/tick to fight this state. Use the diagnostic bundle's Admission resource budget line to see CPU, heap, ticket headroom and the limiting resource.",
                "Admission returning above zero without the resource-budget reason recurring.");
        if(reasonIs(r,"Outstanding load backlog")) return new StateExplanation("Outstanding load backlog",
                "The number of chunks requested but not yet queued passed its pressure threshold — the greater of 8, or a third of the adaptive target.",
                "Those pending loads to arrive.","Nothing.",
                "Queued ("+fmt(p.queued())+") rising as loads land.");
        if(reasonIs(r,"Adaptive queue-depth limit")) return new StateExplanation("Adaptive queue-depth limit",
                "Outstanding work reached the depth the controller currently considers safe. This is the ordinary steady-state brake, not a fault.",
                "Chunks to retire below the target depth.","Nothing. Expect this to appear and clear repeatedly during a healthy run.",
                "Outstanding ("+fmt(p.outstanding())+") against the adaptive target.");
        if(reasonIs(r,"Startup canary")) return new StateExplanation("Startup canary",
                "Before ramping up, the job proves it can retire four chunks. If retirement is broken, this catches it at four chunks rather than at four thousand.",
                "Four successful retirements.","Nothing — it lasts seconds on a healthy run.",
                "Handled ("+fmt(p.handled())+") reaching the canary count.");
        if(reasonIs(r,"Recovery ramp")) return new StateExplanation("Recovery ramp",
                "Coming out of a stall, admission is paced to the rate chunks are actually retiring — fractionally, so it can represent one to three chunks a second rather than snapping back to a full tick budget.",
                "Sustained retirement before the rate is allowed to climb.",
                "Nothing. Ramping faster is what caused the repeated refill-and-stall loop this exists to prevent.",
                "Throughput ("+String.format(Locale.US,"%.2f",p.chunksPerSecond())+" chunks/s) rising steadily.");
        if(r.equals("Predictive stale-frontier throttle")) return new StateExplanation("Predictive stale-frontier throttle",
                "About a tenth of the carve lane is stale, so the admission cap was halved before any hard hold was needed.",
                "The stale share to fall.","Nothing.",
                "Admission rate "+p.rate()+" / "+p.rateCap()+" per tick.");
        if(r.equals("Wall-time retirement-paced admission")) return new StateExplanation("Retirement-paced admission",
                "Admission is being paced against measured retirement in real time rather than against a fixed per-tick budget.",
                "Nothing — this is a pacing mode, not a hold.","Nothing.",
                "Throughput and Admission rate moving together.");
        if(r.equals("Above target queue depth; reduced feed")) return new StateExplanation("Above target queue depth",
                "Queue depth is above target, so the feed is reduced rather than stopped.",
                "Depth to fall back to target.","Nothing.","Outstanding ("+fmt(p.outstanding())+").");
        if(reasonIs(r,"Lighting finalization emergency backlog")) return new StateExplanation("Lighting finalization emergency backlog",
                "The global light-finalization queue reached the emergency threshold at twice the normal high-water mark, so normal terrain admission is closed. During restart recovery, a bounded recovery-only inlet may remain open when that recovery work is required to release a lighting barrier.",
                "The rolling light finalizer to retire enough pending chunks to leave the emergency range, with only dependency-breaking restart recovery allowed through the inlet.",
                "Nothing. Normal admission remains closed; the recovery exception is capped and still obeys the existing heap/cadence/wall-time governor.",
                "The lighting-finalization count falling; terrain admission resumes first in bounded flow-control mode.");
        if(reasonIs(r,"Lighting finalization flow-control")) return new StateExplanation("Lighting finalization flow-control",
                "The light-finalization queue is above its normal high-water mark but below the emergency threshold. Instead of the old stop/start sawtooth, terrain remains capped at one chunk per tick so the resident frontier can keep feeding cheap finalizations.",
                "The rolling light finalizer to drain the queue back below its low-water mark.",
                "Nothing. This is a bounded pacing state, not a stall.",
                "Pending lighting falling while terrain continues at the one-chunk-per-tick cap.");
        if(reasonIs(r,"Lighting finalization backlog")) return new StateExplanation("Lighting finalization backlog",
                "Terrain admission paused because authored chunks waiting for authoritative light publication reached the bounded high-water mark.",
                "The rolling light finalizer to drain the backlog below its low-water mark.",
                "Nothing. This is the v253.61 safety valve that prevents a large Pregen from accumulating hundreds of thousands of unfinished light entries.",
                "The lighting-finalization count falling while terrain admission remains at zero.");
        if(reasonIs(r,"Bounded metadata replay through already light-certified Canvas chunks")) return new StateExplanation("Restart metadata replay",
                "After restart, Ocean Canvas is scanning previously visited coordinates from the beginning so any v253.48 physical-only chunks cannot be skipped as lighting-complete.",
                "The bounded metadata scan to reach the prior cursor. Fully light-certified chunks are not loaded or regenerated.",
                "Nothing. This is a non-destructive reconciliation pass.",
                "Handled progress advancing without a matching rise in outstanding chunk loads.");
        if(reasonIs(r,"Admission ownership precondition failed")
                || reasonIs(r,"Final-drain admission ownership precondition failed")) return new StateExplanation(
                "Ownership precondition failed",
                "A chunk could not be taken under Ocean Canvas ownership cleanly, so the cursor is held rather than advanced past it. Skipping it would leave a gap in the carve.",
                "That chunk to become ownable.",
                "Nothing. Holding the cursor is the safe behaviour; advancing past it is what would lose a chunk.",
                "Handled ("+fmt(p.handled())+" / "+fmt(p.total())+") resuming.");
        if(r.equals("Diagnosing stalled outstanding chunks")) return new StateExplanation("Diagnosing stalled chunks",
                "The controller detected outstanding chunks making no progress and is inspecting them before deciding what to do.",
                "The diagnostic pass to finish.","Nothing. If this is followed by repeated lease bursts, capture the log.",
                "The Reason line changing to a specific hold.");
        if(reasonIs(r,"Waiting for owned targets")) return new StateExplanation("Waiting for owned targets",
                "Every chunk has been submitted; the job is waiting on the ones it already owns. It is not complete and must not report completion yet.",
                "Owned targets to finish carving and release.","Nothing.",
                "Outstanding ("+fmt(p.outstanding())+") and Final-drain tickets ("+fmt(p.finalDrainTickets())+") falling to zero.");
        if(reasonIs(r,"Crash recovery rescan drain:")) return new StateExplanation("Crash recovery rescan drain",
                "Restart recovery still has chunks whose authoritative terrain/light state must be proven before ordinary completion can advance.",
                "The bounded recovery rescan to certify or repair those chunks.",
                "Nothing. This is fail-closed recovery debt, not new terrain work.",
                "The recovery-rescan count falling toward zero.");
        if(r.equals("Forever-world proactive continuity pulse: 1 NORMAL terrain target inside measured safe headroom")) return new StateExplanation("Proactive continuity pulse",
                "The controller admitted one normal terrain target because measured lighting headroom proved a single bounded pulse could not cross the hard ceiling.",
                "That one target to retire or publish lighting before another pulse is considered.",
                "Nothing. The pulse is deliberately one target and remains under the hard pressure guard.",
                "Lighting debt staying below the emergency ceiling while handled progress advances.");
        if(r.equals("Forever-world resume reserve: 1 NORMAL terrain target after 16 light publications/active retirements")) return new StateExplanation("Publication-backed resume reserve",
                "The controller earned one normal-terrain admission after enough lighting publications or active retirements demonstrated debt-negative forward progress.",
                "That reserved target to retire before more reserve credit is earned.",
                "Nothing. This is a bounded liveness escape, not unrestricted admission.",
                "Publication/retirement credit increasing while lighting pressure does not grow.");
        if(reasonIs(r,"Lighting recovery safety cap reached")) return new StateExplanation("Lighting recovery safety cap",
                "Total pending lighting recovery reached its absolute safety cap, so new admission is fail-closed until debt drains.",
                "The bounded light-recovery finalizer to reduce pending debt below the cap.",
                "Nothing. Raising the cap would trade liveness for unbounded memory/ticket pressure.",
                "Pending lighting and active recovery counts falling.");
        if(reasonIs(r,"Lighting repair safety cap reached")) return new StateExplanation("Lighting repair safety cap",
                "Pending light repair reached its absolute safety cap while bounded repair work is still draining.",
                "Existing repair/finalization work to retire below the cap.",
                "Nothing. The controller intentionally refuses to deepen repair debt here.",
                "Pending repair/finalization counts falling.");
        if(r.equals("Predictive light-headroom clamp before high-water overshoot")) return new StateExplanation("Predictive light-headroom clamp",
                "The next admission budget could overshoot the lighting high-water mark after worst-case fan-out, so the controller clamps before the overshoot occurs.",
                "Lighting finalization to create enough measured headroom for another safe admission.",
                "Nothing. This is a predictive safety brake, not a detected stall.",
                "Active lighting pressure moving farther below high-water.");
        if(reasonIs(r,"Recovery-heavy light pressure")) return new StateExplanation("Recovery-heavy light pressure",
                "Restart/recovery lighting debt dominates the active window, so admission is limited to narrowly bounded recovery or debt-negative continuity work.",
                "Recovery lighting debt to retire toward the low-water mark.",
                "Nothing. Normal terrain remains bounded until recovery debt is demonstrably shrinking.",
                "Active recovery lighting falling while publication/retirement credit rises.");
        if(reasonIs(r,"Restart recovery quarantine:")) return new StateExplanation("Restart recovery quarantine",
                "Some restart-recovery chunks still lack authoritative proof, so they remain quarantined and cannot be counted as complete.",
                "The quarantine probes/finalizer to produce authoritative proof or keep the debt visible.",
                "Nothing. Quarantine is intentionally uncertified completion debt.",
                "The quarantine count falling without ordinary admission being starved.");
        if(r.equals("Save & Quit / shutdown preemption")) return new StateExplanation("Save & Quit / shutdown preemption",
                "Shutdown preemption has closed normal admission so Ocean Canvas can release ownership and persist a resumable frontier promptly.",
                "The bounded shutdown handoff to finish.",
                "Nothing. This state exists to keep Save & Quit responsive and recovery-correct.",
                "Outstanding ownership and shutdown debt falling to zero.");
        return null;
    }

    /** Registers a click-to-explain marker beside a status value. */
    private void explainMark(GuiGraphicsExtractor g,int x,int y,String kind,String raw){
        if(raw==null||raw.isBlank()) return;
        text(g,"?",x,y,CY);
        final String key=kind+"|"+raw;
        hit(x-4,y-4,14,16,"Explain this state",()->explain=key);
    }

    private void drawExplain(GuiGraphicsExtractor g){
        int w=560,h=250,x=(DESIGN_W-w)/2,y=(DESIGN_H-h)/2-40;
        hit(0,0,DESIGN_W,DESIGN_H,null,()->explain=null);
        fill(g,x,y,w,h,0xF704070A); border(g,x,y,w,h,CY); blocker(x,y,w,h);
        int sep=explain.indexOf('|');
        String kind=sep<0?"":explain.substring(0,sep), raw=sep<0?explain:explain.substring(sep+1);
        var e=explanationFor(kind,raw);
        icon(g,"close",x+w-30,y+12,DIM);
        hit(x+w-38,y+6,34,30,"Close",()->explain=null);
        if(e==null){
            text(g,"NO EXPLANATION ON FILE",x+18,y+16,GOLD);
            int yy=y+50;
            text(g,"Ocean Canvas has no written explanation for this state:",x+18,yy,DIM); yy+=22;
            text(g,trim(raw,w-36),x+18,yy,BRIGHT); yy+=26;
            text(g,"That is reported rather than guessed at. A state the controller can emit",x+18,yy,DIM); yy+=18;
            text(g,"but this screen cannot explain is worth reporting — the raw text above is",x+18,yy,DIM); yy+=18;
            text(g,"exactly what the server sent.",x+18,yy,DIM);
            return;
        }
        text(g,"EXPLAIN THIS STATE",x+18,y+16,CY);
        int yy=y+46;
        text(g,trim(e.heading(),w-36),x+18,yy,BRIGHT); yy+=24;
        // OC-F212: for a hold, name who owns the condition and what specifically ends it.
        var tax="reason".equals(kind)?OceanCanvasDiagnosticModel.taxonomyFor(raw):null;
        if(tax!=null){
            text(g,trim("Owner: "+tax.owner()+"   ·   Resumes when: "+tax.resumeCondition(),w-36),x+18,yy,CY);
            yy+=22;
        }
        hline(g,x+18,yy-6,w-36);
        for(String[] row:new String[][]{{"What it means",e.meaning()},{"Waiting for",e.waitingFor()},
                {"What you can do",e.youCanDo()},{"What to watch",e.watch()}}){
            text(g,row[0],x+18,yy,DIM);
            yy+=16;
            for(String line:wrap(row[1],w-46)){ text(g,line,x+26,yy,0xFFD2D6D9); yy+=15; }
            yy+=7;
        }
    }

    /** Greedy word wrap against real measured font width; long words are hard-trimmed. */
    private List<String> wrap(String s,int maxWidth){
        var out=new ArrayList<String>();
        if(s==null||s.isBlank()) return out;
        StringBuilder line=new StringBuilder();
        for(String word:s.split(" ")){
            String candidate=line.isEmpty()?word:line+" "+word;
            if(font.width(candidate)<=maxWidth){ line.setLength(0); line.append(candidate); continue; }
            if(!line.isEmpty()){ out.add(line.toString()); line.setLength(0); }
            if(font.width(word)<=maxWidth) line.append(word); else out.add(trim(word,maxWidth));
            if(out.size()>=8) break;
        }
        if(!line.isEmpty()&&out.size()<8) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------- undo centre / reversibility (F056, F241)
    /**
     * How reversible an action actually is, stated before it runs.
     *
     * <p>Three tiers, each grounded in real code rather than optimism:</p>
     * <ul>
     *   <li>{@code PLAN_UNDO} — Plan geometry and property edits, covered by the server's
     *       {@code history_undo}/{@code history_redo} planning routes.</li>
     *   <li>{@code COMMAND_UNDO} — Rewipe only. {@code PregenManager#rewipe} is the sole caller of
     *       {@code OceanCanvasUndoManager#beginRecording}; Pregen, Expand and Restore record
     *       nothing. The recording is bounded and lossy in ways the label must state.</li>
     *   <li>{@code IRREVERSIBLE} — everything else destructive. Saying so is the feature.</li>
     * </ul>
     */
    private record Reversibility(String tier,String label,String detail,int color) { }

    private static final Reversibility REV_PLAN=new Reversibility("PLAN_UNDO","Reversible — Undo in Plans",
            "Plan edits are undone by the Plans panel's Undo, or Ctrl+Z on this screen.",GREEN);
    private static final Reversibility REV_REWIPE=new Reversibility("COMMAND_UNDO","Reversible by command, with limits",
            "Rewipe is the only world operation Ocean Canvas records for undo (/oceancanvas undo). "
            +"It restores block types and shapes only — not chest contents or sign text — is not recorded "
            +"at all above 200,000 changed blocks, and only the most recent few resets per player are kept.",GOLD);
    private static final Reversibility REV_NONE=new Reversibility("IRREVERSIBLE","Not reversible",
            "Ocean Canvas keeps no record that can put this back. A metadata snapshot is taken at the "
            +"start of destructive operations, but it restores Ocean Canvas records, not world blocks.",RED);

    /** Null when an action needs no reversibility statement (ordinary immediate metadata edits). */
    private Reversibility reversibilityOf(String actionKey){
        return switch(actionKey){
            case "rewipe" -> REV_REWIPE;
            case "restore","region_delete","project_delete","plan_clear","region_archive" -> REV_NONE;
            case "plan_edit" -> REV_PLAN;
            default -> null;
        };
    }

    /** Appends the reversibility statement to a control's tooltip so it is read before the click. */
    private String withReversibility(String tip,String actionKey){
        var r=reversibilityOf(actionKey);
        return r==null?tip:tip+"  ·  "+r.label();
    }

    private void planUndo(){ planning("history_undo","","",""); say("Undo requested for the current Plan edit"); }
    private void planRedo(){ planning("history_redo","","",""); say("Redo requested for the current Plan edit"); }

    private void drawUndoCentre(GuiGraphicsExtractor g,int x,int y){
        y=wbSection(g,x,y,"UNDO & REVERSIBILITY");
        y=wbLine(g,x,y,"Plan edits",REV_PLAN.label()+" — Ctrl+Z / Ctrl+Y, or the buttons below",REV_PLAN.color());
        y=wbLine(g,x,y,"Rewipe",REV_REWIPE.label()+" — /oceancanvas undo",REV_REWIPE.color());
        y=wbLine(g,x+12,y,"• Restores","Block types and shapes only — not chest contents or sign text",DIM);
        y=wbLine(g,x+12,y,"• Not recorded","Any single rewipe above 200,000 changed blocks",DIM);
        y=wbLine(g,x+12,y,"• Depth","Only the most recent resets per player are kept",DIM);
        y=wbLine(g,x,y,"Pregen · Expand · Restore","Not recorded for undo at all",RED);
        y=wbLine(g,x,y,"Delete region · project · clear plan",REV_NONE.label(),REV_NONE.color());
        y=wbLine(g,x,y,"Metadata snapshot","Taken at the start of every destructive operation",TEXT);
        y=wbLine(g,x+12,y,"• Restores","Ocean Canvas records, not world blocks",DIM);
        int by=y;
        wbButton(g,x,by,200,"Undo Plan Edit","Undo the most recent Plan edit in this session",CY,this::planUndo);
        wbButton(g,x+208,by,200,"Redo Plan Edit","Redo the most recently undone Plan edit",CY,this::planRedo);
    }

    private void drawHelp(GuiGraphicsExtractor g){
        String[][] rows={{"Command palette","Ctrl+K  /"},{"Coordinate navigation","Ctrl+K → X,Z"},{"Undo Plan edit","Ctrl+Z"},{"Redo Plan edit","Ctrl+Y"},
                {"Select tool","V"},{"Pan canvas","Space"},{"Shape region","R"},{"Draw path","P"},
                {"Place waypoint","W"},{"Toggle layers","L"},{"Toggle snapping","S"},{"Solo / focus view","F"},
                {"Cycle unit profile","U"},{"Change heatmap","H"},{"Activity center","Ctrl+B"},{"UX / layouts","Ctrl+J"},
                {"Start pregen","Ctrl+G"},{"Recentre on player","C"},{"Close panel / cancel","Esc"}};
        // Height follows the row count. It used to be a hand-tuned 309, which the tenth row
        // would have overflowed by 31px - the exact defect class v253.29 removed elsewhere.
        int x=DESIGN_W-82-290,y=66,w=290,h=48+rows.length*30;
        fill(g,x,y,w,h,0xFF04070A);border(g,x,y,w,h,CY);
        blocker(x,y,w,h);
        text(g,"SHORTCUTS",R(1082),80,CY);
        icon(g,"close",R(1329),76,DIM);
        hit(R(1323),70,36,30,"Close",()->overlay=null);
        int yy=106;
        for(String[] r:rows){ hline(g,R(1082),yy,262); text(g,r[0],R(1082),yy+10,0xFFC9C9C9); right(g,r[1],R(1344),yy+10,CY); yy+=30; }
    }
    private void drawToast(GuiGraphicsExtractor g){
        String s=trim(toast,DESIGN_W-200);
        int w=Math.max(120,font.width(s)+32);
        int x=DESIGN_W/2-w/2,y=DESIGN_H-47;
        fill(g,x,y,w,29,0xFF04090C);border(g,x,y,w,29,CY);center(g,s,x,y+10,w,CY);
    }
    /** Server-authored success/error text. Without this the UI can request work and show nothing. */
    private void drawServerFeedback(GuiGraphicsExtractor g){
        var fb=OceanCanvasZoneClientCache.feedback(6000);
        if(fb==null) return;
        String s=trim(fb.message(),DESIGN_W-260);
        int w=Math.max(140,font.width(s)+28), x=DESIGN_W/2-w/2, y=DESIGN_H-84;
        fill(g,x,y,w,27,0xF0000000); border(g,x,y,w,27,fb.error()?RED:GREEN);
        center(g,s,x,y+9,w,fb.error()?0xFFFF9A9A:0xFF9AE8C0);
    }

    // ---------------------------------------------------------------- inline editing
    private void beginEdit(String key,String initial){
        if(key!=null&&key.startsWith("region.vertex.")){
            String[] f=key.split("\\.");
            if(f.length==4){try{selectedRegionVertex=Math.max(0,Integer.parseInt(f[2]));regionVertexPage=selectedRegionVertex/REGION_VERTEX_ROWS;}catch(NumberFormatException ignored){}}
        }
        editKey=key; editBuf=initial==null?"":initial;
    }
    private void cancelEdit(){ editKey=null; editBuf=""; }
    private void commitEdit(){
        if(editKey==null) return;
        String key=editKey, value=editBuf.trim();
        cancelEdit();
        var z=region(); var pl=plan(); var pr=project();
        if(key.startsWith("region.vertex.")){
            if(z==null||regionOperationLocked(z)){if(z!=null)say(regionOperationLockTip(z));return;}
            String[] f=key.split("\\.");
            if(f.length!=4){say("Invalid vertex field");return;}
            int index,v;
            try{index=Integer.parseInt(f[2]);v=Integer.parseInt(value.replace(",",""));}
            catch(NumberFormatException e){say("Not a number: "+value);return;}
            ArrayList<Integer> verts=new ArrayList<>(regionDisplayVertices(z));
            int count=verts.size()/2;if(count<3||index<0||index>=count){say("That Region vertex is no longer available");return;}
            int at=index*2+("z".equals(f[3])?1:0);verts.set(at,v);selectedRegionVertex=index;regionVertexPage=index/REGION_VERTEX_ROWS;
            stageRegionPolygonGeometry(z.name(),verts);return;
        }
        switch(key){
            case "p3.map_title" -> {
                if(value.length()>80){ say("Map titles are limited to 80 characters"); return; }
                mapComposerTitle=value; say(value.isBlank()?"Map title reset to automatic":"Map title → "+value);
            }
            // Bounds mirror the server's own validation so a refusal is prevented, not reported.
            case "task.title" -> {
                if(selectedTask.isBlank()) return;
                if(value.isBlank()){ say("A task title cannot be blank"); return; }
                workspace("task_title",selectedTask,value,""); say("Renamed to "+value);
            }
            case "task.notes" -> {
                if(selectedTask.isBlank()) return;
                if(value.length()>512){ say("Task notes are limited to 512 characters"); return; }
                workspace("task_notes",selectedTask,value,""); say(value.isBlank()?"Notes cleared":"Notes updated");
            }
            case "task.weight" -> {
                if(selectedTask.isBlank()) return;
                double w;
                try{ w=Double.parseDouble(value); }catch(NumberFormatException e){ say("Not a number: "+value); return; }
                if(w<0.1D||w>100D){ say("Task weight must be between 0.1 and 100"); return; }
                workspace("task_weight",selectedTask,String.format(Locale.US,"%.2f",w),"");
                say("Weight → "+String.format(Locale.US,"%.1f",w));
            }
            case "geom.amount" -> {
                try{ geomAmount=Double.parseDouble(value); }catch(NumberFormatException e){ say("Not a number: "+value); }
            }
            case "geom.translate" -> {
                String[] f=value.split(",");
                if(f.length!=2){ say("Translate needs dx,dz — for example 128,-64"); return; }
                try{ Integer.parseInt(f[0].trim()); Integer.parseInt(f[1].trim()); }
                catch(NumberFormatException e){ say("Translate needs two whole numbers"); return; }
                geomTranslate=f[0].trim()+","+f[1].trim();
            }
            case "ref.name" -> {
                if(selectedReference.isBlank()) return;
                if(value.isBlank()||value.length()>64){ say("Reference names must be 1-64 characters"); return; }
                planning("reference_rename",selectedReference,value,""); say("Renamed to "+value);
            }
            case "ref.rotate" -> {
                if(selectedReference.isBlank()) return;
                double deg;
                try{ deg=Double.parseDouble(value); }catch(NumberFormatException e){ say("Not a number: "+value); return; }
                planning("reference_rotate",selectedReference,String.format(Locale.US,"%.2f",deg),"");
                say("Rotated to "+String.format(Locale.US,"%.1f",deg)+"°");
            }
            case "ref.transform" -> {
                if(selectedReference.isBlank()) return;
                String[] f=value.split(",");
                if(f.length!=4){ say("Bounds need minX,minZ,maxX,maxZ"); return; }
                int a0,a1,a2,a3;
                try{ a0=Integer.parseInt(f[0].trim()); a1=Integer.parseInt(f[1].trim());
                     a2=Integer.parseInt(f[2].trim()); a3=Integer.parseInt(f[3].trim()); }
                catch(NumberFormatException e){ say("Bounds need four whole numbers"); return; }
                if(a0==a2||a1==a3){ say("Reference bounds must have non-zero width and height"); return; }
                double rot=0D;
                for(var r:OceanCanvasZoneClientCache.planningReferences())
                    if(r.id().equals(selectedReference)) rot=r.rotation();
                planning("reference_transform",selectedReference,a0+","+a1+","+a2+","+a3,
                        String.format(Locale.US,"%.2f",rot));
                say("Bounds updated");
            }
            case "geom.name" -> {
                if(selectedPlanObject.isBlank()) return;
                if(value.isBlank()||value.length()>64){ say("Planning names must be 1-64 characters"); return; }
                planning("rename",selectedPlanObject,value,""); say("Renamed to "+value);
            }
            case "geom.width" -> {
                if(selectedPlanObject.isBlank()) return;
                double w;
                try{ w=Double.parseDouble(value); }catch(NumberFormatException e){ say("Not a number: "+value); return; }
                if(w<0D||w>10000D){ say("Width must be between 0 and 10000 blocks"); return; }
                planning("width",selectedPlanObject,String.format(Locale.US,"%.2f",w),"");
                say("Width → "+String.format(Locale.US,"%.1f",w));
            }
            case "task.check" -> {
                if(selectedTask.isBlank()) return;
                if(value.isBlank()){ say("Checklist text cannot be blank"); return; }
                workspace("task_check_add",selectedTask,value,""); say("Added: "+value);
            }
            case "region.name" -> {
                if(z==null||value.isBlank()||value.equalsIgnoreCase(z.name())) return;
                if(regionOperationLocked(z)){say(regionOperationLockTip(z));return;}
                send(new OceanCanvasZoneRenameRequestPayload(z.name(),value));
                selectedRegion=value; say("Renaming to "+value);
            }
            case "region.x1","region.z1","region.x2","region.z2" -> {
                if(z==null) return;
                if(regionOperationLocked(z)){say(regionOperationLockTip(z));return;}
                int v;
                try{ v=Integer.parseInt(value.replace(",","")); }catch(NumberFormatException e){ say("Not a number: "+value); return; }
                int[] b=regionDisplayBounds(z);int nx1=b[0],nz1=b[1],nx2=b[2],nz2=b[3];
                switch(key){ case "region.x1"->nx1=v; case "region.z1"->nz1=v; case "region.x2"->nx2=v; default->nz2=v; }
                if(nx2<=nx1||nz2<=nz1){ say("X2/Z2 must stay greater than X1/Z1"); return; }
                stageRegionRectangleGeometry(z.name(),nx1,nz1,nx2,nz2);
            }
            case "waypoint.name" -> {
                var wp=waypoint(); if(wp==null) return;
                if(value.isBlank()||value.length()>64){ say("Waypoint names must be 1-64 characters"); return; }
                workspace("atlas_rename",wp.id(),value,""); say("Renamed to "+value);
            }
            case "waypoint.x","waypoint.z" -> {
                var wp=waypoint(); if(wp==null) return;
                int v;
                try{v=Integer.parseInt(value.replace(",",""));}catch(NumberFormatException e){say("Not a number: "+value);return;}
                int nx=key.endsWith(".x")?v:wp.x(), nz=key.endsWith(".x")?wp.z():v;
                workspace("atlas_move",wp.id(),nx+","+nz,""); say("Moving waypoint to "+nx+", "+nz);
            }
            case "region.biome" -> {
                if(z==null) return;
                send(new OceanCanvasZoneSetBiomeRequestPayload(z.name(),value));
                say(value.isBlank()?"Biome override cleared":"Biome → "+prettyId(value));
            }
            case "plan.name" -> {
                if(pl==null||value.isBlank()) return;
                planning("group_update",pl.id(),value,(pl.parentId()==null?"":pl.parentId())+"\t"+pl.opacity()+"\t"+pl.category()+"\t"+pl.drawOrder());
                say("Renamed layer to "+value);
            }
            case "plan.add" -> {
                if(value.isBlank()) return;
                planning("group_add","",value,"");
                say("Created plan layer "+value);
            }
            case "plan.bookmark_add" -> {
                if(value.isBlank()||value.length()>96){say("View names must be 1-96 characters");return;}
                planning("bookmark_add",selectedPlanObject,value,(int)Math.round(mapViewX)+","+(int)Math.round(mapViewZ)+","+String.format(Locale.US,"%.4f",mapBlocksPerPixel));
                say("Saved map view "+value);
            }
            case "plan.preset_add" -> {
                if(value.isBlank()||value.length()>96){say("Preset names must be 1-96 characters");return;}
                planning("preset_add","",value,"");say("Saved visibility preset "+value);
            }
            case "plan.vertex.x", "plan.vertex.z" -> {
                var obj=planObject();if(obj==null)return;int v;try{v=Integer.parseInt(value.replace(",",""));}catch(NumberFormatException e){say("Not a number: "+value);return;}
                ArrayList<Integer> pts=new ArrayList<>(planningVertexList(obj.points()));int n=pts.size()/2;if(n==0)return;selectedPlanVertex=Math.floorMod(selectedPlanVertex,n);int at=selectedPlanVertex*2+(key.endsWith(".z")?1:0);pts.set(at,v);
                planning("points",obj.id(),planningPoints(pts,planningClosed(obj.points())),"");say("Plan vertex "+(selectedPlanVertex+1)+" updated");
            }
            case "project.name" -> {
                if(pr==null||value.isBlank()) return;
                workspace("project_name",pr.id(),value,"");
                say("Renamed project to "+value);
            }
            case "project.notes" -> {
                if(pr==null) return;
                workspace("project_notes",pr.id(),value,"");
                say("Notes saved");
            }
            case "project.add" -> {
                if(value.isBlank()) return;
                workspace("project_add","",value,region()==null?"":region().name());
                say("Created project "+value);
            }
            case "project.phase.rename" -> {
                if(pr==null||selectedPhase.isBlank()) return;
                if(value.isBlank()||value.length()>96){say("Phase name must be 1-96 characters");return;}
                workspace("project_phase_rename",pr.id(),selectedPhase,value);say("Phase renamed");
            }
            case "project.milestone.rename" -> {
                if(pr==null||selectedMilestone.isBlank()) return;
                OceanCanvasZoneClientCache.WorkspaceMilestone m=null;for(var q:OceanCanvasZoneClientCache.workspaceMilestones(pr))if(q.id().equals(selectedMilestone))m=q;
                if(m==null)return;if(value.isBlank()||value.length()>96){say("Milestone name must be 1-96 characters");return;}
                String spec="NAME="+value+"|PHASE="+(m.phaseId()==null?"":m.phaseId())+"|TASK="+(m.taskId()==null?"":m.taskId())+"|NOTES="+(m.notes()==null?"":m.notes())+"|PROGRESS="+m.progress()+"|TARGET="+(m.targetDate()==null?"":m.targetDate());
                workspace("project_milestone_edit",pr.id(),selectedMilestone,spec);say("Milestone renamed");
            }
            case "project.phase_add" -> {
                if(pr==null||value.isBlank()) return;
                workspace("project_phase_add",pr.id(),value,"");
                say("Added phase "+value);
            }
            case "project.milestone_add" -> {
                if(pr==null||value.isBlank()) return;
                workspace("project_milestone_create",pr.id(),value,"");
                say("Added milestone "+value);
            }
            case "project.blocker_add" -> {
                if(pr==null||value.isBlank()) return;
                workspace("project_blocker_add",pr.id(),value,"");
                say("Added blocker");
            }
            case "workbench.session_note" -> {
                workspace("session_note","",value,"");
                say("Session note saved");
            }
            case "region.meta.notes" -> {
                if(z==null)return;if(value.length()>512){say("Region notes are limited to 512 characters");return;}
                projectMeta("notes",value);say(value.isBlank()?"Region notes cleared":"Region notes saved");
            }
            case "region.template_save" -> {
                if(z==null)return;if(value.length()>64){say("Template names are limited to 64 characters");return;}
                projectMeta("template_save",value);say("Region template saved");
            }
            case "ref.registration" -> {
                if(selectedReference.isBlank())return;var ref=OceanCanvasZoneClientCache.planningReferences().stream().filter(r->r.id().equals(selectedReference)).findFirst().orElse(null);if(ref==null)return;if(ref.locked()){say("Unlock the reference layer before registering it");return;}
                int pairs=0;try{for(String raw:value.split(";")){String[] f=raw.split(",",-1);if(f.length!=4)throw new IllegalArgumentException();Double.parseDouble(f[0].trim());Double.parseDouble(f[1].trim());Integer.parseInt(f[2].trim());Integer.parseInt(f[3].trim());pairs++;}}catch(RuntimeException ex){say("Registration needs imageX,imageY,worldX,worldZ pairs separated by semicolons");return;}
                if(pairs<2||pairs>3){say("Reference registration needs 2 or 3 control-point pairs");return;}
                String transform=ref.minX()+","+ref.minZ()+","+ref.maxX()+","+ref.maxZ()+","+String.format(Locale.US,"%.4f",ref.rotation());planning("reference_registration",selectedReference,value,transform);say("Reference registration saved");
            }
            case "project.import_file" -> {
                if(value.isBlank()){say("Project package filename cannot be blank");return;}projectImportFile=value.endsWith(".oceanproject")?value:value+".oceanproject";say("Project inbox file → "+projectImportFile);
            }
            case "terrain.revision_spec" -> {
                String[] f=value.split(",",-1);if(f.length!=5){say("Revision metadata needs stage,file,width,height,bpp");return;}try{if(Integer.parseInt(f[2].trim())<1||Integer.parseInt(f[3].trim())<1||Double.parseDouble(f[4].trim())<=0)throw new IllegalArgumentException();}catch(RuntimeException ex){say("Revision width/height/bpp must be positive numbers");return;}terrainRevisionSpec=value;say("Revision metadata staged");
            }
            case "terrain.heightmap_spec" -> {
                String[] f=value.split(",",-1);if(f.length!=11){say("Heightmap metadata needs 11 comma-separated fields");return;}if(!f[5].trim().matches("[0-9a-fA-F]{64}")){say("Heightmap SHA-256 must be exactly 64 hex characters");return;}try{int w=Integer.parseInt(f[2].trim()),h=Integer.parseInt(f[3].trim()),bits=Integer.parseInt(f[6].trim()),minY=Integer.parseInt(f[9].trim()),maxY=Integer.parseInt(f[10].trim());double bpp=Double.parseDouble(f[4].trim());if(w<1||h<1||bits<1||bits>64||bpp<=0||minY>=maxY)throw new IllegalArgumentException();}catch(RuntimeException ex){say("Heightmap dimensions/bits/bpp/Y range are invalid");return;}terrainHeightmapSpec=value;say("Heightmap metadata staged");
            }
            case "terrain.placement_spec" -> {terrainPlacementSpec=value;say("Terrain placement metadata staged");}
            case "terrain.review_spec" -> {
                String[] f=value.split(",",-1);if(f.length!=7){say("Review checkpoint needs stage,file,sha256,loaded,missing,mean,max");return;}if(!f[2].trim().isBlank()&&!f[2].trim().matches("[0-9a-fA-F]{64}")){say("Checkpoint SHA-256 must be blank or 64 hex characters");return;}try{Integer.parseInt(f[3].trim());Integer.parseInt(f[4].trim());Double.parseDouble(f[5].trim());Double.parseDouble(f[6].trim());}catch(RuntimeException ex){say("Checkpoint loaded/missing/mean/max values are invalid");return;}terrainReviewSpec=value;say("Review checkpoint metadata staged");
            }
            case "knowledge.claim_key" -> {if(value.isBlank()){say("Claim key cannot be blank");return;}knowledgeClaimKey=value;say("Claim key staged");}
            case "knowledge.claim_value" -> {knowledgeClaimValue=value;say("Claim value staged");}
            case "knowledge.authorship_source" -> {if(value.length()>256){say("Authorship source is limited to 256 characters");return;}knowledgeAuthorshipSource=value;say("Authorship source staged");}
            case "p1.recipe_spec" -> {if(value.isBlank()||!value.contains("|")){say("Recipe format is Name|STEP>STEP");return;}p1RecipeSpec=value;say("Maintenance recipe staged");}
            case "p1.target_y" -> {try{p1TargetY=Integer.parseInt(value);}catch(NumberFormatException ex){say("Target Y must be a whole number");return;}if(p1TargetY<-2048||p1TargetY>2048){say("Target Y is outside the supported planning range");return;}say("Terraform target Y → "+p1TargetY);}
            case "p5.import_file" -> {if(value.isBlank()){say("Import filename cannot be blank");return;}p5ImportFile=value;say("P5 import inbox file staged");}
            case "p5.import_meta" -> {if(value.split("\\|",-1).length!=9){say("Import metadata needs 9 pipe-separated fields");return;}p5ImportMeta=value;say("P5 quarantine metadata staged");}
            case "p5.transform_name" -> {if(value.isBlank()||value.length()>96){say("Transform name must be 1-96 characters");return;}p5TransformName=value;}
            case "p5.transform_spec" -> {if(value.split("\\|",-1).length!=11){say("Transform needs 11 pipe-separated fields");return;}p5TransformSpec=value;say("P5 transform staged");}
            case "p5.recipe_name" -> {if(value.isBlank()||value.length()>96){say("Recipe name must be 1-96 characters");return;}p5RecipeName=value;}
            case "p5.recipe_spec" -> {if(value.split("\\|",-1).length!=8){say("Recipe needs 8 pipe-separated fields");return;}p5RecipeSpec=value;say("P5 export recipe staged");}
            case "p5.placement_name" -> {if(value.isBlank()||value.length()>96){say("Placement name must be 1-96 characters");return;}p5PlacementName=value;}
            case "p5.placement_spec" -> {if(value.split("\\|",-1).length!=13){say("Placement needs 13 pipe-separated fields");return;}p5PlacementSpec=value;say("P5 placement metadata staged");}
            case "p5.marker_name" -> {if(value.isBlank()||value.length()>96){say("Marker schema name must be 1-96 characters");return;}p5MarkerName=value;}
            case "p5.marker_spec" -> {if(value.split("\\|",-1).length!=5){say("Marker schema needs 5 pipe-separated fields");return;}p5MarkerSpec=value;say("P5 marker schema staged");}
            case "program.input" -> {if(value.length()>2048){say("Program input is limited to 2048 characters");return;}programInput=value;say(value.isBlank()?"Program input cleared":"Program input updated");}
            case "p4.draft" -> {if(value.length()>1024){say("P4 notes are limited to 1024 characters");return;}p4DraftText=value;say(value.isBlank()?"P4 draft cleared":"P4 draft updated");}
            case "p4.intent" -> {if(value.isBlank()||value.length()>128){say("Terrain intent must be 1-128 characters");return;}p4TerrainIntent=value;say("Terrain intent → "+value);}
            case "p4.motif" -> {if(value.isBlank()||value.length()>96){say("Motif name must be 1-96 characters");return;}p4MotifName=value;say("Motif → "+value);}
            default -> { }
        }
    }

    // ---------------------------------------------------------------- arbitrary geometry helpers
    // Shared with the offline boundary fuzz harness; this screen must never carry a second
    // copy of polygon→chunk math that could diverge from what we regression-test.
    private static long packChunk(int x,int z){return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.packChunk(x,z);}
    private static List<Integer> cleanVertices(List<Integer> raw){return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.cleanVertices(raw);}
    private static boolean pointInPolygon(double x,double z,List<Integer> v){return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.pointInPolygon(x,z,v);}
    private static boolean polygonIntersectsChunk(List<Integer> v,int cx,int cz){return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonIntersectsChunk(v,cx,cz);}
    private static List<Long> polygonChunks(List<Integer> raw){return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonChunks(raw);}
    private static String rectGeometryArgument(int x1,int z1,int x2,int z2){return "RECT:"+x1+","+z1+","+x2+","+z2;}
    private static String polygonGeometryArgument(List<Integer> v){StringBuilder b=new StringBuilder("POLY:REPLACE:");for(int i=0;i+1<v.size();i+=2){if(i>0)b.append(';');b.append(v.get(i)).append(',').append(v.get(i+1));}return b.toString();}
    private boolean sendResizePreviewed(String name,int x1,int z1,int x2,int z2){
        stageRegionRectangleGeometry(name,x1,z1,x2,z2);return true;
    }
    private boolean sendPolygonShape(String name,List<Integer> raw){return sendPolygonShape(name,raw,false);}
    private boolean sendPolygonShape(String name,List<Integer> raw,boolean newDraft){
        List<Integer> v=cleanVertices(raw);if(v.size()<6){say("A region polygon needs at least 3 vertices");return false;}
        if(newDraft){send(new OceanCanvasZoneShapeRequestPayload(name,"REPLACE",List.of(),v,""));return true;}
        stageRegionPolygonGeometry(name,v);return true;
    }
    private static List<Integer> planningVertexList(String packed){
        if(packed==null||packed.isBlank())return List.of();ArrayList<Integer> out=new ArrayList<>();
        for(String q:packed.split(";")){String[] a=q.trim().split(",",-1);if(a.length<2)continue;try{int x=(int)Math.round(Double.parseDouble(a[0])),z=(int)Math.round(Double.parseDouble(a[1]));if(out.size()>=2&&out.get(out.size()-2)==x&&out.get(out.size()-1)==z)continue;out.add(x);out.add(z);}catch(NumberFormatException ignored){}}
        if(out.size()>=4&&out.get(0).equals(out.get(out.size()-2))&&out.get(1).equals(out.get(out.size()-1))){out.remove(out.size()-1);out.remove(out.size()-1);}return List.copyOf(out);
    }
    private static boolean planningClosed(String packed){
        if(packed==null)return false;String[] q=packed.split(";");if(q.length<3)return false;return q[0].trim().equals(q[q.length-1].trim());
    }
    private String planningPoints(List<Integer> raw,boolean close){
        List<Integer> v=cleanVertices(raw);StringBuilder b=new StringBuilder();for(int i=0;i<v.size();i+=2){if(b.length()>0)b.append(';');b.append(v.get(i)).append(',').append(v.get(i+1));}
        if(close&&v.size()>=6)b.append(';').append(v.get(0)).append(',').append(v.get(1));return b.toString();
    }
    private void cancelGesture(){gestureVertices.clear();gestureKind=null;}
    private void addGestureVertex(double mx,double my,String kind){
        int x=snapWorld(mapWorldX(mx)),z=snapWorld(mapWorldZ(my));if(!kind.equals(gestureKind)){cancelGesture();gestureKind=kind;}
        if(gestureVertices.size()>=2&&gestureVertices.get(gestureVertices.size()-2)==x&&gestureVertices.get(gestureVertices.size()-1)==z)return;
        if(gestureVertices.size()>=512*2){say("Geometry is limited to 512 vertices");return;}gestureVertices.add(x);gestureVertices.add(z);
    }
    private void commitGesture(){
        if(gestureKind==null)return;List<Integer> v=cleanVertices(gestureVertices);String kind=gestureKind;cancelGesture();
        if(kind.equals("region_shape")){
            if(v.size()<6){say("A region shape needs at least 3 vertices");return;}int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;for(int i=0;i<v.size();i+=2){minX=Math.min(minX,v.get(i));maxX=Math.max(maxX,v.get(i));minZ=Math.min(minZ,v.get(i+1));maxZ=Math.max(maxZ,v.get(i+1));}
            String name=createRegion(minX,minZ,maxX,maxZ);sendPolygonShape(name,v,true);selectedRegionVertex=0;panelOpen=true;collapsed=false;activeTool.put("REGIONS","Select");
            say("Created polygon region "+name+" with "+(v.size()/2)+" vertices — review rules, then Pregen & Protect");return;
        }
        boolean shape=kind.endsWith("shape");int min=shape?3:2;if(v.size()/2<min){say((shape?"Shape":"Path")+" needs at least "+min+" vertices");return;}
        String pts=planningPoints(v,shape);String type=shape?"FREEFORM_AREA":"PATH";planning("create","",type,pts);
        if(!selectedPlan.isBlank())pendingLink=new PendingLink("plan_group",pts,selectedPlan,System.currentTimeMillis()+8000L);
        say("Created planning "+(shape?"shape":"path")+(selectedPlan.isBlank()?"":" in the selected layer"));
    }
    private static double segmentDistanceSq(double px,double pz,double ax,double az,double bx,double bz){
        double dx=bx-ax,dz=bz-az;if(dx==0&&dz==0){dx=px-ax;dz=pz-az;return dx*dx+dz*dz;}double t=((px-ax)*dx+(pz-az)*dz)/(dx*dx+dz*dz);t=Math.max(0,Math.min(1,t));double x=ax+t*dx,z=az+t*dz;dx=px-x;dz=pz-z;return dx*dx+dz*dz;
    }
    private void insertRegionVertex(double mx,double my){
        var z=region();if(z==null){say("Select a region first");return;}if(regionOperationLocked(z)){say(regionOperationLockTip(z));return;}List<Integer> v=new ArrayList<>();List<Integer> shown=regionDisplayVertices(z);if(shown.size()>=6)v.addAll(shown);else{int[] b=regionDisplayBounds(z);v.add(b[0]);v.add(b[1]);v.add(b[2]);v.add(b[1]);v.add(b[2]);v.add(b[3]);v.add(b[0]);v.add(b[3]);}
        int x=snapWorld(mapWorldX(mx)),zz=snapWorld(mapWorldZ(my)),n=v.size()/2,best=0;double bestD=Double.POSITIVE_INFINITY;for(int i=0;i<n;i++){int j=(i+1)%n;double d=segmentDistanceSq(x,zz,v.get(i*2),v.get(i*2+1),v.get(j*2),v.get(j*2+1));if(d<bestD){bestD=d;best=i;}}
        int insert=(best+1)*2;v.add(insert,x);v.add(insert+1,zz);selectedRegionVertex=best+1;regionVertexPage=selectedRegionVertex/REGION_VERTEX_ROWS;if(sendPolygonShape(z.name(),v))say("Staged vertex "+(best+2)+" in "+z.name());
    }
    private void deleteSelectedRegionVertex(){
        var z=region();List<Integer> shown=z==null?List.of():regionDisplayVertices(z);if(z==null||shown.size()<6){say("Select a polygon Region first");return;}if(regionOperationLocked(z)){say(regionOperationLockTip(z));return;}ArrayList<Integer> v=new ArrayList<>(shown);int n=v.size()/2;if(n<=3){say("A Region polygon needs at least three vertices");return;}selectedRegionVertex=Math.floorMod(selectedRegionVertex,n);int at=selectedRegionVertex*2;v.remove(at+1);v.remove(at);selectedRegionVertex=Math.floorMod(selectedRegionVertex,n-1);regionVertexPage=selectedRegionVertex/REGION_VERTEX_ROWS;if(sendPolygonShape(z.name(),v))say("Staged Region vertex deletion");
    }

    // ---------------------------------------------------------------- region geometry drag
    private int polygonVertexAt(double mx,double my){
        var z=region();if(z==null)return -1;List<Integer> v=regionDisplayVertices(z);if(v.size()<6)return -1;
        for(int i=0;i+1<v.size();i+=2){int sx=mapScreenX(v.get(i)),sy=mapScreenY(v.get(i+1));if(hitBox(mx,my,sx-7,sy-7,14,14))return i/2;}return -1;
    }
    private String regionHandleAt(double mx,double my){
        var z=region(); if(z==null) return null;
        if(regionDisplayVertices(z).size()>=6) return null;
        int[] b=regionDisplayBounds(z);
        int L=mapScreenX(b[0]),T=mapScreenY(b[1]),Rr=mapScreenX(b[2]),Bb=mapScreenY(b[3]);
        int W=Rr-L,H=Bb-T; if(W<=0||H<=0) return null;
        String[] n={"nw","n","ne","e","se","s","sw","w"};
        double[][] q={{0,0},{.5,0},{1,0},{1,.5},{1,1},{.5,1},{0,1},{0,.5}};
        for(int i=0;i<n.length;i++){
            int hx=(int)Math.round(L+W*q[i][0])-6,hy=(int)Math.round(T+H*q[i][1])-6;
            if(hitBox(mx,my,hx,hy,12,12)) return n[i];
        }
        if(hitBox(mx,my,L,T,W,H)) return "move";
        return null;
    }
    private int snapWorld(double v){int step=Math.max(1,snapStep);return snap?(int)Math.round(v/(double)step)*step:(int)Math.round(v);}
    private void commitGeometry(){
        var z=region();
        if(z==null||!geometryLive){ geometryLive=false; return; }
        int nx1=Math.min(gx1,gx2),nx2=Math.max(gx1,gx2),nz1=Math.min(gz1,gz2),nz2=Math.max(gz1,gz2);
        geometryLive=false;
        if(nx2-nx1<16||nz2-nz1<16){ say("Region must stay at least one chunk across"); return; }
        int[] b=regionDisplayBounds(z);
        if(nx1==b[0]&&nz1==b[1]&&nx2==b[2]&&nz2==b[3]) return;
        if(sendResizePreviewed(z.name(),nx1,nz1,nx2,nz2))say("Staged resize for "+z.name()+" to "+fmt(nx2-nx1)+" × "+fmt(nz2-nz1));
    }

    // ---------------------------------------------------------------- input
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        double mx=ux(event.x()),my=uy(event.y());
        if(event.button()!=0) return super.mouseClicked(event,doubleClick);

        if(uiMinimized){
            if(hitBox(mx,my,DESIGN_W-62,14,44,44)){uiMinimized=false;say("UI restored");}
            return true;
        }
        if(beforeAfterEnabled){int divider=(int)Math.round(DESIGN_W*Math.max(.05,Math.min(.95,beforeAfterDivider)));if(Math.abs(mx-divider)<=8){beforeAfterDragging=true;return true;}}
        // The opacity slider is a drag surface, so it is resolved before the generic hit list.
        if(tab.equals("PLANS")&&panelOpen&&Boolean.TRUE.equals(panelVisible.get("right"))
                &&sliderGroup!=null&&hitBox(mx,my,sliderRectX,sliderRectY,sliderRectW,sliderRectH)){
            applySliderAt(mx); return true;
        }
        Hit h=topHit(mx,my);
        if(h!=null){
            // An open inline field is abandoned by clicking anything else. Actions that want a
            // field call beginEdit() themselves, so this cannot cancel an edit the click intended.
            if(editKey!=null) cancelEdit();
            if(h.action()!=null) h.action().run();
            return true;
        }
        // Nothing in the chrome claimed the click, so it belongs to the map.
        if(editKey!=null) cancelEdit();
        if(inspectMode){
            int ix=(int)Math.round(mapWorldX(mx)),iz=(int)Math.round(mapWorldZ(my));
            send(new OceanCanvasInspectRequestPayload(ix,iz));
            say("Inspecting "+ix+", "+iz);
            return true;
        }
        String tool=activeTool.getOrDefault(tab,"Select");
        if("Pan".equals(tool)){
            mapPanning=true;mapPanStartX=mx;mapPanStartY=my;mapPanViewX=mapViewX;mapPanViewZ=mapViewZ;return true;
        }
        if("Waypoint".equals(tool)&&(tab.equals("REGIONS")||tab.equals("PLANS")||tab.equals("LAYERS"))){
            workspace("atlas_add","",nextWaypointName(),(int)Math.round(mapWorldX(mx))+","+(int)Math.round(mapWorldZ(my)));
            say("Waypoint placed — open Waypoints in Layers to rename, colour or delete it"); return true;
        }
        if(tab.equals("REGIONS")&&"Shape".equals(tool)){
            addGestureVertex(mx,my,"region_shape"); if(doubleClick)commitGesture(); else say((gestureVertices.size()/2)+" region vertices — double-click or Enter to finish"); return true;
        }
        if(tab.equals("REGIONS")&&"Vertex".equals(tool)){
            var rz=region();if(rz!=null&&regionOperationLocked(rz)){say(regionOperationLockTip(rz));return true;}
            int vi=polygonVertexAt(mx,my);
            if(vi>=0){var z=region();selectedRegionVertex=vi;regionVertexPage=vi/REGION_VERTEX_ROWS;polygonDragIndex=vi;polygonEditVertices=new ArrayList<>(regionDisplayVertices(z));return true;}
            insertRegionVertex(mx,my);return true;
        }
        if((tab.equals("REGIONS")||tab.equals("PLANS"))&&"Path".equals(tool)){
            addGestureVertex(mx,my,tab.equals("REGIONS")?"region_path":"plan_path"); if(doubleClick)commitGesture(); else say((gestureVertices.size()/2)+" path vertices — double-click or Enter to finish"); return true;
        }
        if(tab.equals("PLANS")&&"Shape".equals(tool)){
            if(plan()==null){say("Create a plan layer first");return true;}
            addGestureVertex(mx,my,"plan_shape"); if(doubleClick)commitGesture(); else say((gestureVertices.size()/2)+" shape vertices — double-click or Enter to finish"); return true;
        }
        if(tab.equals("REGIONS")){
            String hm=regionHandleAt(mx,my);var z=region();
            if(hm!=null&&z!=null){if(regionOperationLocked(z)){say(regionOperationLockTip(z));return true;}int[] b=regionDisplayBounds(z);dragMode=hm;dragStartX=mx;dragStartY=my;gx1=b[0];gz1=b[1];gx2=b[2];gz2=b[3];geometryLive=true;return true;}
        }
        return super.mouseClicked(event,doubleClick);
    }
    private void applySliderAt(double mx){
        if(sliderGroup==null) return;
        double frac=Math.max(0.05,Math.min(1.0,(mx-sliderRectX)/(double)sliderRectW));
        planning("group_opacity",sliderGroup,String.format(Locale.US,"%.3f",frac),"");
        say("Opacity "+(int)Math.round(frac*100)+"%");
    }

    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(event.button()!=0) return super.mouseDragged(event,dx,dy);
        double mx=ux(event.x()),my=uy(event.y());
        if(beforeAfterDragging){beforeAfterDivider=Math.max(.05,Math.min(.95,mx/(double)DESIGN_W));return true;}
        if(mapPanning){
            mapViewX=mapPanViewX-(mx-mapPanStartX)*mapBlocksPerPixel;
            mapViewZ=mapPanViewZ-(my-mapPanStartY)*mapBlocksPerPixel;
            return true;
        }
        if(drawing){ drawBX=mapWorldX(mx); drawBZ=mapWorldZ(my); return true; }
        if(planDragIndex>=0&&!planEditVertices.isEmpty()){
            ArrayList<Integer> v=new ArrayList<>(planEditVertices);int at=planDragIndex*2;if(at+1<v.size()){v.set(at,snapWorld(mapWorldX(mx)));v.set(at+1,snapWorld(mapWorldZ(my)));planEditVertices=List.copyOf(v);}return true;
        }
        if(polygonDragIndex>=0&&!polygonEditVertices.isEmpty()){
            ArrayList<Integer> v=new ArrayList<>(polygonEditVertices);int at=polygonDragIndex*2;if(at+1<v.size()){v.set(at,snapWorld(mapWorldX(mx)));v.set(at+1,snapWorld(mapWorldZ(my)));polygonEditVertices=List.copyOf(v);}return true;
        }
        if(dragMode!=null){
            var z=region(); if(z==null){dragMode=null;geometryLive=false;return true;}
            int wx=snapWorld((mx-dragStartX)*mapBlocksPerPixel), wz=snapWorld((my-dragStartY)*mapBlocksPerPixel);
            int[] b=regionDisplayBounds(z);gx1=b[0];gz1=b[1];gx2=b[2];gz2=b[3];
            if(dragMode.equals("move")){ gx1+=wx;gx2+=wx;gz1+=wz;gz2+=wz; }
            else{
                if(dragMode.contains("w")) gx1=b[0]+wx;
                if(dragMode.contains("e")) gx2=b[2]+wx;
                if(dragMode.contains("n")) gz1=b[1]+wz;
                if(dragMode.contains("s")) gz2=b[3]+wz;
            }
            return true;
        }
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(event.button()!=0) return super.mouseReleased(event);
        if(beforeAfterDragging){beforeAfterDragging=false;return true;}
        if(mapPanning){ mapPanning=false; return true; }
        if(planDragIndex>=0){
            String id=selectedPlanObject;List<Integer> v=planEditVertices;boolean closed=planEditClosed;planDragIndex=-1;planEditVertices=List.of();if(!id.isBlank()&&!v.isEmpty()){planning("points",id,planningPoints(v,closed),"");say("Moved plan vertex "+(selectedPlanVertex+1));}return true;
        }
        if(polygonDragIndex>=0){
            var z=region();List<Integer> v=polygonEditVertices;polygonDragIndex=-1;polygonEditVertices=List.of();if(z!=null&&!v.isEmpty()&&sendPolygonShape(z.name(),v)){say("Staged vertex "+(selectedRegionVertex+1)+" move");}return true;
        }
        if(drawing){
            drawing=false;
            int minX=(int)Math.floor(Math.min(drawAX,drawBX)),maxX=(int)Math.floor(Math.max(drawAX,drawBX));
            int minZ=(int)Math.floor(Math.min(drawAZ,drawBZ)),maxZ=(int)Math.floor(Math.max(drawAZ,drawBZ));
            if(maxX-minX<16||maxZ-minZ<16){ say("Drag a larger area (at least one chunk)"); return true; }
            String tool=activeTool.getOrDefault(tab,"Select");
            if(tab.equals("PLANS")){
                // FREEFORM_AREA is a real ObjectType; `create` mints its own id and leaves the new
                // object ungrouped, so the layer assignment is completed by resolvePendingLink().
                String pts=minX+","+minZ+";"+maxX+","+minZ+";"+maxX+","+maxZ+";"+minX+","+maxZ+";"+minX+","+minZ;
                planning("create","","FREEFORM_AREA",pts);
                if(!selectedPlan.isBlank())
                    pendingLink=new PendingLink("plan_group",pts,selectedPlan,System.currentTimeMillis()+8000L);
                say("Added shape to plan layer");
            }else if("Marquee".equals(tool)){
                int count=0; String last=null;
                for(var z:zones()) if(z.minX()>=minX&&z.maxX()<=maxX&&z.minZ()>=minZ&&z.maxZ()<=maxZ){count++;last=z.name();}
                if(count==1){ selectedRegion=last; say("Selected "+last); }
                else say(count+" regions inside the marquee");
            }else{
                createRegion(snapWorld(minX),snapWorld(minZ),snapWorld(maxX),snapWorld(maxZ));
            }
            return true;
        }
        if(dragMode!=null){ dragMode=null; commitGeometry(); return true; }
        return super.mouseReleased(event);
    }
    @Override public void mouseMoved(double mx,double my){
        double dx=ux(mx),dy=uy(my);
        coordX=(int)Math.round(mapWorldX(dx));coordZ=(int)Math.round(mapWorldZ(dy));
        if(programCursorShare){long now=System.currentTimeMillis();if(now-lastProgramCursorPublishMs>=750L){lastProgramCursorPublishMs=now;planning("program_cursor","",coordX+","+coordZ,programSubject());}}
        super.mouseMoved(mx,my);
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double scrollX,double scrollY){
        if(scrollY==0) return super.mouseScrolled(mouseX,mouseY,scrollX,scrollY);
        double dx=ux(mouseX),dy=uy(mouseY);
        if("palette".equals(overlay)){
            int n=paletteEntries().size();
            if(n>0) paletteIndex=Math.max(0,Math.min(n-1,paletteIndex+(scrollY>0?-1:1)));
            return true;
        }
        if("workbench".equals(overlay)&&hitBox(dx,dy,wbX(),wbY(),WB_W,WB_H)){
            workbenchScroll=Math.max(0,Math.min(2200,workbenchScroll+(scrollY>0?-52:52)));
            return true;
        }
        if("activity".equals(overlay)){
            int visible=OceanCanvasZoneClientCache.job()==null?10:9;int max=Math.max(0,OceanCanvasP3UXState.activities().size()-visible);activityScroll=Math.max(0,Math.min(max,activityScroll+(scrollY>0?-1:1)));return true;
        }
        double beforeX=mapWorldX(dx),beforeZ=mapWorldZ(dy);
        double factor=scrollY>0?0.82:1.22;
        mapBlocksPerPixel=Math.max(0.5,Math.min(64.0,mapBlocksPerPixel*factor));
        mapViewX=beforeX-(dx-DESIGN_W*0.5)*mapBlocksPerPixel;
        mapViewZ=beforeZ-(dy-DESIGN_H*0.5)*mapBlocksPerPixel;
        return true;
    }
    @Override public boolean charTyped(CharacterEvent event){
        if("palette".equals(overlay)){
            if(event.isAllowedChatCharacter()&&paletteQuery.length()<64){
                paletteQuery+=event.codepointAsString(); paletteIndex=0; paletteScroll=0;
            }
            return true;
        }
        if(editKey!=null){
            if(event.isAllowedChatCharacter()&&editBuf.length()<96){ editBuf+=event.codepointAsString(); return true; }
            return true;
        }
        return super.charTyped(event);
    }
    @Override public boolean keyPressed(KeyEvent event){
        int key=event.key(),mods=event.modifiers();
        boolean ctrl=(mods & GLFW.GLFW_MOD_CONTROL)!=0 || (mods & GLFW.GLFW_MOD_SUPER)!=0;
        if(palettePressed(key,ctrl)) return true;
        if(editKey!=null){
            if(key==GLFW.GLFW_KEY_ESCAPE){ cancelEdit(); return true; }
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){ commitEdit(); return true; }
            if(key==GLFW.GLFW_KEY_BACKSPACE){ if(!editBuf.isEmpty()) editBuf=editBuf.substring(0,editBuf.length()-1); return true; }
            return true; // an open field owns every other key, including the shortcut letters
        }
        if((ctrl&&key==GLFW.GLFW_KEY_K)||key==GLFW.GLFW_KEY_SLASH){ openPalette(); return true; }
        // history_undo/history_redo have been complete server routes for many versions with no
        // caller anywhere in the client. A live capability with no affordance is the same defect
        // class as an affordance with no capability.
        if(ctrl&&key==GLFW.GLFW_KEY_Z){ planUndo(); return true; }
        if(ctrl&&key==GLFW.GLFW_KEY_Y){ planRedo(); return true; }
        if(key==GLFW.GLFW_KEY_ESCAPE){
            if(explain!=null){explain=null;return true;}
            if(gestureKind!=null){cancelGesture();say("Geometry capture cancelled");return true;}
            if(opsOpen){opsOpen=false;return true;}
            if(overlay!=null){overlay=null;return true;}
            if(disclosure!=null){disclosure=null;return true;}
            if(panelOpen){panelOpen=false;return true;}
        }
        if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&gestureKind!=null){commitGesture();return true;}
        if(key==GLFW.GLFW_KEY_V){cancelGesture();activeTool.put(tab,"Select");say("Select tool active");return true;}
        if(key==GLFW.GLFW_KEY_SPACE){cancelGesture();activeTool.put(tab,"Pan");say("Pan tool active");return true;}
        if(key==GLFW.GLFW_KEY_R){cancelGesture();tab="REGIONS";activeTool.put("REGIONS","Shape");overlay=null;say("Region Shape tool active");return true;}
        if(key==GLFW.GLFW_KEY_P&&(tab.equals("REGIONS")||tab.equals("PLANS"))){cancelGesture();activeTool.put(tab,"Path");say("Path tool active");return true;}
        if(key==GLFW.GLFW_KEY_W&&(tab.equals("REGIONS")||tab.equals("PLANS")||tab.equals("LAYERS"))){cancelGesture();activeTool.put(tab,"Waypoint");say("Waypoint tool active");return true;}
        if(key==GLFW.GLFW_KEY_L){cancelGesture();tab="LAYERS";activeTool.put("LAYERS","Toggle");overlay="panels";say("Layer visibility controls");return true;}
        if(key==GLFW.GLFW_KEY_C){
            if(minecraft!=null&&minecraft.player!=null){
                mapViewX=minecraft.player.getX();mapViewZ=minecraft.player.getZ();say("Recentred on player");
            }
            return true;
        }
        if(key==GLFW.GLFW_KEY_S){ snap=!snap; say("Snapping "+(snap?"on":"off")); return true; }
        if(key==GLFW.GLFW_KEY_F){ focusMode=!focusMode; say("Focus mode "+(focusMode?"on":"off")); return true; }
        if(key==GLFW.GLFW_KEY_U){ var u=OceanCanvasP3UXState.cycleUnits();say("Units → "+title(u.name()));return true; }
        if(key==GLFW.GLFW_KEY_H){ boolean on=!OceanCanvasP3UXState.heatmapEnabled();OceanCanvasP3UXState.setHeatmapEnabled(on);say("Change heatmap "+(on?"on":"off"));return true; }
        if(ctrl&&key==GLFW.GLFW_KEY_B){ overlay="activity";activityScroll=0;return true; }
        if(ctrl&&key==GLFW.GLFW_KEY_J){ overlay="p3ux";return true; }
        if(ctrl && key==GLFW.GLFW_KEY_G){
            var z=region();
            if(z==null){ say("Select a region first"); return true; }
            if(z.enabled()){say("Already pregenerated and protected — use Rewipe only for an intentional destructive rerun");return true;}
            var j=OceanCanvasZoneClientCache.job();if(j!=null){say("An Ocean Canvas operation is already running in "+jobRegionName(j));return true;}
            if(!ensureOperationPreview("PREGEN",z.name(),""))return true; String token=operationPreviewToken("PREGEN",z.name(),""); if(token==null)return true; OceanCanvasZoneClientCache.clearOperationPreview(); send(new OceanCanvasZonePregenRequestPayload(z.name(),token));
            say("Pregen requested for "+z.name()+" — it will auto-protect only after verified completion");
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public void removed(){if(mapRaster!=null){mapRaster.close();mapRaster=null;}super.removed();}
    @Override public void onClose(){if(minecraft!=null)minecraft.gui.setScreen(parent);else super.onClose();}
}

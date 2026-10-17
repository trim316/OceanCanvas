package net.oceancanvas.mod.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.network.OceanCanvasConfigUpdateRequestPayload;
import net.oceancanvas.mod.network.OceanCanvasOperationPreviewRequestPayload;
import net.oceancanvas.mod.network.OceanCanvasZoneClientCache;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical Ocean Canvas global settings editor.
 *
 * <p>v86 removes arbitrary pages. Categories remain task-oriented, but the
 * contents scroll naturally with the wheel. Each row has a persistent label,
 * a compact description, and the full description is also attached to the
 * row's interactive control as a hover tooltip so truncated copy is never the
 * only copy available.</p>
 *
 * <p><b>The "Structures" category's five per-kind rows (v124, upgraded to a real three-state
 * Default/Always/Never choice in v125) are the world-wide counterpart to a region's own
 * structure rule buttons in {@code OceanCanvasMapScreen} - "there should be a specific spot in
 * settings to adjust the world-wide structure rules, like there are currently when you select a
 * region" was the explicit ask, later refined to "either always or never, with the exact same
 * behavior as at the region level ... with a third option that is just default." Rather than
 * hand-listing the five rows here with their own separate label + toggle-button layout (as every
 * other {@code Kind.BOOL} row still does, and as this exact category did before v124), these five
 * rows are generated straight from {@link OceanCanvasStructureKind#values()} - the same enum and
 * the same iteration order the region editor's own structure-rule button row already uses (see
 * {@code OceanCanvasMapScreen}'s "RULES tab" comment) - and rendered as one full-width button per
 * kind reading "&lt;name&gt;: Default/Always/Never", matching that editor's "&lt;name&gt;:
 * &lt;state&gt;" button-label pattern and its Inherit-&gt;Force On-&gt;Force Off cycle order
 * (relabeled Default/Always/Never here, since there's nothing above world scope to inherit from).
 * A region's own rule still always wins over whatever is set here - this only edits the fallback
 * a region without its own explicit rule resolves to, exactly as {@link
 * OceanCanvasStructureKind#globalDefault} documents. "Always" here does real, active work, not
 * just a preference: it queues the same vanilla-like forced-distribution pass a region's own
 * Always rule already uses (see {@code ModStarterStructures}), scoped to whatever chunks the next
 * pregen/rewipe/expand actually touches.</p>
 */
public final class OceanCanvasSettingsScreen extends Screen {
    private static final int CY=0xFF1FC4EF, BLACK=0xFF000000, PANEL=0xFF05080A, BORDER=0xFF2E3236, TEXT=0xFFDCDCDC, DIM=0xFF8A8F93, ROW=0xFF1C1F21;
    private enum Kind { BOOL, INT, TEXT, STRUCTURE }
    private record Setting(String key, String label, Kind kind, String category, String help) {}
    private record FieldBinding(Setting setting, EditBox field) {}

    private static final List<Setting> SETTINGS = buildSettings();

    private static List<Setting> buildSettings() {
        List<Setting> list = new ArrayList<>(List.of(
            new Setting("canvasSize","Canvas size",Kind.INT,"Canvas","Width and height of the managed Ocean Canvas, in blocks."),
            new Setting("centerX","Canvas center X",Kind.INT,"Canvas","World X coordinate at the center of the Canvas."),
            new Setting("centerZ","Canvas center Z",Kind.INT,"Canvas","World Z coordinate at the center of the Canvas."),
            new Setting("expansionEnabled","Allow managed expansion",Kind.BOOL,"Canvas","Allows Ocean Canvas to expand the managed area when requested."),
            new Setting("oceanFloorY","Ocean floor Y",Kind.INT,"Canvas","Base excavation floor. Deeper natural terrain is left alone."),
            new Setting("oceanFloorVariation","Floor variation",Kind.INT,"Canvas","Gentle vertical variation applied to the Ocean Canvas floor."),
            new Setting("oceanFloorTransitionThickness","Floor seal thickness",Kind.INT,"Canvas","Solid buffer retained below the excavated floor."),
            new Setting("taperEnabled","Soft edge transition",Kind.BOOL,"Canvas","Blend the Canvas boundary through a transition band."),
            new Setting("taperWidthChunks","Edge transition width",Kind.INT,"Canvas","Width of the soft boundary transition, in chunks."),

            new Setting("pregenEnabled","Enable Pregen",Kind.BOOL,"Performance","Allows Canvas and region Pregen jobs."),
            new Setting("pregenChunksPerTick","Pregen base rate",Kind.INT,"Performance","Base submission rate used by the adaptive limiter."),
            new Setting("foreverWorldTargetHours","Full-canvas target",Kind.INT,"Performance","Completion SLO in hours used for throughput/ETA warnings; default 8."),
            new Setting("flattenerChunksPerTick","Live flatten rate",Kind.INT,"Performance","Background flattening work allowed per server tick."),
            new Setting("backupEnabled","Automatic safety backups",Kind.BOOL,"Performance","Creates safety backups before sufficiently large destructive jobs."),
            new Setting("backupThresholdChunks","Backup threshold",Kind.INT,"Performance","Minimum destructive job size that triggers an automatic backup."),
            new Setting("backupRetentionCount","Backups retained",Kind.INT,"Performance","Maximum automatic safety backups retained."),
            new Setting("undoDepthPerPlayer","Undo history depth",Kind.INT,"Performance","Number of Rewipe operations kept for undo/redo."),

            new Setting("hudEnabled","Boundary HUD",Kind.BOOL,"Display","Shows current Canvas and boundary status in the normal HUD."),
            new Setting("journeyMapOverlayEnabled","JourneyMap compatibility overlay",Kind.BOOL,"Display","Legacy compatibility option for exposing Ocean Canvas regions to JourneyMap."),
            new Setting("worldBorderSyncEnabled","Sync vanilla world border",Kind.BOOL,"Display","Aligns Minecraft's world border to the Canvas when enabled."),
            new Setting("biomeMaskEnabled","Use global ocean biome mask",Kind.BOOL,"Display","Uses one biome across Canvas areas unless a region overrides it."),
            new Setting("biomeMaskBiome","Global biome ID",Kind.TEXT,"Display","Namespaced biome ID, for example minecraft:ocean.")
        ));
        // One row per OceanCanvasStructureKind, generated rather than hand-listed - see this
        // class's own doc for why. Same enum, same order the region rule editor already
        // iterates, so the two lists can never quietly drift out of sync with each other.
        for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
            list.add(new Setting(kind.configKey(), kind.displayName(), Kind.STRUCTURE, "Structures",
                    "Default preserves/relocates if vanilla places one. Always forces it everywhere "
                            + "(same distribution a region's own Always rule uses). Never clears it away. "
                            + "A region's own rule (set on the map) always overrides this."));
        }
        // Not a structure-kind toggle (it places a starter structure near spawn rather than
        // setting a preserve/clear default), so it keeps the ordinary BOOL row layout and sits
        // after the five generated rows rather than being generated alongside them.
        list.add(new Setting("guaranteeSpawnOceanRuin","Starter ocean ruin",Kind.BOOL,"Structures","Places the configured starter ocean ruin near spawn."));
        return List.copyOf(list);
    }

    /**
     * OC-F129 safe defaults audit, partial. Authoritative values copied verbatim from the
     * {@code DEFAULT_*} constants in {@code OceanCanvasConfig} - every key {@link #SETTINGS}
     * exposes has an entry here, so this can never silently omit a setting from the count. This
     * is a consolidated count only, not a full itemized report - see {@link #deviatingSettings()}.
     */
    private static final Map<String,String> SAFE_DEFAULTS = Map.ofEntries(
            Map.entry("canvasSize","20000"), Map.entry("centerX","0"), Map.entry("centerZ","0"),
            Map.entry("expansionEnabled","true"), Map.entry("oceanFloorY","25"),
            Map.entry("oceanFloorVariation","5"), Map.entry("oceanFloorTransitionThickness","4"),
            Map.entry("taperEnabled","false"), Map.entry("taperWidthChunks","8"),
            Map.entry("pregenEnabled","true"), Map.entry("pregenChunksPerTick","4"),
            Map.entry("foreverWorldTargetHours","8"), Map.entry("flattenerChunksPerTick","16"), Map.entry("backupEnabled","true"),
            Map.entry("backupThresholdChunks","500"), Map.entry("backupRetentionCount","5"),
            Map.entry("undoDepthPerPlayer","3"), Map.entry("hudEnabled","false"),
            Map.entry("journeyMapOverlayEnabled","false"), Map.entry("worldBorderSyncEnabled","false"),
            Map.entry("biomeMaskEnabled","true"), Map.entry("biomeMaskBiome","minecraft:ocean"),
            Map.entry("shipwrecksEnabled","INHERIT"), Map.entry("naturalOceanRuinsProtected","FORCE_OFF"),
            Map.entry("buriedTreasureEnabled","INHERIT"), Map.entry("naturalOceanMonumentsProtected","FORCE_OFF"),
            Map.entry("naturalRuinedPortalsProtected","FORCE_OFF"), Map.entry("guaranteeSpawnOceanRuin","false"));
    private static boolean matchesSafeDefault(Setting s,String current){
        String def=SAFE_DEFAULTS.get(s.key());
        if(def==null) return true; // no recorded default - never flag a key this audit does not know
        return switch(s.kind()){
            case BOOL -> Boolean.toString(Boolean.parseBoolean(current)).equals(def);
            case STRUCTURE -> normalizeStructureOverride(current).equals(def);
            case TEXT -> current.trim().equalsIgnoreCase(def);
            case INT -> {
                try { yield Integer.parseInt(current.trim())==Integer.parseInt(def); }
                catch(NumberFormatException ex){ yield current.isBlank(); } // not yet synced - do not flag
            }
        };
    }
    /** Every currently-loaded setting whose value differs from its recorded safe default. */
    private static List<Setting> deviatingSettings(){
        var cfg=OceanCanvasZoneClientCache.config();
        var out=new ArrayList<Setting>();
        for(Setting s:SETTINGS) if(!matchesSafeDefault(s,cfg.getOrDefault(s.key(),""))) out.add(s);
        return out;
    }

    private static final List<String> CATEGORIES = List.of("Canvas","Performance","Display","Structures");
    private static final int ROW_HEIGHT = 62;
    private final Screen parent;
    private int categoryIndex;
    private int scrollRow;
    private final List<FieldBinding> fields = new ArrayList<>();
    private final Map<String,String> localState = new HashMap<>();

    public OceanCanvasSettingsScreen(Screen parent) { this(parent, 0, 0, null); }

    private OceanCanvasSettingsScreen(Screen parent, int categoryIndex, int scrollRow, Map<String,String> carry) {
        super(Component.literal("Ocean Canvas Settings"));
        this.parent = parent; this.categoryIndex = categoryIndex; this.scrollRow = scrollRow;
        if(carry!=null)this.localState.putAll(carry);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        fields.clear();
        if(localState.isEmpty())localState.putAll(OceanCanvasZoneClientCache.config());
        int left=cardLeft(),width=cardWidth();
        List<Setting> category=categorySettings();int visible=visibleRows();int maxScroll=Math.max(0,category.size()-visible);scrollRow=Math.max(0,Math.min(scrollRow,maxScroll));
        int y=contentTop();
        for(int i=scrollRow;i<Math.min(category.size(),scrollRow+visible);i++){
            Setting setting=category.get(i);String value=localState.getOrDefault(setting.key(),OceanCanvasZoneClientCache.config().getOrDefault(setting.key(),""));
            if(setting.kind()==Kind.INT||setting.kind()==Kind.TEXT){
                int applyW=62,fieldW=Math.min(220,Math.max(120,width/3));
                EditBox field=new EditBox(this.font,left+width-fieldW-applyW-8,y+1,fieldW,22,Component.literal(setting.label()));
                field.setValue(value);field.setMaxLength(setting.kind()==Kind.TEXT?80:16);field.setTooltip(Tooltip.create(Component.literal(setting.label()+"\n"+setting.help()+"\n"+OceanCanvasP3UXState.safeDefault(setting.key()))));this.addRenderableWidget(field);fields.add(new FieldBinding(setting,field));
            }
            y+=ROW_HEIGHT;
        }
    }

    private int contentTop() { return 92; }
    private int contentBottom() { return Math.max(contentTop() + ROW_HEIGHT, this.height - 24); }
    private int visibleRows() { return Math.max(1, (contentBottom() - contentTop()) / ROW_HEIGHT); }
    private int cardWidth() { return Math.min(980, this.width - 56); }
    private int cardLeft() { return Math.max(16, (this.width - cardWidth()) / 2); }
    private void rebuild() { if (this.minecraft != null) this.minecraft.gui.setScreen(new OceanCanvasSettingsScreen(parent, categoryIndex, scrollRow, localState)); }
    private List<Setting> categorySettings() { String c = CATEGORIES.get(categoryIndex); return SETTINGS.stream().filter(s -> s.category().equals(c)).toList(); }
    private void send(String key, String value) {
        OceanCanvasZoneClientCache.clearFeedback();
        if(isImpactful(key)){
            var p=OceanCanvasZoneClientCache.operationPreview();
            if(p==null||p.stale(30_000L)||!p.matches("CONFIG",key,value)){
                OceanCanvasZoneClientCache.clearOperationPreview();
                ClientPlayNetworking.send(new OceanCanvasOperationPreviewRequestPayload("CONFIG",key,value));
                OceanCanvasZoneClientCache.pushLocalFeedback("Server change-impact preview requested — review it, then apply the same value again.",false);
                return;
            }
            if(p.blocked()){OceanCanvasZoneClientCache.pushLocalFeedback(p.summary(),true);return;}
            localState.put(key,value);
            String token=p.token();OceanCanvasZoneClientCache.clearOperationPreview();
            ClientPlayNetworking.send(new OceanCanvasConfigUpdateRequestPayload(key,value,token));
            return;
        }
        localState.put(key,value);ClientPlayNetworking.send(new OceanCanvasConfigUpdateRequestPayload(key,value));
    }
    private static boolean isImpactful(String key){return java.util.Set.of("canvasSize","centerX","centerZ","oceanFloorY","oceanFloorVariation","oceanFloorTransitionThickness","taperEnabled","taperWidthChunks","biomeMaskEnabled","biomeMaskBiome","shipwrecksEnabled","naturalOceanRuinsProtected","buriedTreasureEnabled","naturalOceanMonumentsProtected","naturalRuinedPortalsProtected").contains(key);}


    private FieldBinding fieldFor(String key){for(FieldBinding f:fields)if(f.setting().key().equals(key))return f;return null;}

    /** "&lt;name&gt;: Default"/"Always"/"Never" - matches OceanCanvasMapScreen's own per-region
     *  structure-rule button label pattern (see that screen's {@code prettyOverride}), just with
     *  "Default" standing in for INHERIT since there's nothing above world scope to inherit from. */
    private static String structureRowLabel(String label, String value) {
        return label + ": " + prettyStructureOverride(value);
    }


    private static String normalizeStructureOverride(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "FORCE_ON", "FORCE_OFF", "INHERIT" -> v;
            // Backward compatibility for a client that opens before a migrated server config has
            // been rewritten in enum form. Legacy true meant normal/default vanilla behavior;
            // legacy false meant explicitly disabled.
            case "TRUE" -> "INHERIT";
            case "FALSE" -> "FORCE_OFF";
            default -> "INHERIT";
        };
    }

    private static String prettyStructureOverride(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "FORCE_ON" -> "Always";
            case "FORCE_OFF" -> "Never";
            default -> "Default"; // INHERIT, blank, or anything unrecognized
        };
    }

    /** Default -&gt; Always -&gt; Never -&gt; Default, the same cycle order OceanCanvasMapScreen's
     *  own cycleStructureRule already uses for a region's rule (Inherit -&gt; Force On -&gt; Force Off). */
    private static String nextStructureOverride(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "FORCE_ON" -> "FORCE_OFF";
            case "FORCE_OFF" -> "INHERIT";
            default -> "FORCE_ON"; // INHERIT, blank, or anything unrecognized
        };
    }

    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(event.button()!=0)return super.mouseClicked(event,doubleClick);double mx=event.x(),my=event.y();int left=cardLeft(),width=cardWidth(),right=left+width,gap=3,tw=(width-gap*(CATEGORIES.size()-1))/CATEGORIES.size();
        for(int i=0;i<CATEGORIES.size();i++){int x=left+i*(tw+gap),w=i==CATEGORIES.size()-1?width-i*(tw+gap):tw;if(mx>=x&&mx<x+w&&my>=50&&my<74){categoryIndex=i;scrollRow=0;rebuild();return true;}}
        List<Setting> cat=categorySettings();int y=contentTop();for(int i=scrollRow;i<Math.min(cat.size(),scrollRow+visibleRows());i++){Setting st=cat.get(i);if(my>=y-2&&my<y+26){String v=localState.getOrDefault(st.key(),OceanCanvasZoneClientCache.config().getOrDefault(st.key(),""));if(st.kind()==Kind.BOOL&&mx>=right-116&&mx<right){send(st.key(),Boolean.toString(!Boolean.parseBoolean(v)));return true;}if(st.kind()==Kind.STRUCTURE&&mx>=right-168&&mx<right){send(st.key(),nextStructureOverride(v));return true;}if((st.kind()==Kind.INT||st.kind()==Kind.TEXT)&&mx>=right-62&&mx<right){FieldBinding fb=fieldFor(st.key());if(fb!=null)send(st.key(),fb.field().getValue());return true;}}y+=ROW_HEIGHT;}
        return super.mouseClicked(event,doubleClick);
    }

    @Override public void onClose() { if (this.minecraft != null) this.minecraft.gui.setScreen(parent); else super.onClose(); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int left = cardLeft();
        int right = left + cardWidth();
        if (mouseX >= left - 8 && mouseX <= right + 8 && mouseY >= contentTop() - 8 && mouseY <= contentBottom() + 8) {
            List<Setting> category = categorySettings();
            int maxScroll = Math.max(0, category.size() - visibleRows());
            int next = Math.max(0, Math.min(maxScroll, scrollRow - (int)Math.signum(scrollY)));
            if (next != scrollRow) {
                scrollRow = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        super.extractRenderState(g,mouseX,mouseY,delta);
        int left=cardLeft(),width=cardWidth(),right=left+width;g.fill(0,0,this.width,this.height,BLACK);g.fill(left-12,8,right+12,this.height-8,0xFF020405);g.fill(left-12,8,right+12,9,CY);g.fill(left-12,8,left-11,this.height-8,CY);g.fill(right+11,8,right+12,this.height-8,BORDER);
        g.text(font,Component.literal("OCEAN CANVAS SETTINGS"),left,20,CY,false);
        int dev=deviatingSettings().size();
        String auditLine="Global defaults  •  region-specific rules live on the map  •  "
                +(dev==0?"all settings at their safe default":dev+" of "+SETTINGS.size()+" settings differ from their safe default");
        g.text(font,Component.literal(auditLine),left,34,dev==0?DIM:0xFFE0B25A,false);
        int gap=3,tw=(width-gap*(CATEGORIES.size()-1))/CATEGORIES.size();for(int i=0;i<CATEGORIES.size();i++){int x=left+i*(tw+gap),w=i==CATEGORIES.size()-1?width-i*(tw+gap):tw;boolean on=i==categoryIndex;g.fill(x,50,x+w,74,on?0x171FC4EF:PANEL);int bc=on?CY:BORDER;g.fill(x,50,x+w,51,bc);g.fill(x,73,x+w,74,bc);g.fill(x,50,x+1,74,bc);g.fill(x+w-1,50,x+w,74,bc);g.centeredText(font,Component.literal(CATEGORIES.get(i).toUpperCase(java.util.Locale.ROOT)),x+w/2,58,on?CY:TEXT);}
        List<Setting> cat=categorySettings();int vis=visibleRows(),y=contentTop();for(int i=scrollRow;i<Math.min(cat.size(),scrollRow+vis);i++){Setting st=cat.get(i);String v=localState.getOrDefault(st.key(),OceanCanvasZoneClientCache.config().getOrDefault(st.key(),""));g.fill(left,y-5,right,y-4,ROW);g.text(font,Component.literal(st.label()),left,y,TEXT,false);g.text(font,Component.literal(trim(st.help(),Math.max(90,width-330))),left,y+13,DIM,false);g.text(font,Component.literal(trim(OceanCanvasP3UXState.safeDefault(st.key()),Math.max(90,width-330))),left,y+27,0xFFE0B25A,false);OceanCanvasP3UXState.RiskTier tier=isImpactful(st.key())?OceanCanvasP3UXState.RiskTier.DESTRUCTIVE:OceanCanvasP3UXState.RiskTier.SAFE;int tierColor=tier==OceanCanvasP3UXState.RiskTier.DESTRUCTIVE?0xFFE05A5A:0xFF69B884;g.text(font,Component.literal("RISK: "+OceanCanvasP3UXState.riskLabel(tier)),left,y+41,tierColor,false);
            if(st.kind()==Kind.BOOL){boolean on=Boolean.parseBoolean(v);int bx=right-116,by=y-1,bw=116,bh=24;g.fill(bx,by,bx+bw,by+bh,on?0x171FC4EF:0xFF0D1013);int bc=on?CY:BORDER;g.fill(bx,by,bx+bw,by+1,bc);g.fill(bx,by+bh-1,bx+bw,by+bh,bc);g.fill(bx,by,bx+1,by+bh,bc);g.fill(bx+bw-1,by,bx+bw,by+bh,bc);g.centeredText(font,Component.literal(on?"ENABLED":"DISABLED"),bx+bw/2,by+8,on?CY:TEXT);
            }else if(st.kind()==Kind.STRUCTURE){int bx=right-168,by=y-1,bw=168,bh=24;String pretty=prettyStructureOverride(v);g.fill(bx,by,bx+bw,by+bh,0xFF0D1013);int bc=pretty.equals("Always")?CY:pretty.equals("Never")?0xFFE05A5A:BORDER;g.fill(bx,by,bx+bw,by+1,bc);g.fill(bx,by+bh-1,bx+bw,by+bh,bc);g.fill(bx,by,bx+1,by+bh,bc);g.fill(bx+bw-1,by,bx+bw,by+bh,bc);g.centeredText(font,Component.literal(pretty.toUpperCase(java.util.Locale.ROOT)),bx+bw/2,by+8,pretty.equals("Always")?CY:pretty.equals("Never")?0xFFE05A5A:TEXT);
            }else{int bx=right-62,by=y,bb=0xFF2E3236;g.fill(bx,by,bx+62,by+22,0xFF0D1013);g.fill(bx,by,bx+62,by+1,bb);g.fill(bx,by+21,bx+62,by+22,bb);g.fill(bx,by,bx+1,by+22,bb);g.fill(bx+61,by,bx+62,by+22,bb);g.centeredText(font,Component.literal("APPLY"),bx+31,by+7,TEXT);}
            y+=ROW_HEIGHT;}
        if(cat.size()>vis){int max=Math.max(1,cat.size()-vis),tt=contentTop(),tb=contentTop()+vis*ROW_HEIGHT-4,th=Math.max(18,(tb-tt)*vis/cat.size()),ty=tt+(tb-tt-th)*scrollRow/max;g.fill(right+4,tt,right+6,tb,0xFF202428);g.fill(right+3,ty,right+7,ty+th,CY);g.text(font,Component.literal("Scroll for more"),left,this.height-20,DIM,false);}OceanCanvasZoneClientCache.Feedback fb=OceanCanvasZoneClientCache.feedback(5000);if(fb!=null)g.text(font,Component.literal(trim(fb.message(),width)),left,this.height-20,fb.error()?0xFFFF5C5C:0xFF55D98A,false);
    }

    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end) + ellipsis) > maxWidth) end--;
        return text.substring(0, Math.max(0, end)) + ellipsis;
    }
}

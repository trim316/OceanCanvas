package net.oceancanvas.mod.project;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight stable reference used by cross-cutting World Model features.
 * Canonical domain objects remain in their existing stores; this prevents Intent/Authorship/Claims
 * from inventing separate identity schemes while the broader WorldObject model is introduced.
 */
public record OceanCanvasWorldObjectRef(String type,String id) {
    private static final Set<String> TYPES=Set.of("WORLD","REGION","PROJECT","PLAN","TERRAIN_ASSET","FEATURE","WORK_AREA");
    public OceanCanvasWorldObjectRef {
        type=type==null?"PROJECT":type.trim().toUpperCase(Locale.ROOT);
        if(!TYPES.contains(type))throw new IllegalArgumentException("unsupported World Object type: "+type);
        id=id==null?"":id.trim().toLowerCase(Locale.ROOT).replace(' ','_');
        if(id.isBlank()&&!"WORLD".equals(type))throw new IllegalArgumentException("World Object ID required");
    }
    public static OceanCanvasWorldObjectRef of(String type,String id){return new OceanCanvasWorldObjectRef(type,id);}
    public String key(){return type+":"+id;}
}

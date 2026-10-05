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

/** Optional ordered Atlas journeys. Stable metadata only; no custom registry or world dependency. */
public final class OceanCanvasAtlasRouteData extends SavedData {
    public static final int MAX_ROUTES=32,MAX_STOPS=64,MAX_DESCRIPTION=1200;private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"atlas_routes");
    public record Stop(String id,String kind,String targetId,String title,int x,int z,int order){}
    public record Route(String id,String name,String description,List<Stop> stops){}
    private final List<String> packed;private static final Codec<OceanCanvasAtlasRouteData> CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.listOf().optionalFieldOf("routes",List.of()).forGetter(d->d.packed)).apply(i,OceanCanvasAtlasRouteData::new));
    public static final SavedDataType<OceanCanvasAtlasRouteData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasAtlasRouteData::new,CODEC,null);
    public OceanCanvasAtlasRouteData(){this(List.of());}private OceanCanvasAtlasRouteData(List<String> values){packed=new ArrayList<>();if(values!=null)packed.addAll(values.subList(Math.max(0,values.size()-MAX_ROUTES),values.size()));}
    public static OceanCanvasAtlasRouteData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}private static String unb64(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
    private static String encode(Route r){String stops=r.stops().stream().sorted(Comparator.comparingInt(Stop::order)).map(s->String.join(",",s.id(),s.kind(),b64(s.targetId()),b64(s.title()),Integer.toString(s.x()),Integer.toString(s.z()),Integer.toString(s.order()))).collect(java.util.stream.Collectors.joining(";"));return String.join("\t",r.id(),b64(r.name()),b64(r.description()),stops);}
    private static Route decode(String raw){try{String[] f=raw.split("\t",-1);if(f.length!=4)return null;List<Stop> stops=new ArrayList<>();if(!f[3].isBlank())for(String value:f[3].split(";")){String[] s=value.split(",",-1);if(s.length==7&&stops.size()<MAX_STOPS)stops.add(new Stop(s[0],s[1],unb64(s[2]),unb64(s[3]),Integer.parseInt(s[4]),Integer.parseInt(s[5]),Integer.parseInt(s[6])));}stops.sort(Comparator.comparingInt(Stop::order));return new Route(f[0],unb64(f[1]),unb64(f[2]),List.copyOf(stops));}catch(RuntimeException e){return null;}}
    public List<Route> routes(){List<Route> out=new ArrayList<>();for(String raw:packed){Route r=decode(raw);if(r!=null)out.add(r);}return List.copyOf(out);}private int index(String id){for(int i=0;i<packed.size();i++){Route r=decode(packed.get(i));if(r!=null&&r.id().equals(id))return i;}throw new IllegalArgumentException("unknown Atlas route");}
    private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().substring(0,8);}private void save(int i,Route r){packed.set(i,encode(r));setDirty();}
    public Route create(String name){String n=name==null?"":name.trim();if(n.isBlank()||n.length()>64)throw new IllegalArgumentException("route name must be 1-64 characters");if(packed.size()>=MAX_ROUTES)throw new IllegalArgumentException("Atlas route limit reached (32)");Route r=new Route(id("route"),n,"",List.of());packed.add(encode(r));setDirty();return r;}
    public void updateDescription(String routeId,String description){int i=index(routeId);Route r=decode(packed.get(i));String d=description==null?"":description.trim();if(d.length()>MAX_DESCRIPTION)throw new IllegalArgumentException("route chapter must be at most 1200 characters");save(i,new Route(r.id(),r.name(),d,r.stops()));}
    public void delete(String routeId){packed.remove(index(routeId));setDirty();}
    public Stop add(String routeId,String kind,String targetId,String title,int x,int z){int i=index(routeId);Route r=decode(packed.get(i));if(r.stops().size()>=MAX_STOPS)throw new IllegalArgumentException("route stop limit reached (64)");String k=kind==null?"":kind.trim().toUpperCase(Locale.ROOT);if(!Set.of("LANDMARK","REGION","MISSION","MILESTONE","VIEWPOINT").contains(k))throw new IllegalArgumentException("invalid Atlas story kind");if(Math.abs((long)x)>30_000_000L||Math.abs((long)z)>30_000_000L)throw new IllegalArgumentException("Atlas stop is outside Minecraft coordinates");String t=title==null?"":title.trim();if(t.isBlank()||t.length()>96)throw new IllegalArgumentException("stop title must be 1-96 characters");List<Stop> stops=new ArrayList<>(r.stops());Stop s=new Stop(id("stop"),k,targetId==null?"":targetId,t,x,z,stops.size());stops.add(s);save(i,new Route(r.id(),r.name(),r.description(),List.copyOf(stops)));return s;}
    public void remove(String routeId,String stopId){int i=index(routeId);Route r=decode(packed.get(i));List<Stop> stops=new ArrayList<>();for(var s:r.stops())if(!s.id().equals(stopId))stops.add(s);if(stops.size()==r.stops().size())throw new IllegalArgumentException("unknown Atlas stop");save(i,new Route(r.id(),r.name(),r.description(),normalize(stops)));}
    public void move(String routeId,String stopId,int direction){int i=index(routeId);Route r=decode(packed.get(i));List<Stop> stops=new ArrayList<>(r.stops());int at=-1;for(int n=0;n<stops.size();n++)if(stops.get(n).id().equals(stopId)){at=n;break;}if(at<0)throw new IllegalArgumentException("unknown Atlas stop");int to=at+(direction<0?-1:1);if(to<0||to>=stops.size())return;Collections.swap(stops,at,to);save(i,new Route(r.id(),r.name(),r.description(),normalize(stops)));}
    private static List<Stop> normalize(List<Stop> stops){List<Stop> out=new ArrayList<>();for(int i=0;i<stops.size();i++){var s=stops.get(i);out.add(new Stop(s.id(),s.kind(),s.targetId(),s.title(),s.x(),s.z(),i));}return List.copyOf(out);}
    public String clientStops(Route r){return r.stops().stream().map(s->String.join(",",s.id(),s.kind(),b64(s.targetId()),b64(s.title()),Integer.toString(s.x()),Integer.toString(s.z()),Integer.toString(s.order()))).collect(java.util.stream.Collectors.joining(";"));}
}

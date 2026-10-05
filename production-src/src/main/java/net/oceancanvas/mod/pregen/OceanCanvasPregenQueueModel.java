package net.oceancanvas.mod.pregen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded, dependency-free queue state. Running permission is intentionally NOT persisted. */
public final class OceanCanvasPregenQueueModel {
    public static final int SCHEMA=1,MAX_ENTRIES=16,MAX_REGION_CHUNKS=65_536;
    public record Entry(String id,String region,String owner,String shape,String canvas,long chunks,String state,String detail) {
        public Entry withState(String state,String detail){return new Entry(id,region,owner,shape,canvas,chunks,state,detail);}
    }
    private final int schema;
    private final List<Entry> entries;
    private long revision;
    public OceanCanvasPregenQueueModel(int schema,long revision,List<Entry> entries){
        this.schema=schema;this.revision=revision;this.entries=new ArrayList<>(entries);
    }
    public boolean readOnly(){
        if(schema!=SCHEMA||revision<0||revision==Long.MAX_VALUE||entries.size()>MAX_ENTRIES)return true;
        Set<String> ids=new HashSet<>();
        for(int i=0;i<entries.size();i++){
            Entry e=entries.get(i);
            if(e==null||e.region()==null||e.shape()==null||e.canvas()==null||e.state()==null||e.detail()==null)return true;
            try{UUID.fromString(e.id());UUID.fromString(e.owner());}catch(RuntimeException ex){return true;}
            if(!ids.add(e.id())||e.region().isBlank()||e.region().length()>256||!e.shape().matches("[0-9a-f]{64}")
                    ||e.canvas().isBlank()||e.detail().length()>240||(i!=0&&e.state().equals("RUNNING"))
                    ||e.chunks()<1||e.chunks()>MAX_REGION_CHUNKS
                    ||!Set.of("WAITING","RUNNING","BLOCKED","INTERRUPTED").contains(e.state()))return true;
        }
        return false;
    }
    private void writable(){if(readOnly())throw new IllegalStateException("Queue data is incompatible; read-only recovery required.");}
    public int schema(){return schema;}
    public long revision(){return revision;}
    public List<Entry> entries(){return List.copyOf(entries);}
    public Entry head(){return entries.isEmpty()?null:entries.get(0);}
    public boolean contains(String id){return entries.stream().anyMatch(e->e.id().equals(id));}
    public void touch(){writable();revision++;}
    public void add(Entry e){
        writable();
        if(e==null||!"WAITING".equals(e.state()))throw new IllegalArgumentException("New entries must be waiting.");
        if(entries.size()>=MAX_ENTRIES)throw new IllegalArgumentException("Queue is full (16 entries).");
        if(entries.stream().anyMatch(x->x.region().equalsIgnoreCase(e.region())))throw new IllegalArgumentException("That region is already queued.");
        var candidate=new ArrayList<>(entries);candidate.add(e);
        if(new OceanCanvasPregenQueueModel(schema,revision,candidate).readOnly())throw new IllegalArgumentException("Invalid queue entry.");
        entries.add(e);revision++;
    }
    public void remove(String id){
        writable();int i=index(id);if(entries.get(i).state().equals("RUNNING"))throw new IllegalStateException("Stop the current queued job first.");
        entries.remove(i);revision++;
    }
    public void move(String id,int direction){
        writable();int i=index(id),j=i+(direction<0?-1:1);
        if(j<0||j>=entries.size())return;
        if(entries.get(i).state().equals("RUNNING")||entries.get(j).state().equals("RUNNING"))
            throw new IllegalStateException("The running queue entry cannot move.");
        java.util.Collections.swap(entries,i,j);revision++;
    }
    public void state(String id,String state,String detail){
        writable();if(!Set.of("WAITING","RUNNING","BLOCKED","INTERRUPTED").contains(state))throw new IllegalArgumentException("Invalid queue state.");
        int i=index(id);Entry old=entries.get(i);
        if(old.state().equals(state)&&old.detail().equals(detail))return;
        Entry changed=old.withState(state,detail.substring(0,Math.min(240,detail.length())));
        var candidate=new ArrayList<>(entries);candidate.set(i,changed);
        if(new OceanCanvasPregenQueueModel(schema,revision,candidate).readOnly())throw new IllegalStateException("Only the queue head may run.");
        entries.set(i,changed);revision++;
    }
    /** Only the engine's successful completion callback may remove the running head. */
    public void complete(String id){
        writable();if(head()==null||!head().id().equals(id)||!head().state().equals("RUNNING"))
            throw new IllegalStateException("Queue completion did not match its running head.");
        entries.remove(0);revision++;
    }
    private int index(String id){for(int i=0;i<entries.size();i++)if(entries.get(i).id().equals(id))return i;throw new IllegalArgumentException("Queue entry no longer exists.");}

    public record Item(String id,String region,long chunks,String state,String detail){}
    public record View(long revision,boolean armed,boolean readOnly,String message,String recoveryId,List<Item> items){
        public static View empty(){return new View(0,false,false,"Waiting for server queue…","",List.of());}
        public static View decode(String packed){
            try{
                String[] lines=packed.split("\n"),h=lines[0].split("\t",-1);
                if(h.length!=7||!h[0].equals("Q1"))return empty();
                List<Item> items=new ArrayList<>();
                for(int i=1;i<lines.length;i++){
                    String[] f=lines[i].split("\t",-1);
                    if(f.length!=6||items.size()>=MAX_ENTRIES)return empty();
                    items.add(new Item(f[0],unb64(f[1]),Long.parseLong(f[2]),f[3],unb64(f[4])));
                }
                return new View(Long.parseLong(h[1]),Boolean.parseBoolean(h[2]),Boolean.parseBoolean(h[3]),unb64(h[4]),h[5],List.copyOf(items));
            }catch(RuntimeException ex){return empty();}
        }
    }
    public String view(boolean armed,String message,String recoveryId){
        StringBuilder out=new StringBuilder("Q1\t").append(revision).append('\t').append(armed).append('\t').append(readOnly())
                .append('\t').append(b64(message,200)).append('\t').append(recoveryId).append("\tend");
        for(Entry e:entries.subList(0,Math.min(MAX_ENTRIES,entries.size())))out.append('\n').append(e.id()).append('\t')
                .append(b64(e.region(),128)).append('\t').append(e.chunks()).append('\t').append(e.state()).append('\t').append(b64(e.detail(),240)).append("\tend");
        return out.toString();
    }
    private static String b64(String s,int max){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.substring(0,Math.min(max,s.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String unb64(String s){return new String(java.util.Base64.getUrlDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8);}
}

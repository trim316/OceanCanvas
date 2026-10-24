package net.oceancanvas.mod.project;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Dependency-free bounds, evidence and wire model shared by the server, UI and offline tests. */
public final class OceanCanvasPhysicalHealth {
    public static final int MAX_CHUNKS = 1024;
    public static final int PAGE_SIZE = 8;
    private static final int WORLD_LIMIT = 30_000_000;
    private OceanCanvasPhysicalHealth() {}

    public enum Status {
        SAMPLE_MATCH("Sample match", 0xFF56BD91),
        DIFFERENCE("Review differences", 0xFFFFAA55),
        PARTIAL("Partial sample", 0xFFE2D275),
        OBSERVED_ONLY("Observed, not verified", 0xFF7AABDD),
        UNKNOWN("Unknown terrain intent", 0xFFA992CD),
        UNLOADED("Unloaded / not checked", 0xFF687480),
        EXCLUDED("Outside / taper", 0xFF444A50),
        ERROR("Read failed", 0xFFFF6B77);

        public final String label;
        public final int color;
        Status(String label, int color) { this.label = label; this.color = color; }
    }

    public record Cell(int x, int z, Status status, String detail) {}
    /** Conservative read-only preflight lens. UNKNOWN is intentional when evidence cannot safely distinguish authored work. */
    public enum ChunkClass { UNTOUCHED_CANVAS, IMPORTED_TERRAIN, PLAYER_MODIFIED, STRUCTURE, PROTECTED_BUILD, VANILLA, UNKNOWN, UNLOADED, OUTSIDE }
    public static ChunkClass chunkClass(Cell cell) {
        if(cell==null)return ChunkClass.UNKNOWN;String d=cell.detail()==null?"":cell.detail();
        if(cell.status()==Status.UNLOADED)return ChunkClass.UNLOADED;if(cell.status()==Status.EXCLUDED)return ChunkClass.OUTSIDE;
        if(d.startsWith("PROTECTED_BUILD:"))return ChunkClass.PROTECTED_BUILD;
        if(d.contains("Structure context"))return ChunkClass.STRUCTURE;
        if(d.startsWith("VANILLA:"))return ChunkClass.VANILLA;
        if(d.startsWith("CUSTOM_OR_MODIFIED:"))return ChunkClass.IMPORTED_TERRAIN;
        if(d.startsWith("CANVAS:")&&cell.status()==Status.SAMPLE_MATCH)return ChunkClass.UNTOUCHED_CANVAS;
        if(d.startsWith("CANVAS:")&&cell.status()==Status.DIFFERENCE)return ChunkClass.PLAYER_MODIFIED;
        return ChunkClass.UNKNOWN;
    }
    public static long count(Report report,ChunkClass c){return report.cells().stream().filter(v->chunkClass(v)==c).count();}
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public Bounds {
            for(int v:new int[]{minX,minZ,maxX,maxZ}) if(v < -WORLD_LIMIT/16 || v > WORLD_LIMIT/16)
                throw new IllegalArgumentException("Chunk coordinates outside the supported world range.");
        }
        public long count() { return ((long)maxX - minX + 1) * ((long)maxZ - minZ + 1); }
        public List<Long> keys() {
            if (minX > maxX || minZ > maxZ || count() < 1 || count() > MAX_CHUNKS)
                throw new IllegalArgumentException("Scan at most " + MAX_CHUNKS + " chunks at once; choose a smaller rectangle.");
            List<Long> out = new ArrayList<>((int)count());
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) out.add(pack(x, z));
            return List.copyOf(out);
        }
    }

    /** Inclusive block bounds, rounded outward to whole chunks, including negative coordinates. */
    public static Bounds blocks(int x0, int z0, int x1, int z1) {
        for (int v : new int[]{x0,z0,x1,z1}) if (v < -WORLD_LIMIT || v > WORLD_LIMIT)
            throw new IllegalArgumentException("Coordinates must be within +/-30,000,000 blocks.");
        return new Bounds(Math.floorDiv(Math.min(x0,x1),16), Math.floorDiv(Math.min(z0,z1),16),
                Math.floorDiv(Math.max(x0,x1),16), Math.floorDiv(Math.max(z0,z1),16));
    }
    public static long pack(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
    public static int x(long key) { return (int)key; }
    public static int z(long key) { return (int)(key >>> 32); }

    public static Status classify(String intent, int checked, int skipped, int differences) {
        if ("UNKNOWN".equals(intent)) return Status.UNKNOWN;
        if (!"CANVAS".equals(intent)) return Status.OBSERVED_ONLY;
        if (differences > 0) return Status.DIFFERENCE;
        return checked > 0 && skipped == 0 ? Status.SAMPLE_MATCH : Status.PARTIAL;
    }

    public record Report(String state, int total, int page, String label, String message, List<Cell> cells) {
        public long count(Status s) { return cells.stream().filter(c -> c.status() == s).count(); }
        public boolean running() { return "RUNNING".equals(state) || "PAUSED".equals(state); }
    }

    /** Bounded plain-text diagnostic suitable for a later explicit file-export command. No world data is written here. */
    public static String diagnostic(Report report) {
        StringBuilder out=new StringBuilder("Ocean Canvas Physical Health diagnostic v1\\n")
                .append("State: ").append(shorten(report.state(),32)).append("\\n")
                .append("Selection: ").append(shorten(report.label(),256)).append("\\n")
                .append("Sampled: ").append(report.cells().size()).append(" / ").append(report.total()).append("\\n")
                .append("Message: ").append(shorten(report.message(),512)).append("\\n");
        for(Status status:Status.values())out.append(status.name()).append(": ").append(report.count(status)).append("\\n");
        for(Cell cell:report.cells()) {
            if(out.length()>262_144){out.append("TRUNCATED: diagnostic evidence cap reached.\\n");break;}
            out.append(cell.x()).append(',').append(cell.z()).append(" ").append(cell.status().name()).append(" | ")
                    .append(shorten(cell.detail().replace('\n',' '),512)).append("\n");
        }
        return out.toString();
    }

    /** All cells for the spatial map, but only eight evidence strings per page. Under 32K UTF-8 bytes. */
    public static String encode(Report report) {
        int page = Math.max(0, Math.min(report.page(), Math.max(0,(report.cells().size()-1)/PAGE_SIZE)));
        StringBuilder out = new StringBuilder("P\t").append(report.state()).append('\t').append(report.total())
                .append('\t').append(page).append('\t').append(b64(shorten(report.label(),80)))
                .append('\t').append(b64(shorten(report.message(),180)));
        for (int i=0; i<report.cells().size(); i++) {
            Cell c=report.cells().get(i);
            out.append("\nM\t").append(c.x()).append('\t').append(c.z()).append('\t').append(c.status().ordinal());
            if (i/PAGE_SIZE==page) out.append("\nD\t").append(i).append('\t').append(b64(shorten(c.detail(),180)));
        }
        return out.toString();
    }

    public static Report decode(String packed) {
        if (packed==null || !packed.startsWith("P\t")) return new Report("IDLE",0,0,"","No physical scan yet.",List.of());
        try {
            String[] lines=packed.split("\n"), h=lines[0].split("\t",-1);
            List<Cell> cells=new ArrayList<>();
            for (int i=1;i<lines.length;i++) {
                String[] f=lines[i].split("\t",-1);
                if ("M".equals(f[0]) && cells.size()<MAX_CHUNKS)
                    cells.add(new Cell(Integer.parseInt(f[1]),Integer.parseInt(f[2]),Status.values()[Integer.parseInt(f[3])],""));
                else if ("D".equals(f[0])) {
                    int n=Integer.parseInt(f[1]); Cell c=cells.get(n);
                    cells.set(n,new Cell(c.x(),c.z(),c.status(),unb64(f[2])));
                }
            }
            return new Report(h[1],Integer.parseInt(h[2]),Integer.parseInt(h[3]),unb64(h[4]),unb64(h[5]),List.copyOf(cells));
        } catch (RuntimeException ex) {
            return new Report("ERROR",0,0,"","Unreadable physical scan response; run a new scan.",List.of());
        }
    }
    private static String shorten(String s,int max) { return s==null?"":s.substring(0,Math.min(max,s.length())); }
    private static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String s) { return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8); }
}

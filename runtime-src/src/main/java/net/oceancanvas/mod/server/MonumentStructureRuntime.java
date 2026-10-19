package net.oceancanvas.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.mod.OceanCanvas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Vanilla-seed/spacing ocean-monument population for completed Canvas terrain. */
public final class MonumentStructureRuntime {
    private static final String RULES_FILE = "oceancanvas-structure-rules.properties";
    private static final int SPACING = 32;
    private static final int SEPARATION = 5;
    private static final int SALT = 10387313;
    private static final RandomSpreadStructurePlacement PLACEMENT =
            new RandomSpreadStructurePlacement(SPACING, SEPARATION, RandomSpreadType.TRIANGULAR, SALT);
    private static Path configDir;
    private static boolean registered;

    private MonumentStructureRuntime() {}

    public static synchronized void register(Path path) {
        if (registered) return;
        configDir = path;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(MonumentStructureRuntime::tick);
        registered = true;
        OceanCanvas.LOGGER.info("(Ocean Canvas Core) MONUMENT-RUNTIME-REGISTERED vanillaSpacing={} vanillaSeparation={} vanillaSalt={} spread=TRIANGULAR",
                SPACING, SEPARATION, SALT);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceancanvas")
                .requires(PermissionPredicates.require(PermissionNode.of("oceancanvas", "command/structures"), PermissionLevel.ADMINS))
                .then(Commands.literal("monument")
                        .then(Commands.literal("populate")
                                .then(Commands.literal("start").executes(c -> start(c.getSource())))
                                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                                .then(Commands.literal("pause").executes(c -> pause(c.getSource())))
                                .then(Commands.literal("resume").executes(c -> resume(c.getSource()))))));
    }

    private static int start(CommandSourceStack source) {
        try {
            if (!"force_on".equals(rule())) return fail(source, "OceanCanvas monument population refused: first run /oceancanvas structurerules set monument force_on.");
            requireFlattenComplete(source.getServer());
            CoreConfig c = CoreConfig.loadOrCreate(configDir);
            ServerLevel world = source.getServer().overworld();
            if (world == null) throw new IOException("overworld unavailable");
            Path path = statePath(source.getServer());
            State desired = State.initial(c, world.getSeed());
            State existing = State.load(path);
            if (existing != null) {
                existing.requireSame(desired);
                if ("COMPLETE".equals(existing.status)) return ok(source, "OceanCanvas monument population is already COMPLETE.");
                existing.withStatus("RUNNING").write(path);
                return ok(source, "OceanCanvas monument population resumed.");
            }
            Files.createDirectories(path.getParent());
            desired.write(path);
            return ok(source, "OceanCanvas monument population started with vanilla 26.2 spacing=32, separation=5, triangular spread, and world-seed placement.");
        } catch (Exception e) { return fail(source, "OceanCanvas monument population could not start: " + safe(e)); }
    }

    private static int status(CommandSourceStack source) {
        try {
            State s = State.load(statePath(source.getServer()));
            if (s == null) return ok(source, "OceanCanvas monument population: IDLE.");
            long total = s.totalCells(), done = s.completedCells();
            double pct = total == 0 ? 100.0 : 100.0 * done / total;
            return ok(source, String.format(Locale.ROOT, "OceanCanvas monument population: %s %,d/%,d grid cells (%.2f%%), placed %,d.", s.status, done, total, pct, s.placed));
        } catch (Exception e) { return fail(source, "Monument status failed: " + safe(e)); }
    }

    private static int pause(CommandSourceStack source) {
        try {
            Path p = statePath(source.getServer()); State s = State.load(p);
            if (s == null) return fail(source, "OceanCanvas monument population is not configured.");
            if (!"COMPLETE".equals(s.status)) s.withStatus("PAUSED").write(p);
            return ok(source, "OceanCanvas monument population paused at a durable grid-cell boundary.");
        } catch (Exception e) { return fail(source, "Monument pause failed: " + safe(e)); }
    }

    private static int resume(CommandSourceStack source) {
        try {
            Path p = statePath(source.getServer()); State s = State.load(p);
            if (s == null) return fail(source, "No prior monument population exists. Use /oceancanvas monument populate start.");
            if ("COMPLETE".equals(s.status)) return ok(source, "OceanCanvas monument population is already COMPLETE.");
            if (!"force_on".equals(rule())) return fail(source, "Monument rule is not force_on.");
            s.withStatus("RUNNING").write(p);
            return ok(source, "OceanCanvas monument population resumed.");
        } catch (Exception e) { return fail(source, "Monument resume failed: " + safe(e)); }
    }

    private static void tick(MinecraftServer server) {
        try {
            Path p = statePath(server); State s = State.load(p);
            if (s == null || !"RUNNING".equals(s.status)) return;
            if (!"force_on".equals(rule())) { s.withStatus("PAUSED").write(p); return; }
            requireFlattenComplete(server);
            CoreConfig c = CoreConfig.loadOrCreate(configDir);
            ServerLevel world = server.overworld(); if (world == null) throw new IOException("overworld unavailable");
            State desired = State.initial(c, world.getSeed()); s.requireSame(desired);
            if (s.completeCursor()) { s.withStatus("COMPLETE").write(p); return; }

            int gridChunkX = Math.multiplyExact(s.regionX, SPACING);
            int gridChunkZ = Math.multiplyExact(s.regionZ, SPACING);
            ChunkPos candidate = PLACEMENT.getPotentialStructureChunk(s.worldSeed, gridChunkX, gridChunkZ);
            long placed = s.placed;
            if (candidate.x() >= s.minChunkX && candidate.x() <= s.maxChunkX && candidate.z() >= s.minChunkZ && candidate.z() <= s.maxChunkZ) {
                int x = candidate.getMinBlockX(); int z = candidate.getMinBlockZ();
                CommandSourceStack cs = server.createCommandSourceStack().withLevel(world).withSuppressedOutput();
                server.getCommands().performPrefixedCommand(cs, "place structure minecraft:monument " + x + " " + c.oceanFloorY() + " " + z);
                placed++;
            }
            State next = s.advance(placed); if (next.completeCursor()) next = next.withStatus("COMPLETE"); next.write(p);
        } catch (Throwable t) {
            OceanCanvas.LOGGER.error("(Ocean Canvas Core) MONUMENT-POPULATION-FATAL action=pause-preserve-progress", t);
            try { Path p = statePath(server); State s = State.load(p); if (s != null && !"COMPLETE".equals(s.status)) s.withStatus("PAUSED").write(p); } catch (Throwable ignored) {}
        }
    }

    private static String rule() throws IOException {
        Properties p = props(configDir.resolve(RULES_FILE));
        return p.getProperty("monument", "default").trim().toLowerCase(Locale.ROOT);
    }

    private static void requireFlattenComplete(MinecraftServer server) throws IOException {
        Path p = server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve("full-canvas").resolve("operation.properties");
        Properties state = props(p);
        if (!"COMPLETE".equalsIgnoreCase(state.getProperty("status", ""))) throw new IOException("full Canvas flatten must be COMPLETE before monument population");
    }

    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas-core").resolve("monument-population").resolve("operation.properties");
    }

    private static Properties props(Path p) throws IOException {
        Properties out = new Properties(); if (Files.exists(p)) try (InputStream in = Files.newInputStream(p)) { out.load(in); } return out;
    }
    private static int ok(CommandSourceStack s, String m) { s.sendSuccess(() -> Component.literal(m), false); return 1; }
    private static int fail(CommandSourceStack s, String m) { s.sendFailure(Component.literal(m)); return 0; }
    private static String safe(Throwable t) { String m = t.getMessage(); return m == null || m.isBlank() ? t.getClass().getSimpleName() : m; }

    private static final class State {
        final String status; final long worldSeed; final int canvasSize, centerX, centerZ;
        final int minChunkX, maxChunkX, minChunkZ, maxChunkZ, minRegionX, maxRegionX, minRegionZ, maxRegionZ, regionX, regionZ;
        final long placed;
        State(String status,long worldSeed,int canvasSize,int centerX,int centerZ,int minChunkX,int maxChunkX,int minChunkZ,int maxChunkZ,
              int minRegionX,int maxRegionX,int minRegionZ,int maxRegionZ,int regionX,int regionZ,long placed) {
            this.status=status;this.worldSeed=worldSeed;this.canvasSize=canvasSize;this.centerX=centerX;this.centerZ=centerZ;
            this.minChunkX=minChunkX;this.maxChunkX=maxChunkX;this.minChunkZ=minChunkZ;this.maxChunkZ=maxChunkZ;
            this.minRegionX=minRegionX;this.maxRegionX=maxRegionX;this.minRegionZ=minRegionZ;this.maxRegionZ=maxRegionZ;
            this.regionX=regionX;this.regionZ=regionZ;this.placed=placed;
        }
        static State initial(CoreConfig c,long seed) {
            var b=OceanCanvasRegionGeometry.checkedCenteredCanvasChunks(c.canvasSize(),c.centerX(),c.centerZ());
            int minRX=Math.floorDiv(b.minX(),SPACING),maxRX=Math.floorDiv(b.maxX(),SPACING);
            int minRZ=Math.floorDiv(b.minZ(),SPACING),maxRZ=Math.floorDiv(b.maxZ(),SPACING);
            return new State("RUNNING",seed,c.canvasSize(),c.centerX(),c.centerZ(),b.minX(),b.maxX(),b.minZ(),b.maxZ(),minRX,maxRX,minRZ,maxRZ,minRX,minRZ,0);
        }
        State withStatus(String v){return new State(v,worldSeed,canvasSize,centerX,centerZ,minChunkX,maxChunkX,minChunkZ,maxChunkZ,minRegionX,maxRegionX,minRegionZ,maxRegionZ,regionX,regionZ,placed);}
        State advance(long newPlaced){int nx=regionX+1,nz=regionZ;if(nx>maxRegionX){nx=minRegionX;nz++;}return new State(status,worldSeed,canvasSize,centerX,centerZ,minChunkX,maxChunkX,minChunkZ,maxChunkZ,minRegionX,maxRegionX,minRegionZ,maxRegionZ,nx,nz,newPlaced);}
        boolean completeCursor(){return regionZ>maxRegionZ;}
        long totalCells(){return (long)(maxRegionX-minRegionX+1)*(long)(maxRegionZ-minRegionZ+1);}
        long completedCells(){long rows=Math.max(0,Math.min((long)(regionZ-minRegionZ),(long)(maxRegionZ-minRegionZ+1)));long n=rows*(maxRegionX-minRegionX+1L);if(regionZ<=maxRegionZ)n+=Math.max(0,Math.min((long)(regionX-minRegionX),(long)(maxRegionX-minRegionX+1)));return Math.min(totalCells(),n);}
        void requireSame(State d)throws IOException{if(worldSeed!=d.worldSeed||canvasSize!=d.canvasSize||centerX!=d.centerX||centerZ!=d.centerZ||minChunkX!=d.minChunkX||maxChunkX!=d.maxChunkX||minChunkZ!=d.minChunkZ||maxChunkZ!=d.maxChunkZ)throw new IOException("monument population geometry/world seed changed");}
        static State load(Path p)throws IOException{Properties x=props(p);if(x.isEmpty())return null;return new State(x.getProperty("status","PAUSED"),Long.parseLong(x.getProperty("worldSeed")),Integer.parseInt(x.getProperty("canvasSize")),Integer.parseInt(x.getProperty("centerX")),Integer.parseInt(x.getProperty("centerZ")),Integer.parseInt(x.getProperty("minChunkX")),Integer.parseInt(x.getProperty("maxChunkX")),Integer.parseInt(x.getProperty("minChunkZ")),Integer.parseInt(x.getProperty("maxChunkZ")),Integer.parseInt(x.getProperty("minRegionX")),Integer.parseInt(x.getProperty("maxRegionX")),Integer.parseInt(x.getProperty("minRegionZ")),Integer.parseInt(x.getProperty("maxRegionZ")),Integer.parseInt(x.getProperty("regionX")),Integer.parseInt(x.getProperty("regionZ")),Long.parseLong(x.getProperty("placed","0")));}
        void write(Path p)throws IOException{Files.createDirectories(p.getParent());Properties x=new Properties();x.setProperty("status",status);x.setProperty("worldSeed",Long.toString(worldSeed));x.setProperty("canvasSize",Integer.toString(canvasSize));x.setProperty("centerX",Integer.toString(centerX));x.setProperty("centerZ",Integer.toString(centerZ));x.setProperty("minChunkX",Integer.toString(minChunkX));x.setProperty("maxChunkX",Integer.toString(maxChunkX));x.setProperty("minChunkZ",Integer.toString(minChunkZ));x.setProperty("maxChunkZ",Integer.toString(maxChunkZ));x.setProperty("minRegionX",Integer.toString(minRegionX));x.setProperty("maxRegionX",Integer.toString(maxRegionX));x.setProperty("minRegionZ",Integer.toString(minRegionZ));x.setProperty("maxRegionZ",Integer.toString(maxRegionZ));x.setProperty("regionX",Integer.toString(regionX));x.setProperty("regionZ",Integer.toString(regionZ));x.setProperty("placed",Long.toString(placed));Path tmp=p.resolveSibling(p.getFileName()+".tmp");try(OutputStream out=Files.newOutputStream(tmp)){x.store(out,"Ocean Canvas monument population state");}try{Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}}
    }
}

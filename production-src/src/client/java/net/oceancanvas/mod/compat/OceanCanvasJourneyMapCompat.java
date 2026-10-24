package net.oceancanvas.mod.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only compatibility boundary for JourneyMap's persisted surface-tile layout.
 *
 * <p>JourneyMap is optional and Ocean Canvas never links against its classes. The only
 * contract used here is persisted coordinate-named PNG tiles. Directory naming has changed
 * across JourneyMap generations, so discovery is content-based and deliberately fail-soft.</p>
 */
public final class OceanCanvasJourneyMapCompat {
    public enum TileState { MISSING, UNCHANGED, LOADED, INVALID, FAILED }
    public record TileRead(TileState state, BufferedImage image, long modifiedMs) { }

    private static volatile String status = "not-located";

    private OceanCanvasJourneyMapCompat() { }

    public static String status() { return status; }

    /** Locate a persisted surface/day tile directory only when ownership can be proven. */
    public static Path locateSurfaceTileDirectory() {
        Path jm = FabricLoader.getInstance().getGameDir().resolve("journeymap").resolve("data");
        String levelName = null;
        boolean singleplayer = false;
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                singleplayer = true;
                levelName = server.getWorldData().getLevelName();
            }
        } catch (RuntimeException failure) {
            OceanCanvasIncidentRecorder.record("client.journeymap-world-name",
                    "could not read integrated-server level name while locating JourneyMap tiles", failure);
        }

        Path root = jm.resolve(singleplayer ? "sp" : "mp");
        List<Path> candidates = discover(root);
        if (candidates.isEmpty()) {
            status = "tiles-not-found";
            return null;
        }

        if (singleplayer) {
            String wanted = normalize(levelName == null ? "" : levelName);
            List<Path> owned = candidates.stream()
                    .filter(p -> !wanted.isBlank() && normalize(p.toString()).contains(wanted))
                    .toList();
            if (owned.isEmpty()) {
                status = "singleplayer-world-not-proven";
                OceanCanvas.LOGGER.warn("(Ocean Canvas) JourneyMap tiles found, but none can be proven to belong to active world '{}'; background disabled.", levelName);
                return null;
            }
            return chooseWithinOwnedWorld(owned, wanted);
        }

        // JourneyMap's MP cache may contain many servers. Without a stable current-server
        // identifier in this optional, reflection-free boundary, selecting the newest tile
        // tree can display another server's map. Only accept MP data when every candidate
        // belongs to one normalized top-level world family; otherwise fail closed.
        List<String> families = candidates.stream().map(p -> multiplayerFamily(root, p)).distinct().toList();
        if (families.size() != 1) {
            status = "multiplayer-world-ambiguous";
            OceanCanvas.LOGGER.warn("(Ocean Canvas) JourneyMap has {} multiplayer map families; active server ownership is ambiguous, so the background is disabled.", families.size());
            return null;
        }
        return chooseWithinOwnedWorld(candidates, "");
    }

    private static List<Path> discover(Path root) {
        List<Path> candidates = new ArrayList<>();
        if (!Files.isDirectory(root)) return candidates;
        try (var walk = Files.walk(root, 10)) {
            walk.filter(Files::isDirectory)
                    .filter(OceanCanvasJourneyMapCompat::containsCoordinateMapTiles)
                    .forEach(candidates::add);
        } catch (IOException failure) {
            OceanCanvasIncidentRecorder.record("client.journeymap-discovery",
                    "could not scan JourneyMap data root " + root, failure);
        }
        return candidates;
    }

    private static Path chooseWithinOwnedWorld(List<Path> candidates, String wanted) {
        Path found = candidates.stream().max(Comparator
                .comparingInt((Path p) -> score(p, wanted))
                .thenComparingLong(OceanCanvasJourneyMapCompat::modified)).orElse(null);
        if (found != null) {
            status = "tiles=" + found;
            OceanCanvas.LOGGER.info("(Ocean Canvas) Map renderer using ownership-bound JourneyMap tiles from {}", found);
        }
        return found;
    }

    static String multiplayerFamily(Path root, Path candidate) {
        try {
            Path relative = root.relativize(candidate);
            return relative.getNameCount() == 0 ? "" : normalize(relative.getName(0).toString());
        } catch (IllegalArgumentException failure) {
            return "";
        }
    }

    /**
     * Read one coordinate tile. Supplying a known mtime lets callers avoid PNG decode when
     * the persisted tile has not changed.
     */
    public static TileRead readTile(Path directory, int tileX, int tileZ, long knownModifiedMs) {
        if (directory == null) return new TileRead(TileState.MISSING, null, 0L);
        Path file = directory.resolve(tileX + "," + tileZ + ".png");
        try {
            if (!Files.isRegularFile(file)) return new TileRead(TileState.MISSING, null, 0L);
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (knownModifiedMs >= 0L && modified == knownModifiedMs) {
                return new TileRead(TileState.UNCHANGED, null, modified);
            }
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) return new TileRead(TileState.INVALID, null, modified);
            return new TileRead(TileState.LOADED, image, modified);
        } catch (java.nio.file.NoSuchFileException missing) {
            // v253.125.31: JourneyMap may atomically rotate/delete a tile between the
            // existence/mtime/image reads. That is an expected cache race, not an
            // incident; the next refresh can discover the replacement normally.
            return new TileRead(TileState.MISSING, null, 0L);
        } catch (IOException | RuntimeException failure) {
            OceanCanvasIncidentRecorder.record("client.journeymap-tile-read",
                    "could not read JourneyMap tile " + tileX + "," + tileZ + " from " + directory, failure);
            return new TileRead(TileState.FAILED, null, 0L);
        }
    }

    private static boolean containsCoordinateMapTiles(Path p) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.png")) {
            int checked = 0;
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".png")) continue;
                n = n.substring(0, n.length() - 4);
                int comma = n.indexOf(',');
                if (comma <= 0 || comma >= n.length() - 1) continue;
                try {
                    Integer.parseInt(n.substring(0, comma));
                    Integer.parseInt(n.substring(comma + 1));
                    return true;
                } catch (NumberFormatException ignored) { }
                if (++checked >= 32) break;
            }
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int score(Path p, String wanted) {
        String raw = p.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        String compact = normalize(raw);
        int score = 0;
        if (!wanted.isBlank() && compact.contains(wanted)) score += 100;
        if (raw.endsWith("/day") || raw.contains("/day/")) score += 80;
        if (compact.contains("dim0") || compact.contains("overworld")) score += 40;
        if (raw.contains("/sp/")) score += 10;
        if (raw.contains("/night") || raw.contains("/topo") || raw.contains("/biome") || raw.contains("/underground")) score -= 60;
        return score;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static long modified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException e) { return 0L; }
    }
}

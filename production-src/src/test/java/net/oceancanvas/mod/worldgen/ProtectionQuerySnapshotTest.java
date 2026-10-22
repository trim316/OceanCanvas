package net.oceancanvas.mod.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class ProtectionQuerySnapshotTest {
    @Test void mutationAndInclusiveBoundaryOracle() {
        OceanCanvasProtectedData data = new OceanCanvasProtectedData();
        List<BoundingBox> permanent = new ArrayList<>();
        Map<BlockPos, BoundingBox> pending = new HashMap<>();
        Random random = new Random(88042);
        for (int step = 0; step < 1200; step++) {
            BlockPos origin = new BlockPos(random.nextInt(24)-12, 0, random.nextInt(24)-12);
            int x = random.nextInt(128)-64, y = random.nextInt(160)-80, z = random.nextInt(128)-64;
            BoundingBox box = new BoundingBox(x,y,z,x+random.nextInt(12),y+random.nextInt(12),z+random.nextInt(12));
            switch (step % 4) {
                case 0 -> { data.protect(box); if (!permanent.contains(box)) permanent.add(box); }
                case 1 -> { data.protectPendingOriginal(origin,box); pending.put(origin,box); }
                case 2 -> { data.clearPendingOriginalProtection(origin); pending.remove(origin); }
                default -> {
                    Set<Long> chunks = Set.of(ChunkPos.pack(Math.floorDiv(x,16),Math.floorDiv(z,16)));
                    data.prepareRegionRewipe(chunks);
                    permanent.removeIf(b -> touches(b,chunks));
                    pending.values().removeIf(b -> touches(b,chunks));
                }
            }
            for (int i=0; i<40; i++) {
                BlockPos p = i==0 ? new BlockPos(box.minX(),box.minY(),box.minZ()) :
                        i==1 ? new BlockPos(box.maxX(),box.maxY(),box.maxZ()) :
                        new BlockPos(random.nextInt(160)-80,random.nextInt(200)-100,random.nextInt(160)-80);
                boolean expected = permanent.stream().anyMatch(b -> b.isInside(p)) || pending.values().stream().anyMatch(b -> b.isInside(p));
                assertEquals(expected,data.isProtected(p.getX(),p.getY(),p.getZ()),"step="+step+" pos="+p);
            }
        }
    }

    @Test void snapshotRetainsMutableBoxGeometryAndReplacements() {
        OceanCanvasProtectedData data = new OceanCanvasProtectedData();
        BoundingBox box = new BoundingBox(-17,-2,-17,-16,2,-16);
        data.protectPendingOriginal(BlockPos.ZERO,box);
        assertTrue(data.isProtected(-17,0,-17));
        box.move(100,0,100);
        assertFalse(data.isProtected(-17,0,-17));
        assertTrue(data.isProtected(83,0,83));
        data.protectPendingOriginal(BlockPos.ZERO,new BoundingBox(1,1,1,1,1,1));
        assertFalse(data.isProtected(83,0,83));
        assertTrue(data.isProtected(1,1,1));
        data.clearPendingOriginalProtection(BlockPos.ZERO);
        assertFalse(data.isProtected(1,1,1));
    }

    private static boolean touches(BoundingBox b, Set<Long> chunks) {
        for (int x=Math.floorDiv(b.minX(),16); x<=Math.floorDiv(b.maxX(),16); x++)
            for (int z=Math.floorDiv(b.minZ(),16); z<=Math.floorDiv(b.maxZ(),16); z++)
                if (chunks.contains(ChunkPos.pack(x,z))) return true;
        return false;
    }
}

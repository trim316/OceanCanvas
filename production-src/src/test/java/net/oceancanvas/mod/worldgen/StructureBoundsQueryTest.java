package net.oceancanvas.mod.worldgen;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StructureBoundsQueryTest {
    private void compare(List<BoundingBox> boxes,int x,int y,int z) {
        var pos=new BlockPos(x,y,z);
        boolean expected=boxes.stream().anyMatch(b->b.isInside(pos));
        assertEquals(expected,OceanCanvasStructureBoundsQuery.contains(boxes,x,y,z));
    }
    @Test void matchesNativeOracleAcrossBoundariesNegativeAndExtremeCoordinates() {
        Random random=new Random(9501);
        for(int set=0;set<400;set++) {
            List<BoundingBox> boxes=new ArrayList<>();
            for(int i=0;i<set%13;i++) {
                int x=random.nextInt(20000)-10000,y=random.nextInt(400)-64,z=random.nextInt(20000)-10000;
                var box=new BoundingBox(x,y,z,x+random.nextInt(90),y+random.nextInt(90),z+random.nextInt(90));boxes.add(box);
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                    compare(boxes,box.minX()+dx,box.minY()+dy,box.minZ()+dz);
                    compare(boxes,box.maxX()+dx,box.maxY()+dy,box.maxZ()+dz);
                }
            }
            for(int i=0;i<200;i++)compare(boxes,random.nextInt(22000)-11000,random.nextInt(600)-100,random.nextInt(22000)-11000);
        }
        var extremes=List.of(new BoundingBox(Integer.MIN_VALUE,-64,-2,Integer.MAX_VALUE,400,2));
        compare(extremes,Integer.MIN_VALUE,-64,0);compare(extremes,Integer.MAX_VALUE,400,2);compare(extremes,0,401,0);
    }
    @Test void observesMutableBoxCoordinatesAndListReplacement() {
        var box=new BoundingBox(-4,-3,-2,4,3,2);var boxes=new ArrayList<BoundingBox>();boxes.add(box);
        compare(boxes,0,0,0);box.move(100,20,-100);compare(boxes,0,0,0);compare(boxes,100,20,-100);
        boxes.set(0,new BoundingBox(-1,-1,-1,1,1,1));compare(boxes,0,0,0);boxes.clear();compare(boxes,0,0,0);
    }
}

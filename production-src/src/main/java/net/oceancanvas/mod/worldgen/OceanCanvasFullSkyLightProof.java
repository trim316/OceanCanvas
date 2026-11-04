package net.oceancanvas.mod.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Full selected-height native SKY fixed point, including deep and opaque cells. */
final class OceanCanvasFullSkyLightProof {
    interface Probe extends OceanCanvasBlockLightProof.Probe {}
    record Anomaly(int column,int y,int actual,int expected) {}
    private final int minY,maxY;
    private int column,y,samples,bad;
    private boolean direct=true;
    private BlockState above=Blocks.AIR.defaultBlockState();
    private final List<Anomaly> first=new ArrayList<>();
    OceanCanvasFullSkyLightProof(int minY,int maxY) {
        if(minY>=maxY) throw new IllegalArgumentException();
        this.minY=minY;this.maxY=maxY;this.y=maxY-1;
    }
    private void next(BlockState current) {
        above=current;
        if(--y<minY) { column++;y=maxY-1;above=Blocks.AIR.defaultBlockState();direct=true; }
    }
    boolean advance(int readBudget,BooleanSupplier yield,Probe p) {
        if(readBudget<14) throw new IllegalArgumentException("complete native stencil requires14reads");
        int used=0;
        while(column<256) {
            if(yield.getAsBoolean()) return false;
            if(!p.selected(column)) { column++;y=maxY-1;above=Blocks.AIR.defaultBlockState();direct=true;continue; }
            if(used+14>readBudget||!p.resident(column,y)) return false;
            BlockState current=p.state(column,y);used++;
            // Derive native direct-source membership from actual edges, not cached metadata.
            direct=direct&&!OceanCanvasUpperSkyProof.blocksDirectSky(above,current);
            if(p.protectedCell(column,y)) { next(current);continue; }
            int actual=p.light(column,y);used++;
            if(actual<0||actual>15) throw new IllegalArgumentException("native sky range");
            int expected=direct?15:0;
            int opacity=Math.max(1,current.getLightDampening());
            if(!direct&&15-opacity>0) for(Direction d:Direction.values()) {
                int neighbor=p.neighborLight(column,y,d);used++;
                if(neighbor<0||neighbor>15) throw new IllegalArgumentException("native sky range");
                if(neighbor-opacity<=expected) continue;
                BlockState source=p.neighborState(column,y,d);used++;
                expected=Math.max(expected,OceanCanvasBlockLightProof.incoming(source,current,d.getOpposite(),neighbor));
            }
            samples++;
            if(actual!=expected) { bad++;if(first.size()<64) first.add(new Anomaly(column,y,actual,expected)); }
            next(current);
        }
        return true;
    }
    boolean complete(){return column==256;}
    int samples(){return samples;}
    int anomalies(){return bad;}
    List<Anomaly> firstAnomalies(){return List.copyOf(first);}
}

package net.oceancanvas.mod.worldgen;
import java.util.function.IntPredicate;
/** One invocation's conservative native palette answers. False is the only skipping authority. */
final class OceanCanvasLavaSectionMask {
 private final int first;private final boolean[] possible;
 OceanCanvasLavaSectionMask(int minY,int maxY,IntPredicate nativeMaybeLava){first=Math.floorDiv(minY,16);int last=Math.floorDiv(maxY,16);possible=new boolean[maxY<minY?0:last-first+1];for(int i=0;i<possible.length;i++)possible[i]=nativeMaybeLava.test(first+i);}
 int negativeSectionCount(){int count=0;for(boolean value:possible)if(!value)count++;return count;}
 boolean mayContainLava(int y){long i=(long)Math.floorDiv(y,16)-first;return i<0||i>=possible.length||possible[(int)i];}
}

package net.oceancanvas.probe.receipts;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.oceancanvas.mod.worldgen.*;
import net.oceancanvas.probe.receipts.mixin.LavaSpreadInvoker;
public final class ReceiptFixture implements ModInitializer {
 private boolean injected,reported;
 public static boolean hold(){return Boolean.getBoolean("oceancanvas.fixture.holdReceipts");}
 @Override public void onInitialize(){
  ServerLifecycleEvents.SERVER_STARTED.register(server->{if(hold()){var w=server.overworld();w.getChunk(6,4);OceanCanvasSurfaceFlattener.prepareForAquaticDecorationMutation(w,new net.minecraft.world.level.ChunkPos(6,4));}});
  ServerTickEvents.END_SERVER_TICK.register(server->{
   if(!hold())return;var w=server.overworld();var data=OceanCanvasCellRepairData.get(w);
   BlockPos nativeCell=new BlockPos(104,62,72),operatorCell=new BlockPos(105,62,72),protectedCell=new BlockPos(106,62,72);
   if(!injected&&OceanCanvasActiveTerrainOperationBridge.pregenIncludesChunk(6,4)){
    if(data.at(nativeCell.asLong())==null){
    if(!w.getBlockState(nativeCell).equals(Blocks.WATER.defaultBlockState())||!w.getBlockState(operatorCell).equals(Blocks.WATER.defaultBlockState()))throw new IllegalStateException("Expected original canonical source-water baseline");
    ((LavaSpreadInvoker)(Object)Fluids.LAVA).receipt$nativeSpread(w,nativeCell,w.getBlockState(nativeCell),Direction.DOWN,Fluids.LAVA.defaultFluidState());
    w.setBlock(operatorCell,Blocks.STONE.defaultBlockState(),3);
    OceanCanvasProtectedData.get(w).protect(new BoundingBox(106,62,72,106,62,72));w.setBlock(protectedCell,Blocks.STONE.defaultBlockState(),3);
    if(data.at(nativeCell.asLong())==null||data.at(operatorCell.asLong())!=null||data.at(protectedCell.asLong())!=null)throw new IllegalStateException("Native receipt provenance boundary failed");
    }
    String id=net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(w).identity().worldUuid(),op=net.oceancanvas.mod.pregen.OceanCanvasJobState.get(w).cellRepairOperationId();
    System.out.println("CELL-RECEIPT-FIXTURE-READY world="+id+" operation="+op+" native="+nativeCell.asLong()+" operator="+operatorCell.asLong()+" protected="+protectedCell.asLong());injected=true;
   }
   if(injected&&!reported&&data.at(operatorCell.asLong())!=null){
    if(!w.getBlockState(nativeCell).is(Blocks.STONE)||!w.getBlockState(operatorCell).is(Blocks.STONE)||data.at(protectedCell.asLong())!=null)throw new IllegalStateException("Pre-consume checkpoint boundary failed");
    System.out.println("CELL-RECEIPT-CHECKPOINT-READY nativePending=true operatorPending=true protectedAuthority=false");reported=true;
   }
  });
 }
}

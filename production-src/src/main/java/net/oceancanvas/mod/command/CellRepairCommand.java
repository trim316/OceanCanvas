package net.oceancanvas.mod.command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.worldgen.*;
import java.util.concurrent.*;

/** Explicit, permission-checked attestation of one exact legacy-cell recovery. */
public final class CellRepairCommand {
 public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
  dispatcher.register(Commands.literal("oceancanvas").then(Commands.literal("cell-repair")
   .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.pregen",2))
   .then(Commands.literal("status").executes(c->{var w=c.getSource().getLevel();c.getSource().sendSuccess(()->Component.literal("CELL-REPAIR-IDENTITY world="+net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData.get(w).identity().worldUuid()+" operation="+net.oceancanvas.mod.pregen.OceanCanvasJobState.get(w).cellRepairOperationId()),false);return 1;}))
   .then(Commands.literal("import").then(Commands.argument("sha256",StringArgumentType.word()).then(Commands.literal("confirm").executes(c->submit(c.getSource(),StringArgumentType.getString(c,"sha256"))))))));
 }
 private static int submit(CommandSourceStack source,String digest){
  try{OceanCanvasOperatorCellEvidence.requireDigest(digest);}catch(IllegalArgumentException e){source.sendFailure(Component.literal(e.getMessage()));return 0;}
  var world=source.getLevel();var server=world.getServer();
  var imports=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("oceancanvas-cell-repair-imports");
  var receiptRoot=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("oceancanvas-cell-receipts");
  // Read bounded evidence and force its immutable exact bytes off the tick thread.
  CompletableFuture.supplyAsync(()->{
   try {byte[] bytes=OceanCanvasOperatorCellEvidence.read(imports,digest);var e=OceanCanvasOperatorCellEvidence.decode(bytes,digest);
    var root=receiptRoot;
    return new Object[]{e,bytes,root};
   }catch(Exception e){throw new CompletionException(e);}
  }).thenCompose(parts->{var e=(OceanCanvasOperatorCellEvidence)parts[0];return new OceanCanvasReceiptFileBarrier(ForkJoinPool.commonPool()).publish((java.nio.file.Path)parts[2],e.world(),e.operation(),(byte[])parts[1]).thenApply(path->e);})
   .whenComplete((e,error)->server.execute(()->{
    if(net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.shouldPreempt(server))return;
    if(error!=null){source.sendFailure(Component.literal("CELL-REPAIR-IMPORT-REFUSED evidence="+digest+" reason=evidence-read-or-write-failed"));return;}
    if(OceanCanvasSurfaceFlattener.importOperatorCellReceipt(world,e,digest))source.sendSuccess(()->Component.literal("CELL-REPAIR-IMPORT-ACCEPTED evidence="+digest+" action=bounded-repair-and-reproof-pending"),false);
    else source.sendFailure(Component.literal("CELL-REPAIR-IMPORT-REFUSED evidence="+digest+" reason=world-operation-current-state-scope-or-protection"));
   }));
  source.sendSuccess(()->Component.literal("CELL-REPAIR-IMPORT-PENDING evidence="+digest),false);return 1;
 }
}

package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.project.OceanCanvasDeepHealthService;
import net.oceancanvas.mod.project.OceanCanvasHealthService;

public final class HealthCommand {
    private HealthCommand() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var health=Commands.literal("health")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(ctx -> shallow(ctx.getSource()));
        health.then(Commands.literal("deep")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(ctx -> deep(ctx.getSource())));
        health.then(Commands.literal("physical")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(ctx -> physical(ctx.getSource(),"physical_here"))
                .then(Commands.literal("nearby").executes(ctx -> physical(ctx.getSource(),"physical_nearby")))
                .then(Commands.literal("region").then(Commands.argument("name",com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> physical(ctx.getSource(),"physical_region:"+com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"name")))))
                .then(Commands.literal("cancel").executes(ctx -> physical(ctx.getSource(),"physical_cancel"))));
        health.then(Commands.literal("performance")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(ctx -> performance(ctx.getSource())));
        health.then(Commands.literal("structures")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(ctx -> structures(ctx.getSource(), null))
                .then(Commands.literal("region")
                        .then(Commands.argument("name",com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> structures(ctx.getSource(),com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"name"))))));
        health.then(Commands.literal("repair-metadata")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.protect",2))
                .executes(ctx -> repair(ctx.getSource())));
        dispatcher.register(Commands.literal("oceancanvas").then(health));
        // Retain the old root-level spelling as a permission-checked compatibility alias.
        dispatcher.register(Commands.literal("oceancanvas").then(Commands.literal("repair-metadata")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.protect",2))
                .executes(ctx -> repair(ctx.getSource()))));
    }

    private static int physical(CommandSourceStack source,String action){
        var player=source.getPlayer();
        if(player==null){source.sendFailure(Component.translatable("oceancanvas.health.physical.not_player"));return 0;}
        if("physical_cancel".equals(action)){
            net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.cancel(player);
            source.sendSuccess(()->Component.translatable("oceancanvas.health.physical.cancelled"),false);
        } else {
            String message=net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.start(player,action);
            source.sendSuccess(()->Component.literal(message+" Results: Map > Health > Physical Health."),false);
        }
        return 1;
    }

    private static int shallow(CommandSourceStack source){
        var s=OceanCanvasHealthService.snapshot(source.getLevel());
        double pct=s.expectedChunks()==0?0D:s.flattenedChunks()*100.0D/s.expectedChunks();
        source.sendSuccess(()->Component.literal(String.format(java.util.Locale.ROOT,
                "Canvas Health: %s - ~%,d / %,d chunks flattened (%.2f%%); regions %d (%d protected); pregen in-flight %d; regeneration pending %d; active job %s. %s",
                s.state(),s.flattenedChunks(),s.expectedChunks(),pct,s.definedRegions(),s.protectedRegions(),
                s.outstandingPregen(),s.pendingRegeneration(),s.activeJob(),s.detail())),false);
        return 1;
    }
    private static int deep(CommandSourceStack source){
        var r=OceanCanvasDeepHealthService.scan(source.getLevel(),12);
        source.sendSuccess(()->Component.literal("Metadata Health: "+r.healthyExplicit()+"/"+r.explicitStates()+" explicit states have consistent seals; "+r.mismatches()+" contradiction(s). Physical terrain is not verified. Use Map > Health > Physical Health for block/biome samples."),false);
        for(var f:r.findings())source.sendSuccess(()->Component.literal(" - chunk "+f.chunkX()+","+f.chunkZ()+": "+f.detail()),false);
        if(r.truncated())source.sendSuccess(()->Component.translatable("oceancanvas.health.deep.truncated"),false);
        var assets=net.oceancanvas.mod.project.OceanCanvasAssetIntegrityService.scan(source.getLevel(),12);
        source.sendSuccess(()->Component.literal("Plan/Project Health: "+assets.planningObjects()+" Plan objects, "+assets.terrainAssets()+" Terrain Assets, "+assets.projects()+" Projects, "+assets.tasks()+" tasks; "+assets.issues()+" broken link(s)."),false);
        for(var issue:assets.findings())source.sendSuccess(()->Component.literal(" - "+issue.kind()+" "+issue.id()+": "+issue.detail()),false);
        return r.mismatches()==0&&assets.healthy()?1:0;
    }

    private static int performance(CommandSourceStack source){
        var r=net.oceancanvas.mod.project.OceanCanvasPerformanceEngine.summary(source.getLevel());
        source.sendSuccess(()->Component.literal("Performance: I/O="+r.io().state()+" (oldest load "+r.io().oldestLoadMs()+"ms), baseline="+r.baseline().state()+", long-run="+r.decay().state()+", black-box samples="+r.blackBoxSamples()+"."),false);
        for(var b:r.budgets())source.sendSuccess(()->Component.literal(" - budget "+b.id()+" "+b.state()+": "+String.format(java.util.Locale.ROOT,"%.2f",b.observed())+b.unit()+" (budget "+String.format(java.util.Locale.ROOT,"%.2f",b.limit())+b.unit()+")"),false);
        for(var phase:r.phases())source.sendSuccess(()->Component.literal(" - phase "+phase.phase()+": ema="+String.format(java.util.Locale.ROOT,"%.2f",phase.emaMs())+"ms, max="+String.format(java.util.Locale.ROOT,"%.2f",phase.maxMs())+"ms, samples="+phase.samples()),false);
        for(var hot:r.hotRegions())source.sendSuccess(()->Component.literal(" - hot cell chunks ["+hot.minChunkX()+","+hot.minChunkZ()+"]..["+hot.maxChunkX()+","+hot.maxChunkZ()+"] active="+hot.active()+", deferred="+hot.deferred()+", avgLatency="+String.format(java.util.Locale.ROOT,"%.0f",hot.averageLatencyMs())+"ms, max="+hot.maxLatencyMs()+"ms"),false);
        return 1;
    }

    private static int structures(CommandSourceStack source,String regionName){
        var zones=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(source.getLevel());
        java.util.List<net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.Zone> selected;
        if(regionName==null){selected=zones.all();}
        else {
            var z=zones.zoneByName(regionName);
            if(z==null){source.sendFailure(Component.literal("Unknown region: "+regionName));return 0;}
            selected=java.util.List.of(z);
        }
        if(selected.isEmpty()){source.sendSuccess(()->Component.literal("Structure Integrity: no regions are defined."),false);return 1;}
        int failures=0;
        for(var zone:selected){
            var report=net.oceancanvas.mod.worldgen.OceanCanvasStructureIntegrityScanner.scanRegion(source.getLevel(),zone);
            boolean healthy=report.healthy();
            if(!healthy)failures++;
            source.sendSuccess(()->Component.literal("Structure Integrity ["+report.scope()+"]: "+(healthy?"PASS":"NOT VERIFIED")+"; issues="+report.issues()+", incompleteKinds="+report.incompleteKinds()+"."),false);
            for(var kind:report.kinds()){
                source.sendSuccess(()->Component.literal(" - "+kind.kind().displayName()+" "+kind.rule()+": expected="+kind.expectedCandidates()+", verified="+kind.verifiedCandidates()+", starts="+kind.validStarts()+", ineligible="+kind.ineligibleCandidates()+", forbiddenStarts="+kind.forbiddenStarts()+", missing="+kind.missingStarts()+", metadata="+kind.metadataErrors()+", vertical="+kind.verticalErrors()+", orphanRefs="+kind.orphanReferences()+", forbiddenRefs="+kind.forbiddenReferences()+", offGrid="+kind.offGridStarts()+", unloaded="+kind.unloadedEvidence()+(kind.truncated()?", TRUNCATED":"")),false);
                for(var finding:kind.findings())source.sendSuccess(()->Component.literal("   "+finding.severity()+" "+finding.code()+" @ "+finding.chunkX()+","+finding.chunkZ()+": "+finding.detail()),false);
            }
        }
        return failures==0?1:0;
    }
    private static int repair(CommandSourceStack source){
        var r=OceanCanvasDeepHealthService.repairMetadata(source.getLevel());
        source.sendSuccess(()->Component.literal("Health metadata repair: "+r.repaired()+" repaired, "+r.skipped()+" skipped. "+r.detail()),false);
        return 1;
    }
}

package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.project.*;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

/** Fast, metadata-only built-in confidence test. Never generates/loads chunks or mutates the world. */
public final class SelfTestCommand {
    private SelfTestCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){dispatcher.register(Commands.literal("oceancanvas").then(Commands.literal("selftest").executes(c->run(c.getSource()))));}
    private static int run(CommandSourceStack source){var w=source.getLevel();int failures=0;
        var cfg=OceanCanvasConfig.get();if(cfg.canvasSize()<=0)failures+=fail(source,"Config canvas size is not positive.");
        var project=OceanCanvasProjectData.get(w);if(!project.schemaSupportedForMutation())failures+=fail(source,"Project schema is newer than this build.");
        var plan=OceanCanvasPlanningData.get(w);if(plan.schema()>OceanCanvasPlanningData.CURRENT_SCHEMA)failures+=fail(source,"Planning schema is newer than this build.");
        var workspace=OceanCanvasWorkspaceData.get(w);if(workspace.schema()>OceanCanvasWorkspaceData.CURRENT_SCHEMA)failures+=fail(source,"Workspace schema is newer than this build.");
        var library=OceanCanvasPlanLibraryData.get(w);if(library.schema()>OceanCanvasPlanLibraryData.CURRENT_SCHEMA)failures+=fail(source,"Plan library schema is newer than this build.");
        var links=OceanCanvasAssetIntegrityService.scan(w,8);if(!links.healthy())failures+=fail(source,"Plan/Project link integrity has "+links.issues()+" issue(s).");
        java.util.Set<String> names=new java.util.HashSet<>();for(var z:OceanCanvasPlayerZones.get(w).all())if(!names.add(z.name().toLowerCase(java.util.Locale.ROOT)))failures+=fail(source,"Duplicate region name: "+z.name());
        var terrain=OceanCanvasDeepHealthService.scan(w,4);if(terrain.mismatches()>0)failures+=fail(source,"Terrain metadata has "+terrain.mismatches()+" seal contradiction(s).");
        for(var check:net.oceancanvas.mod.project.OceanCanvasHarnessService.regressionResults(w))if(check.verdict()==net.oceancanvas.mod.project.OceanCanvasHarnessService.Verdict.FAIL)failures+=fail(source,check.id()+" "+check.title()+": "+check.evidence());
        int f=failures;source.sendSuccess(()->Component.literal(f==0?"Ocean Canvas self-test PASS: configuration, schemas, region identities, terrain metadata, Plan/Project links, and low-cost reliability invariants are internally consistent.":"Ocean Canvas self-test FAIL: "+f+" check(s) need attention. No terrain was changed."),false);return failures==0?1:0;}
    private static int fail(CommandSourceStack source,String text){source.sendFailure(Component.literal("Self-test: "+text));return 1;}
}

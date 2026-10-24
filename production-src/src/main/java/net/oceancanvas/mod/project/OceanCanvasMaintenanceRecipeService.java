package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OC-F243 maintenance recipes.  Recipes may sequence evidence-producing steps, but any destructive
 * step intentionally stops at AWAITING_CONFIRMATION and delegates execution to the normal Regions
 * preview/token/lifecycle surface.  A recipe can never smuggle world mutation around preflight.
 */
public final class OceanCanvasMaintenanceRecipeService {
    private OceanCanvasMaintenanceRecipeService(){}
    private static final Set<String> READ_ONLY=Set.of("HEALTH_SCAN","BOUNDARY_AUDIT","POI_SCAN","STEWARDSHIP_REPORT","DIAGNOSTIC_BUNDLE","BASELINE_CHECK");
    private static final Set<String> DESTRUCTIVE=Set.of("PREGEN","REWIPE","RESTORE");

    public static OceanCanvasP1W3Data.MaintenanceRecipe add(ServerLevel world,String name,String rawSteps){
        if(name==null||name.isBlank())throw new IllegalArgumentException("recipe name is required");
        List<String> steps=parseSteps(rawSteps);if(steps.isEmpty())throw new IllegalArgumentException("recipe needs at least one step");
        var d=OceanCanvasP1W3Data.get(world);var r=new OceanCanvasP1W3Data.MaintenanceRecipe(d.newId("recipe"),name,steps,System.currentTimeMillis());d.putRecipe(r);return r;
    }
    public static boolean remove(ServerLevel world,String id){return OceanCanvasP1W3Data.get(world).removeRecipe(id);}

    public static String run(ServerLevel world,String id){
        var d=OceanCanvasP1W3Data.get(world);var r=d.recipe(id);if(r==null)throw new IllegalArgumentException("unknown recipe");
        int start=0;var active=d.recipeRun();if(active!=null&&active.recipeId().equals(r.id())&&!"COMPLETE".equals(active.state()))start=Math.max(0,Math.min(r.steps().size(),active.stepIndex()));
        if(OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning()){d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),start,"BLOCKED","A terrain operation is already active.",System.currentTimeMillis()));return "Recipe blocked: a terrain operation is active.";}
        List<String> evidence=new ArrayList<>();
        for(int i=start;i<r.steps().size();i++){
            String step=r.steps().get(i),kind=kind(step),arg=arg(step);
            if(DESTRUCTIVE.contains(kind)){
                if(arg.isBlank()){d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),i,"BLOCKED",kind+" requires a region name.",System.currentTimeMillis()));return kind+" step requires a region name.";}
                var z=OceanCanvasPlayerZones.get(world).zoneByName(arg);if(z==null){d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),i,"BLOCKED","Unknown region "+arg,System.currentTimeMillis()));return "Unknown recipe region: "+arg;}
                var preview=OceanCanvasOperationPreviewService.region(world,kind,z);String detail=kind+" "+arg+" · "+preview.summary();
                d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),i,"AWAITING_CONFIRMATION",detail,System.currentTimeMillis()));
                return "Recipe paused at destructive step "+kind+" for "+arg+". Review/confirm it in Regions using the normal server-authored preview, then Resume Recipe.";
            }
            evidence.add(executeReadOnly(world,kind,arg));
            d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),i+1,"RUNNING",String.join(" | ",evidence),System.currentTimeMillis()));
        }
        d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),r.steps().size(),"COMPLETE",String.join(" | ",evidence),System.currentTimeMillis()));
        return "Recipe complete: "+r.name()+". "+String.join(" | ",evidence);
    }

    /** Resumes only after no terrain operation is active; this never asserts the destructive step succeeded. */
    public static String resumeAfterConfirmedStep(ServerLevel world,String id){
        var d=OceanCanvasP1W3Data.get(world);var r=d.recipe(id);var run=d.recipeRun();if(r==null||run==null||!run.recipeId().equals(r.id()))return "No paused run for that recipe.";
        if(!"AWAITING_CONFIRMATION".equals(run.state()))return run.state().equals("COMPLETE")?"Recipe is already complete.":"Recipe is not waiting for confirmation.";
        if(OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning())return "The confirmed terrain operation is still active; resume after it reaches a terminal state.";
        // Advance one step only.  We explicitly do not infer success; operation history remains the authority.
        d.setRecipeRun(new OceanCanvasP1W3Data.RecipeRun(r.id(),run.stepIndex()+1,"RUNNING","User resumed after reviewing the lifecycle result for "+r.steps().get(run.stepIndex()),System.currentTimeMillis()));
        return run(world,id);
    }

    private static String executeReadOnly(ServerLevel world,String kind,String arg){
        return switch(kind){
            case "HEALTH_SCAN" -> {var h=OceanCanvasDeepHealthService.scan(world,64);OceanCanvasP1W3Service.refreshHealthInbox(world);yield "health mismatches="+h.mismatches();}
            case "BOUNDARY_AUDIT" -> {var r=OceanCanvasP1W3Service.boundaryDriftAudit(world);yield "boundary suspicious="+r.suspiciousChunks();}
            case "POI_SCAN" -> {var r=OceanCanvasP1W3Service.poiIntegrity(world,128);yield "POI/structure suspicious="+r.suspicious();}
            case "STEWARDSHIP_REPORT" -> {try{var r=OceanCanvasStewardshipReportService.write(world,"maintenance recipe");yield "stewardship bytes="+r.bytes();}catch(Exception e){yield "stewardship failed="+e.getMessage();}}
            case "DIAGNOSTIC_BUNDLE" -> {try{var r=OceanCanvasDiagnosticBundle.create(world);yield "bundle="+r.supportCode();}catch(Exception e){yield "bundle failed="+e.getMessage();}}
            case "BASELINE_CHECK" -> OceanCanvasP1W3Service.vanillaBaselineStatus(world,4);
            default -> throw new IllegalArgumentException("unsupported maintenance step "+kind);
        };
    }

    public static List<String> parseSteps(String raw){
        if(raw==null)return List.of();var out=new ArrayList<String>();for(String token:raw.split("[>;]")){String s=token.trim();if(s.isBlank())continue;String k=kind(s);if(!READ_ONLY.contains(k)&&!DESTRUCTIVE.contains(k))throw new IllegalArgumentException("unsupported recipe step: "+s);out.add(k+(arg(s).isBlank()?"":":"+arg(s)));if(out.size()>=24)break;}return List.copyOf(out);
    }
    private static String kind(String s){int i=s.indexOf(':');return (i<0?s:s.substring(0,i)).trim().toUpperCase(Locale.ROOT).replace(' ','_');}
    private static String arg(String s){int i=s.indexOf(':');return i<0?"":s.substring(i+1).trim();}
}

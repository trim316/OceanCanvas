package net.oceancanvas.mod.project;

import java.util.List;

/** Built-in reusable mission blueprints. Data-only: applying one remains a separate explicit server action. */
public final class OceanCanvasMissionTemplates {
    private OceanCanvasMissionTemplates(){}
    public record Step(String phase,String task,String purpose,List<String> checklist){}
    public record Mission(String id,String name,String summary,List<Step> steps){}
    private static Step step(String phase,String task,String purpose,String... checks){return new Step(phase,task,purpose,List.of(checks));}
    public static final List<Mission> ALL=List.of(
        new Mission("continent","Build a Continent","A complete large-landmass workflow from geographic intent through survival-world verification.",List.of(
            step("Scope & Coastlines","Lock continent scope and coastline language","Confirm scale, reference alignment, and the geographic story before terrain work.","Confirm Minecraft bounds and blocks-per-pixel","Review coastline silhouette at map scale","Lock reference opacity and registration"),
            step("Gaea Terrain","Approve continent Gaea terrain","Create the primary heightfield and drainage logic.","Export selective masks","Review mountain and watershed hierarchy","Approve the exact Gaea revision"),
            step("WorldPainter Refinement","Approve continent WorldPainter pass","Choose Minecraft materials, biomes, gradients, and playable transitions.","Verify sea level and orientation","Review biome/material gradients","Approve the exact WorldPainter revision"),
            step("Placement","Package continent placement","Prepare traceable offsets and placement artifacts for the survival world.","Record placement origin and offsets","Verify revision hashes","Prepare Litematica/placement handoff"),
            step("Survival Verification","Verify continent in the world","Inspect the result in context without silently changing the design record.","Compare Blueprint and placed terrain","Run Physical Health samples","Record deviations and follow-up work"))),
        new Mission("archipelago","Create an Archipelago","Coordinate island hierarchy, navigation, ecological variety, and repeated placement handoffs.",List.of(
            step("Island Composition","Design island hierarchy","Establish hero islands, supporting islands, channels, and negative space.","Choose focal island","Verify channel widths","Review silhouette at full Canvas scale"),
            step("Gaea Island Terrain","Approve archipelago Gaea terrain","Develop distinct erosion and elevation identities while keeping the group coherent.","Check island elevation variety","Validate drainage to coast","Approve Gaea revision"),
            step("WorldPainter Ecology","Approve island ecology pass","Assign biome/material identities and navigable coast transitions.","Review beach and cliff gradients","Check biome variety","Approve WorldPainter revision"),
            step("Tiled Placement","Package tiled island placement","Keep each island tile registered to one shared coordinate system.","Record tile bounds","Verify seams and offsets","Prepare placement artifacts"),
            step("Voyage Review","Verify archipelago experience","Review sightlines, travel routes, structures, and survival access between islands.","Sail primary routes","Inspect Blueprint alignment","Capture follow-up tasks"))),
        new Mission("watershed","Design a River Basin","Treat the watershed as one connected system from ridges to river mouth.",List.of(
            step("Watershed Plan","Lock watershed and river network","Define divides, catchments, tributaries, lakes, and river mouth intent.","Validate downstream links","Review catchment coverage","Resolve hydrology findings"),
            step("Gaea Hydrology","Approve hydrologic heightfield","Build terrain whose drainage supports the planned network.","Check flow accumulation","Review lake outlets","Approve Gaea revision"),
            step("WorldPainter Banks","Approve river material pass","Refine channels, banks, floodplains, wetlands, and biome transitions.","Review bank gradients","Check channel continuity","Approve WorldPainter revision"),
            step("Network Placement","Package watershed placement","Place the basin as a coherent registered system rather than disconnected river pieces.","Record common origin","Verify tile seams","Prepare placement artifacts"),
            step("Flow Audit","Verify the basin in-world","Inspect continuity and compare placed drainage against the explicit Plan network.","Walk source-to-mouth sample","Review deviation heatmap","Record repair tasks"))),
        new Mission("settlement","Build a Settlement Region","Coordinate site planning, terrain preparation, schematics, routes, and staged survival construction.",List.of(
            step("Site Plan","Lock settlement plan","Define districts, roads, landmarks, terrain relationships, and expansion space.","Confirm district footprints","Review road hierarchy","Reserve landmark viewpoints"),
            step("Terrain Preparation","Approve settlement terrain","Shape buildable grades and drainage without flattening away geographic character.","Review slopes and retaining areas","Check drainage routes","Approve terrain revision"),
            step("Material Language","Lock palette and biome context","Coordinate WorldPainter materials with the intended architecture and surroundings.","Choose terrain palette","Review biome transitions","Approve WorldPainter revision"),
            step("Schematic Package","Package settlement schematics","Register build sections and origins for controlled survival placement.","Record schematic origins","Verify section boundaries","Prepare Litematica handoff"),
            step("Construction Review","Run staged survival build","Use milestones and ready work to build in dependency order and capture deviations.","Verify access routes","Review landmark sightlines","Record next construction phase")))
    );
    public static Mission byId(String id){for(var m:ALL)if(m.id().equalsIgnoreCase(id==null?"":id.trim()))return m;return null;}
}

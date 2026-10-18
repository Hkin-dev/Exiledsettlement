package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;

/** Fresh deterministic test inputs; all construction after setup uses ordinary resident AI. */
public final class Engineering91Acceptance {
    public static final BlockPos CORE=new BlockPos(0,160,0);
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static void enabled(){if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");}
    private static int height(int x,int z){
        if(Math.abs(x)<=4 && Math.abs(z)<=4)return 159;
        int y=159+(int)Math.round(x*.10+z*.04+5*Math.sin(x*.13)+4*Math.sin(z*.17));
        if(x>18)y+=5;if(x<-22)y-=4;
        if((x+17)*(x+17)+(z-15)*(z-15)<36)y-=9;
        return y;
    }
    public static String start(ServerLevel l)throws Exception{
        enabled();var data=SettlementSavedData.get(l);
        if(!data.settlements().isEmpty())throw new IllegalStateException("FRESH_WORLD_REQUIRED");
        // No player save is opened or copied. This airspace belongs to this explicit new test world.
        int min=999,max=-999;long edits=0;
        for(int x=-56;x<=56;x++)for(int z=-56;z<=86;z++){
            int top=height(x,z);min=Math.min(min,top);max=Math.max(max,top);
            for(int y=136;y<=top;y++){
                var at=new BlockPos(x,y,z);
                if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED "+at);
                l.setBlock(at,y==top?Blocks.GRASS_BLOCK.defaultBlockState():y>=top-2?Blocks.DIRT.defaultBlockState():Blocks.STONE.defaultBlockState(),2);edits++;
            }
            if((x*17+z*13)%127==0 && Math.abs(x)+Math.abs(z)>10){l.setBlock(new BlockPos(x,top+1,z),Blocks.SHORT_GRASS.defaultBlockState(),2);edits++;}
        }
        for(var root:List.of(new BlockPos(-10,height(-10,-16)+1,-16),new BlockPos(18,height(18,20)+1,20),new BlockPos(-22,height(-22,47)+1,47))){
            for(int h=0;h<5;h++){l.setBlock(root.above(h),Blocks.OAK_LOG.defaultBlockState(),2);edits++;}
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){l.setBlock(root.offset(x,5,z),Blocks.OAK_LEAVES.defaultBlockState(),2);edits++;}
        }
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);edits++;
        var created=data.createOrRecover(CORE,UUID.randomUUID(),"Engineering91 fresh complex terrain");
        if(!created.success())throw new IllegalStateException(created.reason());var s=created.value();
        // Level/land are explicit test inputs; no completed building, warehouse or stock is injected.
        s.upgradeLevel();s.upgradeLevel();s.setCreativeCoreInstalled(true);
        s.terrain().enabled=true;s.autoRoads().enabled=true;s.decorations().enabled=false;
        for(int radius=0;radius<=6;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)
            if(!s.ownsChunk(x,z))s.claimChunk(x,z);
        var types=List.of(BuildingType.HOUSE,BuildingType.WAREHOUSE,BuildingType.HOUSE_2,BuildingType.TOOL_WORKSHOP,
                BuildingType.HOUSE_3,BuildingType.HUNTING_LODGE,BuildingType.FORESTER_LODGE,BuildingType.SAWMILL,BuildingType.HOUSE,BuildingType.SCHOOL);
        int[][] centers={{-34,-34},{0,-34},{34,-34},{-34,0},{34,0},{-34,34},{0,34},{34,34},{-20,68},{20,68}};
        var manifest=new JsonObject();var sites=new JsonArray();
        for(int i=0;i<types.size();i++){
            int x=centers[i][0],z=centers[i][1];var samples=new ArrayList<Integer>();
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)samples.add(height(x+dx,z+dz));Collections.sort(samples);
            var anchor=new BlockPos(x,samples.get(12)+1,z);
            var placed=BlueprintService.placeBlueprint(l,s.ownerId(),types.get(i),anchor,Direction.NORTH,false,null);
            if(!placed.success())throw new IllegalStateException("SITE "+i+" "+types.get(i)+" "+placed.reason());
            var b=placed.value();var entry=new JsonObject();entry.addProperty("id",b.id().toString());entry.addProperty("type",types.get(i).name());
            entry.addProperty("anchor",anchor.toShortString());entry.addProperty("facing",b.facing().getName());entry.addProperty("templateVersion",b.templateVersion());sites.add(entry);
        }
        int index=0;for(var c:s.citizens()){
            c.setManualAssignment(CitizenProfession.WORKER,null);
            c.updateProjection(null,CORE.offset(-3+index%3,0,2+index/3));index++;
        }
        CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,6);data.touch();
        manifest.addProperty("settlement",s.id().toString());manifest.addProperty("terrainSetupEdits",edits);
        manifest.addProperty("minSurface",min);manifest.addProperty("maxSurface",max);manifest.addProperty("residents",s.citizens().size());
        manifest.addProperty("normalLifeAndSchedules",true);manifest.addProperty("forceWork",false);manifest.addProperty("creativeCore",true);
        manifest.addProperty("originalSaveReadOrWritten",false);manifest.add("buildings",sites);
        Files.createDirectories(SaveAcceptanceService.directory());Files.writeString(SaveAcceptanceService.file("engineering91-inputs",".json"),JSON.toJson(manifest),StandardOpenOption.CREATE_NEW);
        SaveAcceptanceService.export(l,s,"engineering91-initial");
        return "OK "+s.id()+"; 10 formal validated templates; terrain range="+min+".."+max+"; "+index+" workers; no completed structures/stock or forced work; setup edits="+edits;
    }
    public static String inspect(ServerLevel l){
        enabled();var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var out=new JsonObject();
        out.addProperty("gameTime",l.getGameTime());out.addProperty("dayTime",l.getDayTime());out.addProperty("settlement",s.id().toString());
        out.addProperty("activeBlueprints",s.blueprints().stream().filter(b->!b.cancellationQueued()).count());
        out.addProperty("remainingTerrainSteps",s.terrain().projects.values().stream().filter(p->!p.cancelled && !p.completed).mapToInt(TerrainProject::remaining).sum());
        int terrainShown=0;
        var sites=new JsonArray();for(var b:s.blueprints())if(!b.cancellationQueued()){
            if(b.terrainProject()!=null && terrainShown++>=25)continue;
            var j=new JsonObject();j.addProperty("id",b.id().toString());j.addProperty("type",b.type().name());
            j.addProperty("terrain",b.terrainProject()!=null);j.addProperty("remaining",PublicWorkService.remaining(l,b));
            j.addProperty("allowed",TerrainService.allowed(s,b));j.addProperty("assigned",WorkTaskService.assigned(s,b.id()));
            if(b.terrainProject()==null){
                int left=0;var examples=new JsonArray();
                for(var p:s.terrain().dependingOn(b.id()))if(!p.cancelled && !p.completed)
                    for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && WorkTaskService.essential(s,p,b,p.steps.get(i).position())){
                        left++;if(examples.size()>=8)continue;var step=p.steps.get(i);var child=s.blueprint(p.id);
                        var e=new JsonObject();e.addProperty("at",step.position().toShortString());
                        e.addProperty("actual",l.getBlockState(step.position()).toString());e.addProperty("goal",step.target().toString());
                        e.addProperty("guard",child==null?"MISSING_CHILD":TerrainService.guard(l,s,child,step));
                        e.addProperty("layerAllowed",child!=null && TerrainLayerService.allowed(l,s,child,step));examples.add(e);
                    }
                j.addProperty("foundationRemaining",left);j.add("foundationExamples",examples);
                j.addProperty("clearanceRemaining",b.clearanceTargets().size());
                var cuts=new JsonArray();var template=BlueprintTemplates.create(b);
                b.clearanceTargets().keySet().stream().sorted(Comparator.comparingInt((BlockPos p)->p.getY()).reversed()).limit(16).forEach(at->{
                    var e=new JsonObject();var finalCell=template.placementAt(at);
                    e.addProperty("at",at.toShortString());e.addProperty("actual",l.getBlockState(at).toString());
                    e.addProperty("mode",finalCell==null?"NO_TEMPLATE_CELL":finalCell.mode().name());
                    e.addProperty("goal",finalCell==null?"":finalCell.state().toString());
                    e.addProperty("required",BuildingPlacementRules.requiresClearance(template,at));
                    e.addProperty("layerAllowed",BlueprintService.clearanceLayerAllowed(l,b,at));cuts.add(e);
                });j.add("clearanceExamples",cuts);
                int missingStructure=0;var missingExamples=new JsonArray();
                for(var goal:template.placements()){
                    if(goal.mode()==BlueprintPlacementMode.CLEARANCE || goal.mode()==BlueprintPlacementMode.OPTIONAL_SUPPORT)continue;
                    var actual=l.getBlockState(goal.position());
                    if(BlueprintService.matchesPlacedState(actual,goal.state()))continue;
                    missingStructure++;
                    if(missingExamples.size()<8){var e=new JsonObject();e.addProperty("at",goal.position().toShortString());
                        e.addProperty("actual",actual.toString());e.addProperty("goal",goal.state().toString());
                        e.addProperty("mode",goal.mode().name());missingExamples.add(e);}
                }
                j.addProperty("missingStructureCells",missingStructure);j.add("missingStructureExamples",missingExamples);
                var dispatch = new JsonArray();
                var pending = BlueprintService.pendingPlacements(l,b,Set.of(),b.markerPosition());
                j.addProperty("dispatchPending",pending.size());
                for(var c:s.citizens()) {
                    var row=new JsonObject();row.addProperty("worker",c.id().toString());
                    row.addProperty("eligible",pending.stream().filter(p->CitizenWorkService.constructionDispatchReady(l,s,c,b,p.placement())).count());
                    row.addProperty("emptySlots",c.inventory().stream().filter(net.minecraft.world.item.ItemStack::isEmpty).count());
                    row.addProperty("deliverable",java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE).map(slot->CitizenWorkService.constructionDeliveryCount(s,c,slot)).sum());dispatch.add(row);
                }
                j.add("dispatch",dispatch);
            }
            if(b.terrainProject()!=null){j.addProperty("name",b.terrainProject().name);j.addProperty("cursor",b.terrainProject().cursor);}
            sites.add(j);
        }
        out.add("sites",sites);out.addProperty("formalBuildings",s.buildings().stream().filter(b->b.type()!=BuildingType.WAGON).count());
        out.addProperty("publicPhase",s.terrain().publicGround.phase);out.addProperty("publicCursor",s.terrain().publicGround.cursor);
        out.addProperty("roadStatus",s.autoRoads().status);out.add("roadSearch",AutoRoadPlanner.progress(l,s));
        out.add("groundLayers",TerrainLayerService.observation(l,s));
        var roads=new JsonArray();for(var r:s.autoRoads().routes.values())if(!r.suppressed){var j=new JsonObject();j.addProperty("id",r.id.toString());
            j.addProperty("progress",r.progress);j.addProperty("total",r.cells.size());j.addProperty("connected",r.accessConnected);j.addProperty("status",r.status);
            j.addProperty("blocker",RoadEarthworkService.accessBlocker(l,s,r));roads.add(j);}
        out.add("roads",roads);var workers=new JsonArray();for(var c:s.citizens()){var j=new JsonObject();j.addProperty("id",c.id().toString());
            j.addProperty("state",c.workState().name());j.addProperty("position",c.lastPosition().toShortString());j.addProperty("target",String.valueOf(c.targetPosition()));
            j.addProperty("site",String.valueOf(c.targetBlueprintId()));j.addProperty("activity",c.activity().name());j.addProperty("reason",c.workFailure());
            j.addProperty("sourceBuilding",String.valueOf(c.sourceBuildingId()));
            j.addProperty("resumeSite",String.valueOf(c.constructionResumeId()));
            var entity=CitizenProjectionService.findProjection(l,c);j.addProperty("entityLoaded",entity!=null);
            if(entity!=null){j.addProperty("actualPosition",entity.navigationFeetPosition().toShortString());
                j.addProperty("navigationDiagnostic",entity.settlementNavigationDiagnostics());
                j.addProperty("pitEscape",PitEscapeService.observation(l,s,c,entity));
                j.add("coreApproach",WorkerAccessService.observation(l,s,c,entity,s.corePosition()));
                j.addProperty("coreVisible",SettlementCoreInteractionService.canReceiveSupply(l,s,entity));
                j.addProperty("bodyTemperature",c.thermal().body);j.addProperty("thermalWarming",c.thermal().warming);
                j.addProperty("garment",c.thermal().garment.toString());}
            j.addProperty("localAdvance",String.valueOf(c.workerAccess.advance));
            j.add("terrainWorkFaces",CitizenWorkService.terrainWorkFaces(l,s,c,entity));workers.add(j);}
        out.add("workers",workers);return new Gson().toJson(out);
    }
    private Engineering91Acceptance(){}
    /** Detached inventory fixture; never attaches a citizen or item to this world. */
    public static String inventoryInvariants(ServerLevel l){
        enabled();var c=new CitizenRecord(UUID.randomUUID(),"Detached inventory invariant",CORE);
        c.startTask(CitizenWorkState.TO_BLUEPRINT,CORE.above(),null,UUID.randomUUID());
        c.insertIntoInventory(new net.minecraft.world.item.ItemStack(Blocks.STONE,64));
        c.insertIntoInventory(new net.minecraft.world.item.ItemStack(Blocks.STONE,64));
        c.selectActiveSlot(0);c.updateCarriedItem(new net.minecraft.world.item.ItemStack(Blocks.STONE,30));
        c.selectActiveSlot(1);c.updateCarriedItem(new net.minecraft.world.item.ItemStack(Blocks.STONE,34));
        var named=new net.minecraft.world.item.ItemStack(Blocks.STONE,10);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep components"));
        c.insertIntoInventory(named);
        if(!c.compactInventory() || c.activeSlot()!=0 || c.inventoryItem(0).getCount()!=64
                || !c.inventoryItem(1).isEmpty() || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(c.inventoryItem(2),named)
                || c.inventory().stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum()!=74 || c.compactInventory())
            throw new IllegalStateException("STACK_CONSERVATION_OR_SELECTED_SLOT_FAILED");
        var restored=CitizenRecord.load(c.save(l.registryAccess()),l.registryAccess());
        if(restored.activeSlot()!=0 || restored.inventoryItem(0).getCount()!=64
                || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(restored.inventoryItem(2),named)
                || restored.inventory().stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum()!=74)
            throw new IllegalStateException("SAVE_COMPONENTS_FAILED");
        formalIndexInvariants(l);
        routeMergeInvariants(l);
        return "PASS: total 74 preserved, unequal components retained, active slot follows merged stack, idempotent, save/reload; formal protection view follows add/remove, same-count replacement, terrain conversion and reload; identical road geometry preserves paid indices/dependencies, distinct height/corridor retained, save/reload; detached fixtures only";
    }
    private static void routeMergeInvariants(ServerLevel l){
        var s=new SettlementRecord(UUID.randomUUID(),"Detached road merge",CORE,UUID.randomUUID());var parent=UUID.randomUUID();
        var cells=List.of(CORE.west(5).below(),CORE.west(6).below());var end=cells.getLast();
        var a=new AutoRoadState.Route(parent,end,cells);var b=new AutoRoadState.Route(parent,end,cells);
        var differentHeight=new AutoRoadState.Route(parent,end.above(),cells.stream().map(BlockPos::above).toList());
        var differentCorridor=new AutoRoadState.Route(parent,end,List.of(cells.getFirst().north(),end));
        a.completed.set(0);a.advance();b.completed.set(1);b.constructionGradeFixed=true;
        s.autoRoads().routes.put("first",a);s.autoRoads().routes.put("different-height",differentHeight);
        s.autoRoads().routes.put("different-corridor",differentCorridor);s.autoRoads().routes.put("same",b);
        var p=new TerrainProject(UUID.randomUUID(),"Detached paid ownership",cells.getFirst(),List.of());p.dependencies.add(b.id);s.terrain().index(p);
        if(AdaptiveRoadService.coalesceIdenticalRoutes(s)!=1 || a.progress!=2 || !a.cells.equals(cells)
                || !b.suppressed || !a.id.equals(b.mergedInto) || differentHeight.suppressed || differentCorridor.suppressed
                || !a.constructionGradeFixed || a.accessConnected || p.dependencies.contains(b.id) || !p.dependencies.contains(a.id)
                || AdaptiveRoadService.coalesceIdenticalRoutes(s)!=0)throw new IllegalStateException("ROAD_MERGE_GEOMETRY_OR_OWNERSHIP_FAILED");
        var restored=new AutoRoadState();restored.load(s.autoRoads().save());
        if(restored.routes.get("first").progress!=2 || !restored.routes.get("same").suppressed
                || restored.routes.get("different-height").suppressed || restored.routes.get("different-corridor").suppressed)
            throw new IllegalStateException("ROAD_MERGE_SAVE_FAILED");
    }
    private static void formalIndexInvariants(ServerLevel l){
        var s=new SettlementRecord(UUID.randomUUID(),"Detached formal index",CORE,UUID.randomUUID());
        var first=s.addBlueprint(BuildingType.HOUSE,CORE,Direction.NORTH,s.ownerId(),false);
        if(!s.formalBlueprints().equals(List.of(first)))throw new IllegalStateException("FORMAL_ADD_FAILED");
        s.removeBlueprint(first.id());var second=s.addBlueprint(BuildingType.HOUSE,CORE.east(20),Direction.NORTH,s.ownerId(),false);
        if(!s.formalBlueprints().equals(List.of(second)))throw new IllegalStateException("FORMAL_REPLACE_FAILED");
        var work=s.addBlueprint(BuildingType.HOUSE,CORE.west(20),Direction.NORTH,s.ownerId(),false);
        s.formalBlueprints();
        var project=new TerrainProject(work.id(),"Detached terrain conversion",work.anchor(),List.of());
        s.terrain().index(project);work.withTerrainProject(project);
        if(!s.formalBlueprints().equals(List.of(second)))throw new IllegalStateException("FORMAL_CONVERSION_FAILED");
        var loaded=SettlementRecord.load(s.save(l.registryAccess()),l.registryAccess());
        if(loaded.formalBlueprints().size()!=1 || !loaded.formalBlueprints().getFirst().id().equals(second.id()))
            throw new IllegalStateException("FORMAL_RELOAD_FAILED");
        loaded.removeBlueprint(second.id());if(!loaded.formalBlueprints().isEmpty())throw new IllegalStateException("FORMAL_REMOVE_FAILED");
        var third=s.addBlueprint(BuildingType.HOUSE,CORE.east(40),Direction.NORTH,s.ownerId(),false);
        work.withTerrainProject(null);
        if(!s.formalBlueprints().equals(List.of(second,work,third)))throw new IllegalStateException("FORMAL_REVERSE_ORDER_FAILED");
        s.removeBlueprint(work.id());work.withTerrainProject(project);work.withTerrainProject(null);
        if(!s.formalBlueprints().equals(List.of(second,third)))throw new IllegalStateException("REMOVED_KIND_CALLBACK_FAILED");
    }
}

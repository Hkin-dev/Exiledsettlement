package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_independentareas")
@PrefixGameTestTemplate(false)
public final class IndependentRegionGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event){if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(IndependentRegionGameTests.class);}
    private static final List<BuildingType> TYPES=List.of(BuildingType.FARM_FIELD,BuildingType.MELON_FIELD,BuildingType.TREE_FARM,BuildingType.PASTURE_AREA);
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,BlockPos at){
        void clean(){s.deactivate();for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)h.getLevel().setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,false);}
    }
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(520000+serial++*512,5,0));
        for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)l.setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-6,-2,-6),at.offset(26,7,16))){l.getChunkAt(p);l.setBlock(p,p.getY()==at.getY()-2?Blocks.STONE.defaultBlockState():p.getY()==at.getY()-1?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        var s=SettlementSavedData.get(l).createOrRecover(at.west(5),UUID.randomUUID(),"Independent area regression").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        l.setDayTime(6000);return new Fixture(h,s,at);
    }
    private static BlueprintRecord place(Fixture f,BuildingType type,BlockPos at){
        int size=Math.max(7,type.minimumAreaSize());
        var result=ProductionRegionService.place(f.h().getLevel(),type,at,at.offset(size-1,0,size-1),Direction.NORTH,f.s().ownerId(),null);
        f.h().assertTrue(result.success(),"Accepted independent plot rejected: "+result.reason());return result.value();
    }
    private static void prepare(Fixture f,BlueprintRecord site){
        var r=site.region();while(!r.surveyed)r.survey(f.h().getLevel(),f.s()::contains,64);
        r.layoutReady=true;r.prepared=true;r.preparationCursor=r.cells.size();r.preparationRetries.clear();r.animalSafety.putInt("Phase",7);
    }
    /** Template placement here is fixture setup; only the final missing fence is resident work. */
    private static void builtFixture(Fixture f,BlueprintRecord site){
        prepare(f,site);for(var p:site.region().template().placements())f.h().getLevel().setBlock(p.position(),p.state(),2);
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void adjacentNewPlotsNeverMergeOrActivatePartialGround(GameTestHelper h){
        for(var type:TYPES){var f=fixture(h);var first=place(f,type,f.at());int size=Math.max(7,type.minimumAreaSize());var second=place(f,type,f.at().east(size));
            for(int tick=0;tick<24;tick++)ProductionRegionService.tickLevel(h.getLevel());
            h.assertTrue(f.s().blueprint(first.id())==first && f.s().blueprint(second.id())==second,"Adjacent pending areas were merged: "+type);
            h.assertTrue(first.region().cells.size()==size*size && second.region().cells.size()==size*size,"Selection footprint changed");
            h.assertTrue(first.region().workingBuilding==null && second.region().workingBuilding==null && f.s().buildings().stream().noneMatch(b->b.type()==type),"Partial ground activated production: "+type);
            h.assertTrue(first.region().workingBuilding==null && second.region().workingBuilding==null,"Independent plot gained a merge target");f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void adjacentBuiltPlotKeepsIdentityAndOverlapIsRejected(GameTestHelper h){
        for(var type:TYPES){var f=fixture(h);var first=place(f,type,f.at());builtFixture(f,first);
            var done=BlueprintService.completeAfterFootingCleanup(h.getLevel(),SettlementSavedData.get(h.getLevel()),f.s(),first);
            h.assertTrue(done.success(),"Fixture completion failed: "+done.reason());var b=done.value();var saved=b.region().save();
            var neighbor=place(f,type,f.at().east(Math.max(7,type.minimumAreaSize())));
            int before=f.s().blueprints().size();var bad=ProductionRegionService.place(h.getLevel(),type,f.at(),f.at().offset(6,0,6),Direction.NORTH,f.s().ownerId(),null);
            h.assertTrue(!bad.success() && f.s().blueprints().size()==before,"Overlapping same-type placement changed work");
            for(int tick=0;tick<24;tick++)ProductionRegionService.tickLevel(h.getLevel());
            h.assertTrue(b.active() && f.s().building(b.id())==b && b.region().cells.equals(ProductionRegion.load(saved).cells),"Existing building merged or changed its footprint");
            h.assertTrue(f.s().blueprint(neighbor.id())==neighbor && neighbor.region().workingBuilding==null,"New neighbour absorbed the existing building");
            h.assertTrue(f.s().terrain().projects.values().stream().noneMatch(p->p.name.contains("融合")),"Neighbour spawned internal fence demolition");f.clean();
        }h.succeed();
    }
    private static void lastFenceHandover(GameTestHelper h,BuildingType type){
        var f=fixture(h);var l=h.getLevel();var site=place(f,type,f.at());builtFixture(f,site);
        var fence=site.region().template().placements().stream().filter(p->p.state().is(Blocks.OAK_FENCE)).findFirst().orElseThrow();var at=fence.position();l.setBlock(at,Blocks.AIR.defaultBlockState(),2);
        f.s().decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(at).toLong());
        f.s().terrain().suppressed.add(at.asLong());
        h.assertTrue(!BlueprintService.constructionComplete(l,site),"Missing final fence counted as complete");
        ProductionRegionService.tickLevel(l);h.assertTrue(site.region().workingBuilding==null,"Production registered before the final fence");
        var c=f.s().citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.insertIntoInventory(new ItemStack(Items.OAK_FENCE,1));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(at.getX()-1.5,at.getY(),at.getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
        h.onEachTick(()->CitizenWorkService.advanceSiteConstruction(l,SettlementSavedData.get(l),f.s(),c,actor));
        h.startSequence().thenWaitUntil(()->h.assertTrue(f.s().blueprint(site.id())==null,"Final fence did not finish real construction: "+c.workFailure()))
            .thenExecute(()->{
                var b=f.s().buildings().stream().filter(v->v.type()==type).findFirst().orElseThrow();
                for(var part:site.region().template().placements())if(part.state().is(Blocks.OAK_FENCE)) {
                    var actual=l.getBlockState(part.position());var resolved=actual;
                    for(var direction:Direction.values())resolved=resolved.updateShape(direction,l.getBlockState(part.position().relative(direction)),l,part.position(),part.position().relative(direction));
                    h.assertTrue(actual.equals(resolved),"Completed production region retained stale fence connections: "+type);
                }
                h.assertTrue(l.getBlockState(at).is(Blocks.OAK_FENCE) && !c.hasInventoryItem(i->i.is(Items.OAK_FENCE)),"Final fence was not physically built and paid");
                ProductionRegionService.validate(l,b);h.assertTrue(b.operational(),"Finished independent building did not start production: "+type+"/"+b.status());
                h.assertTrue(b.markDamaged(at),"Real building repair position was not recorded");
                h.assertTrue(ProductionRegionService.constructionPending(f.s(),b),"New construction did not immediately stop production eligibility");
                ProductionRegionService.validate(l,b);h.assertTrue(!b.operational(),"Existing producer remained operational during construction");
                b.clearRepairPosition(at);ProductionRegionService.validate(l,b);h.assertTrue(b.operational(),"Completed handover failed to resume production");
                actor.discard();f.clean();
            }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void allFourAreasSurveyUnderAutomaticDecorationBan(GameTestHelper h){
        for(var type:TYPES){var f=fixture(h);var at=f.at();
            for(int x=0;x<2;x++)for(int z=0;z<2;z++)f.s().decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(at.offset(x*16,0,z*16)).toLong());
            var site=place(f,type,at);int size=Math.max(7,type.minimumAreaSize());
            for(int i=0;i<32;i++)ProductionRegionService.tickLevel(h.getLevel());
            h.assertTrue(site.region().surveyed && site.region().cells.size()==size*size,"Automatic decor ban discarded accepted building cells: "+type);
            h.assertTrue(site.id().equals(RegionWorkScope.owner(f.s(),site.id())),"Building preparation has no owner: "+type);
            f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void allFourAreasTakeOwnPreparationOutOfPublicCarrier(GameTestHelper h){
        for(var type:TYPES){var f=fixture(h);var l=h.getLevel();var site=place(f,type,f.at());prepare(f,site);
            var at=f.at().offset(2,2,2);var outside=f.at().east(22);var paid=f.at().offset(3,-1,2);
            l.setBlock(at,Blocks.DIRT.defaultBlockState(),2);l.setBlock(outside,Blocks.DIRT.defaultBlockState(),2);
            var carrier=f.s().addBlueprint(BuildingType.HOUSE,at,at.above(96),Direction.NORTH,f.s().ownerId(),false);
            var cut=new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState());
            var old=new TerrainProject(carrier.id(),"Mixed public work",at,List.of(cut,new TerrainProject.Step(paid,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(outside,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));
            old.publicGround=true;old.mark(1);carrier.withTerrainProject(old);f.s().terrain().index(old);
            h.assertTrue(!RegionWorkScope.same(f.s(),old,RegionWorkScope.owner(f.s(),site.id())),"A formal area is being treated as public ownership: "+type);
            var own=ProductionRegionService.publish(l,f.s(),site.id(),"Own preparation",at,List.of(cut));
            h.assertTrue(own!=null && site.id().equals(own.regionOwner) && !own.publicGround && !own.road,"Preparation reused public carrier: "+type);
            h.assertTrue(old.abandoned.get(0) && old.finished.get(1) && !old.abandoned.get(1) && !old.finished.get(2) && !old.abandoned.get(2),"Transfer lost paid/outside evidence");
            f.s().decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(at).toLong());f.s().terrain().suppressed.add(at.asLong());
            h.assertTrue(TerrainService.guard(l,f.s(),f.s().blueprint(own.id),own.steps.getFirst()).isEmpty(),"Building rejects own clearance: "+type);
            h.assertTrue(TerrainService.guard(l,f.s(),carrier,cut).equals("PROTECTED_AREA"),"Public work gained authority inside building");
            h.assertTrue(TerrainProject.load(own.save()).regionOwner.equals(site.id()),"Owner not saved");f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void wronglySharedSavedWorkReturnsToOwnPreparation(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var site=place(f,BuildingType.FARM_FIELD,f.at());prepare(f,site);
        var at=f.at().offset(2,2,2);var outside=f.at().east(22);var paid=at.below(3);
        var carrier=f.s().addBlueprint(BuildingType.HOUSE,at,at.above(96),Direction.NORTH,f.s().ownerId(),false);
        var old=new TerrainProject(carrier.id(),"Previously shared public work",at,List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState()),new TerrainProject.Step(paid,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(outside,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));
        old.publicGround=true;old.dependencies.add(site.id());old.mark(1);carrier.withTerrainProject(old);f.s().terrain().index(old);
        ProductionRegionService.reconcileOwnership(f.s(),site);
        h.assertTrue(!old.dependencies.contains(site.id()) && old.abandoned.get(0) && old.finished.get(1) && !old.abandoned.get(1) && !old.abandoned.get(2),"Ownership repair cancelled paid/outside work");
        h.assertTrue(site.region().preparationRetries.stream().anyMatch(i->site.region().ordered().get(i).ground().getX()==at.getX() && site.region().ordered().get(i).ground().getZ()==at.getZ()),"Own unfinished preparation lost");
        h.assertTrue(!RegionConstructionStages.facilitiesReady(f.s(),site),"Ownership repair falsely completed farm");f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void narrowFarmDoesNotExpandOrLoseCropSpace(GameTestHelper h){
        for(var type:List.of(BuildingType.FARM_FIELD,BuildingType.MELON_FIELD)){
            var f=fixture(h);var result=ProductionRegionService.place(h.getLevel(),type,f.at(),f.at().south(3).above(8),Direction.NORTH,f.s().ownerId(),null);
            h.assertTrue(result.success(),"A narrow explicit farm was rejected");var r=result.value().region();
            while(!r.surveyed)r.survey(h.getLevel(),f.s()::contains,64);
            h.assertTrue(r.selectionMargin==0 && r.cells.size()==4 && r.usableArea()==4,"A narrow plot expanded or became all fence");
            h.assertTrue(r.cells.values().stream().allMatch(c->c.ground().getX()==f.at().getX() && c.ground().getY()==f.at().getY()-1),"Narrow plot escaped its confirmed footprint/plane");
            f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void maintenanceCannotChangeAreaOrAbsorbAnotherBuilding(GameTestHelper h){
        for(var type:TYPES){
            var f=fixture(h);var l=h.getLevel();var data=SettlementSavedData.get(l);var first=place(f,type,f.at());builtFixture(f,first);
            var b=BlueprintService.completeAfterFootingCleanup(l,data,f.s(),first).value();
            var second=place(f,type,f.at().east(Math.max(7,type.minimumAreaSize())));builtFixture(f,second);
            var neighbour=BlueprintService.completeAfterFootingCleanup(l,data,f.s(),second).value();var before=neighbour.region().save();
            var repair=f.s().addAreaBlueprint(type,b.position(),b.position().above(96),b.facing(),f.s().ownerId(),false,b.minCorner(),b.maxCorner());
            var saved=b.region().save();saved.putUUID("WorkingBuilding",b.id());saved.putUUID("MergeTarget",neighbour.id());
            var sources=new net.minecraft.nbt.ListTag();var source=new net.minecraft.nbt.CompoundTag();source.putUUID("From",neighbour.id());source.putUUID("To",b.id());sources.add(source);saved.put("Sources",sources);
            var r=ProductionRegion.load(saved);r.groundMaintenance=true;repair.withRegion(r);
            h.assertTrue(!r.save().contains("Sources") && !r.save().contains("MergeTarget"),"Cancelled fusion directives survived loading");
            var outside=neighbour.region().cells.entrySet().iterator().next();r.cells.put(outside.getKey(),outside.getValue());r.invalidate();
            h.assertTrue(!com.hkin.exiledsettlement.terrain.TerrainService.allowed(f.s(),repair),"Changed-scope repair reached worker dispatch");
            h.assertTrue(!ProductionRegionService.completeMaintenance(l,data,f.s(),repair).success() && f.s().blueprint(repair.id())==repair,"Repair expanded into its neighbour");
            r.cells.remove(outside.getKey());r.flatY++;r.invalidate();
            h.assertTrue(!ProductionRegionService.completeMaintenance(l,data,f.s(),repair).success(),"Repair silently changed selected height");
            r.flatY--;r.invalidate();
            var key=r.cells.keySet().iterator().next();var cell=r.cells.get(key);
            r.cells.put(key,new ProductionRegion.Cell(cell.ground().above(),cell.use(),cell.production(),cell.type()));r.invalidate();
            h.assertTrue(!ProductionRegionService.completeMaintenance(l,data,f.s(),repair).success(),"Repair fitted one cell to a different elevation");
            r.cells.put(key,cell);r.invalidate();
            h.assertTrue(ProductionRegionService.completeMaintenance(l,data,f.s(),repair).success(),"Original-area repair failed");
            h.assertTrue(neighbour.active() && f.s().building(neighbour.id())==neighbour && before.equals(neighbour.region().save()),"Repair absorbed, disabled or changed a neighbour");
            h.assertTrue(f.s().terrain().projects.values().stream().noneMatch(p->p.name.contains("融合")),"Repair generated interior fence demolition");f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void timedHandoverAlsoWaitsForPreparationAndRealDependencies(GameTestHelper h){
        for(var type:TYPES){var f=fixture(h);var l=h.getLevel();var site=place(f,type,f.at());builtFixture(f,site);
            site.region().prepared=false;
            BlueprintService.finishTimedIfReady(l,SettlementSavedData.get(l),f.s(),site);
            h.assertTrue(f.s().blueprint(site.id())==site,"Timed work bypassed preparation: "+type);
            site.region().prepared=true;
            var at=f.at().offset(2,2,2);l.setBlock(at,Blocks.STONE.defaultBlockState(),2);
            var child=ProductionRegionService.publish(l,f.s(),site.id(),"Required clearance",at,List.of(new com.hkin.exiledsettlement.terrain.TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));
            h.assertTrue(child!=null && child.remaining()==1,"Fixture failed to publish required child work");
            BlueprintService.finishTimedIfReady(l,SettlementSavedData.get(l),f.s(),site);
            h.assertTrue(f.s().blueprint(site.id())==site && child.remaining()==1 && l.getBlockState(at).is(Blocks.STONE),"Timed handover bypassed real unfinished child work: "+type);
            f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=180)
    public static void realForestMaintenancePausesProductionAndPreservesSapling(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var site=place(f,BuildingType.TREE_FARM,f.at());builtFixture(f,site);
        var done=BlueprintService.completeAfterFootingCleanup(l,SettlementSavedData.get(l),f.s(),site);
        h.assertTrue(done.success(),"Completed forest fixture failed");var b=done.value();var r=b.region();
        var soil=r.cells.values().stream().filter(c->c.use()==ProductionRegion.Use.FOREST).findFirst().orElseThrow().ground();
        r.gradeOrigins.put(ProductionRegion.key(soil.getX(),soil.getZ()),soil.getY()-2);l.setBlock(soil.above(),Blocks.OAK_SAPLING.defaultBlockState(),2);
        var fence=r.template().placements().stream().filter(p->p.state().is(Blocks.OAK_FENCE)).findFirst().orElseThrow();l.setBlock(fence.position(),Blocks.AIR.defaultBlockState(),2);r.nextReview=0;
        h.onEachTick(()->{RegionGroundService.tick(l,f.s());ProductionRegionService.tickLevel(l);});
        h.startSequence().thenWaitUntil(()->h.assertTrue(f.s().formalBlueprints().stream().anyMatch(p->p.region()!=null && b.id().equals(p.region().workingBuilding)),"Broken fence never publishes real maintenance"))
            .thenExecute(()->{
                h.assertTrue(ProductionRegionService.constructionPending(f.s(),b) && !b.operational(),"Maintenance and forestry remain enabled together");
                h.assertTrue(l.getBlockState(soil.above()).is(Blocks.OAK_SAPLING),"Maintenance removed healthy sapling");
                h.assertTrue(f.s().terrain().projects.values().stream().flatMap(p->p.steps.stream()).noneMatch(p->p.position().equals(soil.above())),"Maintenance planned to clear healthy sapling");
                f.clean();
            }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=180)
    public static void farmWaitsForFinalWorkerPlacement(GameTestHelper h){lastFenceHandover(h,BuildingType.FARM_FIELD);}
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=180)
    public static void melonFarmWaitsForFinalWorkerPlacement(GameTestHelper h){lastFenceHandover(h,BuildingType.MELON_FIELD);}
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=180)
    public static void forestWaitsForFinalWorkerPlacement(GameTestHelper h){lastFenceHandover(h,BuildingType.TREE_FARM);}
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=180)
    public static void pastureWaitsForFinalWorkerPlacement(GameTestHelper h){lastFenceHandover(h,BuildingType.PASTURE_AREA);}

    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty")
    public static void fourBlockClearanceAndTallTreesPrecedeAllFacilities(GameTestHelper h){
        for(var type:List.of(BuildingType.FARM_FIELD,BuildingType.MELON_FIELD,BuildingType.PASTURE_AREA)){
            var f=fixture(h);var l=h.getLevel();var site=place(f,type,f.at());var soil=f.at().offset(2,-1,2);
            l.setBlock(soil.above(4),Blocks.STONE.defaultBlockState(),2);
            var high=f.at().offset(4,4,4);l.setBlock(high,Blocks.STONE.defaultBlockState(),2);
            var tree=f.at().offset(3,-1,3);
            for(int y=1;y<=7;y++)l.setBlock(tree.above(y),Blocks.OAK_LOG.defaultBlockState(),2);
            l.setBlock(tree.above(8),Blocks.OAK_LEAVES.defaultBlockState(),2);
            for(int n=0;n<40;n++)ProductionRegionService.tickLevel(l);
            var steps=f.s().terrain().dependingOn(site.id()).stream().flatMap(v->v.steps.stream()).toList();
            h.assertTrue(steps.stream().anyMatch(v->v.position().equals(soil.above(4)) && !v.fill()),"Fourth clearance block omitted: "+type);
            h.assertTrue(steps.stream().noneMatch(v->v.position().equals(high)),"Unrelated fifth-height stone was cleared: "+type);
            h.assertTrue(steps.stream().anyMatch(v->v.position().equals(tree.above(7)) && !v.fill()),"Tree truncated at four blocks: "+type);
            h.assertTrue(TimedConstructionService.first(l,site,Set.of())==null && !com.hkin.exiledsettlement.terrain.TerrainService.allowed(f.s(),site),"Facilities allowed before clearing: "+type);
            h.assertTrue(site.region().template().placements().stream().noneMatch(v->v.mode()==BlueprintPlacementMode.FARM_TILL || v.state().is(Blocks.FARMLAND)),"Worker still tills crop soil");
            var saved=ProductionRegion.load(site.region().save());h.assertTrue(saved.preparationCursor==site.region().preparationCursor && saved.prepared==site.region().prepared,"Preparation did not survive save");
            h.assertTrue(l.getBlockState(tree.above(7)).is(Blocks.OAK_LOG),"Planning silently removed world tree");f.clean();
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=240)
    public static void dirtHandoverLetsFarmerTillAndPlant(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var site=place(f,BuildingType.FARM_FIELD,f.at());builtFixture(f,site);
        var result=BlueprintService.completeAfterFootingCleanup(l,SettlementSavedData.get(l),f.s(),site);
        h.assertTrue(result.success(),"Dirt-only farm could not complete: "+result.reason());var farm=result.value();ProductionRegionService.validate(l,farm);
        var at=farm.region().ordered().stream().filter(v->v.use()==ProductionRegion.Use.CROP && farm.region().readyCrops.contains(v.ground().asLong())).map(ProductionRegion.Cell::ground).findFirst().orElseThrow();
        h.assertTrue(l.getBlockState(at).is(Blocks.DIRT),"Worker fixture already tilled");
        var c=f.s().citizens().iterator().next();c.setManualAssignment(CitizenProfession.FARMER,farm.id());c.assignProfession(CitizenProfession.FARMER,farm.id());c.receiveEquipment(new ItemStack(Items.IRON_HOE));c.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS,16));c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(at.getX()+.5,at.getY()+1,at.getZ()+.5,0,0);actor.setOnGround(true);l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        h.onEachTick(()->{if(l.getBlockState(at.above()).getBlock() instanceof CropBlock)return;c.startTask(CitizenWorkState.TO_FARM,at,null,null);
            try{var method=CitizenWorkService.class.getDeclaredMethod("farm",net.minecraft.server.level.ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class);method.setAccessible(true);method.invoke(null,l,SettlementSavedData.get(l),f.s(),c,actor);}catch(ReflectiveOperationException ex){throw new RuntimeException(ex);}});
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.getBlockState(at).is(Blocks.FARMLAND) && l.getBlockState(at.above()).is(Blocks.WHEAT),"Farmer did not till and plant after dirt handover: "+c.workFailure()))
            .thenExecute(()->{h.assertTrue(c.inventory().stream().filter(v->v.is(Items.WHEAT_SEEDS)).mapToInt(ItemStack::getCount).sum()==15,"Plant did not consume real seed");actor.discard();f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=600)
    public static void swimKeepsDestinationBankAndCanReturn(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var origin=f.at();
        for(var p:BlockPos.betweenClosed(origin.offset(6,-3,-8),origin.offset(10,-1,20)))l.setBlock(p,Blocks.WATER.defaultBlockState(),2);
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.moveTo(origin.getX()+7.5,origin.getY()-.3,origin.getZ()+3.5,0,0);l.addFreshEntity(actor);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());
        var target=origin.offset(14,0,3);var home=origin.offset(3,0,3);boolean[] back={false};
        h.onEachTick(()->actor.navigateTo(back[0]?home:target,.7));
        h.startSequence().thenWaitUntil(()->h.assertTrue(actor.getX()>origin.getX()+11 && !actor.isInWater(),"Swimming was redirected to starting bank"))
            .thenExecute(()->back[0]=true)
            .thenWaitUntil(()->h.assertTrue(actor.getX()<origin.getX()+6 && !actor.isInWater(),"Resident could not return without a bridge"))
            .thenExecute(()->{actor.discard();f.clean();}).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_independentareas",template="empty",timeoutTicks=400)
    public static void workerFellsTallTreeBeforeSoilAndFence(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var site=place(f,BuildingType.FARM_FIELD,f.at());
        var root=f.at().offset(3,-1,3);var fill=f.at().offset(4,-1,3);
        for(int y=1;y<=7;y++)l.setBlock(root.above(y),Blocks.OAK_LOG.defaultBlockState(),2);
        l.setBlock(root.above(8),Blocks.OAK_LEAVES.defaultBlockState(),2);l.setBlock(fill,Blocks.STONE.defaultBlockState(),2);
        for(int n=0;n<32;n++)ProductionRegionService.tickLevel(l);
        var c=f.s().citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        for(var item:List.of(Items.IRON_AXE,Items.IRON_SHOVEL,Items.IRON_PICKAXE))c.receiveEquipment(new ItemStack(item));c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.insertIntoInventory(new ItemStack(Items.DIRT,64));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(root.getX()-1.5,root.getY()+1,root.getZ()+.5,0,0);actor.setOnGround(true);l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        h.onEachTick(()->{
            boolean tree=false;for(int y=1;y<=7;y++)tree|=l.getBlockState(root.above(y)).is(Blocks.OAK_LOG);
            if(tree){h.assertTrue(l.getBlockState(fill).is(Blocks.STONE),"Soil was placed before tree clearance");h.assertTrue(TimedConstructionService.first(l,site,Set.of())==null,"Fence became available during tree cutting");}
            if(c.workState()!=CitizenWorkState.TO_BLUEPRINT){
                for(var child:List.copyOf(f.s().blueprints()))if(child.terrainProject()!=null && child.terrainProject().dependencies.contains(site.id())){
                    var next=com.hkin.exiledsettlement.terrain.TerrainService.pending(l,child,Set.of());
                    if(!next.isEmpty()){c.startTask(CitizenWorkState.TO_BLUEPRINT,next.getFirst().placement().position(),null,child.id());break;}
                }
            }
            CitizenWorkService.advanceSiteConstruction(l,SettlementSavedData.get(l),f.s(),c,actor);
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.getBlockState(fill).is(Blocks.DIRT),"Worker failed to clear tree then fill soil: "+c.workFailure()+" target="+c.targetPosition()))
            .thenExecute(()->{for(int y=1;y<=7;y++)h.assertTrue(l.getBlockState(root.above(y)).isAir(),"Tree left floating wood");
                h.assertTrue(c.inventory().stream().filter(v->v.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum()==7,"Tree drops lost or duplicated");actor.discard();f.clean();}).thenSucceed();
    }
}

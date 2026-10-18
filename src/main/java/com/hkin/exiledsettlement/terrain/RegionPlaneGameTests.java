package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.region.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

/** Fresh fixtures only. Direct block/entity placement below is test setup, not resident construction. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_timed")
@PrefixGameTestTemplate(false)
public final class RegionPlaneGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(RegionPlaneGameTests.class);
    }
    private static int serial;
    private static final List<BuildingType> TYPES=List.of(BuildingType.FARM_FIELD,BuildingType.MELON_FIELD,BuildingType.PASTURE_AREA,BuildingType.TREE_FARM);
    private static ProductionRegion shape(BuildingType type,BlockPos first,int width,int depth) {
        var r=new ProductionRegion(type,first,first.offset(width-1,0,depth-1),Direction.NORTH);
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++) {
            var p=first.offset(x,-3,z);
            r.cells.put(ProductionRegion.key(p.getX(),p.getZ()),new ProductionRegion.Cell(p,
                    r.agricultural()?ProductionRegion.Use.CROP:type==BuildingType.TREE_FARM?ProductionRegion.Use.FOREST:ProductionRegion.Use.PASTURE,
                    BuildingProductionService.defaultFor(type),type));
        }
        r.surveyed=true;r.waterSurveyDone=true;r.rebuildUses();return r;
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void planeRayHeightIndependenceAndSave(GameTestHelper h) {
        h.assertTrue(AreaSelectionPlane.corner(new Vec3(0,90,0),new Vec3(40,50,20),70).equals(new BlockPos(20,70,10)),"Second corner did not intersect the fixed plane");
        h.assertTrue(AreaSelectionPlane.corner(new Vec3(0,90,0),new Vec3(40,50,20),74).equals(new BlockPos(16,74,8)),"Raised plane did not move together");
        h.assertTrue(AreaSelectionPlane.corner(new Vec3(0,90,0),new Vec3(40,90,20),70)==null
                && AreaSelectionPlane.corner(new Vec3(0,90,0),new Vec3(40,100,20),70)==null,"Parallel/behind-camera ray created an invalid corner");
        for(var type:TYPES) {
            var r=shape(type,new BlockPos(0,70,0),5,5);
            h.assertTrue(r.flatY==69 && r.cells.values().stream().allMatch(c->c.ground().getY()==69),type+" ignored selected height");
            h.assertTrue(r.gradeOrigins.values().stream().anyMatch(y->y==67),"Original excavate/fill evidence was discarded");
            var restored=ProductionRegion.load(r.save());restored.rebuildUses();
            h.assertTrue(restored.flatY==69 && restored.cells.equals(r.cells),"Save/rebuild changed selected height/membership");
            var previous=r.save();previous.remove("LayoutReady");previous.putBoolean("Merged",true);
            var pending=ProductionRegion.load(previous);
            h.assertTrue(pending.layoutReady && pending.cells.equals(r.cells) && pending.planeY()==69,
                    "Renaming the old readiness flag changed current saved construction");
            var extension=shape(type,new BlockPos(5,70,0),5,5);
            var upper=shape(type,new BlockPos(10,73,0),5,5);
            h.assertTrue(r.cells.size()==25 && extension.cells.size()==25 && upper.cells.size()==25
                    && r.planeY()==69 && extension.planeY()==69 && upper.planeY()==72,"Independent plots changed another plot's plane or membership");
            h.assertTrue(!r.save().contains("Sources") && !r.save().contains("MergeTarget"),"Retired fusion state is still published");
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void irregularOuterRingAndProtectionSurviveSave(GameTestHelper h) {
        var r=shape(BuildingType.TREE_FARM,new BlockPos(0,70,0),8,8);
        r.cells.entrySet().removeIf(e->e.getValue().ground().getX()>=3 && e.getValue().ground().getZ()>=3);r.invalidate();r.rebuildUses();
        var w=new PublicGroundService.State();PublicGroundService.regionCheckpoints(w,r);
        h.assertTrue(w.anchors.get(ProductionRegion.key(3,4))==69,"Concave edge did not receive a checkpoint");
        h.assertTrue(!w.anchors.containsKey(ProductionRegion.key(7,7)) && !w.anchors.containsKey(ProductionRegion.key(1,1)),"Bounding rectangle or interior became an outer checkpoint");
        var loaded=new PublicGroundService.State();loaded.load(w.save());
        h.assertTrue(loaded.anchors.equals(w.anchors) && loaded.footprints.equals(w.footprints),"Public checkpoint persistence lost the irregular boundary");
        var s=new SettlementRecord(UUID.randomUUID(),"Plane fixture",BlockPos.ZERO,UUID.randomUUID());
        var b=s.addBuilding(r.type,r.first,r.facing,r.min(),r.max(),0,1).withRegion(r);
        var project=new TerrainProject(UUID.randomUUID(),"Public fixture",r.first,List.of());project.publicGround=true;
        h.assertTrue(b.containsColumn(new BlockPos(1,-50,1)) && !b.containsColumn(new BlockPos(4,69,4)),"Production membership still uses a bounding box or height");
        h.assertTrue(TerrainService.protectedPublicColumn(s,project,new BlockPos(1,-50,1))
                && !TerrainService.protectedPublicColumn(s,project,new BlockPos(4,69,4)),"Public work does not protect exact production columns");
        h.succeed();
    }
    private static BlockPos terrain(GameTestHelper h) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(160000+(serial++)*512,5,0));
        for(var p:BlockPos.betweenClosed(at.offset(-12,-3,-12),at.offset(12,-3,12))) {
            var chunk=new ChunkPos(p);level.setChunkForced(chunk.x,chunk.z,true);level.getChunkAt(p);
            level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
            level.setBlock(p.above(),Blocks.DIRT.defaultBlockState(),2);
            for(int y=2;y<=9;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);
        }
        return at;
    }
    private static void release(GameTestHelper h,BlockPos at,SettlementRecord s) {
        s.deactivate();for(var p:BlockPos.betweenClosed(at.offset(-12,0,-12),at.offset(12,0,12))){var c=new ChunkPos(p);h.getLevel().setChunkForced(c.x,c.z,false);}
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void serverSurveyKeepsChosenPlaneForEveryAreaType(GameTestHelper h) {
        for(var type:TYPES) {
            var at=terrain(h);var level=h.getLevel();var data=SettlementSavedData.get(level);
            var s=data.createOrRecover(at.west(8),UUID.randomUUID(),"Plane survey").value();
            int edge=Math.max(5,type.minimumAreaSize())-1;
            // A tall soil column must be excavated, not become the selected plane.
            for(int y=-1;y<=3;y++)level.setBlock(at.offset(2,y,2),Blocks.DIRT.defaultBlockState(),2);
            int size=Math.max(5,type.minimumAreaSize());
            var result=ProductionRegionService.place(level,type,at,at.offset(size-1,8,size-1),Direction.NORTH,s.ownerId(),null);
            h.assertTrue(result.success(),"Server rejected a legal planar area: "+result.reason());
            var r=result.value().region();h.assertTrue(r.second.getY()==at.getY(),"Server trusted second-corner height");
            while(!r.surveyed)r.survey(level,s::contains,32);
            h.assertTrue(r.cells.size()==size*size && r.flatY==at.getY()-1 && r.cells.values().stream().allMatch(c->c.ground().getY()==at.getY()-1),type+" survey changed height or membership");
            h.assertTrue(level.getBlockState(at.offset(2,3,2)).is(Blocks.DIRT) && level.getBlockState(at.below()).isAir(),"Selection/survey edited terrain for free");
            var saved=ProductionRegion.load(r.save());h.assertTrue(saved.flatY.equals(r.flatY) && saved.cells.equals(r.cells),"Saved survey changed");
            int blueprints=s.blueprints().size();
            var invalid=ProductionRegionService.place(level,type,at.atY(level.getMaxBuildHeight()),at.offset(edge,0,edge),Direction.NORTH,s.ownerId(),null);
            h.assertTrue(!invalid.success() && s.blueprints().size()==blueprints,"Invalid height published/revoked work");
            release(h,at,s);
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="region_plane_public",timeoutTicks=3000)
    public static void regionPublishesOuterWorkAndChargesRealFill(GameTestHelper h) {
        var at=terrain(h);var level=h.getLevel();var data=new SettlementSavedData();
        var s=data.createOrRecover(at.west(8),UUID.randomUUID(),"Plane public").value();
        var r=shape(BuildingType.PASTURE_AREA,at,5,5);
        var site=s.addAreaBlueprint(r.type,at,at,r.facing,s.ownerId(),false,r.first,r.second).withRegion(r);
        s.terrain().enabled=true;s.autoRoads().enabled=false;
        PublicGroundService.requestReview(s);
        var outside=at.west().below();var inside=at.offset(1,-1,1);long started=level.getGameTime();
        h.onEachTick(()->{
            PublicGroundService.tick(level,s);
            var project=s.terrain().projects.values().stream().filter(p->p.publicGround && p.steps.stream().anyMatch(step->step.fill() && step.position().equals(outside))).findFirst().orElse(null);
            if(project==null)return;
            h.assertTrue(PublicGroundService.checkpointAt(s,outside)==r.flatY && PublicGroundService.checkpointAt(s,inside)==null,"Incorrect inside/outside checkpoint");
            h.assertTrue(s.terrain().projects.values().stream().filter(p->p.publicGround).flatMap(p->p.steps.stream()).noneMatch(step->r.cell(step.position())!=null),"Public jobs entered the area");
            h.assertTrue(level.getBlockState(outside).isAir() && OptionalWorkBudget.snapshot(s).pending()<=TerrainConfig.OPTIONAL_PENDING_BLOCKS.get(),"Planner edited world or exceeded public quota");
            h.assertTrue(com.hkin.exiledsettlement.citizen.WorkTaskService.priority(s,site)==0,"Area waits behind optional public work");
            var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(level);h.assertTrue(actor!=null,"Missing worker");
            actor.setNoAi(true);actor.moveTo(outside.getX()-1.5,outside.getY(),outside.getZ()+.5,0,0);actor.setOnGround(true);
            actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SHOVEL));
            var stock=new ItemStack(Items.DIRT,1);var carrier=s.blueprint(project.id);
            var result=BlueprintService.placeTimedAt(level,data,s,carrier,outside,stock,actor);
            h.assertTrue(result.success() && stock.isEmpty() && level.getBlockState(outside).is(Blocks.DIRT),"Real outer fill failed or was not charged: "+result.reason());
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("REGION_PLANE_PUBLIC ticks={} pending={} fill={} paid=1",level.getGameTime()-started,OptionalWorkBudget.snapshot(s).pending(),outside);
            actor.discard();release(h,at,s);h.succeed();
        });
    }
}

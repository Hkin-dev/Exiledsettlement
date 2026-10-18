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

/** New independent fixtures only; historical heights are retained as real planning evidence. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_forestprotection")
@PrefixGameTestTemplate(false)
public final class ForestProtectionGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(ForestProtectionGameTests.class);
    }
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,ProductionRegion r,BuildingRecord b,BlockPos soil) {
        void clean(){s.deactivate();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)h.getLevel().setChunkForced((soil.getX()>>4)+x,(soil.getZ()>>4)+z,false);}
    }
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var soil=h.absolutePos(new BlockPos(480000+serial++*512,4,0));
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)l.setChunkForced((soil.getX()>>4)+x,(soil.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(soil.offset(-5,-1,-5),soil.offset(5,8,5))) {
            l.getChunkAt(p);l.setBlock(p,p.getY()<soil.getY()?Blocks.STONE.defaultBlockState():p.getY()==soil.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        }
        var s=SettlementSavedData.get(l).createOrRecover(soil.west(4).above(),UUID.randomUUID(),"Forest protection regression").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var r=new ProductionRegion(BuildingType.TREE_FARM,soil.above(),soil.above(),Direction.NORTH);
        long key=ProductionRegion.key(soil.getX(),soil.getZ());
        r.cells.put(key,new ProductionRegion.Cell(soil,ProductionRegion.Use.FOREST,BuildingProductionService.defaultFor(r.type),r.type));
        r.gradeOrigins.put(key,soil.getY()-2);r.surveyed=true;r.prepared=true;r.groundVersion=RegionGroundLayout.VERSION;r.nextReview=Long.MAX_VALUE;
        var b=s.addBuilding(r.type,r.first,r.facing,r.min(),r.max(),0,1).withRegion(r);
        l.setBlock(soil.above(),Blocks.OAK_SAPLING.defaultBlockState(),2);l.setDayTime(6000);
        return new Fixture(h,s,r,b,soil);
    }
    @GameTest(templateNamespace="exiledsettlement_forestprotection",template="empty")
    public static void replantedSaplingOnFinishedFillIsNotClearance(GameTestHelper h) {
        var f=fixture(h);var r=ProductionRegion.load(f.r().save());
        h.assertTrue(r.gradeOrigins.values().iterator().next()==f.soil().getY()-2,"Historical grading evidence was erased");
        h.assertTrue(!RegionGroundService.clearanceNeeded(h.getLevel(),r,r.cell(f.soil())),"Healthy replanted sapling becomes maintenance clearance after saved grading");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_forestprotection",template="empty")
    public static void grownTreeOnFinishedCutIsKeptButRealObstaclesRemain(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var r=f.r();var p=f.soil();
        r.gradeOrigins.put(ProductionRegion.key(p.getX(),p.getZ()),p.getY()+2);
        for(int y=1;y<=4;y++)l.setBlock(p.above(y),Blocks.OAK_LOG.defaultBlockState(),2);
        l.setBlock(p.above(5),Blocks.OAK_LEAVES.defaultBlockState(),2);
        h.assertTrue(!RegionGroundService.clearanceNeeded(l,r,r.cell(p)),"Grown production tree becomes clearance after excavation");
        l.setBlock(p.above(6),Blocks.STONE.defaultBlockState(),2);
        h.assertTrue(RegionGroundService.clearanceNeeded(l,r,r.cell(p)),"Real non-tree obstacle was exempted");
        l.setBlock(p,Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!RegionGroundService.keepTree(l,r,p,l.getBlockState(p.above(2)),p.above(2)),"Unfinished ground is incorrectly treated as finished forest soil");
        f.clean();h.succeed();
    }
    private static BlueprintRecord staleSite(Fixture f,boolean fill,boolean ownerField) {
        var at=f.soil().above();var p=new TerrainProject(UUID.randomUUID(),"Stale forest clearance",at,List.of(new TerrainProject.Step(at,
                fill?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),Blocks.SHORT_GRASS.defaultBlockState())));
        p.dependencies.add(f.b().id());if(ownerField)p.regionOwner=f.b().id();p.explicit=true;f.s().terrain().index(p);
        return f.s().addBlueprint(BuildingType.HOUSE,at,at.above(96),Direction.NORTH,f.s().ownerId(),false).withTerrainProject(p);
    }
    @GameTest(templateNamespace="exiledsettlement_forestprotection",template="empty")
    public static void staleClearanceAndFillRecheckLivingSapling(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();
        for(boolean fill:List.of(false,true))for(boolean owner:List.of(false,true)){
            var site=staleSite(f,fill,owner);var p=site.terrainProject();
            h.assertTrue(TerrainService.guard(l,f.s(),site,p.steps.getFirst()).equals("PROTECTED_AREA"),"Old forest job may destroy sapling: fill="+fill+" owner="+owner);
            h.assertTrue(!p.cancelled && p.finished.isEmpty(),"Protection faked or cancelled old work");
        }
        // The same owned clearance still accepts an ordinary grass obstacle.
        l.setBlock(f.soil().above(),Blocks.SHORT_GRASS.defaultBlockState(),2);var site=staleSite(f,false,true);
        h.assertTrue(TerrainService.guard(l,f.s(),site,site.terrainProject().steps.getFirst()).isEmpty(),"Forest protection blocks ordinary required clearing");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_forestprotection",template="empty")
    public static void arrivedWorkerCannotExecuteOldSaplingClearance(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var site=staleSite(f,false,true);var c=f.s().citizens().iterator().next();
        c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));c.receiveEquipment(new ItemStack(Items.IRON_AXE));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(f.soil().getX()-1.5,f.soil().getY()+1,f.soil().getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());c.startTask(CitizenWorkState.TO_BLUEPRINT,f.soil().above(),null,site.id());
        CitizenWorkService.advanceSiteConstruction(l,SettlementSavedData.get(l),f.s(),c,actor);
        h.assertTrue(l.getBlockState(f.soil().above()).is(Blocks.OAK_SAPLING),"Arrived worker destroyed living sapling through old work order");
        h.assertTrue(site.terrainProject().finished.isEmpty() && !c.hasInventoryItem(i->i.is(Items.OAK_SAPLING)),"Protection faked progress or generated sapling drops");
        h.assertTrue(c.workFailure().equals("PROTECTED_AREA"),"Protected action does not report its blocker: "+c.workFailure());
        actor.discard();f.clean();h.succeed();
    }
}

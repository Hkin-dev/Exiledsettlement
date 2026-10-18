package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_distinct")
@PrefixGameTestTemplate(false)
public final class DistinctBuildingGameTests {
    private static final BuildingType[] TYPES = {BuildingType.HUNTING_LODGE, BuildingType.TOOL_WORKSHOP,
            BuildingType.TOWN_HALL, BuildingType.FISHING_HUT, BuildingType.SAWMILL, BuildingType.SCHOOL,
            BuildingType.MARKET, BuildingType.TAVERN};
    private static final int[] COUNTS = {604,988,3084,377,1066,872,792,1401};
    private static final int[][] SIZES = {{11,8,13},{15,10,17},{25,14,29},{7,7,15},{11,10,25},{15,9,13},{19,7,17},{17,11,15}};

    @GameTest(templateNamespace="exiledsettlement_distinct",template="empty",timeoutTicks=600)
    public static void eightApprovedModelsLoadOperateAndPersistInFourRotations(GameTestHelper h) {
        var level = h.getLevel();
        for (int i=0;i<TYPES.length;i++) for (Direction facing : Direction.Plane.HORIZONTAL) {
            var type = TYPES[i]; int version = PlayerDesignedTemplates.distinctVersion(type);
            BlockPos anchor = h.absolutePos(new BlockPos(61000+i*48,12,61000+facing.ordinal()*48));
            var template = BlueprintTemplates.create(type,anchor,facing,version);
            h.assertTrue(template.width()==SIZES[i][0] && template.height()==SIZES[i][1] && template.depth()==SIZES[i][2],"Approved size changed "+type);
            h.assertTrue(template.placements().stream().filter(p->p.mode().isFinalStructure()).count()==COUNTS[i],"Approved blocks changed "+type);
            h.assertTrue(template.transitionMargin()==0,"Unapproved outside modules added "+type);
            for(var p:template.placements()) {
                level.setBlock(p.position(),p.state(),2);
                if(p.mode().isFinalStructure() && !p.state().is(ModBlocks.TOWN_HALL_MARKER.get())
                        && (!BlueprintPlacementGroups.isPaired(p.state()) || BlueprintPlacementGroups.isPrimary(p.state())))
                    h.assertTrue(p.material()!=null,"Unpaid block "+type+" "+p.state());
            }
            for(var entrance:template.entrances()) {
                var floor=entrance.position().below();
                h.assertTrue(level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP),"Entrance lacks floor "+type+" "+entrance);
                var state=level.getBlockState(entrance.position());
                h.assertTrue(state.getBlock() instanceof DoorBlock || state.getCollisionShape(level,entrance.position()).isEmpty(),"Entrance obstructed "+type);
                // Facing selects the hinge plane, not which side counts as outside;
                // a valid inward-opening door can face opposite the exit vector.
                if(state.getBlock() instanceof DoorBlock) h.assertTrue(state.getValue(DoorBlock.FACING).getAxis()==entrance.outward().getAxis(),"Door crosses exit direction "+type);
            }
            if(type==BuildingType.FISHING_HUT) level.setBlock(anchor.west(9).below(),Blocks.WATER.defaultBlockState(),2);
            var s=new SettlementRecord(UUID.randomUUID(),"Distinct rotation",anchor,UUID.randomUUID());
            var b=s.addBuilding(type,anchor,facing,template.minCorner(),template.maxCorner(),0,version);
            BuildingFunctionService.validateSingle(level,new SettlementSavedData(),s,b);
            h.assertTrue(type==BuildingType.SCHOOL ? !b.operational() && b.capacity()==0 : b.operational(),"Function missing "+type+" "+b.status());
            if(type==BuildingType.TOWN_HALL) h.assertTrue(level.getBlockState(anchor).is(ModBlocks.TOWN_HALL_MARKER.get()) && template.placementAt(anchor).material()==null,"Management anchor missing");
            var restored=BuildingRecord.load(b.save());
            h.assertTrue(BlueprintTemplates.createForBuilding(restored).placements().equals(template.placements()),"Building reload changed geometry "+type);
            var plan=BlueprintRecord.create(type,anchor,anchor,facing,UUID.randomUUID(),false,version,Map.of());
            h.assertTrue(BlueprintRecord.load(plan.save()).templateVersion()==version,"Plan version lost "+type);
            var legacy=plan.save(); legacy.putInt("StructureVersion",18); legacy.remove("TemplateVersion");
            h.assertTrue(BlueprintRecord.load(legacy).templateVersion()==LegacyTemplateFingerprintGameTests.VERSIONS.get(type),"Old pending plan upgraded "+type);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_distinct",template="empty",timeoutTicks=300)
    public static void historicalGeometryAndApprovedCatalogStayExact(GameTestHelper h) throws Exception {
        try(var stream=DistinctBuildingGameTests.class.getResourceAsStream("/data/exiledsettlement/test_fixtures/template_fingerprints_test44.json")) {
            h.assertTrue(stream!=null,"Baseline evidence missing");
            var baseline=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var actual=LegacyTemplateFingerprintGameTests.fingerprints();
            h.assertTrue(baseline.size()==actual.size(),"Baseline coverage changed");
            for(var e:baseline.entrySet()) h.assertTrue(e.getValue().getAsString().equals(actual.get(e.getKey())),"Historical geometry changed: "+e.getKey());
        }
        Set<BuildingType> expected=new HashSet<>(Arrays.asList(TYPES));
        expected.addAll(List.of(BuildingType.HOUSE,BuildingType.HOUSE_2,BuildingType.WAREHOUSE,BuildingType.FARM_FIELD,
                BuildingType.MELON_FIELD,BuildingType.TREE_FARM,BuildingType.PASTURE_AREA,BuildingType.LEISURE_GARDEN,BuildingType.MINE,BuildingType.CHURCH));
        var actual=Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG).map(e->e.type()).collect(java.util.stream.Collectors.toSet());
        h.assertTrue(actual.equals(expected) && SettlementCoreMenu.BUILDING_CATALOG.length==18,"Wrong player catalog "+actual);
        for(var e:SettlementCoreMenu.BUILDING_CATALOG) h.assertTrue(e.templateVersion()==BlueprintTemplates.currentTemplateVersion(e.type()),"Outdated player choice");
        h.assertTrue(SettlementCoreMenu.BRIDGE_BUTTON==203,"Bridge action changed");
        var old=BuildingRecord.create(BuildingType.TAVERN,BlockPos.ZERO,Direction.SOUTH,BlockPos.ZERO,BlockPos.ZERO,0,1);
        var merged=BuildingRecord.create(BuildingType.TAVERN,BlockPos.ZERO,Direction.SOUTH,BlockPos.ZERO,BlockPos.ZERO,0,2);
        var restaurant=BuildingRecord.create(BuildingType.RESTAURANT,BlockPos.ZERO,Direction.SOUTH,BlockPos.ZERO,BlockPos.ZERO,0,1);
        h.assertTrue(!CommunityCookingService.offersMeals(old) && CommunityCookingService.offersMeals(merged)
                && CommunityCookingService.offersMeals(restaurant),"Combined/legacy meal roles changed");
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_distinct",template="empty",timeoutTicks=2400)
    public static void cookWalksIntoApprovedTavernAndReturnsRealMeals(GameTestHelper h) {
        var level=h.getLevel(); var core=h.absolutePos(new BlockPos(65000,10,65000));
        var min=new ChunkPos(core.offset(-16,0,-16)); var max=new ChunkPos(core.offset(32,0,32));
        for(int x=min.x;x<=max.x;x++) for(int z=min.z;z<=max.z;z++) level.setChunkForced(x,z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-10,-1,-8),core.offset(24,-1,26))) level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);
        level.setDayTime(6000);
        var s=new SettlementRecord(UUID.randomUUID(),"Combined tavern physical",core,UUID.randomUUID());
        var c=s.citizens().iterator().next(); for(var other:s.citizens()) if(other!=c) other.markDead(core);
        s.community().foodReserve=0;s.community().fuelReserve=0;s.community().mealTarget=1;
        var data=new SettlementSavedData();
        var template=BlueprintTemplates.create(BuildingType.TAVERN,core.south(5),Direction.SOUTH);
        for(var p:template.placements()) level.setBlock(p.position(),p.state(),2);
        var tavern=s.addBuilding(BuildingType.TAVERN,template.anchor(),Direction.SOUTH,template.minCorner(),template.maxCorner(),0,BlueprintTemplates.currentTemplateVersion(BuildingType.TAVERN));
        BuildingFunctionService.validateSingle(level,data,s,tavern);
        h.assertTrue(tavern.operational(),"Approved tavern did not activate");
        h.assertTrue(tavern.workerCapacity()==1 && tavern.setWorkerTarget(1,tavern.configurationRevision()),"Chef slot not configurable");
        ProfessionAssignmentService.refresh(data,s);
        h.assertTrue(c.profession()==CitizenProfession.CHEF && tavern.id().equals(c.workBuildingId()),"Chef was not assigned to tavern");
        var storage=s.addBuilding(BuildingType.WAGON,core.west(5));
        level.setBlock(storage.position(),Blocks.BARREL.defaultBlockState(),3);
        storage.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(storage.position()));
        var inventory=(Container)level.getBlockEntity(storage.position());
        inventory.setItem(0,new ItemStack(Items.WHEAT,3));inventory.setItem(1,new ItemStack(Items.CHARCOAL));
        SettlementCitizenEntity[] actor=new SettlementCitizenEntity[1];boolean[] arrived={false};boolean[] assigned={false};
        long start=level.getGameTime();
        long wallStart=System.nanoTime();boolean chefTest="chef".equals(System.getProperty("exiledsettlement.testSuite"));
        long[] fullUntil={-1};boolean[] unblocked={false};
        if(chefTest)c.insertIntoInventory(new ItemStack(Items.BREAD,CommunityContent.carriedFoodTarget));
        h.startSequence().thenWaitUntil(()-> {
            CitizenProjectionService.ensureSettlementCitizens(level,s);
            actor[0]=CitizenProjectionService.findProjection(level,c);
            h.assertTrue(actor[0]!=null,"Waiting for entity chunk");
        }).thenExecute(()-> {
            actor[0].moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5,0,0);actor[0].setOnGround(true);
        }).thenWaitUntil(()-> {
            var entity=actor[0];c.updateProjection(entity.getUUID(),entity.blockPosition());
            if ((level.getGameTime()-start)%200==0) System.out.println("PLAYER_TEMPLATE_CHEF_TRACE tick="+(level.getGameTime()-start)
                    +" pos="+entity.position().subtract(core.getX(),core.getY(),core.getZ())+" stage="+c.life().cookingStage
                    +" target="+entity.currentNavigationDestination()+" pathDone="+entity.getNavigation().isDone()+" work="+c.life().workTicks);
            if((level.getGameTime()-start)%CitizenWorkService.WORK_INTERVAL_TICKS==0) {
                if(chefTest) {
                    if(c.life().cookingStage==3 && !c.life().task.isEmpty() && fullUntil[0]<0) {
                        for(int slot=0;slot<inventory.getContainerSize();slot++) inventory.setItem(slot,new ItemStack(Items.COBBLESTONE,64));
                        fullUntil[0]=level.getGameTime()+80;CommunityStock.invalidate(level,s);
                        var saved=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());
                        h.assertTrue(saved.life().cookingStage==3 && saved.life().cookingOutputCount==c.life().cookingOutputCount,"Saved delivery lost pending meals");
                    }
                    if(fullUntil[0]>0 && !unblocked[0] && level.getGameTime()>=fullUntil[0]) {
                        h.assertTrue(!c.life().task.isEmpty() && c.life().cookingStage==3 && s.community().mealsProduced==1,"Full warehouse discarded or recooked meals");
                        inventory.setItem(0,ItemStack.EMPTY);unblocked[0]=true;CommunityStock.invalidate(level,s);
                    }
                    CommunityLifeService.personal(level,data,s,c,entity);
                } else {
                    if(!assigned[0]) assigned[0]=CommunityCookingService.assign(level,s,c);
                    if(assigned[0] && !c.life().task.isEmpty()) CommunityCookingService.work(level,data,s,c,entity);
                }
                if(c.life().workTicks>0) {
                    var p=entity.blockPosition();
                    arrived[0]|=p.getZ()>template.minCorner().getZ()+1 && p.getZ()<template.maxCorner().getZ()
                            && p.getX()>template.minCorner().getX() && p.getX()<template.maxCorner().getX();
                }
            }
            h.assertTrue(s.community().mealsProduced>0 && c.life().task.isEmpty(),"Cook did not finish: "+c.life().task+" stage="+c.life().cookingStage+" work="+c.life().workTicks+" pos="+entity.position()+" "+c.workFailure());
            h.assertTrue(arrived[0],"Food produced without entering tavern");
            h.assertTrue(WarehouseInventoryService.countItem(level,storage,Items.WHEAT)==0 && WarehouseInventoryService.countItem(level,storage,Items.CHARCOAL)==0,"Inputs not consumed exactly");
            h.assertTrue(WarehouseInventoryService.countItem(level,storage,Items.BREAD)==s.community().mealsProduced,"Meals not physically returned");
            if(chefTest)h.assertTrue(unblocked[0] && CommunityLifeService.carriedFood(c)==CommunityContent.carriedFoodTarget,"Full warehouse wait/rations not preserved");
        }).thenExecute(()-> {
            System.out.println("CHEF_PHYSICAL_CHAIN_OK ticks="+(level.getGameTime()-start)+" wallSeconds="+((System.nanoTime()-wallStart)/1.0e9)
                    +" produced="+s.community().mealsProduced+" fullWarehouseWait="+unblocked[0]+" enteredKitchen="+arrived[0]);
            actor[0].discard();s.deactivate();
            for(int x=min.x;x<=max.x;x++) for(int z=min.z;z<=max.z;z++) level.setChunkForced(x,z,false);
        }).thenSucceed();
    }
}

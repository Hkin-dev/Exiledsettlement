package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit isolated dispatch inputs; no placement or navigation completion is credited. */
public final class RoadDispatch91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(52000,250,52000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Road dispatch isolated inputs").value();
        s.upgradeLevel();s.upgradeLevel();s.setCreativeCoreInstalled(true);
        s.terrain().enabled=true;s.autoRoads().enabled=true;s.autoRoads().workReady=true;s.decorations().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        SettlementCitizenEntity entity=null;long day=l.getDayTime();
        try{
            for(var raw:BlockPos.betweenClosed(core.offset(-12,-2,-4),core.offset(12,3,4))){
                var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
            }
            for(int x=-12;x<=12;x++)for(int z=-4;z<=4;z++)for(int y=-2;y<=-1;y++)l.setBlock(core.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
            l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);l.setDayTime(6000);
            var citizens=new ArrayList<>(s.citizens());for(var resident:citizens)resident.setManualAssignment(CitizenProfession.FARMER,null);
            var c=citizens.getFirst();c.setManualAssignment(CitizenProfession.WORKER,null);
            c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            c.insertIntoInventory(new ItemStack(Items.IRON_AXE));
            for(var armor:List.of(Items.LEATHER_HELMET,Items.LEATHER_CHESTPLATE,Items.LEATHER_LEGGINGS,Items.LEATHER_BOOTS))c.insertIntoInventory(new ItemStack(armor));
            c.insertIntoInventory(new ItemStack(Items.DIRT,16));c.reservePitBlocks(PitEscapeService::ordinary,16);
            c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,8));
            entity=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
            entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            var feet=core.west(2);entity.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);entity.setOnGround(true);
            if(!l.addFreshEntity(entity))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,entity.getUUID(),feet);
            var storeAt=core.east(8);l.setBlock(storeAt,Blocks.BARREL.defaultBlockState(),2);
            var store=s.addBuilding(BuildingType.WAREHOUSE,storeAt,Direction.WEST,storeAt,storeAt,0,1);
            store.withCustomTemplate(CustomTemplateSnapshot.create("dispatch warehouse input",BuildingType.WAREHOUSE,storeAt,Direction.WEST,
                    List.of(new BlueprintPlacement(storeAt,Blocks.BARREL.defaultBlockState(),Items.BARREL,BlueprintPlacementMode.STRUCTURE)),
                    List.of(new BlueprintEntrance(storeAt,Direction.WEST))));
            store.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(storeAt));s.terrain().surveyed.add(store.id());
            var schoolAt=core.west(9);l.setBlock(schoolAt,Blocks.SPRUCE_PLANKS.defaultBlockState(),2);
            var school=s.addBuilding(BuildingType.SCHOOL,schoolAt,Direction.WEST,schoolAt,schoolAt,0,1);
            school.withCustomTemplate(CustomTemplateSnapshot.create("appearance-only school input",BuildingType.SCHOOL,schoolAt,Direction.WEST,
                    List.of(new BlueprintPlacement(schoolAt,Blocks.SPRUCE_PLANKS.defaultBlockState(),Items.SPRUCE_PLANKS,BlueprintPlacementMode.STRUCTURE)),
                    List.of(new BlueprintEntrance(schoolAt,Direction.WEST))));
            school.updateFunction(false,BuildingStatus.REGISTERED_ONLY,0,List.of());s.terrain().surveyed.add(school.id());
            var schoolEnds=AutoRoadService.entrances(l,s,school);
            check(!school.operational() && AutoRoadService.roadEligible(school) && schoolEnds.size()==1 && AutoRoadService.parentMatches(l,s,school.id(),schoolEnds.getFirst()),
                    "physically registered appearance-only school keeps its real road entrance without inventing education or operational status",checks);
            var end=AutoRoadService.entrances(l,s,store).getFirst();var root=AutoRoadService.coreSurface(l,s);
            var line=new ArrayList<BlockPos>();for(int x=root.getX();x<=end.getX();x++)line.add(new BlockPos(x,root.getY(),root.getZ()));
            if(!line.getLast().equals(end))line.add(end);
            var road=new AutoRoadState.Route(store.id(),end,line);s.autoRoads().routes.put("dispatch",road);
            var publicAt=core.west().below();var publicSite=s.addBlueprint(BuildingType.HOUSE,publicAt,publicAt.above(32),Direction.NORTH,s.ownerId(),false);
            var publicOrder=new TerrainProject(publicSite.id(),"near public fill",publicAt,List.of(new TerrainProject.Step(publicAt,Blocks.COBBLESTONE.defaultBlockState(),Blocks.DIRT.defaultBlockState())));
            publicOrder.explicit=true;publicOrder.publicGround=true;publicOrder.groundSurfaces.add(publicAt);publicSite.withTerrainProject(publicOrder);s.terrain().index(publicOrder);
            var assignWork=CitizenWorkService.class.getDeclaredMethod("assignWork",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class);assignWork.setAccessible(true);
            assignWork.invoke(null,l,data,s);
            check(c.workState()==CitizenWorkState.TO_ROAD && road.id.equals(c.roadTaskId()),"ready paving dispatches while public earthwork remains pending: state="+c.workState()+"; target="+c.targetPosition()+"; road="+road.status+"; root="+root+"; end="+end+"; failure="+c.workFailure(),checks);
            check(!publicOrder.done() && !publicOrder.cancelled && l.getBlockState(publicAt).is(Blocks.DIRT)
                    && l.getBlockState(c.targetPosition()).is(Blocks.DIRT),"dispatch preserves actual public and road world without completion credit",checks);
            var originalRoutes=new LinkedHashMap<>(s.autoRoads().routes);
            var invalidShadow=new AutoRoadState.Route(UUID.randomUUID(),end,line);
            s.autoRoads().routes.clear();s.autoRoads().routes.put("invalid-shared-input",invalidShadow);s.autoRoads().routes.putAll(originalRoutes);
            var assignedId=c.roadTaskId();
            AutoRoadService.process(l,data,s,c,entity);
            check(c.workState()==CitizenWorkState.TO_ROAD && assignedId.equals(c.roadTaskId()) && road.id.equals(assignedId),
                    "shared-cell execution retains the assigned valid route instead of repeatedly releasing the worker for an invalid neighbour",checks);
            check(invalidShadow.progress==0 && !invalidShadow.suppressed && !publicOrder.cancelled,
                    "correct route lookup does not cancel or credit the overlapping pending route or terrain",checks);
            s.autoRoads().routes.remove("invalid-shared-input");
            c.clearWork();
            int repairIndex=Math.max(0,road.cells.size()-2);var repairAt=road.cells.get(repairIndex);
            road.repairs.set(repairIndex);road.admitted.set(repairIndex);
            check(AutoRoadService.assign(l,s,c,new HashSet<>()) && repairAt.equals(c.targetPosition()) && road.id.equals(c.roadTaskId())
                    && l.getBlockState(repairAt).is(Blocks.DIRT),"released damaged-road paving precedes other ready cells without placing blocks",checks);
            var savedRoads=new AutoRoadState();savedRoads.load(s.autoRoads().save());
            check(savedRoads.routes.get("dispatch").repairs.get(repairIndex) && savedRoads.routes.get("dispatch").admitted.get(repairIndex),
                    "repair priority and its existing block reservation survive save reload",checks);
            c.clearWork();road.repairs.clear();
            var roadSpace=RoadSpaceData.get(l);roadSpace.addAll(List.of(repairAt));s.markRoadDamaged(repairAt);
            var assignRepair=CitizenWorkService.class.getDeclaredMethod("assignRepair",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class);assignRepair.setAccessible(true);
            check(!(boolean)assignRepair.invoke(null,l,data,s,c,new HashSet<BlockPos>()) && s.roadRepairPositions().contains(repairAt)
                    && l.getBlockState(repairAt).is(Blocks.DIRT),"automatic-road damage retains its real order and uses supported earthwork plus shovel paving instead of separate direct path replacement",checks);
            s.clearRoadRepair(repairAt);roadSpace.remove(repairAt);
            var cut=core.east(5);l.setBlock(cut,Blocks.STONE.defaultBlockState(),2);
            var roadSite=s.addBlueprint(BuildingType.HOUSE,cut,cut.above(32),Direction.NORTH,s.ownerId(),false);
            var roadOrder=new TerrainProject(roadSite.id(),"far road clearance",cut,List.of(new TerrainProject.Step(cut,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));
            roadOrder.road=true;roadOrder.dependencies.add(road.id);roadSite.withTerrainProject(roadOrder);s.terrain().index(roadOrder);
            var assign=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class,boolean.class,boolean.class,boolean.class);assign.setAccessible(true);
            check((boolean)assign.invoke(null,l,data,s,c,new HashSet<BlockPos>(),false,false,false)
                    && roadSite.id().equals(c.targetBlueprintId()) && WorkTaskService.task(s,c).priority()==2,
                    "road preparation precedes nearer independent public work while staying P2",checks);
            check(!roadOrder.done() && !roadOrder.cancelled && l.getBlockState(cut).is(Blocks.STONE),"road preparation assignment does not remove its real obstacle",checks);
            c.clearWork();
            var houseAt=core.west(6);var goals=List.of(houseAt,houseAt.west(),houseAt.west(2));
            var house=s.addBlueprint(BuildingType.HOUSE,houseAt,Direction.NORTH,s.ownerId(),false);
            house.withCustomTemplate(CustomTemplateSnapshot.create("three workfaces input",BuildingType.HOUSE,houseAt,Direction.NORTH,
                    goals.stream().map(at->new BlueprintPlacement(at,Blocks.COBBLESTONE.defaultBlockState(),Items.COBBLESTONE,BlueprintPlacementMode.STRUCTURE)).toList(),
                    List.of(new BlueprintEntrance(houseAt,Direction.NORTH))));s.terrain().surveyed.add(house.id());
            assignWork.invoke(null,l,data,s);
            check(house.id().equals(c.targetBlueprintId()) && WorkTaskService.task(s,c).priority()==0,"available building work stays ahead of paving and road preparation",checks);
            c.clearWork();var claimed=new HashSet<BlockPos>();
            for(int i=1;i<=2;i++){var other=citizens.get(i);other.clearWork();other.setManualAssignment(CitizenProfession.WORKER,null);
                other.startTask(CitizenWorkState.TO_BLUEPRINT,goals.get(i-1),null,house.id());WorkTaskService.bind(s,other);claimed.add(goals.get(i-1));}
            check(!(boolean)assign.invoke(null,l,data,s,c,claimed,false,false,true),"default building slots still control the first spread pass",checks);
            var extra=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class,boolean.class,boolean.class,boolean.class,boolean.class);extra.setAccessible(true);
            check((boolean)extra.invoke(null,l,data,s,c,claimed,false,false,true,false)
                    && house.id().equals(c.targetBlueprintId()) && goals.get(2).equals(c.targetPosition()),"free third building workface remains P0 beyond the default count",checks);
            check(goals.stream().allMatch(at->l.getBlockState(at).isAir()) && c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==8,
                    "dispatch consumes no material and places no building block",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs restored; dispatch only, not autonomous construction evidence";
        }finally{if(entity!=null)entity.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));l.setDayTime(day);data.touch();}
    }
    private RoadDispatch91Checks(){}
}

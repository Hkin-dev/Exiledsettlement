package com.hkin.exiledsettlement.road;
import com.google.gson.*;
import com.hkin.exiledsettlement.debug.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import java.util.*;

/** Opted-in isolated fixture; setup is not real road construction. */
public final class Road83Checks {
    private static final BlockPos CORE=new BlockPos(42100,250,42100);
    private static void check(boolean value,String name,List<String> checks){if(!value)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var checks=new ArrayList<String>();var origin=new BlockPos(43000,250,43000);
        var saved=new LinkedHashMap<BlockPos,BlockState>();var roads=RoadSpaceData.get(l);
        try{
            for(var raw:BlockPos.betweenClosed(origin.offset(-10,-3,-10),origin.offset(10,6,10))){var p=raw.immutable();if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,l.getBlockState(p));}
            for(int x=-10;x<=10;x++)for(int z=-10;z<=10;z++)for(int y=-3;y<=-1;y++)l.setBlock(origin.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
            var repairSettlement=new SettlementRecord(UUID.randomUUID(),"Road repair continuity detached",origin,UUID.randomUUID());
            repairSettlement.upgradeLevel();repairSettlement.upgradeLevel();
            var repairCells=List.of(origin.offset(3,-1,0),origin.offset(4,-1,0),origin.offset(5,-1,0));
            var repairParent=UUID.randomUUID();var oldRepair=new AutoRoadState.Route(repairParent,repairCells.getLast(),repairCells);
            oldRepair.completed.set(0);oldRepair.advance();oldRepair.earthworkCursor=repairCells.size();
            repairSettlement.autoRoads().routes.put("continuity",oldRepair);
            var paidOrder=new TerrainProject(UUID.randomUUID(),"pending road repair",repairCells.getFirst(),List.of(
                    new TerrainProject.Step(repairCells.getFirst(),Blocks.DIRT.defaultBlockState(),Blocks.STONE.defaultBlockState()),
                    new TerrainProject.Step(repairCells.get(1),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            paidOrder.road=true;paidOrder.dependencies.add(oldRepair.id);paidOrder.mark(0);
            repairSettlement.terrain().projects.put(paidOrder.id,paidOrder);repairSettlement.terrain().index(paidOrder);
            l.setBlock(repairCells.getFirst(),Blocks.DIRT_PATH.defaultBlockState(),2);
            l.setBlock(repairCells.get(1),Blocks.AIR.defaultBlockState(),2);roads.addAll(repairCells);
            var repairGround=repairSettlement.terrain().publicGround;repairGround.phase=5;repairGround.planCursor=7;repairGround.signature=91;
            var sameRepair=new AutoRoadState.Route(repairParent,repairCells.getLast(),repairCells);
            check(!RoadEarthworkService.surfaceReady(l,repairSettlement,oldRepair,repairCells.get(1)),"damaged registered surface really needs repair",checks);
            check(!AdaptiveRoadService.accept(l,repairSettlement,"continuity",sameRepair)
                    && repairSettlement.autoRoads().routes.get("continuity")==oldRepair,"same damaged corridor keeps route identity",checks);
            check(!paidOrder.cancelled && !paidOrder.heightSuperseded && paidOrder.dependencies.contains(oldRepair.id)
                    && paidOrder.finished.get(0) && !paidOrder.finished.get(1),"same corridor preserves paid and pending order",checks);
            check(repairGround.phase==5 && repairGround.planCursor==7 && repairGround.signature==91,"same corridor does not restart public publication",checks);
            check(oldRepair.earthworkCursor==oldRepair.progress && l.getBlockState(repairCells.get(1)).isAir(),"repair cursor reopens without free world edits",checks);
            var reorderedRepair=new AutoRoadState.Route(repairParent,repairCells.getLast(),repairCells.reversed());
            reorderedRepair.centerline=oldRepair.centerline;
            check(!AdaptiveRoadService.accept(l,repairSettlement,"continuity",reorderedRepair)
                    && repairSettlement.autoRoads().routes.get("continuity")==oldRepair && !paidOrder.cancelled
                    && oldRepair.completed.get(0) && oldRepair.progress==1 && repairGround.phase==5,
                    "same surface set with different publication order preserves original bitset indices and work",checks);
            for(var p:repairCells){roads.remove(p);l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);}
            for(var dir:Direction.Plane.HORIZONTAL){
                var s=new SettlementRecord(UUID.randomUUID(),"Road83 detached",origin,UUID.randomUUID());s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;
                for(var ys:List.of(new int[]{249,248,249},new int[]{249,248,248,249},new int[]{249,250,249},new int[]{249,250,250,249})){
                    var line=new ArrayList<BlockPos>();for(int i=0;i<ys.length;i++)line.add(origin.relative(dir,i).atY(ys[i]));
                    var state=s.terrain().publicGround;var tag=state.save();tag.putInt("Phase",0);var goals=new ListTag();
                    for(var p:line)for(int side=-1;side<=1;side++){var q=p.relative(dir.getClockWise(),side);var entry=new CompoundTag();entry.putLong("Column",BlockPos.asLong(q.getX(),0,q.getZ()));entry.putInt("Y",q.getY());goals.add(entry);}tag.put("Goals",goals);state.load(tag);
                    var graded=AutoRoadPlanner.previewGrade(l,s,line);check(graded.stream().allMatch(p->p.getY()==249),dir+" short plateau "+Arrays.toString(ys),checks);
                }
                var pinned=origin.relative(dir).atY(248);roads.pinManual(List.of(pinned));
                var manual=AutoRoadPlanner.previewGrade(l,s,List.of(origin.atY(249),pinned,origin.relative(dir,2).atY(249)));
                check(manual.get(1).getY()==248,dir+" manual height remains fixed",checks);roads.remove(pinned);
            }
            check(!AutoRoadService.reviewDue(25,6000) && AutoRoadService.reviewDue(100,6000),"day review cadence",checks);
            check(AutoRoadService.reviewDue(25,19000) && AutoRoadService.reviewDue(50,19000) && AutoRoadService.reviewDue(75,19000),"night review cadence includes completed roads",checks);
            var state=new AutoRoadState();state.reviewCursor=17;var restored=new AutoRoadState();restored.load(state.save());check(restored.reviewCursor==17,"review cursor survives reload",checks);
            checks.addAll(RoadGradingChecks.run(l));
            return "PASS "+checks.size()+" "+String.join("; ",checks)+"; fixture restored; no resident completion simulated";
        }finally{saved.forEach((p,block)->l.setBlock(p,block,2));}
    }
    public static String live(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var data=SettlementSavedData.get(l);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("ALREADY_EXISTS");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(CORE.offset(-20,-4,-15),CORE.offset(20,8,15)))if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-20;x<=20;x++)for(int z=-15;z<=15;z++)for(int y=-4;y<=-1;y++)l.setBlock(CORE.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);var s=data.createOrRecover(CORE,UUID.randomUUID(),"Road83 night maintenance physical chain").value();s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=true;s.autoRoads().enabled=true;s.decorations().enabled=false;
        var storage=CORE.offset(-7,0,-7);l.setBlock(storage,Blocks.BARREL.defaultBlockState(),2);var warehouse=s.addBuilding(BuildingType.WAREHOUSE,storage,Direction.NORTH,storage.offset(-2,-1,-2),storage.offset(2,2,2),0,1);warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(storage));s.terrain().surveyed.add(warehouse.id());warehouse.withCustomTemplate(barrelTemplate(storage,Direction.NORTH));
        // Eight workers reserve 16 ordinary blocks each for personal escape.
        // Keep finite construction stock available after those real withdrawals.
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.BREAD))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,item==Items.DIRT?512:128));
        for(var item:List.of(Items.DIAMOND_SHOVEL,Items.DIAMOND_AXE,Items.DIAMOND_PICKAXE))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,8));
        var parent=CORE.offset(15,0,0);l.setBlock(parent,Blocks.BARREL.defaultBlockState(),2);var wagon=s.addBuilding(BuildingType.WAREHOUSE,parent,Direction.WEST,parent.offset(-2,-1,-2),parent.offset(2,2,2),0,1);wagon.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(parent));s.terrain().surveyed.add(wagon.id());wagon.withCustomTemplate(barrelTemplate(parent,Direction.WEST));
        var line=new ArrayList<BlockPos>();var cells=new ArrayList<BlockPos>();
        int endX=AutoRoadService.entrances(l,s,wagon).getFirst().getX()-CORE.getX();
        for(int x=1;x<=endX;x++){
            int y=x==6 || x==7?248:249;var center=CORE.offset(x,0,0).atY(y);line.add(center);
            for(int z=-1;z<=1;z++){var p=center.offset(0,0,z);cells.add(p);if(y==248)l.setBlock(p.above(),Blocks.AIR.defaultBlockState(),2);var block=x==5 || x==8?Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,x==5?Direction.WEST:Direction.EAST):Blocks.DIRT_PATH.defaultBlockState();l.setBlock(p,block,2);}
        }
        var route=new AutoRoadState.Route(wagon.id(),line.getLast(),cells);route.centerline=List.copyOf(line);route.width=3;route.progress=cells.size();route.completed.set(0,cells.size());route.earthworkCursor=cells.size();route.accessConnected=true;route.adaptiveVersion=5;route.reviewAfter=0;s.autoRoads().routes.put("existing",route);RoadSpaceData.get(l).addAll(cells);
        var w=s.terrain().publicGround;var tag=w.save();tag.putInt("Phase",0);tag.putBoolean("RoadsIntegrated",true);tag.putLong("NextReview",Long.MAX_VALUE);var goals=new ListTag();for(var p:cells){var e=new CompoundTag();e.putLong("Column",BlockPos.asLong(p.getX(),0,p.getZ()));e.putInt("Y",p.getY());goals.add(e);}tag.put("Goals",goals);w.load(tag);
        while(s.citizens().size()<8)s.addMigrant();int n=0;for(var c:s.citizens()){c.setManualAssignment(CitizenProfession.WORKER,null);c.updateProjection(null,CORE.offset(-3,0,2+n++%5));c.insertIntoInventory(new ItemStack(Items.BREAD,16));}
        ForceWorkDebugService.set(l,s,false);CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,2);data.touch();return "OK "+s.id()+"; initial complete road has six stairs and two-row dip; explicit finite fixture, no force work";
    }
    private static CustomTemplateSnapshot barrelTemplate(BlockPos p,Direction d){
        var placements=new ArrayList<BlueprintPlacement>();placements.add(new BlueprintPlacement(p,Blocks.BARREL.defaultBlockState(),Items.BARREL));
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)placements.add(new BlueprintPlacement(p.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),Items.DIRT));
        return CustomTemplateSnapshot.create("Road83 accessible stock area fixture",BuildingType.WAREHOUSE,p,d,placements,List.of(new BlueprintEntrance(p.relative(d,2),d)));
    }
    public static String fixtureEntries(ServerLevel l){if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");return "SETUP: fresh road fixture already has accessible 5x5 warehouse areas and actual outside entries";}
    public static String inspect(ServerLevel l){
        var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var j=new JsonObject();j.addProperty("settlement",s.id().toString());j.addProperty("dayTime",Math.floorMod(l.getDayTime(),24000));j.addProperty("reviewCursor",s.autoRoads().reviewCursor);j.addProperty("plannerBusy",AutoRoadPlanner.busy(s));j.addProperty("status",s.autoRoads().status);var routes=new JsonArray();
        for(var r:s.autoRoads().routes.values())if(!r.suppressed){var a=new JsonObject();a.addProperty("id",r.id.toString());a.addProperty("progress",r.progress);a.addProperty("cells",r.cells.size());a.addProperty("access",r.accessConnected);a.addProperty("reviewAfter",r.reviewAfter);a.addProperty("roughness",AdaptiveRoadService.roughness(r.centerline));a.addProperty("surfaceReady",r.cells.stream().allMatch(p->RoadEarthworkService.surfaceReady(l,s,r,p)));a.addProperty("registered",r.cells.stream().allMatch(p->RoadSpaceData.get(l).contains(p)));a.addProperty("stairs",r.cells.stream().filter(p->l.getBlockState(p).getBlock() instanceof StairBlock).count());a.addProperty("status",r.status);var heights=new JsonArray();r.centerline.forEach(p->heights.add(p.getY()));a.add("heights",heights);routes.add(a);}j.add("routes",routes);j.addProperty("pendingGround",s.blueprints().stream().filter(b->b.terrainProject()!=null && !b.cancellationQueued()).count());return j.toString();
    }
}

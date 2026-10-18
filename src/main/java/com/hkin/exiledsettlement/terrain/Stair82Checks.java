package com.hkin.exiledsettlement.terrain;

import com.google.gson.*;
import com.hkin.exiledsettlement.debug.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Explicit harness: fixture blocks are test inputs; subsequent construction is resident work. */
public final class Stair82Checks {
    private static final BlockPos CORE=new BlockPos(41000,250,41000);
    private static void check(boolean value,String name,List<String> checks){if(!value)throw new IllegalStateException(name);checks.add(name);}
    private static PublicGroundService.State slope(Direction down,int... heights){
        var w=new PublicGroundService.State();var lane=down.getClockWise();
        for(int i=0;i<heights.length;i++)for(int z=0;z<2;z++){var p=BlockPos.ZERO.relative(down,i).relative(lane,z);w.goals.put(p.asLong(),heights[i]);}
        return w;
    }
    private static void plan(SettlementRecord s,PublicGroundService.State w){
        w.phase=6;w.planCursor=0;while(w.phase==6)PublicGroundService.seams(w);
        while(w.phase==7)PublicGroundService.passages(s,w);
    }
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var checks=new ArrayList<String>();var s=new SettlementRecord(UUID.randomUUID(),"Stair82 detached",BlockPos.ZERO,UUID.randomUUID());
        for(var d:Direction.Plane.HORIZONTAL){
            for(var heights:List.of(new int[]{10,10,10,10},new int[]{10,9,9,9},new int[]{10,9,9,8},new int[]{10,9,10,9})){
                var w=slope(d,heights);plan(s,w);check(w.stairs.isEmpty(),d+" no stairs "+Arrays.toString(heights),checks);
            }
            var w=slope(d,10,9,8,8);plan(s,w);check(w.stairs.size()==4,d+" single complete two-wide run",checks);
            check(w.stairs.values().stream().allMatch(f->f==d.getOpposite()),d+" consistent facing",checks);
            var state=new PublicGroundService.State();state.load(w.save());check(state.stairs.equals(w.stairs),d+" save run",checks);
            var uneven=slope(d,10,9,8,8);uneven.goals.put(BlockPos.ZERO.relative(d).relative(d.getClockWise()).asLong(),10);plan(s,uneven);check(uneven.stairs.isEmpty(),d+" mismatched second lane rejected",checks);
            var protectedRun=slope(d,10,9,8);protectedRun.anchors.put(BlockPos.ZERO.relative(d).asLong(),9);plan(s,protectedRun);check(protectedRun.stairs.isEmpty(),d+" fixed checkpoint preserved",checks);
        }
        var broad=new PublicGroundService.State();for(int x=0;x<5;x++)for(int z=-6;z<=6;z++)broad.goals.put(BlockPos.asLong(x,0,z),10-Math.min(x,3));plan(s,broad);
        check(broad.stairs.size()==6,"broad hillside has one aligned corridor",checks);
        var at=new BlockPos(4,10,4);var old=new TerrainProject(UUID.randomUUID(),"old incidental stair",at,List.of(new TerrainProject.Step(at,Blocks.COBBLESTONE_STAIRS.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        old.publicGround=true;old.completed=true;old.finished.set(0);old.groundSurfaces.add(at);s.terrain().projects.put(old.id,old);s.terrain().index(old);
        PublicGroundService.retireOldColumn(s,at);check(old.abandoned.get(0) && old.heightSuperseded,"same-height obsolete stair order released",checks);
        check(s.terrain().supersededPaid(at,Blocks.COBBLESTONE_STAIRS.defaultBlockState()),"paid stair evidence kept for real recovery",checks);
        return "PASS "+checks.size()+" "+String.join("; ",checks);
    }
    public static String live(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var data=SettlementSavedData.get(l);
        if(data.findByCore(CORE).isPresent())throw new IllegalStateException("ALREADY_EXISTS");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(CORE.offset(-18,-3,-18),CORE.offset(18,8,18)))if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++){
            l.setBlock(CORE.offset(x,-2,z),Blocks.STONE.defaultBlockState(),2);l.setBlock(CORE.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),2);
        }
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);var s=data.createOrRecover(CORE,UUID.randomUUID(),"Public stairs82 physical repair").value();s.upgradeLevel();s.upgradeLevel();s.autoRoads().enabled=false;s.terrain().enabled=true;
        var storage=CORE.offset(-7,0,-7);l.setBlock(storage,Blocks.BARREL.defaultBlockState(),2);var b=s.addBuilding(BuildingType.WAREHOUSE,storage,Direction.NORTH,storage.offset(-2,0,-2),storage.offset(2,2,2),0,1);b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(storage));
        // This barrel-only supply fixture is not a formal warehouse architecture test.
        s.terrain().surveyed.add(b.id());
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.COBBLESTONE_STAIRS,Items.BREAD))WarehouseInventoryService.insert(l,b,new ItemStack(item,64));
        for(var item:List.of(Items.DIAMOND_SHOVEL,Items.DIAMOND_PICKAXE,Items.DIAMOND_AXE))WarehouseInventoryService.insert(l,b,new ItemStack(item,8));
        var w=s.terrain().publicGround;w.heightVersion=PublicGroundService.HEIGHT_VERSION;w.roadsIntegrated=true;
        for(int x=0;x<4;x++)for(int z=0;z<2;z++)w.goals.put(BlockPos.asLong(CORE.getX()+4+x,0,CORE.getZ()+4+z),252-Math.min(x,2));
        for(int x=0;x<4;x++)for(int z=0;z<2;z++)w.goals.put(BlockPos.asLong(CORE.getX()+4+x,0,CORE.getZ()-5+z),249);
        for(int x=0;x<3;x++)for(int z=0;z<2;z++){
            var at=CORE.offset(4+x,-1,-5+z);var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.WEST);l.setBlock(at,stair,2);
            var old=new TerrainProject(UUID.randomUUID(),"recorded old scattered public stair",at,List.of(new TerrainProject.Step(at,stair,Blocks.AIR.defaultBlockState())));old.publicGround=true;old.completed=true;old.finished.set(0);old.groundSurfaces.add(at);s.terrain().projects.put(old.id,old);s.terrain().index(old);
        }
        plan(s,w);while(s.citizens().size()<8)s.addMigrant();int n=0;
        for(var c:s.citizens()){c.setManualAssignment(CitizenProfession.WORKER,null);c.updateProjection(null,CORE.offset(-4,0,2+n++%5));c.insertIntoInventory(new ItemStack(Items.BREAD,16));}
        ForceWorkDebugService.set(l,s,true);CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,2);data.touch();
        return "OK "+s.id()+"; explicit fixture: six paid obsolete stairs, one two-wide slope, finite supplies; setup is not resident construction";
    }
    public static String supply(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();
        var warehouse=s.buildings().stream().filter(b->b.type()==BuildingType.WAREHOUSE).findFirst().orElseThrow();
        s.terrain().surveyed.add(warehouse.id());int cancelled=0;
        for(var site:s.blueprints())if(site.terrainProject()!=null && !site.terrainProject().publicGround && site.terrainProject().dependencies.contains(warehouse.id())){
            site.terrainProject().cancelled=true;site.queueCancellation();cancelled++;
        }
        var remainder=WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.COBBLESTONE,64));
        SettlementSavedData.get(l).touch();return "EXPLICIT TEST SUPPLY: 64 finite cobblestone; leftover="+remainder+"; unrelated barrel-fixture grading cancelled="+cancelled+"; not production";
    }
    public static String inspect(ServerLevel l){
        var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var w=s.terrain().publicGround;
        // Freeze only test planning after its real publication; workers keep their actual orders.
        if(w.phase==0)w.nextReview=Long.MAX_VALUE;
        var j=new JsonObject();j.addProperty("settlement",s.id().toString());j.addProperty("phase",w.phase);j.addProperty("plannedStairs",w.stairs.size());var surfaces=new JsonArray();int correct=0,actualStairs=0;
        for(var e:w.goals.entrySet()){
            var at=BlockPos.of(e.getKey()).atY(e.getValue());var facing=w.stairs.get(e.getKey());var expected=facing==null?Blocks.DIRT.defaultBlockState():Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing);var actual=l.getBlockState(at);if(TerrainService.satisfied(actual,expected))correct++;if(actual.getBlock() instanceof StairBlock)actualStairs++;
            var row=new JsonObject();row.addProperty("at",at.toShortString());row.addProperty("expected",expected.toString());row.addProperty("actual",actual.toString());surfaces.add(row);
        }
        j.addProperty("correct",correct);j.addProperty("columns",w.goals.size());j.addProperty("actualStairs",actualStairs);j.addProperty("pending",s.blueprints().stream().filter(b->b.terrainProject()!=null && !b.cancellationQueued()).count());j.add("surfaces",surfaces);return j.toString();
    }
}

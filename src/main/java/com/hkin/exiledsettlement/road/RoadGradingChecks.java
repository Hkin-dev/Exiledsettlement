package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit synthetic geometry in a fresh test server. Never counts as resident construction. */
final class RoadGradingChecks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    private static SettlementRecord settlement(BlockPos origin){
        var s=new SettlementRecord(UUID.randomUUID(),"Stable road grading",origin,UUID.randomUUID());s.upgradeLevel();s.upgradeLevel();return s;
    }
    private static List<BlockPos> line(BlockPos origin,int... heights){
        var out=new ArrayList<BlockPos>();for(int i=0;i<heights.length;i++)out.add(origin.east(i).atY(heights[i]));return out;
    }
    private static void goals(SettlementRecord s,List<BlockPos> line,int phase){
        var t=s.terrain().publicGround.save();t.putInt("Phase",phase);t.putBoolean("RoadsIntegrated",true);var goals=new ListTag();
        for(var p:line)for(int z=-1;z<=1;z++){var q=p.offset(0,0,z);var e=new CompoundTag();e.putLong("Column",BlockPos.asLong(q.getX(),0,q.getZ()));e.putInt("Y",q.getY());goals.add(e);}
        t.put("Goals",goals);s.terrain().publicGround.load(t);
    }
    private static AutoRoadState.Route route(SettlementRecord s,String key,List<BlockPos> line){
        var cells=new ArrayList<BlockPos>();for(var p:line)for(int z=-1;z<=1;z++)cells.add(p.offset(0,0,z));
        var r=new AutoRoadState.Route(UUID.randomUUID(),line.getLast(),cells);r.centerline=line;r.width=3;s.autoRoads().routes.put(key,r);return r;
    }
    static List<String> run(ServerLevel level){
        var checks=new ArrayList<String>();var origin=new BlockPos(43500,240,43500);
        var worker=new com.hkin.exiledsettlement.citizen.CitizenRecord(UUID.randomUUID(),"road material reserve",origin);
        worker.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT,16));
        worker.reservePitBlocks(com.hkin.exiledsettlement.citizen.PitEscapeService::ordinary,16);
        int usable=0;
        for(int slot=0;slot<com.hkin.exiledsettlement.citizen.CitizenRecord.INVENTORY_SIZE;slot++)
            if(worker.inventoryItem(slot).is(net.minecraft.world.item.Items.DIRT)
                    && !com.hkin.exiledsettlement.citizen.CitizenEquipmentService.isReservedEquipment(worker,slot))usable+=worker.inventoryItem(slot).getCount();
        check(usable==0 && worker.pitReserve().getCount()==16,"personal escape reserve cannot satisfy road material demand",checks);
        var stock=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT,64);
        int picked=com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(worker,net.minecraft.world.item.Items.DIRT,8,
                count->stock.split(count),leftover->stock.grow(leftover.getCount()));
        var spent=worker.extractInventoryItem(stack->stack.is(net.minecraft.world.item.Items.DIRT),8);
        check(picked==8 && stock.getCount()==56 && spent.getCount()==8 && worker.pitReserve().getCount()==16,
                "road batch fetches real additional material and preserves escape reserve",checks);
        var saved=new LinkedHashMap<BlockPos,BlockState>();var roads=RoadSpaceData.get(level);
        for(var raw:BlockPos.betweenClosed(origin.offset(-2,-4,-3),origin.offset(35,6,3))){var p=raw.immutable();
            level.getChunkAt(p);if(!level.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED grading");saved.put(p,level.getBlockState(p));}
        try{
            for(var p:saved.keySet())if(p.getY()<240)level.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
            var s=settlement(origin);var flat=line(origin,239,239,239);var dip=line(origin,239,238,239);
            goals(s,dip,14);
            var first=AutoRoadPlanner.previewGrade(level,s,flat);
            check(first.equals(flat),"flat road resists conflicting soft public goals",checks);
            check(AutoRoadPlanner.previewGrade(level,s,first).equals(first),"repeated grading is stable",checks);
            check(AutoRoadPlanner.previewGrade(level,s,dip).equals(flat),"short dip reaches same fixed point",checks);
            int[] heights=new int[25];Arrays.fill(heights,238);heights[0]=heights[24]=239;var longDip=line(origin,heights);goals(s,longDip,14);
            check(AutoRoadPlanner.previewGrade(level,s,longDip).stream().allMatch(p->p.getY()==239),"24-edge hollow has no six-edge cutoff",checks);
            var climbing=line(origin,237,238,237,238,239);goals(s,climbing,14);
            var monotone=AutoRoadPlanner.previewGrade(level,s,climbing);
            check(AdaptiveRoadService.verticalTravel(monotone)==2 && monotone.getFirst().equals(climbing.getFirst()) && monotone.getLast().equals(climbing.getLast()),"unequal endpoints preserve necessary rise without reversal",checks);
            roads.pinManual(List.of(dip.get(1)));goals(s,dip,14);
            check(AutoRoadPlanner.previewGrade(level,s,dip).get(1).equals(dip.get(1)),"manual low point stays pinned",checks);roads.remove(dip.get(1));
            var paid=new TerrainProject(UUID.randomUUID(),"committed old hollow",dip.get(1),List.of(new TerrainProject.Step(dip.get(1),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            paid.publicGround=true;paid.road=true;paid.groundSurfaces.add(dip.get(1));s.terrain().index(paid);goals(s,dip,0);
            check(!RoadEarthworkService.permitted(level,s,flat.get(1)),"resident publication still obeys committed plane",checks);
            try(var scope=RoadEarthworkService.planningScope(s,true)){
                check(AutoRoadPlanner.previewGrade(level,s,dip).equals(flat),"mathematical review can flatten committed old hollow",checks);
            }
            check(!RoadEarthworkService.permitted(level,s,flat.get(1)),"mathematical scope does not leak into resident work",checks);
            var reject=settlement(origin);goals(reject,dip,14);route(reject,"flat",flat);
            var worse=new AutoRoadState.Route(reject.autoRoads().routes.get("flat").building,flat.getLast(),dip.stream().flatMap(p->java.util.stream.IntStream.rangeClosed(-1,1).mapToObj(z->p.offset(0,0,z))).toList());
            worse.centerline=dip;worse.width=3;
            check(!AdaptiveRoadService.accept(level,reject,"flat",worse),"acceptance rejects a rougher soft-goal fit",checks);
            var shared=settlement(origin);var sharedLine=line(origin,239,239,238,238,239,239,239);goals(shared,sharedLine,14);
            var a=route(shared,"a",sharedLine);var b=route(shared,"b",sharedLine);
            for(var p:a.cells){for(int h=1;h<=4;h++)level.setBlock(p.above(h),Blocks.AIR.defaultBlockState(),2);
                var target=RoadEarthworkService.surfaceTarget(level,shared,a,p);
                level.setBlock(p,target!=null && target.getBlock() instanceof StairBlock?target:Blocks.DIRT_PATH.defaultBlockState(),2);}
            roads.addAll(a.cells);
            var idA=a.id;var idB=b.id;
            var joint=new JointRoadGradePlanner(shared,"a");int slices=0;
            while(!joint.advance(level,shared,System.nanoTime()+2_000_000L) && slices++<10000){}
            check(joint.committed(),"shared automatic hollow is revised as a group",checks);
            check(shared.autoRoads().routes.get("a").centerline.stream().allMatch(p->p.getY()==239)
                    && shared.autoRoads().routes.get("b").centerline.stream().allMatch(p->p.getY()==239),"all shared claimants agree",checks);
            check(shared.autoRoads().routes.get("a").id.equals(idA) && shared.autoRoads().routes.get("b").id.equals(idB),"joint revision preserves route identities",checks);
            for(var r:shared.autoRoads().routes.values())for(int i=r.completed.nextSetBit(0);i>=0;i=r.completed.nextSetBit(i+1))
                check(RoadEarthworkService.surfaceReady(level,shared,r,r.cells.get(i)),"completed shared cell uses final group geometry "+i,checks);
            check(level.getBlockState(origin.east(2).atY(238)).is(Blocks.DIRT_PATH) && level.getBlockState(origin.east(2).atY(239)).isAir(),"joint planner never edits world blocks",checks);
            var blocked=settlement(origin);goals(blocked,sharedLine,14);var ba=route(blocked,"a",sharedLine);var bb=route(blocked,"b",sharedLine);bb.manualBridge=true;
            var before=blocked.autoRoads().save();joint=new JointRoadGradePlanner(blocked,"a");slices=0;
            while(!joint.advance(level,blocked,System.nanoTime()+2_000_000L) && slices++<10000){}
            check(!joint.committed() && before.equals(blocked.autoRoads().save()),"protected shared owner rejects the entire revision without partial mutation",checks);
            shared.autoRoads().gradingReviewVersion=1;var restored=new AutoRoadState();restored.load(shared.autoRoads().save());
            check(restored.gradingReviewVersion==1 && restored.routes.get("a").cells.equals(shared.autoRoads().routes.get("a").cells),"joint goals and review migration survive save reload",checks);
            return checks;
        }finally{for(var p:saved.keySet())roads.remove(p);saved.forEach((p,state)->level.setBlock(p,state,2));}
    }
}

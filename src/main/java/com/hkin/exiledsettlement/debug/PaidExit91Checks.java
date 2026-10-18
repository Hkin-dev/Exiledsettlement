package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit pit and actor inputs; each upward displacement is caused by a paid block transaction. */
public final class PaidExit91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(53000,250,53000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Paid pit exit isolated inputs").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        SettlementCitizenEntity e=null;
        try{
            for(var raw:BlockPos.betweenClosed(core.offset(-2,-2,-2),core.offset(5,6,2))){
                var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
            }
            var feet=core.east(2);l.setBlock(feet.below(),Blocks.STONE.defaultBlockState(),2);
            for(int y=0;y<4;y++)l.setBlock(feet.west().above(y),Blocks.STONE.defaultBlockState(),2);
            var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
            c.insertIntoInventory(new ItemStack(Items.DIRT,3));c.insertIntoInventory(new ItemStack(Items.COBBLESTONE));
            c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));c.requestConstructionTool(Items.IRON_AXE,null);
            var site=s.addBlueprint(BuildingType.HOUSE,feet,core.above(6),Direction.NORTH,s.ownerId(),false);
            var steps=new ArrayList<TerrainProject.Step>();for(int y=1;y<=3;y++)steps.add(new TerrainProject.Step(feet.above(y),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            var p=new TerrainProject(site.id(),"existing shared column missing base",feet,steps);
            p.publicGround=true;p.publicGroundVersion=PublicGroundService.HEIGHT_VERSION;
            p.groundSurfaces.add(feet.above(3));p.groundSurfaces.add(feet.west().above(3));
            site.withTerrainProject(p);s.terrain().index(p);
            var joint=s.terrain().publicGround.save();joint.putInt("HeightVersion",PublicGroundService.HEIGHT_VERSION);
            joint.putInt("Phase",14);joint.putBoolean("RoadsIntegrated",false);var proposed=new ListTag();
            for(var at:List.of(feet,feet.west())){var entry=new CompoundTag();entry.putLong("Column",BlockPos.asLong(at.getX(),0,at.getZ()));entry.putInt("Y",feet.getY()-1);proposed.add(entry);}
            joint.put("Goals",proposed);s.terrain().publicGround.load(joint);
            check(Objects.equals(PublicGroundService.targetAt(s,feet),feet.getY()+3),"unpublished lower math goal cannot invalidate the committed column",checks);
            for(int phase:List.of(5,0)){
                var delayed=s.terrain().publicGround.save();delayed.putInt("Phase",phase);delayed.putBoolean("RoadsIntegrated",true);s.terrain().publicGround.load(delayed);
                check(Objects.equals(PublicGroundService.targetAt(s,feet),feet.getY()+3),"capacity-paused publication retains actual committed column in phase "+phase,checks);
                check(!Engineering91Closure.planningTransactionClosed(s),"unpublished real targets prevent acceptance closure even in phase "+phase,checks);
            }
            s.terrain().publicGround.load(joint);
            FoundationSupportService.review(l,s,site);
            check(p.steps.size()==4 && p.indices(feet).size()==1 && l.getBlockState(feet).isAir() && p.finished.isEmpty(),"registered public column receives only a real pending bottom repair",checks);
            FoundationSupportService.review(l,s,site);
            check(p.steps.size()==4 && TerrainProject.load(p.save()).steps.equals(p.steps),"bottom repair is idempotent and saved",checks);
            e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
            e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            e.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),feet);
            c.startTask(CitizenWorkState.TO_EQUIPMENT,core,null,null);
            boolean requested=WorkerAccessService.request(l,e,core,3);
            check(feet.west().above(4).distSqr(core)>feet.distSqr(core),"actual safe exit temporarily increases distance from the low supply goal",checks);
            check(c.hasInventoryItem(stack->stack.is(net.minecraft.tags.ItemTags.SHOVELS)) && requested
                    && site.id().equals(c.workerAccess.fillSite),"paid exit uses carried shovel while another real tool fetch remains pending: requested="+requested
                    +"; block="+c.workerAccess.block+"; fill="+c.workerAccess.fillSite+"; state="+c.workState()
                    +"; allowed="+TerrainService.allowed(s,site)+"; stand="+e.canStandAtPosition(feet.west().above(4))
                    +"; edge="+e.canTraverseRecordedConstructionEdge(feet.above(4),feet.west().above(4))
                    +"; raised="+WorkerAccessService.canRaiseFoundation(l,s,c,e,site,feet,feet.getY()+3)
                    +"; steps="+p.steps.stream().map(step->step.position()+":"+TerrainService.guard(l,s,site,step)+":"+BuriedFillMaterials.choose(l,s,p,step,c)).toList()
                    +"; geometry="+WorkerAccessService.observation(l,s,c,e,core),checks);
            check(l.getBlockState(feet).isAir() && c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==1,
                    "planning the exit neither fills nor consumes material",checks);
            c.workerAccess.lastTick=l.getGameTime()-10;WorkerAccessService.tick(l,data,s,c,e);
            check(l.getBlockState(feet).is(Blocks.COBBLESTONE) && e.getY()>=feet.getY()+1-1.0E-6 && l.noCollision(e)
                    && c.inventory().stream().noneMatch(stack->stack.is(Items.COBBLESTONE)),"first missing bottom is physically filled, paid and raises the resident safely: actual="+l.getBlockState(feet)
                    +"; y="+e.getY()+"; failure="+c.workFailure()+"; state="+c.workState(),checks);
            check(c.workState()==CitizenWorkState.TO_EQUIPMENT && core.equals(c.targetPosition()) && p.remaining()==3,
                    "actual safety fill retains the supply task and remaining column work",checks);
            var pendingExit=new ArrayList<TerrainProject.Step>();
            for(int y=4;y<=5;y++){var at=feet.west().above(y);l.setBlock(at,Blocks.STONE.defaultBlockState(),2);pendingExit.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()));}
            p.appendFoundationSupport(pendingExit);
            check(!WorkerAccessService.canRaiseFoundation(l,s,c,e,site,e.navigationFeetPosition(),feet.getY()+3),
                    "unbuilt exit cannot justify raising without its real clearance tool",checks);
            c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            check(WorkerAccessService.canRaiseFoundation(l,s,c,e,site,e.navigationFeetPosition(),feet.getY()+3)
                    && l.getBlockState(feet.west().above(4)).is(Blocks.STONE),
                    "registered protected paid exit workface can be reached by filling with full materials and real tools, without clearing it remotely",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture restored; autonomous walking still requires scene evidence";
        }finally{if(e!=null)e.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private PaidExit91Checks(){}
}

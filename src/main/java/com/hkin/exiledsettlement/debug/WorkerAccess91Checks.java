package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Stage geometry and actor positions are explicit input, not autonomous walking evidence. */
public final class WorkerAccess91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(50000,250,50000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var saved=new LinkedHashMap<BlockPos,BlockState>();
        for(var raw:BlockPos.betweenClosed(core.offset(-2,-2,-2),core.offset(12,7,2))){
            var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
        }
        var result=data.createOrRecover(core,UUID.randomUUID(),"WorkerAccess91 isolated frontier checks");
        if(!result.success())throw new IllegalStateException(result.reason());var s=result.value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);var checks=new ArrayList<String>();
        try{
            for(int x=0;x<=10;x++)for(int z=-1;z<=1;z++)l.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            for(int z=-1;z<=1;z++)for(int y=250;y<=254;y++)l.setBlock(core.offset(8,0,z).atY(y),Blocks.STONE.defaultBlockState(),2);
            e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            var start=core.east();e.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");
            data.updateProjection(s,c,e.getUUID(),start);
            var parent=s.addBlueprint(BuildingType.HOUSE,core.east(20),Direction.NORTH,s.ownerId(),false);
            c.startTask(CitizenWorkState.TO_BLUEPRINT,parent.anchor(),null,parent.id());WorkTaskService.bind(s,c);
            c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            check(WorkerAccessService.previewCut(l,s,c,start,parent.anchor())==null,"no adjacent obstacle at initial position",checks);
            check(WorkerAccessService.request(l,e,parent.anchor(),3) && c.workerAccess.staging!=null,"reachable nearby frontier requested",checks);
            var cut=c.workerAccess.block;var stage=c.workerAccess.staging;
            var restored=new WorkerAccessService.State();restored.load(c.workerAccess.save());
            check(stage.equals(restored.staging) && cut.equals(restored.block) && parent.anchor().equals(restored.goal),"staging and original goal survive save",checks);
            WorkerAccessService.tick(l,data,s,c,e);
            check(l.getBlockState(cut).is(Blocks.STONE) && c.workerAccess.ticks==0,"distant worker cannot cut before reaching interaction range",checks);
            var attempt=c.workerAccess.save();int beforeTool=c.inventoryItem(0).getDamageValue();
            WorkerAccessService.abandonStalledAttempt(l,c);
            check(c.workerAccess.block==null && parent.id().equals(c.targetBlueprintId()) && parent.anchor().equals(c.targetPosition())
                    && l.getBlockState(cut).is(Blocks.STONE) && c.inventoryItem(0).getDamageValue()==beforeTool,
                    "failed frontier releases worker without cancelling mother or changing world and inventory",checks);
            boolean alternative=WorkerAccessService.request(l,e,parent.anchor(),3);
            check(!alternative || !cut.equals(c.workerAccess.block) || !stage.equals(c.workerAccess.staging),
                    "failed frontier is not immediately selected again",checks);
            // Restore the explicit fixture attempt to isolate its paid on-site transaction.
            c.workerAccess.load(attempt);
            // Explicit positioning input isolates the original paid transaction from navigation.
            e.stopSettlementNavigation();e.setPos(stage.getX()+.5,stage.getY(),stage.getZ()+.5);e.setOnGround(true);
            for(int i=0;i<8 && c.workerAccess.block!=null;i++){c.workerAccess.lastTick=l.getGameTime()-10;WorkerAccessService.tick(l,data,s,c,e);}
            check(l.getBlockState(cut).isAir(),"on-site visible cut actually executed",checks);
            check(c.inventory().stream().filter(stack->stack.is(Items.IRON_PICKAXE)).mapToInt(ItemStack::getDamageValue).sum()==1,"real tool durability consumed once",checks);
            check(c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==1,"actual stone drop retained",checks);
            // A valid neighbouring bank must be reached before cutting the actor's old footing.
            e.stopSettlementNavigation();e.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);e.setOnGround(true);
            var footing=start.below();var bank=start.east();
            c.workerAccess.block=footing;c.workerAccess.staging=bank;c.workerAccess.goal=parent.anchor();
            c.workerAccess.original="minecraft:stone";c.workerAccess.lastTick=l.getGameTime();
            WorkerAccessService.tick(l,data,s,c,e);
            check(footing.equals(c.workerAccess.block) && l.getBlockState(footing).is(Blocks.STONE)
                    && bank.equals(e.activeConstructionMovementTarget()),"current footing is retained while neighbouring bank movement starts",checks);
            e.stopSettlementNavigation();e.setPos(bank.getX()+.5,bank.getY(),bank.getZ()+.5);e.setOnGround(true);
            for(int i=0;i<8 && c.workerAccess.block!=null;i++){c.workerAccess.lastTick=l.getGameTime()-10;WorkerAccessService.tick(l,data,s,c,e);}
            check(l.getBlockState(footing).isAir() && l.getBlockState(bank.below()).is(Blocks.STONE),"old footing is cut only after actor occupies actual stable bank",checks);
            check(c.inventory().stream().filter(stack->stack.is(Items.IRON_PICKAXE)).mapToInt(ItemStack::getDamageValue).sum()==2
                    && c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==2,
                    "bank-side cut retains real drop and tool cost",checks);
            e.stopSettlementNavigation();c.workerAccess.clear();
            var frontier=core.east(5);var origin=frontier.west();l.setBlock(frontier,Blocks.STONE.defaultBlockState(),2);
            e.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);e.setOnGround(true);
            c.workerAccess.block=frontier;c.workerAccess.staging=origin;c.workerAccess.goal=parent.anchor();
            c.workerAccess.original="minecraft:stone";c.workerAccess.lastTick=l.getGameTime();c.workerAccess.personalSource=true;
            for(int i=0;i<8 && c.workerAccess.block!=null;i++){c.workerAccess.lastTick=l.getGameTime()-10;WorkerAccessService.tick(l,data,s,c,e);}
            check(l.getBlockState(frontier).isAir() && frontier.equals(c.workerAccess.advance)
                    && frontier.equals(e.activeConstructionMovementTarget()) && parent.id().equals(c.targetBlueprintId()) && c.workerAccess.personalSource,
                    "paid clearance starts collision-checked one-cell advance retaining mother and personal supply owner",checks);
            var advanceSaved=new WorkerAccessService.State();advanceSaved.load(c.workerAccess.save());
            check(frontier.equals(advanceSaved.advance) && parent.anchor().equals(advanceSaved.goal)
                    && advanceSaved.personalSource && advanceSaved.lastTick==c.workerAccess.lastTick,"frontier advance and finite work clock survive save",checks);
            e.stopSettlementNavigation();c.workerAccess.clear();
            c.setManualAssignment(CitizenProfession.FARMER,null);c.startTask(CitizenWorkState.TO_BLUEPRINT,parent.anchor(),null,parent.id());
            check(!WorkerAccessService.request(l,e,parent.anchor(),3),"non-worker frontier construction refused",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; fixture positions, not autonomous walking acceptance";
        }finally{e.discard();s.deactivate();saved.forEach((p,state)->l.setBlock(p,state,2));data.touch();}
    }
    private WorkerAccess91Checks(){}
}

package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit fixture inputs; real material, clearance and native block displacement transactions. */
public final class PitEscape91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(56000,250,56000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Independent retained pit supports inputs").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        SettlementCitizenEntity e=null;
        try{
            for(var raw:BlockPos.betweenClosed(core.offset(-3,-2,-2),core.offset(5,8,2))){
                var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
            }
            var feet=core.east(2);l.setBlock(feet.below(),Blocks.STONE.defaultBlockState(),2);
            for(int y=0;y<=4;y++)l.setBlock(feet.west().above(y),Blocks.STONE.defaultBlockState(),2);
            for(int x=-3;x<=-2;x++)for(int y=-1;y<=4;y++)l.setBlock(feet.offset(x,y,0),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=4;y++)l.setBlock(feet.above(y),Blocks.STONE.defaultBlockState(),2);
            var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
            c.insertIntoInventory(new ItemStack(Items.DIRT,16));c.requestConstructionTool(Items.IRON_SHOVEL,null);
            c.startTask(CitizenWorkState.TO_EQUIPMENT,core,null,null);
            e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
            e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            e.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),feet);
            var actor=e;check(!l.getEntities((net.minecraft.world.entity.Entity)null,new net.minecraft.world.phys.AABB(feet),a->a==actor).isEmpty(),
                    "fixture actor is active for vanilla block lifting",checks);
            e.setNoGravity(true);var resumed=e.saveWithoutId(new net.minecraft.nbt.CompoundTag());e.load(resumed);
            check(e.isNoGravity(),"fixture reproduces vanilla saved temporary gravity suspension",checks);
            for(int physics=0;physics<5;physics++)e.tick();
            check(!e.isNoGravity(),"resumed actor releases gravity suspension without an active controller",checks);
            check(e.onGround(),"normal physics settles the resumed actor on its actual stone floor",checks);
            check(PitEscapeService.preview(l,s,c,e)==null,"blocked head requires its real cutting tool",checks);
            c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            check(!PitEscapeService.ordinary(new ItemStack(Items.DIAMOND_BLOCK)) && !PitEscapeService.ordinary(new ItemStack(Items.SAND)),
                    "valuable and falling blocks are excluded from the emergency kit",checks);
            check(c.reservePitBlocks(PitEscapeService::ordinary,16)==16 && c.pitReserve().getCount()==16,
                    "reserve moves sixteen existing blocks without creating stock",checks);
            check(c.extractInventoryItem(stack->stack.is(Items.DIRT),64).isEmpty()
                    && c.inventory().stream().filter(stack->stack.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==16,
                    "regular material extraction cannot consume the visible reserved backpack slot",checks);
            c.compactInventory();var reloaded=CitizenRecord.load(c.save(l.registryAccess()),l.registryAccess());
            check(reloaded.pitReserve().getCount()==16 && reloaded.extractInventoryItem(stack->stack.is(Items.DIRT),1).isEmpty(),
                    "compaction and save reload preserve the paid reserve",checks);
            int orders=s.terrain().projects.size();var exit=PitEscapeService.preview(l,s,c,e);
            check(feet.west().above(5).equals(exit),"escape targets a physically existing bank without a public plan",checks);
            s.terrain().suppressed.add(feet.asLong());
            check(!PitEscapeService.request(l,s,c,e) && c.pitReserve().getCount()==16,"explicit forbidden fill leaves actor and inventory unchanged",checks);
            s.terrain().suppressed.remove(feet.asLong());
            check(PitEscapeService.request(l,s,c,e) && !c.hasInventoryItem(stack->stack.is(net.minecraft.tags.ItemTags.SHOVELS)),
                    "a worker fetching its missing shovel can request independent air placement",checks);
            var pending=CitizenRecord.load(c.save(l.registryAccess()),l.registryAccess());
            check(pending.pitEscape.exit.equals(exit) && pending.pitEscape.column.equals(feet),"saved personal escape retains its actual bank",checks);
            for(int turn=0;turn<160 && e.navigationFeetPosition().getY()<feet.getY()+5;turn++){
                c.pitEscape.lastTick=l.getGameTime()-10;PitEscapeService.tick(l,data,s,c,e);
            }
            check(e.getY()>=feet.getY()+5-1.0E-6 && l.noCollision(e),
                    "real head clearance and five paid blocks raise the actor to bank height without a position edit: y="+e.getY()+"; failure="+c.workFailure(),checks);
            check(c.pitReserve().getCount()==11 && c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==3,
                    "five blocks are consumed and all three overhead stone drops enter the backpack",checks);
            var pick=c.inventoryItem(c.findInventorySlot(stack->stack.is(Items.IRON_PICKAXE)));
            check(pick.getDamageValue()==3,"overhead clearance wears the actual pickaxe",checks);
            check(c.workState()==CitizenWorkState.TO_EQUIPMENT && core.equals(c.targetPosition()) && c.constructionTool()==Items.IRON_SHOVEL,
                    "escape retains the original supply task and its missing tool request",checks);
            var loaded=new TerrainState();loaded.load(s.terrain().save());
            for(int y=0;y<5;y++)check(l.getBlockState(feet.above(y)).is(Blocks.DIRT)
                    && loaded.escapeSupport(feet.above(y),l.getBlockState(feet.above(y)))
                    && WorkerAccessService.requiredFill(l,s,feet.above(y)),"retained paid support stays protected after reload at rise "+y,checks);
            check(s.terrain().projects.size()==orders && s.terrain().unfinishedProjects().isEmpty(),
                    "escape creates neither a public plan nor a recovery work order",checks);
            check(!loaded.escapeSupport(feet,Blocks.AIR.defaultBlockState()),"paid evidence cannot claim a missing block exists",checks);
            c.pitEscape.lastTick=l.getGameTime()-10;PitEscapeService.tick(l,data,s,c,e);
            check(e.hasActiveConstructionMovement(),"the last step uses actual adjacent movement toward the bank",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture restored; autonomous scene validation separate";
        }finally{if(e!=null)e.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private PitEscape91Checks(){}
}

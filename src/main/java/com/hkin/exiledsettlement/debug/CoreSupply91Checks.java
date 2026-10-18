package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit obstacle and actor inputs check source safety; not autonomous walking evidence. */
public final class CoreSupply91Checks {
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(48000,250,48000);
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        var s=new SettlementRecord(UUID.randomUUID(),"Detached physical core fixture",core,UUID.randomUUID());
        s.setCreativeCoreInstalled(true);
        var c=new CitizenRecord(UUID.randomUUID(),"Core fixture",core);
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);if(e==null)throw new IllegalStateException("NO_ENTITY");
        try{
            for(var raw:BlockPos.betweenClosed(core.offset(-5,-1,-5),core.offset(10,2,5))){
                var at=raw.immutable();if(!l.hasChunkAt(at) || !l.getBlockState(at).isAir())throw new IllegalStateException("LOADED_UNUSED_AIR_REQUIRED");
                saved.put(at,l.getBlockState(at));
            }
            for(int x=-5;x<=10;x++)for(int z=-5;z<=5;z++)l.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
            var origin=core.east(8);e.moveTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);e.setOnGround(true);l.addFreshEntity(e);
            check(!SettlementCoreInteractionService.canReceiveSupply(l,s,e) && CreativeResidentSupply.give(l,s,c,e,Items.BREAD,7)==0
                    && c.inventory().stream().allMatch(stack->stack.isEmpty()),"distant source grants no items",checks);
            var station=SettlementCoreInteractionService.reserveSupplyPosition(l,s,e);
            check(station!=null && !station.equals(core) && e.canStandAtPosition(station),"source uses legal visible standing cell",checks);
            for(var d:Direction.Plane.HORIZONTAL)for(int dy=0;dy<=1;dy++)l.setBlock(origin.relative(d).above(dy),Blocks.STONE.defaultBlockState(),2);
            check(!e.hasReachablePathTo(station),"actual closed obstacle defeats bounded path probe",checks);
            check(station.equals(SettlementCoreInteractionService.reserveSupplyPosition(l,s,e))
                    && e.navigationFeetPosition().equals(origin) && CreativeResidentSupply.give(l,s,c,e,Items.BREAD,7)==0,
                    "failed probe preserves committed station without movement or remote supply",checks);
            l.setBlock(station,Blocks.STONE.defaultBlockState(),2);
            var alternative=SettlementCoreInteractionService.reserveSupplyPosition(l,s,e);
            check(alternative!=null && !alternative.equals(station) && e.canStandAtPosition(alternative),"blocked station is replaced by another physical candidate",checks);
            SettlementCoreInteractionService.abandon(l,e);
            var retry=SettlementCoreInteractionService.reserveSupplyPosition(l,s,e);
            check(retry!=null && !retry.equals(alternative),"abandoned station is not immediately selected again",checks);
            l.setBlock(station,Blocks.AIR.defaultBlockState(),2);
            e.setPos(retry.getX()+.5,retry.getY(),retry.getZ()+.5);e.setOnGround(true);
            check(SettlementCoreInteractionService.canReceiveSupply(l,s,e) && CreativeResidentSupply.give(l,s,c,e,Items.BREAD,7)==7
                    && c.inventory().stream().filter(stack->stack.is(Items.BREAD)).mapToInt(stack->stack.getCount()).sum()==7,
                    "on-site visible source transfers exactly seven real carried items",checks);
            s.setCreativeCoreInstalled(false);
            check(CreativeResidentSupply.give(l,s,c,e,Items.BREAD,7)==0
                    && c.inventory().stream().filter(stack->stack.is(Items.BREAD)).mapToInt(stack->stack.getCount()).sum()==7,
                    "removed creative source grants no additional items",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture positions, not autonomous core-return acceptance";
        }finally{SettlementCoreInteractionService.release(l,e);e.discard();saved.forEach((p,state)->l.setBlock(p,state,2));}
    }
    private CoreSupply91Checks(){}
}

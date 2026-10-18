package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;

/** Infinite item source, with physical core arrival and real carried stacks. */
public final class CreativeResidentSupply {
    private CreativeResidentSupply(){ }
    public static boolean atCore(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,CitizenActivity activity){
        if(!s.creativeCoreInstalled())return false;
        if(SettlementCoreInteractionService.canReceiveSupply(level,s,e))return true;
        var station=SettlementCoreInteractionService.reserveSupplyPosition(level,s,e);
        if(station==null){
            if(WorkerAccessService.requestCoreAccess(level,s,c,e))CommunityLifeService.blocked(c,e,"正在真实修整返回核心的局部通路");
            else CommunityLifeService.blocked(c,e,"创造核心领取位置不可达");
        }
        else CommunityLifeService.move(c,e,station,activity,"前往创造核心领取物资");
        return false;
    }
    public static int give(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,Item item,int count){
        if(!s.creativeCoreInstalled() || !SettlementCoreInteractionService.canReceiveSupply(level,s,e))return 0;
        int amount=Math.min(Math.max(0,count),c.inventoryCapacityFor(new ItemStack(item)));if(amount<=0)return 0;
        var remaining=c.insertIntoInventory(new ItemStack(item,amount));int received=amount-remaining.getCount();
        if(received>0){com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"creative_core_receive",s.corePosition(),item.toString(),received);SettlementSavedData.get(level).touch();}
        return received;
    }
}

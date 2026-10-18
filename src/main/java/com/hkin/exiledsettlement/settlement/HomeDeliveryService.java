package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.Predicate;

/** Saved two-leg delivery: warehouse -> carried stacks -> occupied home. */
public final class HomeDeliveryService {
    private HomeDeliveryService() {}
    private static final String TASK="steward:home";
    public static boolean handles(CitizenRecord c){return TASK.equals(c.life().task);}
    public static boolean assign(ServerLevel level,SettlementRecord s,CitizenRecord c,BuildingRecord source) {
        if(!source.type().isStorage())return false;
        var stock=WarehouseInventoryService.summarize(level,source);
        var homes=s.buildings().stream().filter(b->b.type().isHouse() && b.active() && b.operational()
                && !s.community().danger(b.position()))
                .sorted(Comparator.comparingDouble(b->b.position().distSqr(source.position()))).toList();
        for(var home:homes) {
            int residents=(int)s.citizens().stream().filter(r->r.alive() && home.id().equals(r.homeBuildingId())).count();
            if(residents==0 || s.citizens().stream().anyMatch(r->r.alive() && handles(r) && home.id().equals(r.life().deliveryHome)))continue;
            var held=WarehouseInventoryService.summarize(level,home);
            for(int kind=0;kind<3;kind++) {
                Predicate<ItemStack> matches=switch(kind) {
                    case 0 -> CommunityLifeService::food;
                    case 1 -> stack->stack.is(Items.COAL)||stack.is(Items.CHARCOAL);
                    default -> CitizenClothingService::isGarment;
                };
                int target=residents*(kind==0?CommunityContent.carriedFoodTarget:kind==1?CommunityContent.homeFuelTarget:CommunityContent.homeGarmentTarget);
                int missing=target-held.entrySet().stream().filter(v->matches.test(new ItemStack(v.getKey()))).mapToInt(Map.Entry::getValue).sum();
                if(missing<=0)continue;
                var carried=c.inventory().stream().filter(v->!v.isEmpty() && matches.test(v)).findFirst().orElse(ItemStack.EMPTY);
                var item=!carried.isEmpty()?carried.getItem():stock.entrySet().stream().filter(v->v.getValue()>0 && matches.test(new ItemStack(v.getKey())))
                        .filter(v->c.inventoryCapacityFor(new ItemStack(v.getKey()))>0)
                        .sorted(Comparator.<Map.Entry<Item,Integer>>comparingInt(Map.Entry::getValue).reversed()
                                .thenComparing(v->BuiltInRegistries.ITEM.getKey(v.getKey()).toString())).map(Map.Entry::getKey).findFirst().orElse(null);
                if(item==null)continue;
                int amount=Math.min(64,Math.min(missing,!carried.isEmpty()?carried.getCount():Math.min(stock.get(item),c.inventoryCapacityFor(new ItemStack(item)))));
                amount=Math.min(amount,WarehouseInventoryService.stock(level,home).room());if(amount<=0)continue;
                var l=c.life();l.task=TASK;l.destination=source.id();l.deliveryHome=home.id();l.taskStarted=level.getGameTime();
                l.cookingInput=BuiltInRegistries.ITEM.getKey(item).toString();l.cookingInputCount=amount;l.cookingOutputCount=carried.isEmpty()?0:amount;l.cookingStage=carried.isEmpty()?0:1;
                SettlementSavedData.get(level).touch();return true;
            }
        }
        return false;
    }
    public static boolean work(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var l=c.life();var source=s.building(l.destination);var home=s.building(l.deliveryHome);
        var key=ResourceLocation.tryParse(l.cookingInput);Item item=key==null?Items.AIR:BuiltInRegistries.ITEM.get(key);
        if(c.profession()!=CitizenProfession.WAREHOUSE_KEEPER || !s.community().stewardEnabled
                || source==null || !source.active() || !source.operational() || !Objects.equals(c.workBuildingId(),source.id())
                || home==null || !home.active() || !home.operational() || s.community().danger(home.position()) || s.community().danger(source.position())
                || item==Items.AIR || level.getGameTime()<l.taskStarted || level.getGameTime()-l.taskStarted>2400
                || s.citizens().stream().noneMatch(r->r.alive() && home.id().equals(r.homeBuildingId()))) {
            finish(level,data,c,e);return false;
        }
        if(l.cookingStage==0) {
            s.community().stewardStatus="仓管进入仓库领取家庭补给";
            if(!CommunityLifeService.atStorage(level,c,e,source,item,CitizenActivity.TO_WAREHOUSE))return true;
            var taken=WarehouseInventoryService.extractItemForResident(level,source,e,item,l.cookingInputCount,c::inventoryCapacityFor);
            if(taken.isEmpty()){finish(level,data,c,e);return false;}
            var remainder=c.insertIntoInventory(taken);
            if(!remainder.isEmpty())throw new IllegalStateException("Preflighted home delivery did not fit");
            l.cookingOutputCount=taken.getCount();l.cookingStage=1;
            SaveAcceptanceService.action(level,c,"home_delivery_fetch",e.blockPosition(),l.cookingInput,taken.getCount());
            CommunityStock.invalidate(level,s);WarehouseInventoryService.releaseInteractionPosition(level,e);
        } else {
            s.community().stewardStatus="仓管送货到住宅";
            var carried=c.inventory().stream().filter(stack->stack.is(item)).findFirst().orElse(ItemStack.EMPTY);
            if(carried.isEmpty() || l.cookingOutputCount<=0){finish(level,data,c,e);return false;}
            var offered=carried.copyWithCount(Math.min(l.cookingOutputCount,carried.getCount()));
            var station=WarehouseInventoryService.approachPosition(level,home,e);
            if(station==null)station=WarehouseInventoryService.reserveInsertionPosition(level,home,e,offered);
            if(station==null){CommunityLifeService.blocked(c,e,"送货暂停：住宅内部不可达，补给保留在背包");return true;}
            CommunityLifeService.move(c,e,station,CitizenActivity.TO_WAREHOUSE,"正在向住宅送货");
            if(WarehouseInventoryService.canInteract(level,home,e)) {
                int moved=offered.getCount()-WarehouseInventoryService.insertForResident(level,home,e,offered).getCount();
                if(moved>0) {
                    c.extractInventoryItem(stack->ItemStack.isSameItemSameComponents(stack,offered),moved);l.cookingOutputCount-=moved;
                    SaveAcceptanceService.action(level,c,"home_delivery_arrive",e.blockPosition(),l.cookingInput,moved);
                }
                if(l.cookingOutputCount<=0 || moved==0)finish(level,data,c,e);
            }
        }
        data.touch();return true;
    }
    private static void finish(ServerLevel level,SettlementSavedData data,CitizenRecord c,SettlementCitizenEntity e) {
        c.life().clearTask();WarehouseInventoryService.releaseInteractionPosition(level,e);e.stopSettlementNavigation();data.touch();
    }
}

package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import java.util.*;

/** Construction-only supply facade: ordinary stock counts remain real, not hypothetical. */
public final class WarehouseConstructionSupply {
    private record Key(UUID building,Item item,int count,long revision,Object recipes,int fuel,int food) { }
    private record Answer(long tick,boolean available) { }
    private static final Map<ServerLevel,LinkedHashMap<Key,Answer>> CACHE=new WeakHashMap<>();
    public static void invalidate(){CACHE.clear();}
    private static List<ItemStack> snapshot(WarehouseStock stock) {
        List<ItemStack> result=new ArrayList<>();
        for(int i=0;i<stock.getContainerSize();i++)if(!stock.getItem(i).isEmpty())result.add(stock.getItem(i).copy());
        return result;
    }
    private static Map<Item,Integer> reserves(SettlementRecord settlement,WarehouseStock stock,Item target) {
        Map<Item,Integer> result=new IdentityHashMap<>();if(settlement==null)return result;
        // Keep the existing life-support policy. Already-carried goods are outside this inventory entirely.
        int fuel=Math.max(0,settlement.community().fuelReserve);
        for(Item item:List.of(Items.COAL,Items.CHARCOAL)) {
            int n=Math.min(fuel,stock.summary().getOrDefault(item,0));result.put(item,n);fuel-=n;
        }
        if(target!=Items.WHEAT)result.put(Items.WHEAT,Math.min(settlement.community().foodReserve,stock.summary().getOrDefault(Items.WHEAT,0)));
        // Do not convert the exact next material of another in-flight construction pickup.
        for(var c:settlement.citizens()) {
            if(c.workState()!=CitizenWorkState.TO_CONSTRUCTION_SUPPLY || c.targetBlueprintId()==null || c.targetPosition()==null)continue;
            var site=settlement.blueprint(c.targetBlueprintId());if(site==null)continue;
            var p=com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(site).placementAt(c.targetPosition());
            if(p!=null && p.material()!=null && p.material()!=target)result.merge(p.material(),1,Integer::sum);
        }
        return result;
    }
    public static boolean available(ServerLevel level,SettlementRecord settlement,Item item,int amount) {
        return SettlementInventoryService.warehouses(settlement).stream().anyMatch(b->available(level,settlement,b,item,amount));
    }
    public static boolean available(ServerLevel level,SettlementRecord settlement,BuildingRecord building,Item item,int amount) {
        if(item==null || amount<=0)return true;
        if(WarehouseInventoryService.hasCreativeSupply(level,building) || WarehouseInventoryService.countItem(level,building,item)>=amount)return true;
        if(!building.active() || !building.operational() || !building.type().isStorage() || !WarehouseStock.applies(building))return false;
        var stock=WarehouseInventoryService.stock(level,building);var generation=WarehouseCrafting.generation(level);
        var key=new Key(building.id(),item,amount,stock.revision(),generation,settlement.community().fuelReserve,settlement.community().foodReserve);
        var cache=CACHE.computeIfAbsent(level,k->new LinkedHashMap<>());var old=cache.get(key);
        if(old!=null && level.getGameTime()-old.tick()<20 && level.getGameTime()>=old.tick())return old.available();
        boolean available=WarehouseCrafting.plan(level,snapshot(stock),item,amount,reserves(settlement,stock,item))!=null;
        if(cache.size()>=256)cache.remove(cache.keySet().iterator().next());
        cache.put(key,new Answer(level.getGameTime(),available));return available;
    }
    public static BuildingRecord warehouse(ServerLevel level,SettlementRecord settlement,BlockPos from,Item item) {
        // Existing goods anywhere in the settlement take precedence over manufacturing more.
        var ready=SettlementInventoryService.supplyWarehouse(level,settlement,from,item);if(ready!=null)return ready;
        return SettlementInventoryService.warehouses(settlement).stream()
                .sorted(Comparator.comparingDouble(b->b.position().distSqr(from)))
                .filter(b->available(level,settlement,b,item,1)).findFirst().orElse(null);
    }
    public static ItemStack extract(ServerLevel level,BuildingRecord building,LivingEntity entity,Item item,int requested) {
        if(building==null || requested<=0 || !WarehouseInventoryService.canInteract(level,building,entity))return ItemStack.EMPTY;
        var ready=WarehouseInventoryService.extractItemOrCreativeForResident(level,building,entity,item,requested);
        if(!ready.isEmpty())return ready;
        if(!WarehouseStock.applies(building) || !building.active() || !building.operational() || !building.type().isStorage())return ItemStack.EMPTY;
        var settlement=SettlementSavedData.get(level).settlements().stream().filter(s->s.building(building.id())!=null).findFirst().orElse(null);
        var stock=WarehouseInventoryService.stock(level,building);long revision=stock.revision();
        var source=snapshot(stock);var reserves=reserves(settlement,stock,item);
        int count=Math.min(Math.min(64,new ItemStack(item).getMaxStackSize()),requested);
        // A short supply batch is useful: lack of 64 units must not block one craftable item.
        WarehouseCrafting.Plan plan=null;
        while(count>0){plan=WarehouseCrafting.plan(level,source,item,count,reserves);if(plan!=null)break;count=count==1?0:Math.max(1,count/2);}
        if(plan==null || !stock.commitCraft(revision,plan.remaining()))return ItemStack.EMPTY;
        if(settlement!=null)CommunityStock.invalidate(level,settlement);
        return plan.output();
    }
    /** Simulate one shared stock budget across all materials without committing any inventory changes. */
    public static Map<Item,Integer> shortages(ServerLevel level,SettlementRecord settlement,Map<Item,Integer> needed) {
        var remaining=new LinkedHashMap<Item,Integer>();
        needed.entrySet().stream().filter(e->e.getValue()>0)
                .sorted(Comparator.comparing(e->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                .forEach(e->remaining.put(e.getKey(),e.getValue()));
        if(settlement.creativeCoreInstalled())return Map.of();
        var stocks=new LinkedHashMap<BuildingRecord,List<ItemStack>>();
        for(var warehouse:SettlementInventoryService.warehouses(settlement)) {
            if(WarehouseInventoryService.hasCreativeSupply(level,warehouse))return Map.of();
            List<ItemStack> source=new ArrayList<>();
            if(WarehouseStock.applies(warehouse))source=snapshot(WarehouseInventoryService.stock(level,warehouse));
            else for(var container:WarehouseInventoryService.loadedContainers(level,warehouse))
                for(int i=0;i<container.container().getContainerSize();i++)source.add(container.container().getItem(i).copy());
            // Claim existing final goods before converting shared ingredients for any other requirement.
            for(var stack:source) {
                int count=Math.min(stack.getCount(),remaining.getOrDefault(stack.getItem(),0));
                if(count>0){remaining.put(stack.getItem(),remaining.get(stack.getItem())-count);stack.shrink(count);}
            }
            stocks.put(warehouse,source);
        }
        for(var entry:remaining.entrySet()) {
            int missing=entry.getValue();
            for(var stock:stocks.entrySet()) {
                if(missing<=0)break;
                if(!WarehouseStock.applies(stock.getKey()))continue;
                var reserved=reserves(settlement,WarehouseInventoryService.stock(level,stock.getKey()),entry.getKey());
                int low=0,high=missing; WarehouseCrafting.Plan best=null;
                while(low<high) {
                    int amount=low+(high-low+1)/2;
                    var plan=WarehouseCrafting.plan(level,stock.getValue(),entry.getKey(),amount,reserved);
                    if(plan==null)high=amount-1;else {low=amount;best=plan;}
                }
                if(best!=null && low>0){missing-=low;stock.setValue(best.remaining());}
            }
            entry.setValue(missing);
        }
        remaining.entrySet().removeIf(e->e.getValue()<=0);
        return remaining;
    }
    private WarehouseConstructionSupply() { }
}

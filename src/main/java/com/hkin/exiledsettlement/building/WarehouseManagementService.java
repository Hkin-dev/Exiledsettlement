package com.hkin.exiledsettlement.building;
import com.hkin.exiledsettlement.network.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.server.level.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class WarehouseManagementService {
    private WarehouseManagementService() {}
    private static final Map<ServerPlayer,Long> REQUESTS=new WeakHashMap<>();
    public static void request(ServerPlayer player,WarehouseRequestPayload p) {
        var level=player.serverLevel();var data=SettlementSavedData.get(level);var s=data.findByCore(p.core()).orElse(null);
        if(s==null || !s.active() || !s.isMember(player.getUUID()) || !s.contains(player.blockPosition()))return;
        long now=level.getGameTime();if(now<REQUESTS.getOrDefault(player,Long.MIN_VALUE))return;REQUESTS.put(player,now+2);
        var warehouses=s.buildings().stream().filter(b->b.active() && WarehouseStock.applies(b)).sorted(Comparator.comparing(BuildingRecord::id)).toList();
        if(p.warehouse()< -1 || p.warehouse()>=warehouses.size() || p.page()<0 || p.keep()<0 || p.keep()>WarehouseStock.CAPACITY)return;
        var selected=p.warehouse()<0?warehouses:List.of(warehouses.get(p.warehouse()));String feedback="按库存数量排序 · 仓库由仓管处理余量 · 住宅保存家庭补给。";
        if(p.apply()) {
            if(selected.size()==1 && selected.getFirst().type().isHouse())return;
            var key=ResourceLocation.tryParse(p.item());
            if(key==null || !BuiltInRegistries.ITEM.containsKey(key) || BuiltInRegistries.ITEM.get(key)==Items.AIR
                    || BuiltInRegistries.ITEM.get(key)==com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get())return;
            for(var b:selected)if(!b.type().isHouse())WarehouseInventoryService.stock(level,b).rule(p.item(),p.keep(),p.discard());
            data.touch();feedback="已保存："+(p.discard()?"保留 "+p.keep()+" 件，仓管清除超额":"全部保留")+(p.warehouse()<0?"（应用到现有所有仓库）":"");
        }
        PacketDistributor.sendToPlayer(player,snapshot(level,s,p.warehouse(),p.page(),p.query(),feedback));
    }
    public static WarehouseSnapshotPayload snapshot(ServerLevel level,SettlementRecord s,int index,int page,String query,String feedback) {
        var all=s.buildings().stream().filter(b->b.active() && WarehouseStock.applies(b)).sorted(Comparator.comparing(BuildingRecord::id)).toList();
        var chosen=index<0?all:List.of(all.get(index));
        var counts=new TreeMap<String,Long>();var samples=new HashMap<String,ItemStack>();long used=0;
        for(var b:chosen) {
            var stock=WarehouseInventoryService.stock(level,b);used+=stock.quantity();
            for(int i=0;i<stock.getContainerSize();i++) {
                var item=stock.getItem(i);if(item.isEmpty())continue;
                String id=BuiltInRegistries.ITEM.getKey(item.getItem()).toString();counts.merge(id,(long)item.getCount(),Long::sum);samples.putIfAbsent(id,item.copyWithCount(1));
            }
        }
        // A valid item ID can configure treatment even when there is no stock yet.
        var key=ResourceLocation.tryParse(query.strip());
        if(key!=null && BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.ITEM.get(key)!=Items.AIR) {
            counts.putIfAbsent(key.toString(),0L);samples.putIfAbsent(key.toString(),new ItemStack(BuiltInRegistries.ITEM.get(key)));
        }
        String filter=query.toLowerCase(Locale.ROOT);var entries=counts.entrySet().stream().filter(e->WarehouseDisplayCategory.matches(samples.get(e.getKey()),filter)
                || !filter.startsWith("category:") && (e.getKey().contains(filter)
                || samples.get(e.getKey()).getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter)
                || WarehouseCategory.classify(samples.get(e.getKey())).serializedName().contains(filter)))
                .sorted(Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey)).toList();
        int pages=Math.max(1,(entries.size()+9)/10);page=Math.min(page,pages-1);var rows=new ArrayList<WarehouseSnapshotPayload.Row>();
        for(var e:entries.subList(page*10,Math.min(entries.size(),page*10+10))) {
            var rule=chosen.isEmpty()?new WarehouseStock.Rule(WarehouseStock.CAPACITY,false):WarehouseInventoryService.stock(level,chosen.get(0)).rule(e.getKey());
            boolean mixed=chosen.stream().anyMatch(b->!WarehouseInventoryService.stock(level,b).rule(e.getKey()).equals(rule));
            rows.add(new WarehouseSnapshotPayload.Row(e.getKey(),samples.get(e.getKey()),e.getValue(),rule.keep(),rule.discard(),mixed));
        }
        String name=index<0?"全部仓库与住宅":(all.get(index).customName().isEmpty()?(all.get(index).type().isHouse()?"住宅 ":"仓库 ")+(index+1):all.get(index).customName());
        if(index>=0 && all.get(index).type().isHouse())feedback="住宅独立库存 · 潜行右击床取放 · 仓管补充口粮、燃料和衣物 · 不执行余量清除";
        if(index>=0)name+=" · "+all.get(index).position().toShortString()+(all.get(index).operational()?"":" · 暂停使用");
        return new WarehouseSnapshotPayload(s.corePosition(),index,all.size(),name,used,(long)chosen.size()*WarehouseStock.CAPACITY,page,pages,query,feedback,index>=0 && all.get(index).type().isHouse(),rows);
    }
}

package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;
import java.util.function.Predicate;

/** Clothing is paid for in a reachable warehouse, crafted on site, and transferred as real ItemStacks. */
public final class CitizenClothingService {
    private CitizenClothingService() { }
    private static final Map<CitizenRecord,Long> RETRY=new WeakHashMap<>();
    private static final Map<CitizenRecord,Long> CORE_GARMENT_RETRY=new WeakHashMap<>();
    private record FuelTrip(long started) { }
    private static final Map<CitizenRecord,FuelTrip> FUEL_TRIPS=new WeakHashMap<>();
    public static int kind(ItemStack s) {return s.is(ModItems.WOOL_COAT.get())?1:s.is(ModItems.LEATHER_COAT.get())?2:0;}
    public static boolean isGarment(ItemStack s) {return kind(s)!=0;}
    private static final String WORN="exiledsettlement_worn_clothing";
    public static boolean isWornGarment(ItemStack stack) {
        return isGarment(stack) && (stack.isDamaged() || stack.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY)
                .copyTag().getBoolean(WORN));
    }
    private static ItemStack worn(ItemStack stack) {
        if(isGarment(stack))net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,stack,tag->tag.putBoolean(WORN,true));
        return stack;
    }
    /** Carry a real spare before leaving the creative source; normal thermal rules choose when to wear it. */
    public static void prepareDeparture(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e){
        if(!s.creativeCoreInstalled() || !c.thermal().garment.isEmpty()
                || c.hasInventoryItem(stack->isGarment(stack) && stack.getDamageValue()<stack.getMaxDamage())
                || !SettlementCoreInteractionService.canReceiveSupply(level,s,e))return;
        Item wanted=desired(c,level);if(wanted==null)wanted=ModItems.WOOL_COAT.get();
        CreativeResidentSupply.give(level,s,c,e,wanted,1);
    }
    public static float insulation(ItemStack s,float wet,float wind) {
        return switch(kind(s)){case 1->ThermalSettings.wool*(1-.45F*wet);case 2->ThermalSettings.leather+wind*5+wet*5;default->0;};
    }
    private static int count(CitizenRecord c,Predicate<ItemStack> match) {return c.inventory().stream().filter(match).mapToInt(ItemStack::getCount).sum();}
    private static Item desired(CitizenRecord c,ServerLevel level) {
        var t=c.thermal();float wind=CitizenTemperatureService.storm(level,c.lastPosition());
        if(t.ambient>=18 && t.body>=36.5F)return null;
        if(t.ambient>=12 && t.body>=36)return t.garment.isEmpty()?null:t.garment.getItem();
        float wool=insulation(new ItemStack(ModItems.WOOL_COAT.get()),t.wet,wind),leather=insulation(new ItemStack(ModItems.LEATHER_COAT.get()),t.wet,wind);
        return leather>wool?ModItems.LEATHER_COAT.get():ModItems.WOOL_COAT.get();
    }
    private static boolean wearCarried(SettlementSavedData data,CitizenRecord c,SettlementCitizenEntity e,Item wanted,long now){
        var t=c.thermal();
        if(wanted==null || t.garment.is(wanted) || now-t.lastSwap<600)return false;
        Predicate<ItemStack> usable=stack->stack.is(wanted) && stack.getDamageValue()<stack.getMaxDamage();
        if(t.garment.isEmpty() && !c.hasInventoryItem(usable))usable=stack->isGarment(stack) && stack.getDamageValue()<stack.getMaxDamage();
        if(!c.hasInventoryItem(usable) || !t.garment.isEmpty() && c.inventoryCapacityFor(t.garment)<1)return false;
        var next=c.extractInventoryItem(usable,1);if(next.isEmpty())return false;
        if(!t.garment.isEmpty())c.insertIntoInventory(worn(t.garment));
        t.garment=worn(next);t.lastSwap=now;e.setVisibleGarment(kind(next));data.touch();return true;
    }
    public static boolean personal(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        if(CitizenInteractionService.held(e))return false;
        if(CitizenWorkService.schedulePhase(level.getDayTime(),c)==CitizenWorkService.SchedulePhase.SLEEP){FUEL_TRIPS.remove(c);return false;}
        if(c.life().task.startsWith("tailor:"))return tailor(level,data,s,c,e);
        var t=c.thermal();long now=level.getGameTime();Item wanted=desired(c,level);
        if(now-t.lastSwap>=600 && (c.workState()==CitizenWorkState.IDLE || t.body<ThermalSettings.warning)) {
            if(wanted==null && !t.garment.isEmpty() && c.inventoryCapacityFor(t.garment)>=1) {
                c.insertIntoInventory(worn(t.garment));t.garment=ItemStack.EMPTY;t.lastSwap=now;e.setVisibleGarment(0);data.touch();
            } else if(wanted!=null && !t.garment.is(wanted)) {
                Predicate<ItemStack> usable=stack->stack.is(wanted) && stack.getDamageValue()<stack.getMaxDamage();
                if(t.garment.isEmpty() && !c.hasInventoryItem(usable))usable=stack->isGarment(stack) && stack.getDamageValue()<stack.getMaxDamage();
                final Predicate<ItemStack> usableClothing=usable;
                if(wearCarried(data,c,e,wanted,now))return false;
                if(s.creativeCoreInstalled() && (t.garment.isEmpty() || c.inventoryCapacityFor(t.garment)>=1)){
                    if(CORE_GARMENT_RETRY.getOrDefault(c,-1L)>now && t.body>=ThermalSettings.warning)return false;
                    receiveCoreGarment(level,data,s,c,e,wanted);
                    if(c.activity()==CitizenActivity.BLOCKED && c.workerAccess.block==null && c.workerAccess.advance==null
                            && t.body>=ThermalSettings.warning){
                        CORE_GARMENT_RETRY.put(c,now+600);
                        SettlementCoreInteractionService.abandon(level,e);
                        return false;
                    }
                    return true;
                }
                var source=stockSource(level,s,c,usableClothing);
                if(source==null && t.garment.isEmpty())source=stockSource(level,s,c,CitizenClothingService::isGarment);
                if(source!=null && c.inventoryCapacityFor(new ItemStack(wanted))>=1) {
                    var stock=CommunityStock.items(level,s,source);
                    Item item=stock.keySet().stream().filter(i->i==wanted).findFirst().orElseGet(()->
                            stock.keySet().stream().filter(i->isGarment(new ItemStack(i))).findFirst().orElse(wanted));
                    if(CommunityLifeService.atStorage(level,c,e,source,item,CitizenActivity.WARMING)) {
                        var next=WarehouseInventoryService.extractItemForResident(level,source,e,item,1);
                        if(!next.isEmpty())c.insertIntoInventory(next);
                        WarehouseInventoryService.releaseInteractionPosition(level,e);CommunityStock.invalidate(level,s);
                    }
                    data.touch();return true;
                }
                if(t.garment.isEmpty() && !t.reason.contains("缺少保温衣物"))t.reason+=" · 缺少保温衣物";
            }
        }
        if(c.homeBuildingId()!=null && t.ambient<12 && !c.hasInventoryItem(st->st.is(Items.COAL)||st.is(Items.CHARCOAL))
                && CitizenWorkService.schedulePhase(level.getDayTime(),c)==CitizenWorkService.SchedulePhase.REST
                && (FUEL_TRIPS.containsKey(c) || RETRY.getOrDefault(c,-1L)<=now)) {
            if(s.creativeCoreInstalled() && c.inventoryCapacityFor(new ItemStack(Items.COAL))>0){
                if(CreativeResidentSupply.atCore(level,s,c,e,CitizenActivity.WARMING))
                    CreativeResidentSupply.give(level,s,c,e,Items.COAL,1);
                data.touch();return true;
            }
            var fuel=stockSource(level,s,c,st->st.is(Items.COAL)||st.is(Items.CHARCOAL));
            if(fuel!=null){
                var trip=FUEL_TRIPS.computeIfAbsent(c,ignored->new FuelTrip(now));
                boolean fetching=now-trip.started<2400 && fetch(level,s,c,e,st->st.is(Items.COAL)||st.is(Items.CHARCOAL),1);
                if(!fetching || c.hasInventoryItem(st->st.is(Items.COAL)||st.is(Items.CHARCOAL))){FUEL_TRIPS.remove(c);RETRY.put(c,now+200);}
                data.touch();return fetching;
            }
            FUEL_TRIPS.remove(c);RETRY.put(c,now+1200);
        }
        if(c.canWork() && c.profession()==CitizenProfession.WORKER && !c.hasManualAssignment() && c.preferredWorksiteId()==null
                && c.workState()==CitizenWorkState.IDLE && c.life().task.isEmpty()
                && CitizenWorkService.schedulePhase(level.getDayTime(),c)==CitizenWorkService.SchedulePhase.WORK
                && RETRY.getOrDefault(c,-1L)<=now) {
            RETRY.put(c,now+1200);
            long alive=s.citizens().stream().filter(CitizenRecord::alive).count();
            for(Item output:List.of(ModItems.WOOL_COAT.get(),ModItems.LEATHER_COAT.get())) {
                long worn=s.citizens().stream().filter(CitizenRecord::alive).filter(r->r.thermal().garment.is(output)).count();
                int stock=CommunityStock.count(level,s,output)+s.citizens().stream().mapToInt(r->count(r,st->st.is(output))).sum();
                Predicate<ItemStack> material=output==ModItems.WOOL_COAT.get()?st->st.is(ItemTags.WOOL):st->st.is(Items.LEATHER);
                int raws=SettlementInventoryService.warehouses(s).stream().flatMap(b->CommunityStock.items(level,s,b).entrySet().stream())
                        .filter(row->material.test(new ItemStack(row.getKey()))).mapToInt(Map.Entry::getValue).sum();
                if(worn+stock>=alive || raws+count(c,material)<4 || CommunityStock.count(level,s,Items.STRING)+count(c,st->st.is(Items.STRING))<2)continue;
                c.life().task=output==ModItems.WOOL_COAT.get()?"tailor:wool":"tailor:leather";
                c.life().taskStarted=now;c.life().workTicks=0;c.life().destination=null;
                return tailor(level,data,s,c,e);
            }
        }
        return false;
    }
    private static BuildingRecord nearestStorage(ServerLevel level,SettlementRecord s,CitizenRecord c) {
        return SettlementInventoryService.warehouses(s).stream().filter(b->!s.community().danger(b.position()))
                .min(Comparator.comparingDouble(b->b.position().distSqr(c.lastPosition()))).orElse(null);
    }
    private static BuildingRecord stockSource(ServerLevel level,SettlementRecord s,CitizenRecord c,Predicate<ItemStack> match) {
        var home=s.building(c.homeBuildingId());
        if(home!=null && home.active() && home.operational() && !s.community().danger(home.position())
                && CommunityStock.items(level,s,home).entrySet().stream().anyMatch(row->row.getValue()>0 && match.test(new ItemStack(row.getKey()))))return home;
        return SettlementInventoryService.warehouses(s).stream().filter(b->!s.community().danger(b.position()))
                .filter(b->CommunityStock.items(level,s,b).entrySet().stream().anyMatch(row->row.getValue()>0 && match.test(new ItemStack(row.getKey()))))
                .min(Comparator.comparingDouble(b->b.position().distSqr(c.lastPosition()))).orElse(null);
    }
    private static boolean fetch(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,Predicate<ItemStack> match,int amount) {
        var b=stockSource(level,s,c,match);if(b==null){CommunityLifeService.blocked(c,e,"衣物制作缺少原料");return false;}
        Item item=CommunityStock.items(level,s,b).keySet().stream().filter(i->match.test(new ItemStack(i))).findFirst().orElse(Items.AIR);
        if(CommunityLifeService.atStorage(level,c,e,b,item,CitizenActivity.WARMING)) {
            int cap=c.inventoryCapacityFor(new ItemStack(item));
            if(cap<=0){CommunityLifeService.blocked(c,e,"背包已满，衣物原料保留");return false;}
            c.insertIntoInventory(WarehouseInventoryService.extractItemForResident(level,b,e,item,Math.min(cap,amount)));
            WarehouseInventoryService.releaseInteractionPosition(level,e);CommunityStock.invalidate(level,s);
        }return true;
    }
    private static boolean tailor(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var life=c.life();
        if(life.task.equals("tailor:deliver")) {
            var stack=c.inventory().stream().filter(st->isGarment(st) && !isWornGarment(st)).findFirst().orElse(ItemStack.EMPTY);
            if(stack.isEmpty()){life.clearTask();return false;}
            var storage=SettlementInventoryService.warehouses(s).stream().filter(b->b.accepts(stack) && !s.community().danger(b.position()))
                    .min(Comparator.comparingDouble(b->b.position().distSqr(c.lastPosition()))).orElse(null);
            if(storage==null){CommunityLifeService.blocked(c,e,"衣物已制成，缺少接受衣物的仓储");return true;}
            var station=WarehouseInventoryService.approachPosition(level,storage,e);
            if(station==null)station=WarehouseInventoryService.reserveInteractionPosition(level,storage,e);
            if(station==null){CommunityLifeService.blocked(c,e,"衣物交货位置不可达");return true;}
            CommunityLifeService.move(c,e,station,CitizenActivity.WARMING,"将制成的衣物送入仓储");
            if(WarehouseInventoryService.canInteract(level,storage,e)) {
                var rest=WarehouseInventoryService.insertForResident(level,storage,e,stack.copyWithCount(1));
                if(rest.isEmpty()){c.extractInventoryItem(st->ItemStack.isSameItemSameComponents(st,stack),1);life.clearTask();
                    WarehouseInventoryService.releaseInteractionPosition(level,e);CommunityStock.invalidate(level,s);}
                else CommunityLifeService.blocked(c,e,"衣物已制成，仓储已满");
            }
            data.touch();return true;
        }
        if(CitizenWorkService.schedulePhase(level.getDayTime(),c)!=CitizenWorkService.SchedulePhase.WORK)return false;
        if(level.getGameTime()-life.taskStarted>12000){life.clearTask();data.touch();return false;}
        boolean wool=life.task.equals("tailor:wool");Predicate<ItemStack> material=wool?st->st.is(ItemTags.WOOL):st->st.is(Items.LEATHER);
        Item output=wool?ModItems.WOOL_COAT.get():ModItems.LEATHER_COAT.get();
        if(count(c,material)<4) {if(!fetch(level,s,c,e,material,4-count(c,material)))life.clearTask();}
        else if(count(c,st->st.is(Items.STRING))<2){if(!fetch(level,s,c,e,st->st.is(Items.STRING),2-count(c,st->st.is(Items.STRING))))life.clearTask();}
        else {
            var b=s.building(life.destination);if(b==null || !b.active() || !b.operational()) {
                b=nearestStorage(level,s,c);life.destination=b==null?null:b.id();
            }
            if(b==null){life.clearTask();data.touch();return false;}
            var station=WarehouseInventoryService.approachPosition(level,b,e);
            if(station==null)station=WarehouseInventoryService.reserveInteractionPosition(level,b,e);
            if(station==null){CommunityLifeService.blocked(c,e,"衣物制作操作点不可达");}
            else {
                CommunityLifeService.move(c,e,station,CitizenActivity.WARMING,"现场制作衣物");
                if(WarehouseInventoryService.canInteract(level,b,e)) {
                    life.workTicks+=CitizenWorkService.WORK_INTERVAL_TICKS;
                    if(life.workTicks>=ThermalSettings.tailorTicks && c.inventoryCapacityFor(new ItemStack(output))>=1) {
                        consume(c,material,4);consume(c,st->st.is(Items.STRING),2);
                        c.insertIntoInventory(new ItemStack(output));life.clearTask();life.task="tailor:deliver";
                        WarehouseInventoryService.releaseInteractionPosition(level,e);CommunityStock.invalidate(level,s);
                    }
                }
            }
        }
        data.touch();return true;
    }
    private static void receiveCoreGarment(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,Item wanted){
        if(!CreativeResidentSupply.atCore(level,s,c,e,CitizenActivity.WARMING))return;
        var t=c.thermal();if(!t.garment.isEmpty())c.insertIntoInventory(worn(t.garment));
        t.garment=worn(new ItemStack(wanted));t.lastSwap=level.getGameTime();e.setVisibleGarment(kind(t.garment));
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"creative_core_garment",s.corePosition(),wanted.toString(),1);
        data.touch();
    }
    public static boolean warm(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var t=c.thermal();var home=s.building(c.homeBuildingId());long now=level.getGameTime();
        Item wanted=desired(c,level);
        // Cold recovery runs before personal clothing selection. Consume the
        // resident's real carried coat before starting another source trip.
        wearCarried(data,c,e,wanted,now);
        if(s.creativeCoreInstalled() && t.garment.isEmpty() && wanted!=null
                && !(home!=null && home.active() && home.operational() && home.contains(e.blockPosition())
                    && CitizenTemperatureService.sheltered(level,e.blockPosition()) && HomeHeatData.get(level).warm(home.id(),now))){
            receiveCoreGarment(level,data,s,c,e,wanted);return true;
        }
        Predicate<ItemStack> fuel=st->st.is(Items.COAL) || st.is(Items.CHARCOAL);
        if(home!=null && home.active() && home.operational()) {
            if(home.contains(e.blockPosition()) && CitizenTemperatureService.sheltered(level,e.blockPosition())) {
                if(!HomeHeatData.get(level).warm(home.id(),now) && c.hasInventoryItem(fuel)) {
                    c.extractInventoryItem(fuel,1);HomeHeatData.get(level).light(home.id(),now);s.community().coldFuelBurned++;data.touch();
                }
                if(HomeHeatData.get(level).warm(home.id(),now)) {
                    e.stopSettlementNavigation();c.setActivity(CitizenActivity.WARMING,"住宅正在供热，体温逐渐恢复");e.setActivity(CitizenActivity.WARMING);return true;
                }
            }
            if(!c.hasInventoryItem(fuel) && !HomeHeatData.get(level).warm(home.id(),now)) {
                if(s.creativeCoreInstalled() && c.inventoryCapacityFor(new ItemStack(Items.COAL))>0 && t.body>=ThermalSettings.injury){
                    if(CreativeResidentSupply.atCore(level,s,c,e,CitizenActivity.WARMING))
                        CreativeResidentSupply.give(level,s,c,e,Items.COAL,1);
                    data.touch();return true;
                }
                var source=stockSource(level,s,c,fuel);
                if(source!=null && t.body>=ThermalSettings.injury) {
                    fetch(level,s,c,e,fuel,1);data.touch();return true;
                }
            }
            var points=new ArrayList<BlockPos>();
            if(c.bedPosition()!=null)points.add(c.bedPosition());
            for(var entrance:BlueprintTemplates.createForBuilding(home).entrances())points.add(entrance.position().relative(entrance.outward().getOpposite()));
            BlockPos reachable=points.stream().filter(e::canStandAtPosition).filter(e::hasReachablePathTo)
                    .min(Comparator.comparingDouble(p->p.distSqr(e.blockPosition()))).orElse(null);
            if(reachable!=null) {
                CommunityLifeService.move(c,e,reachable,CitizenActivity.WARMING,HomeHeatData.get(level).warm(home.id(),now)||c.hasInventoryItem(fuel)?"携带燃料回家取暖":"住宅缺燃料，先避风");return true;
            }
        }
        // A failed home route must not strand a cold resident outside indefinitely.
        for(var b:s.buildings())if(b.active() && b.operational() && BlueprintTemplates.supports(b.type()) && !s.community().danger(b.position())) {
            for(var entrance:BlueprintTemplates.createForBuilding(b).entrances()) {
                var at=entrance.position().relative(entrance.outward().getOpposite());
                if(CitizenTemperatureService.sheltered(level,at) && e.canStandAtPosition(at) && e.hasReachablePathTo(at)) {
                    CommunityLifeService.move(c,e,at,CitizenActivity.WARMING,"住宅路线不可达，先到附近建筑避风");return true;
                }
            }
        }
        CommunityLifeService.blocked(c,e,"受寒：缺少燃料或可达遮蔽，请检查住宅路线");return true;
    }
    public static boolean canRecoverWhileSleeping(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var home=s.building(c.homeBuildingId());var bed=c.bedPosition();
        if(home==null || !home.active() || !home.operational() || bed==null
                || !home.servicePositions().contains(bed) || !home.contains(e.blockPosition())
                || !(level.getBlockState(bed).getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                || !CitizenTemperatureService.sheltered(level,bed))return false;
        if(HomeHeatData.get(level).warm(home.id(),level.getGameTime()))return true;
        var t=c.thermal();
        float environment=Math.max(5,CitizenTemperatureService.ambient(level,bed)+6);
        float felt=environment-t.wet*8+insulation(t.garment,t.wet,0);
        return 37-Math.max(0,12-felt)*.11F>=ThermalSettings.resume;
    }
    private static void consume(CitizenRecord c,Predicate<ItemStack> match,int count) {
        while(count>0){var part=c.extractInventoryItem(match,count);if(part.isEmpty())throw new IllegalStateException("Missing paid clothing material");count-=part.getCount();}
    }
    public static int reserved(CitizenRecord c,int slot) {
        var stack=c.inventoryItem(slot);Predicate<ItemStack> match;int reserve;
        if(isWornGarment(stack))return stack.getCount();
        if(c.life().task.equals("tailor:wool") && stack.is(ItemTags.WOOL)){match=s->s.is(ItemTags.WOOL);reserve=4;}
        else if(c.life().task.equals("tailor:leather") && stack.is(Items.LEATHER)){match=s->s.is(Items.LEATHER);reserve=4;}
        else if(c.life().task.startsWith("tailor:") && stack.is(Items.STRING)){match=s->s.is(Items.STRING);reserve=2;}
        else if(c.homeBuildingId()!=null && c.thermal().ambient<12 && (stack.is(Items.COAL)||stack.is(Items.CHARCOAL))) {
            match=s->s.is(Items.COAL)||s.is(Items.CHARCOAL);reserve=1;
        } else return 0;
        for(int i=0;i<slot;i++)if(match.test(c.inventoryItem(i)))reserve-=c.inventoryItem(i).getCount();
        return Math.min(stack.getCount(),Math.max(0,reserve));
    }
}

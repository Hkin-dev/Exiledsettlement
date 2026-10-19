package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.region.ProductionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;

/** Physical, saved warehouse tasks. Read indexes never manufacture or remove inventory. */
public final class WarehouseStewardService {
    private WarehouseStewardService() { }
    private static final Map<CitizenRecord, Long> AUDITS = new WeakHashMap<>();
    private static final Map<SettlementRecord, Reserve> RESERVES = new WeakHashMap<>();
    private record Reserve(long until, int seeds, long signature) { }
    private static final String PREFIX = "steward:";

    public static boolean handles(CitizenRecord c) { return c.life().task.startsWith(PREFIX); }
    public static boolean overflow(BuildingRecord b) {
        return b.type() == BuildingType.WAREHOUSE && b.warehouseCategoryMask() == WarehouseCategory.MATERIALS.bit();
    }
    public static int stock(ServerLevel level, SettlementRecord s, Item item) {
        return SettlementInventoryService.warehouses(s).stream()
                .mapToInt(b -> CommunityStock.items(level, s, b).getOrDefault(item, 0)).sum();
    }
    public static int grain(ServerLevel level, SettlementRecord s) {
        return (int)Math.min(Integer.MAX_VALUE, stock(level,s,Items.WHEAT) + 9L*stock(level,s,Items.HAY_BLOCK));
    }
    public static int seedReserve(ServerLevel level, SettlementRecord s) {
        var old = RESERVES.get(s); long now = level.getGameTime();
        long signature=s.community().seedRounds;
        for(var b:s.buildings())if(b.type()==BuildingType.FARM_FIELD)
            signature=31*signature+Objects.hash(b.id(),b.active(),b.productionId(),b.region()==null?0:b.region().cells.size(),b.region()==null?0:b.region().revision,b.productionSite()==null?0:b.productionSite().workCells().hashCode());
        for(var b:s.blueprints())if(b.type()==BuildingType.FARM_FIELD)
            signature=31*signature+Objects.hash(b.id(),b.cancellationQueued(),b.region()==null?0:b.region().cells.size(),b.region()==null?0:b.region().revision,b.productionSite()==null?0:b.productionSite().workCells().hashCode());
        if (old != null && old.signature==signature && now < old.until && now >= old.until - 200) return old.seeds;
        Set<Long> cells = new HashSet<>();
        for (var b : s.buildings()) if (b.active() && b.type() == BuildingType.FARM_FIELD) {
            if (b.productionSite()!=null) addCells(cells,b.productionSite());
            else if (b.region() != null) addCells(cells,b.region());
            else if (BuildingProductionService.seed(b.productionId()) == Items.WHEAT_SEEDS)
                BlueprintTemplates.createForBuilding(b).placements().stream().filter(p -> p.state().is(Blocks.FARMLAND))
                        .forEach(p -> cells.add(ProductionRegion.key(p.position().getX(),p.position().getZ())));
        }
        for (var b : s.blueprints()) if (!b.cancellationQueued() && b.type() == BuildingType.FARM_FIELD) {
            if (b.productionSite()!=null) addCells(cells,b.productionSite());
            else if (b.region()!=null) addCells(cells,b.region());
            else BlueprintTemplates.create(b).placements().stream().filter(p -> p.state().is(Blocks.FARMLAND))
                    .forEach(p -> cells.add(ProductionRegion.key(p.position().getX(),p.position().getZ())));
        }
        int reserve = (int)Math.min(Integer.MAX_VALUE,32L + (long)cells.size()*s.community().seedRounds);
        RESERVES.put(s,new Reserve(now+200,reserve,signature)); return reserve;
    }
    private static void addCells(Set<Long> cells, ProductionRegion r) {
        r.cells.values().stream().filter(c -> c.use()==ProductionRegion.Use.CROP
                && BuildingProductionService.seed(BuildingProductionService.cropAt(c.production(),c.ground()))==Items.WHEAT_SEEDS)
                .forEach(c -> cells.add(ProductionRegion.key(c.ground().getX(),c.ground().getZ())));
    }
    private static void addCells(Set<Long> cells,com.hkin.exiledsettlement.region.ProductionSiteSnapshot site) {
        site.workCells().stream().filter(c->c.use()==ProductionRegion.Use.CROP
                && BuildingProductionService.seed(BuildingProductionService.cropAt(c.production(),c.ground()))==Items.WHEAT_SEEDS)
                .forEach(c->cells.add(ProductionRegion.key(c.ground().getX(),c.ground().getZ())));
    }
    public static boolean pauseWheat(ServerLevel level, SettlementRecord s) {
        if(SettlementInventoryService.warehouses(s).stream().anyMatch(WarehouseStock::applies)) {
            if(s.community().wheatPaused){s.community().wheatPaused=false;SettlementSavedData.get(level).touch();}
            return false;
        }
        var c=s.community(); boolean before=c.wheatPaused;
        int amount=grain(level,s);
        if (!c.stewardEnabled || c.wheatTarget==0) c.wheatPaused=false;
        else if(amount>=c.wheatTarget) c.wheatPaused=true;
        else if(amount<=c.wheatTarget/2) c.wheatPaused=false;
        if(before!=c.wheatPaused) SettlementSavedData.get(level).touch();
        return c.wheatPaused;
    }
    private static BuildingRecord source(ServerLevel level, SettlementRecord s, CitizenRecord c, Item item, int count,
                                         boolean ordinaryOnly) {
        return SettlementInventoryService.warehouses(s).stream().filter(b -> !s.community().danger(b.position())
                && (!ordinaryOnly || !overflow(b)) && WarehouseInventoryService.countItem(level,b,item)>=count)
                .min(Comparator.comparingDouble(b -> b.position().distSqr(c.lastPosition()))).orElse(null);
    }
    private static void start(ServerLevel level,CitizenRecord c,BuildingRecord b,String kind,Item in,int amount,Item out,int made) {
        var l=c.life(); l.task=PREFIX+kind; l.destination=b.id(); l.taskStarted=level.getGameTime(); l.workTicks=0;
        l.cookingInput=id(in); l.cookingOutput=id(out); l.cookingInputCount=amount; l.cookingOutputCount=made;
        l.cookingStage=0; l.cookingFuel=""; l.stewardTarget=null;
    }
    public static boolean assign(ServerLevel level,SettlementRecord s,CitizenRecord c) {
        var state=s.community(); long now=level.getGameTime();
        if(!state.stewardEnabled || c.profession()!=CitizenProfession.WAREHOUSE_KEEPER
                || handles(c) || !c.life().task.isEmpty() || now<AUDITS.getOrDefault(c,-1L))return false;
        AUDITS.put(c,now+200); state.stewardStatus="库存整理待命";
        var assigned=s.building(c.workBuildingId());
        if(assigned!=null && WarehouseStock.applies(assigned)) {
            if(!assigned.active() || !assigned.operational() || s.community().danger(assigned.position()))return false;
            if(HomeDeliveryService.assign(level,s,c,assigned))return true;
            var stock=WarehouseInventoryService.stock(level,assigned);
            for(var entry:WarehouseInventoryService.summarize(level,assigned).entrySet()) {
                var rule=stock.rule(id(entry.getKey()));
                if(!rule.discard() || entry.getValue()<=rule.keep()
                        || entry.getKey()==com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get())continue;
                var sample=new ItemStack(entry.getKey());
                int n=Math.min(entry.getValue()-rule.keep(),Math.min(sample.getMaxStackSize(),c.inventoryCapacityFor(sample)));
                if(n>0){start(level,c,assigned,"discard",entry.getKey(),n,entry.getKey(),n);return true;}
            }
            return false;
        }
        // A bounded housekeeping turn must not be starved by endless bread/overflow jobs.
        if((now/200)%2==0 && SettlementInventoryService.warehouses(s).stream()
                .anyMatch(b->WarehouseInventoryService.needsOrganization(level,b)))return false;
        if(s.building(c.workBuildingId())==null)return false;
        int bread=stock(level,s,Items.BREAD);
        if(state.mealTarget>0 && bread<state.mealTarget) {
            var b=source(level,s,c,Items.WHEAT,3,false);
            if(b!=null && canMake(c,Items.WHEAT,3,Items.BREAD,1)) {
                start(level,c,b,"bread",Items.WHEAT,3,Items.BREAD,1); return true;
            }
            b=source(level,s,c,Items.HAY_BLOCK,1,false);
            if(b!=null && canMake(c,Items.HAY_BLOCK,1,Items.WHEAT,9)) {
                start(level,c,b,"unpack",Items.HAY_BLOCK,1,Items.WHEAT,9); return true;
            }
        }
        // Alternate overflow work to avoid a huge snowy winter starving seed disposal.
        boolean seedsFirst=(now/200)%2==0;
        if(seedsFirst && assignCompost(level,s,c))return true;
        int snow=SettlementInventoryService.warehouses(s).stream().filter(b -> !overflow(b))
                .mapToInt(b -> CommunityStock.items(level,s,b).getOrDefault(Items.SNOWBALL,0)).sum();
        if(snow>state.snowLimit) {
            var b=source(level,s,c,Items.SNOWBALL,1,true);
            var dest=destination(level,s,c,new ItemStack(Items.SNOWBALL),true);
            if(b!=null && dest!=null && c.inventoryCapacityFor(new ItemStack(Items.SNOWBALL))>0) {
                int n=Math.min(16,Math.min(snow-state.snowLimit,c.inventoryCapacityFor(new ItemStack(Items.SNOWBALL))));
                start(level,c,b,"snow",Items.SNOWBALL,n,Items.SNOWBALL,n);return true;
            }
            state.stewardStatus="雪球超额：需要可达、有空间的仅材料仓库及仓管背包空间";
        }
        if(!seedsFirst && assignCompost(level,s,c))return true;
        int loose=stock(level,s,Items.WHEAT);
        int looseReserve=Math.max(24,state.mealTarget*3);
        if(loose>=looseReserve+9) {
            var b=source(level,s,c,Items.WHEAT,9,false);
            if(b!=null && canMake(c,Items.WHEAT,9,Items.HAY_BLOCK,1)) {
                start(level,c,b,"hay",Items.WHEAT,9,Items.HAY_BLOCK,1);return true;
            }
        }
        return false;
    }
    private static boolean assignCompost(ServerLevel level,SettlementRecord s,CitizenRecord c) {
        int excess=stock(level,s,Items.WHEAT_SEEDS)+count(c,Items.WHEAT_SEEDS)-seedReserve(level,s);
        if(excess<=0)return false;
        if(stock(level,s,Items.BONE_MEAL)>=s.community().boneTarget) {
            s.community().stewardStatus="种子超额：骨粉已达目标，暂停堆肥";return false;
        }
        var b=source(level,s,c,Items.WHEAT_SEEDS,1,false);
        if(b==null && count(c,Items.WHEAT_SEEDS)>0)b=s.building(c.workBuildingId());
        if(b==null || c.inventoryCapacityFor(new ItemStack(Items.WHEAT_SEEDS))<1)return false;
        BlockPos target=findComposter(level,s,b);
        if(target==null){s.community().stewardStatus="种子超额：请在仓库附近领地内放置可达堆肥桶";return false;}
        start(level,c,b,"compost",Items.WHEAT_SEEDS,
                Math.min(32,Math.min(excess,count(c,Items.WHEAT_SEEDS)+c.inventoryCapacityFor(new ItemStack(Items.WHEAT_SEEDS)))),Items.BONE_MEAL,0);
        c.life().stewardTarget=target;return true;
    }
    private static BlockPos findComposter(ServerLevel level,SettlementRecord s,BuildingRecord b) {
        for(BlockPos p:BlockPos.betweenClosed(b.position().offset(-8,-2,-8),b.position().offset(8,3,8)))
            if(s.contains(p) && !s.community().danger(p) && level.hasChunkAt(p) && level.getBlockState(p).is(Blocks.COMPOSTER)
                    && s.citizens().stream().noneMatch(c -> handles(c) && p.equals(c.life().stewardTarget)))return p.immutable();
        return null;
    }
    /** Emergency bread is available to residents even without a keeper, restaurant or coal. */
    public static boolean personalBread(ServerLevel level,SettlementSavedData data,SettlementRecord s,
                                        CitizenRecord c,SettlementCitizenEntity e) {
        if(handles(c) && !c.life().task.startsWith(PREFIX+"personal"))finish(level,data,c,e);
        if(!handles(c)) {
            var b=source(level,s,c,Items.WHEAT,Math.max(1,3-count(c,Items.WHEAT)),false);
            if(b==null && count(c,Items.WHEAT)>=3)b=SettlementInventoryService.nearestWarehouse(s,c.lastPosition());
            if(b!=null)start(level,c,b,"personal",Items.WHEAT,3,Items.BREAD,1);
            else {
                b=source(level,s,c,Items.HAY_BLOCK,1,false);
                if(b==null)return false;
                start(level,c,b,"personal_unpack",Items.HAY_BLOCK,1,Items.WHEAT,9);
            }
        }
        return work(level,data,s,c,e);
    }
    private static boolean canMake(CitizenRecord c,Item in,int n,Item out,int made) {
        // Ingredients can be collected incrementally; consumption may free the last backpack slot.
        return count(c,in)>=n || c.inventoryCapacityFor(new ItemStack(in))>=Math.max(0,n-count(c,in));
    }
    public static boolean work(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        if(HomeDeliveryService.handles(c))return HomeDeliveryService.work(level,data,s,c,e);
        var l=c.life(); var b=s.building(l.destination);Item in=item(l.cookingInput),out=item(l.cookingOutput);
        boolean personal=l.task.startsWith(PREFIX+"personal");
        if(!personal && b!=null && WarehouseStock.applies(b)
                && (!l.task.equals(PREFIX+"discard") || !java.util.Objects.equals(c.workBuildingId(),l.destination))) {
            finish(level,data,c,e);return false;
        }
        if(b==null || !b.active() || !b.operational() || s.community().danger(b.position())
                || in==Items.AIR || out==Items.AIR || level.getGameTime()<l.taskStarted || level.getGameTime()-l.taskStarted>4800
                || !personal && (!s.community().stewardEnabled || c.profession()!=CitizenProfession.WAREHOUSE_KEEPER)) {
            finish(level,data,c,e);return false;
        }
        if(!personal)s.community().stewardStatus=switch(l.task.substring(PREFIX.length())) {
            case "bread" -> l.cookingStage==0?"仓管领取小麦":l.cookingStage==1?"仓管合成面包":"仓管交付面包";
            case "hay" -> "仓管打包长期余粮";case "unpack" -> "仓管拆取干草块备餐";
            case "snow" -> "仓管搬运超额雪球";case "compost" -> l.cookingStage>=2?"仓管交付堆肥产物":"仓管处理超额种子";
            default -> "库存整理待命";
        };
        if(l.cookingStage==0) {
            int missing=l.cookingInputCount-count(c,in);
            if(missing<=0)l.cookingStage=1;
            else {
                if(!CommunityLifeService.atStorage(level,c,e,b,in,CitizenActivity.COOKING))return true;
                int allowed=missing;
                if(l.task.equals(PREFIX+"compost"))allowed=Math.min(allowed,Math.max(0,stock(level,s,in)-seedReserve(level,s)));
                if(allowed<=0){finish(level,data,c,e);return false;}
                ItemStack taken=WarehouseInventoryService.extractItemForResident(level,b,e,in,allowed,c::inventoryCapacityFor);
                ItemStack remainder=c.insertIntoInventory(taken);
                if(!remainder.isEmpty())throw new IllegalStateException("Preflighted warehouse input did not fit");
                if(taken.isEmpty()) {
                    CommunityLifeService.blocked(c,e,"仓管取料暂停：库存已变或背包空间不足");
                    if(WarehouseInventoryService.countItem(level,b,in)==0)finish(level,data,c,e);
                } else {
                    SaveAcceptanceService.action(level,c,"steward_fetch",b.position(),id(in),taken.getCount());
                    CommunityStock.invalidate(level,s);
                    if(count(c,in)>=l.cookingInputCount)l.cookingStage=1;
                    else if(l.task.equals(PREFIX+"compost")){l.cookingInputCount=count(c,in);l.cookingStage=1;}
                }
            }
        } else if(l.cookingStage==1) {
            if(l.task.equals(PREFIX+"discard")) {
                if(!CommunityLifeService.atStorage(level,c,e,b,in,CitizenActivity.COOKING))return true;
                var rule=WarehouseInventoryService.stock(level,b).rule(id(in));
                if(!rule.discard()){l.cookingStage=2;data.touch();return true;}
                l.workTicks+=(int)(CitizenWorkService.WORK_INTERVAL_TICKS * c.professionExperience().multiplier(c.profession()));
                if(l.workTicks>=100) {
                    int held=Math.min(l.cookingInputCount,count(c,in));
                    int n=Math.min(held,Math.max(0,WarehouseInventoryService.countItem(level,b,in)+held-rule.keep()));
                    if(n>0){consumeInput(c,in,n);SaveAcceptanceService.action(level,c,"warehouse_discard",b.position(),id(in),n);}
                    l.cookingOutputCount=held-n;
                    if(l.cookingOutputCount==0){finish(level,data,c,e);return true;}
                    l.cookingStage=2;
                }
                data.touch();return true;
            }
            if(l.task.equals(PREFIX+"compost"))return compost(level,data,s,c,e);
            if(l.task.equals(PREFIX+"snow")){l.cookingStage=2;data.touch();return true;}
            if(!CommunityLifeService.atStorage(level,c,e,b,in,CitizenActivity.COOKING))return true;
            l.workTicks+=(int)(CitizenWorkService.WORK_INTERVAL_TICKS * c.professionExperience().multiplier(c.profession()));
            if(l.workTicks>=100) {
                if(count(c,in)<l.cookingInputCount){l.cookingStage=0;l.workTicks=0;}
                else if(personal && out==Items.BREAD && c.nutrition()<=70) {
                    var meal=takeCraftedMeal(level,s,c);
                    if(!meal.isEmpty()) {
                        SaveAcceptanceService.action(level,c,"steward_craft",b.position(),id(out),1);
                        CommunityLifeService.consumeMeal(level,data,s,c,e,meal,CommunityLifeService.day(level));
                        finish(level,data,c,e);return true;
                    }
                    CommunityLifeService.blocked(c,e,"面包合成配方不匹配，保留原料");
                }
                else if(completeCraft(level,s,c)) {
                    SaveAcceptanceService.action(level,c,"steward_craft",b.position(),id(out),l.cookingOutputCount);
                    if(l.task.equals(PREFIX+"personal_unpack")) {
                        start(level,c,b,"personal",Items.WHEAT,3,Items.BREAD,1);data.touch();return true;
                    }
                    if(personal){finish(level,data,c,e);return true;}
                    l.cookingStage=2;
                } else CommunityLifeService.blocked(c,e,"仓管合成暂停：配方不匹配或产物无背包空间");
            }
        } else if(l.cookingStage==3 && l.task.equals(PREFIX+"compost")) {
            // The entity's normal close-range pickup can run before this work cycle.
            if(count(c,out)>0) {
                l.cookingStage=2;l.cookingOutputCount=1;
                SaveAcceptanceService.action(level,c,"steward_compost_pickup",l.stewardTarget,id(out),1);
                data.touch();return true;
            }
            var drops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(l.stewardTarget).inflate(3),
                    i -> i.getItem().is(Items.BONE_MEAL) && !i.hasPickUpDelay()
                            && CitizenInteractionRange.visible(e,i.blockPosition()));
            var drop=drops.stream().min(Comparator.comparingDouble(i -> i.distanceToSqr(e))).orElse(null);
            if(drop!=null && c.inventoryCapacityFor(drop.getItem())>0) {
                var rem=c.insertIntoInventory(drop.getItem().copyWithCount(1));
                if(rem.isEmpty()){drop.getItem().shrink(1);if(drop.getItem().isEmpty())drop.discard();
                    l.cookingStage=2;l.cookingOutputCount=1;SaveAcceptanceService.action(level,c,"steward_compost_pickup",drop.blockPosition(),id(out),1);}
            } else {
                l.workTicks+=CitizenWorkService.WORK_INTERVAL_TICKS;
                if(l.workTicks>=200)finish(level,data,c,e); // Another resident may already have picked up the real output.
            }
        } else {
            int n=Math.min(l.cookingOutputCount,count(c,out));
            if(n<=0){finish(level,data,c,e);return false;}
            var available=c.inventory().stream().filter(i -> i.is(out)).findFirst().orElse(ItemStack.EMPTY);
            n=Math.min(n,available.getCount());
            ItemStack stack=available.copyWithCount(n);
            var dest=destination(level,s,c,stack,l.task.equals(PREFIX+"snow"));
            if(dest==null){CommunityLifeService.blocked(c,e,"仓管交货暂停：目标仓库满载、过滤拒绝或生活空格预留");return true;}
            var station=WarehouseInventoryService.approachPosition(level,dest,e);
            if(station==null)station=WarehouseInventoryService.reserveInsertionPosition(level,dest,e,stack);
            if(station==null){CommunityLifeService.blocked(c,e,"仓管交货点不可达");return true;}
            CommunityLifeService.move(c,e,station,CitizenActivity.TO_WAREHOUSE,"");
            if(WarehouseInventoryService.canInteract(level,dest,e)) {
                var rem=WarehouseInventoryService.insertForResident(level,dest,e,stack);
                int moved=n-rem.getCount();
                if(moved>0){c.extractInventoryItem(i -> ItemStack.isSameItemSameComponents(i,stack),moved);
                    l.cookingOutputCount-=moved;CommunityStock.invalidate(level,s);
                    SaveAcceptanceService.action(level,c,"steward_deliver",dest.position(),id(out),moved);}
                if(l.cookingOutputCount<=0)finish(level,data,c,e);
            }
        }
        data.touch();return true;
    }
    private static BuildingRecord destination(ServerLevel level,SettlementRecord s,CitizenRecord c,ItemStack stack,boolean snow) {
        return SettlementInventoryService.warehouses(s).stream().filter(b -> !s.community().danger(b.position())
                && (!snow || overflow(b)) && WarehouseInventoryService.canAccept(level,b,stack))
                .min(Comparator.comparingDouble(b -> b.position().distSqr(c.lastPosition()))).orElse(null);
    }
    /** Native crafting recipe and a simulated post-consumption backpack, before any mutation. */
    public static boolean completeCraft(ServerLevel level,SettlementRecord s,CitizenRecord c) {
        var l=c.life();Item in=item(l.cookingInput),out=item(l.cookingOutput);int n=l.cookingInputCount;
        if(l.cookingStage!=1 || count(c,in)<n)return false;
        var made=craftOutput(level,c);
        if(made.isEmpty())return false;
        int room=0,left=n;
        for(var i:c.inventory()) {
            int consume=i.is(in)?Math.min(left,i.getCount()):0;left-=consume;
            if(i.isEmpty() || i.getCount()==consume)room+=made.getMaxStackSize();
            else if(ItemStack.isSameItemSameComponents(i,made))room+=i.getMaxStackSize()-i.getCount();
        }
        if(room<made.getCount())return false;
        consumeInput(c,in,n);
        if(!c.insertIntoInventory(made).isEmpty())throw new IllegalStateException("Preflighted craft did not fit");
        if(out==Items.BREAD)s.community().mealsProduced+=made.getCount();
        l.cookingStage=2;return true;
    }
    /** A hungry resident eats the physical recipe result at the station, even with a full backpack. */
    public static ItemStack takeCraftedMeal(ServerLevel level,SettlementRecord s,CitizenRecord c) {
        var l=c.life();
        if(!l.task.equals(PREFIX+"personal") || l.cookingStage!=1 || count(c,Items.WHEAT)<l.cookingInputCount)return ItemStack.EMPTY;
        var made=craftOutput(level,c);
        if(!made.is(Items.BREAD) || made.getCount()!=1)return ItemStack.EMPTY;
        consumeInput(c,Items.WHEAT,l.cookingInputCount);s.community().mealsProduced++;l.cookingStage=2;return made;
    }
    private static void consumeInput(CitizenRecord c,Item in,int n) {
        int left=n;while(left>0)left-=c.extractInventoryItem(i -> i.is(in),left).getCount();
    }
    private static ItemStack craftOutput(ServerLevel level,CitizenRecord c) {
        var l=c.life();Item in=item(l.cookingInput),out=item(l.cookingOutput);int n=l.cookingInputCount;
        int width=in==Items.WHEAT && out==Items.HAY_BLOCK?3:in==Items.WHEAT?3:1;
        int height=out==Items.HAY_BLOCK?3:1;
        List<ItemStack> inputs=new ArrayList<>();for(int i=0;i<width*height;i++)inputs.add(new ItemStack(in));
        CraftingInput input=CraftingInput.of(width,height,inputs);
        var recipe=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElse(null);
        if(recipe==null || n!=width*height)return ItemStack.EMPTY;
        var made=recipe.value().assemble(input,level.registryAccess());
        if(!made.is(out) || made.getCount()!=l.cookingOutputCount
                || recipe.value().getRemainingItems(input).stream().anyMatch(i -> !i.isEmpty()))return ItemStack.EMPTY;
        return made;
    }
    private static boolean compost(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        var l=c.life();var p=l.stewardTarget;
        if(p==null || !s.contains(p) || s.community().danger(p) || !level.hasChunkAt(p)
                || !level.getBlockState(p).is(Blocks.COMPOSTER)){finish(level,data,c,e);return false;}
        if(stock(level,s,Items.BONE_MEAL)>=s.community().boneTarget){finish(level,data,c,e);return false;}
        if(!CitizenInteractionRange.visible(e,p)) {
            var stand=Direction.Plane.HORIZONTAL.stream().map(p::relative).filter(e::canStandAtPosition)
                    .min(Comparator.comparingDouble(q -> q.distSqr(c.lastPosition()))).orElse(null);
            if(stand==null){CommunityLifeService.blocked(c,e,"堆肥桶没有可达作业面");return true;}
            CommunityLifeService.move(c,e,stand,CitizenActivity.COOKING,"");return true;
        }
        e.getNavigation().stop();var state=level.getBlockState(p);int value=state.getValue(ComposterBlock.LEVEL);
        if(value==7)return true;
        var snapshot=BlockSnapshot.create(level.dimension(),level,p,Block.UPDATE_CLIENTS);
        if(EventHooks.onBlockPlace(e,snapshot,Direction.UP)) {
            CommunityLifeService.blocked(c,e,"堆肥桶操作被领地保护拒绝");return true;
        }
        if(value==8) {
            ComposterBlock.extractProduce(e,state,level,p);
            l.cookingStage=3;l.cookingOutputCount=1;
            SaveAcceptanceService.action(level,c,"steward_compost_output",p,id(Items.BONE_MEAL),1);
        } else {
            // Recheck all stock plus this saved batch. New field expansion raises the protected quantity immediately.
            if(l.cookingInputCount<=0 || count(c,Items.WHEAT_SEEDS)<=0
                    || stock(level,s,Items.WHEAT_SEEDS)+count(c,Items.WHEAT_SEEDS)<=seedReserve(level,s)) {
                finish(level,data,c,e);return false;
            }
            var seed=c.extractInventoryItem(i -> i.is(Items.WHEAT_SEEDS),1);
            ComposterBlock.insertItem(e,state,level,seed,p);
            if(!seed.isEmpty())c.insertIntoInventory(seed);
            else {l.cookingInputCount--;SaveAcceptanceService.action(level,c,"steward_compost_seed",p,id(Items.WHEAT_SEEDS),1);}
        }
        c.setActivity(CitizenActivity.COOKING,"");e.setActivity(CitizenActivity.COOKING);
        data.touch();return true;
    }
    public static void finish(ServerLevel level,SettlementSavedData data,CitizenRecord c,SettlementCitizenEntity e) {
        c.life().clearTask();c.life().stewardTarget=null;WarehouseInventoryService.releaseInteractionPosition(level,e);data.touch();
    }
    private static int count(CitizenRecord c,Item item){return c.inventory().stream().filter(i -> i.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static String id(Item item){return BuiltInRegistries.ITEM.getKey(item).toString();}
    private static Item item(String id){var key=net.minecraft.resources.ResourceLocation.tryParse(id);return key==null?Items.AIR:BuiltInRegistries.ITEM.get(key);}

    /** Maximum admissible resident insertion. Player transfers and pre-existing stock are untouched. */
    public static int admission(ServerLevel level,BuildingRecord b,ItemStack stack,List<WarehouseInventoryService.ContainerAt> containers) {
        if(WarehouseStock.applies(b))return Math.min(stack.getCount(),WarehouseInventoryService.stock(level,b).room());
        if(!b.type().isStorage())return stack.getCount();
        var s=SettlementSavedData.get(level).settlements().stream().filter(v -> v.building(b.id())==b).findFirst().orElse(null);
        if(s==null || !s.community().stewardEnabled || overflow(b))return stack.getCount();
        boolean essential=CommunityLifeService.food(stack) || WarehouseCategory.classify(stack)==WarehouseCategory.TOOLS
                || WarehouseCategory.classify(stack)==WarehouseCategory.EQUIPMENT || stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
        int allowed=stack.getCount();
        if(stack.is(Items.SNOWBALL)) {
            int stored=SettlementInventoryService.warehouses(s).stream().filter(w -> !overflow(w))
                    .mapToInt(w -> WarehouseInventoryService.countItem(level,w,Items.SNOWBALL)).sum();
            allowed=Math.min(allowed,Math.max(0,s.community().snowLimit-stored));
        }
        if(essential)return allowed;
        int free=0,merge=0;
        for(var at:containers)for(int slot=0;slot<at.container().getContainerSize();slot++) {
            var existing=at.container().getItem(slot);
            if(existing.isEmpty() && at.container().canPlaceItem(slot,stack))free++;
            else if(at.container().canPlaceItem(slot,stack) && ItemStack.isSameItemSameComponents(existing,stack))
                merge+=Math.max(0,Math.min(existing.getMaxStackSize(),at.container().getMaxStackSize(existing))-existing.getCount());
        }
        return Math.min(allowed,merge+Math.max(0,free-s.community().lifeSlots)*Math.min(stack.getMaxStackSize(),64));
    }
}

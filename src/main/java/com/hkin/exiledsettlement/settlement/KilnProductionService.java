package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.*;
import net.minecraft.world.Container;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.citizen.CitizenInteractionRange;
import java.util.*;

/** Kiln batches reuse the persisted production cargo fields; paid inputs and outputs stay physical. */
public final class KilnProductionService {
    public static final int STOCK_TARGET = 256;
    public static final List<KilnRecipes.Recipe> RECIPES = KilnRecipes.RECIPES;
    private KilnProductionService() { }
    public static boolean isKiln(BuildingRecord building) {
        return building.type() == BuildingType.BRICK_KILN;
    }
    private static List<BlockPos> productionStations(ServerLevel level, BuildingRecord building, KilnRecipes.Recipe recipe) {
        var block=recipe.usesFuel() ? Blocks.FURNACE : recipe.station()==KilnRecipes.Station.STONECUTTER ? Blocks.STONECUTTER : Blocks.CRAFTING_TABLE;
        return BlueprintTemplates.createForBuilding(building).placements().stream()
                .filter(p -> p.mode().isFinalStructure() && p.state().is(block))
                .filter(p -> level.hasChunkAt(p.position()) && level.getBlockState(p.position()).is(block))
                .flatMap(p -> recipe.usesFuel()
                        ? java.util.stream.Stream.of(p.position().relative(level.getBlockState(p.position()).getValue(HorizontalDirectionalBlock.FACING)))
                        : java.util.stream.Stream.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.WEST).map(p.position()::relative))
                .filter(p -> building.contains(p) && WarehouseInventoryService.canStand(level,p)).toList();
    }
    private static boolean atKilnStation(ServerLevel level, BuildingRecord building, SettlementCitizenEntity entity, KilnRecipes.Recipe recipe) {
        BlockPos feet = entity.blockPosition();
        return feet.getX()>building.minCorner().getX() && feet.getX()<building.maxCorner().getX()
                && feet.getZ()>building.minCorner().getZ() && feet.getZ()<building.maxCorner().getZ()
                && level.noCollision(entity) && productionStations(level,building,recipe).stream()
                .anyMatch(p -> entity.distanceToSqr(Vec3.atBottomCenterOf(p)) <= 4);
    }
    private static BlockPos kilnDestination(ServerLevel level, BuildingRecord building, SettlementCitizenEntity entity, KilnRecipes.Recipe recipe) {
        if (atKilnStation(level,building,entity,recipe)) return entity.navigationFeetPosition();
        return productionStations(level,building,recipe).stream()
                .sorted(Comparator.comparingDouble(p -> p.distSqr(entity.blockPosition())))
                .filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }
    public static boolean isFiring(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, SettlementCitizenEntity entity) {
        var kiln=assignedKiln(settlement,citizen);
        var recipe=batchRecipe(citizen);
        return kiln!=null && recipe!=null && kiln.operational() && kiln.repairPositions().isEmpty()
                && kiln.id().equals(citizen.life().destination) && citizen.life().task.startsWith("kiln:")
                && citizen.life().cookingStage==2 && citizen.activity()==(recipe.usesFuel() ? CitizenActivity.FIRING_KILN : CitizenActivity.FORMING_BUILDING_MATERIALS)
                && atStation(level,settlement,kiln,entity,recipe);
    }
    private static final Map<CitizenRecord,Long> RETRY = new WeakHashMap<>();
    private static final Map<CitizenRecord,String> WAIT_REASON = new WeakHashMap<>();
    private static boolean reject(CitizenRecord citizen, String reason) {
        WAIT_REASON.put(citizen,reason);return false;
    }
    public static String waitingReason(CitizenRecord citizen) {
        return WAIT_REASON.getOrDefault(citizen,"窑工等待烧制检查");
    }
    private static BuildingRecord assignedKiln(SettlementRecord settlement, CitizenRecord citizen) {
        var building=settlement.building(citizen.workBuildingId());
        return citizen.canWork() && citizen.profession()==CitizenProfession.KILN_WORKER && building!=null
                && isKiln(building) && building.active() && !building.demolitionQueued() ? building : null;
    }
    private static boolean hasCargo(CitizenRecord citizen) {
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
            if(CitizenEquipmentService.deliverableCount(citizen,slot)>0
                    && !citizen.inventoryItem(slot).has(net.minecraft.core.component.DataComponents.TOOL)) return true;
        return false;
    }
    /** Unrelated cargo is delivered before starting a kiln batch. */
    public static boolean shift(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                CitizenRecord citizen, SettlementCitizenEntity entity) {
        if(hasCargo(citizen)) return false;
        if(assign(level,settlement,citizen)) {data.touch();return work(level,data,settlement,citizen,entity);}
        entity.stopSettlementNavigation();
        citizen.setActivity(CitizenActivity.KILN_STANDBY,waitingReason(citizen));
        entity.setActivity(CitizenActivity.KILN_STANDBY);
        data.touch();return true;
    }
    private static Item item(String id) {
        var key = ResourceLocation.tryParse(id); return key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
    }
    public static boolean assign(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        if(!citizen.life().task.isEmpty() || citizen.workState()!=CitizenWorkState.IDLE) return false;
        var kiln=assignedKiln(settlement,citizen);
        if(kiln==null) return reject(citizen,"窑工未分配到可用砖瓦窑");
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK)
            return reject(citizen,"窑工休息时间，下一工作时段继续");
        long now = level.getGameTime();
        if (RETRY.getOrDefault(citizen, -1L) > now) return false;
        RETRY.put(citizen, now + 100);
        var state = settlement.community();
        if (!kiln.operational() || !kiln.repairPositions().isEmpty()) return reject(citizen,"砖瓦窑设施未就绪或等待维修");
        if (state.danger(kiln.position())) return reject(citizen,"砖瓦窑位于危险区域，暂停烧制");
        if (hasCargo(citizen)) return reject(citizen,"窑工先将携货送回仓库");
        if(settlement.citizens().stream().filter(c -> c!=citizen && c.alive()
                && kiln.id().equals(c.life().destination) && c.life().task.startsWith("kiln:")).count() >= kiln.workerCapacity())
            return reject(citizen,"本窑生产岗位已满，等待空位");
        var goal=KilnRecipes.forOutput(BuiltInRegistries.ITEM.get(kiln.productionId()));
        if(goal==null)return reject(citizen,"砖瓦窑产物不可用");
        int promised=settlement.citizens().stream().filter(c -> c.alive() && c.life().task.startsWith("kiln:"))
                .filter(c -> (c.life().kilnFinalOutput.isEmpty()?c.life().cookingOutput:c.life().kilnFinalOutput).equals(kiln.productionId().toString()))
                .mapToInt(c -> c.life().kilnFinalOutput.isEmpty()?c.life().cookingOutputCount:c.life().kilnFinalCount).sum();
        int shortage=STOCK_TARGET-stockCount(level,settlement,goal.output())-promised;
        if(shortage<=0)return reject(citizen,"建材储备已达目标，窑工待命");
        int amount=Math.min(goal.outputCount(),((shortage+goal.minimumOutput()-1)/goal.minimumOutput())*goal.minimumOutput());
        var plan=plan(level,settlement,citizen,kiln,goal.output(),amount);
        if(!ready(level,settlement,kiln,citizen,plan))return false;
        citizen.life().kilnFinalOutput=kiln.productionId().toString();citizen.life().kilnFinalCount=amount;
        start(level,kiln,citizen,plan.steps().getFirst());return true;
    }
    public static KilnRecipes.Plan plan(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,Item goal,int amount) {
        return KilnRecipes.plan(goal,amount,i -> stockCount(level,settlement,i)+count(citizen,i));
    }
    private static KilnRecipes.Plan plan(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,
                                         BuildingRecord kiln,Item goal,int amount) {
        return KilnRecipes.plan(goal,amount,i -> (i==Items.GLASS_BOTTLE
                ? kilnBottleCount(level,kiln) : stockCount(level,settlement,i))+count(citizen,i));
    }
    private static boolean ready(ServerLevel level,SettlementRecord settlement,BuildingRecord kiln,CitizenRecord citizen,KilnRecipes.Plan plan) {
        if(plan.steps().isEmpty() && plan.ready())return reject(citizen,"窑工批次目标无效，保留携货等待调整");
        if(!plan.ready())return reject(citizen,"窑工等待原料："+KilnRecipes.stack(plan.missing(),1).getHoverName().getString()+" ×"+plan.missingCount());
        int available=stockCount(level,settlement,Items.COAL)+stockCount(level,settlement,Items.CHARCOAL)-settlement.community().fuelReserve
                +count(citizen,Items.COAL)+count(citizen,Items.CHARCOAL);
        if(plan.fuel()>0 && available<plan.fuel())return reject(citizen,"窑工等待燃料：完成本链需要 "+plan.fuel()+" 煤或木炭，保留线内燃料不可用");
        if(plan.needsWater() && KilnWaterService.stations(level,settlement,kiln).isEmpty())
            return reject(citizen,"窑工等待窑内水源：检查砖瓦窑的炼药锅是否有水且可到达");
        return true;
    }
    private static void start(ServerLevel level,BuildingRecord kiln,CitizenRecord citizen,KilnRecipes.Batch batch) {
        var life=citizen.life();var recipe=batch.recipe();
        life.task="kiln:"+BuiltInRegistries.ITEM.getKey(recipe.input());life.destination=kiln.id();
        life.taskStarted=level.getGameTime();life.workTicks=0;
        life.cookingInput=BuiltInRegistries.ITEM.getKey(recipe.input()).toString();
        life.cookingOutput=BuiltInRegistries.ITEM.getKey(recipe.output()).toString();
        life.cookingInputCount=recipe.inputs(batch.amount()).getFirst().count();life.cookingOutputCount=batch.amount();
        life.cookingFuel="";life.cookingStage=0;
    }
    private static boolean atStation(ServerLevel level,SettlementRecord settlement,BuildingRecord kiln,SettlementCitizenEntity entity,KilnRecipes.Recipe recipe) {
        return recipe.station()==KilnRecipes.Station.WATER ? KilnWaterService.at(level,settlement,kiln,entity) : atKilnStation(level,kiln,entity,recipe);
    }
    private static int warehouseCount(ServerLevel level,BuildingRecord warehouse,Item item) {
        int total=0;
        for(var at:WarehouseInventoryService.loadedContainers(level,warehouse))for(int slot=0;slot<at.container().getContainerSize();slot++) {
            var stack=at.container().getItem(slot);if(KilnRecipes.matches(stack,item))total+=stack.getCount();
        }
        return total;
    }
    private static int stockCount(ServerLevel level,SettlementRecord settlement,Item item) {
        return SettlementInventoryService.warehouses(settlement).stream().filter(b -> !settlement.community().danger(b.position()))
                .mapToInt(b -> warehouseCount(level,b,item)).sum();
    }
    public static boolean work(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                               CitizenRecord citizen, SettlementCitizenEntity entity) {
        var life = citizen.life(); var kiln = settlement.building(life.destination);
        var recipe=batchRecipe(citizen);
        Item input = item(life.cookingInput), output = item(life.cookingOutput);
        if (kiln == null || assignedKiln(settlement,citizen)!=kiln
                || recipe==null) {
            life.clearTask(); WarehouseInventoryService.releaseInteractionPosition(level,entity);
            entity.stopSettlementNavigation();data.touch(); return false; // Reassigned/legacy workers keep cargo for return.
        }
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK) return false;
        // Do not expire funded work across a night, a blocked route or a full warehouse.
        if(life.cookingStage<3 && (!kiln.operational() || !kiln.repairPositions().isEmpty()
                || settlement.community().danger(kiln.position()))) {
            CommunityLifeService.blocked(citizen,entity,"砖瓦窑维修、停用或危险：暂停烧制，保留本批原料");
            WarehouseInventoryService.releaseInteractionPosition(level,entity);data.touch();return true;
        }
        if (life.cookingStage == 0) {
            var missing=recipe.inputs(life.cookingOutputCount).stream().filter(i -> count(citizen,i.item())<i.count()).findFirst();
            if(missing.isPresent())fetch(level,settlement,kiln,citizen,entity,missing.get().item(),missing.get().count()-count(citizen,missing.get().item()));
            else life.cookingStage=recipe.usesFuel()?1:2;
        } else if (life.cookingStage == 1) {
            if(!recipe.usesFuel()) {life.cookingFuel="";life.cookingStage=2;data.touch();return true;}
            Item fuel = life.cookingFuel.isEmpty() ? Items.AIR : item(life.cookingFuel);
            if (fuel == Items.AIR || count(citizen,fuel)==0 && SettlementInventoryService.countItem(level,settlement,fuel)==0) {
                fuel = SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) > 0 ? Items.CHARCOAL : Items.COAL;
                life.cookingFuel = BuiltInRegistries.ITEM.getKey(fuel).toString();
            }
            if (count(citizen, fuel) > 0) life.cookingStage = 2;
            else if (SettlementInventoryService.countItem(level, settlement, Items.COAL)
                    + SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) <= settlement.community().fuelReserve) {
                CommunityLifeService.blocked(citizen, entity, "烧制暂停：公共燃料已到保留线");
            } else fetch(level, settlement, kiln, citizen, entity, fuel, 1);
        } else if (life.cookingStage == 2) {
            if (!hasInputs(citizen,recipe) || recipe.usesFuel() && count(citizen, item(life.cookingFuel)) < 1) {
                // Emergency warming or player reassignment may have consumed carried fuel.
                life.cookingStage = 0; life.workTicks = 0; data.touch(); return true;
            }
            // Firing uses the furnace apron; unfired forming uses the indoor workbench.
            var station = recipe.station()==KilnRecipes.Station.WATER ? KilnWaterService.destination(level,settlement,kiln,entity) : kilnDestination(level, kiln, entity,recipe);
            if (station == null) CommunityLifeService.blocked(citizen, entity, recipe.station()==KilnRecipes.Station.WATER ? "窑工取水/硬化点不可达或水源已消失" : "砖瓦窑作业点不可达或设施缺失");
            else {
                CommunityLifeService.move(citizen, entity, station,
                        atStation(level,settlement,kiln,entity,recipe) ? (recipe.usesFuel() ? CitizenActivity.FIRING_KILN : CitizenActivity.FORMING_BUILDING_MATERIALS) : CitizenActivity.TO_KILN, "");
                if (atStation(level,settlement,kiln,entity,recipe)) {
                    life.workTicks += (int)(CitizenWorkService.WORK_INTERVAL_TICKS * citizen.professionExperience().multiplier(citizen.profession()));
                    if (life.workTicks >= 400 && completeBatch(settlement, citizen)) {
                        life.cookingStage = 3; WarehouseInventoryService.releaseInteractionPosition(level, entity);
                    } else if(life.workTicks>=400) {
                        CommunityLifeService.blocked(citizen,entity,"窑工背包空间不足或本批物料缺失，保留批次");
                    }
                }
            }
        } else {
            // Keep real intermediate cargo and the original final selection through every stage.
            if(count(citizen,Items.GLASS_BOTTLE)>0) {
                int carried=count(citizen,Items.GLASS_BOTTLE);
                var station=kilnBottleDestination(level,kiln,entity,false);
                if(station==null) CommunityLifeService.blocked(citizen,entity,"空玻璃瓶待归还：窑内木桶不可达");
                else {
                    CommunityLifeService.move(citizen,entity,station,CitizenActivity.DELIVERING_BUILDING_MATERIALS,"");
                    if(atKilnBottleStorage(level,kiln,entity,false)) {
                        int inserted=insertKilnBottles(level,kiln,entity,carried);
                        if(inserted<=0) CommunityLifeService.blocked(citizen,entity,"空玻璃瓶待归还：窑内木桶没有空位");
                        else {
                            citizen.extractInventoryItem(s -> s.is(Items.GLASS_BOTTLE),inserted);
                            WarehouseInventoryService.releaseInteractionPosition(level,entity);
                        }
                    }
                }
                data.touch();return true;
            }
            if(!life.kilnFinalOutput.isEmpty() && !life.cookingOutput.equals(life.kilnFinalOutput)) {
                if(RETRY.getOrDefault(citizen,-1L)>level.getGameTime())return true;
                RETRY.put(citizen,level.getGameTime()+100);
                var plan=plan(level,settlement,citizen,kiln,item(life.kilnFinalOutput),life.kilnFinalCount);
                if(ready(level,settlement,kiln,citizen,plan))start(level,kiln,citizen,plan.steps().getFirst());
                else CommunityLifeService.blocked(citizen,entity,waitingReason(citizen));
                data.touch();return true;
            }
            var available = citizen.inventory().stream().filter(s -> s.is(output) && !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            final ItemStack stack;
            if(life.cookingOutputCount>0)stack=available.copyWithCount(Math.min(available.getCount(),life.cookingOutputCount));
            else {
                ItemStack remainder=ItemStack.EMPTY;
                for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++) {
                    var carried=citizen.inventoryItem(slot);
                    int deliverable=CitizenEquipmentService.deliverableCount(citizen,slot);
                    if(deliverable>0 && !carried.has(net.minecraft.core.component.DataComponents.TOOL)) { remainder=carried.copyWithCount(Math.min(deliverable,carried.getMaxStackSize()));break; }
                }
                stack=remainder;
            }
            if (stack.isEmpty()) { life.clearTask(); data.touch(); return false; }
            var destination = SettlementInventoryService.warehouses(settlement).stream()
                    .filter(b -> !settlement.community().danger(b.position()) && WarehouseInventoryService.canAccept(level, b, stack))
                    .min(Comparator.comparingDouble(b -> b.position().distSqr(citizen.lastPosition()))).orElse(null);
            if (destination == null) CommunityLifeService.blocked(citizen, entity, "建材待交货：仓库满载或过滤拒绝");
            else {
                var station = WarehouseInventoryService.approachPosition(level, destination, entity);
                if (station == null) station = WarehouseInventoryService.reserveInsertionPosition(level, destination, entity, stack);
                if (station == null) CommunityLifeService.blocked(citizen, entity, "建材交货点不可达或被占用");
                else {
                    CommunityLifeService.move(citizen, entity, station, CitizenActivity.DELIVERING_BUILDING_MATERIALS, "");
                    if (WarehouseInventoryService.canInteract(level, destination, entity)) {
                        ItemStack remainder = WarehouseInventoryService.insertForResident(level, destination, entity, stack.copy());
                        int inserted = stack.getCount() - remainder.getCount();
                        if (inserted > 0) citizen.extractInventoryItem(s -> ItemStack.isSameItemSameComponents(s, stack), inserted);
                        if (inserted > 0) CommunityStock.invalidate(level,settlement);
                        if(life.cookingOutputCount>0)life.cookingOutputCount-=inserted;
                        if (life.cookingOutputCount <= 0 && !hasCargo(citizen)) { life.clearTask(); WarehouseInventoryService.releaseInteractionPosition(level, entity); }
                    }
                }
            }
        }
        data.touch(); return true;
    }
    /** Atomic preflight before consuming anything; used by the physical station path and regression tests. */
    public static boolean completeBatch(SettlementRecord settlement, CitizenRecord citizen) {
        var l = citizen.life(); Item input = item(l.cookingInput), fuel = item(l.cookingFuel), output = item(l.cookingOutput);
        var recipe=batchRecipe(citizen);
        if(recipe==null || l.cookingStage!=2)return false;
        ItemStack made=KilnRecipes.stack(output,l.cookingOutputCount);
        ItemStack returned=recipe.returnedCount(l.cookingOutputCount)>0 ? KilnRecipes.stack(recipe.returnedItem(),recipe.returnedCount(l.cookingOutputCount)) : ItemStack.EMPTY;
        if(!hasInputs(citizen,recipe)
                || recipe.usesFuel() && ((fuel!=Items.COAL && fuel!=Items.CHARCOAL) || count(citizen,fuel)<1)
                || !roomFor(citizen,made,returned))return false;
        for(var ingredient:recipe.inputs(l.cookingOutputCount)) {
            int remaining=ingredient.count();
            while(remaining>0) {
                var taken=citizen.extractInventoryItem(s -> KilnRecipes.matches(s,ingredient.item()),remaining);
                if(taken.isEmpty())return false; // Preflight above normally guarantees this; retain safety across split stacks.
                remaining-=taken.getCount();
            }
        }
        if(recipe.usesFuel())citizen.extractInventoryItem(s -> s.is(fuel),1);
        citizen.insertIntoInventory(made);if(!returned.isEmpty())citizen.insertIntoInventory(returned);
        l.cookingStage=3;return true;
    }
    private static boolean roomFor(CitizenRecord citizen,ItemStack... outputs) {
        var simulated=new net.minecraft.world.SimpleContainer(CitizenRecord.INVENTORY_SIZE);
        for(int i=0;i<CitizenRecord.INVENTORY_SIZE;i++)simulated.setItem(i,citizen.inventoryItem(i));
        for(var stack:outputs)if(!stack.isEmpty() && !simulated.addItem(stack.copy()).isEmpty())return false;
        return true;
    }
    private static boolean hasInputs(CitizenRecord citizen, KilnRecipes.Recipe recipe) {
        return recipe.inputs(citizen.life().cookingOutputCount).stream().allMatch(i -> count(citizen,i.item())>=i.count());
    }
    /** Resolve the persisted immutable input/output contract, never the newly selected product. */
    private static KilnRecipes.Recipe batchRecipe(CitizenRecord citizen) {
        var l = citizen.life();
        if(!l.task.startsWith("kiln:") || l.cookingStage<0 || l.cookingStage>3) return null;
        return RECIPES.stream().filter(r -> item(l.cookingInput)==r.input() && item(l.cookingOutput)==r.output())
                .filter(r -> {
                    int amount=l.cookingInputCount*r.outputCount()/r.inputCount();
                    return r.accepts(amount) && r.inputs(amount).getFirst().count()==l.cookingInputCount
                            && (l.cookingStage==3 ? l.cookingOutputCount>=0 && l.cookingOutputCount<=amount : l.cookingOutputCount==amount);
                }).findFirst().orElse(null);
    }
    private static int count(CitizenRecord citizen, Item item) {
        return citizen.inventory().stream().filter(s -> KilnRecipes.matches(s,item)).mapToInt(ItemStack::getCount).sum();
    }
    private record KilnBottleStation(WarehouseInventoryService.ContainerAt container,BlockPos feet) {}
    private static List<WarehouseInventoryService.ContainerAt> kilnBottleContainers(ServerLevel level,BuildingRecord kiln) {
        var containers=new ArrayList<WarehouseInventoryService.ContainerAt>();
        for(var placement:BlueprintTemplates.createForBuilding(kiln).placements()) {
            var position=placement.position();
            if(!placement.mode().isFinalStructure() || !placement.state().is(Blocks.BARREL)
                    || !level.hasChunkAt(position) || !level.getBlockState(position).is(Blocks.BARREL))continue;
            if(level.getBlockEntity(position) instanceof Container container)
                containers.add(new WarehouseInventoryService.ContainerAt(position.immutable(),container));
        }
        return List.copyOf(containers);
    }
    public static int kilnBottleCount(ServerLevel level,BuildingRecord kiln) {
        return kilnBottleContainers(level,kiln).stream().mapToInt(at->{
            int count=0;
            for(int slot=0;slot<at.container().getContainerSize();slot++) {
                var stack=at.container().getItem(slot);
                if(stack.is(Items.GLASS_BOTTLE))count+=stack.getCount();
            }
            return count;
        }).sum();
    }
    private static List<KilnBottleStation> kilnBottleStations(ServerLevel level,BuildingRecord kiln) {
        var stations=new ArrayList<KilnBottleStation>();
        for(var container:kilnBottleContainers(level,kiln))for(var direction:Direction.Plane.HORIZONTAL) {
            var feet=container.position().relative(direction);
            if(kiln.contains(feet) && WarehouseInventoryService.canStand(level,feet))
                stations.add(new KilnBottleStation(container,feet.immutable()));
        }
        return List.copyOf(stations);
    }
    private static boolean bottleContainerHasStock(WarehouseInventoryService.ContainerAt at) {
        for(int slot=0;slot<at.container().getContainerSize();slot++)
            if(at.container().getItem(slot).is(Items.GLASS_BOTTLE))return true;
        return false;
    }
    private static boolean bottleContainerHasRoom(WarehouseInventoryService.ContainerAt at) {
        var container=at.container();var bottle=new ItemStack(Items.GLASS_BOTTLE);
        for(int slot=0;slot<container.getContainerSize();slot++) {
            var existing=container.getItem(slot);
            if(!container.canPlaceItem(slot,bottle))continue;
            if(existing.isEmpty() || existing.is(Items.GLASS_BOTTLE)
                    && existing.getCount()<Math.min(existing.getMaxStackSize(),container.getMaxStackSize(existing)))return true;
        }
        return false;
    }
    private static boolean atKilnBottleStorage(ServerLevel level,BuildingRecord kiln,SettlementCitizenEntity entity,boolean source) {
        return level.noCollision(entity) && kilnBottleStations(level,kiln).stream()
                .filter(s->source?bottleContainerHasStock(s.container()):bottleContainerHasRoom(s.container()))
                .filter(s->entity.distanceToSqr(Vec3.atBottomCenterOf(s.feet()))<=0.64)
                .anyMatch(s->CitizenInteractionRange.visible(entity,s.container().position()));
    }
    private static BlockPos kilnBottleDestination(ServerLevel level,BuildingRecord kiln,SettlementCitizenEntity entity,boolean source) {
        if(atKilnBottleStorage(level,kiln,entity,source))return entity.navigationFeetPosition();
        return kilnBottleStations(level,kiln).stream().map(KilnBottleStation::feet).distinct()
                .filter(feet->kilnBottleStations(level,kiln).stream().anyMatch(s->s.feet().equals(feet)
                        && (source?bottleContainerHasStock(s.container()):bottleContainerHasRoom(s.container()))))
                .sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition())))
                .filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }
    private static List<WarehouseInventoryService.ContainerAt> interactableKilnBottleContainers(
            ServerLevel level,BuildingRecord kiln,SettlementCitizenEntity entity,boolean source) {
        return kilnBottleStations(level,kiln).stream()
                .filter(s->source?bottleContainerHasStock(s.container()):bottleContainerHasRoom(s.container()))
                .filter(s->entity.distanceToSqr(Vec3.atBottomCenterOf(s.feet()))<=0.64)
                .filter(s->CitizenInteractionRange.visible(entity,s.container().position()))
                .map(KilnBottleStation::container).distinct().toList();
    }
    private static int extractKilnBottles(ServerLevel level,BuildingRecord kiln,SettlementCitizenEntity entity,int wanted) {
        int moved=0;
        for(var at:interactableKilnBottleContainers(level,kiln,entity,true)) {
            var container=at.container();
            for(int slot=0;slot<container.getContainerSize() && moved<wanted;slot++) {
                var stack=container.getItem(slot);
                if(!stack.is(Items.GLASS_BOTTLE))continue;
                int amount=Math.min(wanted-moved,stack.getCount());stack.shrink(amount);moved+=amount;container.setChanged();
            }
        }
        return moved;
    }
    private static int insertKilnBottles(ServerLevel level,BuildingRecord kiln,SettlementCitizenEntity entity,int offered) {
        int remaining=offered;
        for(var at:interactableKilnBottleContainers(level,kiln,entity,false)) {
            var container=at.container();
            for(int slot=0;slot<container.getContainerSize() && remaining>0;slot++) {
                var stack=container.getItem(slot);
                if(!stack.is(Items.GLASS_BOTTLE) || !container.canPlaceItem(slot,stack))continue;
                int limit=Math.min(stack.getMaxStackSize(),container.getMaxStackSize(stack));
                int moved=Math.min(remaining,limit-stack.getCount());
                if(moved>0){stack.grow(moved);remaining-=moved;container.setChanged();}
            }
        }
        for(var at:interactableKilnBottleContainers(level,kiln,entity,false)) {
            var container=at.container();
            for(int slot=0;slot<container.getContainerSize() && remaining>0;slot++) {
                if(!container.getItem(slot).isEmpty())continue;
                var stack=new ItemStack(Items.GLASS_BOTTLE,Math.min(remaining,Items.GLASS_BOTTLE.getDefaultMaxStackSize()));
                if(!container.canPlaceItem(slot,stack))continue;
                container.setItem(slot,stack);remaining-=stack.getCount();container.setChanged();
            }
        }
        return offered-remaining;
    }
    private static void fetch(ServerLevel level, SettlementRecord settlement, BuildingRecord kiln,CitizenRecord citizen,
                              SettlementCitizenEntity entity, Item item, int wanted) {
        if(item==Items.GLASS_BOTTLE) {
            var station=kilnBottleDestination(level,kiln,entity,true);
            if(station==null) {
                CommunityLifeService.blocked(citizen,entity,kilnBottleCount(level,kiln)>0
                        ? "窑内空玻璃瓶存储位置不可达" : "窑工等待空玻璃瓶：请放入本窑储物木桶");
                return;
            }
            CommunityLifeService.move(citizen,entity,station,CitizenActivity.FETCHING_KILN_INPUT,"");
            if(atKilnBottleStorage(level,kiln,entity,true)) {
                int capacity=citizen.inventoryCapacityFor(new ItemStack(Items.GLASS_BOTTLE));
                int taken=extractKilnBottles(level,kiln,entity,Math.min(wanted,capacity));
                if(taken<=0)CommunityLifeService.blocked(citizen,entity,"窑工等待空玻璃瓶：请放入本窑储物木桶");
                else citizen.insertIntoInventory(new ItemStack(Items.GLASS_BOTTLE,taken));
            }
            return;
        }
        var source = SettlementInventoryService.warehouses(settlement).stream()
                .filter(b -> !settlement.community().danger(b.position()) && warehouseCount(level,b,item)>0)
                .min(Comparator.comparingDouble(b -> b.position().distSqr(citizen.lastPosition()))).orElse(null);
        if (source == null) { CommunityLifeService.blocked(citizen, entity, "砖瓦窑等待："+KilnRecipes.stack(item,1).getHoverName().getString()); return; }
        if (CommunityLifeService.atStorage(level, citizen, entity, source, item,
                item==Items.COAL || item==Items.CHARCOAL ? CitizenActivity.FETCHING_KILN_FUEL : CitizenActivity.FETCHING_KILN_INPUT)) {
            if ((item == Items.COAL || item == Items.CHARCOAL)
                    && SettlementInventoryService.countItem(level, settlement, Items.COAL)
                    + SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) - wanted < settlement.community().fuelReserve) {
                CommunityLifeService.blocked(citizen, entity, "烧制暂停：公共燃料已到保留线"); return;
            }
            int capacity = citizen.inventoryCapacityFor(KilnRecipes.stack(item,1));
            if (capacity < wanted) { CommunityLifeService.blocked(citizen, entity, "烧制背包空间不足"); return; }
            citizen.insertIntoInventory(WarehouseInventoryService.extractItemForResident(level,source,entity,item,wanted,
                    s -> KilnRecipes.matches(s,item)?Math.min(wanted,citizen.inventoryCapacityFor(s)):0));
            CommunityStock.invalidate(level, settlement);
        }
    }
}

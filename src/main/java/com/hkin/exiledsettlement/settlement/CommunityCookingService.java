package com.hkin.exiledsettlement.settlement;

import com.google.gson.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import java.util.*;

/** Assigned chefs use real ingredients, an indoor station and physical public-stock delivery. */
public final class CommunityCookingService {
    public record Recipe(Item input, int inputCount, Item output, int outputCount) { }
    private static List<Recipe> recipes = List.of(new Recipe(Items.WHEAT, 3, Items.BREAD, 1),
            new Recipe(Items.POTATO, 4, Items.BAKED_POTATO, 4),
            new Recipe(Items.BEEF, 4, Items.COOKED_BEEF, 4),
            new Recipe(Items.COD, 4, Items.COOKED_COD, 4));
    private CommunityCookingService() { }
    public static boolean offersMeals(BuildingRecord building) {
        return building.type() == BuildingType.RESTAURANT
                || (building.type() == BuildingType.TAVERN && building.templateVersion() >= 2);
    }
    private static boolean combinedTavern(BuildingRecord building) {
        return building.type() == BuildingType.TAVERN && building.templateVersion() >= 2;
    }
    private static List<BlockPos> cookingStations(ServerLevel level, BuildingRecord building) {
        return BlueprintTemplates.createForBuilding(building).placements().stream()
                .filter(p -> p.mode().isFinalStructure() && p.state().is(Blocks.SMOKER))
                .filter(p -> level.hasChunkAt(p.position()) && level.getBlockState(p.position()).is(Blocks.SMOKER))
                .map(p -> p.position().relative(level.getBlockState(p.position()).getValue(HorizontalDirectionalBlock.FACING)))
                .filter(p -> WarehouseInventoryService.canStand(level,p)).toList();
    }
    private static boolean atCookingStation(ServerLevel level, BuildingRecord building, SettlementCitizenEntity entity) {
        if (!combinedTavern(building)) return WarehouseInventoryService.canInteract(level,building,entity);
        BlockPos feet = entity.blockPosition();
        return feet.getX()>building.minCorner().getX() && feet.getX()<building.maxCorner().getX()
                && feet.getZ()>building.minCorner().getZ() && feet.getZ()<building.maxCorner().getZ()
                && level.noCollision(entity) && cookingStations(level,building).stream()
                .anyMatch(p -> entity.distanceToSqr(Vec3.atBottomCenterOf(p)) <= 4);
    }
    private static BlockPos cookingDestination(ServerLevel level, BuildingRecord building, SettlementCitizenEntity entity) {
        if (combinedTavern(building)) {
            if (atCookingStation(level,building,entity)) return entity.navigationFeetPosition();
            return cookingStations(level,building).stream()
                    .sorted(Comparator.comparingDouble(p -> p.distSqr(entity.blockPosition())))
                    .filter(entity::hasReachablePathTo).findFirst().orElse(null);
        }
        var station = WarehouseInventoryService.approachPosition(level,building,entity);
        return station != null ? station : WarehouseInventoryService.reserveInteractionPosition(level,building,entity);
    }
    private static final Map<CitizenRecord,Long> RETRY = new WeakHashMap<>();
    private static final Map<CitizenRecord,String> WAIT_REASON = new WeakHashMap<>();
    private static boolean reject(CitizenRecord citizen, String reason) {
        WAIT_REASON.put(citizen,reason);return false;
    }
    public static String waitingReason(CitizenRecord citizen) {
        return WAIT_REASON.getOrDefault(citizen,"厨师等待供餐检查");
    }
    private static BuildingRecord assignedKitchen(SettlementRecord settlement, CitizenRecord citizen) {
        var building=settlement.building(citizen.workBuildingId());
        return citizen.canWork() && citizen.profession()==CitizenProfession.CHEF && building!=null
                && offersMeals(building) && building.active() && !building.demolitionQueued() ? building : null;
    }
    private static boolean hasCargo(CitizenRecord citizen) {
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
            if(CitizenEquipmentService.deliverableCount(citizen,slot)>0
                    && !citizen.inventoryItem(slot).has(net.minecraft.core.component.DataComponents.TOOL)) return true;
        return false;
    }
    /** An idle chef stays on kitchen duty; unrelated cargo is returned through normal delivery first. */
    public static boolean shift(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                CitizenRecord citizen, SettlementCitizenEntity entity) {
        if(hasCargo(citizen)) return false;
        if(assign(level,settlement,citizen)) {data.touch();return work(level,data,settlement,citizen,entity);}
        entity.stopSettlementNavigation();
        citizen.setActivity(CitizenActivity.KITCHEN_STANDBY,waitingReason(citizen));
        entity.setActivity(CitizenActivity.KITCHEN_STANDBY);
        data.touch();return true;
    }
    public static void reload(JsonArray values) {
        List<Recipe> loaded = new ArrayList<>();
        for (JsonElement element : values) {
            var o = element.getAsJsonObject();
            Item input = item(o.get("input").getAsString()), output = item(o.get("output").getAsString());
            int in = o.get("input_count").getAsInt(), out = o.get("output_count").getAsInt();
            if (input == Items.AIR || output == Items.AIR || in < 1 || in > 64 || out < 1 || out > 64
                    || !CommunityLifeService.food(new ItemStack(output))) throw new IllegalArgumentException("Invalid cooking recipe");
            loaded.add(new Recipe(input, in, output, out));
            if (loaded.size() > 64) throw new IllegalArgumentException("Too many cooking recipes");
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No cooking recipes");
        recipes = List.copyOf(loaded);
    }
    private static Item item(String id) {
        var key = ResourceLocation.tryParse(id); return key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
    }
    public static boolean assign(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        if(!citizen.life().task.isEmpty() || citizen.workState()!=CitizenWorkState.IDLE) return false;
        var restaurant=assignedKitchen(settlement,citizen);
        if(restaurant==null) return reject(citizen,"厨师未分配到可用酒馆或餐厅");
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK)
            return reject(citizen,"厨师休息时间，下一工作时段继续");
        long now = level.getGameTime();
        if (RETRY.getOrDefault(citizen, -1L) > now) return false;
        RETRY.put(citizen, now + 100);
        var state = settlement.community();
        if (state.mealTarget <= 0) return reject(citizen,"熟食储备目标为零，暂停烹饪");
        if (!restaurant.operational() || !restaurant.repairPositions().isEmpty()) return reject(citizen,"酒馆设施未就绪或等待维修");
        if (state.danger(restaurant.position())) return reject(citizen,"酒馆位于危险区域，暂停烹饪");
        if (hasCargo(citizen)) return reject(citizen,"厨师先将携货送回仓库");
        if(settlement.citizens().stream().anyMatch(c -> c!=citizen && c.alive()
                && restaurant.id().equals(c.life().destination) && c.life().task.startsWith("cook:")))
            return reject(citizen,"本店已有厨师批次，等待完成");
        int fuel = CommunityStock.count(level, settlement, Items.COAL)
                + CommunityStock.count(level, settlement, Items.CHARCOAL);
        boolean belowTarget=false;
        for (var recipe : recipes) {
            int promised=settlement.citizens().stream().filter(c -> c.alive() && c.life().task.startsWith("cook:")
                    && c.life().cookingOutput.equals(BuiltInRegistries.ITEM.getKey(recipe.output()).toString()))
                    .mapToInt(c -> c.life().cookingOutputCount).sum();
            if ((long)CommunityStock.count(level, settlement, recipe.output())+promised >= state.mealTarget) continue;
            belowTarget=true;
            if(fuel<=state.fuelReserve) return reject(citizen,"厨师等待燃料：公共煤炭或木炭已到保留线");
            int raw = CommunityStock.count(level, settlement, recipe.input());
            int totalFood = CommunityStock.food(level, settlement);
            if (raw < recipe.inputCount() || (CommunityLifeService.food(new ItemStack(recipe.input()))
                    && totalFood - recipe.inputCount() < state.foodReserve)) continue;
            var life = citizen.life(); life.task = "cook:" + BuiltInRegistries.ITEM.getKey(recipe.input());
            life.destination = restaurant.id(); life.taskStarted = level.getGameTime(); life.workTicks = 0;
            life.cookingInput = BuiltInRegistries.ITEM.getKey(recipe.input()).toString();
            life.cookingOutput = BuiltInRegistries.ITEM.getKey(recipe.output()).toString();
            life.cookingFuel = ""; life.cookingInputCount = recipe.inputCount();
            life.cookingOutputCount = recipe.outputCount(); life.cookingStage = 0; return true;
        }
        return reject(citizen,belowTarget ? "厨师等待可用食材，或公共食物已到保留线" : "熟食储备已达目标，厨师待命");
    }
    public static boolean work(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                               CitizenRecord citizen, SettlementCitizenEntity entity) {
        var life = citizen.life(); var restaurant = settlement.building(life.destination);
        Item input = item(life.cookingInput), output = item(life.cookingOutput);
        if (restaurant == null || assignedKitchen(settlement,citizen)!=restaurant
                || input == Items.AIR || output == Items.AIR
                || life.cookingInputCount < 1 || life.cookingOutputCount < 1) {
            life.clearTask(); WarehouseInventoryService.releaseInteractionPosition(level,entity);
            entity.stopSettlementNavigation();data.touch(); return false; // Reassigned/legacy workers keep cargo for return.
        }
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK) return false;
        // Do not expire funded work across a night, a blocked route or a full warehouse.
        if(life.cookingStage<3 && (!restaurant.operational() || !restaurant.repairPositions().isEmpty()
                || settlement.community().danger(restaurant.position()))) {
            CommunityLifeService.blocked(citizen,entity,"酒馆维修、停用或危险：暂停烹饪，保留本批食材");
            WarehouseInventoryService.releaseInteractionPosition(level,entity);data.touch();return true;
        }
        if (life.cookingStage == 0) {
            int count = count(citizen, input);
            if (count >= life.cookingInputCount) life.cookingStage = 1;
            else fetch(level, settlement, citizen, entity, input, life.cookingInputCount - count);
        } else if (life.cookingStage == 1) {
            Item fuel = life.cookingFuel.isEmpty() ? Items.AIR : item(life.cookingFuel);
            if (fuel == Items.AIR || count(citizen,fuel)==0 && SettlementInventoryService.countItem(level,settlement,fuel)==0) {
                fuel = SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) > 0 ? Items.CHARCOAL : Items.COAL;
                life.cookingFuel = BuiltInRegistries.ITEM.getKey(fuel).toString();
            }
            if (count(citizen, fuel) > 0) life.cookingStage = 2;
            else if (SettlementInventoryService.countItem(level, settlement, Items.COAL)
                    + SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) <= settlement.community().fuelReserve) {
                CommunityLifeService.blocked(citizen, entity, "烹饪暂停：公共燃料已到保留线");
            } else fetch(level, settlement, citizen, entity, fuel, 1);
        } else if (life.cookingStage == 2) {
            if (count(citizen, input) < life.cookingInputCount || count(citizen, item(life.cookingFuel)) < 1) {
                // Eating or emergency warming can consume supplies during a suspended batch.
                life.cookingStage = 0; life.workTicks = 0; data.touch(); return true;
            }
            // New combined taverns cook at their indoor hearth/kitchen; old restaurants retain their stations.
            var station = cookingDestination(level, restaurant, entity);
            if (station == null) CommunityLifeService.blocked(citizen, entity, "餐厅作业点不可达或设施缺失");
            else {
                CommunityLifeService.move(citizen, entity, station,
                        atCookingStation(level,restaurant,entity) ? CitizenActivity.COOKING : CitizenActivity.TO_KITCHEN, "");
                if (atCookingStation(level, restaurant, entity)) {
                    life.workTicks += CitizenWorkService.WORK_INTERVAL_TICKS;
                    if (life.workTicks >= 400 && completeBatch(settlement, citizen)) {
                        life.cookingStage = 3; WarehouseInventoryService.releaseInteractionPosition(level, entity);
                    } else if(life.workTicks>=400) {
                        CommunityLifeService.blocked(citizen,entity,"厨师背包空间不足或本批物料缺失，保留批次");
                    }
                }
            }
        } else {
            var available = citizen.inventory().stream().filter(s -> s.is(output) && !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            var stack = available.copyWithCount(Math.min(available.getCount(), life.cookingOutputCount));
            if (stack.isEmpty()) { life.clearTask(); data.touch(); return false; }
            var destination = SettlementInventoryService.warehouses(settlement).stream()
                    .filter(b -> !settlement.community().danger(b.position()) && WarehouseInventoryService.canAccept(level, b, stack))
                    .min(Comparator.comparingDouble(b -> b.position().distSqr(citizen.lastPosition()))).orElse(null);
            if (destination == null) CommunityLifeService.blocked(citizen, entity, "熟食待交货：仓库满载或过滤拒绝");
            else {
                var station = WarehouseInventoryService.approachPosition(level, destination, entity);
                if (station == null) station = WarehouseInventoryService.reserveInsertionPosition(level, destination, entity, stack);
                if (station == null) CommunityLifeService.blocked(citizen, entity, "熟食交货点不可达或被占用");
                else {
                    CommunityLifeService.move(citizen, entity, station, CitizenActivity.DELIVERING_MEALS, "");
                    if (WarehouseInventoryService.canInteract(level, destination, entity)) {
                        ItemStack remainder = WarehouseInventoryService.insertForResident(level, destination, entity, stack.copy());
                        int inserted = stack.getCount() - remainder.getCount();
                        if (inserted > 0) citizen.extractInventoryItem(s -> ItemStack.isSameItemSameComponents(s, stack), inserted);
                        if (inserted > 0) CommunityStock.invalidate(level,settlement);
                        life.cookingOutputCount -= inserted;
                        if (life.cookingOutputCount <= 0) { life.clearTask(); WarehouseInventoryService.releaseInteractionPosition(level, entity); }
                    }
                }
            }
        }
        data.touch(); return true;
    }
    /** Atomic preflight before consuming anything; used by the physical station path and regression tests. */
    public static boolean completeBatch(SettlementRecord settlement, CitizenRecord citizen) {
        var l = citizen.life(); Item input = item(l.cookingInput), fuel = item(l.cookingFuel), output = item(l.cookingOutput);
        ItemStack made = new ItemStack(output, l.cookingOutputCount);
        if (l.cookingStage != 2 || input == Items.AIR || fuel == Items.AIR || output == Items.AIR
                || count(citizen, input) < l.cookingInputCount || count(citizen, fuel) < 1
                || citizen.inventoryCapacityFor(made) < made.getCount()) return false;
        citizen.extractInventoryItem(s -> s.is(input), l.cookingInputCount);
        citizen.extractInventoryItem(s -> s.is(fuel), 1);
        citizen.insertIntoInventory(made); settlement.community().mealsProduced += made.getCount();
        l.cookingStage = 3;
        return true;
    }
    private static int count(CitizenRecord citizen, Item item) {
        return citizen.inventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static void fetch(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
                              SettlementCitizenEntity entity, Item item, int wanted) {
        var source = SettlementInventoryService.warehouses(settlement).stream()
                .filter(b -> !settlement.community().danger(b.position()) && WarehouseInventoryService.countItem(level, b, item) > 0)
                .min(Comparator.comparingDouble(b -> b.position().distSqr(citizen.lastPosition()))).orElse(null);
        if (source == null) { CommunityLifeService.blocked(citizen, entity, "餐厅缺少原料或燃料"); return; }
        if (CommunityLifeService.atStorage(level, citizen, entity, source, item,
                item==Items.COAL || item==Items.CHARCOAL ? CitizenActivity.FETCHING_COOKING_FUEL : CitizenActivity.FETCHING_COOKING_INPUT)) {
            if (CommunityLifeService.food(new ItemStack(item))
                    && SettlementInventoryService.countCategory(level, settlement, WarehouseCategory.FOOD) - wanted < settlement.community().foodReserve) {
                CommunityLifeService.blocked(citizen, entity, "烹饪暂停：公共食物已到保留线"); return;
            }
            if ((item == Items.COAL || item == Items.CHARCOAL)
                    && SettlementInventoryService.countItem(level, settlement, Items.COAL)
                    + SettlementInventoryService.countItem(level, settlement, Items.CHARCOAL) - wanted < settlement.community().fuelReserve) {
                CommunityLifeService.blocked(citizen, entity, "烹饪暂停：公共燃料已到保留线"); return;
            }
            int capacity = citizen.inventoryCapacityFor(new ItemStack(item));
            if (capacity < wanted) { CommunityLifeService.blocked(citizen, entity, "烹饪背包空间不足"); return; }
            citizen.insertIntoInventory(WarehouseInventoryService.extractItemForResident(level, source, entity, item, wanted));
            CommunityStock.invalidate(level, settlement);
        }
    }
}

package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.settlement.CommunityContent;
import com.hkin.exiledsettlement.settlement.CommunityLifeService;
import com.hkin.exiledsettlement.settlement.CommunityStock;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Actual containers and resident bags; never consumes the resident's reserved meals. */
final class PetLogistics {
    private PetLogistics() {}
    record Supply(BuildingRecord building, Item item) {}

    static Predicate<ItemStack> food(PetState.Pet pet) {
        if (pet.status == PetState.Status.ADOPTING && !pet.cat) return stack -> stack.is(Items.BONE);
        return pet.cat ? stack -> stack.is(ItemTags.CAT_FOOD) : stack -> stack.is(ItemTags.WOLF_FOOD);
    }

    static boolean availableInBag(CitizenRecord citizen, PetState.Pet pet) {
        boolean spareMeals = mealCount(citizen) > CommunityContent.carriedFoodTarget;
        return citizen.inventory().stream().anyMatch(stack -> !stack.isEmpty() && food(pet).test(stack)
                && (!CommunityLifeService.food(stack) || spareMeals));
    }

    static ItemStack consume(CitizenRecord citizen, PetState.Pet pet) {
        if (!availableInBag(citizen, pet)) return ItemStack.EMPTY;
        boolean spareMeals = mealCount(citizen) > CommunityContent.carriedFoodTarget;
        return citizen.extractInventoryItem(stack -> food(pet).test(stack)
                && (!CommunityLifeService.food(stack) || spareMeals), 1);
    }

    static ItemStack preview(CitizenRecord citizen, PetState.Pet pet) {
        boolean spareMeals = mealCount(citizen) > CommunityContent.carriedFoodTarget;
        return citizen.inventory().stream().filter(stack -> !stack.isEmpty() && food(pet).test(stack)
                && (!CommunityLifeService.food(stack) || spareMeals)).findFirst()
                .map(stack -> stack.copyWithCount(1)).orElse(ItemStack.EMPTY);
    }

    private static int mealCount(CitizenRecord citizen) {
        return citizen.inventory().stream().filter(CommunityLifeService::food).mapToInt(ItemStack::getCount).sum();
    }

    static Supply find(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, PetState.Pet pet) {
        List<BuildingRecord> sources = new ArrayList<>();
        BuildingRecord home = settlement.building(citizen.homeBuildingId());
        if (home != null && home.active() && home.operational()) sources.add(home);
        SettlementInventoryService.warehouses(settlement).stream()
                .filter(building -> !sources.contains(building))
                .sorted(Comparator.comparingDouble(building -> building.position().distSqr(citizen.lastPosition())))
                .forEach(sources::add);
        for (BuildingRecord source : sources) {
            if (!level.hasChunkAt(source.position()) || settlement.community().danger(source.position())) continue;
            if (!source.type().isHouse() && !publicFoodAllowed(level, settlement, pet)) continue;
            for (var entry : CommunityStock.items(level, settlement, source).entrySet()) {
                if (entry.getValue() > 0 && food(pet).test(new ItemStack(entry.getKey())))
                    return new Supply(source, entry.getKey());
            }
        }
        return null;
    }

    static boolean publicFoodAllowed(ServerLevel level, SettlementRecord settlement, PetState.Pet pet) {
        if (pet.status == PetState.Status.ADOPTING && !pet.cat) return true;
        CommunityStock.invalidate(level, settlement);
        return CommunityStock.food(level, settlement) > settlement.community().foodReserve;
    }

    static boolean fetch(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
                         SettlementCitizenEntity entity, PetState.Pet pet) {
        Supply supply = find(level, settlement, citizen, pet);
        if (supply == null) {
            pet.blocker = "缺少宠物食物，或需保留居民口粮";
            return false;
        }
        if (citizen.inventoryCapacityFor(new ItemStack(supply.item())) < 1) {
            pet.blocker = "照料者背包已满";
            return false;
        }
        pet.activity = "照料者取食";
        BlockPos station = WarehouseInventoryService.approachPosition(level, supply.building(), entity);
        if (station == null) station = WarehouseInventoryService.reserveExtractionPosition(level, supply.building(), entity, supply.item());
        if (station == null) {
            CommunityLifeService.blocked(citizen, entity, "库存装卸点不可达或被占用");
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            return true;
        }
        PetCareService.moveCaregiver(level, citizen, entity, pet, station, CitizenActivity.FETCHING_MEAL,
                pet.cat ? "为聚落猫取食" : "为聚落狗取食");
        if (!WarehouseInventoryService.canInteract(level, supply.building(), entity)) return true;
        if (!supply.building().type().isHouse() && !publicFoodAllowed(level, settlement, pet)) {
            pet.blocker = "公共食物需保留给居民";
            return false;
        }
        ItemStack taken = WarehouseInventoryService.extractItemForResident(level, supply.building(), entity,
                supply.item(), 1, citizen::inventoryCapacityFor);
        if (taken.isEmpty()) {
            CommunityStock.invalidate(level, settlement);
            pet.blocker = "库存或装卸位置改变";
            return false;
        }
        ItemStack remainder = citizen.insertIntoInventory(taken);
        if (!remainder.isEmpty()) entity.spawnAtLocation(remainder);
        CommunityStock.invalidate(level, settlement);
        WarehouseInventoryService.releaseInteractionPosition(level, entity);
        pet.blocker = "";
        return true;
    }
}

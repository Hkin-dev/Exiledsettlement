package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** One appointment document per Minecraft day, stored in a real town-hall container, capped at four. */
public final class TownHallProductionService {
    private TownHallProductionService() { }

    public static void tickLevel(ServerLevel level) {
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            if (!settlement.active() || settlement.appointmentProductionDay() >= day) continue;
            BuildingRecord hall = settlement.buildings().stream()
                    .filter(BuildingRecord::operational)
                    .filter(building -> building.type() == BuildingType.TOWN_HALL)
                    .findFirst().orElse(null);
            if (hall == null || WarehouseInventoryService.countItem(level, hall,
                    ModItems.APPOINTMENT_DOCUMENT.get()) >= 4) continue;
            ItemStack remaining = WarehouseInventoryService.insertIntoBuilding(level, hall,
                    new ItemStack(ModItems.APPOINTMENT_DOCUMENT.get()));
            if (remaining.isEmpty() && settlement.markAppointmentProduced(day)) data.touch();
        }
    }
}

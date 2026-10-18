package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.network.OpenCitizenAssignmentPayload;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;

public final class CitizenAssignmentService {
    private CitizenAssignmentService() {
    }

    public static void open(ServerPlayer player, SettlementRecord settlement, CitizenRecord citizen) {
        if (!settlement.isMember(player.getUUID())) {
            player.displayClientMessage(Component.translatable("error.exiledsettlement.no_permission"), true);
            return;
        }
        if (citizen.equipAvailableInventory()) {
            com.hkin.exiledsettlement.settlement.SettlementSavedData.get(player.serverLevel()).touch();
        }
        List<OpenCitizenAssignmentPayload.WorkplaceEntry> workplaces = settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .filter(building -> building.capacity() > 0)
                .map(building -> new OpenCitizenAssignmentPayload.WorkplaceEntry(
                        building.id(), building.type(), building.customName(), building.operational(), false,
                        building.workerCapacity(), building.workerTarget(), settlement.lockedWorkers(building.id()),
                        building.configurationRevision()))
                .toList();
        List<OpenCitizenAssignmentPayload.InventoryEntry> inventory = java.util.stream.IntStream
                .range(0, CitizenRecord.INVENTORY_SIZE)
                .mapToObj(slot -> {
                    var stack = citizen.inventoryItem(slot);
                    return new OpenCitizenAssignmentPayload.InventoryEntry(
                            slot, stack, slot == citizen.activeSlot());
                })
                .toList();
        SettlementCitizenEntity projection = CitizenProjectionService.findProjection(player.serverLevel(), citizen);
        CitizenInteractionService.begin(player, projection);
        PacketDistributor.sendToPlayer(player, new OpenCitizenAssignmentPayload(
                settlement.id(), citizen.id(), citizen.name(),
                citizen.ysmModelId(), citizen.ysmTextureName(),
                citizen.profession(), citizen.hasManualAssignment(),
                citizen.manualReleasePending(), citizen.workBuildingId(), citizen.activity(),
                citizen.workFailure(), citizen.targetPosition(),
                workplaces, inventory, CitizenRecord.EQUIPMENT_SLOTS.stream().map(citizen::equipmentItem).toList(),
                CitizenEquipmentService.visibleHand(citizen), citizen.nutrition(),
                projection == null ? 0.0F : projection.getHealth(), projection == null ? 0.0F : projection.getMaxHealth(),
                projection != null, projection != null && projection.isArmorReady()));
        PacketDistributor.sendToPlayer(player,com.hkin.exiledsettlement.network.CitizenThermalPayload.of(citizen));
    }
}

package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class SettlementMessages {
    private SettlementMessages() {
    }

    public static void sendSummary(ServerPlayer player, SettlementRecord settlement) {
        if (settlement.active()) {
            BuildingFunctionService.validateSettlement(
                    player.serverLevel(),
                    SettlementSavedData.get(player.serverLevel()),
                    settlement
            );
        }
        player.displayClientMessage(
                Component.translatable("message.exiledsettlement.summary.header", settlement.name())
                        .withStyle(ChatFormatting.GOLD),
                false
        );
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.summary.identity",
                        shortId(settlement.id()),
                        Component.translatable("role.exiledsettlement." + settlement.roleOf(player.getUUID()).name().toLowerCase()),
                        Component.translatable(settlement.active()
                                ? "state.exiledsettlement.active"
                                : "state.exiledsettlement.inactive")
                ),
                false
        );
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.summary.citizens",
                        settlement.aliveCitizenCount(),
                        settlement.citizens().size()
                ),
                false
        );
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.summary.buildings",
                        settlement.operationalBuildingCount(),
                        settlement.activeBuildingCount()
                ),
                false
        );
        for (CitizenRecord citizen : settlement.citizens()) {
            Component home = citizen.homeBuildingId() == null
                    ? Component.translatable("state.exiledsettlement.homeless")
                    : Component.translatable("state.exiledsettlement.housed");
            Component activity = activityComponent(citizen);
            player.displayClientMessage(
                    Component.translatable(
                            "message.exiledsettlement.summary.citizen_line",
                            citizen.name(),
                            Component.translatable(citizen.profession().translationKey()),
                            home,
                            Component.translatable(citizen.alive()
                                    ? "state.exiledsettlement.alive"
                                    : "state.exiledsettlement.dead"),
                            activity
                    ).withStyle(citizen.alive() ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY),
                    false
            );
        }
    }

    public static void sendCitizenSummary(ServerPlayer player, CitizenRecord citizen) {
        Component home = citizen.homeBuildingId() == null
                ? Component.translatable("state.exiledsettlement.homeless")
                : Component.translatable("state.exiledsettlement.housed");
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.citizen.inspect",
                        citizen.name(),
                        Component.translatable(citizen.profession().translationKey()),
                        home,
                        activityComponent(citizen),
                        Component.translatable(citizen.workState().translationKey())
                ).withStyle(ChatFormatting.AQUA),
                false
        );
    }

    private static Component activityComponent(CitizenRecord citizen) {
        Component activity = Component.translatable(citizen.activity().translationKey());
        if (!citizen.workFailure().isEmpty()) {
            return Component.translatable(
                    "message.exiledsettlement.work.blocked",
                    activity,
                    Component.translatable("work_failure.exiledsettlement." + citizen.workFailure().toLowerCase())
            );
        }
        return activity;
    }

    public static void sendBuildingSummary(ServerPlayer player, SettlementRecord settlement, BuildingRecord building) {
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.building.summary",
                        building.customName().isBlank() ? Component.translatable(building.type().translationKey())
                                : Component.literal(building.customName()),
                        settlement.name(),
                        shortId(building.id())
                ).withStyle(ChatFormatting.YELLOW),
                false
        );
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.building.state",
                        Component.translatable(building.status().translationKey())
                ).withStyle(building.operational() ? ChatFormatting.GREEN : ChatFormatting.RED),
                false
        );
        if (building.type().isHouse()) {
            player.displayClientMessage(
                    Component.translatable(
                            "message.exiledsettlement.building.house",
                            settlement.occupantsOf(building.id()),
                            building.capacity(),
                            BuildingFunctionService.HOUSE_SCAN_RADIUS
                    ),
                    false
            );
        } else if (building.type() == BuildingType.WAREHOUSE) {
            player.displayClientMessage(
                    Component.translatable(
                            "message.exiledsettlement.building.warehouse",
                            WarehouseInventoryService.usedSlots(player.serverLevel(), building),
                            WarehouseInventoryService.totalSlots(player.serverLevel(), building),
                            building.servicePositions().size(),
                            BuildingFunctionService.WAREHOUSE_SCAN_RADIUS
                    ),
                    false
            );
        } else if (building.type() != BuildingType.WAGON) {
            player.displayClientMessage(
                    Component.translatable(
                            "message.exiledsettlement.building.workplace",
                            building.capacity(),
                            building.servicePositions().size()
                    ),
                    false
            );
        }
    }

    public static void sendFailure(ServerPlayer player, String reason) {
        sendFailure(player, reason, false);
    }

    public static void sendFailure(ServerPlayer player, String reason, boolean actionBar) {
        player.displayClientMessage(
                Component.translatable("error.exiledsettlement." + reason.toLowerCase()).withStyle(ChatFormatting.RED),
                actionBar
        );
    }

    public static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}

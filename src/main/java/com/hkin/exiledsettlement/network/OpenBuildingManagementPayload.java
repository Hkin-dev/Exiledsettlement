package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.hkin.exiledsettlement.citizen.CitizenProfession;

public record OpenBuildingManagementPayload(
        UUID settlementId, BlockPos corePosition, SettlementDetailsPayload.BuildingEntry building,
        int toolStockLimit, int equipmentStockLimit, List<InventoryEntry> inventory,
        List<PersonnelEntry> personnel, boolean refreshOnly
) implements CustomPacketPayload {
    public static final Type<OpenBuildingManagementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("open_building_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBuildingManagementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.settlementId());
                buffer.writeBlockPos(payload.corePosition());
                SettlementDetailsPayload.writeEntry(buffer, payload.building());
                buffer.writeVarInt(payload.toolStockLimit());
                buffer.writeVarInt(payload.equipmentStockLimit());
                buffer.writeVarInt(payload.inventory().size());
                for (InventoryEntry entry : payload.inventory()) {
                    buffer.writeResourceLocation(entry.itemId());
                    buffer.writeVarInt(entry.count());
                    buffer.writeVarInt(entry.produced());
                    buffer.writeVarInt(entry.consumed());
                }
                buffer.writeVarInt(payload.personnel().size());
                for (PersonnelEntry entry : payload.personnel()) {
                    buffer.writeUUID(entry.citizenId());
                    buffer.writeUtf(entry.name(), 48);
                    buffer.writeVarInt(entry.profession().ordinal());
                    buffer.writeBoolean(entry.assignedHere());
                    buffer.writeBoolean(entry.lockedHere());
                    buffer.writeBoolean(entry.lockedElsewhere());
                    buffer.writeBoolean(entry.blocked());
                    buffer.writeBoolean(entry.releasePending());
                }
                buffer.writeBoolean(payload.refreshOnly());
            },
            buffer -> {
                UUID settlementId = buffer.readUUID();
                BlockPos core = buffer.readBlockPos();
                SettlementDetailsPayload.BuildingEntry building = SettlementDetailsPayload.readEntry(buffer);
                int toolLimit = buffer.readVarInt();
                int equipmentLimit = buffer.readVarInt();
                int count = Math.min(512, buffer.readVarInt());
                List<InventoryEntry> inventory = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    inventory.add(new InventoryEntry(buffer.readResourceLocation(), buffer.readVarInt(),
                            buffer.readVarInt(), buffer.readVarInt()));
                }
                int personnelCount = Math.min(512, buffer.readVarInt());
                List<PersonnelEntry> personnel = new ArrayList<>(personnelCount);
                for (int i = 0; i < personnelCount; i++) {
                    UUID citizenId = buffer.readUUID();
                    String name = buffer.readUtf(48);
                    CitizenProfession profession = CitizenProfession.values()[Math.max(0,
                            Math.min(CitizenProfession.values().length - 1, buffer.readVarInt()))];
                    personnel.add(new PersonnelEntry(citizenId, name, profession,
                            buffer.readBoolean(), buffer.readBoolean(),
                            buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()));
                }
                return new OpenBuildingManagementPayload(settlementId, core, building, toolLimit, equipmentLimit,
                        List.copyOf(inventory), List.copyOf(personnel), buffer.readBoolean());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record InventoryEntry(ResourceLocation itemId, int count, int produced, int consumed) {
    }

    public record PersonnelEntry(UUID citizenId, String name, CitizenProfession profession,
                                 boolean assignedHere, boolean lockedHere,
                                 boolean lockedElsewhere, boolean blocked,
                                 boolean releasePending) {
    }
}

package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record OpenCitizenAssignmentPayload(
        UUID settlementId, UUID citizenId, String citizenName,
        String ysmModelId, String ysmTextureName,
        CitizenProfession profession, boolean manual, boolean manualReleasePending, UUID workBuildingId,
        CitizenActivity activity, String workFailure, BlockPos targetPosition,
        List<WorkplaceEntry> workplaces, List<InventoryEntry> inventory, List<ItemStack> equipment, ItemStack mainHand,
        int nutrition, float life, float maxLife, boolean projectionLoaded, boolean armorWorn,
        List<Integer> professionExperience, int teachingPointsToday
) implements CustomPacketPayload {
    // Structured work diagnostics include a reason, coordinates and a block registry ID.
    private static final int MAX_WORK_FAILURE_LENGTH = 1024;
    public static final Type<OpenCitizenAssignmentPayload> TYPE = new Type<>(ExiledSettlementMod.id("open_citizen_assignment"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCitizenAssignmentPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.settlementId());
                buffer.writeUUID(payload.citizenId());
                buffer.writeUtf(payload.citizenName(), 48);
                buffer.writeUtf(payload.ysmModelId(), 256);
                buffer.writeUtf(payload.ysmTextureName(), 128);
                buffer.writeVarInt(payload.profession().ordinal());
                buffer.writeBoolean(payload.manual());
                buffer.writeBoolean(payload.manualReleasePending());
                buffer.writeBoolean(payload.workBuildingId() != null);
                if (payload.workBuildingId() != null) buffer.writeUUID(payload.workBuildingId());
                buffer.writeVarInt(payload.activity().ordinal());
                buffer.writeUtf(payload.workFailure(), MAX_WORK_FAILURE_LENGTH);
                buffer.writeBoolean(payload.targetPosition() != null);
                if (payload.targetPosition() != null) buffer.writeBlockPos(payload.targetPosition());
                buffer.writeVarInt(payload.workplaces().size());
                for (WorkplaceEntry entry : payload.workplaces()) {
                    buffer.writeUUID(entry.id());
                    buffer.writeVarInt(entry.type().ordinal());
                    buffer.writeUtf(entry.name(), 32);
                    buffer.writeBoolean(entry.operational());
                    buffer.writeBoolean(entry.blueprint());
                    buffer.writeVarInt(entry.capacity());
                    buffer.writeVarInt(entry.workerTarget());
                    buffer.writeVarInt(entry.lockedWorkers());
                    buffer.writeVarInt(entry.configurationRevision());
                }
                buffer.writeVarInt(payload.inventory().size());
                for (InventoryEntry entry : payload.inventory()) {
                    buffer.writeVarInt(entry.slot());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.stack());
                    buffer.writeBoolean(entry.active());
                }
                for (int slot = 0; slot < 5; slot++) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.equipment().get(slot));
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.mainHand());
                buffer.writeVarInt(payload.nutrition());
                buffer.writeFloat(payload.life()); buffer.writeFloat(payload.maxLife());
                buffer.writeBoolean(payload.projectionLoaded()); buffer.writeBoolean(payload.armorWorn());
                for (CitizenProfession job : CitizenProfession.values()) buffer.writeVarInt(payload.professionExperience().get(job.ordinal()));
                buffer.writeVarInt(payload.teachingPointsToday());
            },
            buffer -> {
                UUID settlementId = buffer.readUUID();
                UUID citizenId = buffer.readUUID();
                String name = buffer.readUtf(48);
                String ysmModelId = buffer.readUtf(256);
                String ysmTextureName = buffer.readUtf(128);
                CitizenProfession profession = CitizenProfession.values()[Math.max(0,
                        Math.min(CitizenProfession.values().length - 1, buffer.readVarInt()))];
                boolean manual = buffer.readBoolean();
                boolean manualReleasePending = buffer.readBoolean();
                UUID workBuildingId = buffer.readBoolean() ? buffer.readUUID() : null;
                CitizenActivity activity = CitizenActivity.values()[Math.max(0,
                        Math.min(CitizenActivity.values().length - 1, buffer.readVarInt()))];
                String workFailure = buffer.readUtf(MAX_WORK_FAILURE_LENGTH);
                BlockPos targetPosition = buffer.readBoolean() ? buffer.readBlockPos() : null;
                int count = Math.min(512, buffer.readVarInt());
                List<WorkplaceEntry> workplaces = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    UUID id = buffer.readUUID();
                    BuildingType type = BuildingType.values()[Math.max(0,
                            Math.min(BuildingType.values().length - 1, buffer.readVarInt()))];
                    workplaces.add(new WorkplaceEntry(id, type, buffer.readUtf(32),
                            buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(),
                            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
                }
                int inventoryCount = Math.min(36, buffer.readVarInt());
                List<InventoryEntry> inventory = new ArrayList<>(inventoryCount);
                for (int i = 0; i < inventoryCount; i++) {
                    inventory.add(new InventoryEntry(buffer.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), buffer.readBoolean()));
                }
                List<ItemStack> equipment = new ArrayList<>(5);
                for (int slot = 0; slot < 5; slot++) equipment.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                ItemStack mainHand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                int nutrition = buffer.readVarInt();
                float life = buffer.readFloat(), maxLife = buffer.readFloat();
                boolean loaded = buffer.readBoolean(), armor = buffer.readBoolean();
                List<Integer> experience = new ArrayList<>();
                for (CitizenProfession job : CitizenProfession.values()) experience.add(Math.max(0, Math.min(185, buffer.readVarInt())));
                int teaching = Math.max(0, Math.min(10, buffer.readVarInt()));
                return new OpenCitizenAssignmentPayload(settlementId, citizenId, name,
                        ysmModelId, ysmTextureName, profession,
                        manual, manualReleasePending, workBuildingId, activity, workFailure, targetPosition,
                        List.copyOf(workplaces), List.copyOf(inventory), List.copyOf(equipment), mainHand,
                        nutrition, life, maxLife, loaded, armor, List.copyOf(experience), teaching);
            });

    /** Retain the constructor used by pre-experience fixtures and integrations. Wire version is still bumped. */
    public OpenCitizenAssignmentPayload(UUID settlementId, UUID citizenId, String citizenName,
            String ysmModelId, String ysmTextureName, CitizenProfession profession, boolean manual,
            boolean manualReleasePending, UUID workBuildingId, CitizenActivity activity, String workFailure,
            BlockPos targetPosition, List<WorkplaceEntry> workplaces, List<InventoryEntry> inventory,
            List<ItemStack> equipment, ItemStack mainHand, int nutrition, float life, float maxLife,
            boolean projectionLoaded, boolean armorWorn) {
        this(settlementId, citizenId, citizenName, ysmModelId, ysmTextureName, profession, manual,
                manualReleasePending, workBuildingId, activity, workFailure, targetPosition, workplaces,
                inventory, equipment, mainHand, nutrition, life, maxLife, projectionLoaded, armorWorn,
                java.util.Collections.nCopies(CitizenProfession.values().length, 0), 0);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record WorkplaceEntry(UUID id, BuildingType type, String name, boolean operational, boolean blueprint,
                                 int capacity, int workerTarget, int lockedWorkers,
                                 int configurationRevision) {
    }

    public record InventoryEntry(int slot, ItemStack stack, boolean active) {
    }
}

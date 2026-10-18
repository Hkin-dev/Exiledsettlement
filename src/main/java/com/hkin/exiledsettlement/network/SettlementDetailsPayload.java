package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record SettlementDetailsPayload(BlockPos corePosition, int boundaryRadiusChunks, List<BuildingEntry> buildings)
        implements CustomPacketPayload {
    public static final Type<SettlementDetailsPayload> TYPE = new Type<>(ExiledSettlementMod.id("settlement_details"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SettlementDetailsPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeVarInt(payload.boundaryRadiusChunks());
                buffer.writeVarInt(payload.buildings().size());
                for (BuildingEntry entry : payload.buildings()) {
                    writeEntry(buffer, entry);
                }
            },
            buffer -> {
                BlockPos core = buffer.readBlockPos();
                int boundaryRadius = buffer.readVarInt();
                int count = Math.min(1024, buffer.readVarInt());
                List<BuildingEntry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    entries.add(readEntry(buffer));
                }
                return new SettlementDetailsPayload(core, boundaryRadius, List.copyOf(entries));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void writeEntry(RegistryFriendlyByteBuf buffer, BuildingEntry entry) {
        buffer.writeUUID(entry.id());
        buffer.writeVarInt(entry.type().ordinal());
        buffer.writeUtf(entry.name(), 96);
        buffer.writeBlockPos(entry.position());
        buffer.writeVarInt(entry.facing().get2DDataValue());
        buffer.writeBlockPos(entry.minCorner());
        buffer.writeBlockPos(entry.maxCorner());
        buffer.writeVarInt(entry.transitionMargin());
        buffer.writeBoolean(entry.operational());
        buffer.writeVarInt(entry.status().ordinal());
        buffer.writeVarInt(entry.capacity());
        buffer.writeVarInt(entry.templateVersion());
        buffer.writeVarInt(entry.workerTarget());
        buffer.writeVarInt(entry.lockedWorkers());
        buffer.writeVarInt(entry.assignedWorkers());
        buffer.writeVarInt(entry.arrivedWorkers());
        buffer.writeVarInt(entry.workingWorkers());
        buffer.writeVarInt(entry.configurationRevision());
        buffer.writeVarInt(entry.worksiteAssignmentMode().ordinal());
        buffer.writeVarInt(entry.staffingProfessionOrdinal() + 1);
        buffer.writeVarInt(entry.occupants());
        buffer.writeVarInt(entry.usedSlots());
        buffer.writeBoolean(entry.creativeSupply());
        buffer.writeBoolean(entry.demolitionQueued());
        buffer.writeVarInt(entry.warehouseCategoryMask());
        buffer.writeResourceLocation(entry.productionId());
        buffer.writeVarInt(entry.productionCount());
        buffer.writeVarInt(entry.productionCapacity());
        buffer.writeVarInt(entry.repairCount());
        buffer.writeBoolean(entry.blueprint());
        buffer.writeVarInt(entry.constructionProgress());
        buffer.writeVarInt(entry.constructionTotal());
        buffer.writeBoolean(entry.customTemplate());buffer.writeBoolean(entry.terrainRegion());buffer.writeVarInt(entry.remainingPlacements());buffer.writeVarInt(entry.preparationActions());buffer.writeVarInt(entry.foundationPlacements());buffer.writeVarInt(entry.suggestedWorkers());
    }

    public static BuildingEntry readEntry(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        BuildingType type = enumValue(BuildingType.values(), buffer.readVarInt(), BuildingType.WAGON);
        String name = buffer.readUtf(96);
        BlockPos position = buffer.readBlockPos();
        Direction facing = Direction.from2DDataValue(buffer.readVarInt());
        BlockPos minCorner = buffer.readBlockPos();
        BlockPos maxCorner = buffer.readBlockPos();
        int transitionMargin = buffer.readVarInt();
        boolean operational = buffer.readBoolean();
        BuildingStatus status = enumValue(BuildingStatus.values(), buffer.readVarInt(), BuildingStatus.PENDING_VALIDATION);
        int capacity = buffer.readVarInt();
        int templateVersion = buffer.readVarInt();
        int workerTarget = buffer.readVarInt();
        int lockedWorkers = buffer.readVarInt();
        int assignedWorkers = buffer.readVarInt();
        int arrivedWorkers = buffer.readVarInt();
        int workingWorkers = buffer.readVarInt();
        int configurationRevision = buffer.readVarInt();
        WorksiteAssignmentMode worksiteAssignmentMode = enumValue(
                WorksiteAssignmentMode.values(), buffer.readVarInt(), WorksiteAssignmentMode.AUTO);
        int staffingProfessionOrdinal = buffer.readVarInt() - 1;
        int occupants = buffer.readVarInt();
        int usedSlots = buffer.readVarInt();
        boolean creativeSupply = buffer.readBoolean();
        boolean demolitionQueued = buffer.readBoolean();
        int warehouseCategoryMask = buffer.readVarInt();
        ResourceLocation productionId = buffer.readResourceLocation();
        int productionCount = buffer.readVarInt();
        int productionCapacity = buffer.readVarInt();
        int repairCount = buffer.readVarInt();
        boolean blueprint = buffer.readBoolean();
        int constructionProgress = buffer.readVarInt();
        int constructionTotal = buffer.readVarInt();
        boolean customTemplate = buffer.readBoolean();boolean terrainRegion=buffer.readBoolean();int remaining=buffer.readVarInt(),preparation=buffer.readVarInt(),foundation=buffer.readVarInt(),suggested=buffer.readVarInt();
        return new BuildingEntry(id, type, name, position, facing, minCorner, maxCorner, transitionMargin,
                operational, status, capacity, templateVersion, workerTarget, lockedWorkers,
                assignedWorkers, arrivedWorkers, workingWorkers, configurationRevision, staffingProfessionOrdinal,
                worksiteAssignmentMode,
                occupants, usedSlots, creativeSupply,
                demolitionQueued, warehouseCategoryMask, productionId, productionCount,
                productionCapacity, repairCount, blueprint, constructionProgress, constructionTotal, customTemplate,terrainRegion,remaining,preparation,foundation,suggested);
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    public record BuildingEntry(UUID id, BuildingType type, String name, BlockPos position, Direction facing,
                                BlockPos minCorner, BlockPos maxCorner, int transitionMargin,
                                boolean operational, BuildingStatus status, int capacity,
                                int templateVersion, int workerTarget, int lockedWorkers,
                                int assignedWorkers, int arrivedWorkers, int workingWorkers, int configurationRevision,
                                int staffingProfessionOrdinal,
                                WorksiteAssignmentMode worksiteAssignmentMode,
                                int occupants, int usedSlots, boolean creativeSupply, boolean demolitionQueued,
                                int warehouseCategoryMask, ResourceLocation productionId,
                                int productionCount, int productionCapacity, int repairCount,
                                boolean blueprint, int constructionProgress, int constructionTotal, boolean customTemplate, boolean terrainRegion,int remainingPlacements,int preparationActions,int foundationPlacements,int suggestedWorkers) {
        /** Wire-compatible flag: geometry comes from the server, not a built-in type template.
         * Includes custom buildings and public terrain orders whose internal worksite type is HOUSE. */
        public boolean serverDefinedGeometry() { return customTemplate; }
    }
}

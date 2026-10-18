package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.network.ClearPlacementPayload;
import com.hkin.exiledsettlement.network.StartPlacementPayload;
import com.hkin.exiledsettlement.network.StartRoadPlacementPayload;
import com.hkin.exiledsettlement.network.PlacementNetworking;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.road.RoadService;
import com.hkin.exiledsettlement.road.RoadTier;
import com.hkin.exiledsettlement.network.ContinuePlacementPayload;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PlacementSessionService {
    public static final double MAX_CAMERA_RAY_DISTANCE = 96.0D;
    private static final Map<UUID, PlacementSession> SESSIONS = new LinkedHashMap<>();
    private static final Map<UUID, RoadPlacementSession> ROAD_SESSIONS = new LinkedHashMap<>();

    private PlacementSessionService() {
    }

    public static void startFoundingCamp(ServerPlayer player) {
        cancel(player,false);
        var core=player.blockPosition();
        int version=BlueprintTemplates.currentTemplateVersion(BuildingType.STARTER_CAMP);
        SESSIONS.put(player.getUUID(),new PlacementSession(new UUID(0,0),core,BuildingType.STARTER_CAMP,version,
                "item.exiledsettlement.starter_camp_blueprint"));
        PacketDistributor.sendToPlayer(player,new StartPlacementPayload(BuildingType.STARTER_CAMP,version,
                "item.exiledsettlement.starter_camp_blueprint",core,6,java.util.List.of()));
    }

    public static void start(
            ServerPlayer player,
            SettlementRecord settlement,
            BuildingType type,
            int templateVersion,
            String displayTranslationKey,
            BlockPos corePosition
    ) {
        ROAD_SESSIONS.remove(player.getUUID());
        com.hkin.exiledsettlement.terrain.PlayerWorksService.cancel(player);
        PlacementSession session = new PlacementSession(
                settlement.id(), corePosition.immutable(), type, templateVersion, displayTranslationKey);
        SESSIONS.put(player.getUUID(), session);
        com.hkin.exiledsettlement.network.BuildingSnapshotPages.send(player,corePosition,new StartPlacementPayload(type,templateVersion,displayTranslationKey,corePosition,settlement.boundaryRadiusChunks(),java.util.List.of()),PlacementNetworking.overviewEntries(player,settlement));
        player.displayClientMessage(
                Component.translatable(
                        "message.exiledsettlement.placement.started",
                        Component.translatable(displayTranslationKey)
                ),
                true
        );
    }

    public static void startRoad(ServerPlayer player, SettlementRecord settlement, BlockPos corePosition, RoadTier tier) {
        com.hkin.exiledsettlement.terrain.PlayerWorksService.cancel(player);
        SESSIONS.remove(player.getUUID());
        ROAD_SESSIONS.put(player.getUUID(), new RoadPlacementSession(settlement.id(), corePosition.immutable(), tier));
        PacketDistributor.sendToPlayer(player, new StartRoadPlacementPayload(
                corePosition, tier, settlement.boundaryRadiusChunks()));
        player.displayClientMessage(Component.translatable("message.exiledsettlement.road.started", tier.level()), true);
    }

    public static OperationResult<Integer> placeRoad(ServerPlayer player, BlockPos first, BlockPos second) {
        if(com.hkin.exiledsettlement.terrain.PlayerWorksService.active(player)){
            com.hkin.exiledsettlement.terrain.PlayerWorksService.select(player,first,second);
            return OperationResult.success(0);
        }
        RoadPlacementSession session = ROAD_SESSIONS.get(player.getUUID());
        if (session == null || !(player.level() instanceof ServerLevel level)) {
            return OperationResult.failure("NO_PLACEMENT_SESSION");
        }
        SettlementRecord settlement = SettlementSavedData.get(level).findById(session.settlementId()).orElse(null);
        if (settlement == null || !settlement.active()
                || !settlement.corePosition().equals(session.corePosition())
                || !level.getBlockState(session.corePosition()).is(ModBlocks.SETTLEMENT_CORE.get())) {
            cancel(player, true);
            return OperationResult.failure("CORE_INACTIVE");
        }
        if (!settlement.isMember(player.getUUID())) {
            cancel(player, true);
            return OperationResult.failure("NO_PERMISSION");
        }
        OperationResult<Integer> result = com.hkin.exiledsettlement.road.ManualRoadPlacementService.enqueue(
                level, settlement, player, first, second, session.tier());
        if (result.success()) {
            PacketDistributor.sendToPlayer(player, new ContinuePlacementPayload(true, null));
        }
        return result;
    }

    public static OperationResult<BlueprintRecord> place(
            ServerPlayer player,
            BlockPos anchor,
            Direction facing
    ) {
        PlacementSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return OperationResult.failure("NO_PLACEMENT_SESSION");
        }
        if (session.type()==BuildingType.STARTER_CAMP && session.settlementId().equals(new UUID(0,0))) {
            var result=com.hkin.exiledsettlement.settlement.FoundingCampService.deploy(player,anchor,facing);
            if (result.success()) cancel(player,true);
            return result.success()?OperationResult.success(null):OperationResult.failure(result.reason());
        }
        if (!(player.level() instanceof ServerLevel level)) {
            cancel(player, true);
            return OperationResult.failure("NO_PLACEMENT_SESSION");
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findById(session.settlementId()).orElse(null);
        if (settlement == null
                || !settlement.active()
                || !settlement.corePosition().equals(session.corePosition())
                || !level.getBlockState(session.corePosition()).is(ModBlocks.SETTLEMENT_CORE.get())) {
            cancel(player, true);
            return OperationResult.failure("CORE_INACTIVE");
        }
        if (!settlement.isMember(player.getUUID())) {
            cancel(player, true);
            return OperationResult.failure("NO_PERMISSION");
        }
        if(BlueprintTemplates.flexibleProduction(session.type(),session.templateVersion()))
            return OperationResult.failure("AREA_SELECTION_REQUIRED");
        Direction horizontalFacing = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        OperationResult<BlueprintRecord> placed = BlueprintService.placeBlueprint(
                level,
                player.getUUID(),
                session.type(),
                anchor,
                horizontalFacing,
                session.templateVersion(),
                false,
                player
        );
        if (!placed.success() || placed.value() == null) {
            return placed;
        }

        PacketDistributor.sendToPlayer(player, new ContinuePlacementPayload(
                false, PlacementNetworking.blueprintEntry(level, settlement, placed.value())));
        return placed;
    }

    public static OperationResult<BlueprintRecord> placeArea(
            ServerPlayer player, BlockPos first, BlockPos second, Direction facing
    ) {
        PlacementSession session = SESSIONS.get(player.getUUID());
        if (session == null || !BlueprintTemplates.flexibleProduction(session.type(),session.templateVersion())
                || session.templateVersion()>BlueprintTemplates.currentTemplateVersion(session.type())) {
            return OperationResult.failure("NO_PLACEMENT_SESSION");
        }
        if (!(player.level() instanceof ServerLevel level)) return OperationResult.failure("NO_PLACEMENT_SESSION");
        SettlementRecord settlement = SettlementSavedData.get(level).findById(session.settlementId()).orElse(null);
        if (settlement == null || !settlement.active()
                || !settlement.corePosition().equals(session.corePosition())
                || !level.getBlockState(session.corePosition()).is(ModBlocks.SETTLEMENT_CORE.get())) {
            cancel(player, true);
            return OperationResult.failure("CORE_INACTIVE");
        }
        if (!settlement.isMember(player.getUUID())) return OperationResult.failure("NO_PERMISSION");
        long width=Math.abs((long)first.getX()-second.getX())+1;
        long depth=Math.abs((long)first.getZ()-second.getZ())+1;
        int minimum=session.type().minimumAreaSize();
        int maximum=BlueprintService.MAX_DYNAMIC_AREA_BLOCKS;
        if(width<minimum || depth<minimum)return OperationResult.failure("AREA_TOO_SMALL");
        if(width>maximum || depth>maximum || width*depth>maximum)return OperationResult.failure("AREA_TOO_LARGE");
        second=new BlockPos(second.getX(),first.getY(),second.getZ());
        for(int x=Math.min(first.getX(),second.getX())>>4;x<=(Math.max(first.getX(),second.getX())>>4);x++)
            for(int z=Math.min(first.getZ(),second.getZ())>>4;z<=(Math.max(first.getZ(),second.getZ())>>4);z++)
                if(!settlement.ownsChunk(x,z))return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        BlockPos center=new BlockPos((int)(((long)first.getX()+second.getX())/2),first.getY(),
                (int)(((long)first.getZ()+second.getZ())/2));
        SettlementRecord target=SettlementSavedData.get(level).findContaining(center).orElse(null);
        if(target==null || !target.id().equals(settlement.id()))return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        OperationResult<BlueprintRecord> result = BlueprintService.placeAreaBlueprint(
                level, player.getUUID(), session.type(), first, second, horizontal, session.templateVersion(), player);
        if (result.success() && result.value() != null) PacketDistributor.sendToPlayer(player,
                new ContinuePlacementPayload(false, PlacementNetworking.blueprintEntry(
                        level, settlement, result.value())));
        return result;
    }

    public static void cancel(ServerPlayer player, boolean notifyClient) {
        com.hkin.exiledsettlement.terrain.PlayerWorksService.cancel(player);
        SESSIONS.remove(player.getUUID());
        ROAD_SESSIONS.remove(player.getUUID());
        if (notifyClient) {
            PacketDistributor.sendToPlayer(player, ClearPlacementPayload.INSTANCE);
        }
    }

    @Nullable
    public static PlacementSession activeSession(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    public record PlacementSession(
            UUID settlementId, BlockPos corePosition, BuildingType type,
            int templateVersion, String displayTranslationKey
    ) {
    }

    public record RoadPlacementSession(UUID settlementId, BlockPos corePosition, RoadTier tier) {
    }
}

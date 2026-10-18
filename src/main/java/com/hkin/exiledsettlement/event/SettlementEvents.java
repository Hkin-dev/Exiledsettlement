package com.hkin.exiledsettlement.event;

import com.hkin.exiledsettlement.block.SettlementBuildingBlock;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.blueprint.PlacementSessionService;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.debug.ConstructionDebugService;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingRepairService;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.SettlementMigrationService;
import com.hkin.exiledsettlement.settlement.SettlementChunkLoadingService;
import com.hkin.exiledsettlement.settlement.SettlementInventoryLedgerService;
import com.hkin.exiledsettlement.settlement.TownHallProductionService;
import com.hkin.exiledsettlement.settlement.TravelingMerchantService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import com.hkin.exiledsettlement.road.RoadMovementService;

import java.util.UUID;
import java.util.List;

public final class SettlementEvents {
    private static final int PROJECTION_CHECK_INTERVAL_TICKS = 100;

    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        com.hkin.exiledsettlement.debug.WorkTargetDebugService.shutdown(event.getServer());
        com.hkin.exiledsettlement.debug.Engineering91RoadWalk.stop();
        com.hkin.exiledsettlement.debug.Engineering91EntranceProbe.stop();
        com.hkin.exiledsettlement.region.PastureGroundSafety.releaseAllAi();
        com.hkin.exiledsettlement.region.PastureResidentService.releaseAll();
        for (ServerLevel level : event.getServer().getAllLevels())
            com.hkin.exiledsettlement.road.ManualRoadPlacementService.abortLevel(level);
    }

    public void onServerTick(ServerTickEvent.Post event) {
        com.hkin.exiledsettlement.debug.TickPhaseProfiler.beginPost();
        long sampleStart=com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled()?System.nanoTime():0;
        com.hkin.exiledsettlement.debug.WorkTargetDebugService.tick(event.getServer());
        if (com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled()
                && !event.getServer().tickRateManager().runsNormally()) return;
        int tick = event.getServer().getTickCount();
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.tick(event.getServer());
        if(com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled())com.hkin.exiledsettlement.debug.NavigationLiveAcceptance.tick(event.getServer());
        if(com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled()) {
            com.hkin.exiledsettlement.debug.Engineering91RoadWalk.tick(event.getServer());
            com.hkin.exiledsettlement.debug.Engineering91EntranceProbe.tick(event.getServer());
        }
        com.hkin.exiledsettlement.network.BuildingSnapshotPages.tick(event.getServer());
        com.hkin.exiledsettlement.debug.BuildingLabService.tick(event.getServer());
        com.hkin.exiledsettlement.debug.BuildingEditBridge.tick(event.getServer());
        com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(0);
        for (ServerLevel level : event.getServer().getAllLevels()) {
            com.hkin.exiledsettlement.settlement.SettlementWildlifeService.tickLevel(level);
            com.hkin.exiledsettlement.building.MobRecoveryService.tick(level);
            com.hkin.exiledsettlement.road.PlayerRoadEdits.flush(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(1);
            com.hkin.exiledsettlement.decoration.DecorationSitePolicy.tick(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(2);
            com.hkin.exiledsettlement.settlement.SeasonWeatherService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(3);
            com.hkin.exiledsettlement.settlement.BlizzardData.tick(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(4);
            com.hkin.exiledsettlement.blueprint.CustomProjectionService.tick(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(5);
            com.hkin.exiledsettlement.decoration.DecorationService.flushPlayerRemovals(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(6);
            com.hkin.exiledsettlement.road.AutoRoadService.flushPlayerRemovals(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(7);
            com.hkin.exiledsettlement.region.ProductionRegionService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(8);
            com.hkin.exiledsettlement.terrain.TerrainService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(9);
            com.hkin.exiledsettlement.road.AutoRoadPlanner.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(10);
            com.hkin.exiledsettlement.road.RoadEarthworkService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(11);
            com.hkin.exiledsettlement.road.BridgeService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(12);
            com.hkin.exiledsettlement.road.RoadService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(13);
            if (tick % PROJECTION_CHECK_INTERVAL_TICKS == 0) {
                com.hkin.exiledsettlement.settlement.WorldSeasonData.get(level).syncSettlements(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(14);
                SettlementMigrationService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(15);
                SettlementChunkLoadingService.reconcile(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(16);
                CitizenProjectionService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(17);
                BuildingFunctionService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(18);
                SettlementInventoryLedgerService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(19);
                TownHallProductionService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(20);
                TravelingMerchantService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(21);
                com.hkin.exiledsettlement.settlement.CommunityLifeService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(22);
                com.hkin.exiledsettlement.settlement.SeasonSyncService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(23);
                com.hkin.exiledsettlement.decoration.DecorationService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(24);
            }
            if(com.hkin.exiledsettlement.road.AutoRoadService.reviewDue(tick,level.getDayTime()))com.hkin.exiledsettlement.road.AutoRoadService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(25);
            BlueprintService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(26);
            CitizenWorkService.tickLevel(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(27);
            CitizenWorkService.tickDemolitionStaggered(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(28);
            CitizenWorkService.tickSiteConstructionStaggered(level);
            com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(29);
            if (ConstructionDebugService.shouldAdvance(tick)) {
                ConstructionDebugService.tickLevel(level);
                com.hkin.exiledsettlement.debug.TickPhaseProfiler.mark(30);
            }
        }
        com.hkin.exiledsettlement.debug.EngineeringTickSamples.record(sampleStart);
        com.hkin.exiledsettlement.debug.TickPhaseProfiler.finish(event.getServer());
    }

    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        com.hkin.exiledsettlement.decoration.DecorationSitePolicy.invalidate(level);
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = null;
        boolean allowed = true;

        if (event.getState().is(ModBlocks.SETTLEMENT_CORE.get())) {
            settlement = data.findByCore(event.getPos()).orElse(null);
            allowed = settlement == null || settlement.isOwner(event.getPlayer().getUUID());
        } else if (event.getState().getBlock() instanceof SettlementBuildingBlock) {
            settlement = data.findByBuildingPosition(event.getPos()).orElse(null);
            allowed = settlement == null || settlement.isMember(event.getPlayer().getUUID());
        } else {
            settlement = com.hkin.exiledsettlement.decoration.DecorationService.protectedSettlementAt(level, event.getPos());
            if (settlement == null) settlement = com.hkin.exiledsettlement.road.AutoRoadService.protectedSettlementAt(level, event.getPos());
            if (settlement == null) settlement = com.hkin.exiledsettlement.terrain.TerrainService.protectedSettlementAt(level, event.getPos());
            allowed = settlement == null || settlement.isMember(event.getPlayer().getUUID());
        }

        if (allowed && !event.isCanceled()) {
            com.hkin.exiledsettlement.building.MobRecoveryService.playerEdit(level,event.getPos());
            com.hkin.exiledsettlement.decoration.DecorationService.notePlayerRemoval(level, event.getPos());
        }
        if (allowed && !event.isCanceled()) com.hkin.exiledsettlement.road.AutoRoadService.notePlayerRemoval(level, event.getPos());
        if (allowed && !event.isCanceled()) com.hkin.exiledsettlement.terrain.TerrainService.notePlayerRemoval(level, event.getPos());
        if(allowed && !event.isCanceled() && !(event.getPlayer() instanceof net.neoforged.neoforge.common.util.FakePlayer))
            com.hkin.exiledsettlement.building.MineExpansionService.playerEdit(level,event.getPos());
        if(allowed && !event.isCanceled() && !(event.getPlayer() instanceof net.neoforged.neoforge.common.util.FakePlayer))
            com.hkin.exiledsettlement.road.PlayerRoadEdits.note(level,event.getPos(),event.getState());
        if (!allowed) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable("error.exiledsettlement.no_permission"), false);
        }
    }

    public void onBlockPlace(BlockEvent.EntityPlaceEvent event){
        if(!event.isCanceled() && event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && !(player instanceof net.neoforged.neoforge.common.util.FakePlayer))
        {
            com.hkin.exiledsettlement.building.MobRecoveryService.playerEdit(level,event.getPos());
            com.hkin.exiledsettlement.building.MineExpansionService.playerEdit(level,event.getPos());
            com.hkin.exiledsettlement.road.PlayerRoadEdits.note(level,event.getPos(),event.getBlockSnapshot().getState());
        }
    }

    public void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var source=event.getExplosion().getIndirectSourceEntity();
            if(com.hkin.exiledsettlement.building.MobRecoveryService.mob(source))
                com.hkin.exiledsettlement.building.MobRecoveryService.capture(level,source,List.copyOf(event.getAffectedBlocks()),"explosion");
            else BuildingRepairService.recordExplosionDamage(level, List.copyOf(event.getAffectedBlocks()));
        }
    }

    public void onMobDestroy(net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent event) {
        if(!event.isCanceled() && event.getEntity().level() instanceof ServerLevel level)
            com.hkin.exiledsettlement.building.MobRecoveryService.capture(level,event.getEntity(),List.of(event.getPos()),"direct");
    }
    public void onMobGrief(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event) {
        if(event.canGrief() && event.getEntity() instanceof net.minecraft.world.entity.monster.EnderMan mob && mob.level() instanceof ServerLevel level)
            com.hkin.exiledsettlement.building.MobRecoveryService.enderman(level,mob);
    }
    public void onRecoveryFallingBlock(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(!event.isCanceled() && event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity falling) {
            var data=SettlementSavedData.get(level);var s=data.findContaining(falling.getStartPos()).orElse(null);
            if(s==null)return;var incident=s.mobRecovery().owner(falling.getStartPos());if(incident==null)return;
            var cell=incident.cells.get(falling.getStartPos());
            cell.status=com.hkin.exiledsettlement.building.MobRecoveryState.Status.UNSUPPORTED;
            cell.reason="RECOVERY_FALLING_DEBRIS";data.touch();
        }
    }

    public void onCitizenDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !CitizenProjectionService.isManaged(entity)) {
            return;
        }
        UUID settlementId = CitizenProjectionService.settlementId(entity);
        UUID citizenId = CitizenProjectionService.citizenId(entity);
        if (settlementId == null || citizenId == null) {
            return;
        }

        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord currentSettlement = data.settlementForCitizen(settlementId);
        CitizenRecord currentCitizen = currentSettlement == null ? null : currentSettlement.citizen(citizenId);
        if (currentCitizen != null && event.getEntity() instanceof SettlementCitizenEntity projection) {
            CitizenWorkService.recoverCarriedItem(level, data, currentCitizen, projection);
        }
        data.markCitizenDead(settlementId, citizenId, entity.blockPosition()).ifPresent(settlement -> {
            String citizenName = settlement.citizen(citizenId) == null
                    ? citizenId.toString().substring(0, 8)
                    : settlement.citizen(citizenId).name();
            long day = com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level);
            if (com.hkin.exiledsettlement.settlement.CommunityLifeService.winter(settlement, day)) settlement.community().winterLoss = true;
            settlement.community().event(day, citizenName + "死亡；原因：" + event.getSource().type().msgId());
            level.players().stream()
                    .filter(player -> settlement.isMember(player.getUUID()))
                    .forEach(player -> player.displayClientMessage(
                            Component.translatable("message.exiledsettlement.citizen.died", citizenName, settlement.name()),
                            false
                    ));
        });
    }

    public void onCitizenConversion(LivingConversionEvent.Pre event) {
        if (CitizenProjectionService.isManaged(event.getEntity())) {
            event.setConversionTimer(600);
            event.setCanceled(true);
        }
    }

    public void onCitizenBreeding(BabyEntitySpawnEvent event) {
        if (CitizenProjectionService.isManaged(event.getParentA())
                || CitizenProjectionService.isManaged(event.getParentB())) {
            event.setCanceled(true);
        }
    }

    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.hkin.exiledsettlement.debug.WorkTargetDebugService.clear(player);
            PlacementSessionService.cancel(player, false);
            com.hkin.exiledsettlement.citizen.CitizenNamingService.clear(player);
            com.hkin.exiledsettlement.citizen.CitizenInteractionService.clear(player);
        }
    }

    public void onEntityTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof net.minecraft.world.entity.animal.Animal animal)
            com.hkin.exiledsettlement.region.PastureResidentService.tick(animal);
        RoadMovementService.tick(event.getEntity());
    }
}

package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves short-lived, physical interaction stations around the settlement core.
 * The solid core block is a supply source, never a navigation destination.
 */
public final class SettlementCoreInteractionService {
    private static final double ARRIVAL_DISTANCE_SQUARED = 0.81D;
    private static final double INTERACTION_REACH_SQUARED = 20.25D;

    private static final java.util.Map<SettlementCitizenEntity, java.util.Map<BlockPos,Long>> FAILED = new java.util.WeakHashMap<>();
    private static final java.util.Map<SettlementCitizenEntity, BlockPos> DESTINATIONS = new java.util.WeakHashMap<>();

    private SettlementCoreInteractionService() {
    }

    @Nullable
    public static BlockPos reserveSupplyPosition(ServerLevel level, SettlementRecord settlement, SettlementCitizenEntity entity) {
        if (canReceiveSupply(level, settlement, entity)) return entity.navigationFeetPosition();
        var failures=FAILED.computeIfAbsent(entity,e->new java.util.HashMap<>());
        failures.entrySet().removeIf(e->e.getValue()<=level.getGameTime());
        var candidates=interactionCandidates(level, settlement.corePosition(), entity).stream()
                .filter(p->!failures.containsKey(p)).toList();
        // A bounded probe can fail while the resident is still approaching a
        // cliff. Keep the legal station so ordinary navigation can widen its
        // detour and prepare a paid local passage, rather than stopping it here.
        var committed=DESTINATIONS.get(entity);
        if(committed!=null && candidates.contains(committed))return committed;
        var destination=candidates.stream().filter(entity::hasReachablePathTo).findFirst().orElse(null);
        if(destination==null && !candidates.isEmpty())destination=candidates.getFirst();
        if(destination!=null)DESTINATIONS.put(entity,destination);
        return destination;
    }

    @Nullable
    public static BlockPos reserveWaitingPosition(
            ServerLevel level, SettlementRecord settlement, SettlementCitizenEntity entity
    ) {
        long gameTime = level.getGameTime();
        for (BlockPos candidate : ringCandidates(level, settlement.corePosition(), entity, 3, 6, false)) {
            if (!entity.hasReachablePathTo(candidate)) continue;
            if (CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), candidate, entity.blockPosition(), gameTime)) return candidate;
        }
        return null;
    }

    public static boolean hasPhysicalStation(
            ServerLevel level, SettlementRecord settlement, SettlementCitizenEntity entity
    ) {
        return interactionCandidates(level, settlement.corePosition(), entity).stream()
                .anyMatch(entity::hasReachablePathTo);
    }

    public static boolean canReceiveSupply(ServerLevel level, SettlementRecord settlement, SettlementCitizenEntity entity) {
        return CitizenInteractionRange.visible(entity, settlement.corePosition());
    }

    public static void release(ServerLevel level, SettlementCitizenEntity entity) {
        DESTINATIONS.remove(entity);
        CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
    }

    public static void abandon(ServerLevel level, SettlementCitizenEntity entity) {
        var at=DESTINATIONS.remove(entity);
        if(at!=null)FAILED.computeIfAbsent(entity,e->new java.util.HashMap<>()).put(at,level.getGameTime()+600);
        CitizenNavigationReservationService.abandonStation(level, entity.getUUID(), level.getGameTime());
    }

    private static List<BlockPos> interactionCandidates(
            ServerLevel level, BlockPos core, SettlementCitizenEntity entity
    ) {
        return ringCandidates(level, core, entity, 1, 4, true);
    }

    private static List<BlockPos> ringCandidates(
            ServerLevel level, BlockPos core, SettlementCitizenEntity entity,
            int minimumRadius, int maximumRadius, boolean requireCoreVisibility
    ) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (int radius = minimumRadius; radius <= maximumRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                addVerticalCandidates(level, core, entity, positions, dx, -radius, requireCoreVisibility);
                addVerticalCandidates(level, core, entity, positions, dx, radius, requireCoreVisibility);
            }
            for (int dz = -radius + 1; dz < radius; dz++) {
                addVerticalCandidates(level, core, entity, positions, -radius, dz, requireCoreVisibility);
                addVerticalCandidates(level, core, entity, positions, radius, dz, requireCoreVisibility);
            }
        }
        List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingDouble(position -> position.distSqr(entity.blockPosition())));
        return List.copyOf(sorted);
    }

    private static void addVerticalCandidates(
            ServerLevel level, BlockPos core, SettlementCitizenEntity entity, Set<BlockPos> result,
            int dx, int dz, boolean requireCoreVisibility
    ) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos candidate = core.offset(dx, dy, dz);
            if (!level.hasChunkAt(candidate) || !entity.canStandAtPosition(candidate)) continue;
            if (requireCoreVisibility && !isVisibleInteractionStation(level, candidate, core, entity)) continue;
            result.add(candidate.immutable());
        }
    }

    private static boolean isVisibleInteractionStation(
            ServerLevel level, BlockPos station, BlockPos core, SettlementCitizenEntity entity
    ) {
        Vec3 eye = new Vec3(station.getX() + 0.5D, station.getY() + entity.getEyeY() - entity.getY(), station.getZ() + 0.5D);
        Vec3 center = Vec3.atCenterOf(core);
        double dx=Math.max(0,Math.abs(station.getX()-core.getX())-.5D);
        double dz=Math.max(0,Math.abs(station.getZ()-core.getZ())-.5D);
        // Keep a margin for a navigator stopping off the cell centre; actual
        // transfer still checks the resident's unchanged physical reach.
        if(dx*dx+dz*dz>9 || Math.abs(station.getY()-core.getY())>2)return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(core);
    }
}

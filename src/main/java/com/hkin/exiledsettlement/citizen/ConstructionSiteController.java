package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Per-site runtime plan; persisted world/footing records remain the source of truth. */
public final class ConstructionSiteController {
    public static final int STALL_TICKS = 200;
    /** Open ground and earthworks use real local ground/support, never an exterior building frame. */
    public static boolean groundWork(BlueprintRecord site) {
        return site.region()!=null || site.terrainProject()!=null || site.dynamicArea() || site.type().isDynamicArea();
    }
    public static boolean recordedSupport(net.minecraft.world.level.Level level,BlueprintRecord site,BlockPos at) {
        var item=site.temporaryFootingItem(at);
        return item!=net.minecraft.world.item.Items.AIR && level.hasChunkAt(at)
                && level.getBlockState(at).is(net.minecraft.world.level.block.Block.byItem(item));
    }
    public void recordGroundAction(long tick, String stage, BlockPos position) {
        phase = stage;
        target = position;
        lastPlacement = tick;
        placements++;
    }
    private List<BlockPos> frame;
    private BlockPos target;
    private long lastPlacement = -1, lastDiagnostic = -1;
    private int placements, stalls;
    private final Map<BlockPos, Long> accessRetryAfter = new HashMap<>();
    private String accessFailure = "";
    public boolean prepareGroundAccessNext() { return placements % 3 == 0; }
    public boolean accessReady(BlockPos position, long tick) {
        accessRetryAfter.entrySet().removeIf(e -> e.getValue() <= tick);
        return !accessRetryAfter.containsKey(position);
    }
    public void postponeAccess(BlockPos position, long tick, String reason) {
        accessRetryAfter.put(position.immutable(), tick + 100);
        accessFailure = reason;
    }
    private String phase = "SCAFFOLD";
    private final Map<UUID, ScaffoldMovementController> movements = new LinkedHashMap<>();
    private final Map<BlockPos, Long> postponed = new HashMap<>();
    private record FrameClaim(UUID worker, long expires) { }
    private final Map<BlockPos, FrameClaim> frameClaims = new HashMap<>();

    public BlockPos activeFrameTarget(ServerLevel level, SettlementCitizenEntity worker) {
        for (var entry : frameClaims.entrySet()) {
            if (entry.getValue().worker().equals(worker.getUUID()) && accessReady(entry.getKey(), level.getGameTime())
                    && ScaffoldSitePreparation.space(level, entry.getKey())) {
                entry.setValue(new FrameClaim(worker.getUUID(), level.getGameTime() + 100));
                return entry.getKey();
            }
        }
        return null;
    }

    public BlockPos nextForWorker(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity worker) {
        if(groundWork(site)){frameClaims.clear();phase="GROUND_WORK";return null;}
        long now = level.getGameTime();
        frameClaims.entrySet().removeIf(e -> e.getValue().expires() < now
                || !ScaffoldSitePreparation.space(level, e.getKey())
                || !accessReady(e.getKey(), now));
        for (var entry : frameClaims.entrySet()) {
            if (entry.getValue().worker().equals(worker.getUUID())) {
                entry.setValue(new FrameClaim(worker.getUUID(), now + 100));
                return entry.getKey();
            }
        }
        BlockPos next = frame(level, site).stream()
                .filter(p -> !frameClaims.containsKey(p) && accessReady(p, now) && ScaffoldSitePreparation.space(level, p))
                .filter(p -> CitizenWorkService.scaffoldSupported(level, p))
                .min(Comparator.comparingDouble(p -> p.distSqr(worker.blockPosition()))).orElse(null);
        if (next != null) frameClaims.put(next, new FrameClaim(worker.getUUID(), now + 100));
        phase = "PARALLEL_STRUCTURE_AND_SCAFFOLD";
        return next;
    }

    public BlockPos closurePrerequisite(ServerLevel level, BlueprintRecord site,
            com.hkin.exiledsettlement.blueprint.BlueprintPlacement placement) {
        BlockPos lowerNode = com.hkin.exiledsettlement.blueprint.ConstructionDependencies.firstUnfinished(
                level, BlueprintTemplates.create(site), placement);
        if (lowerNode != null) return lowerNode;
        return com.hkin.exiledsettlement.blueprint.ConstructionClosureGuard.prerequisite(
                level, BlueprintTemplates.create(site), placement);
    }

    public BlockPos postponeStructureTarget(ServerLevel level, BlueprintRecord site,
            com.hkin.exiledsettlement.settlement.SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        long now = level.getGameTime();
        postponed.entrySet().removeIf(e -> e.getValue() <= now);
        if (citizen.targetPosition() != null) postponed.put(citizen.targetPosition(), now + 200);
        Set<BlockPos> excluded = new HashSet<>(postponed.keySet());
        for (var other : settlement.citizens()) {
            if (!other.id().equals(citizen.id()) && site.id().equals(other.targetBlueprintId())
                    && other.targetPosition() != null) excluded.add(other.targetPosition());
        }
        return com.hkin.exiledsettlement.blueprint.BlueprintService.pendingPlacements(level, site, excluded, entity.blockPosition())
                .stream().filter(p -> p.placement().state().canSurvive(level, p.placement().position()))
                .filter(p -> closurePrerequisite(level, site, p.placement()) == null)
                .min(Comparator.comparingInt((com.hkin.exiledsettlement.blueprint.BlueprintService.PendingPlacement p) ->
                        CitizenWorkService.canConstructFromCurrentPosition(entity, p.placement().position()) ? 0 : 1)
                        .thenComparingDouble(p -> p.placement().position().distSqr(entity.blockPosition())))
                .map(p -> p.placement().position()).orElse(null);
    }

    public List<BlockPos> frame(ServerLevel level, BlueprintRecord site) {
        if(groundWork(site))return List.of();
        if (frame != null) return frame;
        var template = BlueprintTemplates.create(site);
        var cells = new LinkedHashSet<BlockPos>();
        for (int y = template.minCorner().getY() + ExteriorScaffoldFrame.FIRST_RING_OFFSET;
                y < template.maxCorner().getY(); y += ExteriorScaffoldFrame.RING_INTERVAL) {
            for (BlockPos column : ExteriorScaffoldFrame.columns(template, y)) {
                int base = template.minCorner().getY() + 1;
                while (base > template.minCorner().getY() - 3) {
                    var below = new BlockPos(column.getX(), base - 1, column.getZ());
                    if (!level.hasChunkAt(below)) return List.of();
                    if (!level.getBlockState(below).is(Blocks.SNOW)
                            && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) break;
                    base--;
                }
                for (int cy = base; cy <= y; cy++) cells.add(new BlockPos(column.getX(), cy, column.getZ()));
            }
            cells.addAll(ExteriorScaffoldFrame.ring(template, y));
        }
        frame = List.copyOf(cells);
        return frame;
    }

    public BlockPos next(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity worker) {
        if(groundWork(site)){target=null;frameClaims.clear();phase="GROUND_WORK";return null;}
        long now = level.getGameTime();
        if (lastPlacement < 0) lastPlacement = now;
        if (target != null && level.getBlockState(target).is(Blocks.SCAFFOLDING)) {
            placements++; lastPlacement = now; target = null;
            movements.values().forEach(ScaffoldMovementController::completedTarget);
        }
        if (target != null && ScaffoldSitePreparation.space(level, target)
                && !CitizenWorkService.scaffoldSupported(level, target) && !worker.hasActiveConstructionMovement()) {
            target = null;
            movements.values().forEach(ScaffoldMovementController::completedTarget);
        }
        if (target != null) return target; // Never select another target during a valid active journey.
        List<BlockPos> missing = frame(level, site).stream()
                .filter(p -> !level.getBlockState(p).is(Blocks.SCAFFOLDING)).toList();
        if (missing.isEmpty() && frame != null) { phase = "STRUCTURE"; return null; }
        phase = "SCAFFOLD";
        // Respect support dependencies, then prefer nearby work rather than hopping between opposite arms.
        target = missing.stream().filter(p -> accessReady(p, now) && ScaffoldSitePreparation.space(level, p))
                .filter(p -> CitizenWorkService.scaffoldSupported(level, p))
                .min(Comparator.comparingDouble(p -> p.distSqr(worker.blockPosition())))
                .orElse(null);
        return target;
    }

    public void diagnose(ServerLevel level, CitizenRecord worker, SettlementCitizenEntity entity) {
        long now = level.getGameTime();
        if (phase.equals("SCAFFOLD") && now - lastPlacement >= STALL_TICKS
                && (lastDiagnostic < 0 || now - lastDiagnostic >= STALL_TICKS)) {
            stalls++; lastDiagnostic = now;
            entity.traceConstructionDecision("site-stall:" + diagnostics() + ":worker=" + worker.id()
                    + ":feet=" + entity.blockPosition() + ":failure=" + worker.workFailure()
                    + ":route=" + movement(entity).diagnostics());
        }
    }

    public ScaffoldMovementController movement(SettlementCitizenEntity entity) {
        if (!movements.containsKey(entity.getUUID()) && movements.size() >= 64) {
            movements.remove(movements.keySet().iterator().next());
        }
        return movements.computeIfAbsent(entity.getUUID(), id -> new ScaffoldMovementController());
    }
    public String movementDiagnostics() {
        return movements.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().diagnostics())
                .collect(java.util.stream.Collectors.joining(";"));
    }
    public String diagnostics() {
        return "phase=" + phase + ",target=" + target + ",placed=" + placements + ",stalls=" + stalls
                + ",lastPlacement=" + lastPlacement + ",accessFailure=" + accessFailure;
    }
}

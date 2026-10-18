package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a bounded, deterministic work package from the remaining blueprint.
 *
 * <p>The world remains authoritative: packages are cheap plans that can be
 * regenerated after a block edit, task hand-off or server restart. Keeping a
 * resident on one layer/stage/local cluster prevents the old per-block linear
 * scan from sending them back and forth across the entire building.</p>
 */
public final class ConstructionPlanService {
    public static final int MAX_PACKAGE_PLACEMENTS = 64;
    public static final int MAX_SUPPLY_PLACEMENTS = 1024;
    private static final int PACKAGE_RADIUS = 10;

    private ConstructionPlanService() {
    }

    public static WorkPackage createWorkPackage(
            net.minecraft.server.level.ServerLevel level,
            BlueprintRecord blueprint,
            Set<BlockPos> excludedPositions,
            BlockPos workerPosition,
            BlockPos preferredTarget
    ) {
        List<BlueprintService.PendingPlacement> pending = BlueprintService.pendingPlacements(
                level, blueprint, excludedPositions, workerPosition);
        if (pending.isEmpty()) return WorkPackage.empty();
        if(blueprint.terrainProject()!=null || FarmEarthworkService.applies(blueprint) && (blueprint.earthworkPlan()==null || !blueprint.earthworkPlan().done())) {
            var local=List.copyOf(pending.subList(0,Math.min(MAX_PACKAGE_PLACEMENTS,pending.size())));
            Map<Item,Integer> materials=new LinkedHashMap<>();
            for(var p:local) if(p.placement().material()!=null) materials.merge(p.placement().material(),1,Integer::sum);
            var seed=local.getFirst();
            return new WorkPackage(seed.placement().position(),0,seed.placement().position().getY(),local,Collections.unmodifiableMap(materials));
        }
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);

        BlueprintService.PendingPlacement exactPreferred = pending.stream()
                .filter(candidate -> candidate.placement().position().equals(preferredTarget))
                .findFirst().orElse(null);
        BlueprintService.PendingPlacement frontier = pending.getFirst();
        boolean frontierClearance = blueprint.hasClearanceTarget(frontier.placement().position());
        int frontierStage = frontierClearance
                ? -1 : BlueprintService.constructionStageFor(frontier.placement());
        int frontierLayer = frontier.placement().position().getY();
        BlueprintService.PendingPlacement seed = exactPreferred != null ? exactPreferred : pending.stream()
                .filter(candidate -> blueprint.hasClearanceTarget(candidate.placement().position())
                        == frontierClearance)
                .filter(candidate -> candidate.placement().position().getY() == frontierLayer)
                .filter(candidate -> frontierClearance
                        || BlueprintService.constructionStageFor(candidate.placement()) == frontierStage)
                .min(java.util.Comparator
                        .comparingInt((BlueprintService.PendingPlacement candidate) ->
                                candidate.placement().position().distManhattan(preferredTarget))
                        .thenComparingInt(candidate ->
                                candidate.placement().position().distManhattan(workerPosition))
                        .thenComparingInt(BlueprintService.PendingPlacement::index))
                .orElse(frontier);
        boolean clearance = blueprint.hasClearanceTarget(seed.placement().position());
        int stage = clearance ? -1 : BlueprintService.constructionStageFor(seed.placement());
        int layer = seed.placement().position().getY();

        List<BlueprintService.PendingPlacement> nearby = new ArrayList<>();
        for (BlueprintService.PendingPlacement candidate : pending) {
            if (candidate == seed) continue;
            BlockPos position = candidate.placement().position();
            boolean candidateClearance = blueprint.hasClearanceTarget(position);
            if (candidateClearance != clearance || position.getY() != layer
                    || !template.constructionNode(candidate.placement()).equals(template.constructionNode(seed.placement()))
                    || template.constructionPhase(candidate.placement()) != template.constructionPhase(seed.placement())
                    || position.distManhattan(seed.placement().position()) > PACKAGE_RADIUS
                    || !candidateClearance
                    && BlueprintService.constructionStageFor(candidate.placement()) != stage) continue;
            nearby.add(candidate);
        }
        nearby.sort(java.util.Comparator
                .comparingInt((BlueprintService.PendingPlacement candidate) ->
                        candidate.placement().position().distManhattan(workerPosition))
                .thenComparingInt(candidate ->
                        candidate.placement().position().distManhattan(seed.placement().position()))
                .thenComparingInt(BlueprintService.PendingPlacement::index));
        List<BlueprintService.PendingPlacement> local = new ArrayList<>();
        local.add(seed);
        local.addAll(nearby.subList(0, Math.min(MAX_PACKAGE_PLACEMENTS - 1, nearby.size())));

        Map<Item, Integer> requirements = new LinkedHashMap<>();
        for (BlueprintService.PendingPlacement placement : local) {
            Item material = placement.placement().material();
            if (material != null) requirements.merge(material, 1, Integer::sum);
        }
        return new WorkPackage(
                seed.placement().position(), stage, layer,
                List.copyOf(local), Collections.unmodifiableMap(requirements));
    }

    /**
     * Plans a bounded backpack load from the remaining blueprint rather than
     * limiting a supply visit to the resident's much smaller local work package.
     * The preferred material is kept first so a full backpack can never omit the
     * block that caused the supply request.
     */
    public static SupplyPlan createSupplyPlan(
            net.minecraft.server.level.ServerLevel level,
            BlueprintRecord blueprint,
            Set<BlockPos> excludedPositions,
            BlockPos workerPosition,
            BlockPos preferredTarget
    ) {
        if (blueprint.terrainProject() != null) {
            return terrainSupplyPlan(blueprint.terrainProject());
        }
        List<BlueprintService.PendingPlacement> pending = BlueprintService.pendingPlacements(
                level, blueprint, excludedPositions, workerPosition);
        if (pending.isEmpty()) return SupplyPlan.empty();

        List<BlueprintService.PendingPlacement> ordered = new ArrayList<>();
        pending.stream()
                .filter(candidate -> candidate.placement().position().equals(preferredTarget))
                .findFirst().ifPresent(ordered::add);
        for (BlueprintService.PendingPlacement candidate : pending) {
            if (ordered.size() >= MAX_SUPPLY_PLACEMENTS) break;
            if (!candidate.placement().position().equals(preferredTarget)) ordered.add(candidate);
        }

        Map<Item, Integer> requirements = new LinkedHashMap<>();
        int materialPlacements = 0;
        for (BlueprintService.PendingPlacement candidate : ordered) {
            Item material = candidate.placement().material();
            if (material == null) continue;
            requirements.merge(material, 1, Integer::sum);
            materialPlacements++;
        }
        return new SupplyPlan(ordered.size(), materialPlacements,
                Collections.unmodifiableMap(requirements));
    }

    /** Supply is frozen paid demand, not the currently supported work frontier.
     * A vertical foundation or a bridge may expose only one executable block.
     * Counting that frontier made every new layer require another warehouse trip.
     */
    public static SupplyPlan terrainSupplyPlan(com.hkin.exiledsettlement.terrain.TerrainProject project) {
        if (project.cancelled || project.completed) return SupplyPlan.empty();
        Map<BlockPos, Item> remaining = new LinkedHashMap<>();
        for (int i = project.cursor; i < project.steps.size(); i++) {
            if (project.finished.get(i) || project.abandoned.get(i)) continue;
            var step = project.steps.get(i);
            if (step.fill()) remaining.put(step.position(), step.target().getBlock().asItem());
        }
        Map<Item, Integer> requirements = new LinkedHashMap<>();
        for (var material : remaining.values()) {
            if (material == net.minecraft.world.item.Items.AIR) continue;
            int stackSize = material.getDefaultMaxStackSize();
            requirements.compute(material, (item, count) -> Math.min(stackSize, count == null ? 1 : count + 1));
        }
        return new SupplyPlan(remaining.size(), remaining.size(), Collections.unmodifiableMap(requirements));
    }

    /** Related frozen orders share a delivery batch even when published one column at a time. */
    public static int relatedTerrainSupplyNeed(com.hkin.exiledsettlement.settlement.SettlementRecord settlement, com.hkin.exiledsettlement.terrain.TerrainProject own, Item material) {
        if (own == null) return 1;
        int need = 0, limit = material.getDefaultMaxStackSize();
        var positions = new java.util.HashSet<BlockPos>();
        for (var project : settlement.terrain().projects.values()) {
            if (project.cancelled || project.completed || project != own
                    && java.util.Collections.disjoint(project.dependencies, own.dependencies)) continue;
            for (int i = project.cursor; i < project.steps.size(); i++) {
                var step = project.steps.get(i);
                if (!project.finished.get(i) && !project.abandoned.get(i) && step.fill()
                        && step.target().getBlock().asItem() == material && positions.add(step.position())
                        && ++need >= limit) return limit;
            }
        }
        return Math.max(1, need);
    }

    public record WorkPackage(
            BlockPos seed,
            int stage,
            int layer,
            List<BlueprintService.PendingPlacement> placements,
            Map<Item, Integer> requirements
    ) {
        private static WorkPackage empty() {
            return new WorkPackage(BlockPos.ZERO, -1, 0, List.of(), Map.of());
        }

        public boolean isEmpty() {
            return placements.isEmpty();
        }
    }

    public record SupplyPlan(
            int plannedPlacements,
            int materialPlacements,
            Map<Item, Integer> requirements
    ) {
        private static SupplyPlan empty() {
            return new SupplyPlan(0, 0, Map.of());
        }

        public boolean isEmpty() {
            return plannedPlacements == 0;
        }
    }
}

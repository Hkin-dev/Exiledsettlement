package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.registry.ExiledSettlementTags;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BlueprintService {
    public static final int MAX_DYNAMIC_AREA_BLOCKS = 1024;
    public static final int PREVIEW_INTERVAL_TICKS = 20;

    private BlueprintService() {
    }

    /** Revoke this construction request and only the unfinished work that no longer has an owner. */
    public static boolean cancelConstruction(SettlementRecord settlement, BlueprintRecord blueprint) {
        if (blueprint == null || settlement.blueprint(blueprint.id()) != blueprint) return false;
        boolean changed = blueprint.queueCancellation();
        return com.hkin.exiledsettlement.terrain.TerrainChildLifecycle.cancel(settlement, blueprint) || changed;
    }

    public static OperationResult<BlueprintRecord> placeBlueprint(
            ServerLevel level, UUID actorId, BuildingType type, BlockPos anchor, Direction facing,
            boolean refundItem, @Nullable ServerPlayer feedbackPlayer
    ) {
        return placeBlueprint(level, actorId, type, anchor, facing,
                BlueprintTemplates.currentTemplateVersion(type), refundItem, feedbackPlayer);
    }

    public static OperationResult<BlueprintRecord> placeBlueprint(
            ServerLevel level, UUID actorId, BuildingType type, BlockPos anchor, Direction facing,
            int templateVersion, boolean refundItem, @Nullable ServerPlayer feedbackPlayer
    ) {
        if (!BlueprintTemplates.supports(type)) return OperationResult.failure("UNSUPPORTED_BLUEPRINT");
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findContaining(anchor).orElse(null);
        if (settlement == null) return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        if (!settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");

        BuildingBlueprintTemplate template;
        try {
            template = ConstructionWorkArea.template(BlueprintTemplates.create(type, anchor, facing, templateVersion));
        } catch (IllegalArgumentException exception) {
            return OperationResult.failure("UNSUPPORTED_BLUEPRINT");
        }
        SiteCheck site = validateSite(level, settlement, template);
        if (!site.valid()) {
            sendSiteFailure(feedbackPlayer, site);
            return OperationResult.failure(site.reason());
        }
        releaseUnstartedDecorations(settlement,template);
        BlueprintRecord blueprint = settlement.addBlueprint(
                type, anchor, anchor, facing, actorId, refundItem, templateVersion, site.clearances());
        com.hkin.exiledsettlement.terrain.P0PlanningService.placed(level,settlement,blueprint);
        data.touch();
        if (feedbackPlayer != null) sendPlaced(feedbackPlayer, blueprint, template);
        return OperationResult.success(blueprint);
    }

    public static String validateCustomCell(ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate template,
            BlueprintPlacement placement, Map<BlockPos, BlockState> clearances) {
        SiteCheck check=validatePlacement(level,settlement,template,placement);
        if (!check.valid()) return check.reason()+"："+placement.position().toShortString();
        clearances.putAll(check.clearances());
        return "";
    }
    public static OperationResult<BlueprintRecord> placeCustomValidated(ServerLevel level,UUID actorId,
            CustomTemplateSnapshot snapshot,@Nullable ServerPlayer player,Map<BlockPos,BlockState> clearances) {
        var template=snapshot.template(); var data=SettlementSavedData.get(level);
        var settlement=data.findContaining(template.anchor()).orElse(null);
        if (settlement==null || !settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");
        if (settlement.blueprintAt(template.anchor())!=null || settlement.buildingAt(template.anchor())!=null) return OperationResult.failure("ALREADY_REGISTERED");
        String reservationFailure=validateProjectionReservations(settlement,template);
        if(!reservationFailure.isEmpty())return OperationResult.failure(reservationFailure);
        releaseUnstartedDecorations(settlement,template);
        BlueprintRecord blueprint=settlement.addBlueprint(template.type(),template.anchor(),template.anchor(),template.facing(),actorId,
                false,1,clearances).withCustomTemplate(snapshot);
        com.hkin.exiledsettlement.terrain.P0PlanningService.placed(level,settlement,blueprint);
        data.touch(); if (player!=null) sendPlaced(player,blueprint,template); return OperationResult.success(blueprint);
    }

    /** Called after bounded server validation of a frozen client projection. */
    public static OperationResult<BlueprintRecord> placeCustom(ServerLevel level, UUID actorId,
            CustomTemplateSnapshot snapshot, @Nullable ServerPlayer player) {
        var template=snapshot.template(); var data=SettlementSavedData.get(level);
        var settlement=data.findContaining(template.anchor()).orElse(null);
        if (settlement==null || !settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");
        SiteCheck check=validateSite(level,settlement,template);
        if (!check.valid()) return OperationResult.failure(check.reason());
        releaseUnstartedDecorations(settlement,template);
        BlueprintRecord blueprint=settlement.addBlueprint(template.type(),template.anchor(),template.anchor(),template.facing(),
                actorId,false,1,check.clearances()).withCustomTemplate(snapshot);
        com.hkin.exiledsettlement.terrain.P0PlanningService.placed(level,settlement,blueprint);
        data.touch(); if (player!=null) sendPlaced(player,blueprint,template);
        return OperationResult.success(blueprint);
    }

    public static OperationResult<BlueprintRecord> placeAreaBlueprint(
            ServerLevel level, UUID actorId, BuildingType type, BlockPos first, BlockPos second,
            Direction facing, @Nullable ServerPlayer feedbackPlayer
    ) {
        return placeAreaBlueprint(level,actorId,type,first,second,facing,
                BlueprintTemplates.currentTemplateVersion(type),feedbackPlayer);
    }

    public static OperationResult<BlueprintRecord> placeAreaBlueprint(
            ServerLevel level, UUID actorId, BuildingType type, BlockPos first, BlockPos second,
            Direction facing, int templateVersion, @Nullable ServerPlayer feedbackPlayer
    ) {
        if (!BlueprintTemplates.flexibleProduction(type,templateVersion)
                || templateVersion>BlueprintTemplates.currentTemplateVersion(type)) {
            return OperationResult.failure("UNSUPPORTED_BLUEPRINT");
        }
        long width = Math.abs((long)first.getX() - second.getX()) + 1;
        long depth = Math.abs((long)first.getZ() - second.getZ()) + 1;
        int minimum = type.minimumAreaSize();
        if (width < minimum || depth < minimum) return OperationResult.failure("AREA_TOO_SMALL");
        if (width > MAX_DYNAMIC_AREA_BLOCKS || depth > MAX_DYNAMIC_AREA_BLOCKS
                || width * depth > MAX_DYNAMIC_AREA_BLOCKS) return OperationResult.failure("AREA_TOO_LARGE");
        // The first selected point fixes the floor. Both saved corners must retain that same plane.
        second = second.atY(first.getY());
        SettlementSavedData data = SettlementSavedData.get(level);
        BlockPos center = new BlockPos((int)(((long)first.getX() + second.getX()) / 2), first.getY(),
                (int)(((long)first.getZ() + second.getZ()) / 2));
        SettlementRecord settlement = data.findContaining(center).orElse(null);
        if (settlement == null || !settlement.contains(first) || !settlement.contains(second)) {
            return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        }
        if (!settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");
        BuildingBlueprintTemplate template = ConstructionWorkArea.template(
                BlueprintTemplates.createArea(type, first, second, facing, templateVersion));
        SiteCheck site = validateSite(level, settlement, template);
        if (!site.valid()) {
            sendSiteFailure(feedbackPlayer, site);
            return OperationResult.failure(site.reason());
        }
        releaseUnstartedDecorations(settlement,template);
        BlueprintRecord blueprint = settlement.addAreaBlueprint(type, template.anchor(), center, facing, actorId,
                false, first, second, templateVersion, site.clearances());
        com.hkin.exiledsettlement.terrain.P0PlanningService.placed(level,settlement,blueprint);
        data.touch();
        if (feedbackPlayer != null) sendPlaced(feedbackPlayer, blueprint, template);
        return OperationResult.success(blueprint);
    }

    /** Administrative recovery hook. Normal creative and survival placement is completed by workers. */
    public static OperationResult<BuildingRecord> confirm(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            @Nullable ServerPlayer player, boolean bypassMaterials
    ) {
        if (!BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) {
            return OperationResult.failure("BLUEPRINT_VERSION_OUTDATED");
        }
        UUID actorId = player == null ? blueprint.ownerId() : player.getUUID();
        if (!settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");
        if(!com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id()))
            return OperationResult.failure("TERRAIN_FOUNDATION_PENDING");

        SettlementSavedData data = SettlementSavedData.get(level);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        if (FarmEarthworkService.applies(blueprint)
                && (!FarmEarthworkService.ensure(level, blueprint).valid() || !blueprint.earthworkPlan().done()))
            return OperationResult.failure("FARM_EARTHWORK_PENDING");
        SiteCheck site = validateSite(level, settlement, template, blueprint);
        if (!site.valid()) {
            sendSiteFailure(player, site);
            return OperationResult.failure(site.reason());
        }
        Map<Item, Integer> requirements = template.requirements(level);
        if (!bypassMaterials) {
            if (player == null) return OperationResult.failure("MATERIALS_MISSING");
            Map<Item, Integer> missing = BlueprintMaterials.missing(level, settlement, player, requirements);
            if (!missing.isEmpty()) {
                sendMissing(player, missing);
                return OperationResult.failure("MATERIALS_MISSING");
            }
        }

        List<BlockSnapshot> snapshots = new ArrayList<>();
        for (BlueprintPlacement placement : template.placements()) {
            if (BlueprintPlacementGroups.isPaired(placement.state())) {
                if (!BlueprintPlacementGroups.isPrimary(placement.state())
                        || BlueprintPlacementGroups.satisfied(level, template, placement)) continue;
                var placed = BlueprintPlacementGroups.place(level, settlement, template, placement, player, false);
                if (!placed.success()) {
                    rollback(snapshots);
                    return OperationResult.failure(placed.reason());
                }
                snapshots.addAll(placed.value());
                continue;
            }
            if (placementSatisfied(level, template, placement)) continue;
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, placement.position(), Block.UPDATE_CLIENTS);
            snapshots.add(snapshot);
            if (!applyPlacement(level, placement)
                    || EventHooks.onBlockPlace(player, snapshot, Direction.UP)) {
                rollback(snapshots);
                return OperationResult.failure("PROTECTED_AREA");
            }
        }
        if (!bypassMaterials && player != null && !player.isCreative()) {
            BlueprintMaterials.consume(level, settlement, player, requirements);
        }
        return complete(level, data, settlement, blueprint, template, actorId, player, snapshots);
    }

    @Nullable
    public static PendingPlacement nextPending(
            ServerLevel level, SettlementSavedData data, BlueprintRecord blueprint
    ) {
        return nextUnclaimedPending(level, data, blueprint, Set.of(), blueprint.markerPosition());
    }

    @Nullable
    public static PendingPlacement nextUnclaimedPending(
            ServerLevel level, SettlementSavedData data, BlueprintRecord blueprint, Set<BlockPos> claimedPositions
    ) {
        return nextUnclaimedPending(level, data, blueprint, claimedPositions, blueprint.markerPosition());
    }

    public static boolean clearanceLayerAllowed(ServerLevel level,BlueprintRecord b,BlockPos at){
        if(b.terrainProject()!=null || b.region()!=null)return true;
        int highest=b.clearanceTargets().keySet().stream().filter(p->level.hasChunkAt(p) && !level.getBlockState(p).isAir() && level.getBlockState(p).getDestroySpeed(level,p)>=0).mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
        return highest==Integer.MIN_VALUE || b.hasClearanceTarget(at) && at.getY()==highest;
    }
    @Nullable
    public static PendingPlacement nextUnclaimedPending(
            ServerLevel level, SettlementSavedData data, BlueprintRecord blueprint,
            Set<BlockPos> claimedPositions, BlockPos workerPosition
    ) {
        if (blueprint.cancellationQueued()
                || !BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) return null;
        if(blueprint.terrainProject()!=null) {
            var work=com.hkin.exiledsettlement.terrain.TerrainService.pending(level,blueprint,claimedPositions);
            if(blueprint.terrainProject().pitFill)
                return work.stream().min(java.util.Comparator.comparingDouble(p->p.placement().position().distSqr(workerPosition))).orElse(null);
            return work.isEmpty()?null:work.getFirst();
        }
        if(TimedConstructionService.building(blueprint))return TimedConstructionService.first(level,blueprint,claimedPositions);
        if(blueprint.region()!=null)return regionPending(level,blueprint,claimedPositions,workerPosition).stream().findFirst().orElse(null);
        if (FarmEarthworkService.applies(blueprint)) {
            var earthwork = FarmEarthworkService.pending(level, blueprint, claimedPositions);
            if (!earthwork.isEmpty()) return earthwork.getFirst();
        }
        reconcileClearanceTargets(level, data, blueprint);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        // Progress is a cache, not authority. Temporary construction blocks, player edits,
        // or an older save can invalidate a position below the persisted index.
        int index = 0;
        while (index < template.placements().size()
                && !pendingGroup(level, blueprint, template, template.placements().get(index))) {
            index++;
        }
        if (blueprint.setProgressIndex(index)) data.touch();
        List<PendingPlacement> candidates = new ArrayList<>();
        for (int candidate = index; candidate < template.placements().size(); candidate++) {
            BlueprintPlacement placement = template.placements().get(candidate);
            if (pendingGroup(level, blueprint, template, placement)
                    && !BlueprintPlacementGroups.claimed(template, placement, claimedPositions)) {
                candidates.add(new PendingPlacement(candidate, placement));
            }
        }
        var next=candidates.stream().filter(p->clearanceLayerAllowed(level,blueprint,p.placement().position())).min(constructionOrder(level, blueprint, template, workerPosition)).orElse(null);
        if(next==null && FarmEarthworkService.applies(blueprint)) {
            BlockPos missing=FarmEarthworkPlan.missingSupport(level,template);
            if(missing!=null && !claimedPositions.contains(missing))
                return new PendingPlacement(-1,new FarmEarthworkPlan.Step(missing,false).placement());
        }
        return next;
    }

    @Nullable
    public static PendingPlacement pendingAt(
            ServerLevel level, BlueprintRecord blueprint, BlockPos position
    ) {
        if (blueprint.cancellationQueued()
                || !BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) return null;
        if(TimedConstructionService.building(blueprint)) {
            return TimedConstructionService.at(level,blueprint,position);
        }
        if(blueprint.terrainProject()==null && blueprint.region()==null)
            reconcileClearanceTargets(level, SettlementSavedData.get(level), blueprint);
        if(!clearanceLayerAllowed(level,blueprint,position))return null;
        if(blueprint.terrainProject()!=null) {
            return com.hkin.exiledsettlement.terrain.TerrainService.at(level,blueprint,position);
        }
        if (FarmEarthworkService.applies(blueprint)) {
            var earthwork = FarmEarthworkService.pending(level, blueprint, Set.of());
            if (!earthwork.isEmpty()) {
                for(var p:earthwork) if(p.placement().position().equals(position)) return p;
                return null;
            }
        }
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlueprintPlacement requested = template.placementAt(position);
        if (requested == null) return null;
        queueCurrentObstruction(level, blueprint, template, requested);
        if (BlueprintPlacementGroups.isPaired(requested.state()) && !blueprint.hasClearanceTarget(position)) {
            List<BlueprintPlacement> group = BlueprintPlacementGroups.parts(template, requested);
            if (!group.isEmpty()) position = group.getFirst().position();
        }
        List<BlueprintPlacement> placements = template.placements();
        for (int index = 0; index < placements.size(); index++) {
            BlueprintPlacement placement = placements.get(index);
            if (placement.position().equals(position) && pendingGroup(level, blueprint, template, placement)) {
                return new PendingPlacement(index, placement);
            }
        }
        return null;
    }

    public static List<PendingPlacement> pendingPlacements(
            ServerLevel level, BlueprintRecord blueprint, Set<BlockPos> excludedPositions
    ) {
        return pendingPlacements(level, blueprint, excludedPositions, blueprint.markerPosition());
    }

    public static List<PendingPlacement> pendingPlacements(
            ServerLevel level, BlueprintRecord blueprint, Set<BlockPos> excludedPositions, BlockPos workerPosition
    ) {
        if (blueprint.cancellationQueued()
                || !BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) return List.of();
        if(blueprint.terrainProject()!=null) {
            var work=com.hkin.exiledsettlement.terrain.TerrainService.pending(level,blueprint,excludedPositions);
            return blueprint.terrainProject().pitFill
                    ?work.stream().sorted(java.util.Comparator.comparingDouble(p->p.placement().position().distSqr(workerPosition))).toList():work;
        }
        if(TimedConstructionService.building(blueprint))return TimedConstructionService.pending(level,blueprint,excludedPositions);
        if(blueprint.region()!=null)return regionPending(level,blueprint,excludedPositions,workerPosition);
        if (FarmEarthworkService.applies(blueprint)) {
            var earthwork = FarmEarthworkService.pending(level, blueprint, excludedPositions);
            if (!earthwork.isEmpty()) return earthwork;
        }
        // All scheduling entry points must reconcile real final cells before
        // applying the common clearance layer gate. A stale occupied record
        // must not suppress lower obstructions that still need on-site work.
        reconcileClearanceTargets(level, SettlementSavedData.get(level), blueprint);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        List<BlueprintPlacement> placements = template.placements();
        List<PendingPlacement> pending = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            BlueprintPlacement placement = placements.get(index);
            if (!BlueprintPlacementGroups.claimed(template, placement, excludedPositions)
                    && pendingGroup(level, blueprint, template, placement)) {
                pending.add(new PendingPlacement(index, placement));
            }
        }
        pending.removeIf(p->!clearanceLayerAllowed(level,blueprint,p.placement().position()));
        pending.sort(constructionOrder(level, blueprint, template, workerPosition));
        return List.copyOf(pending);
    }

    private static List<PendingPlacement> regionPending(ServerLevel level,BlueprintRecord b,Set<BlockPos> excluded,BlockPos from) {
        if(!b.region().surveyed || b.region().groundPaused)return List.of();var t=BlueprintTemplates.create(b);var placements=t.placements();var result=new ArrayList<PendingPlacement>();
        // Always examine the entrance before the rotating crop scan. A large
        // field must not keep an old fence across its only usable entrance.
        for(var entrance:t.entrances())for(var at:List.of(entrance.position().below(),entrance.position())) {
            var p=t.placementAt(at);if(p==null || excluded.contains(at) || !pendingGroup(level,b,t,p))continue;
            result.add(new PendingPlacement(placements.indexOf(p),p));
        }
        for(int work=0;work<Math.min(512,placements.size());work++) {
            if(b.region().workCursor>=placements.size())b.region().workCursor=0;
            int i=b.region().workCursor++;var p=placements.get(i);
            if(!excluded.contains(p.position()) && result.stream().noneMatch(a->a.placement().position().equals(p.position())) && pendingGroup(level,b,t,p))result.add(new PendingPlacement(i,p));
            if(result.size()>=32)break;
        }
        result.sort(constructionOrder(level,b,t,from));return List.copyOf(result);
    }
    private static boolean pendingGroup(ServerLevel level, BlueprintRecord blueprint,
                                        BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        if(blueprint.region()!=null) {
            if(!com.hkin.exiledsettlement.region.RegionGroundService.goalAllowed(level,blueprint,placement.position()))return false;
            if(waterBarrierSatisfied(level,blueprint,placement)) {
                if(blueprint.removeClearanceTarget(placement.position()))SettlementSavedData.get(level).touch();
                return false;
            }
            var settlement=SettlementSavedData.get(level).settlements().stream().filter(s->s.blueprint(blueprint.id())==blueprint).findFirst().orElse(null);
            var current=level.getBlockState(placement.position());
            if(placement.state().is(Blocks.DIRT_PATH) && (!FarmEarthworkPlan.soil(current) && !current.is(Blocks.DIRT_PATH) || !FarmEarthworkPlan.support(level,placement.position().below()) || !level.getBlockState(placement.position().above()).isAir()))return false;
            if(placement.mode()==BlueprintPlacementMode.FARM_TILL && (!FarmEarthworkPlan.soil(current) || !FarmEarthworkPlan.support(level,placement.position().below())))return false;
            if(placement.mode()==BlueprintPlacementMode.FARM_TILL) {
                var above=level.getBlockState(placement.position().above());
                if(!above.isAir() && !com.hkin.exiledsettlement.citizen.ScaffoldSitePreparation.softObstacle(above))return false;
            }
            if(placement.material()==Items.WATER_BUCKET && !blueprint.region().legacyWater.contains(com.hkin.exiledsettlement.region.ProductionRegion.key(placement.position().getX(),placement.position().getZ()))
                    && (!current.is(net.minecraft.world.level.block.Blocks.OAK_SLAB) || current.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)!=net.minecraft.world.level.block.state.properties.SlabType.TOP || !FarmEarthworkPlan.support(level,placement.position().below())))return false;
            if(placement.material()==Items.WATER_BUCKET && !blueprint.region().legacyWater.contains(com.hkin.exiledsettlement.region.ProductionRegion.key(placement.position().getX(),placement.position().getZ()))) {
                var source=placement.state().getCollisionShape(level,placement.position());
                // Use the same combined-face test as vanilla FlowingFluid. The
                // slab's upper half and farmland's lower 15/16 jointly seal the
                // face; testing one shape against the other rejected safe water.
                for(var side:Direction.Plane.HORIZONTAL){var adjacent=placement.position().relative(side);if(!level.hasChunkAt(adjacent) || !net.minecraft.world.phys.shapes.Shapes.mergedFaceOccludes(source,level.getBlockState(adjacent).getCollisionShape(level,adjacent),side))return false;}
            }
            if(settlement!=null && (com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(level,settlement,placement.position()) || com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(level,settlement,placement.position().below())))return false;
        }
        if (blueprint.hasTemporaryFooting(placement.position())) return false;
        queueCurrentObstruction(level, blueprint, template, placement);
        if (blueprint.hasClearanceTarget(placement.position())) return true;
        if (!BlueprintPlacementGroups.isPaired(placement.state())) {
            return !placementSatisfied(level, blueprint, placement);
        }
        // A persisted target may be either half, but scheduling and material
        // reservation always use the canonical lower/foot cell.
        if (!BlueprintPlacementGroups.isPrimary(placement.state())) return false;
        return !BlueprintPlacementGroups.satisfied(level, template, placement);
    }

    /** Inspect only the template cells already visited by the work scheduler. */
    private static void queueCurrentObstruction(ServerLevel level, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        if (blueprint.cancellationQueued() || placement.mode() == BlueprintPlacementMode.CLEARANCE
                && !BuildingPlacementRules.requiresClearance(template, placement.position())) return;
        List<BlueprintPlacement> parts = BlueprintPlacementGroups.isPaired(placement.state())
                ? BlueprintPlacementGroups.parts(template, placement) : List.of(placement);
        for (BlueprintPlacement part : parts) {
            BlockPos pos = part.position();
            if (!level.hasChunkAt(pos) || blueprint.hasTemporaryFooting(pos)
                    || blueprint.hasClearanceTarget(pos) || placementSatisfied(level, blueprint, part)
                    || waitsForChestPartner(level, new PendingPlacement(0, part))) continue;
            if (part.mode() != BlueprintPlacementMode.CLEARANCE && part.mode() != BlueprintPlacementMode.STRUCTURE) continue;
            BlockState current = level.getBlockState(pos);
            if(part.material()==Items.WATER_BUCKET && current.getBlock()==part.state().getBlock() && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) && !current.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))continue;
            if(part.state().is(Blocks.DIRT_PATH) && canDirectlyTransformFarmGround(current))continue;
            if (isClearable(level, pos, current)
                    && blueprint.addClearanceTarget(pos, current)) SettlementSavedData.get(level).touch();
        }
    }

    private static Comparator<PendingPlacement> constructionOrder(
            ServerLevel level, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, BlockPos workerPosition
    ) {
        return Comparator
                // Clear the site first, then finish each physical layer before
                // moving higher. A global "all ordinary blocks, then every
                // functional block" stage sealed roofs before low beds and
                // containers were installed. Layer-first ordering is generic
                // geometry, not a route scripted for a particular template.
                .comparingInt((PendingPlacement pending) -> blueprint.region()!=null && template.entrances().stream()
                        .anyMatch(e->e.position().equals(pending.placement().position()) || e.position().below().equals(pending.placement().position())) ? 0 : 1)
                .thenComparingInt((PendingPlacement pending) ->
                        blueprint.hasClearanceTarget(pending.placement().position()) ? 0 : 1)
                // Only pending, actually contained water points reach this list.
                // Finish them before fences or tilling can keep a large field busy.
                .thenComparingInt(pending -> blueprint.region()!=null && pending.placement().material()==Items.WATER_BUCKET ? 0 : 1)
                .thenComparingInt(pending -> template.constructionPhase(pending.placement()))
                .thenComparingInt(pending -> blueprint.hasClearanceTarget(pending.placement().position())
                        || pending.placement().state().isAir() ? -pending.placement().position().getY() : pending.placement().position().getY())
                .thenComparingInt(pending -> constructionStageFor(pending.placement()))
                // The first half of a double chest temporarily becomes SINGLE
                // until its partner is installed. Do not keep selecting that
                // occupied block-entity cell and reporting BLUEPRINT_BLOCKED;
                // finish an unplaced partner on the same layer first, after
                // which the normal state resolver repairs both halves. This is
                // state-derived and applies to every template, not just the
                // player warehouse.
                .thenComparingInt(pending -> waitsForChestPartner(level, pending) ? 1 : 0)
                .thenComparingInt(pending -> constructionZone(template, pending.placement()))
                .thenComparingInt(pending -> entranceDepthOrder(template, pending.placement()))
                .thenComparingDouble(pending -> pending.placement().position().distSqr(workerPosition))
                .thenComparingInt(PendingPlacement::index);
    }

    public static boolean waitsForChestPartner(ServerLevel level, PendingPlacement pending) {
        BlueprintPlacement placement = pending.placement();
        BlockState expected = placement.state();
        if (!(expected.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return false;
        net.minecraft.world.level.block.state.properties.ChestType expectedType =
                expected.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
        if (expectedType == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return false;
        BlockState current = level.getBlockState(placement.position());
        if (current.getBlock() != expected.getBlock()
                || current.getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                != expected.getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                || current.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == expectedType) {
            return false;
        }
        Direction facing = expected.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
        Direction connected = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(expected);
        return level.getBlockState(placement.position().relative(connected)).getBlock()
                != expected.getBlock();
    }

    public static int constructionStageFor(BlueprintPlacement placement) {
        return switch (placement.mode()) {
            case CLEARANCE -> 0;
            case OPTIONAL_SUPPORT -> 1;
            case FARM_WATER -> 1;
            case FARM_TILL -> 2;
            case STRUCTURE -> placement.state().getBlock() instanceof DoorBlock
                    || placement.state().getBlock() instanceof FenceGateBlock
                    || placement.state().getBlock() instanceof TrapDoorBlock
                    || placement.state().getBlock() instanceof net.minecraft.world.level.block.LanternBlock
                    || placement.state().hasBlockEntity() ? 4 : 3;
        };
    }

    private static int constructionZone(
            BuildingBlueprintTemplate template, BlueprintPlacement placement
    ) {
        if (!placement.mode().isFinalStructure()
                || placement.position().getY() < template.anchor().getY()) return 0;
        BlockPos position = placement.position();
        boolean perimeter = position.getX() == template.minCorner().getX()
                || position.getX() == template.maxCorner().getX()
                || position.getZ() == template.minCorner().getZ()
                || position.getZ() == template.maxCorner().getZ();
        // Keep the interior route usable while a layer is being built. Structural
        // perimeter blocks are closed after supports/interior points on the same Y,
        // and doors remain in the final stage, so workers are less likely to wall
        // themselves out of the remaining plan.
        return perimeter ? 1 : 0;
    }

    private static int entranceDepthOrder(
            BuildingBlueprintTemplate template, BlueprintPlacement placement
    ) {
        if (!placement.mode().isFinalStructure()
                || placement.position().getY() < template.anchor().getY()) return 0;
        BlockPos offset = placement.position().subtract(template.anchor());
        int depth = offset.getX() * template.facing().getStepX()
                + offset.getZ() * template.facing().getStepZ();
        return -depth;
    }

    /** On-site timed placement retains resource charging and protection events, without physical construction gates. */
    public static OperationResult<BuildingRecord> placeTimedAt(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, BlueprintRecord blueprint, BlockPos position, ItemStack supplied, LivingEntity worker) {
        if(blueprint.cancellationQueued())return OperationResult.failure("CONSTRUCTION_CANCELLED");
        if(!com.hkin.exiledsettlement.region.RegionConstructionStages.facilitiesReady(settlement,blueprint))return OperationResult.failure("REGION_GROUND_NOT_READY");
        if(!(worker instanceof com.hkin.exiledsettlement.citizen.SettlementCitizenEntity actor)
                || !TimedConstructionService.arrived(level,blueprint,actor,position))return OperationResult.failure("CONSTRUCTION_SITE_ROUTE_PENDING");
        var template=BlueprintTemplates.create(blueprint);
        var pending=TimedConstructionService.at(level,blueprint,position);
        if(pending==null)return finishTimedIfReady(level,data,settlement,blueprint);
        var placement=pending.placement();
        var parts=BlueprintPlacementGroups.parts(template,placement);
        if(parts.isEmpty())return OperationResult.failure("BLUEPRINT_INVALID_PAIR");
        for(var part:parts)if(!settlement.contains(part.position()) || BuildingWorkPolicy.automaticForbidden(settlement,blueprint,part.position())
                || BuildingWorkPolicy.suppressed(settlement,blueprint,part.position()))return OperationResult.failure("PROTECTED_AREA");
        if(placement.material()!=null && (supplied.isEmpty() || !supplied.is(placement.material())))return OperationResult.failure("MATERIALS_MISSING");
        if(placement.mode()==BlueprintPlacementMode.CLEARANCE) {
            var result=com.hkin.exiledsettlement.citizen.ResidentBlockDrops.run(level,worker,()-> {
                var cleared=SiteClearance.clear(level,position,worker,worker.getMainHandItem(),part ->
                        settlement.contains(part) && !reservedByOther(level,settlement,blueprint,part)
                        && !BuildingWorkPolicy.automaticForbidden(settlement,blueprint,part)
                        && !BuildingWorkPolicy.suppressed(settlement,blueprint,part)
                        && !blueprint.hasTemporaryFooting(part)
                        && (part.equals(position) || template.placementAt(part)==null
                            || !template.placementAt(part).mode().isFinalStructure()
                            || !placementSatisfied(level,template,template.placementAt(part))));
                if(cleared.failure().isEmpty())cleared.drops().forEach(drop->net.minecraft.world.level.block.Block.popResource(level,position,drop));
                return cleared;
            },cleared->cleared.failure().isEmpty());
            if(!result.failure().isEmpty())return OperationResult.failure(result.failure());
            result.removed().forEach(blueprint::removeClearanceTarget);
        } else if(placement.mode()==BlueprintPlacementMode.FARM_TILL && FarmConstructionSequence.tillGoal(template,placement)) {
            if(!FarmConstructionSequence.till(level,placement,worker))return OperationResult.failure("PROTECTED_AREA");
        } else if(IrrigationConstructionService.waterlogging(template,placement)) {
            if(!IrrigationConstructionService.placeWater(level,template,placement,worker))return OperationResult.failure("PROTECTED_AREA");
        } else if(BlueprintPlacementGroups.isPaired(placement.state())) {
            var result=BlueprintPlacementGroups.placeTimed(level,settlement,template,placement,worker);
            if(!result.success())return OperationResult.failure(result.reason());
        } else if(!TimedConstructionService.put(level,position,BlueprintBlockStateResolver.resolve(level,placement),worker))
            return OperationResult.failure("PROTECTED_AREA");
        if(placement.material()!=null)supplied.shrink(1);
        IrrigationConstructionService.returnBucket(level,template,placement,worker);
        for(var part:parts) {blueprint.removeClearanceTarget(part.position());blueprint.removeTemporaryFooting(part.position());}
        data.touch();
        return TimedConstructionService.first(level,blueprint,Set.of())==null
                ? finishTimedIfReady(level,data,settlement,blueprint) : OperationResult.success(null);
    }

    public static OperationResult<BuildingRecord> finishTimedIfReady(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, BlueprintRecord blueprint) {
        reconcileClearanceTargets(level,data,blueprint);
        if(!com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id()))
            return OperationResult.failure("TERRAIN_FOUNDATION_PENDING");
        if(blueprint.cancellationQueued() || TimedConstructionService.first(level,blueprint,Set.of())!=null)
            return OperationResult.failure("CONSTRUCTION_INCOMPLETE");
        var template=BlueprintTemplates.create(blueprint);
        for(var placement:template.placements())if(!level.hasChunkAt(placement.position()) || !placementSatisfied(level,template,placement))
            return OperationResult.failure("CONSTRUCTION_INCOMPLETE");
        for(var at:blueprint.clearanceTargets().keySet())if(!level.hasChunkAt(at) || !level.getBlockState(at).isAir())
            return OperationResult.failure("CONSTRUCTION_INCOMPLETE");
        if(blueprint.region()!=null && !constructionComplete(level,blueprint))return OperationResult.success(null);
        return complete(level,data,settlement,blueprint,template,blueprint.ownerId(),null,new ArrayList<>(),true);
    }

    /**
     * Places one construction step. A material-bearing step requires one matching item in suppliedMaterial.
     * The stack is shrunk only after the world change succeeds.
     */
    public static OperationResult<BuildingRecord> placeNext(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, ItemStack suppliedMaterial
    ) {
        if (blueprint.cancellationQueued()) return OperationResult.failure("CONSTRUCTION_CANCELLED");
        PendingPlacement pending = nextPending(level, data, blueprint);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        if (pending == null) {
            if (!blueprint.temporaryFootings().isEmpty()) return OperationResult.success(null);
            return complete(level, data, settlement, blueprint, template, blueprint.ownerId(), null, new ArrayList<>());
        }
        return placeAt(level, data, settlement, blueprint, pending.placement().position(), suppliedMaterial);
    }

    public static OperationResult<BuildingRecord> placeAt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, BlockPos position, ItemStack suppliedMaterial
    ) {
        return placeAt(level, data, settlement, blueprint, position, suppliedMaterial, null);
    }

    public static OperationResult<BuildingRecord> placeAt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, BlockPos position, ItemStack suppliedMaterial,
            @Nullable net.minecraft.world.entity.Entity worker
    ) {
        if (blueprint.cancellationQueued()) return OperationResult.failure("CONSTRUCTION_CANCELLED");
        PendingPlacement pending = pendingAt(level, blueprint, position);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        if (pending == null) return OperationResult.failure("CONSTRUCTION_TARGET_CHANGED");
        if (pending.index()<0) return OperationResult.failure("FARM_EARTHWORK_PENDING");
        if (blueprint.hasClearanceTarget(position)) return OperationResult.failure("CLEARANCE_REQUIRED");
        if(com.hkin.exiledsettlement.terrain.TerrainService.needsFoundation(settlement,blueprint)
                && !com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id()))
            return OperationResult.failure("TERRAIN_FOUNDATION_PENDING");
        BlueprintPlacement placement = pending.placement();
        if(worker!=null && ConstructionOccupancy.occupied(level,template,placement,worker))
            return OperationResult.failure(ConstructionOccupancy.FAILURE);
        if(blueprint.region()!=null && blueprint.region().plainSoil() && !com.hkin.exiledsettlement.region.RegionGroundService.goalAllowed(level,blueprint,position))return OperationResult.failure("REGION_GROUND_NOT_READY");
        if(FarmEarthworkService.applies(blueprint)) {
            BlockPos ground=new BlockPos(position.getX(),blueprint.anchor().getY()-1,position.getZ());
            if(!FarmEarthworkPlan.support(level,ground.below()))
                return OperationResult.failure(FarmEarthworkService.detail(level,ground.below(),"FARM_EARTHWORK_NO_SUPPORT"));
            if(placement.mode()==BlueprintPlacementMode.FARM_TILL && !FarmEarthworkPlan.soil(level.getBlockState(ground)))
                return OperationResult.failure("FARM_EARTHWORK_PENDING");
        }
        if (worker != null && ConstructionDependencies.firstUnfinished(level, template, placement) != null) {
            return OperationResult.failure("CONSTRUCTION_PREREQUISITE_PENDING");
        }
        if (worker != null && ConstructionClosureGuard.prerequisite(level, template, placement) != null) {
            return OperationResult.failure("CONSTRUCTION_WOULD_SEAL_GAP");
        }
        if (placement.material() != null
                && (suppliedMaterial.isEmpty() || !suppliedMaterial.is(placement.material()))) {
            return OperationResult.failure("MATERIALS_MISSING");
        }
        List<BlockSnapshot> snapshots;
        if (BlueprintPlacementGroups.isPaired(placement.state())) {
            if (BlueprintPlacementGroups.parts(template, placement).stream().anyMatch(part ->
                    blueprint.hasClearanceTarget(part.position()) || blueprint.hasTemporaryFooting(part.position()))) {
                return OperationResult.failure("CLEARANCE_REQUIRED");
            }
            var result = BlueprintPlacementGroups.place(level, settlement, template, placement, worker, false);
            if (!result.success()) return OperationResult.failure(result.reason());
            snapshots = result.value();
        } else {
            SiteCheck check = validatePlacement(level, settlement, template, placement);
            if (!check.valid()) return OperationResult.failure(check.reason());
            BlockSnapshot snapshot = BlockSnapshot.create(
                    level.dimension(), level, placement.position(), Block.UPDATE_CLIENTS);
            if (!applyPlacement(level, placement) || EventHooks.onBlockPlace(worker, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_CLIENTS);
                return OperationResult.failure("PROTECTED_AREA");
            }
            snapshots = new ArrayList<>(List.of(snapshot));
        }
        if (placement.material() != null) suppliedMaterial.shrink(1);
        if(blueprint.region()!=null && placement.mode()==BlueprintPlacementMode.FARM_TILL)
            blueprint.region().cultivated.add(placement.position().asLong());
        if(blueprint.region()!=null && placement.state().is(Blocks.DIRT_PATH)) {
            com.hkin.exiledsettlement.road.RoadSpaceData.get(level).addAll(List.of(placement.position()),com.hkin.exiledsettlement.road.RoadTier.LEVEL_1);
            var resident=settlement.citizens().stream().filter(c->worker!=null && worker.getUUID().equals(c.entityId())).findFirst().orElse(null);
            if(resident!=null)com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,resident,"farm_path",placement.position(),"minecraft:dirt_path",1);
        }
        if (blueprint.region()!=null && placement.material()==Items.WATER_BUCKET) {
            var resident=settlement.citizens().stream().filter(c->worker!=null && worker.getUUID().equals(c.entityId())).findFirst().orElse(null);
            if(resident!=null)com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,resident,"farm_irrigate",
                    placement.position(),"minecraft:water_bucket",1);
        }
        com.hkin.exiledsettlement.terrain.PublicWorkService.changed(level,blueprint,pending.index());data.touch();
        PendingPlacement remaining = nextPending(level, data, blueprint);
        if (remaining == null) {
            if(blueprint.region()!=null && !constructionComplete(level,blueprint)) {
                IrrigationConstructionService.returnBucket(level,template,placement,worker);return OperationResult.success(null);
            }
            if (!blueprint.temporaryFootings().isEmpty()) {IrrigationConstructionService.returnBucket(level,template,placement,worker);return OperationResult.success(null);}
            var result = complete(level, data, settlement, blueprint, template, blueprint.ownerId(), null, snapshots);
            if (!result.success() && placement.material() != null) suppliedMaterial.grow(1);
            if(result.success())IrrigationConstructionService.returnBucket(level,template,placement,worker);
            return result;
        }
        IrrigationConstructionService.returnBucket(level,template,placement,worker);
        return OperationResult.success(null);
    }

    public static OperationResult<List<ItemStack>> clearAt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, BlockPos position, LivingEntity worker, ItemStack tool
    ) {
        if (blueprint.cancellationQueued()) return OperationResult.failure("CONSTRUCTION_CANCELLED");
        if (blueprint.hasTemporaryFooting(position)) return OperationResult.failure("CONSTRUCTION_TARGET_CHANGED");
        if (!blueprint.hasClearanceTarget(position)) return OperationResult.failure("CONSTRUCTION_TARGET_CHANGED");
        BlueprintPlacement finalPlacement = BlueprintTemplates.create(blueprint).placementAt(position);
        if (finalPlacement == null) return OperationResult.failure("CONSTRUCTION_TARGET_CHANGED");
        if (finalPlacement.mode()==BlueprintPlacementMode.CLEARANCE
                && !BuildingPlacementRules.requiresClearance(BlueprintTemplates.create(blueprint),position)) {
            blueprint.removeClearanceTarget(position); data.touch(); return OperationResult.success(List.of());
        }
        if (!settlement.contains(position) || !level.hasChunkAt(position)) {
            return OperationResult.failure("BLUEPRINT_AREA_NOT_LOADED");
        }
        if(blueprint.region()!=null && blueprint.region().plainSoil() && !com.hkin.exiledsettlement.region.RegionGroundService.goalAllowed(level,blueprint,position))return OperationResult.failure("ANIMAL_GROUND_OCCUPIED");
        BlockState current = level.getBlockState(position);
        if (current.isAir() || finalPlacement.mode() != BlueprintPlacementMode.CLEARANCE
                && placementSatisfied(level, finalPlacement)) {
            blueprint.removeClearanceTarget(position);
            data.touch();
            return OperationResult.success(List.of());
        }
        if (!isClearable(level, position, current)) {
            return OperationResult.failure("PROTECTED_AREA");
        }
        var template = BlueprintTemplates.create(blueprint);
        var cleared = SiteClearance.clear(level, position, worker, tool, part -> {
            if (!settlement.contains(part) || blueprint.hasTemporaryFooting(part)
                    || reservedByOther(level,settlement,blueprint,part)) return false;
            if (part.equals(position)) return isClearable(level, part, level.getBlockState(part));
            var finalPart = template.placementAt(part);
            if (finalPart != null && finalPart.mode().isFinalStructure()
                    && placementSatisfied(level, finalPart)) return false;
            return isClearable(level, part, level.getBlockState(part));
        });
        if (!cleared.failure().isEmpty()) return OperationResult.failure(cleared.failure());
        cleared.removed().forEach(blueprint::removeClearanceTarget);
        data.touch();
        return OperationResult.success(cleared.drops());
    }

    public static boolean constructionComplete(ServerLevel level, BlueprintRecord blueprint) {
        if(blueprint.region()!=null) {
            var settlement=SettlementSavedData.get(level).settlements().stream().filter(x->x.blueprint(blueprint.id())==blueprint).findFirst().orElse(null);
            if(!com.hkin.exiledsettlement.region.PastureGroundSafety.finished(blueprint.region()) || !blueprint.region().surveyed || !blueprint.region().layoutReady || !blueprint.region().prepared || !blueprint.region().preparationRetries.isEmpty() || settlement==null || !com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id()) || com.hkin.exiledsettlement.terrain.PublicWorkService.remaining(level,blueprint)>0)return false;
            var placements=BlueprintTemplates.create(blueprint).placements();
            for(int i=0;i<placements.size();i++)if(!placementSatisfied(level,blueprint,placements.get(i))){com.hkin.exiledsettlement.terrain.PublicWorkService.changed(level,blueprint,i);return false;}
            return true;
        }
        if(blueprint.terrainProject()!=null)return !blueprint.cancellationQueued() && com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(level,blueprint.terrainProject());
        var settlement=SettlementSavedData.get(level).findContaining(blueprint.anchor()).orElse(null);
        if(settlement!=null && !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint))return false;
        if (blueprint.cancellationQueued() || !blueprint.clearanceTargets().isEmpty()) return false;
        if (FarmEarthworkService.applies(blueprint)
                && (blueprint.earthworkPlan()==null || !blueprint.earthworkPlan().done())) return false;
        if(FarmEarthworkService.applies(blueprint) && FarmEarthworkPlan.missingSupport(level,BlueprintTemplates.create(blueprint))!=null) return false;
        for (BlueprintPlacement placement : BlueprintTemplates.create(blueprint).placements()) {
            if (!placementSatisfied(level, blueprint, placement)) return false;
        }
        return true;
    }

    /** Actual satisfied world steps, used by management UI instead of the non-linear scheduling cursor. */
    public static int completedPlacementCount(ServerLevel level, BlueprintRecord blueprint) {
        if(blueprint.terrainProject()!=null)return blueprint.terrainProject().cursor;
        if(blueprint.region()!=null)return Math.max(0,BlueprintTemplates.create(blueprint).placements().size()-com.hkin.exiledsettlement.terrain.PublicWorkService.remaining(level,blueprint));
        int completed = 0;
        for (BlueprintPlacement placement : BlueprintTemplates.create(blueprint).placements()) {
            if (placementSatisfied(level, blueprint, placement)) completed++;
        }
        return completed;
    }

    /**
     * Returns true once every remaining mismatch is a registered temporary footing.
     * This lets old saves remove an internal footing before resuming the real structure.
     */
    public static boolean readyForFootingCleanup(ServerLevel level, BlueprintRecord blueprint) {
        if (blueprint.cancellationQueued() || blueprint.temporaryFootings().isEmpty()) return false;
        for (BlueprintPlacement placement : BlueprintTemplates.create(blueprint).placements()) {
            if (blueprint.hasTemporaryFooting(placement.position())) continue;
            if (!placementSatisfied(level, blueprint, placement)) return false;
        }
        return true;
    }

    public static OperationResult<BuildingRecord> completeAfterFootingCleanup(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BlueprintRecord blueprint
    ) {
        if (blueprint.cancellationQueued()) return OperationResult.failure("CONSTRUCTION_CANCELLED");
        if (!blueprint.temporaryFootings().isEmpty() || !constructionComplete(level, blueprint)) {
            return OperationResult.failure("CONSTRUCTION_INCOMPLETE");
        }
        return complete(level, data, settlement, blueprint, BlueprintTemplates.create(blueprint),
                blueprint.ownerId(), null, new ArrayList<>());
    }

    /**
     * Advances one real blueprint step without a resident, material, route, rest, or reach check; building ownership remains protected.
     * This method is only called by the explicit administrator debug mode and is not normal construction AI.
     */
    public static DebugAdvanceResult debugAdvanceOne(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BlueprintRecord blueprint
    ) {
        if (!com.hkin.exiledsettlement.debug.ConstructionDebugService.isEnabled(level,settlement.id())
                && !com.hkin.exiledsettlement.debug.TerrainEvolveDebugService.authorizes(level,settlement,blueprint)) return DebugAdvanceResult.idle("DEBUG_DISABLED");
        if (blueprint.cancellationQueued()) return DebugAdvanceResult.idle("CONSTRUCTION_CANCELLED");
        if(blueprint.terrainProject()!=null)
            return com.hkin.exiledsettlement.terrain.TerrainService.debugAdvanceOne(level,settlement,blueprint);
        if(FarmEarthworkService.applies(blueprint)) {
            var survey=FarmEarthworkService.ensure(level,blueprint);
            if(!survey.valid())return DebugAdvanceResult.idle(survey.reason());
            var earthwork=survey.plan();
            if(!earthwork.done()) {
                var step=earthwork.next();var placement=step.placement();
                if(!FarmEarthworkService.allowed(settlement,blueprint,BlueprintTemplates.create(blueprint),step.position()))
                    return DebugAdvanceResult.idle("PROTECTED_AREA");
                if(!com.hkin.exiledsettlement.debug.ConstructionForceWork.putTerrain(level,settlement,blueprint,placement.position(),placement.state()))
                    return DebugAdvanceResult.idle("DEBUG_WORLD_CHANGE_FAILED");
                earthwork.advance();data.touch();return DebugAdvanceResult.advanced(false,"DEBUG_FARM_EARTHWORK");
            }
        }
        if(com.hkin.exiledsettlement.terrain.TerrainService.needsFoundation(settlement,blueprint)
                && (!settlement.terrain().surveyed.contains(blueprint.id()) || !FoundationFootprint.legacySurveyInitialized(blueprint))) {
            String survey=com.hkin.exiledsettlement.terrain.TerrainService.plan(level,settlement,BlueprintTemplates.create(blueprint),blueprint.id(),"调试施工地基勘察",false);
            if(!survey.isEmpty())return DebugAdvanceResult.idle(survey);
            settlement.terrain().surveyed.add(blueprint.id());settlement.terrain().blockers.remove(blueprint.id());data.touch();
        }
        if(!com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id()))
            return DebugAdvanceResult.idle("TERRAIN_FOUNDATION_PENDING");
        if (!BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) {
            return DebugAdvanceResult.idle("BLUEPRINT_VERSION_OUTDATED");
        }

        reconcileClearanceTargets(level, data, blueprint);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        List<PendingPlacement> pending = new ArrayList<>();
        for (int index = 0; index < template.placements().size(); index++) {
            BlueprintPlacement placement = template.placements().get(index);
            if (BlueprintPlacementGroups.isPaired(placement.state())
                    && !BlueprintPlacementGroups.isPrimary(placement.state())) continue;
            if (blueprint.hasTemporaryFooting(placement.position())
                    || (BlueprintPlacementGroups.isPaired(placement.state())
                    ? !BlueprintPlacementGroups.satisfied(level, template, placement)
                    : !placementSatisfied(level, template, placement))) {
                pending.add(new PendingPlacement(index, placement));
            }
        }
        PendingPlacement next = pending.stream()
                .filter(work->debugStructureGuard(level,settlement,blueprint,template,work.placement()).isEmpty())
                .min(constructionOrder(level, blueprint, template, blueprint.markerPosition()))
                .orElse(null);
        if(next==null && !pending.isEmpty())return DebugAdvanceResult.idle("PROTECTED_AREA");
        if (next != null) {
            BlockPos position = next.placement().position();
            level.getChunkAt(position);
            if (BlueprintPlacementGroups.isPaired(next.placement().state())) {
                var result = BlueprintPlacementGroups.place(level, settlement, template, next.placement(), null, true);
                if (!result.success()) return DebugAdvanceResult.idle(result.reason());
                for (BlueprintPlacement part : BlueprintPlacementGroups.parts(template, next.placement())) {
                    blueprint.removeTemporaryFooting(part.position());
                    blueprint.removeClearanceTarget(part.position());
                }
            } else if (!com.hkin.exiledsettlement.debug.ConstructionForceWork.putTerrain(level,settlement,blueprint,position,
                    BlueprintBlockStateResolver.resolve(level,next.placement()))) {
                return DebugAdvanceResult.idle("DEBUG_WORLD_CHANGE_FAILED");
            }
            blueprint.removeTemporaryFooting(position);
            blueprint.removeClearanceTarget(position);
            data.touch();
            OperationResult<BuildingRecord> completed = finishDebugConstructionIfReady(
                    level, data, settlement, blueprint, template);
            if (completed != null && !completed.success()) {
                return DebugAdvanceResult.advanced(false, completed.reason());
            }
            return DebugAdvanceResult.advanced(completed != null, "DEBUG_PLACEMENT");
        }

        // Defensive recovery for an old or malformed save whose clearance map
        // contains a position no longer represented by the template. Normal
        // blueprints replace a clearance cell with its final block in the same
        // forced step above, so clearing never consumes a second 10-tick slot.
        BlockPos clearance = blueprint.clearanceTargets().keySet().stream()
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .findFirst().orElse(null);
        if (clearance != null) {
            if(!debugStructureGuard(level,settlement,blueprint,template,new BlueprintPlacement(clearance,Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)).isEmpty())
                return DebugAdvanceResult.idle("PROTECTED_AREA");
            if (level.isOutsideBuildHeight(clearance)) {
                return DebugAdvanceResult.idle("BLUEPRINT_AREA_NOT_LOADED");
            }
            level.getChunkAt(clearance);
            if (!level.getBlockState(clearance).isAir()
                    && !com.hkin.exiledsettlement.debug.ConstructionForceWork.putTerrain(level,settlement,blueprint,clearance,Blocks.AIR.defaultBlockState())) {
                return DebugAdvanceResult.idle("DEBUG_WORLD_CHANGE_FAILED");
            }
            blueprint.removeClearanceTarget(clearance);
            data.touch();
            return DebugAdvanceResult.advanced(false, "DEBUG_ORPHAN_CLEARANCE");
        }

        OperationResult<BuildingRecord> completed = finishDebugConstructionIfReady(
                level, data, settlement, blueprint, template);
        if (completed == null) return DebugAdvanceResult.idle("CONSTRUCTION_INCOMPLETE");
        return completed.success()
                ? DebugAdvanceResult.completed("DEBUG_COMPLETED")
                : DebugAdvanceResult.idle(completed.reason());
    }

    @Nullable
    private static OperationResult<BuildingRecord> finishDebugConstructionIfReady(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, BuildingBlueprintTemplate template
    ) {
        if (!blueprint.clearanceTargets().isEmpty()) return null;
        for (BlueprintPlacement placement : template.placements()) {
            if (blueprint.hasTemporaryFooting(placement.position())
                    || !placementSatisfied(level, template, placement)) return null;
        }
        boolean removedFooting = false;
        List<Map.Entry<BlockPos, Item>> footings = blueprint.temporaryFootings().entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<BlockPos, Item>>comparingInt(entry -> entry.getKey().getY()).reversed()
                        .thenComparing(entry -> blueprint.temporaryFootingOrder(entry.getKey()),
                                Comparator.reverseOrder()))
                .toList();
        for (Map.Entry<BlockPos, Item> footing : footings) {
            BlockPos position = footing.getKey();
            if(!debugStructureGuard(level,settlement,blueprint,template,new BlueprintPlacement(position,Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)).isEmpty())
                return OperationResult.failure("PROTECTED_AREA");
            if (level.getBlockState(position).is(Block.byItem(footing.getValue()))) {
                if(!com.hkin.exiledsettlement.debug.ConstructionForceWork.putTerrain(level,settlement,blueprint,position,Blocks.AIR.defaultBlockState()))
                    return OperationResult.failure("DEBUG_WORLD_CHANGE_FAILED");
            }
            removedFooting |= blueprint.removeTemporaryFooting(position);
        }
        if (removedFooting) data.touch();
        return completeAfterFootingCleanup(level, data, settlement, blueprint);
    }

    /** Debug bypasses work simulation, never another building's ownership or a revoked order. */
    private static String debugStructureGuard(ServerLevel level,SettlementRecord s,BlueprintRecord own,
            BuildingBlueprintTemplate template,BlueprintPlacement placement) {
        var project=own.terrainProject();
        if(project!=null && project.cancelled || !com.hkin.exiledsettlement.region.FarmConstructionPolicy.workerMayBuild(s,own))return "PROTECTED_AREA";
        var parts=BlueprintPlacementGroups.isPaired(placement.state())?BlueprintPlacementGroups.parts(template,placement):List.of(placement);
        for(var part:parts) {
            var at=part.position();
            if(!level.hasChunkAt(at) || level.isOutsideBuildHeight(at))return "BLUEPRINT_AREA_NOT_LOADED";
            if(com.hkin.exiledsettlement.debug.TerrainEvolveDebugService.authorizes(level,s,own)
                    && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(at).inflate(1),e->e.isAlive()&&!e.isSpectator()).isEmpty())return "OCCUPIED";
            if(!part.state().getCollisionShape(level,at).isEmpty() && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(at)).isEmpty())return "OCCUPIED";
            if(!s.contains(at) || at.equals(s.corePosition()) || BuildingWorkPolicy.automaticForbidden(s,own,at) || s.community().danger(at)
                    || com.hkin.exiledsettlement.terrain.TerrainService.protectedPublicColumn(s,project,at))return "PROTECTED_AREA";
            for(var b:s.buildings())if(b.active() && !(own.region()!=null && (b.id().equals(own.region().workingBuilding)))) {
                if(b.contains(at) || buildingLayout(b).reserves(at))return "PROTECTED_AREA";
            }
            for(var other:s.formalBlueprints())if(other!=own && !other.cancellationQueued()) {
                var target=BlueprintTemplates.create(other).placementAt(at);
                if(target!=null && target.mode().isFinalStructure() && !target.state().isAir())return "PROTECTED_AREA";
            }
        }
        return "";
    }

    public static void showRequirements(ServerPlayer player, SettlementRecord settlement, BlueprintRecord blueprint) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        sendPlaced(player, blueprint, template);
        Map<Item, Integer> missing = BlueprintMaterials.missing(
                player.serverLevel(), settlement, player, template.requirements(player.serverLevel()));
        if (!missing.isEmpty()) sendMissing(player, missing);
    }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            for (BlueprintRecord blueprint : List.copyOf(settlement.blueprints())) {
                if(!com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),blueprint.id(),PREVIEW_INTERVAL_TICKS,7))continue;
                if (!level.hasChunkAt(blueprint.anchor())) continue;
                reconcileCompletedBlueprint(level, data, settlement, blueprint);
                if (settlement.blueprint(blueprint.id()) == null) continue;
                if(blueprint.cancellationQueued())continue;
                if(blueprint.region()!=null || blueprint.terrainProject()!=null){
                    for(var p:constructionMarkers(level,blueprint))particle(level,p.getX(),p.getY(),p.getZ());
                }else showFootprint(level, BlueprintTemplates.create(blueprint));
            }
        }
    }

    private static void reconcileCompletedBlueprint(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BlueprintRecord blueprint
    ) {
        if (blueprint.cancellationQueued()
                || !BlueprintRecord.supportsStructureVersion(blueprint.structureVersion())) return;
        if (!blueprint.temporaryFootings().isEmpty() && readyForFootingCleanup(level, blueprint)) {
            int previousFootingCount = blueprint.temporaryFootings().size();
            BlueprintCancellationService.nextCleanup(level, blueprint, Set.of());
            if (blueprint.temporaryFootings().size() != previousFootingCount) data.touch();
        }
        if (blueprint.temporaryFootings().isEmpty() && constructionComplete(level, blueprint)) {
            completeAfterFootingCleanup(level, data, settlement, blueprint);
        }
    }

    public static ItemStack blueprintItem(BuildingType type) {
        return switch (type) {
            case HOUSE -> new ItemStack(ModItems.HOUSE_BLUEPRINT.get());
            case WAREHOUSE -> new ItemStack(ModItems.WAREHOUSE_BLUEPRINT.get());
            default -> ItemStack.EMPTY;
        };
    }

    private static OperationResult<BuildingRecord> complete(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, UUID actorId, @Nullable ServerPlayer player,
            List<BlockSnapshot> snapshots
    ) {
        return complete(level,data,settlement,blueprint,template,actorId,player,snapshots,false);
    }

    private static OperationResult<BuildingRecord> complete(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, UUID actorId, @Nullable ServerPlayer player,
            List<BlockSnapshot> snapshots, boolean timed
    ) {
        if(!timed && blueprint.region()!=null && !constructionComplete(level,blueprint)){rollback(snapshots);return OperationResult.failure("CONSTRUCTION_IN_PROGRESS");}
        if(blueprint.terrainProject()==null && !com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,blueprint.id())){rollback(snapshots);return OperationResult.failure("TERRAIN_FOUNDATION_PENDING");}
        if(blueprint.region()!=null && blueprint.region().workingBuilding!=null)
            return com.hkin.exiledsettlement.region.ProductionRegionService.completeMaintenance(level,data,settlement,blueprint);
        if(blueprint.terrainProject()!=null) {com.hkin.exiledsettlement.terrain.TerrainService.completed(level,settlement,blueprint);data.touch();return OperationResult.success(null);}
        if(!timed && FarmEarthworkService.applies(blueprint)) {
            String failure=FarmEarthworkService.prepareTarget(level,settlement,blueprint,null);
            if(!failure.isEmpty() || blueprint.earthworkPlan()==null || !blueprint.earthworkPlan().done()) {
                rollback(snapshots);
                return OperationResult.failure(failure.isEmpty()?"FARM_EARTHWORK_PENDING":failure);
            }
        }
        OperationResult<BuildingRecord> registration = data.registerBuilding(
                template.registrationPosition(), blueprint.type(), actorId, template.facing(),
                template.minCorner(), template.maxCorner(), template.transitionMargin(),
                blueprint.templateVersion());
        if (!registration.success() || registration.value() == null) {
            rollback(snapshots);
            return OperationResult.failure(registration.reason());
        }
        if (blueprint.customTemplate() != null) {
            registration.value().withCustomTemplate(blueprint.customTemplate());
            registration.value().rename(blueprint.customTemplate().name());
        }
        if(blueprint.region()!=null){registration.value().withRegion(blueprint.region());}
        if(blueprint.productionSite()!=null){registration.value().withProductionSite(blueprint.productionSite());}
        for(var project:settlement.terrain().projects.values())if(project.dependencies.contains(blueprint.id()))project.dependencies.add(registration.value().id());
        com.hkin.exiledsettlement.road.AutoRoadService.transfer(settlement,blueprint.id(),registration.value().id());
        settlement.removeBlueprint(blueprint.markerPosition());
        data.touch();
        for (BlueprintPlacement placement : template.placements()) {
            level.updateNeighborsAt(placement.position(), placement.state().getBlock());
        }
        BuildingFunctionService.validateSingle(level, data, settlement, registration.value());
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.completed",
                    Component.translatable(blueprint.type().translationKey())).withStyle(ChatFormatting.GREEN), false);
        }
        return registration;
    }

    public record PlacementCheck(boolean valid,String reason,BlockPos position,int clearances) { }
    public static PlacementCheck previewSite(ServerLevel level,SettlementRecord settlement,BuildingBlueprintTemplate template) {
        SiteCheck check=validateSite(level,settlement,template);
        return new PlacementCheck(check.valid(),check.reason(),check.position(),check.clearances().size());
    }

    private static SiteCheck validateSite(ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate template) {
        return validateSite(level,settlement,template,null);
    }

    private static SiteCheck validateSite(ServerLevel level, SettlementRecord settlement,
            BuildingBlueprintTemplate template,@Nullable BlueprintRecord own) {
        Map<BlockPos, BlockState> clearances = new LinkedHashMap<>();
        if (settlement.buildingAt(template.registrationPosition()) != null
                || settlement.blueprintAt(template.anchor()) != null && settlement.blueprintAt(template.anchor()) != own) {
            return SiteCheck.blocked("ALREADY_REGISTERED", template.registrationPosition());
        }
        SiteCheck conflict = validateReservations(settlement, template, own);
        if (!conflict.valid()) return conflict;
        for (BlueprintPlacement placement : template.placements()) {
            SiteCheck check = validatePlacement(level, settlement, template, placement);
            if (!check.valid()) return check;
            clearances.putAll(check.clearances());
        }
        return new SiteCheck(true, clearances.isEmpty() ? "OK" : "CLEARANCE_REQUIRED",
                template.anchor(), Map.copyOf(clearances));
    }

    private static boolean decorationTemplate(BuildingBlueprintTemplate template) {
        return com.hkin.exiledsettlement.decoration.DecorationCatalog.ENTRIES.values().stream().anyMatch(e->e.type()==template.type());
    }
    private static boolean unstartedDecoration(SettlementRecord s,BlueprintRecord b) {
        return com.hkin.exiledsettlement.decoration.DecorationService.owned(s,b.id())
                && b.progressIndex()==0 && b.temporaryFootings().isEmpty();
    }
    private static void releaseUnstartedDecorations(SettlementRecord s,BuildingBlueprintTemplate template) {
        if(decorationTemplate(template))return;
        var min=template.minCorner();var max=template.maxCorner();int margin=template.transitionMargin();
        for(var other:s.blueprints())if(unstartedDecoration(s,other)) {
            var t=BlueprintTemplates.create(other);
            if(min.getX()-margin<=t.maxCorner().getX() && max.getX()+margin>=t.minCorner().getX()
                    && min.getZ()-margin<=t.maxCorner().getZ() && max.getZ()+margin>=t.minCorner().getZ()) {
                other.queueCancellation();var f=s.decorations().owned(other.id());f.suppressed=true;f.siteReady=false;
            }
        }
    }

    private static SiteCheck validatePlacement(ServerLevel level, SettlementRecord settlement,
                                              BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        BlockPos position = placement.position();
        if (placement.mode()==BlueprintPlacementMode.CLEARANCE
                && !BuildingPlacementRules.requiresClearance(template,position)) return SiteCheck.ready(position);
        if (!settlement.contains(position)) return SiteCheck.blocked("OUTSIDE_SETTLEMENT_BOUNDARY", position);
        BuildingPlacementRules.Cell result=BuildingPlacementRules.checkCell(level,template,placement);
        if (!result.valid()) return SiteCheck.blocked(result.reason(),position);
        return result.clearable() ? SiteCheck.clearable(position,level.getBlockState(position)) : SiteCheck.ready(position);
    }

    /** Shared geometry, including completed buildings; no bounding-margin prohibition. */
    private static SiteCheck validateReservations(SettlementRecord s,BuildingBlueprintTemplate t,@Nullable BlueprintRecord own) {
        var candidate=t.placementLayout();
        for (var other:s.blueprints()) {
            if(other==own || other.cancellationQueued() || !decorationTemplate(t) && unstartedDecoration(s,other))continue;
            if(!decorationTemplate(t) && com.hkin.exiledsettlement.terrain.P0PlanningService.optional(s,other))continue;
            BlockPos conflict=candidate.conflict(BlueprintTemplates.create(other).placementLayout());
            if(conflict!=null)return SiteCheck.blocked("BLUEPRINT_OVERLAP",conflict);
        }
        for(var b:s.buildings())if(b.active()) {
            if(own!=null && own.region()!=null && (b.id().equals(own.region().workingBuilding)
                   ))continue;
            BuildingPlacementRules.Layout existing=buildingLayout(b);
            BlockPos conflict=candidate.conflict(existing);
            if(conflict!=null)return SiteCheck.blocked("PLACEMENT_REGISTERED_STRUCTURE",conflict);
        }
        return SiteCheck.ready(t.anchor());
    }

    private static BuildingPlacementRules.Layout buildingLayout(com.hkin.exiledsettlement.building.BuildingRecord b) {
        try { return BlueprintTemplates.createForBuilding(b).placementLayout(); }
        catch(IllegalArgumentException unavailable) { return new BuildingPlacementRules.Layout(Set.of(b.position()),Set.of()); }
    }

    public static String validateProjectionReservations(SettlementRecord s,BuildingBlueprintTemplate t) {
        SiteCheck check=validateReservations(s,t,null);
        return check.valid()?"":check.reason()+"："+check.position().toShortString();
    }

    private static boolean reservedByOther(ServerLevel level,SettlementRecord s,BlueprintRecord own,BlockPos p) {
        for(var other:s.blueprints())if(other!=own && !other.cancellationQueued()
                && BlueprintTemplates.create(other).placementLayout().reserves(p)) {
            // A public earthwork reservation is not a completed structure protecting grass on this site.
            var order=other.terrainProject();
            if(order!=null && (order.dependencies.contains(own.id())
                    || com.hkin.exiledsettlement.terrain.TerrainService.soft(level.getBlockState(p))))continue;
            // An unbuilt public target caused by an old anchor-only footprint
            // cannot veto the actual building's own approved clearance. Its
            // paid blocks and unrelated facilities retain their protection.
            if(order!=null && (order.cancelled || order.heightSuperseded
                    || order.publicGround && !order.road && own.terrainProject()==null && own.containsColumn(p)
                        && !s.terrain().paidFillEvidence(p)
                        && com.hkin.exiledsettlement.terrain.TerrainService.natural(level.getBlockState(p))))continue;
            return true;
        }
        for(var b:s.buildings())if(b.active() && !(own.region()!=null
                && (b.id().equals(own.region().workingBuilding)))) {
            if(buildingLayout(b).reserves(p))return true;
        }
        return p.equals(s.corePosition());
    }

    private static boolean isClearable(
            ServerLevel level, BlockPos position, BlockState state
    ) {
        return !state.isAir() && state.getDestroySpeed(level, position) >= 0.0F
                && state.getFluidState().isEmpty();
    }

    private static boolean canDirectlyTransformFarmGround(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.FARMLAND);
    }

    private static void reconcileClearanceTargets(
            ServerLevel level, SettlementSavedData data, BlueprintRecord blueprint
    ) {
        boolean changed = false;
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        for (BlockPos position : List.copyOf(blueprint.clearanceTargets().keySet())) {
            if (!level.hasChunkAt(position)) continue;
            BlueprintPlacement finalPlacement = template.placementAt(position);
            boolean alreadyCompleted = finalPlacement != null
                    && finalPlacement.mode() != BlueprintPlacementMode.CLEARANCE
                    && placementSatisfied(level, finalPlacement);
            boolean directPath=finalPlacement!=null && finalPlacement.state().is(Blocks.DIRT_PATH) && canDirectlyTransformFarmGround(level.getBlockState(position));
            if (blueprint.hasTemporaryFooting(position) || level.getBlockState(position).isAir() || alreadyCompleted || directPath
                    || finalPlacement!=null && finalPlacement.mode()==BlueprintPlacementMode.CLEARANCE
                    && !BuildingPlacementRules.requiresClearance(template,position)) {
                changed |= blueprint.removeClearanceTarget(position);
            }
        }
        if (changed) data.touch();
    }

    private static boolean placementSatisfied(ServerLevel level, BlueprintPlacement placement) {
        BlockState current = level.getBlockState(placement.position());
        if (placement.mode() == BlueprintPlacementMode.CLEARANCE) return current.isAir();
        if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return !current.isAir();
        // A pair can temporarily exist as two SINGLE chests because each half
        // was installed in a separate resident action. Repair only the state of
        // already-present matching chests before deciding that the occupied
        // block-entity cell is an obstruction. No block or inventory is created
        // or replaced here; a missing partner remains a normal pending step.
        if (placement.state().getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && current.getBlock() == placement.state().getBlock()
                && !current.equals(placement.state())) {
            BlueprintBlockStateResolver.refreshExisting(level, placement);
            current = level.getBlockState(placement.position());
        }
        return matchesPlacedState(current, placement.state());
    }

    public static boolean matchesPlacedState(BlockState current, BlockState expected) {
        if (current.getBlock() != expected.getBlock()) return false;
        Set<String> dynamicProperties;
        Block block = expected.getBlock();
        if (block == com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get()
                || block == com.hkin.exiledsettlement.registry.ModBlocks.TOWN_HALL_MARKER.get()) {
            // An existing desk remains functional in the owner's chosen direction, including pre-facing saves.
            dynamicProperties = Set.of("facing");
        } else if (block instanceof FenceBlock || block instanceof IronBarsBlock) {
            dynamicProperties = Set.of("north", "east", "south", "west");
        } else if (block instanceof WallBlock) {
            dynamicProperties = Set.of("north", "east", "south", "west", "up");
        } else if (block instanceof FenceGateBlock) {
            dynamicProperties = Set.of("in_wall", "open", "powered");
        } else if (block instanceof DoorBlock) {
            dynamicProperties = Set.of("open", "powered");
        } else if (block instanceof net.minecraft.world.level.block.BedBlock) {
            dynamicProperties = Set.of("occupied");
        } else if (block instanceof net.minecraft.world.level.block.BrewingStandBlock) {
            // These flags show real bottle contents, not damage to the building.
            dynamicProperties = Set.of("has_bottle_0", "has_bottle_1", "has_bottle_2");
        } else if (block instanceof FarmBlock) {
            dynamicProperties = Set.of("moisture");
        } else if (block instanceof net.minecraft.world.level.block.SnowyDirtBlock) {
            dynamicProperties = Set.of("snowy");
        } else if (block instanceof net.minecraft.world.level.block.ComposterBlock) {
            dynamicProperties = Set.of("level");
        } else if (block instanceof StairBlock) {
            dynamicProperties = Set.of("shape");
        } else {
            return current.equals(expected);
        }
        for (Property<?> property : expected.getProperties()) {
            if (dynamicProperties.contains(property.getName())) continue;
            if (!java.util.Objects.equals(current.getValue(property), expected.getValue(property))) return false;
        }
        return true;
    }

    public static boolean placementSatisfied(
            ServerLevel level, BlueprintRecord blueprint, BlueprintPlacement placement
    ) {
        if(waterBarrierSatisfied(level,blueprint,placement))return true;
        if(blueprint.region()!=null && placement.mode()==BlueprintPlacementMode.FARM_TILL
                && blueprint.region().cultivated.contains(placement.position().asLong())
                && level.hasChunkAt(placement.position())
                && FarmEarthworkPlan.soil(level.getBlockState(placement.position()))
                && FarmEarthworkPlan.support(level,placement.position().below())
                && level.getFluidState(placement.position().above()).isEmpty()
                && placement.state().canSurvive(level,placement.position()))return true;
        // A registered scaffold temporarily occupies this step in the work plan.
        // It is deliberately skipped until the rest of the structure is complete,
        // then recovered before the real final block is placed.
        if (blueprint.hasTemporaryFooting(placement.position())) return true;
        if (placement.state().getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            BlueprintBlockStateResolver.refreshExisting(level, BlueprintTemplates.create(blueprint), placement);
        }
        return placement.mode() == BlueprintPlacementMode.CLEARANCE || blueprint.region()!=null
                ? placementSatisfied(level, BlueprintTemplates.create(blueprint), placement)
                : placementSatisfied(level, placement);
    }

    private static boolean waterBarrierSatisfied(ServerLevel level,BlueprintRecord blueprint,BlueprintPlacement placement) {
        if(blueprint.region()==null || !placement.state().is(net.minecraft.world.level.block.Blocks.DIRT))return false;
        var cell=blueprint.region().cell(placement.position());
        boolean boundary=cell!=null && cell.ground().equals(placement.position()) && (blueprint.region().plainSoil() || cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY || cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE);
        if(!boundary && !blueprint.region().waterBarriers.contains(com.hkin.exiledsettlement.region.ProductionRegion.key(placement.position().getX(),placement.position().getZ())))return false;
        var state=level.getBlockState(placement.position());
        return FarmEarthworkPlan.soil(state) && state.isFaceSturdy(level,placement.position(),Direction.UP);
    }

    public static boolean placementSatisfied(
            ServerLevel level, BuildingBlueprintTemplate template, BlueprintPlacement placement
    ) {
        if (placement.mode() == BlueprintPlacementMode.CLEARANCE) {
            if (!BuildingPlacementRules.requiresClearance(template,placement.position())) return true;
            BlueprintPlacement finished = template.placementAt(placement.position());
            if (finished != null && finished.mode().isFinalStructure()
                    && placementSatisfied(level, finished)) return true;
        }
        var current=level.getBlockState(placement.position());
        if((FarmEarthworkPlan.applies(template.type()) || template.type()==BuildingType.TREE_FARM || template.type()==BuildingType.PASTURE_AREA) && placement.state().is(Blocks.DIRT)
                && FarmEarthworkPlan.soil(current) && current.isFaceSturdy(level,placement.position(),Direction.UP))return true;
        return placementSatisfied(level, placement);
    }

    private static boolean applyPlacement(ServerLevel level, BlueprintPlacement placement) {
        if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                && !level.getBlockState(placement.position()).isAir()) return true;
        boolean placed = level.setBlock(placement.position(), BlueprintBlockStateResolver.resolve(level, placement),
                Block.UPDATE_ALL);
        if (placed) BlueprintBlockStateResolver.refreshExisting(level, placement);
        return placed;
    }

    @Nullable
    private static BlockPos findMarkerPosition(
            ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate template
    ) {
        int minX = template.minCorner().getX() - 2;
        int maxX = template.maxCorner().getX() + 2;
        int minZ = template.minCorner().getZ() - 2;
        int maxZ = template.maxCorner().getZ() + 2;
        java.util.List<BlockPos> perimeter = new ArrayList<>();
        int baseY = template.anchor().getY();
        for (int x = minX; x <= maxX; x++) {
            perimeter.add(new BlockPos(x, baseY, minZ));
            perimeter.add(new BlockPos(x, baseY, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            perimeter.add(new BlockPos(minX, baseY, z));
            perimeter.add(new BlockPos(maxX, baseY, z));
        }
        perimeter.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(template.anchor())));
        for (BlockPos base : perimeter) {
            for (int dy = -4; dy <= 6; dy++) {
                BlockPos candidate = base.offset(0, dy, 0);
                if (validMarkerPosition(level, settlement, candidate)) return candidate;
            }
        }
        return null;
    }

    public static boolean validMarkerPosition(ServerLevel level, SettlementRecord settlement, BlockPos candidate) {
        return settlement.contains(candidate) && level.hasChunkAt(candidate)
                && level.getBlockState(candidate).isAir()
                && level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)
                && settlement.blueprintAt(candidate) == null;
    }

    private static void rollback(List<BlockSnapshot> snapshots) {
        for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore(Block.UPDATE_CLIENTS);
    }

    private static void refund(ServerLevel level, BlueprintRecord blueprint) {
        if (!blueprint.refundItem()) return;
        ItemStack refund = blueprintItem(blueprint.type());
        if (!refund.isEmpty()) Block.popResource(level, blueprint.markerPosition(), refund);
    }

    private static void showFootprint(ServerLevel level, BuildingBlueprintTemplate template) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int y = template.anchor().getY();
        for (BlueprintPlacement placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE || placement.position().getY() != y) continue;
            minX = Math.min(minX, placement.position().getX()); maxX = Math.max(maxX, placement.position().getX());
            minZ = Math.min(minZ, placement.position().getZ()); maxZ = Math.max(maxZ, placement.position().getZ());
        }
        if (minX == Integer.MAX_VALUE) return;
        for (int x = minX; x <= maxX; x += 2) { particle(level, x, y, minZ); particle(level, x, y, maxZ); }
        for (int z = minZ; z <= maxZ; z += 2) { particle(level, minX, y, z); particle(level, maxX, y, z); }
    }
    /** Only outstanding final positions are highlighted in a partially operating field/public work. */
    public static List<BlockPos> constructionMarkers(ServerLevel level,BlueprintRecord blueprint){
        if(blueprint.cancellationQueued())return List.of();var template=BlueprintTemplates.create(blueprint);var placements=template.placements();
        if(placements.isEmpty())return List.of();var points=new ArrayList<BlockPos>();
        var project=blueprint.terrainProject();if(project!=null && project.playerBridge!=null){
            for(int i:project.playerBridge.activeIndices(project))if(project.playerBridge.admitted.get(i) && !project.finished.get(i)){
                var step=project.steps.get(i);if(level.hasChunkAt(step.position()))points.add(step.position());if(points.size()>=16)break;
            }return List.copyOf(points);
        }
        int start=Math.floorMod((int)(level.getGameTime()/20)*64,placements.size());
        for(int i=0;i<Math.min(64,placements.size());i++){var p=placements.get((start+i)%placements.size());
            if(p.state().isAir() || placementSatisfied(level,blueprint,p) || !level.hasChunkAt(p.position()))continue;
            points.add(p.position());if(points.size()>=16)break;
        }
        return List.copyOf(points);
    }

    private static void particle(ServerLevel level, int x, int y, int z) {
        level.sendParticles(ParticleTypes.END_ROD, x + 0.5D, y + 0.15D, z + 0.5D,
                1, 0.02D, 0.02D, 0.02D, 0.0D);
    }

    private static void sendPlaced(ServerPlayer player, BlueprintRecord blueprint, BuildingBlueprintTemplate template) {
        player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.placed",
                Component.translatable(blueprint.type().translationKey()), template.width(), template.depth(), template.height())
                .withStyle(ChatFormatting.AQUA), false);
        player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.worker_hint"), false);
        player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.material_header"), false);
        if (blueprint.earthworkPlan()!=null) {
            var plan=blueprint.earthworkPlan();
            player.displayClientMessage(Component.translatable("message.exiledsettlement.farm_earthwork.summary",
                    plan.soilY(),plan.digCount(),plan.fillCount()),false);
        }
        for (Map.Entry<Item, Integer> requirement : FarmEarthworkService.requirements(player.serverLevel(), blueprint).entrySet()) {
            player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.material_line",
                    Component.translatable(requirement.getKey().getDescriptionId()), requirement.getValue())
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void sendMissing(ServerPlayer player, Map<Item, Integer> missing) {
        player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.missing_header")
                .withStyle(ChatFormatting.RED), false);
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            player.displayClientMessage(Component.translatable("message.exiledsettlement.blueprint.material_line",
                    Component.translatable(entry.getKey().getDescriptionId()), entry.getValue())
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    private static void sendSiteFailure(@Nullable ServerPlayer player, SiteCheck site) {
        if (player != null) player.displayClientMessage(Component.translatable(
                "message.exiledsettlement.blueprint.blocked_position", site.position().toShortString())
                .withStyle(ChatFormatting.RED), false);
    }

    public record PendingPlacement(int index, BlueprintPlacement placement) {
    }

    public record DebugAdvanceResult(boolean advanced, boolean completed, String reason) {
        private static DebugAdvanceResult idle(String reason) {
            return new DebugAdvanceResult(false, false, reason);
        }

        private static DebugAdvanceResult advanced(boolean completed, String reason) {
            return new DebugAdvanceResult(true, completed, reason);
        }

        private static DebugAdvanceResult completed(String reason) {
            return new DebugAdvanceResult(false, true, reason);
        }
    }

    private record SiteCheck(
            boolean valid, String reason, BlockPos position, Map<BlockPos, BlockState> clearances
    ) {
        private static SiteCheck ready(BlockPos position) {
            return new SiteCheck(true, "OK", position, Map.of());
        }

        private static SiteCheck clearable(BlockPos position, BlockState state) {
            return new SiteCheck(true, "CLEARANCE_REQUIRED", position, Map.of(position.immutable(), state));
        }

        private static SiteCheck blocked(String reason, BlockPos position) {
            return new SiteCheck(false, reason, position, Map.of());
        }
    }
}

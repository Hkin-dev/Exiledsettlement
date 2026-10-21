package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** Resident construction is an on-site timed presentation, not a physical block puzzle. */
public final class TimedConstructionService {
    public static final int INTERVAL = 10;
    private TimedConstructionService() {}
    private static final Map<BuildingBlueprintTemplate,Map<BlockPos,List<Integer>>> INDICES=new WeakHashMap<>();
    private record RoadWorksite(List<BlockPos> cells, BuildingBlueprintTemplate bounds) {}
    private static final Map<com.hkin.exiledsettlement.road.AutoRoadState.Route,RoadWorksite> ROAD_WORKSITES=new WeakHashMap<>();

    /** A bridge approach is built from its reachable bank, including buried foundation steps. */
    @Nullable private static BuildingBlueprintTemplate bridgeApproach(ServerLevel level,BlueprintRecord site,BlockPos target) {
        var project=site==null?null:site.terrainProject();
        if(project==null || !project.road || project.cancelled || target==null)return null;
        var s=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).findContaining(site.anchor()).orElse(null);
        if(s==null || s.blueprint(site.id())!=site)return null;
        for(var route:s.autoRoads().routes.values()) {
            if(route.suppressed || route.p0Invalidated || !project.dependencies.contains(route.id)
                    || com.hkin.exiledsettlement.road.BridgeRoadConnections.bridge(s,route.building)==null
                    || route.cells.stream().noneMatch(p->p.getX()==target.getX() && p.getZ()==target.getZ()))continue;
            var cached=ROAD_WORKSITES.get(route);
            if(cached!=null && cached.cells()==route.cells)return cached.bounds();
            int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
            for(var p:route.cells){minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());
                maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
            var bounds=new BuildingBlueprintTemplate(site.type(),site.anchor(),site.facing(),maxX-minX+1,maxZ-minZ+1,maxY-minY+1,
                    new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ),0,List.of());
            ROAD_WORKSITES.put(route,new RoadWorksite(route.cells,bounds));return bounds;
        }
        return null;
    }
    private static Map<BlockPos,List<Integer>> indices(BuildingBlueprintTemplate template) {
        return INDICES.computeIfAbsent(template,t->{var out=new HashMap<BlockPos,List<Integer>>();
            for(int i=0;i<t.placements().size();i++)out.computeIfAbsent(t.placements().get(i).position(),p->new ArrayList<>()).add(i);
            return out;});
    }
    private static boolean needed(ServerLevel level,BlueprintRecord site,BuildingBlueprintTemplate template,BlueprintPlacement p,Set<BlockPos> claimed) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(level,p.position()))return false;
        return level.hasChunkAt(p.position()) && !level.isOutsideBuildHeight(p.position())
                && (!BlueprintPlacementGroups.isPaired(p.state()) || BlueprintPlacementGroups.isPrimary(p.state()))
                && !BlueprintPlacementGroups.claimed(template,p,claimed)
                && (!BlueprintService.placementSatisfied(level,template,p) || site.hasTemporaryFooting(p.position()));
    }
    private record ClearancePhase(boolean active,List<BlueprintService.PendingPlacement> work) {}
    /** Clearance can open the site first; final building work waits for its surveyed foundation. */
    private static boolean foundationReady(ServerLevel level,BlueprintRecord site) {
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).findContaining(site.anchor()).orElse(null);
        return settlement!=null && com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(settlement,site.id());
    }
    /** Read live obstacles before dispatch AND execution. Claimed obstacles still hold the phase gate. */
    private static ClearancePhase clearancePhase(ServerLevel level,BlueprintRecord site,BuildingBlueprintTemplate template) {
        // Region earthworks already have their own preparation sequence.
        if(site.region()!=null || site.terrainProject()!=null)return new ClearancePhase(false,List.of());
        var work=new LinkedHashMap<BlockPos,BlueprintService.PendingPlacement>();
        boolean unloaded=false;
        for(int i=0;i<template.placements().size();i++) {
            var p=template.placements().get(i);var at=p.position();
            if(p.mode()==BlueprintPlacementMode.OPTIONAL_SUPPORT || site.hasTemporaryFooting(at)
                    || p.mode()==BlueprintPlacementMode.CLEARANCE && !BuildingPlacementRules.requiresClearance(template,at))continue;
            if(!level.hasChunkAt(at) || level.isOutsideBuildHeight(at)){unloaded=true;continue;}
            if(level.getBlockState(at).isAir() || BlueprintService.placementSatisfied(level,template,p))continue;
            var cell=BuildingPlacementRules.checkCell(level,template,FarmConstructionSequence.stage(level,template,p));
            // Soil transforms and waterlogging are construction, not demolition.
            if(cell.valid() && !cell.clearable())continue;
            work.putIfAbsent(at,new BlueprintService.PendingPlacement(i,new BlueprintPlacement(at,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)));
        }
        for(var at:site.clearanceTargets().keySet())if(template.placementAt(at)==null) {
            if(!level.hasChunkAt(at) || level.isOutsideBuildHeight(at)){unloaded=true;continue;}
            if(!level.getBlockState(at).isAir())work.putIfAbsent(at,new BlueprintService.PendingPlacement(-1,
                    new BlueprintPlacement(at,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)));
        }
        var ordered=new ArrayList<>(work.values());
        ordered.sort(Comparator.comparingInt((BlueprintService.PendingPlacement p)->p.placement().position().getY()).reversed());
        return new ClearancePhase(unloaded || !ordered.isEmpty(),ordered);
    }
    public static boolean clearancePending(ServerLevel level,BlueprintRecord site) {
        return clearancePhase(level,site,BlueprintTemplates.create(site)).active();
    }
    /** Query only this saved cell, including any ordered preparation at the same position. */
    public static BlueprintService.PendingPlacement at(ServerLevel level,BlueprintRecord site,BlockPos at) {
        if(!com.hkin.exiledsettlement.region.RegionConstructionStages.facilitiesReady(level,site))return null;
        if(at==null)return null;var template=BlueprintTemplates.create(site);
        var phase=clearancePhase(level,site,template);
        if(phase.active())return phase.work().stream().filter(p->p.placement().position().equals(at)).findFirst().orElse(null);
        if(!foundationReady(level,site))return null;
        if(FarmConstructionSequence.applies(template))return FarmConstructionSequence.pending(level,site,template,Set.of())
                .stream().filter(p->p.placement().position().equals(at)).findFirst().orElse(null);
        for(int i:indices(template).getOrDefault(at,List.of())) {var p=template.placements().get(i);
            if(needed(level,site,template,p,Set.of())){var action=IrrigationConstructionService.stage(level,template,p);
                if(IrrigationConstructionService.ready(level,template,action))return new BlueprintService.PendingPlacement(i,action);}}
        if(template.placementAt(at)==null && site.hasClearanceTarget(at) && level.hasChunkAt(at) && !level.getBlockState(at).isAir())
            return new BlueprintService.PendingPlacement(-1,new BlueprintPlacement(at,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE));
        return null;
    }
    public static BlueprintService.PendingPlacement first(ServerLevel level,BlueprintRecord site,Set<BlockPos> claimed) {
        claimed=com.hkin.exiledsettlement.citizen.TreeSurvey.claimed(level,claimed);
        if(!com.hkin.exiledsettlement.region.RegionConstructionStages.facilitiesReady(level,site))return null;
        var template=BlueprintTemplates.create(site);
        var phase=clearancePhase(level,site,template);
        if(phase.active()) {
            for(var p:phase.work())if(!claimed.contains(p.placement().position()))return p;
            return null;
        }
        if(!foundationReady(level,site))return null;
        if(FarmConstructionSequence.applies(template))return FarmConstructionSequence.pending(level,site,template,claimed)
                .stream().findFirst().orElse(null);
        for(int i=0;i<template.placements().size();i++) {var p=template.placements().get(i);
            if(needed(level,site,template,p,claimed)){var action=IrrigationConstructionService.stage(level,template,p);
                if(IrrigationConstructionService.ready(level,template,action))return new BlueprintService.PendingPlacement(i,action);}}
        for(var at:site.clearanceTargets().keySet())if(!claimed.contains(at) && template.placementAt(at)==null) {
            var p=at(level,site,at);if(p!=null)return p;}
        return null;
    }

    public static boolean building(BlueprintRecord site) { return site != null && site.terrainProject() == null
            && (site.customTemplate()!=null || site.region()!=null || BlueprintTemplates.supports(site.type())); }

    /** Clearance precedes the surveyed foundation gate; final goals retain real material and protection checks. */
    public static List<BlueprintService.PendingPlacement> pending(ServerLevel level, BlueprintRecord site, Set<BlockPos> claimed) {
        claimed=com.hkin.exiledsettlement.citizen.TreeSurvey.claimed(level,claimed);
        if(!com.hkin.exiledsettlement.region.RegionConstructionStages.facilitiesReady(level,site))return List.of();
        var template = BlueprintTemplates.create(site);
        var phase=clearancePhase(level,site,template);
        if(phase.active()) {
            var available=new ArrayList<BlueprintService.PendingPlacement>();
            for(var p:phase.work())if(!claimed.contains(p.placement().position()))available.add(p);
            return available;
        }
        if(!foundationReady(level,site))return List.of();
        if(FarmConstructionSequence.applies(template))return FarmConstructionSequence.pending(level,site,template,claimed);
        var result = new ArrayList<BlueprintService.PendingPlacement>();
        for (int i = 0; i < template.placements().size(); i++) {
            var p = template.placements().get(i);
            if (!level.hasChunkAt(p.position()) || level.isOutsideBuildHeight(p.position())
                    || BlueprintPlacementGroups.isPaired(p.state()) && !BlueprintPlacementGroups.isPrimary(p.state())
                    || BlueprintPlacementGroups.claimed(template,p,claimed)) continue;
            if (!BlueprintService.placementSatisfied(level,template,p) || site.hasTemporaryFooting(p.position())){
                var action=IrrigationConstructionService.stage(level,template,p);
                if(IrrigationConstructionService.ready(level,template,action))result.add(new BlueprintService.PendingPlacement(i,action));
            }
        }
        for(var at:site.clearanceTargets().keySet())if(template.placementAt(at)==null && level.hasChunkAt(at)
                && !level.getBlockState(at).isAir() && !claimed.contains(at))
            result.add(new BlueprintService.PendingPlacement(-1,new BlueprintPlacement(at,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE)));
        return result;
    }

    public static boolean fill(ServerLevel level, BlueprintRecord site, BlockPos at) {
        if (site == null || site.terrainProject() == null || at == null) return false;
        // A future fill at this cell must not capture an excavation that has to
        // happen first. Use the same current-step selector as actual execution.
        var pending = com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,at);
        return pending != null && site.terrainProject().steps.get(-pending.index()-1).fill();
    }

    /** Farm fill children use their owning field's worksite, never the water-bottom child's bounds. */
    @Nullable private static BuildingBlueprintTemplate worksite(ServerLevel level,BlueprintRecord site,BlockPos target) {
        if(building(site))return BlueprintTemplates.create(site);
        var approach=bridgeApproach(level,site,target);if(approach!=null)return approach;
        var project=site==null?null:site.terrainProject();
        if(project==null || project.publicGround || project.road || project.bridge || project.areaClearance
                || project.roadReclaim || project.escapeFor!=null || target==null)return null;
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).findContaining(site.anchor()).orElse(null);
        if(settlement==null || settlement.blueprint(site.id())!=site)return null;
        var owner=project.regionOwner;
        if(owner==null)for(var dependency:project.dependencies) {
            var candidate=com.hkin.exiledsettlement.region.RegionWorkScope.owner(settlement,dependency);
            if(candidate!=null){if(owner!=null && !owner.equals(candidate))return null;owner=candidate;}
        }
        if(owner==null || !com.hkin.exiledsettlement.region.RegionWorkScope.same(settlement,project,owner))return null;
        var region=com.hkin.exiledsettlement.region.RegionWorkScope.region(settlement,owner);
        if(region==null || !region.agricultural())return null;
        var cell=region.cell(target);
        return cell!=null && target.getY()<=cell.ground().getY() && fill(level,site,target)?region.template():null;
    }

    private static boolean withinWorksite(BuildingBlueprintTemplate t,BlockPos feet) {
        int dx=Math.max(Math.max(t.minCorner().getX()-feet.getX(),feet.getX()-t.maxCorner().getX()),0);
        int dz=Math.max(Math.max(t.minCorner().getZ()-feet.getZ(),feet.getZ()-t.maxCorner().getZ()),0);
        return Math.max(dx,dz)<=4 && feet.getY()>=t.minCorner().getY()-4 && feet.getY()<=t.maxCorner().getY()+4;
    }

    /** Buildings and their farm fills share a worksite; other earthwork keeps its local column. */
    public static boolean arrived(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity actor, BlockPos target) {
        return arrivedAt(level,site,actor.navigationFeetPosition(),target);
    }
    public static boolean arrivedAt(ServerLevel level,BlueprintRecord site,BlockPos feet,BlockPos target) {
        if(site!=null && site.terrainProject()!=null && site.terrainProject().playerBridge!=null)
            return com.hkin.exiledsettlement.road.PlayerBridgeWork.arrived(level,site.terrainProject(),feet,target);
        var t=worksite(level,site,target);
        if(t!=null)return withinWorksite(t,feet);
        return columnArrivedAt(level,feet,target);
    }

    public static boolean columnArrived(ServerLevel level, SettlementCitizenEntity actor, BlockPos target) {
        return columnArrivedAt(level,actor.navigationFeetPosition(),target);
    }
    private static boolean columnArrivedAt(ServerLevel level,BlockPos feet,BlockPos target) {
        if (target == null || !level.hasChunkAt(target)) return false;
        int surface=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,target.getX(),target.getZ());
        return Math.abs(feet.getX()-target.getX())<=4 && Math.abs(feet.getZ()-target.getZ())<=4
                && (Math.abs(feet.getY()-target.getY())<=4 || Math.abs(feet.getY()-surface)<=4);
    }

    /** Only walking needs a standable destination. No per-block reach or line of sight is required after arrival. */
    @Nullable public static BlockPos approach(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity actor, BlockPos target) {
        if(site!=null && site.terrainProject()!=null && site.terrainProject().playerBridge!=null)
            return com.hkin.exiledsettlement.road.PlayerBridgeWork.approach(level,site.terrainProject(),actor,target);
        var bases=new ArrayList<BlockPos>();
        var worksite=worksite(level,site,target);
        if(worksite!=null) {
            var min=worksite.minCorner();var max=worksite.maxCorner();var feet=actor.navigationFeetPosition();
            if(site.terrainProject()!=null && site.terrainProject().road && withinWorksite(worksite,feet)
                    && actor.canStandAtPosition(feet))return feet;
            int x=Math.clamp(feet.getX(),min.getX(),max.getX()),z=Math.clamp(feet.getZ(),min.getZ(),max.getZ());
            bases.addAll(List.of(new BlockPos(min.getX()-2,min.getY(),z),new BlockPos(max.getX()+2,min.getY(),z),
                    new BlockPos(x,min.getY(),min.getZ()-2),new BlockPos(x,min.getY(),max.getZ()+2)));
        } else if(target!=null) {
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)bases.add(target.offset(dx,0,dz));
        }
        var candidates=new ArrayList<BlockPos>();
        for(var base:bases) {
            if(!level.hasChunkAt(base))continue;
            int surface=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base.getX(),base.getZ());
            for(int y:new int[]{surface,actor.navigationFeetPosition().getY(),base.getY(),base.getY()+1}) {
                var p=base.atY(y);if((worksite!=null?withinWorksite(worksite,p):arrivedAt(level,site,p,target)) && actor.canStandAtPosition(p))candidates.add(p);
            }
        }
        var ordered=candidates.stream().distinct().sorted(Comparator.comparingDouble(p->p.distSqr(actor.blockPosition()))).toList();
        var reachable=actor.findReachableBedStation(ordered);
        return reachable!=null?reachable:ordered.isEmpty()?null:ordered.getFirst();
    }

    /** Staging avoids physics undoing an attachment before later timed steps supply its final neighbours. */
    public static boolean put(ServerLevel level, BlockPos at, BlockState target, LivingEntity worker) {
        return com.hkin.exiledsettlement.citizen.ResidentBlockDrops.run(level, worker, () -> putDirect(level, at, target, worker), Boolean.TRUE::equals);
    }

    private static boolean putDirect(ServerLevel level, BlockPos at, BlockState target, LivingEntity worker) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(level,at))return false;
        if(level.isOutsideBuildHeight(at) || !level.hasChunkAt(at))return false;
        var before=level.getBlockState(at);
        if(before.equals(target)){BlueprintBlockStateResolver.refreshAround(level,at);return true;}
        var tree=com.hkin.exiledsettlement.citizen.ResidentTreeFelling.clear(level,at,worker,worker.getMainHandItem(),p->true,target);
        if(tree!=null) {
            if(!tree.failure().isEmpty())return false;
            tree.drops().forEach(drop->Block.popResource(level,at,drop));return true;
        }
        if(!before.isAir() && !CommonHooks.canEntityDestroy(level,at,worker))return false;
        var snapshot=BlockSnapshot.create(level.dimension(),level,at,Block.UPDATE_CLIENTS);
        var drops=before.isAir()?List.<ItemStack>of():Block.getDrops(before,level,at,level.getBlockEntity(at),worker,worker.getMainHandItem());
        if(!level.setBlock(at,target,Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS))return false;
        if(EventHooks.onBlockPlace(worker,snapshot,Direction.UP) || !level.getBlockState(at).equals(target)) {
            snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);return false;
        }
        BlueprintBlockStateResolver.refreshAround(level,at);
        drops.forEach(drop->Block.popResource(level,at,drop));
        return true;
    }
}

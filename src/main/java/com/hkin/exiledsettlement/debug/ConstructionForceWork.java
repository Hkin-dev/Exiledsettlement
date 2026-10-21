package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;

/** Explicit debug actions only. No resident, material or production progress is fabricated. */
public final class ConstructionForceWork {
    private ConstructionForceWork() {}
    public static boolean put(ServerLevel level,SettlementRecord s,BlockPos at,BlockState target) {
        if(!ConstructionDebugService.workEnabled(level,s) || !clearForEvolve(level,s,at))return false;
        return putAuthorized(level,s,at,target);
    }
    public static boolean putTerrain(ServerLevel level,SettlementRecord s,BlueprintRecord site,BlockPos at,BlockState target) {
        boolean evolve=TerrainEvolveDebugService.authorizes(level,s,site);
        if(!evolve && !ConstructionDebugService.isEnabled(level,s.id()))return false;
        // Leave a local escape margin around living bodies, including their footing.
        // Never turn fast terrain publication into an enclosing wall beside a resident.
        if(evolve && !level.getEntitiesOfClass(LivingEntity.class,new AABB(at).inflate(1),e->e.isAlive()&&!e.isSpectator()).isEmpty())return false;
        return putAuthorized(level,s,at,target);
    }
    public static boolean clearForEvolve(ServerLevel level,SettlementRecord s,BlockPos at) {
        return !TerrainEvolveDebugService.running(level,s) || level.getEntitiesOfClass(LivingEntity.class,
                new AABB(at).inflate(1),e->e.isAlive()&&!e.isSpectator()).isEmpty();
    }
    /** Snapshot work carriers, never run an unbounded whole settlement inside one queued step. */
    public static List<java.util.function.BooleanSupplier> otherJobs(ServerLevel level,SettlementRecord s) {
        var jobs=new ArrayList<java.util.function.BooleanSupplier>();
        for(var route:List.copyOf(s.autoRoads().routes.values()))jobs.add(()->
                s.autoRoads().routes.containsValue(route) && road(level,s,route));
        for(var b:List.copyOf(s.buildings()))if(b.active()) {
            if(b.demolitionQueued())jobs.add(()->b.active() && b.demolitionQueued() && demolish(level,s,b));
            else {
                if(!b.repairPositions().isEmpty())jobs.add(()->b.active() && !b.demolitionQueued() && repairBuilding(level,s,b));
                if(b.type()==BuildingType.MINE)jobs.add(()->MineExpansionService.debugAdvance(level,s,b));
            }
        }
        for(var at:List.copyOf(s.roadRepairPositions()))jobs.add(()->repairRoad(level,s,at));
        for(var incident:s.mobRecovery().incidents.values())for(var cell:incident.cells.values())
            if(cell.status==MobRecoveryState.Status.PENDING)jobs.add(()->cell.status==MobRecoveryState.Status.PENDING
                    && MobRecoveryService.debugPlace(level,s,incident,cell).isEmpty());
        return jobs;
    }
    private static boolean repairRoad(ServerLevel level,SettlementRecord s,BlockPos at) {
        if(!s.roadRepairPositions().contains(at) || MobRecoveryService.owns(s,at))return false;
        var state=BuildingRepairService.expectedState(level,s,null,at);
        if(state==null || level.hasChunkAt(at) && (level.getBlockState(at).isAir()
                || level.getBlockState(at).canBeReplaced() || BlueprintService.matchesPlacedState(level.getBlockState(at),state))
                && put(level,s,at,state)) {
            s.clearRoadRepair(at);SettlementSavedData.get(level).touch();return true;
        }
        return false;
    }
    private static boolean putAuthorized(ServerLevel level,SettlementRecord s,BlockPos at,BlockState target) {
        if(!level.hasChunkAt(at)
                || level.isOutsideBuildHeight(at) || !s.contains(at) || at.equals(s.corePosition())
                || s.community().danger(at) || s.decorations().forbidden(at) || s.terrain().suppressed.contains(at.asLong()))return false;
        var before=level.getBlockState(at);
        if(BlueprintService.matchesPlacedState(before,target)){BlueprintBlockStateResolver.refreshAround(level,at);return true;}
        if(!target.getCollisionShape(level,at).isEmpty()
                && !level.getEntitiesOfClass(LivingEntity.class,new AABB(at),e->e.isAlive()&&!e.isSpectator()).isEmpty())return false;
        if(target.isAir() && !level.getEntitiesOfClass(LivingEntity.class,new AABB(at).move(0,1,0),
                e->e.isAlive()&&!e.isSpectator()).isEmpty())return false;
        var snapshot=BlockSnapshot.create(level.dimension(),level,at,Block.UPDATE_CLIENTS);
        int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
        if(!level.setBlock(at,target,flags) || EventHooks.onBlockPlace(null,snapshot,Direction.UP)) {
            snapshot.restore(flags);return false;
        }
        // Staged placement keeps later-supported torches, rails and paired plan
        // fragments intact, matching forced blueprint placement.
        BlueprintBlockStateResolver.refreshAround(level,at);
        return true;
    }
    /** Finish admitted road paving within solved or completed public terrain columns. */
    public static boolean pavePublicTerrain(ServerLevel level,SettlementRecord s,AutoRoadState.Route route) {
        if(!TerrainEvolveDebugService.terrainRunning(level,s) || route.suppressed || !AutoRoadService.routeAllowed(s,route))return false;
        for(int n=0;n<Math.min(64,route.cells.size());n++) {
            int index=Math.floorMod(route.workCursor++,route.cells.size());
            if(route.completed.get(index) && !route.repairs.get(index) || !route.admitted.get(index))continue;
            var at=route.cells.get(index);
            if(!com.hkin.exiledsettlement.terrain.PublicGroundService.hasSolvedColumn(s,at)
                    && s.terrain().projectsInColumn(at).stream().noneMatch(p->p.publicGround && p.road && p.completed
                    && !p.cancelled && p.dependencies.contains(route.id) && p.groundSurfaces.contains(at)))continue;
            if(!RoadMaintenanceService.workTargetAllowed(level,s,route,at) || !AutoRoadService.safeCell(level,s,at)
                    || !RoadEarthworkService.surfaceReady(level,s,route,at))continue;
            if(!level.getEntitiesOfClass(LivingEntity.class,new AABB(at).inflate(1),e->e.isAlive()&&!e.isSpectator()).isEmpty())continue;
            var now=level.getBlockState(at);
            boolean earth=now.is(Blocks.GRASS_BLOCK)||now.is(Blocks.DIRT)||now.is(Blocks.COARSE_DIRT)
                    ||now.is(Blocks.PODZOL)||now.is(Blocks.MYCELIUM)||now.is(Blocks.ROOTED_DIRT);
            if(!PlayerRoadEdits.retained(level,at) && earth && !putAuthorized(level,s,at,Blocks.DIRT_PATH.defaultBlockState()))continue;
            if(!earth && !RoadSurfaceProfile.usable(level,at))continue;
            RoadSpaceData.get(level).addAll(List.of(at),RoadTier.LEVEL_1);
            route.mark(at);route.repairs.clear(index);route.status="调试地形演化：公共通路已铺设";
            SettlementSavedData.get(level).touch();return true;
        }
        return false;
    }
    public static void tick(ServerLevel level,SettlementRecord s) {
        if(!ConstructionDebugService.isEnabled(level,s.id()))return;
        for(var route:List.copyOf(s.autoRoads().routes.values()))road(level,s,route);
        for(var building:List.copyOf(s.buildings()))if(building.active()) {
            if(building.demolitionQueued())demolish(level,s,building);
            else {
                repairBuilding(level,s,building);
                if(building.type()==BuildingType.MINE)MineExpansionService.debugAdvance(level,s,building);
            }
        }
        for(var at:List.copyOf(s.roadRepairPositions())) {
            if(MobRecoveryService.owns(s,at))continue;
            var state=BuildingRepairService.expectedState(level,s,null,at);
            if(state==null || level.hasChunkAt(at) && (level.getBlockState(at).isAir()
                    || level.getBlockState(at).canBeReplaced() || BlueprintService.matchesPlacedState(level.getBlockState(at),state))
                    && put(level,s,at,state)) {
                s.clearRoadRepair(at);SettlementSavedData.get(level).touch();
            }
            break;
        }
        for(var incident:s.mobRecovery().incidents.values()) {
            for(var cell:incident.cells.values())if(cell.status==MobRecoveryState.Status.PENDING) {
                if(MobRecoveryService.debugPlace(level,s,incident,cell).isEmpty())break;
            }
        }
    }
    private static boolean road(ServerLevel level,SettlementRecord s,AutoRoadState.Route route) {
        if(route.suppressed || !AutoRoadService.routeAllowed(s,route))return false;
        // Rotate a bounded scan so an inaccessible first cell cannot hide later work.
        for(int n=0;n<Math.min(64,route.cells.size());n++) {
            int i=Math.floorMod(route.workCursor++,route.cells.size());
            if(route.completed.get(i) && !route.repairs.get(i))continue;
            var at=route.cells.get(i);
            if(!RoadMaintenanceService.workTargetAllowed(level,s,route,at) || !AutoRoadService.safeCell(level,s,at))continue;
            // Evolving all roads retains the public-evolution geometry gate:
            // a planned slope is not finished just because each cell is solid.
            if(TerrainEvolveDebugService.running(level,s) && !RoadEarthworkService.surfaceReady(level,s,route,at))continue;
            var current=level.getBlockState(at);
            boolean earth=current.is(Blocks.GRASS_BLOCK)||current.is(Blocks.DIRT)||current.is(Blocks.COARSE_DIRT)
                    ||current.is(Blocks.PODZOL)||current.is(Blocks.MYCELIUM)||current.is(Blocks.ROOTED_DIRT);
            if(!PlayerRoadEdits.retained(level,at) && earth && !put(level,s,at,Blocks.DIRT_PATH.defaultBlockState()))continue;
            if(!earth && !RoadSurfaceProfile.usable(level,at))continue;
            RoadSpaceData.get(level).addAll(List.of(at),RoadTier.LEVEL_1);
            route.mark(at);route.repairs.clear(i);route.status="调试强制施工：道路进度已推进";
            SettlementSavedData.get(level).touch();return true;
        }
        return false;
    }
    private static boolean repairBuilding(ServerLevel level,SettlementRecord s,BuildingRecord b) {
        if(!BlueprintTemplates.supports(b.type()))return false;
        var template=BlueprintTemplates.createForBuilding(b);
        for(var at:List.copyOf(b.repairPositions())) {
            if(!level.hasChunkAt(at) || MobRecoveryService.owns(s,at))continue;
            var placement=template.placementAt(at);
            if(placement==null || BuildingRepairService.satisfied(level,b,at)) {
                BuildingRepairService.clearRepair(s,b,at);SettlementSavedData.get(level).touch();continue;
            }
            var now=level.getBlockState(at);
            boolean irrigation=now.getBlock()==placement.state().getBlock()
                    && now.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    && placement.state().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    && now.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true).equals(placement.state());
            if(!irrigation && !now.isAir() && !now.canBeReplaced()
                    && !now.is(com.hkin.exiledsettlement.registry.ExiledSettlementTags.REPLACEABLE_FOUNDATION))continue;
            boolean success;
            if(BlueprintPlacementGroups.isPaired(placement.state())) {
                if(BlueprintPlacementGroups.parts(template,placement).stream().anyMatch(part->
                        !level.hasChunkAt(part.position()) || !clearForEvolve(level,s,part.position()) || !s.contains(part.position()) || s.community().danger(part.position())
                        || s.decorations().forbidden(part.position()) || s.terrain().suppressed.contains(part.position().asLong()) || !level.getEntitiesOfClass(LivingEntity.class,new AABB(part.position())).isEmpty()))continue;
                success=BlueprintPlacementGroups.place(level,s,template,placement,null,false).success();
            } else success=put(level,s,at,placement.state());
            if(success){BuildingRepairService.clearRepair(s,b,at);BuildingFunctionService.validateSingle(level,SettlementSavedData.get(level),s,b);SettlementSavedData.get(level).touch();return true;}
        }
        return false;
    }
    private static boolean demolish(ServerLevel level,SettlementRecord s,BuildingRecord b) {
        if(b.type()==BuildingType.WAGON || !BuildingDemolitionService.areaLoaded(level,b))return false;
        // Virtual warehouse contents are real stacks. Drop one stack before
        // removing any building blocks; never erase or remotely duplicate stock.
        if(WarehouseStock.applies(b)) {
            var stock=WarehouseInventoryService.stock(level,b);
            for(int i=0;i<stock.getContainerSize();i++)if(!stock.getItem(i).isEmpty()) {
                var stack=stock.getItem(i);int count=Math.min(stack.getCount(),stack.getMaxStackSize());
                var drop=new ItemEntity(level,b.position().getX()+.5,b.position().getY()+1,b.position().getZ()+.5,stack.copyWithCount(count));
                if(level.addFreshEntity(drop)){stock.removeItem(i,count);SettlementSavedData.get(level).touch();return true;}
                return false;
            }
        }
        var target=BuildingDemolitionService.nextBlock(level,b);
        if(target==null) {
            s.finishBuildingDemolition(b.id());ProfessionAssignmentService.refresh(SettlementSavedData.get(level),s);
            SettlementSavedData.get(level).touch();return true;
        }
        if(!clearForEvolve(level,s,target) || !level.getEntitiesOfClass(LivingEntity.class,new AABB(target).expandTowards(0,2,0)).isEmpty())return false;
        if(BuildingDemolitionService.removeAt(level,b,target,null)){SettlementSavedData.get(level).touch();return true;}
        return false;
    }
    public static boolean cleanup(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        if(!ConstructionDebugService.workEnabled(level,s))return false;
        var next=BlueprintCancellationService.nextCleanup(level,site,Set.of());
        if(next!=null) {
            if(level.hasChunkAt(next.position()) && clearForEvolve(level,s,next.position())
                    && BlueprintCancellationService.removePlacedBlock(level,site,next.position(),null)) {
                SettlementSavedData.get(level).touch();return true;}
            return false;
        }
        if(!site.temporaryFootings().isEmpty())return false;
        if(site.terrainProject()==null && site.region()==null && site.productionSite()==null)
            for(var p:BlueprintTemplates.create(site).placements()) {
                if(!level.hasChunkAt(p.position()))return false;
                if(FarmEarthworkService.applies(site) && p.position().getY()<=site.anchor().getY()-1
                        && (FarmEarthworkPlan.soil(p.state())||p.mode()==BlueprintPlacementMode.FARM_TILL))continue;
                if(p.mode().isFinalStructure() && !p.state().isAir()
                        && BlueprintService.matchesPlacedState(level.getBlockState(p.position()),p.state()))return false;
            }
        s.removeBlueprint(site.id());SettlementSavedData.get(level).touch();return true;
    }
}

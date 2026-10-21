package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Planning never edits blocks. Preparation and boundary removal use the existing paid resident work chain. */
public final class ProductionRegionService {
    private ProductionRegionService() { }
    private static final Map<SettlementRecord,Integer> CURSORS=new WeakHashMap<>();
    private static final Map<ProductionRegion,Integer> IRRIGATION_CHECKS=new WeakHashMap<>();
    public static boolean supports(BuildingType t){return t==BuildingType.TREE_FARM || t==BuildingType.FARM_FIELD || t==BuildingType.MELON_FIELD || t==BuildingType.PASTURE_AREA;}
    /** Reuse the construction lifecycle: production starts only after the site is handed over. */
    public static boolean constructionPending(SettlementRecord s,BuildingRecord b){
        if(s==null || b==null || !supports(b.type()))return false;
        if(!b.repairPositions().isEmpty())return true;
        for(var site:s.formalBlueprints())if(site.region()!=null && !FarmConstructionPolicy.maintenance(s,site)
                && (site.region()==b.region() || b.id().equals(site.region().workingBuilding)))return true;
        for(var project:s.terrain().projects.values())if(!project.cancelled && project.remaining()>0 && !project.road && !project.bridge && !project.publicGround
                && (b.id().equals(project.regionOwner) || project.dependencies.contains(b.id()))
                && project.dependencies.stream().map(s::blueprint).noneMatch(site->FarmConstructionPolicy.maintenance(s,site)))return true;
        return false;
    }
    private static boolean overlaps(BlockPos min,BlockPos max,BlockPos first,BlockPos second){
        return Math.min(first.getX(),second.getX())<=max.getX() && Math.max(first.getX(),second.getX())>=min.getX()
                && Math.min(first.getZ(),second.getZ())<=max.getZ() && Math.max(first.getZ(),second.getZ())>=min.getZ();
    }
    public static OperationResult<BlueprintRecord> place(ServerLevel level,BuildingType type,BlockPos first,BlockPos second,Direction facing,UUID actor,ServerPlayer player) {
        var data=SettlementSavedData.get(level);var s=data.findContaining(first).orElse(null);
        if(s==null || !s.contains(second))return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        if(!s.isMember(actor))return OperationResult.failure("NO_PERMISSION");
        if(Math.abs(first.getX()-second.getX())+1<type.minimumAreaSize() || Math.abs(first.getZ()-second.getZ())+1<type.minimumAreaSize())return OperationResult.failure("AREA_TOO_SMALL");
        if(first.getY()-1<level.getMinBuildHeight() || first.getY()+6>=level.getMaxBuildHeight())return OperationResult.failure("AREA_HEIGHT_INVALID");
        second=second.atY(first.getY());
        for(var building:s.buildings())if(building.active() && overlaps(building.minCorner(),building.maxCorner(),first,second))return OperationResult.failure("PROTECTED_AREA");
        for(var planned:s.formalBlueprints())if(!planned.cancellationQueued()){
            var template=planned.region()==null?BlueprintTemplates.create(planned):null;
            if(overlaps(template==null?planned.areaMin():template.minCorner(),template==null?planned.areaMax():template.maxCorner(),first,second))return OperationResult.failure("PROTECTED_AREA");
        }
        var anchor=new BlockPos((first.getX()+second.getX())/2,first.getY(),(first.getZ()+second.getZ())/2);
        var marker=anchor;while(s.blueprintAt(marker)!=null)marker=marker.above();
        var site=s.addAreaBlueprint(type,anchor,marker,facing,actor,false,first,second);
        var region=new ProductionRegion(type,first,second,facing);
        region.rectangularSelection=type==BuildingType.TREE_FARM;
        site.withRegion(region);
        P0PlanningService.placed(level,s,site);data.touch();
        if(player!=null)player.displayClientMessage(Component.literal("已安排独立生产建筑；由工人按确认地坪施工，完工后投入生产，相邻区域不合并。"),false);
        return OperationResult.success(site);
    }
    private static boolean allowed(SettlementRecord s,BlueprintRecord own,BlockPos at) {
        if(!s.contains(at) || s.community().danger(at) || BuildingWorkPolicy.automaticForbidden(s,own,at))return false;
        for(var b:s.buildings())if(b.active() && !b.id().equals(own.region().workingBuilding) && b.containsColumn(at))return false;
        for(var b:s.blueprints())if(b!=own && !b.cancellationQueued() && b.region()!=null && b.containsColumn(at))return false;
        for(var b:s.blueprints())if(b!=own && !b.cancellationQueued() && b.region()==null && b.terrainProject()==null) {
            var t=BlueprintTemplates.create(b);if(at.getX()>=t.minCorner().getX() && at.getX()<=t.maxCorner().getX() && at.getZ()>=t.minCorner().getZ() && at.getZ()<=t.maxCorner().getZ())return false;
        }
        return true;
    }
    /** Reconcile task ownership, never reinterpret a public reservation as this building's authority. */
    public static boolean reconcileOwnership(SettlementRecord s,BlueprintRecord site) {
        var r=site.region();if(r==null || site.cancellationQueued() || r.groundMaintenance)return false;
        boolean changed=false;
        for(var project:List.copyOf(s.terrain().dependingOn(site.id()))) {
            if(project.cancelled || project.bridge || project.road || project.areaClearance || project.roadReclaim)continue;
            if(!project.publicGround) {
                var owner=RegionWorkScope.owner(s,site.id());
                if(!Objects.equals(project.regionOwner,owner)){project.regionOwner=owner;changed=true;}
                continue;
            }
            // A mixed public carrier keeps its out-of-area work and paid history. Only unpaid
            // positions inside this accepted plot return to its normal preparation publisher.
            var retry=new HashSet<Long>();
            for(int i=project.cursor;i<project.steps.size();i++)if(!project.finished.get(i) && !project.abandoned.get(i)) {
                var at=project.steps.get(i).position();if(r.cell(at)==null)continue;
                retry.add(ProductionRegion.key(at.getX(),at.getZ()));project.abandon(i);
                s.terrain().cells.remove(at,project.id);
            }
            project.dependencies.remove(site.id());
            changed=true;
            for(int i=0;i<r.ordered().size();i++)if(retry.contains(ProductionRegion.key(r.ordered().get(i).ground().getX(),r.ordered().get(i).ground().getZ())))r.preparationRetries.add(i);
            RegionConstructionStages.invalidate(s,site.id());
        }
        return changed;
    }
    public static void tickLevel(ServerLevel level) {
        ProductionSiteMigration.tickLevel(level);
    }
    /** Finish only the original read-only survey; never publish fresh region maintenance. */
    static boolean finishLegacySurvey(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        var r=site.region();
        if(!r.surveyed && !r.survey(level,at->allowed(s,site,at),32))return false;
        if(!r.layoutReady){refreshUnbuiltLayout(level,s,r);r.rebuildUses(level);r.layoutReady=true;}
        return true;
    }
    private static boolean farmNatural(net.minecraft.world.level.block.state.BlockState state) {
        return TerrainService.natural(state) || FarmEarthworkPlan.soil(state) || state.is(Blocks.DIRT_PATH) || state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }
    private static boolean farmClearable(ServerLevel level,SettlementRecord s,BlueprintRecord site,BlockPos p,net.minecraft.world.level.block.state.BlockState state){
        if(site.region().rectangularSelection && site.region().cell(p)!=null && state.getFluidState().isEmpty() && state.getDestroySpeed(level,p)>=0)return true;
        if(site.region().oldIrrigation.contains(p.asLong()) && (state.is(Blocks.OAK_SLAB) || TerrainService.replaceableWater(state)))return true;
        if(farmNatural(state) || FarmVegetation.leaf(state) || site.region().naturalTrees.contains(ProductionRegion.key(p.getX(),p.getZ())) && state.is(BlockTags.LOGS))return true;
        if(site.region().oldFences.contains(p.asLong()) && (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock || state.is(Blocks.COBBLESTONE_STAIRS) || state.is(Blocks.COBBLESTONE)))return true;
        for(var project:s.terrain().projectsAt(p))for(int i:project.indices(p))if(!project.abandoned.get(i) && project.steps.get(i).fill() && (i<project.cursor || project.finished.get(i)) && TerrainService.satisfied(state,project.steps.get(i).target()))return true;
        return false;
    }
    private static void refreshUnbuiltLayout(ServerLevel level,SettlementRecord s,ProductionRegion r) {
        if(r.plainSoil())return;
        // Unpaid water/path symbols from each original plot must not become fixed
        // irrigation anchors when the fixed-plane layout is initialized.
        var wet=new HashSet<Long>();
        for(var entry:List.copyOf(r.cells.entrySet())) {
            var c=entry.getValue();var state=level.getBlockState(c.ground());
            if(c.use()==ProductionRegion.Use.WATER && !level.getFluidState(c.ground()).is(net.minecraft.tags.FluidTags.WATER)
                    && !TerrainService.pendingAt(level,s,c.ground())) {
                r.cells.put(entry.getKey(),new ProductionRegion.Cell(c.ground(),ProductionRegion.Use.CROP,c.production(),c.type()));
                r.preserved.remove(entry.getKey());
            } else if(c.use()==ProductionRegion.Use.WATER)wet.add(entry.getKey());
            if(c.use()==ProductionRegion.Use.PATH && !state.is(Blocks.DIRT_PATH) && !state.is(Blocks.COBBLESTONE_STAIRS)
                    && !TerrainService.pendingAt(level,s,c.ground()))r.preserved.remove(entry.getKey());
        }
        r.waterBarriers.removeIf(k->{var c=r.cells.get(k);if(c==null)return true;
            return wet.stream().map(r.cells::get).noneMatch(w->w.ground().distManhattan(c.ground())<=1);});
        r.layoutVersion=0;r.invalidate();
    }
    private static void releaseObsoleteRegionOrders(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        var r=site.region();
        for(var project:s.terrain().projects.values())if(!project.cancelled && !project.completed
                && project.dependencies.contains(site.id()) && project.name.equals("区域局部土层与支撑")) {
            if(r.workingBuilding!=null)project.dependencies.add(r.workingBuilding);
            for(int i=0;i<project.steps.size();i++)if(!project.abandoned.get(i)) {
                var step=project.steps.get(i);var cell=r.cell(step.position());if(cell==null)continue;
                var goal=r.template().placementAt(step.position());
                if(r.plainSoil() && !step.fill() && goal!=null && !goal.state().isAir()
                        && BlueprintService.placementSatisfied(level,r.template(),goal)){
                    project.abandon(i);s.terrain().cells.remove(step.position(),project.id);continue;
                }
                boolean obsolete=(!step.fill() && step.position().getY()<=cell.ground().getY() && project.template().placementAt(step.position()).state().isAir()) || step.fill() && (step.position().getY()>cell.ground().getY()
                        || step.position().getY()<=cell.ground().getY() && !FarmEarthworkPlan.soil(step.target()))
                        && (goal==null || !TerrainService.satisfied(step.target(),goal.state()));
                if(obsolete){project.abandon(i);s.terrain().cells.remove(step.position(),project.id);}
            }
        }
    }
    private static void releasePaidObsoleteTargets(ServerLevel level,SettlementRecord s,List<TerrainProject.Step> steps){
        for(var step:steps){var owner=s.terrain().projects.get(s.terrain().cells.get(step.position()));if(owner==null || owner.cancelled || !owner.completed)continue;
            var goal=owner.template().placementAt(step.position());if(goal==null || goal.state().isAir() || !TerrainService.satisfied(level.getBlockState(step.position()),goal.state()))continue;
            if(step.target().isAir()){for(int i:owner.indices(step.position()))owner.abandon(i);s.terrain().cells.remove(step.position(),owner.id);}
        }
    }
    static void releaseCrossingRoadOrders(ServerLevel level,SettlementRecord s,ProductionRegion r) {
        // Older road dig orders must not cut a newly accepted field boundary or crop bed.
        for(var order:s.terrain().projects.values())if(order.road && !order.completed && !order.cancelled) {
            for(int i=order.cursor;i<order.steps.size();i++)if(!order.finished.get(i)) {
                var step=order.steps.get(i);var c=r.cell(step.position());
                if(c==null)continue;
                if(TerrainService.satisfied(level.getBlockState(step.position()),step.target()))order.mark(i);
                else {order.abandon(i);order.connectionWarning="原道路土方进入新农田，保留田地并暂缓该格";}
            }
        }
        for(var order:s.terrain().projects.values())if(!order.completed && !order.cancelled && order.name.equals("区域局部土层与支撑") && order.dependencies.contains(r.workingBuilding)) {
            var finals=order.template();
            for(int i=order.cursor;i<order.steps.size();i++)if(!order.finished.get(i)) {
                var step=order.steps.get(i);var c=r.cell(step.position());var finalGoal=finals.placementAt(step.position());
                if(c==null || finalGoal==null)continue;
                boolean obsolete=finalGoal.state().isAir() && step.position().getY()<=c.ground().getY()
                        || !finalGoal.state().isAir() && step.position().getY()>c.ground().getY()
                        || c.use()==ProductionRegion.Use.WATER && step.position().equals(c.ground()) && finalGoal.state().is(Blocks.DIRT);
                if(obsolete)order.abandon(i);
            }
        }
    }
    private static void preservePaidPaths(ServerLevel level,ProductionRegion r) {
        for(var c:List.copyOf(r.cells.values()))if((c.use()==ProductionRegion.Use.CROP || c.use()==ProductionRegion.Use.PATH) && !r.waterBarriers.contains(ProductionRegion.key(c.ground().getX(),c.ground().getZ()))) {
            long key=ProductionRegion.key(c.ground().getX(),c.ground().getZ());
            for(var at:List.of(c.ground(),c.ground().atY(r.gradeOrigins.getOrDefault(key,c.ground().getY()))))
                if(level.hasChunkAt(at) && level.getBlockState(at).is(Blocks.DIRT_PATH) && com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(at)) {
                    r.cells.put(key,new ProductionRegion.Cell(at,ProductionRegion.Use.PATH,c.production(),c.type()));r.gradeOrigins.remove(key);break;
                }
        }
        r.invalidate();
    }
    public static String conflict(SettlementRecord s,List<TerrainProject.Step> steps){return conflict(s,null,steps);}
    public static String conflict(SettlementRecord s,UUID dependency,List<TerrainProject.Step> steps) {
        var finals=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();steps.forEach(step->finals.put(step.position(),step.target()));
        for(var goal:finals.entrySet()) {
            var id=s.terrain().cells.get(goal.getKey());var project=id==null?null:s.terrain().projects.get(id);
            if(project==null || project.cancelled)continue;
            if(RegionWorkScope.publicOverlap(s,dependency,project,goal.getKey()))continue;
            if(dependency!=null && !project.completed && TerrainService.pendingAt(s,goal.getKey())
                    && !RegionWorkScope.same(s,project,RegionWorkScope.owner(s,dependency)))return "工程归属冲突："+project.name+" @ "+goal.getKey().toShortString();
            var existing=project.template().placementAt(goal.getKey());
            if(project.completed && existing!=null && existing.state().isAir())continue;
            if(project.name.equals("区域局部土层与支撑") && !TerrainService.pendingAt(s,goal.getKey()))continue;
            if(existing!=null && !TerrainService.satisfied(existing.state(),goal.getValue()) && !TerrainService.satisfied(goal.getValue(),existing.state()))
                return "工程目标冲突："+project.name+" @ "+goal.getKey().toShortString();
        }
        return "";
    }
    public static TerrainProject publish(ServerLevel level,SettlementRecord s,UUID dependency,String name,BlockPos anchor,List<TerrainProject.Step> steps) {
        return publish(level,s,dependency,name,anchor,steps,false);
    }
    public static TerrainProject publish(ServerLevel level,SettlementRecord s,UUID dependency,String name,BlockPos anchor,List<TerrainProject.Step> steps,boolean roadReclaim) {
        if(!conflict(s,dependency,steps).isEmpty()){var r=RegionWorkScope.region(s,dependency);if(r!=null)r.status=conflict(s,dependency,steps);else s.terrain().status=conflict(s,dependency,steps);return null;}
        UUID regionOwner=RegionWorkScope.owner(s,dependency);
        var filtered=new ArrayList<TerrainProject.Step>();var finals=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();steps.forEach(step->finals.put(step.position(),step.target()));
        var simulated=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var step:steps) {
            var owner=s.terrain().cells.get(step.position());
            if(owner!=null){var shared=s.terrain().projects.get(owner);if(shared!=null && !shared.cancelled && !shared.completed && TerrainService.pendingAt(s,step.position())){
                if(RegionWorkScope.publicOverlap(s,dependency,shared,step.position())){
                    // Release only superseded unpaid actions; paid world blocks and their evidence remain.
                    for(int i:shared.indices(step.position()))if(!shared.finished.get(i))shared.abandon(i);
                    s.terrain().cells.remove(step.position(),shared.id);
                }else if(RegionWorkScope.same(s,shared,regionOwner)){
                    shared.dependencies.add(dependency);if(regionOwner!=null){shared.regionOwner=regionOwner;shared.explicit=true;}continue;
                }else {var r=RegionWorkScope.region(s,dependency);String message="工程归属冲突："+shared.name+" @ "+step.position().toShortString();if(r!=null)r.status=message;else s.terrain().status=message;return null;}
            }}
            var current=simulated.getOrDefault(step.position(),level.getBlockState(step.position()));
            boolean restore=roadReclaim || obsoleteFarmPath(s,dependency,step.position(),current);
            if(!restore && !simulated.containsKey(step.position()) && TerrainService.satisfied(current,finals.get(step.position())))continue;
            if(restore && current.is(Blocks.DIRT_PATH) && step.target().is(Blocks.DIRT) || !TerrainService.satisfied(current,step.target())){filtered.add(step);simulated.put(step.position(),step.target());}
        }
        if(filtered.isEmpty())return null;
        filtered.sort(Comparator.comparingInt((TerrainProject.Step p)->p.fill()?1:0).thenComparingInt(p->p.fill()?p.position().getY():-p.position().getY()));
        var marker=anchor.above(96);while(s.blueprintAt(marker)!=null)marker=marker.above();
        var site=s.addBlueprint(BuildingType.HOUSE,anchor,marker,Direction.NORTH,s.ownerId(),false,1,Map.of());
        var project=new TerrainProject(site.id(),name,anchor,filtered);project.regionOwner=regionOwner;project.explicit=true;project.roadReclaim=roadReclaim;project.dependencies.add(dependency);var parent=s.blueprint(dependency);if(parent!=null && parent.region()!=null && parent.region().workingBuilding!=null)project.dependencies.add(parent.region().workingBuilding);site.withTerrainProject(project);s.terrain().index(project);RegionConstructionStages.invalidate(s,dependency);SettlementSavedData.get(level).touch();return project;
    }
    public static boolean obsoleteFarmPath(SettlementRecord s,UUID dependency,BlockPos at,net.minecraft.world.level.block.state.BlockState current){
        if(!current.is(Blocks.DIRT_PATH))return false;
        var site=s.blueprint(dependency);var building=s.building(dependency);var region=site!=null?site.region():building!=null?building.region():null;
        var cell=region==null?null:region.cell(at);
        return cell!=null && cell.ground().equals(at) && (cell.use()==ProductionRegion.Use.CROP || cell.use()==ProductionRegion.Use.FRUIT);
    }
    /** Complete only a repair of this exact building; never absorb a neighbouring area. */
    public static boolean maintenanceScopeUnchanged(SettlementRecord s,ProductionRegion r){
        if(r.workingBuilding==null)return true;
        var target=s.building(r.workingBuilding);var original=target==null?null:target.region();
        return target!=null && target.active() && original!=null && target.type()==r.type
                && original.planeY()==r.planeY() && original.cells.keySet().equals(r.cells.keySet())
                && r.cells.values().stream().allMatch(c->c.ground().getY()==original.planeY());
    }
    public static OperationResult<BuildingRecord> completeMaintenance(ServerLevel level,SettlementSavedData data,SettlementRecord s,BlueprintRecord site) {
        var r=site.region();var target=s.building(r.workingBuilding);
        if(!maintenanceScopeUnchanged(s,r) || target==null)
            return OperationResult.failure("REGION_MAINTENANCE_SCOPE_CHANGED");
        target.withRegion(r);retainAnimals(level,r);s.removeBlueprint(site.id());
        validate(level,target);data.touch();return OperationResult.success(target);
    }
    public static boolean matchesAnimal(BuildingRecord b,net.minecraft.world.entity.animal.Animal animal) {
        var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType());
        if(b.productionSite()!=null)return b.productionSite().productions().contains(id);
        return b.region()==null?BuildingProductionService.matchesAnimal(animal,b.productionId()):b.region().productions().contains(id);
    }
    public static boolean animalSurplus(BuildingRecord b,List<net.minecraft.world.entity.animal.Animal> animals,net.minecraft.world.entity.animal.Animal animal) {
        // A newborn is not a replacement breeding adult. Keep two adults even
        // while the one permitted surplus birth grows to its vanilla adult age.
        if(animal.isBaby() || animals.stream().filter(a->a.isAlive() && !a.isBaby()
                && a.getType()==animal.getType()).count()<=2)return false;
        if(b.productionSite()!=null){
            var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType());
            long area=b.productionSite().workCells().stream().filter(c->c.use()==ProductionRegion.Use.PASTURE && c.production().equals(id)).count();
            int quota=Math.max(b.productionSite().animalFloor(id),Math.max(2,(int)(area/8)));
            return animals.stream().filter(a->a.getType()==animal.getType()).count()>quota;
        }
        if(b.region()==null)return animals.size()>BuildingProductionService.animalCapacity(b);
        var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType());
        long area=b.region().ordered().stream().filter(c->c.use()==ProductionRegion.Use.PASTURE && b.region().readyPasture.contains(c.ground().asLong()) && c.production().equals(id)).count();
        int quota=Math.max(b.region().animalFloors.getOrDefault(id,0),Math.max(2,(int)(area/8)));
        return animals.stream().filter(a->a.getType()==animal.getType()).count()>quota;
    }
    private static void retainAnimals(ServerLevel level,ProductionRegion r) {
        if(r.type!=BuildingType.PASTURE_AREA)return;
        var box=new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(r.min()),net.minecraft.world.phys.Vec3.atLowerCornerOf(r.max().offset(1,1,1)));
        var counts=new HashMap<net.minecraft.resources.ResourceLocation,Integer>();
        for(var animal:level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,box,a->a.isAlive() && r.contains(a.blockPosition())))
            counts.merge(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()),1,Integer::sum);
        counts.forEach((id,n)->r.animalFloors.merge(id,n,Math::max));
    }
    public static boolean validate(ServerLevel level,BuildingRecord b) {
        var r=b.region();if(r==null || !r.surveyed)return false;
        var settlement=SettlementSavedData.get(level).findContaining(b.position()).orElse(null);
        if(constructionPending(settlement,b)){
            r.readyCrops.clear();r.readyForest.clear();r.readyPasture.clear();
            return b.updateFunction(false,BuildingStatus.UNDER_CONSTRUCTION,b.workerCapacity(),List.of());
        }
        BlueprintBlockStateResolver.refreshCompleted(level,r.template());
        var ground=new ArrayList<BlockPos>();var gates=new ArrayList<BlockPos>();boolean closed=true;
        for(var p:r.template().placements())if(p.state().is(Blocks.OAK_FENCE) || p.state().is(Blocks.OAK_FENCE_GATE)) {
            if(!level.hasChunkAt(p.position()) || !BlueprintService.matchesPlacedState(level.getBlockState(p.position()),p.state()))closed=false;
            else if(p.state().is(Blocks.OAK_FENCE_GATE))gates.add(p.position());
        }
        r.readyCrops.clear();r.readyForest.clear();
        if(r.type==BuildingType.TREE_FARM){
            for(var c:r.cells.values())if(c.use()==ProductionRegion.Use.FOREST && r.groundReady(level,c)
                    && RegionGroundService.plantReady(level,b,c.ground().above())){r.readyForest.add(c.ground().asLong());ground.add(c.ground().above());}
            boolean ready=!ground.isEmpty();
            return b.updateFunction(ready,ready?BuildingStatus.OPERATIONAL:BuildingStatus.REGISTERED_ONLY,Math.max(1,Math.min(8,r.usableArea()/100)),ground);
        }
        var accessible=r.agricultural()?FarmConnectivity.accessible(level,r):Set.<Long>of();
        for(var c:r.cells.values())if(c.use()==ProductionRegion.Use.CROP && level.hasChunkAt(c.ground())
                && accessible.contains(ProductionRegion.key(c.ground().getX(),c.ground().getZ()))
                && (level.getBlockState(c.ground()).is(Blocks.FARMLAND)
                    || r.prepared && FarmEarthworkPlan.soil(level.getBlockState(c.ground()))
                    && settlement!=null && !TerrainService.pendingAt(level,settlement,c.ground())
                    && !TerrainService.pendingAt(level,settlement,c.ground().below())
                    && !TerrainService.pendingAt(level,settlement,c.ground().above()))
                && FarmEarthworkPlan.support(level,c.ground().below())){ground.add(c.ground());r.readyCrops.add(c.ground().asLong());}
        r.readyPasture.clear();
        if(!r.agricultural() && closed && !gates.isEmpty()) {
            var seen=new HashSet<Long>();var queue=new ArrayDeque<ProductionRegion.Cell>();
            for(var c:r.cells.values())if(c.use()==ProductionRegion.Use.GATE){queue.add(c);seen.add(ProductionRegion.key(c.ground().getX(),c.ground().getZ()));}
            while(!queue.isEmpty()) {
                var c=queue.removeFirst();if(c.use()==ProductionRegion.Use.PASTURE && r.groundReady(level,c) && !RegionGroundService.pendingColumn(level,settlement,c.ground()) && level.getBlockState(c.ground().above()).getCollisionShape(level,c.ground().above()).isEmpty() && level.getFluidState(c.ground().above()).isEmpty())r.readyPasture.add(c.ground().asLong());
                for(var d:Direction.Plane.HORIZONTAL){var n=r.cell(c.ground().relative(d));if(n==null || n.use()==ProductionRegion.Use.BOUNDARY || Math.abs(n.ground().getY()-c.ground().getY())>1 || !level.hasChunkAt(n.ground()) || !level.getBlockState(n.ground().above()).getCollisionShape(level,n.ground().above()).isEmpty() || !level.getBlockState(n.ground().above(2)).getCollisionShape(level,n.ground().above(2)).isEmpty() || !level.getFluidState(n.ground().above()).isEmpty())continue;
                    long key=ProductionRegion.key(n.ground().getX(),n.ground().getZ());if(seen.add(key))queue.add(n);
                }
            }
        }
        boolean ready=r.agricultural()?!ground.isEmpty():closed && !gates.isEmpty() && !r.readyPasture.isEmpty();
        int capacity=Math.max(1,(r.agricultural()?ground.size()+63:r.readyPasture.size()+127)/(r.agricultural()?64:128));
        return b.updateFunction(ready,ready?BuildingStatus.OPERATIONAL:BuildingStatus.REGISTERED_ONLY,capacity,r.agricultural()?ground:gates);
    }
}


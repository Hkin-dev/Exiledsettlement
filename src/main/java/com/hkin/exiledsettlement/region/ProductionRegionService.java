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
        var data=SettlementSavedData.get(level);
        for(var s:data.settlements())if(s.active()) {
            if(FarmConstructionPolicy.stopAutomaticMaintenance(s))data.touch();
            for(var pending:s.blueprints())if(pending.region()!=null && !maintenanceScopeUnchanged(s,pending.region())){
                pending.region().groundPaused=true;pending.region().status="维修范围或高度与原建筑不一致，保留工程等待处理";data.touch();
            }
            RegionGroundService.tick(level,s);
            // Established farms no longer publish automatic worker redesign/upgrade jobs.
            for(var pending:List.copyOf(s.blueprints()))if(pending.region()!=null && pending.region().surveyed && pending.region().levelled() && !pending.cancellationQueued() && !FarmConstructionPolicy.maintenance(s,pending)) {
                if(pending.region().groundPaused)continue;
                if(level.getGameTime()%100==0){if(reconcileOwnership(s,pending))data.touch();releaseObsoleteRegionOrders(level,s,pending);FarmEntranceService.plan(level,s,pending);}
                var working=s.building(pending.region().workingBuilding);
                if(working!=null && level.getGameTime()%100==0){releaseCrossingRoadOrders(level,s,pending.region());}
            }
            // Recheck only irrigation walls belonging to an active selected region.
            // A previously cleared wall may already be water-filled; repair it through
            // a paid earthwork order instead of leaving a generic placement blocked.
            // Irrigation repairs may publish another blueprint during this pass.
            for(var pending:List.copyOf(s.blueprints()))if(pending.region()!=null && pending.region().surveyed && !pending.cancellationQueued() && !FarmConstructionPolicy.maintenance(s,pending)) {
                var r=pending.region();if(r.groundPaused)continue;var ordered=r.ordered();if(ordered.isEmpty())continue;
                int cursor=IRRIGATION_CHECKS.getOrDefault(r,0);IRRIGATION_CHECKS.put(r,(cursor+8)%ordered.size());
                for(int work=0;work<Math.min(8,ordered.size());work++) {
                    int i=(cursor+work)%ordered.size();
                    var cell=ordered.get(i);var p=cell.ground();
                    if(r.agricultural() && r.flatY!=null) {
                        int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())-1;
                        for(int y=r.flatY+1;y<=top;y++) {
                            var wet=p.atY(y);var wetState=level.getBlockState(wet);
                            if(wetState.getFluidState().isSource() && wetState.getBlock() instanceof BucketPickup
                                    && (wetState.is(Blocks.WATER) || farmClearable(level,s,pending,wet,wetState)) && allowed(s,pending,wet)) {
                                if(r.oldIrrigation.add(wet.asLong()))data.touch();
                                if(!TerrainService.pendingAt(level,s,wet)) {
                                    var order=publish(level,s,pending.id(),"农业旧灌溉回收",wet,List.of(new TerrainProject.Step(wet,Blocks.AIR.defaultBlockState(),wetState)));
                                    if(order!=null && r.workingBuilding!=null)order.dependencies.add(r.workingBuilding);
                                }
                            }
                        }
                    }
                    long key=ProductionRegion.key(p.getX(),p.getZ());
                    if(cell.use()==ProductionRegion.Use.CROP && level.hasChunkAt(p) && level.getBlockState(p).is(Blocks.FARMLAND)
                            && FarmEarthworkPlan.support(level,p.below()) && r.cultivated.add(p.asLong()))data.touch();
                    if((cell.use()==ProductionRegion.Use.BOUNDARY || cell.use()==ProductionRegion.Use.GATE) && level.hasChunkAt(p)) {
                        if(!FarmEarthworkPlan.soil(level.getBlockState(p)) || !FarmEarthworkPlan.support(level,p.below()))r.preparationRetries.add(i);
                        // Flowing irrigation can enter a future retaining wall above
                        // the soil. Ordinary placement cannot fill it; use paid,
                        // supported earthwork just like the lower foundation.
                        var wetWall=new ArrayList<TerrainProject.Step>();
                        for(int y=p.getY()+1;y<=r.max().getY();y++) {
                            var at=p.atY(y);var goal=r.template().placementAt(at);
                            if(goal!=null && goal.state().is(Blocks.COBBLESTONE) && TerrainService.replaceableWater(level.getBlockState(at))
                                    && !TerrainService.pendingAt(level,s,at) && allowed(s,pending,at))
                                wetWall.add(new TerrainProject.Step(at,goal.state(),level.getBlockState(at)));
                        }
                        if(!wetWall.isEmpty()) {
                            var project=publish(level,s,pending.id(),"农田围栏水边支撑",p,wetWall);
                            if(project!=null && r.workingBuilding!=null)project.dependencies.add(r.workingBuilding);
                        }
                    }
                    boolean barrier=r.waterBarriers.contains(key);
                    boolean soilCell=cell.use()==ProductionRegion.Use.CROP || cell.use()==ProductionRegion.Use.FRUIT || cell.use()==ProductionRegion.Use.PATH;
                    if(!barrier && !soilCell || !level.hasChunkAt(p) || TerrainService.pendingAt(level,s,p) || TerrainService.exitInUse(level,s,p))continue;
                    var actual=level.getBlockState(p);
                    var finalGround=r.template().placementAt(p);
                    if(finalGround!=null && BlueprintService.placementSatisfied(level,r.template(),finalGround))continue;
                    if(FarmEarthworkPlan.soil(actual) && (!barrier || actual.isFaceSturdy(level,p,Direction.UP)))continue;
                    // Excavation or receding water can expose an already scanned column.
                    // Requeue its real support/fill work; never replace a later solid facility.
                    if(!barrier && !actual.isAir() && !TerrainService.replaceableWater(actual) && !obsoleteFarmPath(s,pending.id(),p,actual))continue;
                    if(r.preparationRetries.add(i))data.touch();
                }
            }
            for(var cancelled:List.copyOf(s.blueprints()))if(cancelled.region()!=null && cancelled.cancellationQueued()) {
                var region=cancelled.region();
                for(var project:s.terrain().dependingOn(cancelled.id()))if(project.dependencies.remove(cancelled.id())) {
                    project.dependencies.remove(region.workingBuilding);
                    if(project.dependencies.isEmpty()){project.cancelled=true;var work=s.blueprint(project.id);if(work!=null)work.queueCancellation();}
                }
            }
            var candidates=s.blueprints().stream().filter(b->b.region()!=null && !b.cancellationQueued() && !FarmConstructionPolicy.maintenance(s,b) && (!b.region().prepared || !b.region().preparationRetries.isEmpty()) && (!b.region().surveyed || b.region().waterSurveyDone)).toList();
            if(candidates.isEmpty())continue;
            int cursor=CURSORS.getOrDefault(s,0);CURSORS.put(s,cursor+1);var site=candidates.get(Math.floorMod(cursor,candidates.size()));
            var r=site.region();
            if(r.groundPaused || r.groundMaintenance && !r.plainSoil() && (!s.terrain().enabled || s.terrain().publicPaused)){r.status="地面维护已暂缓，保留现场及安全围合";continue;}
            if(!r.surveyed){r.survey(level,at->allowed(s,site,at),32);data.touch();if(!r.surveyed)continue;PublicGroundService.requestReview(s);}
            if(!r.layoutReady) {
                // Each accepted selection remains independent, even when directly adjacent.
                refreshUnbuiltLayout(level,s,r);r.rebuildUses(level);r.layoutReady=true;PublicGroundService.requestReview(s);
            }
            if(level.getGameTime()%100==0)releaseObsoleteRegionOrders(level,s,site);
            if(r.cells.isEmpty()){r.status="选区没有可用的真实陆地";continue;}
            if(r.plainSoil() && !PastureGroundSafety.prepare(level,s,site))continue;
            int budget=8;var batch=new ArrayList<TerrainProject.Step>();
            while((r.preparationCursor<r.ordered().size() || !r.preparationRetries.isEmpty()) && budget-->0) {
                int cellIndex=r.preparationCursor<r.ordered().size()?r.preparationCursor++:r.preparationRetries.iterator().next();
                r.preparationRetries.remove(cellIndex);var cell=r.ordered().get(cellIndex);var at=cell.ground();
                if(!level.hasChunkAt(at)){r.status="等待正常加载地形："+at.toShortString();r.preparationRetries.add(cellIndex);continue;}
                if(!allowed(s,site,at)){r.preparationRetries.add(cellIndex);r.status="局部准备被设施、领地或禁放区阻挡："+at.toShortString();continue;}
                boolean usingExit=s.terrain().projects.values().stream().anyMatch(p->p.escapeFor!=null && p.dependencies.contains(site.id())
                        && p.accessSurfaces.stream().anyMatch(surface->surface.getX()==at.getX() && surface.getZ()==at.getZ())
                        && s.citizens().stream().anyMatch(c->c.alive() && p.accessSurfaces.stream().anyMatch(surface->surface.getX()==c.lastPosition().getX()
                        && surface.getZ()==c.lastPosition().getZ()) && c.lastPosition().getY()<=at.getY()));
                if(usingExit){r.preparationRetries.add(cellIndex);r.status="等待居民走出临时施工出口："+at.toShortString();continue;}
                String wait=RegionGroundService.columnWait(level,s,site,at);
                if(!wait.isEmpty()){r.status=wait;r.groundBlockers.put(ProductionRegion.key(at.getX(),at.getZ()),wait);r.preparationRetries.add(cellIndex);continue;}
                r.groundBlockers.remove(ProductionRegion.key(at.getX(),at.getZ()));
                List<TerrainProject.Step> steps=new ArrayList<>();
                if(r.levelled() || r.gradeOrigins.containsKey(ProductionRegion.key(at.getX(),at.getZ()))) {
                    int worldTop=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,at.getX(),at.getZ())-1;
                    int originalY=at.getY();
                    while(originalY<worldTop && farmNatural(level.getBlockState(at.atY(originalY+1)))
                            && FarmEarthworkPlan.support(level,at.atY(originalY+1)))originalY++;
                    int top=RegionConstructionStages.applies(r)?worldTop:RegionGroundService.clearanceTop(r,at.getY(),worldTop);
                    boolean blocked=false;
                    for(int y=top;y>at.getY();y--) {
                        var p=at.atY(y);var current=level.getBlockState(p);
                        if(current.isAir())continue;
                        if(RegionConstructionStages.applies(r) && y>Math.max(at.getY()+RegionGroundService.clearanceHeight(r),originalY)
                                && !(FarmVegetation.leaf(current) || r.naturalTrees.contains(ProductionRegion.key(p.getX(),p.getZ())) && current.is(BlockTags.LOGS)))continue;
                        var placedGoal=r.plainSoil()?r.template().placementAt(p):null;
                        if(placedGoal!=null && !placedGoal.state().isAir() && BlueprintService.placementSatisfied(level,r.template(),placedGoal))continue;
                        if(r.type==BuildingType.TREE_FARM && RegionGroundService.keepTree(level,r,at,current,p))continue;
                        if(!current.getFluidState().isEmpty() && current.getBlock() instanceof BucketPickup && farmClearable(level,s,site,p,current)) {r.preparationRetries.add(cellIndex);blocked=true;break;}
                        // Seal the real irrigation barrier first. Flowing water
                        // above crops recedes naturally and is not a solid obstacle.
                        if(TerrainService.replaceableWater(current))continue;
                        if(!farmClearable(level,s,site,p,current) || !allowed(s,site,p)){blocked=true;break;}
                        steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),current));
                    }
                    if(blocked){r.status="台面修整保留已有设施："+at.toShortString();r.preparationRetries.add(cellIndex);continue;}
                }
                // Agriculture uses the saved common plane; every column retains continuous real support.
                if(r.levelled() || cell.use()!=ProductionRegion.Use.PASTURE || r.waterFill.contains(ProductionRegion.key(at.getX(),at.getZ()))) {
                    int bottom=at.getY()-1;
                    while(bottom>=level.getMinBuildHeight() && !FarmEarthworkPlan.support(level,at.atY(bottom))) {
                        var p=at.atY(bottom);var state=level.getBlockState(p);
                        if(!TerrainService.replaceableWater(state) && (!state.getFluidState().isEmpty() && !TerrainService.clearableWetNatural(state) || !farmNatural(state)) || !allowed(s,site,p)){r.status="局部支撑被阻挡："+p.toShortString();bottom=Integer.MIN_VALUE;break;}
                        if(!state.isAir() && !TerrainService.replaceableWater(state))steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),state));bottom--;
                    }
                    if(bottom<level.getMinBuildHeight()){r.status="局部支撑尚不可施工："+at.toShortString();r.preparationRetries.add(cellIndex);continue;}
                    for(int y=bottom+1;y<at.getY();y++){var p=at.atY(y);var current=level.getBlockState(p);steps.add(new TerrainProject.Step(p,Blocks.DIRT.defaultBlockState(),TerrainService.replaceableWater(current)?current:Blocks.AIR.defaultBlockState()));}
                    var state=level.getBlockState(at);
                    if(cell.use()!=ProductionRegion.Use.WATER && !FarmEarthworkPlan.soil(state) && !(cell.use()==ProductionRegion.Use.PATH && (state.is(Blocks.DIRT_PATH) || BlueprintService.matchesPlacedState(state,r.template().placementAt(at).state())))) {
                        if(TerrainService.replaceableWater(state)) {
                            r.waterFill.add(ProductionRegion.key(at.getX(),at.getZ()));
                            steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),state));
                        }else {
                        if(!farmClearable(level,s,site,at,state)){r.status="保留表面设施："+at.toShortString();r.preparationRetries.add(cellIndex);continue;}
                        steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state));steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
                        }
                    }
                    if(cell.use()==ProductionRegion.Use.WATER && !r.legacyWater.contains(ProductionRegion.key(at.getX(),at.getZ())) && (!level.getBlockState(at).is(Blocks.OAK_SLAB) || level.getBlockState(at).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)!=net.minecraft.world.level.block.state.properties.SlabType.TOP)) {
                        var current=level.getBlockState(at);
                        if(!current.isAir() && !TerrainService.replaceableWater(current))steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),current));
                        steps.add(new TerrainProject.Step(at,Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP),TerrainService.replaceableWater(current)?current:Blocks.AIR.defaultBlockState()));
                    }
                    for(int h=2;h>=1;h--) {
                        var p=at.above(h);var stateAbove=level.getBlockState(p);
                        if(r.type==BuildingType.TREE_FARM && stateAbove.getBlock() instanceof SaplingBlock && r.groundReady(level,cell))continue;
                        if(cell.use()==ProductionRegion.Use.PATH?farmNatural(stateAbove) && !stateAbove.isAir():stateAbove.getBlock() instanceof BushBlock && !(stateAbove.getBlock() instanceof CropBlock) && !(stateAbove.getBlock() instanceof StemBlock))steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),stateAbove));
                    }
                }
                if(!steps.isEmpty()) {
                    releasePaidObsoleteTargets(level,s,steps);
                    String conflict=conflict(s,site.id(),steps);
                    if(!conflict.isEmpty()){r.status=conflict;r.preparationRetries.add(cellIndex);continue;}
                    batch.addAll(steps);
                }
                data.touch();
            }
            if(!batch.isEmpty()) {
                var project=publish(level,s,site.id(),"区域局部土层与支撑",batch.getFirst().position(),batch);
                if(project!=null && r.workingBuilding!=null)project.dependencies.add(r.workingBuilding);
            }
            if(r.preparationCursor>=r.ordered().size()){r.prepared=true;if(r.preparationRetries.isEmpty())r.status="准备工单已安排，等待工人完成建筑后投产";data.touch();}
        }
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
        return b.region()==null?BuildingProductionService.matchesAnimal(animal,b.productionId()):b.region().productions().contains(id);
    }
    public static boolean animalSurplus(BuildingRecord b,List<net.minecraft.world.entity.animal.Animal> animals,net.minecraft.world.entity.animal.Animal animal) {
        if(b.region()==null)return animals.size()>BuildingProductionService.animalCapacity(b);
        var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType());
        long area=b.region().ordered().stream().filter(c->c.use()==ProductionRegion.Use.PASTURE && b.region().readyPasture.contains(c.ground().asLong()) && c.production().equals(id)).count();
        int quota=Math.max(b.region().animalFloors.getOrDefault(id,0),Math.max(1,(int)(area/8)));
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


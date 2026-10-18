package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Bounded planning only. Every world edit is executed by a physically present resident. */
public final class TerrainService {
    private static final Map<ServerLevel,Map<BlockPos,Long>> REMOVALS=new WeakHashMap<>();
    private static final Map<ServerLevel,Long> LAST_SURVEY=new WeakHashMap<>();
    public static final int LAYOUT_VERSION=4;
    private TerrainService(){}
    public static boolean exitInUse(ServerLevel level,SettlementRecord settlement,BlockPos at) {
        for(var project:settlement.terrain().projectsAt(at))if(!project.cancelled && project.escapeFor!=null && project.escapingCitizen!=null) {
            var citizen=settlement.citizen(project.escapingCitizen);
            var entity=citizen==null?null:com.hkin.exiledsettlement.citizen.CitizenProjectionService.findProjection(level,citizen);
            if(entity!=null && entity.isAlive() && (entity.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(project.escapeFor)) || entity.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(at))))return true;
        }
        return false;
    }
    public static boolean replaceableWater(BlockState state) {
        return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && state.canBeReplaced() && !state.hasBlockEntity();
    }
    public static boolean canFillWater(TerrainProject.Step step,BlockState current) {
        return replaceableWater(current) && (step.target().is(Blocks.DIRT) || step.target().is(Blocks.COBBLESTONE) || step.target().is(Blocks.OAK_SLAB) && step.target().getValue(net.minecraft.world.level.block.SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.TOP);
    }
    public static boolean canFillWater(TerrainProject project,TerrainProject.Step step,BlockState current){
        return canFillWater(step,current) || project!=null && project.playerBridge!=null && project.playerBridge.buildingModel
                && step.fill() && step.position().getY()<project.playerBridge.surface && replaceableWater(current)
                && (project.bridgeBeams.contains(step.position()) || project.bridgeDeck.contains(step.position()))
                && Block.isShapeFullBlock(step.target().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,step.position()));
    }
    public static boolean clearableWetNatural(BlockState state) {
        return state.is(Blocks.POINTED_DRIPSTONE) && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !state.hasBlockEntity();
    }
    public static boolean canClearWetNatural(TerrainProject.Step step,BlockState current) {
        return !step.fill() && step.target().isAir() && current.is(Blocks.POINTED_DRIPSTONE)
                && step.original().is(Blocks.POINTED_DRIPSTONE) && !current.hasBlockEntity();
    }
    private record ColumnFaces(BuildingBlueprintTemplate template,Map<Long,Integer> tops) {}
    private static final Map<TerrainProject,ColumnFaces> FILL_FACES=new WeakHashMap<>();
    /** Registered dry voids use the column's surface; never survey or fill neighbouring cave space. */
    public static BlockPos groundFillSurface(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        if(project.cancelled || project.bridge || project.areaClearance || project.roadReclaim
                || project.escapeFor!=null || !FoundationPlacement.applies(project,step))return null;
        var at=step.position();
        if(!level.hasChunkAt(at) || !level.getBlockState(at).isAir())return null;
        var template=project.template();var goal=template.placementAt(at);
        if(goal==null || !goal.state().equals(step.target()))return null;
        var cached=FILL_FACES.get(project);
        if(cached==null || cached.template()!=template) {
            var tops=new HashMap<Long,Integer>();
            for(var placement:template.placements())if(placement.state().is(Blocks.DIRT) || placement.state().is(Blocks.COBBLESTONE)) {
                var p=placement.position();tops.merge(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY(),Math::max);
            }
            cached=new ColumnFaces(template,tops);FILL_FACES.put(project,cached);
        }
        int top=cached.tops().getOrDefault(BlockPos.asLong(at.getX(),0,at.getZ()),at.getY());
        int actualTop=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
        var current=level.getBlockState(at.atY(actualTop));
        boolean actualSurface=actualTop<=top+2 && current.getFluidState().isEmpty() && !current.hasBlockEntity()
                && (current.is(BlockTags.DIRT) || current.is(BlockTags.BASE_STONE_OVERWORLD)
                    || current.is(Blocks.COBBLESTONE) || current.is(Blocks.DIRT_PATH))
                && FarmEarthworkPlan.support(level,at.atY(actualTop));
        // The future grade is not a workface. An intact low roof remains usable
        // before the rest of its column has been raised to that grade.
        if(actualSurface && actualTop>at.getY())return at.atY(actualTop);
        // Open pits use an existing nearby bank, never a promised fill cap.
        // Without a deep bank the ordinary close interaction handles the next
        // block above today's footing, then follows the rising real ground.
        Integer bank=null;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(dx*dx+dz*dz>0 && dx*dx+dz*dz<=4){
            var neighbour=at.offset(dx,0,dz);if(!level.hasChunkAt(neighbour))continue;
            int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,neighbour.getX(),neighbour.getZ())-1;
            if(y-at.getY()<3)continue;
            var ground=neighbour.atY(y);var state=level.getBlockState(ground);
            if(state.getFluidState().isEmpty() && !state.hasBlockEntity()
                    && (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                        || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DIRT_PATH))
                    && FarmEarthworkPlan.support(level,ground) && (bank==null || y<bank))bank=y;
        }
        return bank==null?null:at.atY(bank);
    }
    public static BlockPos fillSurfaceWorkFace(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        var tree=com.hkin.exiledsettlement.region.RegionConstructionStages.treeWorkFace(level,project,step);
        if(tree!=null)return tree;
        var ground=groundFillSurface(level,project,step);
        return ground!=null?ground:waterFillSurface(level,project,step);
    }
    /** A visible ground work face above the same supported fill column, never a water-bottom station. */
    public static BlockPos waterFillSurface(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        var at=step.position();
        var current=level.getBlockState(at);
        boolean drainedFill=step.fill() && current.isAir() && replaceableWater(step.original());
        if(step.fill() && current.isAir() && !drainedFill) {
            for(var settlement:SettlementSavedData.get(level).settlements())for(var id:project.dependencies) {
                var building=settlement.building(id);var blueprint=settlement.blueprint(id);
                var region=building!=null?building.region():blueprint!=null?blueprint.region():null;
                if(region!=null && region.waterFill.contains(com.hkin.exiledsettlement.region.ProductionRegion.key(at.getX(),at.getZ())))drainedFill=true;
            }
        }
        if(!canFillWater(step,current) && !canClearWetNatural(step,current) && !drainedFill)return null;
        int y=at.getY();
        for(var goal:project.steps)if(goal.fill() && goal.position().getX()==at.getX() && goal.position().getZ()==at.getZ())y=Math.max(y,goal.position().getY());
        int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
        var face=at.atY(top);var state=level.getBlockState(face);
        while(top>level.getMinBuildHeight() && (state.getBlock() instanceof BushBlock || state.is(Blocks.SNOW) || state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock)){face=at.atY(--top);state=level.getBlockState(face);}
        int upper=project.template().maxCorner().getY()+2;
        for(var s:SettlementSavedData.get(level).settlements())for(var id:project.dependencies) {
            var b=s.building(id);var cell=b==null || b.region()==null?null:b.region().cell(at);
            if(cell!=null)upper=Math.max(upper,cell.ground().getY()+2);
        }
        if(top<=upper && (FarmEarthworkPlan.soil(state) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COBBLESTONE)))y=Math.max(y,top);
        return at.atY(y);
    }
    public static boolean owned(BlueprintRecord site){return site!=null && site.terrainProject()!=null;}
    public static boolean natural(BlockState state) {
        return state.isAir() || state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.SNOW)
                || state.getBlock() instanceof BushBlock || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE);
    }
    public static boolean soft(BlockState state){return state.is(Blocks.SNOW)||state.getBlock() instanceof BushBlock;}
    public static boolean satisfied(BlockState current,BlockState target) {
        return BlueprintService.matchesPlacedState(current,target) || target.is(Blocks.DIRT) && (FarmEarthworkPlan.soil(current) && !current.is(Blocks.FARMLAND) || current.is(Blocks.DIRT_PATH));
    }
    private static boolean clearable(ServerLevel level,SettlementRecord s,UUID dependency,BlockPos at,BlockState state) {
        var parent=s.blueprint(dependency);
        var owner=s.terrain().projects.get(s.terrain().cells.get(at));
        var paid=owner==null?null:owner.template().placementAt(at);
        return natural(state) || parent!=null && parent.terrainProject()!=null && parent.terrainProject().road
                && com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,at) || state.is(Blocks.DIRT_PATH) && com.hkin.exiledsettlement.road.AdaptiveRoadService.automaticAt(s,at)
                && !com.hkin.exiledsettlement.road.RoadSpaceData.get(level).manual(at)
                && com.hkin.exiledsettlement.road.RoadSpaceData.get(level).levelAt(at)<=1
                || replaceableWater(state) || parent!=null && parent.hasTemporaryFooting(at)
                || owner!=null && owner.completed && paid!=null && satisfied(state,paid.state());
    }
    public static boolean allowed(SettlementRecord s,BlueprintRecord site) {
        if(!com.hkin.exiledsettlement.region.FarmConstructionPolicy.workerMayBuild(s,site))return false;
        if(site.region()!=null && (site.region().groundPaused || !com.hkin.exiledsettlement.region.ProductionRegionService.maintenanceScopeUnchanged(s,site.region())))return false;
        if(site.region()!=null && (!site.region().surveyed || !site.region().layoutReady))return false;
        if(site.region()!=null)return !s.community().evacuation && com.hkin.exiledsettlement.region.RegionConstructionStages.facilitiesReady(s,site);
        if(!owned(site))return true; // Arrival-based building construction has no foundation scheduling gate.
        var p=site.terrainProject();
        if(p.playerBridge!=null && p.playerBridge.paused)return false;
        if(!com.hkin.exiledsettlement.region.RegionWorkScope.allowed(s,p))return false;
        if(!com.hkin.exiledsettlement.road.BridgeService.workPermitted(s,p))return false;
        if(p.publicGround && p.publicGroundVersion<PublicGroundService.HEIGHT_VERSION)return false;
        return !p.cancelled && !s.community().evacuation
                && (p.explicit || !s.terrain().publicPaused && (p.road?s.autoRoads().enabled:p.publicGround?s.terrain().enabled:s.terrain().enabled || s.autoRoads().enabled) || p.dependencies.stream().anyMatch(id->s.blueprint(id)!=null));
    }
    public static boolean needsFoundation(SettlementRecord s,BlueprintRecord site) {
        boolean production=site.region()==null && (site.productionSite()!=null || BlueprintTemplates.fixedProduction(site.type(),site.templateVersion()));
        return !owned(site) && site.type()!=BuildingType.MINE && (!site.type().isDynamicArea() || production)
                && !com.hkin.exiledsettlement.decoration.DecorationService.owned(s,site.id());
    }
    /** Established production sites maintain facilities, never natural working soil. */
    public static boolean productionStructure(BuildingRecord building,BlockPos at) {
        if(building==null || at==null || building.region()!=null
                || building.productionSite()==null && !BlueprintTemplates.fixedProduction(building.type(),building.templateVersion()))return false;
        var placement=BlueprintTemplates.createForBuilding(building).placementAt(at);
        if(placement==null || placement.mode()!=BlueprintPlacementMode.STRUCTURE || placement.state().isAir())return false;
        if(building.productionSite()!=null)return building.productionSite().structural(at);
        var state=placement.state();
        return !FarmEarthworkPlan.soil(state) && !state.is(BlockTags.DIRT) && !state.is(Blocks.MOSS_BLOCK)
                && !state.is(Blocks.MUD) && !state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }
    /** The scheduling priority uses the exact same base columns as foundation planning. */
    public static boolean foundationAt(BlueprintRecord site,BlockPos at) {
        return FoundationFootprint.belowBase(site,at);
    }
    public static boolean dependenciesReady(SettlementRecord s,UUID id) {
        var parent=s.blueprint(id);
        if(parent==null)return true;
        if(needsFoundation(s,parent) && (!s.terrain().surveyed.contains(id) || !FoundationFootprint.legacySurveyInitialized(parent)))return false;
        for(var p:s.terrain().dependingOn(id))if(!p.cancelled && !p.completed){
            // A registered road or bridge is never a global construction gate. Each real action still checks access and support.
            if(p.road || p.bridge)continue;
            for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i)
                    && com.hkin.exiledsettlement.citizen.WorkTaskService.essential(s,p,parent,p.steps.get(i).position()))return false;
        }
        return true;
    }
    /** Forget both the persisted and in-flight slices of a withdrawn request. */
    static boolean forgetSurvey(SettlementRecord s,UUID id) {
        boolean changed=s.terrain().surveys.remove(id)!=null;
        changed|=s.terrain().surveySnapshots.remove(id)!=null;
        changed|=s.terrain().surveyed.remove(id);
        changed|=s.terrain().blockers.remove(id)!=null;
        var running=SURVEYS.get(s);
        if(running!=null){changed|=running.remove(id)!=null;if(running.isEmpty())SURVEYS.remove(s);}
        return changed;
    }
    public static void tickLevel(ServerLevel level) {
        PlayerWorksService.tick(level);
        flushRemovals(level);
        var activeData=SettlementSavedData.get(level);
        for(var settlement:activeData.settlements()) {
            if(TerrainChildLifecycle.tick(settlement,level.getGameTime()))activeData.touch();
            if(TerrainChildLifecycle.retireFoundationReviews(settlement,level.getGameTime()))activeData.touch();
            for(var site:settlement.formalBlueprints())if(!site.cancellationQueued() && needsFoundation(settlement,site)
                    && FoundationFootprint.startLegacySurvey(site)) {
                if(forgetSurvey(settlement,site.id()))activeData.touch();
            }
            migrateFoundationSurroundings(settlement);
            PublicGroundService.tick(level,settlement);
            PublicStairRepairService.tick(level,settlement);
            var pendingSurveys=List.copyOf(settlement.terrain().surveys.values());
            if(pendingSurveys.isEmpty())continue;var next=pendingSurveys.get(Math.floorMod(settlement.terrain().surveyCursor++,pendingSurveys.size()));
            UUID id=next.getUUID("Dependency");var site=settlement.blueprint(id);
            // Completed-building reviews from old saves are withdrawn, never resumed.
            if(site==null || site.cancellationQueued()){
                if(forgetSurvey(settlement,id))activeData.touch();continue;
            }
            var template=BlueprintTemplates.create(site);
            String result=plan(level,settlement,template,id,next.getString("Name"),next.getBoolean("Surroundings"));
            settlement.terrain().status=result.isEmpty()?"公共整地勘察完成":result;
            if(result.isEmpty()){settlement.terrain().surveyed.add(id);settlement.terrain().blockers.remove(id);}activeData.touch();
        }
        long now=level.getGameTime();if(now-LAST_SURVEY.getOrDefault(level,now-100)<100)return;LAST_SURVEY.put(level,now);
        var data=SettlementSavedData.get(level);
        for(var s:data.settlements())if(s.active() && level.hasChunkAt(s.corePosition())) {
            // Only unfinished buildings request a bounded foundation survey.
            var waiting=s.blueprints().stream().filter(b->!b.cancellationQueued() && needsFoundation(s,b)
                    && !s.terrain().surveyed.contains(b.id()) && !s.terrain().surveys.containsKey(b.id())).toList();
            if(waiting.isEmpty())continue;
            var pending=waiting.get(Math.floorMod(s.terrain().surveyCursor++,waiting.size()));
            String error=plan(level,s,BlueprintTemplates.create(pending),pending.id(),"地基与台地",false);
            s.terrain().status=error.isEmpty()?"必要地基已勘察，居民先整地再建房":error;
            if(error.isEmpty()){s.terrain().surveyed.add(pending.id());s.terrain().blockers.remove(pending.id());}
            else s.terrain().blockers.put(pending.id(),error);data.touch();
        }
    }
    private static void migrateFoundationSurroundings(SettlementRecord s){
        for(var p:s.terrain().legacyFoundations()){
            var parents=p.dependencies.stream().map(s::blueprint).filter(Objects::nonNull).filter(b->b.terrainProject()==null && !b.type().isDynamicArea()).toList();
            if(parents.isEmpty())continue;
            // Capture actual completed fills before abandon() marks the optional targets finished.
            p.heightSuperseded=true;s.terrain().index(p);
            for(int i=0;i<p.steps.size();i++){var at=p.steps.get(i).position();boolean foundation=parents.stream().anyMatch(b->b.containsColumn(at));if(!foundation)p.abandon(i);}
            p.layoutVersion=LAYOUT_VERSION;p.heightSuperseded=true;s.terrain().index(p);break;
        }
    }
    private static boolean landscapeOrder(TerrainProject p) {
        return p.layoutVersion>0 || !p.explicit && !p.road && (p.name.equals("地基与台地") || p.name.startsWith("修整 · "));
    }
    private static TerrainProject retainPaid(ServerLevel level,TerrainProject old) {
        var retained=new ArrayList<TerrainProject.Step>();
        for(int i=0;i<old.steps.size();i++) {
            var step=old.steps.get(i);
            if(step.fill() && (i<old.cursor || old.finished.get(i))
                    && (!level.hasChunkAt(step.position()) || satisfied(level.getBlockState(step.position()),step.target())))retained.add(step);
        }
        var kept=new TerrainProject(old.id,old.name,old.anchor,retained);kept.layoutVersion=LAYOUT_VERSION;kept.publicGround=old.publicGround;
        kept.publicGroundVersion=old.publicGroundVersion;kept.heightSuperseded=old.heightSuperseded;
        kept.accessSurfaces.addAll(old.accessSurfaces);
        for(var p:old.groundSurfaces)if(retained.stream().anyMatch(step->step.position().equals(p)))kept.groundSurfaces.add(p);
        kept.dependencies.addAll(old.dependencies);kept.cursor=retained.size();kept.finished.set(0,retained.size());kept.completed=true;
        return kept;
    }

    private record Column(int x,int z,int y,boolean foundation,boolean edge) {
        BlockPos at(){return new BlockPos(x,y,z);}
    }
    public static String plan(ServerLevel level,SettlementRecord s,BuildingBlueprintTemplate t,UUID dependency,String name,boolean surroundings) {
        var site=s.blueprint(dependency);
        if(site==null || site.cancellationQueued()){
            forgetSurvey(s,dependency);
            return "";
        }
        if(t.type()==BuildingType.MINE)return "";
        if(FoundationFootprint.startLegacySurvey(site))forgetSurvey(s,dependency);
        Survey survey=SURVEYS.computeIfAbsent(s,key->new LinkedHashMap<>()).computeIfAbsent(dependency,key->{var work=new Survey(t,dependency,name,surroundings);var saved=s.terrain().surveys.get(dependency);if(saved!=null)work.restore(saved);return work;});
        String result=survey.advance(level,s);
        if(!result.equals("勘察中，分片继续")) { SURVEYS.get(s).remove(dependency); s.terrain().surveys.remove(dependency);s.terrain().surveySnapshots.remove(dependency); }
        else {
            var header=new net.minecraft.nbt.CompoundTag();header.putUUID("Dependency",dependency);header.putString("Name",name);header.putBoolean("Surroundings",surroundings);
            s.terrain().surveys.putIfAbsent(dependency,header);s.terrain().surveySnapshots.put(dependency,survey::save);
        }
        return result;
    }
    private static final Map<SettlementRecord,Map<UUID,Survey>> SURVEYS=new WeakHashMap<>();
    private static final class Survey {
        private static final int RULES_VERSION=1;
        final BuildingBlueprintTemplate t;
        final UUID dependency;
        final String name;
        final boolean surroundings;
        final int floor,depth,margin,reach,minX,maxX,minZ,maxZ;
        int foundationCursor,columnCursor,targetCursor,stage,profileCursor,approachCursor,edgeCursor;
        final Set<Long> approachColumns=new LinkedHashSet<>(),wallColumns=new LinkedHashSet<>();
        final Set<BlockPos> groundCaps=new LinkedHashSet<>();
        String connectionWarning="";
        long ringCursor;
        final Map<BlockPos,Column> columns=new LinkedHashMap<>();
        final Map<BlockPos,BlockState> targets=new LinkedHashMap<>();
        List<Column> columnList=List.of();
        List<FoundationFootprint.GroundColumn> foundationColumns;
        List<Map.Entry<BlockPos,BlockState>> targetList=List.of();
        final List<TerrainProject.Step> clear=new ArrayList<>(),fill=new ArrayList<>();
        final Set<UUID> shared=new LinkedHashSet<>();
        final Map<UUID,Set<Integer>> reopen=new LinkedHashMap<>();
        Survey(BuildingBlueprintTemplate t,UUID dependency,String name,boolean surroundings) {
            this.t=t;this.dependency=dependency;this.name=name;this.surroundings=surroundings;
            floor=t.entrances().stream().mapToInt(e->e.position().getY()-1).min().orElse(t.anchor().getY()-1);
            depth=0;margin=surroundings?TerrainConfig.MARGIN.get():0;reach=surroundings?TerrainConfig.TRANSITION.get():0;
            minX=t.minCorner().getX();maxX=t.maxCorner().getX();minZ=t.minCorner().getZ();maxZ=t.maxCorner().getZ();
        }
        String advance(ServerLevel level,SettlementRecord s) {
            if(stage==0) {
                if(foundationColumns==null)foundationColumns=FoundationFootprint.columns(s.blueprint(dependency),t).values().stream()
                        .sorted(Comparator.comparingLong(column->column.top().asLong())).toList();
                int budget=256;
                while(foundationCursor<foundationColumns.size() && budget-->0) {
                    var planned=foundationColumns.get(foundationCursor++);var p=planned.top();
                    columns.put(horizontal(p),new Column(p.getX(),p.getZ(),p.getY(),true,false));
                    if(planned.surface())groundCaps.add(p.immutable());
                }
                if(foundationCursor<foundationColumns.size())return "勘察中，分片继续";
                stage=1;
            }
            if(stage==1 && surroundings) {
                // Only the usable near-house platform is unconditional. No concentric grade cone.
                long width=(long)maxX-minX+1+2*margin,height=(long)maxZ-minZ+1+2*margin;
                int budget=64;
                while(ringCursor<width*height && budget-->0) {
                    int x=(int)(minX-margin+ringCursor%width),z=(int)(minZ-margin+ringCursor/width);ringCursor++;
                    if(distance(x,z)==0)continue;
                    var at=new BlockPos(x,floor,z);if(!availableColumn(level,s,t,dependency,at))continue;
                    int ground=surface(level,at,depth);if(ground==Integer.MIN_VALUE)return failure("地形未加载或没有可用表面",at);
                    var plane=s.terrain().platformPlanes.get(columnKey(at));
                    if(plane!=null && plane!=floor)continue; // Never drag another platform to this floor.
                    columns.put(horizontal(at),new Column(x,z,floor,false,false));
                }
                if(ringCursor<width*height)return "勘察中，分片继续";
            }
            if(stage==1)stage=5;
            if(stage==5) {
                var edges=columns.values().stream().filter(c->!c.foundation() && distance(c.x(),c.z())<=margin).toList();
                int budget=8;
                while(surroundings && profileCursor<edges.size() && budget-->0)gentleEdges(level,s,edges.get(profileCursor++));
                if(surroundings && profileCursor<edges.size())return "勘察中，分片继续";
                stage=6;
            }
            List<BlueprintEntrance> entrances=t.entrances();
            if(stage==6) {
                if(surroundings && approachCursor<entrances.size()) {
                    connectEntrance(level,s,entrances.get(approachCursor++));
                    if(approachCursor<entrances.size())return "勘察中，分片继续";
                }
                stage=7;
            }
            if(stage==7) {
                var edgeCandidates=columns.values().stream().filter(c->!c.foundation() && !wallColumns.contains(columnKey(c.at()))).toList();
                int budget=8;
                while(edgeCursor<edgeCandidates.size() && budget-->0)uphillFaces(level,s,edgeCandidates.get(edgeCursor++));
                if(edgeCursor<edgeCandidates.size())return "勘察中，分片继续";
                columnList=new ArrayList<>(columns.values());stage=2;
            }
            if(stage==2) {
        for(int columnBudget=0;columnBudget<8 && columnCursor<columnList.size();columnBudget++) {
            var c=columnList.get(columnCursor++);
            BlockPos at=c.at();if(!s.contains(at) || !level.hasChunkAt(at))return failure("范围未加载或超出领地",at);
            int lowest=at.getY();
            for(var d:Direction.Plane.HORIZONTAL) {
                var near=at.relative(d);if(distance(near.getX(),near.getZ())==0)continue;
                var neighbor=columns.get(horizontal(near));
                int ground=neighbor==null?surface(level,near,depth):neighbor.y();
                var plane=s.terrain().platformPlanes.get(columnKey(near));
                if(neighbor==null && plane!=null)ground=plane;
                if(ground!=Integer.MIN_VALUE)lowest=Math.min(lowest,ground);
            }
            boolean exposed=at.getY()-lowest>=2;
            boolean retaining=!c.foundation() && (wallColumns.contains(columnKey(at)) || at.getY()>lowest);
            int faceBottom=retaining?lowest+1:at.getY();
            int bottom=Integer.MIN_VALUE;
            for(int y=Math.min(at.getY()-1,faceBottom-1);y>=level.getMinBuildHeight();y--) {
                var q=at.atY(y);if(!level.hasChunkAt(q) || level.isOutsideBuildHeight(q))return failure("地基范围未加载",q);
                var state=level.getBlockState(q);
                if(!state.getFluidState().isEmpty() && !replaceableWater(state))return failure("不可填筑的流体或水中设施",q);
                if(state.isFaceSturdy(level,q,Direction.UP) && state.getFluidState().isEmpty()
                        && (!c.foundation() || !isolatedSoil(level,q,state))){bottom=y;break;}
                if(!clearable(level,s,dependency,q,state))return failure("保留地下设施",q);
            }
            if(bottom==Integer.MIN_VALUE)return failure("实际高度边界内找不到连续支撑",at);
            // Keep actual buildings. Water is filled directly below the floor, never cleared to air.
            if(!c.foundation())for(int y=Math.min(level.getMaxBuildHeight()-1,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,at.getX(),at.getZ()));y>=at.getY();y--) {
                var q=at.atY(y);var current=level.getBlockState(q);
                if(!availableColumn(level,s,t,dependency,q) || !clearable(level,s,dependency,q,current))return failure("保留设施或不可整地位置",q);
                if(!current.getFluidState().isEmpty() && (y>at.getY() || !replaceableWater(current)))return failure("水面高于地坪或流体不能填筑",q);
                if(y>at.getY() && !current.isAir())targets.putIfAbsent(q,Blocks.AIR.defaultBlockState());
            }
            for(int y=bottom+1;y<=at.getY();y++) {
                var q=at.atY(y);var current=level.getBlockState(q);
                boolean groundCap=groundCaps.contains(q);
                if(groundCap && FoundationFootprint.usableSurface(level,q,current))continue;
                if((!current.getFluidState().isEmpty() && !replaceableWater(current)) || !clearable(level,s,dependency,q,current)) {
                    if(c.foundation() && !groundCap && current.isFaceSturdy(level,q,Direction.UP) && current.getFluidState().isEmpty())continue;
                    return failure("保留地基或玩家设施",q);
                }
                if(c.foundation() && !groundCap && current.isFaceSturdy(level,q,Direction.UP) && current.getFluidState().isEmpty())continue;
                targets.putIfAbsent(q,groundCap?Blocks.DIRT.defaultBlockState():retaining || c.foundation()?Blocks.COBBLESTONE.defaultBlockState():Blocks.DIRT.defaultBlockState());
            }
            if(exposed && !c.foundation() && !approachColumns.contains(columnKey(at)))
                targets.putIfAbsent(at.above(),Blocks.COBBLESTONE_WALL.defaultBlockState());
        }

                if(columnCursor<columnList.size())return "勘察中，分片继续";
                targetList=new ArrayList<>(targets.entrySet());stage=3;
            }
            if(stage==3) {
                int budget=256;
                while(targetCursor<targetList.size() && budget-->0) {
                    var entry=targetList.get(targetCursor++);
                    BlockPos p=entry.getKey();BlockState target=entry.getValue(),current=level.getBlockState(p);
                    // Another worker or the player may have supplied a real floor since this column was surveyed.
                    // It is already usable ground, regardless of the old shared order's chosen material.
                    if(groundCaps.contains(p) && FoundationFootprint.usableSurface(level,p,current))continue;
                    if(s.terrain().suppressed.contains(p.asLong()))return failure("此处曾被主动移除，保留空地",p);
                    var owner=s.terrain().cells.get(p);
                    if(owner!=null) {
                        var other=s.terrain().projects.get(owner);
                        if(other==null)return failure("公共工程记录缺失",p);
                        var carrier=s.blueprint(owner);
                        if(carrier!=null && carrier.cancellationQueued())return failure("关联地基工单正在撤销，稍后复查",p);
                        if(!other.completed && !other.cancelled) {
                            var expected=other.template().placementAt(p);
                            var column=columns.get(horizontal(p));
                            boolean paidFoundation=column!=null && column.foundation() && target.is(Blocks.COBBLESTONE)
                                    && expected!=null && BuriedFillMaterials.ordinary(level,p,expected.state());
                            if(expected==null || !satisfied(expected.state(),target) && !(!groundCaps.contains(p)&&target.is(Blocks.DIRT)&&expected.state().is(Blocks.COBBLESTONE)) && !paidFoundation)return failure("公共地形目标冲突",p);
                            // A partly unfinished order can contain a damaged,
                            // previously completed fill. Reopen its real paid
                            // step instead of treating its reservation as work.
                            if(!satisfied(current,expected.state()))for(int i:other.indices(p))
                                if(other.finished.get(i) && !other.abandoned.get(i) && other.steps.get(i).fill()
                                        && other.steps.get(i).target().equals(expected.state()))reopen.computeIfAbsent(owner,key->new LinkedHashSet<>()).add(i);
                            shared.add(owner);continue;
                        }
                    }
                    if(satisfied(current,target))continue;
                    if(replaceableWater(current)) {
                        var step=new TerrainProject.Step(p,target,current);
                        if(!canFillWater(step,current))return failure("水格只能用完整填筑方块替换",p);
                        fill.add(step);continue;
                    }
                    if(!current.isAir()) {if(!clearable(level,s,dependency,p,current))return failure("保留玩家设施",p);clear.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),current));}
                    if(!target.isAir())fill.add(new TerrainProject.Step(p,target,Blocks.AIR.defaultBlockState()));
                }
                if(targetCursor<targetList.size())return "勘察中，分片继续";
                stage=4;
            }
        clear.sort(Comparator.comparingInt((TerrainProject.Step a)->a.position().getY()).reversed().thenComparingLong(a->a.position().asLong()));
        fill.sort(Comparator.comparingInt((TerrainProject.Step a)->a.position().getY()).thenComparingInt(a->a.target().is(Blocks.DIRT)?1:0).thenComparingLong(a->a.position().asLong()));
        clear.addAll(fill);
        // Survey is atomic: publish no reservation or cost until all cells are valid.
        for(var id:shared){var project=s.terrain().projects.get(id);var carrier=s.blueprint(id);
            if(project==null || project.cancelled || carrier!=null && carrier.cancellationQueued())return failure("关联地基工单已变更，稍后复查",t.anchor());}
        for(var c:columns.values())if(!c.foundation() && !wallColumns.contains(columnKey(c.at())))s.terrain().platformPlanes.putIfAbsent(new BlockPos(c.x(),0,c.z()).asLong(),c.y());
        for(var id:shared){var project=s.terrain().projects.get(id);if(project!=null && !project.cancelled){
            project.dependencies.add(dependency);
            if(!project.publicGround && !project.road && !project.bridge && !project.areaClearance && !project.roadReclaim)
                for(var cap:groundCaps){var goal=project.template().placementAt(cap);if(goal!=null && goal.state().is(Blocks.DIRT))project.groundSurfaces.add(cap);}
        }}
        boolean reopened=false;
        for(var entry:reopen.entrySet()) {
            var project=s.terrain().projects.get(entry.getKey());if(project==null || project.cancelled)continue;
            for(int index:entry.getValue())if(index>=0 && index<project.steps.size()
                    && project.finished.get(index) && !project.abandoned.get(index)) {
                var step=project.steps.get(index);
                if(groundCaps.contains(step.position()) && acceptGroundCap(level,project,step))continue;
                if(step.fill() && level.hasChunkAt(step.position()) && !satisfied(level.getBlockState(step.position()),step.target())){
                    project.reopen(index);
                    if(s.blueprint(project.id)==null)s.resumeTerrain(project);
                    reopened=true;
                }
            }
        }
        if(reopened)TerrainLayerService.invalidate(s);
        if(clear.isEmpty())return "";
        BlockPos marker=t.anchor().above(96);
        while(s.blueprintAt(marker)!=null)marker=marker.above();
        var site=s.addBlueprint(BuildingType.HOUSE,t.anchor(),marker,t.facing(),s.ownerId(),false,1,Map.of());
        var project=new TerrainProject(site.id(),name,t.anchor(),clear);project.layoutVersion=LAYOUT_VERSION;project.connectionWarning=connectionWarning;project.dependencies.add(dependency);
        project.groundSurfaces.addAll(groundCaps);
        for(var c:columns.values())if(approachColumns.contains(columnKey(c.at())))project.accessSurfaces.add(c.at());
        site.withTerrainProject(project);s.terrain().index(project);SettlementSavedData.get(level).touch();return "";
        }
        private boolean foundationPlacement(SettlementRecord s,BlueprintPlacement placement) {
            var site=s.blueprint(dependency);
            return site!=null && FoundationFootprint.isBase(site,t,floor,placement);
        }
        private static boolean isolatedSoil(ServerLevel level,BlockPos at,BlockState state) {
            if(!(state.is(BlockTags.DIRT) || state.is(Blocks.MUD) || state.is(Blocks.CLAY)
                    || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)))return false;
            for(var direction:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
                var neighbor=at.relative(direction);
                if(!level.hasChunkAt(neighbor))return false; // Unknown adjacency never expands a foundation.
                var next=level.getBlockState(neighbor);
                if(next.getFluidState().isEmpty() && next.isFaceSturdy(level,neighbor,Direction.UP))return false;
            }
            return true;
        }
        private static BlockPos horizontal(BlockPos p){return new BlockPos(p.getX(),0,p.getZ());}
        private static long columnKey(BlockPos p){return horizontal(p).asLong();}
        private int distance(int x,int z){return Math.max(Math.max(Math.max(minX-x,x-maxX),0),Math.max(Math.max(minZ-z,z-maxZ),0));}
        private void gentleEdges(ServerLevel level,SettlementRecord s,Column edge) {
            for(var direction:Direction.Plane.HORIZONTAL) {
                var first=edge.at().relative(direction);
                if(distance(first.getX(),first.getZ())==0 || columns.containsKey(horizontal(first)))continue;
                int natural=surface(level,first,depth),drop=floor-natural;
                // This is a shape choice, never an engineering-depth rejection. Larger relief uses a wall.
                if(natural==Integer.MIN_VALUE || drop==0 || Math.abs(drop)>2 || Math.abs(drop)*2>reach)continue;
                var proposed=new LinkedHashMap<BlockPos,Column>();boolean fits=true;
                int run=Math.abs(drop)*2,sign=Integer.signum(drop);
                for(int i=1;i<=run;i++) {
                    var p=edge.at().relative(direction,i);int y=floor-sign*((i+1)/2);
                    if(distance(p.getX(),p.getZ())<=margin || !availableColumn(level,s,t,dependency,p)){fits=false;break;}
                    // A slope needs free space across its width as well as along its length.
                    for(var side:List.of(direction.getClockWise(),direction.getCounterClockWise()))
                        if(!availableColumn(level,s,t,dependency,p.relative(side))){fits=false;break;}
                    int ground=surface(level,p,depth);
                    if(!fits || ground==Integer.MIN_VALUE || Math.abs(ground-floor)>2
                            || !level.getFluidState(p.atY(y)).isEmpty()
                            || i==run && ground!=y){fits=false;break;}
                    var old=columns.get(horizontal(p));var plane=s.terrain().platformPlanes.get(columnKey(p));
                    if(old!=null && old.y()!=y || plane!=null && plane!=y){fits=false;break;}
                    proposed.put(horizontal(p),new Column(p.getX(),p.getZ(),y,false,false));
                }
                if(fits)proposed.forEach(columns::putIfAbsent);
            }
        }
        private void uphillFaces(ServerLevel level,SettlementRecord s,Column edge) {
            for(var direction:Direction.Plane.HORIZONTAL) {
                var p=edge.at().relative(direction);
                if(distance(p.getX(),p.getZ())==0 || columns.containsKey(horizontal(p))
                        || approachColumns.contains(columnKey(p)) || !availableColumn(level,s,t,dependency,p))continue;
                int high=surface(level,p,depth);if(high==Integer.MIN_VALUE || high-edge.y()<2)continue;
                // Face the actual cut with a full stone wall, without making a second high platform.
                var plane=s.terrain().platformPlanes.get(columnKey(p));if(plane!=null && plane!=high)continue;
                columns.put(horizontal(p),new Column(p.getX(),p.getZ(),high,false,true));wallColumns.add(columnKey(p));
            }
        }
        private record Approach(Map<BlockPos,Column> columns,Map<BlockPos,BlockState> stairs,Set<Long> footprint,double cost) { }
        private void connectEntrance(ServerLevel level,SettlementRecord s,BlueprintEntrance entrance) {
            Direction outward=entrance.outward();BlockPos start=entrance.position();int exit=t.width()+t.depth()+2;
            while(exit-->0 && distance(start.getX(),start.getZ())==0)start=start.relative(outward);
            Approach best=null;
            for(int turn:List.of(-1,Math.max(1,margin),Math.max(1,margin+reach/2)))
                for(int bend:turn<0?List.of(0):List.of(-1,1))for(int laneSide:List.of(-1,1)) {
                    var route=approach(level,s,start,outward,turn,bend,laneSide);
                    if(route!=null && (best==null || route.cost()<best.cost()))best=route;
                }
            if(best==null) {
                connectionWarning="入口 "+entrance.position().toShortString()+" 缺少两格宽可连接的台阶/落脚空间；保留小平台和挡土墙，未向外强造大坡";
                return;
            }
            columns.putAll(best.columns());targets.putAll(best.stairs());approachColumns.addAll(best.footprint());
        }
        private Approach approach(ServerLevel level,SettlementRecord s,BlockPos start,Direction out,int turn,int bend,int laneSide) {
            var result=new LinkedHashMap<BlockPos,Column>();var stairs=new LinkedHashMap<BlockPos,BlockState>();var footprint=new LinkedHashSet<Long>();
            int previous=floor;Direction before=out;double cost=0;
            for(int i=0;i<margin+reach;i++) {
                Direction direction=turn>=0 && i>=turn?(bend<0?out.getCounterClockWise():out.getClockWise()):out;
                BlockPos seed=turn>=0 && i>=turn?start.relative(out,turn).relative(direction,i-turn):start.relative(out,i);
                var side=laneSide<0?direction.getCounterClockWise():direction.getClockWise();
                int natural=surface(level,seed,depth);if(natural==Integer.MIN_VALUE)return null;
                int height=i<margin || direction!=before?previous:Math.clamp(natural,previous-1,previous+1);
                // A ready road at the doorway is already a valid landing, not a new fill target.
                if(i==0 && com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(seed.atY(previous))
                        && exitReady(level,s,seed.relative(direction.getOpposite()).atY(previous),direction,side)
                        && exitReady(level,s,seed.atY(previous),direction,side))return new Approach(result,stairs,footprint,0);
                for(int lane=0;lane<2;lane++) {
                    var p=seed.relative(side,lane).atY(height);
                    if(distance(p.getX(),p.getZ())==0 || !availableColumn(level,s,t,dependency,p))return null;
                    var plane=s.terrain().platformPlanes.get(columnKey(p));if(plane!=null && plane!=height)return null;
                    var prior=result.get(horizontal(p));if(prior!=null && prior.y()!=height)return null;
                    for(int h=1;h<=2;h++) {
                        var state=level.getBlockState(p.above(h));
                        if(!state.getFluidState().isEmpty() || !clearable(level,s,dependency,p.above(h),state))return null;
                    }
                    result.put(horizontal(p),new Column(p.getX(),p.getZ(),height,false,false));footprint.add(columnKey(p));
                    int old=surface(level,p,depth);if(old==Integer.MIN_VALUE)return null;
                    cost+=1+Math.abs(height-old);
                    if(height!=previous) {
                        var at=p.atY(Math.min(height,previous)+1);
                        stairs.put(at,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,height>previous?direction:direction.getOpposite()));
                    }
                }
                if(i>=margin-1 && height==natural && exitReady(level,s,seed.atY(height),direction,side))return new Approach(result,stairs,footprint,cost);
                previous=height;before=direction;
            }
            return null; // Never publish a staircase ending above a cliff or in water.
        }
        private boolean exitReady(ServerLevel level,SettlementRecord s,BlockPos end,Direction direction,Direction side) {
            for(int lane=0;lane<2;lane++) {
                var seed=end.relative(direction).relative(side,lane);
                int ground=surface(level,seed,depth);if(ground==Integer.MIN_VALUE || Math.abs(ground-end.getY())>1)return false;
                var at=seed.atY(ground);
                if(!s.contains(at) || s.community().danger(at) || BuildingWorkPolicy.automaticForbidden(s,s.blueprint(dependency),at)
                        || distance(at.getX(),at.getZ())==0 || !FarmEarthworkPlan.support(level,at))return false;
                boolean road=com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(at);
                if(!road && !availableColumn(level,s,t,dependency,at))return false;
                for(int h=1;h<=2;h++)if(!level.getFluidState(at.above(h)).isEmpty()
                        || !level.getBlockState(at.above(h)).getCollisionShape(level,at.above(h)).isEmpty())return false;
            }
            return true;
        }

        net.minecraft.nbt.CompoundTag save() {
            var tag=new net.minecraft.nbt.CompoundTag();tag.putUUID("Dependency",dependency);tag.putString("Name",name);tag.putBoolean("Surroundings",surroundings);
            tag.putInt("SurveyRulesVersion",RULES_VERSION);tag.putLongArray("GroundCaps",groundCaps.stream().mapToLong(BlockPos::asLong).toArray());
            tag.putInt("LayoutVersion",LAYOUT_VERSION);tag.putInt("ProfileCursor",profileCursor);tag.putInt("ApproachCursor",approachCursor);tag.putInt("EdgeCursor",edgeCursor);tag.putString("ConnectionWarning",connectionWarning);
            tag.putLongArray("ApproachColumns",approachColumns.stream().mapToLong(Long::longValue).toArray());tag.putLongArray("WallColumns",wallColumns.stream().mapToLong(Long::longValue).toArray());
            tag.putInt("Stage",stage);tag.putInt("FoundationCursor",foundationCursor);tag.putLong("RingCursor",ringCursor);tag.putInt("ColumnCursor",columnCursor);tag.putInt("TargetCursor",targetCursor);
            var list=new net.minecraft.nbt.ListTag();for(var c:columns.values()){var a=new net.minecraft.nbt.CompoundTag();a.putLong("At",c.at().asLong());a.putBoolean("Foundation",c.foundation());a.putBoolean("Edge",c.edge());list.add(a);}tag.put("Columns",list);
            var target=new net.minecraft.nbt.ListTag();targets.forEach((at,state)->{var a=new net.minecraft.nbt.CompoundTag();a.putLong("At",at.asLong());a.put("State",net.minecraft.nbt.NbtUtils.writeBlockState(state));target.add(a);});tag.put("Targets",target);
            var steps=new net.minecraft.nbt.ListTag();for(var step:clear){var a=new net.minecraft.nbt.CompoundTag();a.putLong("At",step.position().asLong());a.put("Original",net.minecraft.nbt.NbtUtils.writeBlockState(step.original()));steps.add(a);}tag.put("Clear",steps);
            var fills=new net.minecraft.nbt.ListTag();for(var step:fill){var a=new net.minecraft.nbt.CompoundTag();a.putLong("At",step.position().asLong());a.put("State",net.minecraft.nbt.NbtUtils.writeBlockState(step.target()));fills.add(a);}tag.put("Fill",fills);
            var deps=new net.minecraft.nbt.ListTag();for(var id:shared){var a=new net.minecraft.nbt.CompoundTag();a.putUUID("Id",id);deps.add(a);}tag.put("Shared",deps);
            var repairs=new net.minecraft.nbt.ListTag();reopen.forEach((id,indices)->{var a=new net.minecraft.nbt.CompoundTag();a.putUUID("Id",id);a.putIntArray("Indices",indices.stream().mapToInt(Integer::intValue).toArray());repairs.add(a);});tag.put("Reopen",repairs);return tag;
        }
        void restore(net.minecraft.nbt.CompoundTag tag) {
            if(tag.getInt("LayoutVersion")!=LAYOUT_VERSION || tag.getInt("SurveyRulesVersion")!=RULES_VERSION)return;
            for(long p:tag.getLongArray("GroundCaps"))groundCaps.add(BlockPos.of(p));
            profileCursor=tag.getInt("ProfileCursor");approachCursor=tag.getInt("ApproachCursor");edgeCursor=tag.getInt("EdgeCursor");connectionWarning=tag.getString("ConnectionWarning");
            for(long p:tag.getLongArray("ApproachColumns"))approachColumns.add(p);for(long p:tag.getLongArray("WallColumns"))wallColumns.add(p);
            stage=tag.getInt("Stage");foundationCursor=tag.getInt("FoundationCursor");ringCursor=tag.getLong("RingCursor");columnCursor=tag.getInt("ColumnCursor");targetCursor=tag.getInt("TargetCursor");
            var list=tag.getList("Columns",10);for(int i=0;i<list.size();i++){var a=list.getCompound(i);var at=BlockPos.of(a.getLong("At"));columns.put(new BlockPos(at.getX(),0,at.getZ()),new Column(at.getX(),at.getZ(),at.getY(),a.getBoolean("Foundation"),a.getBoolean("Edge")));}
            var target=tag.getList("Targets",10);for(int i=0;i<target.size();i++){var a=target.getCompound(i);targets.put(BlockPos.of(a.getLong("At")),net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),a.getCompound("State")));}
            var steps=tag.getList("Clear",10);for(int i=0;i<steps.size();i++){var a=steps.getCompound(i);clear.add(new TerrainProject.Step(BlockPos.of(a.getLong("At")),Blocks.AIR.defaultBlockState(),net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),a.getCompound("Original"))));}
            var fills=tag.getList("Fill",10);for(int i=0;i<fills.size();i++){var a=fills.getCompound(i);fill.add(new TerrainProject.Step(BlockPos.of(a.getLong("At")),net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),a.getCompound("State")),Blocks.AIR.defaultBlockState()));}
            var deps=tag.getList("Shared",10);for(int i=0;i<deps.size();i++)shared.add(deps.getCompound(i).getUUID("Id"));
            var repairs=tag.getList("Reopen",10);for(int i=0;i<repairs.size();i++){var a=repairs.getCompound(i);var indices=new LinkedHashSet<Integer>();for(int index:a.getIntArray("Indices"))indices.add(index);reopen.put(a.getUUID("Id"),indices);}
            columnList=new ArrayList<>(columns.values());targetList=new ArrayList<>(targets.entrySet());
        }
    }
    private static int surface(ServerLevel level,BlockPos seed,int range) {
        if(!level.hasChunkAt(seed))return Integer.MIN_VALUE;
        int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,seed.getX(),seed.getZ());
        for(int y=Math.min(top,level.getMaxBuildHeight()-1);y>=level.getMinBuildHeight();y--){var p=seed.atY(y);
            if(!level.hasChunkAt(p))return Integer.MIN_VALUE;
            var state=level.getBlockState(p);if(state.isFaceSturdy(level,p,Direction.UP)&&state.getFluidState().isEmpty())return y;}
        return Integer.MIN_VALUE;
    }
    private static boolean availableColumn(ServerLevel level,SettlementRecord s,BuildingBlueprintTemplate own,UUID dependency,BlockPos p) {
        if(com.hkin.exiledsettlement.road.RoadSpaceData.get(level).manualHeight(p)!=null)return false;
        if(!s.contains(p)||s.community().danger(p)||BuildingWorkPolicy.automaticForbidden(s,s.blueprint(dependency),p))return false;
        for(var b:s.buildings())if(b.active() && b.minCorner().getX()<=p.getX()&&p.getX()<=b.maxCorner().getX()
                && b.minCorner().getZ()<=p.getZ()&&p.getZ()<=b.maxCorner().getZ())return false;
        for(var b:s.blueprints())if(!b.id().equals(dependency)&&!owned(b)&&!b.cancellationQueued()) {
            var t=BlueprintTemplates.create(b);if(t.minCorner().getX()<=p.getX()&&p.getX()<=t.maxCorner().getX()
                    && t.minCorner().getZ()<=p.getZ()&&p.getZ()<=t.maxCorner().getZ())return false;
        }
        var roads=com.hkin.exiledsettlement.road.RoadSpaceData.get(level);
        return (!roads.contains(p) || roads.levelAt(p)<=1 && com.hkin.exiledsettlement.road.AdaptiveRoadService.automaticAt(s,p))
                && (!roads.contains(p.below()) || roads.levelAt(p.below())<=1 && com.hkin.exiledsettlement.road.AdaptiveRoadService.automaticAt(s,p.below()));
    }
    public static boolean irrigationRetirement(SettlementRecord s,BlueprintRecord site,TerrainProject.Step step) {
        if(step.fill() || site.terrainProject()==null)return false;
        return site.terrainProject().dependencies.stream().anyMatch(id->{
            var b=s.building(id);var blueprint=s.blueprint(id);var r=blueprint!=null?blueprint.region():b!=null?b.region():null;
            return r!=null && r.agricultural() && r.flatY!=null && r.cell(step.position())!=null
                    && step.position().getY()>r.flatY && r.oldIrrigation.contains(step.position().asLong());
        });
    }
    private static BlueprintPlacement currentPlacement(ServerLevel level,BlueprintRecord site,TerrainProject.Step step,BlockState current) {
        if(!step.fill() && !current.getFluidState().isEmpty() && current.getBlock() instanceof BucketPickup)
            return new BlueprintPlacement(step.position(),step.target(),net.minecraft.world.item.Items.BUCKET,BlueprintPlacementMode.CLEARANCE);
        if(step.fill()){var chosen=BuriedFillMaterials.choose(level,sFor(level,site),site.terrainProject(),step,null);return new TerrainProject.Step(step.position(),chosen,step.original()).placement();}
        return step.placement();
    }
    /** Public routes and grading never own a building's vertical column, even when naming it as a dependency. */
    public static boolean protectedPublicColumn(SettlementRecord s,TerrainProject project,BlockPos at) {
        if(project==null || !(project.publicGround || project.road || project.roadReclaim))return false;
        if(at.getX()==s.corePosition().getX() && at.getZ()==s.corePosition().getZ())return true;
        for(var b:s.buildings())if(b.active() && b.containsColumn(at))return true;
        return s.formalBlueprints().stream().anyMatch(b->!b.cancellationQueued() && b.containsColumn(at));
    }
    public static String guard(ServerLevel level,SettlementRecord s,BlueprintRecord site,TerrainProject.Step step) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(s,step.position()))return "RECOVERY_WAITING";
        var p=step.position();if(!s.contains(p)||level.isOutsideBuildHeight(p)||!level.hasChunkAt(p))return "BLUEPRINT_AREA_NOT_LOADED";
        var project=site.terrainProject();
        if(project!=null && !BuriedFillMaterials.configured(level,s,project,step))return "MATERIALS_MISSING";
        if(project!=null && project.playerBridge!=null && project.playerBridge.buildingModel
                && (s.buildings().stream().anyMatch(b->b.active() && b.containsColumn(p))
                || s.formalBlueprints().stream().anyMatch(b->!b.id().equals(site.id()) && !b.cancellationQueued() && b.containsColumn(p))))return "PROTECTED_AREA";
        String bridgeGuard=com.hkin.exiledsettlement.road.PlayerBridgeWork.guard(level,project,step);
        if(!bridgeGuard.isEmpty())return bridgeGuard;
        if(step.fill() && !com.hkin.exiledsettlement.region.RegionConstructionStages.fillAllowed(level,s,project))return "REGION_GROUND_NOT_READY";
        if(protectedPublicColumn(s,project,p))return "PROTECTED_AREA";
        if(project!=null && project.publicGround && !project.road && s.formalBlueprints().stream().anyMatch(b->
                !b.cancellationQueued() && b.containsColumn(p)))return "PROTECTED_AREA";
        if(!com.hkin.exiledsettlement.region.RegionWorkScope.allowed(s,project))return "REGION_GROUND_NOT_READY";
        String publicWorkGuard=PublicGroundService.workGuard(level,s,project,step);
        if(!publicWorkGuard.isEmpty())return publicWorkGuard;
        String animalGuard=com.hkin.exiledsettlement.region.PastureGroundSafety.guard(level,s,site,step);
        if(!animalGuard.isEmpty())return animalGuard;
        if(project!=null && project.road && s.formalBlueprints().stream().anyMatch(b->
                !b.cancellationQueued() && !project.dependencies.contains(b.id()) && b.containsColumn(p)))return "PROTECTED_AREA";
        if(project!=null && project.areaClearance && (project.template().placementAt(p)==null
                || PlayerWorksService.protectedAt(s,p)
                || com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(p)))return "PROTECTED_AREA";
        if(project!=null && !project.bridge && (project.road || project.publicGround)){
            var joint=PublicGroundService.targetAt(s,p);
            if(joint!=null && s.terrain().enabled && !s.terrain().publicPaused && project.road && !project.roadReclaim)
                for(var route:s.autoRoads().routes.values())if(!route.suppressed && project.dependencies.contains(route.id)){
                    var road=route.cellInColumn(p);
                    if(road!=null && road.getY()!=joint)return "CONSTRUCTION_TARGET_CHANGED";
                }
            if(com.hkin.exiledsettlement.road.PlayerRoadEdits.holding(level,p))return "CONSTRUCTION_TARGET_CHANGED";
            var edited=com.hkin.exiledsettlement.road.RoadSpaceData.get(level).playerSurface(p);
            if(edited!=null && p.getY()>=edited.getY() && com.hkin.exiledsettlement.road.PlayerRoadEdits.retained(level,edited))return "CONSTRUCTION_TARGET_CHANGED";
        }
        if(s.community().danger(p)||BuildingWorkPolicy.automaticForbidden(s,site,p)||BuildingWorkPolicy.suppressed(s,site,p))return "PROTECTED_AREA";
        var current=level.getBlockState(p);
        if(com.hkin.exiledsettlement.region.RegionGroundService.protectsTree(level,s,project,p))return "PROTECTED_AREA";
        if(!current.getFluidState().isEmpty() && !canFillWater(project,step,current) && !canClearWetNatural(step,current) && !irrigationRetirement(s,site,step))return "FARM_EARTHWORK_FLUID";
        if(!FoundationPlacement.replaces(level,project,step) && !irrigationRetirement(s,site,step) && !canFillWater(project,step,current) && !canClearWetNatural(step,current) && !satisfied(current,step.original())
                && !(step.fill() && current.isAir() && replaceableWater(step.original()))
                && !(!step.fill() && FarmEarthworkPlan.soil(current) && FarmEarthworkPlan.soil(step.original()))
                && !(soft(current) && (step.original().isAir() || soft(step.original()))))return "CONSTRUCTION_TARGET_CHANGED";
        for(var b:s.buildings())if(b.active() && b.contains(p)) {
            if(!BlueprintTemplates.supports(b.type()))return "PROTECTED_AREA";
            if(!site.terrainProject().dependencies.contains(b.id()))return "PROTECTED_AREA";
            var actual=BlueprintTemplates.createForBuilding(b).placementAt(p);
            boolean ownSoilRepair=b.region()!=null && !step.fill() && (FarmEarthworkPlan.soil(current) || current.is(Blocks.DIRT_PATH))
                    && site.terrainProject().template().placementAt(p)!=null && site.terrainProject().template().placementAt(p).state().is(Blocks.DIRT);
            if(!ownSoilRepair && actual!=null && !actual.state().isAir() && actual.mode().isFinalStructure() && (b.region()==null || BlueprintService.matchesPlacedState(current,actual.state())))return "PROTECTED_AREA";
        }
        for(var b:s.formalBlueprints())if(!b.id().equals(site.id()) && !site.terrainProject().dependencies.contains(b.id()) && !b.cancellationQueued()) {
            var actual=BlueprintTemplates.create(b).placementAt(p);
            if(actual!=null && !actual.state().isAir() && actual.mode().isFinalStructure() && (b.region()==null || BlueprintService.matchesPlacedState(current,actual.state())))return "PROTECTED_AREA";
        }
        return "";
    }
    public static List<BlueprintService.PendingPlacement> pending(ServerLevel level,BlueprintRecord site,Set<BlockPos> excluded) {
        var settlement=sFor(level,site);return pendingRaw(level,site,excluded).stream().filter(work->work.placement().mode().isFinalStructure() && !PublicWorkAreas.applies(settlement,site)
                || TerrainLayerService.allowed(level,settlement,site,site.terrainProject().steps.get(-work.index()-1))).toList();
    }
    private static final class PendingScan {List<TerrainProject.Step> source;int cursor;long tick=Long.MIN_VALUE;boolean scanned;byte[] checks;final TreeSet<Integer> ready=new TreeSet<>();}
    private static final Map<TerrainProject,PendingScan> LAYER_SCANS=new WeakHashMap<>();
    private static boolean candidatePending(ServerLevel level,BlueprintRecord site,int index,PendingScan scan) {
        if(site.terrainProject().finished.get(index))return false;
        if(scan.checks[index]==0)scan.checks[index]=(byte)(physicalPending(level,site,index)?1:2);
        return scan.checks[index]==1;
    }
    private static boolean physicalPending(ServerLevel level,BlueprintRecord site,int index){
        var project=site.terrainProject();if(index<project.cursor || project.finished.get(index))return false;
        var step=project.steps.get(index);var at=step.position();if(!level.hasChunkAt(at))return false;
        if(project.road && step.fill() && retainConstructionSupport(level,sFor(level,site),project,step))return false;
        if(project.playerBridge!=null && (!project.playerBridge.admitted.get(index) || !project.playerBridge.current(project,index)))return false;
        if(stepSatisfied(level,project,step)){project.mark(index);SettlementSavedData.get(level).touch();return false;}
        if(step.fill())return guard(level,sFor(level,site),site,step).isEmpty();
        var current=level.getBlockState(at);if(FoundationPlacement.coveredDig(level,project,step))return false;
        if(step.fill() && (!FoundationPlacement.replaces(level,project,step) && !current.isAir() && !soft(current) && !canFillWater(step,current) || !com.hkin.exiledsettlement.road.BridgeService.supported(level,project,step)))return false;
        return wallSupportReady(level,project,step) && guard(level,sFor(level,site),site,step).isEmpty();
    }
    /** A temporary adjacent pit must not schedule replacement of the road's already buried soil. */
    static boolean retainConstructionSupport(ServerLevel level,SettlementRecord s,TerrainProject p,TerrainProject.Step step){
        if(!p.road || p.roadReclaim || p.bridge || !step.fill())return false;
        var fixed=com.hkin.exiledsettlement.road.AutoRoadService.fixedConstructionSurface(s,step.position());
        var actual=level.getBlockState(step.position());
        if(fixed==null || step.position().getY()>=fixed.getY() || !FarmEarthworkPlan.soil(actual)
                || !FarmEarthworkPlan.support(level,step.position()) || !actual.equals(step.original())
                && !p.steps.stream().anyMatch(old->old.position().equals(step.position()) && !old.fill() && old.original().equals(actual))
                || !BuriedFillMaterials.hidden(level,s,p,step))return false;
        // Release only redundant untouched replacement steps. No block action, consumption or paid fill is recorded.
        for(int i:p.indices(step.position()))if(!p.finished.get(i))p.abandon(i);
        SettlementSavedData.get(level).touch();return true;
    }
    static List<BlueprintService.PendingPlacement> pendingRaw(ServerLevel level,BlueprintRecord site,Set<BlockPos> excluded) {
        excluded=com.hkin.exiledsettlement.citizen.TreeSurvey.claimed(level,excluded);
        FoundationSupportService.review(level,sFor(level,site),site);
        if(site.terrainProject().playerBridge!=null) {
            var project=site.terrainProject();var out=new ArrayList<BlueprintService.PendingPlacement>();
            for(int i:project.playerBridge.activeIndices(project))if(!excluded.contains(project.steps.get(i).position()) && physicalPending(level,site,i))
                out.add(new BlueprintService.PendingPlacement(-i-1,project.steps.get(i).placement()));
            return out;
        }
        var project=site.terrainProject();var order=project.layerOrder();var scan=LAYER_SCANS.computeIfAbsent(project,p->new PendingScan());
        if(scan.source!=project.steps){scan.source=project.steps;scan.cursor=project.layerFrontier();scan.ready.clear();scan.checks=new byte[project.steps.size()];scan.tick=Long.MIN_VALUE;}
        if(scan.tick!=level.getGameTime()){scan.tick=level.getGameTime();Arrays.fill(scan.checks,(byte)0);scan.scanned=false;}
        if(scan.cursor>=order.size())scan.cursor=project.layerFrontier();
        // Remember ready lower cells while scanning later windows. Claimed cells remain in the frontier.
        // Share candidate checks for this synchronous tick only. at()/actual execution
        // still recheck current blocks, layer and protection before performing any work.
        for(int budget=0;!scan.scanned && budget<1024 && scan.cursor<order.size();budget++){
            int rank=scan.cursor++;int index=order.get(rank);if(candidatePending(level,site,index,scan))scan.ready.add(rank);else scan.ready.remove(rank);
        }
        scan.scanned=true;
        project.workCursor=scan.cursor;var result=new ArrayList<BlueprintService.PendingPlacement>();
        for(var iterator=scan.ready.iterator();iterator.hasNext();){int rank=iterator.next();int index=order.get(rank);
            if(!candidatePending(level,site,index,scan)){iterator.remove();continue;}
            var step=project.steps.get(index);if(excluded.contains(step.position()))continue;
            result.add(new BlueprintService.PendingPlacement(-index-1,currentPlacement(level,site,step,level.getBlockState(step.position()))));
            if(result.size()>=32)break;
        }
        return List.copyOf(result);
    }
    private static SettlementRecord sFor(ServerLevel level,BlueprintRecord site){return SettlementSavedData.get(level).settlements().stream().filter(s->s.blueprint(site.id())==site).findFirst().orElseThrow();}
    public static BlueprintService.PendingPlacement at(ServerLevel level,BlueprintRecord site,BlockPos position) {
        var p=site.terrainProject();
        for(int i:p.indices(position))if(i>=p.cursor && !p.finished.get(i) && p.steps.get(i).fill()
                && !stepSatisfied(level,p,p.steps.get(i)) && guard(level,sFor(level,site),site,p.steps.get(i)).isEmpty()
                && (!PublicWorkAreas.applies(sFor(level,site),site) || TerrainLayerService.allowed(level,sFor(level,site),site,p.steps.get(i))))
            return new BlueprintService.PendingPlacement(-i-1,currentPlacement(level,site,p.steps.get(i),level.getBlockState(position)));
        for(int i:p.indices(position))if(i>=p.cursor && !p.finished.get(i) && !stepSatisfied(level,p,p.steps.get(i))) {
            var step=p.steps.get(i);var current=level.getBlockState(position);
            if(FoundationPlacement.coveredDig(level,p,step))continue;
            if(step.fill() && (!FoundationPlacement.replaces(level,p,step) && !current.isAir() && !soft(current) && !canFillWater(p,step,current) || !com.hkin.exiledsettlement.road.BridgeService.supported(level,p,step)))continue;
            if(!wallSupportReady(level,p,step))continue;
            if(guard(level,sFor(level,site),site,step).isEmpty() && TerrainLayerService.allowed(level,sFor(level,site),site,step))return new BlueprintService.PendingPlacement(-i-1,currentPlacement(level,site,step,current));
        }
        return null;
    }
    private static boolean wallSupportReady(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        if(project.layoutVersion<LAYOUT_VERSION || !step.target().is(Blocks.DIRT) || isGroundCap(level,project,step))return true;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            var at=step.position().offset(dx,0,dz);var target=project.template().placementAt(at);
            if(target!=null && target.state().is(Blocks.COBBLESTONE)
                    && (!level.hasChunkAt(at) || !satisfied(level.getBlockState(at),target.state())))return false;
        }
        return true;
    }
    /** A new building's missing walking plane, not a public grade or a working farm's soil. */
    private static boolean isGroundCap(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        if(project==null || project.cancelled || project.publicGround || project.road || project.bridge
                || project.areaClearance || project.roadReclaim || project.escapeFor!=null || project.playerBridge!=null
                || !project.groundSurfaces.contains(step.position()))return false;
        var target=project.template().placementAt(step.position());
        if(!step.target().is(Blocks.DIRT) && (target==null || !target.state().is(Blocks.DIRT)))return false;
        var settlement=sForProject(level,project);if(settlement==null)return false;
        for(var id:project.dependencies){var parent=settlement.blueprint(id);
            if(com.hkin.exiledsettlement.citizen.WorkTaskService.building(settlement,parent)
                    && parent.region()==null && needsFoundation(settlement,parent))return true;
        }
        return false;
    }
    /** Preserve a real surface added after survey, including a paired clearance before its fill. */
    private static boolean acceptGroundCap(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        if(!isGroundCap(level,project,step) || !level.hasChunkAt(step.position()))return false;
        var current=level.getBlockState(step.position());
        if(!FoundationFootprint.usableSurface(level,step.position(),current))return false;
        boolean changed=false;
        for(int index:project.indices(step.position()))if(project.steps.get(index).target().is(Blocks.DIRT)){
            if(!project.steps.get(index).target().equals(current)){project.recordFill(index,current);changed=true;}
            // A paired dig must not convert an existing coloured floor into a new grey-wall request.
            // The actual surface already exists; finish its fill at the same time as adopting it.
            if(!project.finished.get(index)){project.mark(index);changed=true;}
        }
        if(changed)SettlementSavedData.get(level).touch();
        return true;
    }
    private static boolean stepSatisfied(ServerLevel level,TerrainProject project,TerrainProject.Step step) {
        if(!level.hasChunkAt(step.position()))return false;
        if(acceptGroundCap(level,project,step))return true;
        var current=level.getBlockState(step.position());
        var owner=BuriedFillMaterials.wallStep(project,step)?sForProject(level,project):null;
        if(owner!=null && BuriedFillMaterials.wallStep(project,step) && !BuriedFillMaterials.hidden(level,owner,project,step)) {
            if(!BuriedFillMaterials.wall(level,step.position(),current))return false;
            for(int i:project.indices(step.position()))if(project.steps.get(i).equals(step)){project.recordFill(i,current);break;}
            return true;
        }
        if(FoundationPlacement.applies(project,step) && !satisfied(current,step.target())) {
            var settlement=SettlementSavedData.get(level).settlements().stream()
                    .filter(s->s.terrain().projects.get(project.id)==project).findFirst().orElse(null);
            if(settlement!=null && settlement.terrain().escapeSupport(step.position(),current)
                    && BuriedFillMaterials.ordinary(level,step.position(),current)
                    && BuriedFillMaterials.hidden(level,settlement,project,step)) {
                // This block was already paid for during escape. Adopt its real
                // hidden material, rather than replace it or charge another block.
                for(int i:project.indices(step.position()))if(project.steps.get(i).equals(step)) {
                    project.recordFill(i,current);return true;
                }
            }
        }
        if(project.bridge && project.bridgeOpenFaces.contains(step.position()) && !com.hkin.exiledsettlement.road.BridgeService.allBeamsReady(level,project))
            return !step.fill() && current.isAir();
        boolean restore=current.is(Blocks.DIRT_PATH) && (project.roadReclaim || SettlementSavedData.get(level).settlements().stream().anyMatch(s->project.dependencies.stream()
                .anyMatch(id->com.hkin.exiledsettlement.region.ProductionRegionService.obsoleteFarmPath(s,id,step.position(),current))));
        if(project.bridge && step.fill() && com.hkin.exiledsettlement.road.BridgeService.matches(level,project,step.position()))return true;
        if(project.bridge && !step.fill() && com.hkin.exiledsettlement.road.BridgeService.matches(level,project,step.position()))return true;
        if(!restore && satisfied(current,step.target()))return true;
        if(farmStageSatisfied(level,project,step.position(),step.target()))return true;
        var finalPlacement=project.template().placementAt(step.position());
        if(!step.fill() && finalPlacement!=null && !finalPlacement.state().isAir()
                && canFillWater(new TerrainProject.Step(step.position(),finalPlacement.state(),current),current))return true;
        return !step.fill() && finalPlacement!=null && !finalPlacement.state().isAir()
                && !restore && satisfied(current,finalPlacement.state());
    }
    private static SettlementRecord sForProject(ServerLevel level,TerrainProject project){
        return SettlementSavedData.get(level).settlements().stream().filter(s->s.terrain().projects.get(project.id)==project).findFirst().orElse(null);
    }
    /** A paid soil/slab preparation is still complete after the farm tills or irrigates it. */
    private static boolean farmStageSatisfied(ServerLevel level,TerrainProject project,BlockPos at,BlockState target) {
        if(project.roadReclaim || !target.is(Blocks.DIRT) && !target.is(Blocks.OAK_SLAB))return false;
        for(var settlement:SettlementSavedData.get(level).settlements())for(var id:project.dependencies) {
            var building=settlement.building(id);var site=settlement.blueprint(id);
            var region=site!=null?site.region():building!=null?building.region():null;
            if(region==null || !region.agricultural())continue;
            var cell=region.cell(at);if(cell==null || !cell.ground().equals(at))continue;
            var goal=region.template().placementAt(at);
            if(goal==null || !BlueprintService.placementSatisfied(level,region.template(),goal))continue;
            if(target.is(Blocks.DIRT) && (cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.CROP
                    || cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.PATH)
                    && FarmEarthworkPlan.support(level,at.below()))return true;
            if(target.is(Blocks.OAK_SLAB) && cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.WATER
                    && target.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.TOP
                    && FarmEarthworkPlan.support(level,at.below()))return true;
        }
        return false;
    }
    public static boolean constructionComplete(ServerLevel level,TerrainProject project) {
        if(!project.done())return false;
        if(project.bridge) {
            for(var p:project.bridgeDeck)if(!com.hkin.exiledsettlement.road.BridgeService.readyDeck(level,project,p))return false;
            for(var p:project.accessSurfaces)if(!com.hkin.exiledsettlement.road.AdaptiveRoadService.walkSurface(level,p))return false;
        }
        if(project.publicGround)for(var p:project.groundSurfaces)if(!level.hasChunkAt(p)
                || !level.getBlockState(p.above()).getFluidState().isEmpty() || !level.getBlockState(p.above(2)).getFluidState().isEmpty()
                || !level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
                || !level.getBlockState(p.above(2)).getCollisionShape(level,p.above(2)).isEmpty())return false;
        // Only the surviving final fill of each position matters. Superseded or
        // abandoned stairs/soil must not keep a finished order open forever.
        for(var goal:project.template().placements())if(!goal.state().isAir()) {
            if(!level.hasChunkAt(goal.position()) || !(project.bridge && com.hkin.exiledsettlement.road.BridgeService.matches(level,project,goal.position())) && !satisfied(level.getBlockState(goal.position()),goal.state())
                    && !farmStageSatisfied(level,project,goal.position(),goal.state()))return false;
        }
        return true;
    }
    public static void completed(ServerLevel level,SettlementRecord s,BlueprintRecord site){var p=site.terrainProject();if(p.bridge)com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver.refreshCompleted(level,p.template());p.completed=true;s.terrain().status="整地完成："+p.name;s.removeBlueprint(site.markerPosition());}
    public static String cancelNear(SettlementRecord s,BlockPos at) {
        var site=s.blueprints().stream().filter(TerrainService::owned).filter(b->b.terrainProject().playerBridge==null).filter(b->b.anchor().distSqr(at)<1024)
                .min(Comparator.comparingDouble(b->b.anchor().distSqr(at))).orElse(null);
        if(site==null)return "附近没有待建整地计划";site.terrainProject().cancelled=true;site.queueCancellation();
        retainBuiltReservations(s,site.terrainProject());
        for(var step:site.terrainProject().steps)s.terrain().suppressed.add(step.position().asLong());
        return "已撤销待做整地，保留实际地基、挡土墙和填土";
    }
    public static String detail(ServerLevel level,BlockPos at,String reason) {
        return "TERRAIN_EARTHWORK|"+reason+"|"+at.getX()+"|"+at.getY()+"|"+at.getZ()+"|"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock());
    }
    public static BlockState resolve(ServerLevel level,TerrainProject.Step step) {
        var state=step.target();
        for(var direction:Direction.values())state=state.updateShape(direction,level.getBlockState(step.position().relative(direction)),level,step.position(),step.position().relative(direction));
        return state;
    }
    public static String resurvey(ServerLevel level,SettlementRecord s) {
        // Explicit settlement action only: never cancel independent agricultural or road works.
        for(var site:List.copyOf(s.blueprints()))if(owned(site) && landscapeOrder(site.terrainProject()) && !site.cancellationQueued()) {
            var old=site.terrainProject();old.cancelled=true;site.queueCancellation();
            var kept=retainPaid(level,old);
            s.terrain().projects.put(old.id,kept);
            s.terrain().cells.entrySet().removeIf(e->e.getValue().equals(old.id));s.terrain().index(kept);
        }
        s.terrain().surveyed.clear();s.terrain().blockers.clear();s.terrain().surveys.clear();s.terrain().surveySnapshots.clear();SURVEYS.remove(s);
        s.terrain().platformPlanes.clear();
        PublicGroundService.requestReview(s);
        SettlementSavedData.get(level).touch();return "已重新安排勘察，保留实际地基和挡土墙，以及玩家主动移除记录";
    }
    public static boolean reserved(SettlementRecord s,BlockPos at) {
        UUID id=s.terrain().cells.get(at);if(id==null)return false;
        return s.terrain().projects.containsKey(id);
    }
    private static void retainBuiltReservations(SettlementRecord s,TerrainProject project) {
        Set<BlockPos> built=new HashSet<>();for(int i=0;i<project.cursor;i++)if(project.steps.get(i).fill())built.add(project.steps.get(i).position());
        s.terrain().cells.entrySet().removeIf(e->e.getValue().equals(project.id)&&!built.contains(e.getKey()));
    }
    public static boolean pendingAt(SettlementRecord s,BlockPos at) {
        var id=s.terrain().cells.get(at);var site=id==null?null:s.blueprint(id);
        if(site==null || site.cancellationQueued())return false;
        var p=site.terrainProject();return p!=null && p.indices(at).stream().anyMatch(i->i>=p.cursor && !p.finished.get(i));
    }
    /** A finished ground cell is usable even while a different cell of the same order is blocked. */
    public static boolean pendingAt(ServerLevel level,SettlementRecord s,BlockPos at) {
        var id=s.terrain().cells.get(at);var site=id==null?null:s.blueprint(id);
        if(site==null || site.cancellationQueued() || site.terrainProject()==null)return false;
        var p=site.terrainProject();boolean pending=false,changed=false;
        for(int i:p.indices(at))if(i>=p.cursor && !p.finished.get(i)) {
            if(stepSatisfied(level,p,p.steps.get(i))){p.mark(i);changed=true;}else pending=true;
        }
        if(changed)SettlementSavedData.get(level).touch();
        return pending;
    }
    public static SettlementRecord protectedSettlementAt(ServerLevel level,BlockPos at) {
        for(var s:SettlementSavedData.get(level).settlements())if(s.active() && reserved(s,at))return s;return null;
    }
    public static void notePlayerRemoval(ServerLevel level,BlockPos at) {
        // Automatic road edits are maintenance inputs, not public-earthwork cancellation.
        for(var s:SettlementSavedData.get(level).settlements())if(com.hkin.exiledsettlement.road.AutoRoadService.plannedAt(s,at)
                && !com.hkin.exiledsettlement.road.RoadSpaceData.get(level).manual(at) || com.hkin.exiledsettlement.road.BridgeService.owner(s,at)!=null)return;
        if(protectedSettlementAt(level,at)==null)return;
        REMOVALS.computeIfAbsent(level,l->new LinkedHashMap<>()).put(at.immutable(),level.getGameTime());
    }
    private static void flushRemovals(ServerLevel level) {
        var pending=REMOVALS.get(level);if(pending==null)return;var iterator=pending.entrySet().iterator();int budget=64;
        while(iterator.hasNext() && budget-->0){var e=iterator.next();if(e.getValue()>=level.getGameTime())continue;
            if(level.hasChunkAt(e.getKey()) && level.getBlockState(e.getKey()).isAir()) {
                var s=protectedSettlementAt(level,e.getKey());if(s!=null) {
                    s.terrain().suppressed.add(e.getKey().asLong());var id=s.terrain().cells.get(e.getKey());var site=s.blueprint(id);
                    if(site!=null){site.terrainProject().cancelled=true;site.queueCancellation();retainBuiltReservations(s,site.terrainProject());}
                    s.terrain().status="保留玩家移除位置，相关施工已暂停 @ "+e.getKey().toShortString();SettlementSavedData.get(level).touch();
                }
            }
            iterator.remove();
        }
    }
    public static List<String> report(SettlementRecord s) {
        var lines=new ArrayList<String>();lines.add("—— 居民整地 ——");lines.add("居住区修整："+(s.terrain().enabled?"开启":"关闭")+"；"+s.terrain().status);
        lines.add("原房屋标高与外壳保持，近旁平台按高差分级；需真实泥土、圆石、石台阶、石墙、铲及镐。");
        lines.add("平台边距 "+TerrainConfig.MARGIN.get()+"格；挖填至维度真实高度边界，不设工程深度额度；过渡范围 "+TerrainConfig.TRANSITION.get()+"格。");
        lines.addAll(PublicGroundService.report(s));
        s.terrain().blockers.values().stream().limit(16).forEach(v->lines.add("勘察阻塞："+v));
        for(var p:s.terrain().projects.values().stream().filter(project->!project.completed && !project.cancelled).limit(12).toList()){var b=s.blueprint(p.id);String failure="";
            if(b!=null)failure=s.citizens().stream().filter(c->p.id.equals(c.targetBlueprintId())).map(c->c.workFailure()).filter(v->!v.isBlank()).findFirst().orElse("");
            if(!p.connectionWarning.isBlank())lines.add("通路待处理："+p.connectionWarning);
            lines.add(p.name+" @ "+p.anchor.toShortString()+"："+(p.steps.size()-p.remaining())+"/"+p.steps.size()+ (failure.isEmpty()?"":"；"+failureLabel(failure)));
            int digging=0;Map<net.minecraft.world.item.Item,Integer> materials=new LinkedHashMap<>();
            for(int i=p.cursor;i<p.steps.size();i++){if(p.finished.get(i))continue;var step=p.steps.get(i);if(!step.fill())digging++;else materials.merge(step.target().getBlock().asItem(),1,Integer::sum);}
            lines.add("剩余挖除 "+digging+"格；填筑用料："+String.join("、",materials.entrySet().stream().map(e->materialName(e.getKey())+" × "+e.getValue()).toList()));
        }
        return lines;
    }
    private static String materialName(net.minecraft.world.item.Item item) {
        if(item==net.minecraft.world.item.Items.DIRT)return "泥土";if(item==net.minecraft.world.item.Items.COBBLESTONE)return "圆石";
        if(item==net.minecraft.world.item.Items.COBBLESTONE_STAIRS)return "圆石楼梯";if(item==net.minecraft.world.item.Items.COBBLESTONE_WALL)return "圆石墙";return item.getDescription().getString();
    }
    private static String failureLabel(String value) {
        String[] detail=value.split("\\|",6);String code=detail.length==6?detail[1]:value;
        String label=switch(code){case "MATERIALS_MISSING"->"缺少施工材料";case "PROTECTED_AREA"->"权限或保护事件阻止施工";
            case "CONSTRUCTION_TARGET_CHANGED"->"目标发生改动，请重新勘察";case "FARM_EARTHWORK_NO_SUPPORT"->"等待下方连续支撑";
            case "FARM_EARTHWORK_OCCUPIED"->"等待居民或玩家离开施工位置";case "FARM_EARTHWORK_FLUID"->"液体阻挡施工";
            case "BLUEPRINT_AREA_NOT_LOADED"->"等待施工范围加载";case "CONSTRUCTION_SITE_NO_ROUTE"->"居民找不到工地通路";
            case "FARM_EARTHWORK_TOOL_MISSING"->"等待领取整地工具";default->code;};
        return label+(detail.length==6?" @ "+detail[2]+", "+detail[3]+", "+detail[4]:"");
    }
    private static String failure(String reason,BlockPos at){return reason+" @ "+at.toShortString();}
}

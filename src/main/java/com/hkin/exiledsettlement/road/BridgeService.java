package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Plans only. Bridge blocks are paid, protected actions of the normal resident work controller. */
public final class BridgeService {
    private BridgeService() { }
    private record Support(long tick,int progress,Set<BlockPos> rooted) { }
    private static final Map<TerrainProject,Support> SUPPORT=new WeakHashMap<>();
    private static boolean structurePermitted(ServerLevel level,SettlementRecord s,BlockPos at){
        if(!s.contains(at) || level.isOutsideBuildHeight(at) || !level.hasChunkAt(at) || s.community().danger(at)
                || s.decorations().forbidden(at) || s.terrain().suppressed.contains(at.asLong()) || RoadSpaceData.get(level).manual(at) || RoadSpaceData.get(level).levelAt(at)>1)return false;
        for(var b:s.buildings())if(b.active() && (b.region()!=null?b.region().cell(at)!=null:at.getX()>=b.minCorner().getX() && at.getX()<=b.maxCorner().getX() && at.getZ()>=b.minCorner().getZ() && at.getZ()<=b.maxCorner().getZ()))return false;
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.containsColumn(at))return false;
        return true;
    }

    public static TerrainProject owner(SettlementRecord s,BlockPos p) {
        var project=s.terrain().projects.get(s.terrain().cells.get(p));
        if(project!=null && project.bridge && (!project.cancelled || project.completed) && project.accessSurfaces.contains(p))return project;
        // Shared beam/headroom indexing must not hide another bridge's actual
        // registered walking surface in the same position.
        var shared=s.terrain().bridgeSurface(p);
        if(shared!=null)return shared;
        return project!=null && project.bridge && (!project.cancelled || project.completed)?project:null;
    }
    public static boolean column(SettlementRecord s,BlockPos at){return s.terrain().bridgeColumn(at);}
    /** Do not send builders past the first unfinished crossing of a consumer. */
    public static boolean workPermitted(SettlementRecord s,TerrainProject project) {
        // Shared routes can list the same crossings in opposite orders. Never create a
        // bridge-to-bridge scheduling cycle: actual rooted support and reachable work
        // positions decide which frontier a resident can build. Consumers still wait.
        if(project.bridge)return true;
        boolean roadConsumer=false;
        for(var route:s.autoRoads().routes.values())if(!route.suppressed && project.dependencies.contains(route.id)) {
            roadConsumer=true;
            var source=route.sourceBridges.stream().map(s.terrain().projects::get).filter(Objects::nonNull).filter(p->!p.completed && !p.cancelled).findFirst().orElse(null);
            if(source!=null){if(source==project)return true;continue;}
            TerrainProject first=null;int boundary=-1;
            for(int i=0;i<route.cells.size();i++) {
                var bridge=owner(s,route.cells.get(i));
                if(bridge!=null && !bridge.completed && !bridge.cancelled){first=bridge;boundary=i;break;}
            }
            if(first==null || first==project)return true;
            if(!project.bridge)for(int i=0;i<boundary;i++) {
                var at=route.cells.get(i);
                if(project.steps.stream().anyMatch(step->step.position().getX()==at.getX() && step.position().getZ()==at.getZ()))return true;
            }
        }
        if(roadConsumer)return false;
        // Reachable foundation/clearance frontiers can proceed while a shared access corridor is still being built.
        // Real navigation and support decide reachability; the finished building still checks its access dependency.
        return true;
    }
    /** Dry beam and deck above a water column; never convert the river into soil. */
    public static boolean waterSpan(ServerLevel level,BlockPos p) {
        if(!level.hasChunkAt(p))return false;
        for(int y=p.getY()-1;y>=level.getMinBuildHeight();y--) {
            var at=p.atY(y);var state=level.getBlockState(at);
            if(TerrainService.replaceableWater(state))return p.getY()>=y+2;
            if(!state.isAir() && !TerrainService.soft(state))return false;
        }
        return false;
    }
    public static boolean candidate(ServerLevel level,SettlementRecord s,BlockPos p) {
        if(!RoadEarthworkService.permitted(level,s,p))return false;
        var own=owner(s,p);
        if(own!=null && own.accessSurfaces.contains(p))return true;
        if(!waterSpan(level,p))return false;
        if(!TerrainService.replaceableWater(level.getBlockState(p.below(2))))return false;
        for(int h=-1;h<=2;h++) {
            var at=p.above(h);var state=level.getBlockState(at);
            if(!structurePermitted(level,s,at) || !state.isAir() && !TerrainService.soft(state))return false;
        }
        return true;
    }
    public static boolean span(ServerLevel level,SettlementRecord s,BlockPos p) {
        var own=owner(s,p);return own!=null && own.accessSurfaces.contains(p);
    }
    public static boolean supported(ServerLevel level,TerrainProject p,TerrainProject.Step step) {
        if(p.playerBridge!=null)return PlayerBridgeWork.supported(level,p,step);
        if(!p.bridge)return FarmEarthworkPlan.support(level,step.position().below());
        var at=step.position();var roots=rooted(level,p);
        if(p.bridgeBeams.contains(at)) {
            if(p.bridgeAnchors.contains(at))return FarmEarthworkPlan.support(level,at.below());
            for(var side:Direction.values())if(roots.contains(at.relative(side)))return true;
            return false;
        }
        // Keep the lower beam work strip open until it reaches the far shore. Early
        // decking otherwise hides the next lower beam face behind the resident's floor.
        if(p.bridgeDeck.contains(at))return roots.containsAll(p.bridgeBeams);
        if(p.bridgeRails.contains(at))return readyDeck(level,p,at.below()) && p.bridgeDeck.stream().allMatch(d->readyDeck(level,p,d));
        return FarmEarthworkPlan.support(level,at.below());
    }
    private static Set<BlockPos> rooted(ServerLevel level,TerrainProject p) {
        var cached=SUPPORT.get(p);
        if(cached!=null && cached.tick==level.getGameTime() && cached.progress==p.finished.cardinality())return cached.rooted;
        var roots=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
        for(var at:p.bridgeAnchors)if(level.hasChunkAt(at) && FarmEarthworkPlan.support(level,at.below()) && matches(level,p,at)) {
            roots.add(at);queue.add(at);
        }
        while(!queue.isEmpty()) {
            var at=queue.removeFirst();
            for(var side:Direction.values()) {
                var next=at.relative(side);
                if(p.bridgeBeams.contains(next) && !roots.contains(next) && matches(level,p,next)){roots.add(next);queue.add(next);}
            }
        }
        SUPPORT.put(p,new Support(level.getGameTime(),p.finished.cardinality(),Set.copyOf(roots)));return roots;
    }
    public static boolean allBeamsReady(ServerLevel level,TerrainProject p){return rooted(level,p).containsAll(p.bridgeBeams);}
    private static void openRepairFace(ServerLevel level,TerrainProject p,BlockPos beam) {
        if(p.playerBridge!=null)return; // On-site timed repair reaches the saved beam from the intact deck.
        var deck=beam.above();if(!p.bridgeDeck.contains(deck) || level.getBlockState(deck).isAir())return;
        if(p.indices(deck).stream().anyMatch(i->!p.finished.get(i) && !p.steps.get(i).fill()))return;
        var extra=new ArrayList<TerrainProject.Step>();var rail=deck.above();var state=level.getBlockState(rail);
        if(p.bridgeRails.contains(rail) && state.getBlock() instanceof FenceBlock){p.bridgeOpenFaces.add(rail);extra.add(new TerrainProject.Step(rail,Blocks.AIR.defaultBlockState(),state));}
        var actual=level.getBlockState(deck);if(!matches(level,p,deck))return;
        p.bridgeOpenFaces.add(deck);extra.add(new TerrainProject.Step(deck,Blocks.AIR.defaultBlockState(),actual));extra.add(new TerrainProject.Step(deck,actual,Blocks.AIR.defaultBlockState()));
        if(p.bridgeRails.contains(rail) && state.getBlock() instanceof FenceBlock)extra.add(new TerrainProject.Step(rail,state,Blocks.AIR.defaultBlockState()));
        p.appendBridgeRepair(extra);
    }
    public static boolean matches(ServerLevel level,TerrainProject p,BlockPos at) {
        var goal=p.template().placementAt(at);
        if(goal==null || !level.hasChunkAt(at))return false;var actual=level.getBlockState(at);
        if(TerrainService.satisfied(actual,goal.state()))return true;
        // A player-confirmed timber bridge must retain its specified material.
        // Ordinary road surface equivalence would accept grass, then lose the
        // crossing as soon as residents trample that grass into a lower path.
        if(p.playerBridge!=null)return false;
        if(!actual.getFluidState().isEmpty() || actual.hasBlockEntity())return false;
        if(p.bridgeAnchors.contains(at))return actual.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || actual.is(Blocks.STONE_BRICKS);
        if(p.bridgeBeams.contains(at))return (!TerrainService.natural(actual) || actual.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) && actual.isFaceSturdy(level,at,Direction.UP) && actual.isFaceSturdy(level,at,Direction.DOWN);
        if(p.bridgeDeck.contains(at))return RoadSurfaceProfile.equivalent(level,at,actual,goal.state());
        return p.bridgeRails.contains(at) && actual.getBlock() instanceof FenceBlock;
    }
    public static boolean readyDeck(ServerLevel level,TerrainProject p,BlockPos at) {
        return p.bridgeDeck.contains(at) && matches(level,p,at) && (p.playerBridge!=null? p.bridgeBeams.contains(at.below()) && matches(level,p,at.below()) : rooted(level,p).contains(at.below()));
    }
    public static boolean ready(ServerLevel level,SettlementRecord s,BlockPos at) {
        var p=owner(s,at);return p!=null && (p.playerBridge==null || p.completed) && p.accessSurfaces.contains(at)
                && (p.bridgeAnchors.contains(at)?rooted(level,p).contains(at):readyDeck(level,p,at)) && AdaptiveRoadService.walkSurface(level,at);
    }
    public static BlockState target(SettlementRecord s,BlockPos at) {
        var p=owner(s,at);var goal=p==null?null:p.template().placementAt(at);return goal==null?null:goal.state();
    }
    /** Freeze one connected crossing and attach later consumers to the same paid project. */
    public static void linkSources(SettlementRecord s,AutoRoadState.Route route) {
        for(var id:route.sourceBridges){var shared=s.terrain().projects.get(id);if(shared!=null && shared.bridge && shared.playerBridge==null){shared.dependencies.add(route.id);if(AutoRoadService.required(s,route))shared.dependencies.add(route.building);}}
    }
    /** Roads consume registered bridges; only player confirmation may create a crossing. */
    public static boolean publish(ServerLevel level,SettlementRecord s,AutoRoadState.Route route) {
        for(var at:route.cells)if(waterSpan(level,at) && owner(s,at)==null && !BridgeRoadConnections.approachGap(level,s,at)) {
            route.status="此处跨河需要玩家在建筑菜单规划桥梁";
            return false;
        }
        linkSources(s,route);
        return true;
    }
    public static void tickLevel(ServerLevel level) {
        var saved=SettlementSavedData.get(level);
        for(var s:saved.settlements())if(s.active())for(var p:s.terrain().unfinishedProjects())if(p.playerBridge!=null)PlayerBridgeWork.tick(level,s,p);

        var data=SettlementSavedData.get(level);
        for(var s:data.settlements())if(s.active())for(var project:List.copyOf(s.terrain().projects.values()))if(project.bridge && !project.cancelled) {
            if(Math.floorMod(level.getGameTime()+(project.playerBridge==null?0:project.id.hashCode()),20)!=0)continue;
            for(var route:s.autoRoads().routes.values())if(project.playerBridge==null && project.dependencies.contains(route.id) && (route.suppressed || !AutoRoadService.parentValid(level,s,route)
                    || !route.sourceBridges.contains(project.id) && route.cells.stream().noneMatch(project.accessSurfaces::contains))) {
                project.dependencies.remove(route.id);
                if(s.autoRoads().routes.values().stream().noneMatch(r->!r.suppressed && r.building.equals(route.building) && project.dependencies.contains(r.id)))project.dependencies.remove(route.building);
            }
            boolean referenced=project.playerBridge!=null || s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && project.dependencies.contains(r.id) && AutoRoadService.parentValid(level,s,r));
            if(!referenced){if(!project.completed){project.cancelled=true;var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();}continue;}
            int count=project.steps.size();boolean damaged=false;
            for(int n=0;n<Math.min(64,count);n++) {
                int i=project.bridgeReviewCursor++%count;var step=project.steps.get(i);
                if(!level.hasChunkAt(step.position()))continue;
                if(step.fill() && project.finished.get(i) && !matches(level,project,step.position())){project.reopen(i);damaged=true;if(project.bridgeBeams.contains(step.position()))openRepairFace(level,project,step.position());}
                if(step.fill() && project.bridgeBeams.contains(step.position()) && !matches(level,project,step.position()))
                    openRepairFace(level,project,step.position());
            }
            if(damaged) {
                s.resumeTerrain(project);project.completed=false;
            }
            var surfaces=List.copyOf(project.accessSurfaces);int accessStart=Math.floorMod(project.bridgeReviewCursor,surfaces.size()==0?1:surfaces.size());
            for(int n=0;n<Math.min(project.playerBridge==null?surfaces.size():64,surfaces.size());n++){var at=surfaces.get((accessStart+n)%surfaces.size());if(!level.hasChunkAt(at))continue;
                if(ready(level,s,at))RoadSpaceData.get(level).addAll(List.of(at));else RoadSpaceData.get(level).remove(at);
            }
            if(project.playerBridge==null || project.completed)project.connectionWarning=project.completed?"桥面与两岸通路已完成":"居民从已支撑岸基逐段架桥；缺料、工具或到场条件由施工面板显示";
            if(project.playerBridge!=null && project.completed){
                long connected=BridgeRoadConnections.entrances(s,project.id).stream().filter(end->s.autoRoads().routes.values().stream()
                        .anyMatch(r->r.building.equals(project.id)&&r.entrance.equals(end)&&!r.suppressed&&!r.p0Invalidated&&r.accessConnected)).count();
                long deckRoads=project.accessSurfaces.stream().filter(RoadSpaceData.get(level)::contains).count();
                project.connectionWarning="桥梁本体已完成；桥面路网 "+deckRoads+"/"+project.accessSurfaces.size()+"格；两端道路 "+connected+"/2 已接通";
            }
            data.touch();
        }
    }
}

package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Deliberately destructive, synchronous test-world terrain baking. Never used by ordinary work. */
public final class InstantTerrainService {
    private record ModeKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,UUID settlement) {}
    private static final class Mode { Result last; long rounds,stamp,blocks,nextScan; boolean attempted; String error=""; }
    private static final Map<net.minecraft.server.MinecraftServer,Map<ModeKey,Mode>> MODES=new WeakHashMap<>();
    private static Mode mode(ServerLevel level,SettlementRecord s){return MODES.getOrDefault(level.getServer(),Map.of()).get(new ModeKey(level.dimension(),s.id()));}
    public static boolean enabled(ServerLevel level,SettlementRecord s){return mode(level,s)!=null;}
    public static void disable(ServerLevel level,SettlementRecord s){
        var modes=MODES.get(level.getServer());if(modes==null)return;
        modes.remove(new ModeKey(level.dimension(),s.id()));if(modes.isEmpty())MODES.remove(level.getServer());
    }
    public static Result enable(ServerLevel level,SettlementRecord s){
        var modes=MODES.computeIfAbsent(level.getServer(),ignored->new HashMap<>());
        var mode=modes.computeIfAbsent(new ModeKey(level.dimension(),s.id()),ignored->new Mode());
        // Reissuing ON is idempotent. New input, rather than ON, drives the next solve.
        tick(level,s,true);return mode.error.isEmpty()?mode.last:null;
    }
    public static long rounds(ServerLevel level,SettlementRecord s){var m=mode(level,s);return m==null?0:m.rounds;}
    public static String status(ServerLevel level,SettlementRecord s){
        return statusMessage(level,s).getString();
    }
    public static net.minecraft.network.chat.Component statusMessage(ServerLevel level,SettlementRecord s){
        var m=mode(level,s);String prefix="message.exiledsettlement.debug.terrain_evolve.";
        if(m==null)return net.minecraft.network.chat.Component.translatable(prefix+"continuous_off");
        if(!m.error.isEmpty())return net.minecraft.network.chat.Component.translatable(prefix+"continuous_error",m.error);
        return net.minecraft.network.chat.Component.translatable(prefix+"continuous_on",m.rounds,
                m.last==null?0:m.last.columns(),m.last==null?0:m.last.changedBlocks(),m.last==null?0:m.last.failedRoads());
    }
    public static void tick(ServerLevel level,SettlementRecord s){tick(level,s,false);}
    private static void tick(ServerLevel level,SettlementRecord s,boolean requested){
        var m=mode(level,s);if(m==null || active(s))return;
        if(!requested && level.getGameTime()<m.nextScan && !PublicGroundService.reviewQueued(s))return;
        // This is only an idle-world probe interval; a solve itself has no tick/work quota.
        m.nextScan=level.getGameTime()+20;
        long stamp=PublicGroundService.inputSignature(level,s),blocks=surfaceStamp(level,s);
        if(m.attempted && stamp==m.stamp && blocks==m.blocks && (!requested || m.error.isEmpty()))return;
        m.attempted=true;
        try{
            m.last=finish(level,s);m.rounds++;m.error="";
            m.stamp=PublicGroundService.inputSignature(level,s);m.blocks=surfaceStamp(level,s);
        }catch(RuntimeException e){
            m.error=e.getClass().getSimpleName()+": "+e.getMessage();
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("Continuous terrain evolution failed for {}",s.id(),e);
            // Preserve the switch and diagnostics, but do not throw on every server tick.
            m.stamp=stamp;m.blocks=blocks;m.nextScan=level.getGameTime()+20;
        }
    }
    private static long surfaceStamp(ServerLevel level,SettlementRecord s){
        long h=1;
        var w=s.terrain().publicGround;var columns=new LinkedHashSet<>(w.mask);columns.addAll(w.goals.keySet());
        for(long k:columns){
            var p=BlockPos.of(k);if(reserved(s,p))continue;
            if(!level.hasChunkAt(p)){h=31*h+k;continue;}
            p=p.atY(w.goals.getOrDefault(k,currentSurface(level,p)));
            h=31*h+level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ());
            for(int y=-1;y<=4;y++){
                var state=level.getBlockState(p.above(y));
                if(state.is(Blocks.GRASS_BLOCK))state=Blocks.DIRT.defaultBlockState();
                h=31*h+Block.getId(state);
            }
        }
        return h;
    }
    private static int currentSurface(ServerLevel level,BlockPos p){return level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())-1;}
    private static final ThreadLocal<SettlementRecord> ACTIVE=new ThreadLocal<>();
    public static boolean active(SettlementRecord s){return ACTIVE.get()==s;}
    public static boolean reserved(SettlementRecord s,BlockPos p){
        if(p.getX()==s.corePosition().getX() && p.getZ()==s.corePosition().getZ() || BridgeService.column(s,p))return true;
        for(var b:s.buildings())if(b.active() && b.containsColumn(p))return true;
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.containsColumn(p))return true;
        return false;
    }
    public record Result(int columns,int changedBlocks,int roadCells,int failedRoads,long planningMillis,long totalMillis){}
    public static Result finish(ServerLevel level,SettlementRecord s){
        long start=System.nanoTime(),planning=0;
        var previous=ACTIVE.get();ACTIVE.set(s);var w=s.terrain().publicGround;w.instantSampling=true;
        var changes=new LinkedHashMap<BlockPos,TerrainProject.Step>();
        try{
            AutoRoadPlanner.invalidate(s);PublicRoadIntegration.reset(s);
            // Retire stale public targets before solving; they must not pin or replay old elevations.
            for(var project:List.copyOf(s.terrain().projects.values()))
                if((project.publicGround || project.road || project.roadReclaim) && !project.bridge && !project.explicit){
                    project.cancelled=true;project.heightSuperseded=true;s.terrain().index(project);
                    var site=s.blueprint(project.id);if(site!=null)s.removeBlueprint(site.markerPosition());
                }
            for(var r:s.autoRoads().routes.values())r.constructionGradeFixed=false;
            var oldRoutes=new LinkedHashMap<>(s.autoRoads().routes);
            s.autoRoads().routes.entrySet().removeIf(e->!e.getValue().manualBridge);
            var registry=RoadSpaceData.get(level);
            for(var r:oldRoutes.values())if(!r.manualBridge){
                for(var p:r.cells)if(!registry.manual(p) && registry.levelAt(p)<=1)registry.remove(p);
                for(var p:r.retired)if(!registry.manual(p) && registry.levelAt(p)<=1)registry.remove(p);
            }
            long mark=System.nanoTime();PublicGroundService.solveInstant(level,s);planning+=System.nanoTime()-mark;
            apply(level,s,changes);
            long[] applicationNanos={0};mark=System.nanoTime();var failed=AutoRoadPlanner.finishInstant(level,s,oldRoutes,()->{
                long before=System.nanoTime();apply(level,s,changes);applicationNanos[0]+=System.nanoTime()-before;
            });
            PublicGroundService.solveInstant(level,s);planning+=System.nanoTime()-mark-applicationNanos[0];
            apply(level,s,changes);
            // Surface-dependent rails are evaluated only after every final ground column exists.
            for(var e:w.goals.entrySet()){
                var p=BlockPos.of(e.getKey()).atY(e.getValue());if(reserved(s,p))continue;
                if(PublicEdgeSafety.needed(level,s,p))put(level,changes,p.above(),Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
            for(var p:changes.keySet())BlueprintBlockStateResolver.refreshAround(level,p);
            var steps=new ArrayList<TerrainProject.Step>();
            for(var e:changes.entrySet())steps.add(new TerrainProject.Step(e.getKey(),level.getBlockState(e.getKey()),e.getValue().original()));
            var project=new TerrainProject(UUID.randomUUID(),"测试：直接完成地形与道路",s.corePosition(),steps);
            project.publicGround=true;project.layoutVersion=TerrainService.LAYOUT_VERSION;project.completed=true;
            project.finished.set(0,steps.size());project.cursor=steps.size();
            for(var e:w.goals.entrySet()){
                var p=BlockPos.of(e.getKey()).atY(e.getValue());if(!reserved(s,p))project.groundSurfaces.add(p);
            }
            int roadCells=0;var roads=RoadSpaceData.get(level);
            for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated && !r.manualBridge){
                r.completed.clear();r.repairs.clear();
                for(var p:r.cells)if(!reserved(s,p) && RoadSurfaceProfile.usable(level,p)){
                    r.mark(p);roads.addAll(List.of(p));project.accessSurfaces.add(p);roadCells++;
                }
                r.advance();r.admitted.or(r.completed);r.earthworkCursor=r.progress;
                String blocker=RoadEarthworkService.accessBlocker(level,s,r);
                r.accessConnected=r.progress==r.cells.size() && blocker.isEmpty();
                if(!r.accessConnected)failed.add(r.building);
                r.status=r.accessConnected?"测试直接完成":"通路未完成："+blocker;
                if(!r.accessConnected)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("Instant route incomplete: end={}, progress={}/{}, blocker={}",r.entrance,r.progress,r.cells.size(),blocker);
            }
            s.terrain().index(project);
            w.planned.clear();w.planned.addAll(w.goals.keySet());w.phase=0;w.planCursor=w.goals.size();
            w.capacityPaused=false;w.nextReview=level.getGameTime()+1200;
            w.status="测试直接完成："+project.groundSurfaces.size()+"列，"+changes.size()+"处方块变化";
            s.terrain().status=w.status;SettlementSavedData.get(level).touch();
            return new Result(project.groundSurfaces.size(),changes.size(),roadCells,failed.size(),planning/1_000_000,(System.nanoTime()-start)/1_000_000);
        }finally{w.instantSampling=false;if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    private static long column(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    private static void apply(ServerLevel level,SettlementRecord s,Map<BlockPos,TerrainProject.Step> changes){
        var w=s.terrain().publicGround;var targets=new LinkedHashMap<Long,BlockPos>();
        var surfaces=new HashMap<Long,BlockState>();
        for(var e:w.goals.entrySet()){
            var p=BlockPos.of(e.getKey()).atY(e.getValue());if(reserved(s,p))continue;
            targets.put(e.getKey(),p);var facing=w.stairs.get(e.getKey());
            surfaces.put(e.getKey(),facing!=null && !w.anchors.containsKey(e.getKey())
                    ?Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing):Blocks.GRASS_BLOCK.defaultBlockState());
        }
        var roads=RoadSpaceData.get(level);
        for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated && !r.manualBridge){
            for(var p:r.cells){if(reserved(s,p))continue;level.getChunkAt(p);
                targets.put(column(p),p);var target=RoadEarthworkService.surfaceTarget(level,s,r,p);
                surfaces.put(column(p),target==null?level.getBlockState(p):target.is(Blocks.DIRT)?Blocks.DIRT_PATH.defaultBlockState():target);
            }
        }
        // Reclaim against the complete new network, never against a partially collected route list.
        for(var r:s.autoRoads().routes.values())if(!r.manualBridge){
            for(var p:List.copyOf(r.retired))if(!targets.containsKey(column(p)) && !reserved(s,p)){
                put(level,changes,p,Blocks.AIR.defaultBlockState());
                var below=p.below();
                while(below.getY()>level.getMinBuildHeight() && (level.getBlockState(below).is(Blocks.COBBLESTONE)
                        || level.getBlockState(below).getBlock() instanceof StairBlock)){
                    put(level,changes,below,Blocks.AIR.defaultBlockState());below=below.below();
                }
                if(TerrainSurface.ground(level,below))put(level,changes,below,Blocks.GRASS_BLOCK.defaultBlockState());
                roads.remove(p);
            }
            r.retired.clear();
        }
        for(var old:List.copyOf(roads.decorationPositions())){
            var p=targets.get(column(old));if(p!=null && !old.equals(p))roads.remove(old);
        }
        for(var e:targets.entrySet()){
            var p=e.getValue();int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())-1;
            for(int y=top;y>p.getY();y--)put(level,changes,p.atY(y),Blocks.AIR.defaultBlockState());
            int face=p.getY();
            for(var d:Direction.Plane.HORIZONTAL){var n=p.relative(d);var goal=targets.get(column(n));int y=goal==null?PublicGroundService.currentGround(level,n):goal.getY();face=Math.min(face,y+1);}
            int bottom=Math.min(p.getY()-1,face-1);
            while(bottom>level.getMinBuildHeight() && !TerrainSurface.ground(level,p.atY(bottom)))bottom--;
            for(int y=bottom+1;y<p.getY();y++)put(level,changes,p.atY(y),y>=face?Blocks.COBBLESTONE.defaultBlockState():Blocks.DIRT.defaultBlockState());
            put(level,changes,p,surfaces.get(e.getKey()));
        }
    }
    private static void put(ServerLevel level,Map<BlockPos,TerrainProject.Step> changes,BlockPos p,BlockState target){
        var actual=level.getBlockState(p);if(actual.equals(target))return;
        changes.putIfAbsent(p,new TerrainProject.Step(p,target,actual));level.setBlock(p,target,2);
    }
    private InstantTerrainService(){}
}

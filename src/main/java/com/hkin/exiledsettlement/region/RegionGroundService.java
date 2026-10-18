package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Plain soil regions share earthwork, never the crop/water production contract. */
public final class RegionGroundService {
    private RegionGroundService() {}
    public static final int CLEARANCE_HEIGHT=6;
    public static int clearanceHeight(ProductionRegion r){return r.type==BuildingType.TREE_FARM?CLEARANCE_HEIGHT:4;}
    public static int clearanceTop(ProductionRegion r,int groundY,int worldTop){return Math.min(worldTop,groundY+clearanceHeight(r));}
    public static boolean clearanceExempt(ServerLevel l,ProductionRegion r,ProductionRegion.Cell c,BlockPos at){
        var state=l.getBlockState(at);var goal=r.template().placementAt(at);
        return state.isAir() || goal!=null && !goal.state().isAir() && BlueprintService.placementSatisfied(l,r.template(),goal)
            || r.type==BuildingType.TREE_FARM && keepTree(l,r,c.ground(),state,at);
    }
    public static boolean clearanceNeeded(ServerLevel l,ProductionRegion r,ProductionRegion.Cell c){
        for(int h=1;h<=clearanceHeight(r);h++)if(!clearanceExempt(l,r,c,c.ground().above(h)))return true;
        return false;
    }
    public static boolean pendingColumn(ServerLevel l,SettlementRecord s,BlockPos p){
        return s!=null && (TerrainService.pendingAt(l,s,p) || TerrainService.pendingAt(l,s,p.below()) || TerrainService.pendingAt(l,s,p.above()));
    }
    public static boolean plantReady(ServerLevel l,BuildingRecord b,BlockPos p){
        var r=b.region();if(r==null || !r.plainSoil())return true;
        var c=r.cell(p);if(c==null || c.use()!=ProductionRegion.Use.FOREST || !r.groundReady(l,c))return false;
        var s=SettlementSavedData.get(l).findContaining(p).orElse(null);
        return !pendingColumn(l,s,c.ground()) && columnWait(l,s,null,c.ground(),r).isEmpty();
    }
    /** Do not clear a healthy existing tree merely because its canopy is above the soil plane. */
    public static boolean keepTree(ServerLevel l,ProductionRegion r,BlockPos soil,BlockState state,BlockPos at){
        // gradeOrigins is historical earthwork evidence, not the current planting surface.
        // Once the accepted soil exists, replanted/grown trees must survive clearance reviews.
        var cell=r.cell(soil);
        if(r.rectangularSelection && cell!=null && cell.use()!=ProductionRegion.Use.FOREST)return false;
        // A lower accepted plane beneath an unexcavated soil column is not a planted surface.
        for(int y=soil.getY()+1;y<at.getY();y++)if(FarmEarthworkPlan.soil(l.getBlockState(soil.atY(y))))return false;
        return cell!=null && at.getY()>cell.ground().getY() && r.groundReady(l,cell)
            && (state.is(BlockTags.LOGS) || FarmVegetation.leaf(state) || state.getBlock() instanceof SaplingBlock || state.is(Blocks.BEE_NEST));
    }
    public static boolean protectsTree(ServerLevel l,SettlementRecord s,TerrainProject project,BlockPos at){
        var state=l.getBlockState(at);
        if(!(state.is(BlockTags.LOGS) || FarmVegetation.leaf(state) || state.getBlock() instanceof SaplingBlock || state.is(Blocks.BEE_NEST)))return false;
        if(project.regionOwner!=null && protectsTree(l,RegionWorkScope.region(s,project.regionOwner),at,state))return true;
        for(var id:project.dependencies)if(protectsTree(l,RegionWorkScope.region(s,id),at,state))return true;
        return false;
    }
    private static boolean protectsTree(ServerLevel l,ProductionRegion r,BlockPos at,BlockState state){
        if(r==null || r.type!=BuildingType.TREE_FARM)return false;
        var cell=r.cell(at);return cell!=null && keepTree(l,r,cell.ground(),state,at);
    }
    public static String columnWait(ServerLevel l,SettlementRecord s,BlueprintRecord b,BlockPos p){return columnWait(l,s,b,p,b.region());}
    private static String columnWait(ServerLevel l,SettlementRecord s,BlueprintRecord b,BlockPos p,ProductionRegion r){
        if(!r.plainSoil())return "";
        if(s!=null && RoadSpaceData.get(l).contains(p))return "保留已登记道路："+p.toShortString();
        String animals=PastureGroundSafety.columnWait(l,r,p);if(!animals.isEmpty())return animals;
        // Accepted new/expanded earthwork clears recorded natural obstacles through worker
        // orders. Waiting for production lumberjacks here would deadlock the handover gate.
        return "";
    }
    public static boolean goalAllowed(ServerLevel l,BlueprintRecord b,BlockPos p){
        var r=b.region();if(r==null || !r.plainSoil())return true;
        var s=SettlementSavedData.get(l).settlements().stream().filter(v->v.blueprint(b.id())==b).findFirst().orElse(null);
        if(r.groundPaused || !columnWait(l,s,b,p).isEmpty())return false;
        var c=r.cell(p);return c!=null && !pendingColumn(l,s,c.ground()) && r.groundReady(l,c);
    }
    /** Flood only the accepted water columns: anything connected outside remains protected. */
    public static boolean excludeConnectedWater(net.minecraft.world.level.Level l,ProductionRegion r){
        r.groundBlockers.entrySet().removeIf(e->e.getValue().startsWith("排除与外界连通水体"));
        boolean blocked=false;
        var unseen=new LinkedHashSet<>(r.waterFill);
        while(!unseen.isEmpty()){
            long first=unseen.iterator().next();var seen=new HashSet<Long>();var q=new ArrayDeque<Long>();q.add(first);unseen.remove(first);boolean river=false;
            while(!q.isEmpty()){
                long k=q.remove();seen.add(k);var c=r.cells.get(k);if(c==null)continue;
                for(var d:Direction.Plane.HORIZONTAL){var p=c.ground().relative(d);long next=ProductionRegion.key(p.getX(),p.getZ());
                    if(!l.hasChunkAt(p)){river=true;continue;}
                    if(!r.cells.containsKey(next) && !l.getFluidState(p).isEmpty())river=true;
                    if(unseen.remove(next))q.add(next);
                }
            }
            if(river)for(long k:seen){blocked=true;var c=r.rectangularSelection?r.cells.get(k):r.cells.remove(k);if(!r.rectangularSelection)r.waterFill.remove(k);if(c!=null)r.groundBlockers.put(k,"排除与外界连通水体："+c.ground().toShortString());}
        }
        r.invalidate();
        return !r.rectangularSelection || !blocked;
    }
    public static boolean toggle(SettlementRecord s,UUID id){
        var b=s.building(id);var job=s.blueprint(id);var r=job!=null?job.region():b!=null?b.region():null;
        if(r==null || !r.plainSoil())return false;r.groundPaused=!r.groundPaused;r.invalidate();
        for(var p:s.blueprints())if(p.region()!=null && id.equals(p.region().workingBuilding))p.region().groundPaused=r.groundPaused;
        return true;
    }
    public static String summary(ServerLevel l,SettlementRecord s,ProductionRegion r){
        long soil=r.cells.values().stream().filter(c->r.groundReady(l,c)).count();long grass=r.cells.values().stream().filter(c->l.getBlockState(c.ground()).is(Blocks.GRASS_BLOCK)).count();
        long trees=r.groundBlockers.values().stream().filter(v->v.startsWith("等待伐木工")).count();
        long remaining=s.terrain().projects.values().stream().filter(p->r.workingBuilding!=null && p.dependencies.contains(r.workingBuilding) && !p.cancelled).mapToLong(p->p.remaining()).sum();
        boolean active=s.blueprints().stream().anyMatch(b->b.region()!=null && !b.cancellationQueued() && (b.region()==r || r.workingBuilding!=null && r.workingBuilding.equals(b.region().workingBuilding)));
        var dirt=new HashSet<BlockPos>();
        for(var p:s.terrain().projects.values())if(!p.cancelled && r.workingBuilding!=null && p.dependencies.contains(r.workingBuilding))
            for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && !p.abandoned.get(i) && p.steps.get(i).target().is(Blocks.DIRT))dirt.add(p.steps.get(i).position());
        long fences=r.template().placements().stream().filter(p->p.state().getBlock() instanceof FenceBlock && !BlueprintService.placementSatisfied(l,r.template(),p)).count();
        long gates=r.template().placements().stream().filter(p->p.state().getBlock() instanceof FenceGateBlock && !BlueprintService.placementSatisfied(l,r.template(),p)).count();
        int herd=r.animalSafety.getList("Animals",net.minecraft.nbt.Tag.TAG_COMPOUND).size();
        String result="地坪Y="+(r.flatY==null?"勘察中":r.flatY)+" 土层 "+soil+"/"+r.cells.size()+" 草地 "+grass+" 待土方 "+remaining+" 已规划材料（土/栏/门） "+dirt.size()+"/"+fences+"/"+gates+" 待采树列 "+trees+" 保护牲畜 "+herd+" 隔离阶段 "+r.animalSafety.getInt("Phase")+" ["+(r.groundPaused?"已暂缓":active?"施工中":"已完成")+"] "+(active?r.status:"统一地面与围合完成，生产使用实际土层");
        return result.length()>480?result.substring(0,480):result;
    }
    public static void tick(ServerLevel l,SettlementRecord s){
        if(l.getGameTime()%20!=0)return;
        // Recover physical leashes before any new movement, including cancelled work.
        PastureGroundSafety.recoverLeads(l,s);
        for(var b:List.copyOf(s.buildings()))if(b.active() && !b.demolitionQueued() && (b.type()==BuildingType.TREE_FARM || b.type()==BuildingType.PASTURE_AREA)){
            var pending=s.blueprints().stream().filter(p->p.region()!=null && !p.cancellationQueued() && b.id().equals(p.region().workingBuilding)).findFirst().orElse(null);
            if(pending!=null){PastureGroundSafety.prepare(l,s,pending);continue;}
            var current=b.region();
            if(current!=null && current.groundPaused)continue;
            // Test-version contract: only current region records are maintained; no old-save migration.
            if(current==null || current.groundVersion!=RegionGroundLayout.VERSION)continue;
            if(l.getGameTime()<current.nextReview)continue;
            boolean damaged=false;var cells=current.ordered();
            for(int budget=0;budget<32 && current.reviewCursor<cells.size();budget++){
                var c=cells.get(current.reviewCursor++);if(!l.hasChunkAt(c.ground())){current.reviewCursor--;break;}
                if(!current.groundReady(l,c) || clearanceNeeded(l,current,c))current.reviewFixed.add(c.ground().asLong());
            }
            if(current.reviewCursor<cells.size())continue;
            damaged=!current.reviewFixed.isEmpty() || current.template().placements().stream().filter(p->p.state().getBlock() instanceof FenceBlock || p.state().getBlock() instanceof FenceGateBlock).anyMatch(p->!BlueprintService.placementSatisfied(l,current.template(),p));
            current.reviewCursor=0;current.reviewFixed.clear();current.nextReview=l.getGameTime()+1200;
            current.reviewStatus=damaged?"统一地坪复查：安排居民真实修复":"土层和围合复查完成";
            if(damaged)publish(s,b,ProductionRegion.load(current.save()));SettlementSavedData.get(l).touch();
        }
    }
    private static void publish(SettlementRecord s,BuildingRecord b,ProductionRegion r){
        r.workingBuilding=b.id();r.groundMaintenance=true;r.layoutReady=true;r.prepared=false;r.preparationCursor=0;r.preparationRetries.clear();
        r.animalSafety.putInt("Phase",0);var marker=b.position().above(96);while(s.blueprintAt(marker)!=null)marker=marker.above();
        var job=s.addAreaBlueprint(b.type(),b.position(),marker,b.facing(),s.ownerId(),false,r.min(),r.max());job.withRegion(r);b.withRegion(r);
        if(b.type()==BuildingType.TREE_FARM)b.updateFunction(false,BuildingStatus.UNDER_CONSTRUCTION,b.workerCapacity(),List.of());
    }
}

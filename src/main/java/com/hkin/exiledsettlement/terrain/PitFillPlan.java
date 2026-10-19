package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Bounded, read-only column survey. Publishing never places blocks or supplies materials. */
public final class PitFillPlan {
    public static final int MAX_AREA=1024, MAX_DEPTH=32;
    public final BlockPos first,min,max;
    public final List<TerrainProject.Step> steps=new ArrayList<>();
    public int cursor,excluded,water;
    public boolean ready;
    public String problem="";
    public PitFillPlan(BlockPos first,BlockPos second) {
        this.first=first.immutable();
        min=new BlockPos(Math.min(first.getX(),second.getX()),first.getY(),Math.min(first.getZ(),second.getZ()));
        max=new BlockPos(Math.max(first.getX(),second.getX()),first.getY(),Math.max(first.getZ(),second.getZ()));
        long width=(long)max.getX()-min.getX()+1,depth=(long)max.getZ()-min.getZ()+1;
        if(width*depth>MAX_AREA)problem="选区最多1024格，请分区填筑";
    }
    public void survey(ServerLevel level,SettlementRecord s,int budget) {
        if(ready || !problem.isEmpty())return;
        if(!level.hasChunkAt(first) || !s.contains(first) || !PitFillSurface.ground(level,first)
                || !level.getBlockState(first).getFluidState().isEmpty()) {
            problem="首点须选在真实、干燥的岸边地面，填至该地面高度";return;
        }
        int width=max.getX()-min.getX()+1,total=width*(max.getZ()-min.getZ()+1);
        while(budget-->0 && cursor<total) {
            int n=cursor++;var cap=min.offset(n%width,0,n/width);
            var column=new ArrayList<TerrainProject.Step>();boolean supported=false,blocked=false;
            for(int depth=0;depth<=MAX_DEPTH;depth++) {
                var at=cap.below(depth);
                if(level.isOutsideBuildHeight(at)||!level.hasChunkAt(at)) {problem="选区含未加载或越界位置："+at.toShortString();return;}
                var state=level.getBlockState(at);
                if(PlayerWorksService.protectedAt(s,at) || RoadSpaceData.get(level).contains(at)
                        || s.terrain().suppressed.contains(at.asLong())){blocked=true;break;}
                if(!state.isAir() && !TerrainService.replaceableWater(state) && !PitFillSurface.vegetation(state)) {
                    supported=PitFillSurface.ground(level,at);break;
                }
                if(PitFillSurface.vegetation(state) && state.getBlock() instanceof DoublePlantBlock) {
                    var other=state.getValue(DoublePlantBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER?at.above():at.below();
                    if(!level.hasChunkAt(other) || PlayerWorksService.protectedAt(s,other) || RoadSpaceData.get(level).contains(other)
                            || s.terrain().suppressed.contains(other.asLong())){blocked=true;break;}
                }
                if(depth==MAX_DEPTH)break;
                column.add(new TerrainProject.Step(at,Blocks.COBBLESTONE.defaultBlockState(),state));
            }
            if(blocked || !supported){excluded++;continue;}
            Collections.reverse(column);steps.addAll(column);
            water+=column.stream().filter(v->TerrainService.replaceableWater(v.original())).count();
        }
        ready=cursor==total;
        if(ready && steps.isEmpty())problem="选区没有可填坑洞；受保护、无支撑或超过32格深的列已排除";
    }
    public String description(ServerLevel level,SettlementRecord s) {
        int available=0;
        for(var b:SettlementInventoryService.warehouses(s))for(var e:WarehouseInventoryService.summarize(level,b).entrySet())
            if(e.getKey() instanceof net.minecraft.world.item.BlockItem item && BuriedFillMaterials.ordinary(level,first,item.getBlock().defaultBlockState()))available+=e.getValue();
        return (problem.isEmpty()?"":problem+"\n")+"范围 "+min.toShortString()+" 至 "+max.toShortString()
                +"\n填筑顶面 Y="+PitFillSurface.top(first)+"（方块层 Y="+first.getY()+"）；填筑 "+steps.size()+" 格，其中水格 "+water+"，排除 "+excluded+" 列。"
                +"\n灰色石料：需要 "+steps.size()+" / 仓库 "+available
                +"\n圆石、石头、安山岩、磨制安山岩、石砖逐格混用。最深32格；草丛先由居民清理，保留已有地面、土径、楼梯和设施。缺料等待，居民取料后逐格施工。";
    }
    public TerrainProject publish(ServerLevel level,SettlementRecord s,UUID owner) {
        if(!ready || !problem.isEmpty())throw new IllegalStateException(problem.isEmpty()?"尚未勘察完成":problem);
        for(var step:steps)if(!level.hasChunkAt(step.position()) || !level.getBlockState(step.position()).equals(step.original())
                || PlayerWorksService.protectedAt(s,step.position()) || RoadSpaceData.get(level).contains(step.position())
                || s.terrain().suppressed.contains(step.position().asLong()))throw new IllegalStateException("选区已经变化，请重新框选勘察");
        var marker=min.above(96);while(s.blueprintAt(marker)!=null)marker=marker.above();
        var site=s.addBlueprint(BuildingType.HOUSE,min,marker,Direction.NORTH,owner,false,1,Map.of());
        var project=new TerrainProject(site.id(),"框选填坑",min,steps);project.explicit=true;project.pitFill=true;
        site.withTerrainProject(project);s.terrain().index(project);SettlementSavedData.get(level).touch();return project;
    }
    public static String guard(ServerLevel level,SettlementRecord s,TerrainProject project,TerrainProject.Step step) {
        if(project==null || !project.pitFill)return "";
        var at=step.position();var state=level.getBlockState(at);
        if(s.terrain().suppressed.contains(at.asLong()) || s.decorations().forbidden(at)
                || RoadSpaceData.get(level).contains(at) || com.hkin.exiledsettlement.road.BridgeService.column(s,at)
                || s.buildings().stream().anyMatch(b->b.active() && b.containsColumn(at))
                || s.formalBlueprints().stream().anyMatch(b->!b.cancellationQueued() && b.containsColumn(at))
                || s.terrain().projectsAt(at).stream().anyMatch(p->p!=project && !p.cancelled && !p.completed))return "PROTECTED_AREA";
        if(!step.fill()) {
            if(!PitFillSurface.vegetation(state) && !state.isAir())return "CONSTRUCTION_TARGET_CHANGED";
            boolean authorised=project.indices(at).stream().anyMatch(i->project.steps.get(i).fill());
            if(!authorised && PitFillSurface.vegetation(state) && state.getBlock() instanceof DoublePlantBlock) {
                var other=state.getValue(DoublePlantBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER?at.above():at.below();
                authorised=level.getBlockState(other).is(state.getBlock())
                        && project.indices(other).stream().anyMatch(i->project.steps.get(i).fill());
            }
            return authorised?"":"PROTECTED_AREA";
        }
        if(!state.isAir() && !TerrainService.replaceableWater(state) && !PitFillSurface.vegetation(state))return "CONSTRUCTION_TARGET_CHANGED";
        if(!PitFillSurface.ground(level,at.below()))return "FARM_EARTHWORK_NO_SUPPORT";
        if(FoundationPlacement.occupied(level,at,false))return "FARM_EARTHWORK_OCCUPIED";
        return "";
    }
    private PitFillPlan(){throw new UnsupportedOperationException();}
}

package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.blueprint.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.Half;
import java.util.*;

/** Replace a paid low-side stair with a stair in the upper bank, through real resident work. */
public final class PublicStairRepairService {
    private record Candidate(TerrainProject project,int index){ }
    private record Scan(int count,List<Candidate> candidates,int cursor){ }
    private static final Map<SettlementRecord,Scan> SCANS=new WeakHashMap<>();
    private PublicStairRepairService(){ }
    public static void tick(ServerLevel level,SettlementRecord s){
        if(level.getGameTime()%20!=0 || s.terrain().publicPaused || !s.terrain().enabled && !s.autoRoads().enabled)return;
        var scan=SCANS.get(s);
        if(scan==null || scan.count()!=s.terrain().projects.size()){
            var list=new ArrayList<Candidate>();
            for(var project:s.terrain().projects.values())if(!project.cancelled && project.stairLayoutVersion==0)for(int i=0;i<project.steps.size();i++)
                if(!project.road && !project.publicGround && project.accessSurfaces.contains(project.steps.get(i).position()) && project.steps.get(i).target().getBlock() instanceof StairBlock)list.add(new Candidate(project,i));
            scan=new Scan(s.terrain().projects.size(),list,0);
        }
        int cursor=scan.cursor();
        for(int inspected=0;inspected<8 && !scan.candidates().isEmpty();inspected++){
            var c=scan.candidates().get(cursor++%scan.candidates().size());var original=c.project().steps.get(c.index());var at=original.position();
            if(c.project().cancelled || c.project().abandoned.get(c.index()) || !level.hasChunkAt(at) || TerrainService.pendingAt(s,at))continue;
            var state=level.getBlockState(at);
            if(!(state.getBlock() instanceof StairBlock) || state.getValue(StairBlock.HALF)!=Half.BOTTOM
                    || !BlueprintService.matchesPlacedState(state,original.target()))continue;
            var high=at.relative(state.getValue(StairBlock.FACING)).above();var soil=level.getBlockState(high);
            if(!FarmEarthworkPlan.soil(soil) && !soil.is(Blocks.DIRT_PATH) || !RoadEarthworkService.permitted(level,s,at)
                    || !RoadEarthworkService.permitted(level,s,high) || TerrainService.pendingAt(s,high)
                    || !level.getBlockState(high.above()).getCollisionShape(level,high.above()).isEmpty()
                    || !level.getBlockState(high.above(2)).getCollisionShape(level,high.above(2)).isEmpty())continue;
            var roads=RoadSpaceData.get(level);if(roads.manual(at) || roads.manual(high) || roads.levelAt(at)>1 || roads.levelAt(high)>1)continue;
            var oldOwners=new LinkedHashMap<BlockPos,UUID>();
            for(var p:List.of(at,high)){var owner=s.terrain().cells.remove(p);if(owner!=null)oldOwners.put(p,owner);}
            var steps=List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state),
                    new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),
                    new TerrainProject.Step(high,Blocks.AIR.defaultBlockState(),soil),
                    new TerrainProject.Step(high,state,Blocks.AIR.defaultBlockState()));
            var project=ProductionRegionService.publish(level,s,c.project().id,"低位台阶通行修正",at,steps);
            if(project==null){s.terrain().cells.putAll(oldOwners);continue;}
            for(var e:oldOwners.entrySet()){
                var owner=s.terrain().projects.get(e.getValue());if(owner!=null)for(int i:owner.indices(e.getKey()))owner.abandon(i);
            }
            c.project().abandon(c.index());project.explicit=false;project.accessSurfaces.add(at);project.accessSurfaces.add(high);
            break;
        }
        SCANS.put(s,new Scan(scan.count(),scan.candidates(),scan.candidates().isEmpty()?0:cursor%scan.candidates().size()));
    }
}

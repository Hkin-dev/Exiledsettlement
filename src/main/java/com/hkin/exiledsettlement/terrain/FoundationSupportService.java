package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Paid repair orders below an unfinished, registered soil column. */
public final class FoundationSupportService {
    private static final Map<TerrainProject,Long> CHECKED=new WeakHashMap<>();
    public static void review(ServerLevel l,SettlementRecord s,BlueprintRecord site){
        var p=site.terrainProject();
        if(p==null || p.cancelled || p.heightSuperseded || p.bridge || p.roadReclaim || p.areaClearance)return;
        var parents=p.dependencies.stream().map(s::blueprint).filter(b->WorkTaskService.building(s,b)).toList();
        if(parents.isEmpty() && !p.publicGround && !p.road)return;
        long now=l.getGameTime();var checked=CHECKED.get(p);if(checked!=null && now-checked>=0 && now-checked<20)return;CHECKED.put(p,now);
        var bottoms=new HashMap<Long,TerrainProject.Step>();
        for(int i=0;i<p.steps.size();i++){
            var step=p.steps.get(i);if(p.abandoned.get(i) || !step.fill()
                    || !step.target().is(Blocks.COBBLESTONE) && !step.target().is(Blocks.DIRT)
                    || parents.stream().noneMatch(b->WorkTaskService.essential(s,p,b,step.position()))
                        && !(p.publicGround && p.groundSurfaces.stream().anyMatch(at->at.getX()==step.position().getX()
                                && at.getZ()==step.position().getZ() && at.getY()>=step.position().getY()))
                        && !(p.road && s.autoRoads().routes.values().stream().anyMatch(route->!route.suppressed
                                && p.dependencies.contains(route.id) && route.cells.stream().anyMatch(at->at.getX()==step.position().getX()
                                        && at.getZ()==step.position().getZ() && at.getY()>=step.position().getY()))))continue;
            var finalGoal=p.template().placementAt(step.position());if(finalGoal==null || finalGoal.state().isAir())continue;
            long column=new BlockPos(step.position().getX(),0,step.position().getZ()).asLong();var old=bottoms.get(column);
            if(old==null || step.position().getY()<old.position().getY())bottoms.put(column,step);
        }
        var extra=new ArrayList<TerrainProject.Step>();
        for(var bottom:bottoms.values()){
            var at=bottom.position().below();if(!l.hasChunkAt(at) || FarmEarthworkPlan.support(l,at))continue;
            var gap=new ArrayList<TerrainProject.Step>();boolean supported=false;
            // Only a bounded air gap in this same registered foundation column.
            // Fluids, occupied ordinary solids and protected objects stop review.
            for(int depth=0;depth<16 && !l.isOutsideBuildHeight(at) && l.hasChunkAt(at);depth++,at=at.below()){
                if(FarmEarthworkPlan.support(l,at)){supported=true;break;}
                if(!l.getBlockState(at).isAir() || !p.indices(at).isEmpty()
                        || !TerrainService.guard(l,s,site,new TerrainProject.Step(at,Blocks.COBBLESTONE.defaultBlockState(),l.getBlockState(at))).isEmpty())break;
                gap.add(new TerrainProject.Step(at,Blocks.COBBLESTONE.defaultBlockState(),l.getBlockState(at)));
            }
            if(supported)extra.addAll(gap);
        }
        if(!extra.isEmpty()){p.appendFoundationSupport(extra);TerrainLayerService.invalidate(s);SettlementSavedData.get(l).touch();}
    }
    private FoundationSupportService(){}
}

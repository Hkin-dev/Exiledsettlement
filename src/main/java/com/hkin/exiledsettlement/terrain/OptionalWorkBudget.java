package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.road.AutoRoadState;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Admission only. Neither progress, targets, inventory nor world blocks are modified. */
public final class OptionalWorkBudget {
    private final Set<BlockPos> pending=new HashSet<>();
    private final int limit;
    private boolean denied;
    private OptionalWorkBudget(SettlementRecord s,int limit){
        this.limit=limit;
        for(var p:s.terrain().unfinishedProjects())if((!p.bridge || p.playerBridge!=null) && !p.areaClearance && (p.publicGround || p.road || p.roadReclaim || p.playerBridge!=null)){
            var site=s.blueprint(p.id);
            for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && !p.abandoned.get(i) && (p.playerBridge==null || p.playerBridge.admitted.get(i))){
                var at=p.steps.get(i).position();
                if(site==null || WorkTaskService.priorityAt(s,site,at)!=0)pending.add(at);
            }
        }
        for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated && !r.manualBridge)
            for(int i=r.admitted.nextSetBit(r.progress);i>=0 && i<r.cells.size();i=r.admitted.nextSetBit(i+1))
                if(!r.completed.get(i))pending.add(r.cells.get(i));
    }
    public static OptionalWorkBudget snapshot(SettlementRecord s){return new OptionalWorkBudget(s,TerrainConfig.OPTIONAL_PENDING_BLOCKS.get());}
    public static OptionalWorkBudget snapshot(SettlementRecord s,int limit){return new OptionalWorkBudget(s,limit);}
    public int pending(){return pending.size();}
    public int limit(){return limit;}
    public String status(){return "待办规划额度不足："+pending.size()+"/"+limit+"格；先施工，完成后继续规划";}
    public boolean denied(){return denied;}
    public boolean full(){return pending.size()>=limit;}
    public boolean fits(Collection<TerrainProject.Step> steps,BlockPos paving){
        var extra=new HashSet<BlockPos>();
        for(var step:steps)if(!pending.contains(step.position()))extra.add(step.position());
        if(paving!=null && !pending.contains(paving))extra.add(paving);
        boolean allowed=extra.isEmpty() || pending.size()+extra.size()<=limit;
        denied=!allowed;return allowed;
    }
    public void reserve(Collection<TerrainProject.Step> steps,BlockPos paving){
        for(var step:steps)pending.add(step.position());if(paving!=null)pending.add(paving);
    }
    public boolean admitPaving(AutoRoadState.Route route,BlockPos at){
        int i=route.cellIndex(at);if(i<0)return false;
        if(route.manualBridge || route.admitted.get(i))return true;
        if(!fits(List.of(),at))return false;
        route.admitted.set(i);pending.add(at);return true;
    }
}

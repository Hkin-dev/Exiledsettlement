package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Successful P0 placement revokes conflicting optional plans, never world blocks or other P0 work. */
public final class P0PlanningService {
    private P0PlanningService() {}
    public static boolean optional(SettlementRecord s,BlueprintRecord site) {
        var p=site.terrainProject();
        return p!=null && !p.cancelled && !p.bridge && (p.publicGround || p.road || p.roadReclaim)
                && WorkTaskService.priority(s,site)==2;
    }
    public static void placed(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        if(!WorkTaskService.building(s,site) || WorkTaskService.priority(s,site)!=0)return;
        if(com.hkin.exiledsettlement.decoration.DecorationCatalog.ENTRIES.values().stream().anyMatch(e->e.type()==site.type()))return;
        var template=site.region()==null?BlueprintTemplates.create(site):null;
        var min=template==null?site.areaMin():template.minCorner();
        var max=template==null?site.areaMax():template.maxCorner();
        var entries=new HashSet<Long>();
        if(template!=null)for(var entry:template.entrances())for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
            entries.add(column(RoadEntranceService.outside(template,entry).offset(x,0,z)));
        java.util.function.Predicate<BlockPos> nearby=p->p.getX()>=min.getX()-1 && p.getX()<=max.getX()+1
                && p.getZ()>=min.getZ()-1 && p.getZ()<=max.getZ()+1 || entries.contains(column(p));
        int revoked=0;
        for(var carrier:List.copyOf(s.blueprints()))if(carrier!=site && optional(s,carrier)) {
            var p=carrier.terrainProject();var columns=new LinkedHashSet<BlockPos>();
            for(int i=0;i<p.steps.size();i++)if(!p.abandoned.get(i) && nearby.test(p.steps.get(i).position()))columns.add(new BlockPos(p.steps.get(i).position().getX(),0,p.steps.get(i).position().getZ()));
            if(columns.isEmpty())continue;
            for(var at:columns)p.retireColumn(at);
            p.connectionWarning="新P0工程撤销周边旧规划；已建方块与付费记录保留";
            s.terrain().index(p);
            for(var step:p.steps)if(nearby.test(step.position()))s.terrain().cells.remove(step.position(),p.id);
            if(p.done()){p.cancelled=true;carrier.queueCancellation();s.terrain().index(p);}
            revoked+=columns.size();
        }
        var changedRoutes=new HashSet<String>();
        for(var entry:s.autoRoads().routes.entrySet()) {
            var route=entry.getValue();if(route.suppressed || route.manualBridge || AutoRoadService.required(s,route) || route.cells.stream().noneMatch(nearby))continue;
            route.p0Invalidated=true;route.reviewAfter=0;
            route.status="新P0工程撤销旧路线规划；保留已建道路，等待重新选线";
            changedRoutes.add(entry.getKey());
        }
        AutoRoadPlanner.invalidateChangedRoutes(s,changedRoutes);
        PublicGroundService.requestReview(s);
        s.terrain().status="新P0工程已确认，撤销周边公共规划 "+revoked+"列，重新勘察";
        SettlementSavedData.get(level).touch();
    }
    private static long column(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
}

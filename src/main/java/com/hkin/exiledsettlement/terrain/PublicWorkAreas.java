package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Stable local public work fronts; building and bridge dependencies keep their own ordering. */
public final class PublicWorkAreas {
    public static final int SIZE=16;
    private PublicWorkAreas() {}
    public static long area(BlockPos at){return BlockPos.asLong(Math.floorDiv(at.getX(),SIZE),0,Math.floorDiv(at.getZ(),SIZE));}
    public static boolean applies(SettlementRecord s,BlueprintRecord b){
        var p=b==null?null:b.terrainProject();
        return p!=null && (p.publicGround || p.road) && !p.bridge && p.escapeFor==null
                && (p.dependencies.stream().noneMatch(id->WorkTaskService.building(s,s.blueprint(id)))
                    || WorkTaskService.priority(s,b)==2);
    }
    /** One column per area per round, retaining nearby column order inside each area. */
    public static List<Long> interleave(List<Long> columns){
        var areas=new LinkedHashMap<Long,ArrayDeque<Long>>();
        for(long k:columns)areas.computeIfAbsent(area(BlockPos.of(k)),v->new ArrayDeque<>()).add(k);
        var active=new ArrayDeque<>(areas.values());var result=new ArrayList<Long>(columns.size());
        while(!active.isEmpty()){var a=active.remove();result.add(a.remove());if(!a.isEmpty())active.add(a);}
        return result;
    }
    public static Map<Long,Integer> assigned(SettlementRecord s){
        var result=new HashMap<Long,Integer>();
        for(var c:s.citizens())if(c.alive()){
            if((c.workState()==com.hkin.exiledsettlement.citizen.CitizenWorkState.TO_ROAD
                    || c.workState()==com.hkin.exiledsettlement.citizen.CitizenWorkState.TO_ROAD_TOOL) && c.targetPosition()!=null){
                result.merge(area(c.targetPosition()),1,Integer::sum);continue;
            }
            var task=WorkTaskService.task(s,c);var b=task==null?null:s.blueprint(task.child());
            if(applies(s,b))result.merge(area(b.anchor()),1,Integer::sum);
        }
        return result;
    }
}

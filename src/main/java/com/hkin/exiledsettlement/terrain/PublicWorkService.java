package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Incremental work estimates shared by all residents, never a full template scan per resident. */
public final class PublicWorkService {
    private static final Map<ServerLevel,Map<UUID,Estimate>> ESTIMATES=new WeakHashMap<>();
    private static final class Estimate {
        long tick=Long.MIN_VALUE;
        int cursor, placed, prepared;
        final BitSet missing=new BitSet();
        final BitSet preparation=new BitSet();
        boolean initialized;
        BuildingBlueprintTemplate template;
        int[] costs;long weighted;
    }
    private PublicWorkService() { }
    public static int remaining(ServerLevel level,BlueprintRecord site) {
        if(site.terrainProject()!=null) return site.terrainProject().remaining();
        var template=BlueprintTemplates.create(site);
        var map=ESTIMATES.computeIfAbsent(level,key->new HashMap<>());
        var estimate=map.computeIfAbsent(site.id(),key->new Estimate());
        if(estimate.template!=template){estimate.missing.clear();estimate.preparation.clear();estimate.cursor=0;estimate.tick=Long.MIN_VALUE;estimate.template=template;estimate.costs=new int[template.placements().size()];java.util.Arrays.fill(estimate.costs,1);estimate.weighted=template.placements().size();estimate.missing.set(0,template.placements().size());estimate.initialized=true;}
        if(estimate.tick!=level.getGameTime()) {
            estimate.tick=level.getGameTime();
            int budget=256;
            while(budget-->0 && !template.placements().isEmpty()) {
                int index=estimate.cursor++ % template.placements().size();
                var p=template.placements().get(index);
                boolean missing=!BlueprintService.placementSatisfied(level,site,p);
                var current=level.getBlockState(p.position());
                boolean dig=p.state().isAir() || site.hasClearanceTarget(p.position());
                int effort=missing?(dig?Math.max(1,1+(int)Math.ceil(Math.max(0,current.getDestroySpeed(level,p.position()))*4)):1):0;
                estimate.weighted+=effort-estimate.costs[index];estimate.costs[index]=effort;
                estimate.missing.set(index,missing);
                estimate.preparation.set(index,missing && (p.state().isAir() || site.hasClearanceTarget(p.position())));
                if(estimate.cursor>=template.placements().size())estimate.cursor=0;
            }
        }
        int remaining=estimate.missing.cardinality();
        // Unexamined cells count as work until surveyed, so a cold estimate does not starve a large site.
        if(estimate.tick==Long.MIN_VALUE) remaining=template.placements().size();
        if(site.earthworkPlan()!=null)remaining+=site.earthworkPlan().size()-site.earthworkPlan().completed();
        return remaining;
    }
    public static void changed(ServerLevel level,BlueprintRecord site,int index) {
        if(site.terrainProject()!=null)return;remaining(level,site);var estimate=ESTIMATES.get(level).get(site.id());var placements=BlueprintTemplates.create(site).placements();
        if(index<0 || index>=placements.size())return;var p=placements.get(index);boolean missing=!BlueprintService.placementSatisfied(level,site,p);
        int effort=missing?Math.max(1,estimate.costs[index]):0;estimate.weighted+=effort-estimate.costs[index];estimate.costs[index]=effort;estimate.missing.set(index,missing);estimate.preparation.set(index,missing && p.state().isAir());
    }
    public static int demand(ServerLevel level,BlueprintRecord site){int remaining=remaining(level,site);if(site.terrainProject()!=null)return Math.max(1,remaining);return Math.max(1,(int)Math.min(Integer.MAX_VALUE,ESTIMATES.get(level).get(site.id()).weighted));}
    public record WorkCounts(int placements,int preparation,int foundation,int suggested,int assigned) { }
    public static WorkCounts counts(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        int placement=0,preparation=0,foundation=0;
        if(site.terrainProject()!=null) {
            var p=site.terrainProject();for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i)) {
                var step=p.steps.get(i);if(step.fill())placement++;else preparation++;
            }
            foundation=placement;
        }else {
            remaining(level,site);var estimate=ESTIMATES.get(level).get(site.id());preparation=estimate.preparation.cardinality();placement=estimate.missing.cardinality()-preparation;
            for(var p:s.terrain().projects.values())if(!p.cancelled && !p.completed && p.dependencies.contains(site.id()))for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && p.steps.get(i).fill())foundation++;
        }
        int suggested=Math.max(1,(int)((placement+(long)(site.terrainProject()==null?foundation:0)+preparation+TerrainConfig.ACTIONS_PER_WORKER.get()-1)/TerrainConfig.ACTIONS_PER_WORKER.get()));
        if(site.terrainProject()!=null && site.terrainProject().playerBridge!=null)suggested=2;
        if(com.hkin.exiledsettlement.citizen.WorkTaskService.building(s,site))suggested=com.hkin.exiledsettlement.citizen.WorkTaskService.defaultWorkers(site);
        int assigned=com.hkin.exiledsettlement.citizen.WorkTaskService.assigned(s,site.id());
        return new WorkCounts(placement,preparation,foundation,suggested,assigned);
    }
    public static List<String> report(ServerLevel level,SettlementRecord s) {
        var lines=new ArrayList<String>();lines.add("—— 公共工程分工 ——");
        lines.add("施工仅由工人参与；建筑自动人数按整座工地计算，含清场、地基和取料，满员后分配其他任务；人工指派保留。");
        int shown=0;for(var b:s.blueprints())if(!b.cancellationQueued()) {
            if(shown++>=12){lines.add("其余工程可在工地管理中查看；工程数量没有上限。");break;}
            var c=counts(level,s,b);String name=b.terrainProject()!=null?b.terrainProject().name:b.region()!=null?"地形生产区域":"建筑工地";
            if(b.region()!=null && !b.region().preparationRetries.isEmpty())lines.add("局部准备待重试"+b.region().preparationRetries.size()+"处："+b.region().status);
            lines.add("P"+com.hkin.exiledsettlement.citizen.WorkTaskService.priority(s,b)+" · "+name+" @ "+b.anchor().toShortString()+"：待放"+c.placements()+"，地基/支撑"+c.foundation()+"，准备"+c.preparation()+"；默认"+c.suggested()+"人 / 已派"+c.assigned()+"人");
            var phases=new LinkedHashMap<String,Integer>();
            for(var worker:s.citizens()){var t=com.hkin.exiledsettlement.citizen.WorkTaskService.task(s,worker);if(t!=null && b.id().equals(t.mother()))phases.merge(t.phase(),1,Integer::sum);}
            if(!phases.isEmpty())lines.add("子任务："+phases);
        }
        return lines;
    }
    public static int builderLimit(ServerLevel level,SettlementRecord settlement,BlueprintRecord site) {
        int pool=(int)settlement.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c->c.profession()==CitizenProfession.WORKER).count();
        int active=(int)settlement.blueprints().stream().filter(b->!b.cancellationQueued())
                .filter(b->TerrainService.allowed(settlement,b)).count();
        int minimum=com.hkin.exiledsettlement.citizen.WorkTaskService.building(settlement,site)
                ?Math.max(Math.min(pool,com.hkin.exiledsettlement.citizen.WorkTaskService.defaultWorkers(site)),1):1;
        if(active<=1)return Math.max(1,pool);
        long total=0;
        int weight=TerrainConfig.ACTIONS_PER_WORKER.get();
        for(var other:settlement.blueprints())if(!other.cancellationQueued() && TerrainService.allowed(settlement,other))total+=Math.max(1,(demand(level,other)+(long)weight-1)/weight);
        int extra=Math.max(0,pool-active);
        return Math.max(minimum,1+(int)Math.ceil((double)extra*Math.max(1,(demand(level,site)+(long)weight-1)/weight)/Math.max(1,total)));
    }
}

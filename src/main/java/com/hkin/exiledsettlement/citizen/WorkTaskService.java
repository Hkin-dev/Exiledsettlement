package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.decoration.DecorationService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.TerrainProject;
import net.minecraft.core.BlockPos;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable work goals over the existing real action state machines. No synthetic progress. */
public final class WorkTaskService {
    private WorkTaskService() {}
    public record Task(UUID mother, UUID child, int priority, String phase) {}
    public static boolean building(SettlementRecord s, BlueprintRecord b) {
        return b!=null && b.terrainProject()==null && !b.cancellationQueued() && !DecorationService.owned(s,b.id());
    }
    private static boolean bridgeBuilding(BlueprintRecord b){
        return b!=null && !b.cancellationQueued() && b.terrainProject()!=null
                && b.terrainProject().playerBridge!=null && b.terrainProject().playerBridge.buildingModel;
    }
    public static boolean essential(SettlementRecord s, TerrainProject p, BlueprintRecord parent, BlockPos at) {
        if(!building(s,parent) || !p.dependencies.contains(parent.id()) || p.areaClearance || p.roadReclaim)return false;
        if(p.bridge || p.road)return false;
        if(parent.region()!=null && parent.region().levelled() && (parent.region().cell(at)!=null
                || p.name.equals(com.hkin.exiledsettlement.region.FarmEntranceService.NAME)
                || p.name.startsWith("牧场安全")))return true;
        // A shared public order may include unrelated surroundings. Only the actual foundation is a P0 child.
        return parent.containsColumn(at) && at.getY()<parent.anchor().getY();
    }
    public static UUID mother(SettlementRecord s, BlueprintRecord b, BlockPos at) {
        if(b==null)return null;
        var p=b.terrainProject();if(p==null)return b.id();
        UUID first=null;
        for(var id:p.dependencies) {var parent=s.blueprint(id);
            if(essential(s,p,parent,at) && (first==null || id.compareTo(first)<0))first=id;}
        return first==null?b.id():first;
    }
    public static List<BlueprintRecord> parents(SettlementRecord s,BlueprintRecord b,BlockPos at){
        if(building(s,b) || bridgeBuilding(b))return List.of(b);
        var p=b.terrainProject();return p==null?List.of():p.dependencies.stream().map(s::blueprint)
                .filter(parent->parent!=null && essential(s,p,parent,at)).toList();
    }
    public static int priorityAt(SettlementRecord s, BlueprintRecord b, BlockPos at) {
        if(b!=null && b.terrainProject()!=null && b.terrainProject().playerBridge!=null
                && b.terrainProject().playerBridge.buildingModel)return 0;
        if(building(s,b))return b.region()!=null && b.region().groundMaintenance?2:0;
        if(b.terrainProject()!=null) {var parent=mother(s,b,at);if(!parent.equals(b.id()))return priorityAt(s,s.blueprint(parent),at);}
        return 2;
    }
    public static int priority(SettlementRecord s,BlueprintRecord b) {
        if(bridgeBuilding(b))return 0;
        if(building(s,b))return b.region()!=null && b.region().groundMaintenance?2:0;
        var p=b.terrainProject();if(p==null)return 2;
        for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && priorityAt(s,b,p.steps.get(i).position())==0)return 0;
        return 2;
    }
    public static int defaultWorkers(BlueprintRecord b) {
        int beds=b.type().isHouse()?b.type().housingLimit():0;
        return beds>=2 && beds<=4?beds:2;
    }
    public static Task task(SettlementRecord s,CitizenRecord c) {
        if(c.workState()==CitizenWorkState.IDLE)return null;
        var recovery=com.hkin.exiledsettlement.building.MobRecoveryService.task(s,c);
        if(recovery!=null)return new Task(recovery.id,recovery.id,1,c.workState()==CitizenWorkState.TO_REPAIR_SUPPLY?"生物破坏恢复：取料":"生物破坏恢复：施工");
        if(c.workState()==CitizenWorkState.TO_ROAD || c.workState()==CitizenWorkState.TO_ROAD_TOOL){
            var route=s.autoRoads().routes.values().stream().filter(r->!r.suppressed && (r.id.equals(c.roadTaskId()) || c.roadTaskId()==null && c.targetPosition()!=null && r.containsCell(c.targetPosition()))).findFirst().orElse(null);
            if(route!=null)return new Task(route.id,route.id,2,c.workState()==CitizenWorkState.TO_ROAD_TOOL?"道路领铲":"道路铺设");
        }
        var b=s.blueprint(c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId());
        var at=c.constructionResumeId()!=null?c.constructionResumePosition():c.targetPosition();
        if(b!=null && at!=null) {
            String phase=switch(c.workState()) {
                case TO_CONSTRUCTION_SUPPLY,TO_FOOTING_SUPPLY,TO_EQUIPMENT -> "领取工具材料";
                default -> b.terrainProject()!=null?"地基与通路":b.hasClearanceTarget(at)?"清理场地":"放置建筑";
            };
            UUID owner=mother(s,b,at);
            if(c.motherTaskId()!=null && (b.id().equals(c.motherTaskId()) || parents(s,b,at).stream().anyMatch(p->p.id().equals(c.motherTaskId()))))owner=c.motherTaskId();
            return new Task(owner,b.id(),priorityAt(s,b,at),c.workerAccess.block!=null?"受阻通路清理":phase);
        }
        UUID owner=c.sourceBuildingId()!=null?c.sourceBuildingId():c.targetBuildingId();
        if(owner==null)owner=UUID.nameUUIDFromBytes((c.id()+":"+c.profession()).getBytes(StandardCharsets.UTF_8));
        final UUID regionOwner=owner;
        var regionJob=s.blueprints().stream().filter(p->p.region()!=null && !p.cancellationQueued() && regionOwner.equals(p.region().workingBuilding)
            && (c.workState()==CitizenWorkState.TO_FOREST || c.workState()==CitizenWorkState.TO_CAPTURE)).findFirst().orElse(null);
        if(regionJob!=null)return new Task(regionJob.id(),owner,priority(s,regionJob),c.workState().serializedName());
        UUID child=UUID.nameUUIDFromBytes((owner+":"+c.workState()).getBytes(StandardCharsets.UTF_8));
        return new Task(owner,child,c.workState()==CitizenWorkState.TO_ROAD?2:1,c.workState().serializedName());
    }
    public static void bind(SettlementRecord s,CitizenRecord c) {
        var t=task(s,c);c.setMotherTask(t==null?null:t.mother());
    }
    public static void reserve(SettlementRecord s,CitizenRecord c){
        if(c.motherTaskId()!=null){bind(s,c);return;}
        var b=s.blueprint(c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId());
        var at=c.constructionResumeId()!=null?c.constructionResumePosition():c.targetPosition();
        if(b==null || at==null){bind(s,c);return;}
        var old=task(s,c);var parents=parents(s,b,at);
        var preferred=parents.stream().filter(p->p.id().equals(c.preferredWorksiteId())).findFirst();
        var chosen=preferred.orElseGet(()->parents.stream().min(Comparator.comparingDouble((BlueprintRecord p)->
                (double)(assigned(s,p.id())-(old!=null && p.id().equals(old.mother())?1:0))/defaultWorkers(p)).thenComparing(BlueprintRecord::id)).orElse(null));
        c.setMotherTask(chosen==null?b.id():chosen.id());
    }
    public static int assigned(SettlementRecord s,UUID mother) {
        if(mother==null)return 0;
        int count=0;for(var c:s.citizens())if(c.alive() && mother.equals(assignedMother(s,c)))count++;return count;
    }
    /** Dispatch counts only need ownership; do not construct phases, priorities or child IDs per candidate. */
    private static UUID assignedMother(SettlementRecord s,CitizenRecord c) {
        if(c.workState()==CitizenWorkState.IDLE)return null;
        if(c.workState()==CitizenWorkState.TO_ROAD || c.workState()==CitizenWorkState.TO_ROAD_TOOL)
            for(var r:s.autoRoads().routes.values())if(!r.suppressed && (r.id.equals(c.roadTaskId())
                    || c.roadTaskId()==null && c.targetPosition()!=null && r.containsCell(c.targetPosition())))return r.id;
        var b=s.blueprint(c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId());
        var at=c.constructionResumeId()!=null?c.constructionResumePosition():c.targetPosition();
        if(b!=null && at!=null) {
            var reserved=c.motherTaskId();
            if(reserved!=null && (b.id().equals(reserved) || !bridgeBuilding(b) && b.terrainProject()!=null && essential(s,b.terrainProject(),s.blueprint(reserved),at)))return reserved;
            return mother(s,b,at);
        }
        UUID owner=c.sourceBuildingId()!=null?c.sourceBuildingId():c.targetBuildingId();
        if(owner==null)owner=UUID.nameUUIDFromBytes((c.id()+":"+c.profession()).getBytes(StandardCharsets.UTF_8));
        if(c.workState()==CitizenWorkState.TO_FOREST || c.workState()==CitizenWorkState.TO_CAPTURE)
            for(var site:s.blueprints())if(site.region()!=null && !site.cancellationQueued() && owner.equals(site.region().workingBuilding))return site.id();
        return owner;
    }
    public static boolean underfilled(SettlementRecord s,BlueprintRecord site,BlockPos at) {
        if(building(s,site))return assigned(s,site.id())<defaultWorkers(site);
        return parents(s,site,at).stream().anyMatch(parent->assigned(s,parent.id())<defaultWorkers(parent));
    }
    /** All automatic entry points share the parent building's seats, including supplies and preparation. */
    public static boolean automaticSlotAvailable(SettlementRecord s,BlueprintRecord site,BlockPos at) {
        return automaticSlotAvailable(s,site,at,counts(s));
    }
    public static boolean automaticSlotAvailable(SettlementRecord s,BlueprintRecord site,BlockPos at,Map<UUID,Integer> counts) {
        var owners=parents(s,site,at);
        return owners.isEmpty() || owners.stream().anyMatch(p->counts.getOrDefault(p.id(),0)<defaultWorkers(p));
    }
    /** Stable selection also drains assignments made by older versions without cancelling their orders. */
    public static boolean withinBuildingCapacity(SettlementRecord s,CitizenRecord c) {
        var owner=assignedMother(s,c);var site=s.blueprint(owner);
        if(!(building(s,site) || bridgeBuilding(site)) || owner.equals(c.preferredWorksiteId()))return true;
        var residents=s.citizens().stream().filter(CitizenRecord::alive)
                .filter(r->owner.equals(assignedMother(s,r))).toList();
        long manual=residents.stream().filter(r->owner.equals(r.preferredWorksiteId())).count();
        return residents.stream().filter(r->!owner.equals(r.preferredWorksiteId()))
                .sorted(Comparator.comparing(CitizenRecord::id)).limit(Math.max(0,defaultWorkers(site)-manual))
                .anyMatch(r->r.id().equals(c.id()));
    }
    public static Map<UUID,Integer> counts(SettlementRecord s){
        var counts=new HashMap<UUID,Integer>();for(var c:s.citizens())if(c.alive()){var mother=assignedMother(s,c);if(mother!=null)counts.merge(mother,1,Integer::sum);}return counts;
    }
}

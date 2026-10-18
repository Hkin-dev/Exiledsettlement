package com.hkin.exiledsettlement.road;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Physical bridge frontier, shared by candidate selection and actual block actions. */
public final class PlayerBridgeWork {
    private PlayerBridgeWork(){}
    public static boolean arrived(ServerLevel level,TerrainProject project,BlockPos feet,BlockPos target){
        return project.playerBridge.inWorkArea(feet) && (project.playerBridge.buildingModel || BridgeConstructionSequence.dryStand(level,feet));
    }
    public static BlockPos approach(ServerLevel level,TerrainProject project,com.hkin.exiledsettlement.citizen.SettlementCitizenEntity actor,BlockPos target){
        if(project.playerBridge.buildingModel){
            var plan=project.playerBridge;var feet=actor.navigationFeetPosition();
            int row=Math.clamp(plan.longitudinal(feet),plan.start,plan.end);
            int side=Math.clamp(plan.alongX?feet.getZ():feet.getX(),plan.sideMin,plan.sideMin+plan.width-1);
            var bases=List.of(plan.at(plan.start-2,side,plan.surface),plan.at(plan.end+2,side,plan.surface),
                    plan.at(row,plan.sideMin-2,plan.surface),plan.at(row,plan.sideMin+plan.width+1,plan.surface),
                    plan.at(row,side,feet.getY()));
            var choices=new ArrayList<BlockPos>();
            for(var base:bases){
                if(!level.hasChunkAt(base))continue;
                int ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base.getX(),base.getZ());
                for(int y:new int[]{ground,feet.getY(),plan.surface}){
                    var p=base.atY(y);if(plan.inWorkArea(p) && actor.canStandAtPosition(p))choices.add(p);
                }
            }
            var ordered=choices.stream().distinct().sorted(Comparator.comparingDouble(p->p.distSqr(feet))).toList();
            var reachable=actor.findReachableBedStation(ordered);
            return reachable!=null?reachable:ordered.isEmpty()?null:ordered.getFirst();
        }
        var options=project.playerBridge.workArea(project,target).stream()
                .filter(p->arrived(level,project,p,target) && actor.canStandAtPosition(p))
                .sorted(Comparator.comparingDouble(p->p.distSqr(actor.blockPosition()))).limit(24).toList();
        var reachable=actor.findReachableBedStation(options);
        return reachable!=null?reachable:options.isEmpty()?null:options.getFirst();
    }
    public static PlayerBridgePlan assignment(SettlementRecord s,com.hkin.exiledsettlement.citizen.CitizenRecord c){
        var id=c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId();
        var p=id==null?null:s.terrain().projects.get(id);return p==null?null:p.playerBridge;
    }
    public static com.hkin.exiledsettlement.building.BuildingRecord supplier(ServerLevel l,SettlementRecord s,
            com.hkin.exiledsettlement.citizen.CitizenRecord c,BlockPos from,net.minecraft.world.item.Item item){
        var plan=assignment(s,c);if(plan==null)return null;
        return com.hkin.exiledsettlement.building.SettlementInventoryService.warehouses(s).stream()
                .filter(w->plan.supplyingBank(w.position()))
                .filter(w->com.hkin.exiledsettlement.building.WarehouseConstructionSupply.available(l,s,w,item,1)
                        || com.hkin.exiledsettlement.building.WarehouseInventoryService.hasCreativeSupply(l,w))
                .min(Comparator.comparingDouble(w->w.position().distSqr(from))).orElse(null);
    }
    public static String cancelNear(ServerLevel l,SettlementRecord s,BlockPos at){
        var p=s.terrain().projects.values().stream().filter(v->v.playerBridge!=null && !v.cancelled && !v.completed)
                .filter(v->v.bridgeDeck.stream().anyMatch(b->b.distSqr(at)<=32*32))
                .min(Comparator.comparingDouble(v->v.anchor.distSqr(at))).orElse(null);
        if(p==null)return "32格内没有未完工的玩家桥梁";
        p.cancelled=true;var site=s.blueprint(p.id);if(site!=null)site.queueCancellation();
        SettlementSavedData.get(l).touch();return "已撤销桥梁施工，保留已建结构、实际材料和记录";
    }
    public static String guard(ServerLevel l,TerrainProject p,TerrainProject.Step step){
        if(p==null)return "";var b=p.playerBridge;if(b==null)return "";if(b.paused)return "BRIDGE_PAUSED";
        int index=-1;for(int i:p.indices(step.position()))if(p.steps.get(i).equals(step)){index=i;break;}
        if(index<0 || !b.admitted.get(index) || !b.current(p,index))return "BRIDGE_FRONTIER_WAIT";
        if(!step.fill() && !l.getEntities((net.minecraft.world.entity.Entity)null,new net.minecraft.world.phys.AABB(step.position()).inflate(.01,.05,.01),
                e->e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive() && !e.isSpectator() && e.getY()>=step.position().getY()+.95).isEmpty())return "BRIDGE_WORKFACE_OCCUPIED";
        if(step.fill()){
            if(!supported(l,p,step))return "BRIDGE_SUPPORT_WAIT";
            var shape=step.target().getCollisionShape(l,step.position());
            if(!shape.isEmpty() && !l.getEntities((net.minecraft.world.entity.Entity)null,shape.bounds().move(step.position()),e->e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive() && !e.isSpectator()).isEmpty())return "BRIDGE_WORKFACE_OCCUPIED";
        }
        return "";
    }
    public static boolean supported(ServerLevel l,TerrainProject p,TerrainProject.Step step){
        if(p.playerBridge!=null && p.playerBridge.buildingModel)return true;
        var at=step.position();if(!step.fill())return true;
        if(p.bridgeAnchors.contains(at))return FarmEarthworkPlan.support(l,at.below());
        if(p.bridgeBeams.contains(at)){
            for(var d:Direction.values()){var n=at.relative(d);if(p.bridgeBeams.contains(n) && BridgeService.matches(l,p,n))return true;}
            return false;
        }
        if(p.bridgeRails.contains(at))return BridgeService.readyDeck(l,p,at.below());
        return FarmEarthworkPlan.support(l,at.below());
    }
    public static String pauseNear(ServerLevel l,SettlementRecord s,BlockPos at,boolean pause){
        var p=s.terrain().projects.values().stream().filter(v->v.playerBridge!=null && !v.cancelled && !v.completed)
                .filter(v->v.bridgeDeck.stream().anyMatch(b->b.distSqr(at)<=32*32)).min(Comparator.comparingDouble(v->v.anchor.distSqr(at))).orElse(null);
        if(p==null)return "32格内没有未完工的玩家桥梁";p.playerBridge.paused=pause;p.connectionWarning=pause?"桥梁已暂停，保留实物和材料":"桥梁已恢复，继续原有进度";
        SettlementSavedData.get(l).touch();return p.connectionWarning;
    }
    public static void tick(ServerLevel l,SettlementRecord s,TerrainProject p){
        if(p.playerBridge==null || p.cancelled || p.completed || p.playerBridge.paused)return;
        var b=p.playerBridge;b.refresh(p);if(b.frontier>=p.steps.size())return;
        if(Math.floorMod(l.getGameTime()+p.id.hashCode(),10)!=0)return;
        var cap=OptionalWorkBudget.snapshot(s);int positions=0;var newPositions=new HashSet<BlockPos>();
        for(int i:b.activeIndices(p))if(!p.finished.get(i) && !b.admitted.get(i)){
            var step=p.steps.get(i);if(!newPositions.contains(step.position()) && positions>=16)break;
            if(!cap.fits(List.of(step),null))break;
            cap.reserve(List.of(step),null);b.admitted.set(i);if(newPositions.add(step.position()))positions++;
        }
        p.connectionWarning="玩家桥梁："+(b.buildingModel?List.of("支撑柱与梁层","铺设桥面","护栏收尾","完成"):List.of("逐排岸基和梁层","逐排铺桥面","护栏收尾","完成")).get(b.phase)
                +"；已完成 "+p.finished.cardinality()+"/"+p.steps.size()+"；"+(cap.full()?cap.status():b.buildingModel?"工地附近计时施工":"桥头工作区计时施工");
        SettlementSavedData.get(l).touch();
    }
}

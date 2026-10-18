package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Read-only main-world diagnosis; persistence checks use a detached record. */
public final class CommittedRoad128Checks {
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var live=SettlementSavedData.get(level).findByCore(Engineering91Acceptance.CORE).orElseThrow();
        var out=new JsonObject();var rows=new JsonArray();out.addProperty("gameTime",level.getGameTime());
        for(var route:live.autoRoads().routes.values())if(!route.suppressed){
            var row=new JsonObject();row.addProperty("id",route.id.toString());row.addProperty("entrance",route.entrance.toShortString());
            row.addProperty("parentValid",AutoRoadService.parentValid(level,live,route));row.addProperty("committedConflict",AdaptiveRoadService.committedConflict(live,route));
            row.addProperty("blocker",RoadEarthworkService.accessBlocker(level,live,route));var conflicts=new JsonArray();
            for(var at:route.cells){var plane=live.terrain().publicPlane(at);if(plane!=null && plane.getY()!=at.getY()){
                var c=new JsonObject();c.addProperty("old",at.toShortString());c.addProperty("committed",plane.toShortString());
                c.addProperty("actualOld",level.getBlockState(at).toString());c.addProperty("actualCommitted",level.getBlockState(plane).toString());conflicts.add(c);}}
            row.add("conflicts",conflicts);rows.add(row);
        }
        out.add("routes",rows);var checks=new JsonObject();
        var probeCells=java.util.List.of(Engineering91Acceptance.CORE,Engineering91Acceptance.CORE.east(),Engineering91Acceptance.CORE.east(2));
        var progressProbe=new AutoRoadState.Route(java.util.UUID.randomUUID(),probeCells.getLast(),probeCells);
        progressProbe.completed.set(0,3);progressProbe.progress=3;progressProbe.completed.clear(1);progressProbe.advance();
        checks.addProperty("damagedCompletionRewindsCursor",progressProbe.progress==1);
        progressProbe.repairs.set(0);progressProbe.advance();checks.addProperty("paidHeadroomRepairSurvivesAdvance",progressProbe.repairs.get(0));
        var copy=SettlementRecord.load(live.save(level.registryAccess()),level.registryAccess());
        var route=copy.autoRoads().routes.entrySet().stream().filter(e->!e.getValue().suppressed && AdaptiveRoadService.committedConflict(copy,e.getValue())).findFirst()
                .orElseGet(()->copy.autoRoads().routes.entrySet().stream().filter(e->!e.getValue().suppressed).findFirst().orElseThrow());
        var r=route.getValue();var b=copy.building(r.building);var root=AutoRoadService.coreSurface(level,copy);
        var before=copy.terrain().publicGround.save();var pending=OptionalWorkBudget.snapshot(copy).pending();
        try{
            AutoRoadPlanner.beginCommittedRepair(level,copy,b,root,r.entrance,route.getKey());
            var saved=AutoRoadPlanner.saved(copy.autoRoads());
            checks.addProperty("repairIntentSaved",saved.getBoolean("CommittedRepair"));
            AutoRoadPlanner.invalidateOptional(copy);checks.addProperty("optionalReviewKeepsRepair",AutoRoadPlanner.busy(copy));
            checks.addProperty("publicationCursorUnchanged",before.equals(copy.terrain().publicGround.save()));
            checks.addProperty("pendingQuotaUnchanged",pending==OptionalWorkBudget.snapshot(copy).pending());
            checks.addProperty("noRouteReplacedAtSearchStart",copy.autoRoads().routes.get(route.getKey())==r);
            checks.addProperty("intentSurvivesRecordReload",SettlementRecord.load(copy.save(level.registryAccess()),level.registryAccess()).autoRoads().planning.getBoolean("CommittedRepair"));
        }finally{AutoRoadPlanner.invalidate(copy);}
        boolean passed=true;for(var e:checks.entrySet())passed&=e.getValue().getAsBoolean();
        out.add("checks",checks);out.addProperty("passed",passed);out.addProperty("worldEdits",0);out.addProperty("detachedRecordChecks",true);
        var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        if(actor!=null){
            actor.moveTo(8.5,159.9375,12.5,0,0);actor.setOnGround(true);
            var navigation=(com.hkin.exiledsettlement.citizen.CitizenGroundNavigation)actor.getNavigation();
            var target=new net.minecraft.core.BlockPos(8,163,15);
            navigation.destination(null,actor.navigationFeetPosition(),target);
            var path=navigation.createPath(target,0);var probe=navigation.roadProbeDiagnostic();
            probe.addProperty("reached",path!=null && path.canReach());
            if(path!=null){var nodes=new JsonArray();for(int i=0;i<path.getNodeCount();i++)nodes.add(path.getNode(i).asBlockPos().toShortString());probe.add("path",nodes);}
            probe.addProperty("spawned",false);probe.addProperty("movementCredit",false);out.add("nativeRoadProbe",probe);
        }
        return new Gson().toJson(out);
    }
    private CommittedRoad128Checks(){}
}

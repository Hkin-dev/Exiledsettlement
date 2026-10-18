package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Exercise saved edit/cancel/merge records on a detached copy; never edit the live world. */
public final class RoadEditChecks {
    private RoadEditChecks(){ }
    public static String run(ServerLevel level,SettlementRecord live){
        var copy=SettlementRecord.load(live.save(level.registryAccess()),level.registryAccess());
        var routes=copy.autoRoads().routes.values().stream().filter(r->!r.cells.isEmpty()).limit(3).toList();
        if(routes.size()<3)throw new IllegalArgumentException("NEED_THREE_ROAD_RECORDS");
        var removed=routes.get(0);var cancelled=routes.get(1);var merged=routes.get(2);
        removed.suppressed=true;removed.mergedInto=null;removed.status="玩家移除路面，路线不再重建";
        cancelled.suppressed=true;cancelled.mergedInto=null;cancelled.status="玩家已撤销，保持现有路面且不再重建";
        merged.suppressed=true;merged.mergedInto=removed.id;merged.status="玩家移除路面，路线不再重建";
        copy.autoRoads().playerEditVersion=0;var checks=new JsonObject();
        checks.addProperty("migration_runs",AdaptiveRoadService.migratePlayerEdits(copy));
        checks.addProperty("ordinary_old_edit_resumes",!removed.suppressed);
        checks.addProperty("explicit_cancel_preserved",cancelled.suppressed);
        checks.addProperty("merged_route_preserved",merged.suppressed);
        checks.addProperty("migration_is_once",!AdaptiveRoadService.migratePlayerEdits(copy));
        var p=removed.cells.getFirst();removed.completed.set(0);removed.progress=removed.cells.size();
        checks.addProperty("ordinary_edit_rechecks",AutoRoadService.reconsiderSurface(removed,p) && !removed.suppressed && !removed.completed.get(0) && removed.progress==0);
        checks.addProperty("explicit_edit_stays_cancelled",!AutoRoadService.reconsiderSurface(cancelled,cancelled.cells.getFirst()) && cancelled.suppressed);
        var saved=copy.autoRoads().save();var loaded=new AutoRoadState();loaded.load(saved);
        checks.addProperty("save_reload_preserves_policy",loaded.save().equals(saved));
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);out.addProperty("liveRecordEdits",0);
        out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
}

package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.nio.file.*;
import java.util.*;

/** Read-only physical audit of the ten original inputs; no reconciliation or completion credit. */
public final class Engineering91Closure {
    private Engineering91Closure(){}
    public static boolean planningTransactionClosed(SettlementRecord s) {
        return s.terrain().publicGround.phase==0 && PublicGroundService.unpublishedColumns(s)==0
                && !PublicGroundService.planning(s) && !AutoRoadPlanner.busy(s) && s.autoRoads().planning.isEmpty();
    }
    public static String inspect(ServerLevel l)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var manifest=JsonParser.parseString(Files.readString(SaveAcceptanceService.file("engineering91-inputs",".json"))).getAsJsonObject();
        var s=SettlementSavedData.get(l).findById(UUID.fromString(manifest.get("settlement").getAsString()))
                .orElseThrow(()->new IllegalStateException("ORIGINAL_TEST_SETTLEMENT_REQUIRED"));
        var out=new JsonObject();out.addProperty("gameTime",l.getGameTime());out.addProperty("readOnly",true);
        int completed=0,missingStructures=0,unconnected=0;
        var buildings=new JsonArray();
        for(var input:manifest.getAsJsonArray("buildings")){
            var entry=input.getAsJsonObject();var id=UUID.fromString(entry.get("id").getAsString());
            var xyz=entry.get("anchor").getAsString().split(",\\s*");
            var anchor=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
            var type=BuildingType.valueOf(entry.get("type").getAsString());
            // Ordinary registration assigns a new formal ID and transfers the
            // road. Match the original immutable template identity exactly;
            // never replace an original input with an arbitrary built count.
            var facing=entry.has("facing")?Direction.byName(entry.get("facing").getAsString()):Direction.NORTH;
            if(facing==null)throw new IllegalStateException("ORIGINAL_FACING_REQUIRED");
            int version=entry.get("templateVersion").getAsInt();
            var original=BlueprintTemplates.create(type,anchor,facing,version);
            var expectedFacing=facing;
            var matches=s.buildings().stream().filter(built->built.type()==type && built.templateVersion()==version
                    && built.facing()==expectedFacing && built.position().equals(original.registrationPosition())
                    && built.minCorner().equals(original.minCorner()) && built.maxCorner().equals(original.maxCorner())).toList();
            var b=matches.size()==1?matches.getFirst():null;
            var row=new JsonObject();row.addProperty("id",id.toString());row.addProperty("built",b!=null);
            row.addProperty("matchingFormalRecords",matches.size());row.addProperty("formalId",b==null?"":b.id().toString());
            int missing=0;
            if(b!=null){completed++;for(var placement:BlueprintTemplates.createForBuilding(b).placements()){
                if(placement.mode()==BlueprintPlacementMode.CLEARANCE || placement.mode()==BlueprintPlacementMode.OPTIONAL_SUPPORT)continue;
                if(!l.hasChunkAt(placement.position()) || !BlueprintService.matchesPlacedState(l.getBlockState(placement.position()),placement.state()))missing++;
            }}
            boolean road=b!=null && s.autoRoads().routes.values().stream().filter(r->!r.suppressed && r.building.equals(b.id()))
                    .anyMatch(r->r.progress==r.cells.size() && RoadEarthworkService.accessBlocker(l,s,r).isEmpty()
                            && r.cells.stream().allMatch(p->RoadEarthworkService.surfaceReady(l,s,r,p)));
            if(!road)unconnected++;missingStructures+=missing;row.addProperty("missingStructureCells",missing);row.addProperty("roadPhysicallyReady",road);buildings.add(row);
        }
        long pending=s.blueprints().stream().filter(b->!b.cancellationQueued()).count();
        int unfinished=0,invalidCompleted=0;
        for(var p:s.terrain().projects.values())if(!p.cancelled && !p.heightSuperseded){
            if(!p.completed)unfinished++;
            else if(!TerrainService.constructionComplete(l,p))invalidCompleted++;
        }
        int roadPending=0,roadInvalid=0,retired=0;
        for(var r:s.autoRoads().routes.values())if(!r.suppressed){
            if(r.progress<r.cells.size())roadPending++;retired+=r.retired.size();
            if(!RoadEarthworkService.accessBlocker(l,s,r).isEmpty()
                    || r.cells.stream().anyMatch(p->!RoadEarthworkService.surfaceReady(l,s,r,p)))roadInvalid++;
        }
        out.add("originalBuildings",buildings);out.addProperty("builtOriginalBuildings",completed);
        out.addProperty("missingStructureCells",missingStructures);out.addProperty("unconnectedOriginalBuildings",unconnected);
        out.addProperty("activeBlueprints",pending);out.addProperty("unfinishedTerrainProjects",unfinished);
        out.addProperty("physicallyInvalidCompletedTerrain",invalidCompleted);out.addProperty("unfinishedRoads",roadPending);
        out.addProperty("physicallyInvalidRoads",roadInvalid);out.addProperty("retiredRoadCells",retired);
        boolean planningClosed=planningTransactionClosed(s);
        out.addProperty("publicPlanningPhase",s.terrain().publicGround.phase);
        out.addProperty("unpublishedPublicColumns",PublicGroundService.unpublishedColumns(s));
        out.addProperty("planningTransactionClosed",planningClosed);
        out.addProperty("geometryAndPlanningClosed",planningClosed && completed==10 && missingStructures==0 && unconnected==0
                && pending==0 && unfinished==0 && invalidCompleted==0 && roadPending==0 && roadInvalid==0 && retired==0);
        out.addProperty("residentTraversalVerified",false);
        Files.writeString(SaveAcceptanceService.file("engineering91-closure",".jsonl"),new Gson().toJson(out)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        return new Gson().toJson(out);
    }
}

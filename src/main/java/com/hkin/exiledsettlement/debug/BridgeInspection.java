package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Read-only reports of actual loaded river terrain and real engineering orders. */
public final class BridgeInspection {
    private BridgeInspection() { }
    public static String export(ServerLevel level,SettlementRecord s,String name)throws Exception {
        var out=new JsonObject();out.addProperty("gameTime",level.getGameTime());out.addProperty("onlinePlayers",level.getServer().getPlayerCount());
        out.add("planning",AutoRoadPlanner.progress(s));out.addProperty("roadStatus",s.autoRoads().status);
        out.add("endpoints",BridgeEndpointInspection.inspect(level,s));
        out.addProperty("coreSurface",String.valueOf(AutoRoadService.coreSurface(level,s)));
        out.addProperty("roadWorkReady",s.autoRoads().workReady);out.addProperty("hasPending",AutoRoadService.hasPending(s));
        var projections=new JsonObject();
        for(var entity:level.getAllEntities())if(entity instanceof com.hkin.exiledsettlement.citizen.SettlementCitizenEntity
                && entity.isAlive() && s.id().equals(com.hkin.exiledsettlement.citizen.CitizenProjectionService.settlementId(entity))) {
            var citizen=com.hkin.exiledsettlement.citizen.CitizenProjectionService.citizenId(entity);if(citizen==null)continue;
            String key=citizen.toString();var list=projections.has(key)?projections.getAsJsonArray(key):new JsonArray();
            var entry=new JsonObject();entry.addProperty("entity",entity.getUUID().toString());entry.addProperty("x",entity.getX());entry.addProperty("y",entity.getY());entry.addProperty("z",entity.getZ());list.add(entry);projections.add(key,list);
        }
        out.add("residentProjections",projections);
        var columns=new JsonArray();var core=s.corePosition();
        for(int x=core.getX()-128;x<=core.getX()+128;x++)for(int z=core.getZ()-128;z<=core.getZ()+128;z++) {
            var p=new BlockPos(x,core.getY(),z);if(!level.hasChunkAt(p))continue;
            int y=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1;var at=p.atY(y);var j=new JsonObject();
            j.addProperty("x",x);j.addProperty("y",y);j.addProperty("z",z);j.addProperty("block",level.getBlockState(at).toString());
            j.addProperty("owned",s.contains(at));
            j.addProperty("water",level.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER));j.addProperty("permitted",RoadEarthworkService.permitted(level,s,at));columns.add(j);
        }
        out.add("columns",columns);var projects=new JsonArray();
        for(var p:s.terrain().projects.values())if(p.bridge) {
            var j=new JsonObject();j.addProperty("id",p.id.toString());j.addProperty("completed",p.completed);j.addProperty("cancelled",p.cancelled);j.addProperty("remaining",p.remaining());
            j.addProperty("finished",p.finished.cardinality());j.addProperty("steps",p.steps.size());j.addProperty("warning",p.connectionWarning);
            var deps=new JsonArray();p.dependencies.forEach(id->deps.add(id.toString()));j.add("dependencies",deps);
            var goals=new JsonArray();for(var g:p.template().placements()) {
                var cell=new JsonObject();cell.addProperty("x",g.position().getX());cell.addProperty("y",g.position().getY());cell.addProperty("z",g.position().getZ());
                cell.addProperty("target",g.state().toString());cell.addProperty("actual",level.getBlockState(g.position()).toString());
                cell.addProperty("beam",p.bridgeBeams.contains(g.position()));cell.addProperty("anchor",p.bridgeAnchors.contains(g.position()));cell.addProperty("walk",p.accessSurfaces.contains(g.position()));
                if(p.bridgeDeck.contains(g.position()))cell.addProperty("waterTwoBelow",level.getFluidState(g.position().below(2)).is(net.minecraft.tags.FluidTags.WATER));
                cell.addProperty("supported",BridgeService.supported(level,p,new com.hkin.exiledsettlement.terrain.TerrainProject.Step(g.position(),g.state(),g.state())));
                cell.addProperty("ready",BridgeService.ready(level,s,g.position()));goals.add(cell);
                if(p.bridgeBeams.contains(g.position()) && !BridgeService.matches(level,p,g.position())) {
                    var site=s.blueprint(p.id);
                    if(site!=null)for(int index:p.indices(g.position()))if(p.steps.get(index).fill())cell.addProperty("guard",com.hkin.exiledsettlement.terrain.TerrainService.guard(level,s,site,p.steps.get(index)));
                    var actors=new JsonArray();
                    for(var citizen:s.citizens()) {
                        var entity=com.hkin.exiledsettlement.citizen.CitizenProjectionService.findProjection(level,citizen);
                        if(entity==null || entity.blockPosition().distSqr(g.position())>64*64)continue;
                        var actor=new JsonObject();actor.addProperty("id",citizen.id().toString());actor.addProperty("x",entity.getX());actor.addProperty("y",entity.getY());actor.addProperty("z",entity.getZ());
                        actor.addProperty("reach",com.hkin.exiledsettlement.citizen.CitizenInteractionRange.contains(entity,g.position()));
                        actor.addProperty("centerVisible",com.hkin.exiledsettlement.citizen.CitizenInteractionRange.visible(entity,g.position()));
                        actor.addProperty("upperVisible",com.hkin.exiledsettlement.citizen.CitizenInteractionRange.visiblePlacement(entity,g.position()));
                        actor.addProperty("ground",entity.onGround());actors.add(actor);
                    }
                    cell.add("workers",actors);
                }
            }j.add("goals",goals);projects.add(j);
        }
        out.add("bridges",projects);
        var routes=new JsonArray();for(var route:s.autoRoads().routes.values())if(AutoRoadService.required(s,route)) {
            var r=new JsonObject();r.addProperty("parent",route.building.toString());r.addProperty("progress",route.progress);r.addProperty("cells",route.cells.size());
            r.addProperty("accessConnected",route.accessConnected);r.addProperty("accessBlocker",RoadEarthworkService.accessBlocker(level,s,route));
            var blocked=new JsonArray();for(var at:route.cells)if(!RoadEarthworkService.surfaceReady(level,s,route,at) && blocked.size()<12) {
                var c=new JsonObject();c.addProperty("x",at.getX());c.addProperty("y",at.getY());c.addProperty("z",at.getZ());
                c.addProperty("actual",level.getBlockState(at).toString());c.addProperty("permitted",RoadEarthworkService.permitted(level,s,at));c.addProperty("safe",AutoRoadService.safeCell(level,s,at));
                c.addProperty("profileConnected",RoadSurfaceProfile.connected(level,route,at));c.addProperty("checkpoint",com.hkin.exiledsettlement.terrain.PublicGroundService.checkpointAt(s,at));
                c.addProperty("publicTarget",com.hkin.exiledsettlement.terrain.PublicGroundService.targetAt(s,at));
                var id=s.terrain().cells.get(at);if(id!=null)c.addProperty("terrain",id.toString());blocked.add(c);
            }r.add("blocked",blocked);routes.add(r);
        }out.add("routes",routes);
        var path=SaveAcceptanceService.file(name,".json");Files.createDirectories(path.getParent());
        Files.writeString(path,new Gson().toJson(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return path.toString();
    }
}

package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.settlement.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;

/** Console/RCON support; enabled only in an explicitly opted-in test process. */
public final class SaveAcceptanceCommands {
    private SaveAcceptanceCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("settlementtest").requires(s -> s.hasPermission(2) && SaveAcceptanceService.enabled());
        root.then(Commands.literal("bridge65longresume").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bridge65LongResume.start(c.getSource().getLevel()))));
        root.then(Commands.literal("bridge65longinspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bridge65LongResume.inspect())));
        root.then(Commands.literal("bridge65start").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bridge65Acceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("bridge65reload").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bridge65Acceptance.verifyReload(c.getSource().getLevel()))));
        root.then(Commands.literal("bridge65inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bridge65Acceptance.inspect())));
        root.then(Commands.literal("tickprofile").then(Commands.argument("name",StringArgumentType.word()).then(Commands.argument("ticks",IntegerArgumentType.integer(20,12000))
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.TickPhaseProfiler.start(StringArgumentType.getString(c,"name"),IntegerArgumentType.getInteger(c,"ticks")))))));
        root.then(Commands.literal("home7start").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("home7engineering").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.engineering(c.getSource().getLevel()))));
        root.then(Commands.literal("home7engineeringstats").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.engineeringStats(c.getSource().getLevel()))));
        root.then(Commands.literal("home7inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("home7paths").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.paths(c.getSource().getLevel()))));
        root.then(Commands.literal("home7consume").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.consume(c.getSource().getLevel()))));
        root.then(Commands.literal("home7animals").executes(c->run(c,()->com.hkin.exiledsettlement.debug.HomeLogisticsAcceptance.animals(c.getSource().getLevel()))));
        root.then(Commands.literal("engineering91start").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91Acceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("engineering91simulate").then(Commands.argument("ticks",IntegerArgumentType.integer(1,1000))
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91Simulation.run(c.getSource().getLevel(),number(c,"ticks"))))));
        root.then(Commands.literal("engineering91inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91Acceptance.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("committedroad128check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.CommittedRoad128Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("near114check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Near114Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("sharedroad119check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.SharedRoad119Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("entrance121check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Entrance121Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("bank122check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Bank122Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("widepit127check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.WidePit127Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("clearance108check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Clearance108Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("inventory105check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Inventory105Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("scaffold102check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Scaffold102Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("engineering91closure").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91Closure.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("engineering91entranceprobe").then(Commands.literal("start").then(Commands.argument("route",StringArgumentType.word())
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91EntranceProbe.start(c.getSource().getLevel(),StringArgumentType.getString(c,"route"))))))
                .then(Commands.literal("status").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91EntranceProbe.status()))));
        root.then(Commands.literal("engineering91guidedentrance").then(Commands.argument("route",StringArgumentType.word())
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91EntranceProbe.start(c.getSource().getLevel(),StringArgumentType.getString(c,"route"),true)))));
        root.then(Commands.literal("engineering91roadwalk").then(Commands.literal("start").then(Commands.argument("route",StringArgumentType.word())
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91RoadWalk.start(c.getSource().getLevel(),StringArgumentType.getString(c,"route"))))))
                .then(Commands.literal("status").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91RoadWalk.status()))));
        root.then(Commands.literal("engineering91inventorycheck").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Engineering91Acceptance.inventoryInvariants(c.getSource().getLevel()))));
        root.then(Commands.literal("coresupply91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.CoreSupply91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("foundation91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Foundation91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("terrainreturn91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.TerrainReturn91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("workeraccess91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.WorkerAccess91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("clearance91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Clearance91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("roaddispatch91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.RoadDispatch91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("paidexit91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.PaidExit91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("pitescape91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.PitEscape91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("surfacefill91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.SurfaceFill91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("occupancy91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.ConstructionOccupancy91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("optionalwork91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.OptionalWork91Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("terrainpersistence91check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.TerrainPersistence91Checks.run())));
        root.then(Commands.literal("building89check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Building89Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("building89live").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Building89Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("publicdig87live").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.PublicDig87Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("dispatch88check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Dispatch88Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("dispatch88live").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Dispatch88Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("dispatch88inspect").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Dispatch88Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("publicdig87check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.PublicDig87Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("regions86check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Region86Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("regions86live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Region86Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("regions86resume").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Region86Checks.resume(c.getSource().getLevel()))));
        root.then(Commands.literal("regions86inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Region86Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("layers84check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Layer84Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("layers84live").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Layer84Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("layers84inspect").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Layer84Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("roads83check").executes(c->run(c,()->com.hkin.exiledsettlement.road.Road83Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("roads83live").executes(c->run(c,()->com.hkin.exiledsettlement.road.Road83Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("roads83fixtureentries").executes(c->run(c,()->com.hkin.exiledsettlement.road.Road83Checks.fixtureEntries(c.getSource().getLevel()))));
        root.then(Commands.literal("roads83inspect").executes(c->run(c,()->com.hkin.exiledsettlement.road.Road83Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("stairs82check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Stair82Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("stairs82live").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Stair82Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("stairs82inspect").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Stair82Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("stairs82supply").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Stair82Checks.supply(c.getSource().getLevel()))));
        root.then(Commands.literal("construction80check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Construction80Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("construction80live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Construction80Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("ground79check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("ground79live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.start(c.getSource().getLevel()))));
        root.then(Commands.literal("ground81live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.startFresh(c.getSource().getLevel()))));
        root.then(Commands.literal("ground81inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.inspectFresh(c.getSource().getLevel()))));
        root.then(Commands.literal("ground81staff").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.staffFresh(c.getSource().getLevel()))));
        root.then(Commands.literal("ground81pause").then(Commands.argument("paused",BoolArgumentType.bool()).executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.pauseFresh(c.getSource().getLevel(),BoolArgumentType.getBool(c,"paused"))))));
        root.then(Commands.literal("ground79inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("ground79leadfix").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.repairFixtureLead(c.getSource().getLevel()))));
        root.then(Commands.literal("ground79staff").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.staff(c.getSource().getLevel()))));
        root.then(Commands.literal("ground79pause").then(Commands.argument("paused",BoolArgumentType.bool()).executes(c->run(c,()->com.hkin.exiledsettlement.debug.Ground79Checks.pause(c.getSource().getLevel(),BoolArgumentType.getBool(c,"paused"))))));
        root.then(Commands.literal("foundation78check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Foundation78Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("foundation78live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Foundation78Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("workers85check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker85Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("workers85live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker85Checks.live(c.getSource().getLevel()))));
        root.then(Commands.literal("workers85access").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker85Checks.accessFixture(c.getSource().getLevel()))));
        root.then(Commands.literal("workers85inspect").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker85Checks.inspect(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77LiveAcceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77status").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77LiveAcceptance.status(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77retirefixture").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77LiveAcceptance.retireFailedFixture(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77supply").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77LiveAcceptance.supply(c.getSource().getLevel()))));
        root.then(Commands.literal("worker77access").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Worker77AccessAcceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("warehouse75check").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Warehouse75Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("warehouse75live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Warehouse75LiveAcceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("warehouse75rule").then(Commands.argument("keep",IntegerArgumentType.integer(0,200000))
                .executes(c->run(c,()->com.hkin.exiledsettlement.debug.Warehouse75LiveAcceptance.policy(c.getSource().getLevel(),number(c,"keep"))))));
        root.then(Commands.literal("supplycheck").executes(c -> run(c, () -> com.hkin.exiledsettlement.debug.ConstructionSupplyChecks.run())));
        root.then(Commands.literal("works74check").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.Works74Checks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("works74live").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Works74LiveAcceptance.start(c.getSource().getLevel()))));
        root.then(Commands.literal("works74housesupply").executes(c->run(c,()->com.hkin.exiledsettlement.debug.Works74LiveAcceptance.supplyHouse(c.getSource().getLevel()))));
        root.then(Commands.literal("publicworkscheck").executes(c->run(c,()->com.hkin.exiledsettlement.terrain.PublicWorksChecks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("publicgradecheck").executes(c -> run(c, () -> com.hkin.exiledsettlement.terrain.PublicGradeChecks.run())));
        root.then(Commands.literal("navigationpolicycheck").executes(c->run(c,()->com.hkin.exiledsettlement.debug.NavigationJourneyChecks.run())));
        root.then(Commands.literal("navigationlive").then(Commands.literal("start").executes(c->run(c,()->com.hkin.exiledsettlement.debug.NavigationLiveAcceptance.start(c.getSource().getLevel()))))
                .then(Commands.literal("status").executes(c->run(c,()->com.hkin.exiledsettlement.debug.NavigationLiveAcceptance.status()))));
        root.then(Commands.literal("workindexcheck").executes(c -> run(c, () -> com.hkin.exiledsettlement.debug.WorkIndexChecks.run())));
        root.then(Commands.literal("stewardcheck").executes(c -> run(c, () -> com.hkin.exiledsettlement.debug.WarehouseStewardChecks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("roadgeometrycheck").executes(c -> run(c, () -> com.hkin.exiledsettlement.debug.RoadGeometryChecks.run(c.getSource().getLevel()))));
        root.then(Commands.literal("list").executes(c -> run(c, () -> {
            var lines = new StringBuilder();
            for (var level : c.getSource().getServer().getAllLevels())
                for (var s : SettlementSavedData.get(level).settlements())
                    lines.append(s.id()).append(" ").append(level.dimension().location()).append(" ").append(s.name()).append('\n');
            return lines.toString();
        })));
        root.then(Commands.literal("stopwatch").executes(c -> run(c, () -> { SaveAcceptanceService.stop(c.getSource().getServer()); return "OK"; })));
        root.then(Commands.literal("unload").executes(c -> run(c, () -> { SaveAcceptanceService.unload(c.getSource().getServer()); return "OK"; })));
        var settlement = Commands.argument("settlement", StringArgumentType.word());
        settlement.then(Commands.literal("construction80inspect").executes(c->run(c,()->{var t=target(c);return com.hkin.exiledsettlement.debug.Construction80Checks.inspect(t.level,t.settlement);} )));
        settlement.then(Commands.literal("gradereview").executes(c->run(c,()->{var t=target(c);com.hkin.exiledsettlement.terrain.PublicGroundService.requestReview(t.settlement);SettlementSavedData.get(t.level).touch();return "OK request normal public ground survey; no world edits";})));
        settlement.then(Commands.literal("workindexaudit").executes(c->run(c,()->{var t=target(c);return com.hkin.exiledsettlement.debug.WorkIndexChecks.audit(t.settlement);})));
        settlement.then(Commands.literal("roadchanged").then(Commands.argument("position",net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c->run(c,()->{
            var t=target(c);var p=net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c,"position");
            if(!t.settlement.contains(p) || !t.level.hasChunkAt(p))throw new IllegalArgumentException("OUTSIDE_LOADED_SETTLEMENT");
            com.hkin.exiledsettlement.road.PlayerRoadEdits.changed(t.level,p);return "OK resurvey actual player edit; no world edits";
        }))));
        settlement.then(Commands.literal("claim").then(Commands.argument("position",net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c->run(c,()->{
            var t=target(c);var p=net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c,"position");
            var result=SettlementSavedData.get(t.level).claimChunk(p,t.settlement.ownerId());if(!result.success())throw new IllegalArgumentException(result.reason());return "OK normal adjacent claim business; test scenario setup only";
        }))));
        settlement.then(Commands.literal("bridgeinspect").then(Commands.argument("name",StringArgumentType.word()).executes(c->run(c,()->{var t=target(c);return com.hkin.exiledsettlement.debug.BridgeInspection.export(t.level,t.settlement,word(c,"name"));}))));
        settlement.then(Commands.literal("roadeditcheck").executes(c->run(c,()->{
            var t=target(c);return com.hkin.exiledsettlement.debug.RoadEditChecks.run(t.level,t.settlement);
        })));
        settlement.then(Commands.literal("block").then(Commands.argument("position",net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c->run(c,()->{
            var t=target(c);var p=net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c,"position");
            if(!t.settlement.contains(p) || !t.level.hasChunkAt(p))throw new IllegalArgumentException("OUTSIDE_LOADED_SETTLEMENT");
            var out=new com.google.gson.JsonObject();out.addProperty("position",p.toShortString());out.addProperty("state",t.level.getBlockState(p).toString());
            out.addProperty("road",com.hkin.exiledsettlement.road.RoadSpaceData.get(t.level).contains(p));
            out.addProperty("manual",com.hkin.exiledsettlement.road.RoadSpaceData.get(t.level).manual(p));
            out.addProperty("playerSurface",com.hkin.exiledsettlement.road.RoadSpaceData.get(t.level).playerSurfaceAt(p));
            out.addProperty("editableByRoad",com.hkin.exiledsettlement.road.AdaptiveRoadService.editableSurface(t.level,t.settlement,p));
            out.addProperty("pending",com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(t.level,t.settlement,p));
            out.addProperty("paidObsolete",t.settlement.terrain().supersededPaid(p,t.level.getBlockState(p)));return out.toString();
        }))));
        for(String operation:new String[]{"placementcheck","place"}) {
            settlement.then(Commands.literal(operation).then(Commands.argument("type",StringArgumentType.word())
                    .then(Commands.argument("position",net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                    .then(Commands.argument("facing",StringArgumentType.word()).executes(c -> run(c,() -> {
                        var t=target(c);var type=BuildingType.valueOf(word(c,"type"));
                        var position=net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c,"position");
                        var facing=net.minecraft.core.Direction.byName(word(c,"facing"));
                        if(facing==null || !facing.getAxis().isHorizontal())throw new IllegalArgumentException("INVALID_FACING");
                        var template=com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(type,position,facing);
                        var check=com.hkin.exiledsettlement.blueprint.BlueprintService.previewSite(t.level,t.settlement,template);
                        var out=new com.google.gson.JsonObject();out.addProperty("valid",check.valid());out.addProperty("reason",check.reason());
                        out.addProperty("position",check.position().toShortString());out.addProperty("clearances",check.clearances());
                        out.addProperty("min",template.minCorner().toShortString());out.addProperty("max",template.maxCorner().toShortString());
                        out.addProperty("structureCells",template.placementLayout().structure().size());out.addProperty("airCells",template.placementLayout().air().size());
                        if(operation.equals("place")) {
                            var result=com.hkin.exiledsettlement.blueprint.BlueprintService.placeBlueprint(t.level,t.settlement.ownerId(),type,position,facing,false,null);
                            if(!result.success())throw new IllegalArgumentException(result.reason());out.addProperty("blueprint",result.value().id().toString());
                        }
                        return out.toString();
                    }))))));
        }
        settlement.then(Commands.literal("roadpreview").then(Commands.argument("route",StringArgumentType.word())
                .executes(c->run(c,()->{
                    var t=target(c);var id=UUID.fromString(word(c,"route"));
                    var route=t.settlement.autoRoads().routes.values().stream().filter(r->r.id.equals(id)).findFirst().orElseThrow();
                    var line=com.hkin.exiledsettlement.road.AutoRoadPlanner.previewGrade(t.level,t.settlement,route.centerline);
                    var cells=com.hkin.exiledsettlement.road.AutoRoadService.corridor(t.level,t.settlement,line);
                    var out=new com.google.gson.JsonObject();out.addProperty("route",id.toString());
                    out.addProperty("beforeRoughness",com.hkin.exiledsettlement.road.AdaptiveRoadService.roughness(route.centerline));
                    out.addProperty("afterRoughness",com.hkin.exiledsettlement.road.AdaptiveRoadService.roughness(line));
                    out.addProperty("beforeCrossfall",com.hkin.exiledsettlement.road.AdaptiveRoadService.crossfall(route.centerline,route.cells));
                    out.addProperty("afterCrossfall",com.hkin.exiledsettlement.road.AdaptiveRoadService.crossfall(line,cells));
                    out.addProperty("corridorValid",!cells.isEmpty());var positions=new com.google.gson.JsonArray();
                    for(var p:line){var xyz=new com.google.gson.JsonArray();xyz.add(p.getX());xyz.add(p.getY());xyz.add(p.getZ());positions.add(xyz);}out.add("centerline",positions);
                    return out.toString();
                }))));
        settlement.then(Commands.literal("goal").then(Commands.argument("site",StringArgumentType.word())
                .then(Commands.argument("position",net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c->run(c,()->{
                    var t=target(c);var site=t.settlement.blueprint(UUID.fromString(word(c,"site")));
                    if(site==null)throw new IllegalArgumentException("NO_BLUEPRINT");
                    var p=net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c,"position");
                    var template=com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(site);var goal=template.placementAt(p);
                    if(goal==null)throw new IllegalArgumentException("NO_GOAL");
                    var j=new com.google.gson.JsonObject();j.addProperty("actual",t.level.getBlockState(p).toString());j.addProperty("above",t.level.getBlockState(p.above()).toString());j.addProperty("goal",goal.toString());
                    j.addProperty("satisfied",com.hkin.exiledsettlement.blueprint.BlueprintService.placementSatisfied(t.level,site,goal));
                    j.addProperty("clearance",site.hasClearanceTarget(p));j.addProperty("pending",String.valueOf(com.hkin.exiledsettlement.blueprint.BlueprintService.pendingAt(t.level,site,p)));
                    j.addProperty("dependency",String.valueOf(com.hkin.exiledsettlement.blueprint.ConstructionDependencies.firstUnfinished(t.level,template,goal)));
                    j.addProperty("closure",String.valueOf(com.hkin.exiledsettlement.blueprint.ConstructionClosureGuard.prerequisite(t.level,template,goal)));
                    j.addProperty("earthwork",com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(t.level,t.settlement,p));
                    j.addProperty("earthworkBelow",com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(t.level,t.settlement,p.below()));
                    var owner=t.settlement.terrain().cells.get(p.below());var order=owner==null?null:t.settlement.terrain().projects.get(owner);
                    if(order!=null){j.addProperty("belowOrder",order.name+" "+order.id);var steps=new com.google.gson.JsonArray();for(int i:order.indices(p.below())){var step=order.steps.get(i);steps.add(i+" "+step+" done="+(i<order.cursor || order.finished.get(i))+" guard="+com.hkin.exiledsettlement.terrain.TerrainService.guard(t.level,t.settlement,t.settlement.blueprint(owner),step));}j.add("belowSteps",steps);}
                    return j.toString();
                })))));
        settlement.then(Commands.literal("load").then(Commands.argument("radius", IntegerArgumentType.integer(1, 12)).executes(c -> run(c, () -> {
            var t = target(c); return "OK " + SaveAcceptanceService.load(t.level, t.settlement, number(c, "radius"));
        }))));
        settlement.then(Commands.literal("area").then(Commands.argument("type", StringArgumentType.word())
                .then(Commands.argument("first", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                .then(Commands.argument("second", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c -> run(c, () -> {
                    var t = target(c); var type = BuildingType.valueOf(word(c, "type"));
                    if (!com.hkin.exiledsettlement.region.ProductionRegionService.supports(type)) throw new IllegalArgumentException("UNSUPPORTED_AREA_TYPE");
                    var first = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "first");
                    var second = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "second");
                    if (!t.settlement.contains(first) || !t.settlement.contains(second)) throw new IllegalArgumentException("OUTSIDE_SETTLEMENT_BOUNDARY");
                    var result = com.hkin.exiledsettlement.region.ProductionRegionService.place(t.level, type, first, second,
                            net.minecraft.core.Direction.SOUTH, t.settlement.ownerId(), null);
                    if (!result.success()) throw new IllegalArgumentException(result.reason());
                    return "OK " + result.value().id();
                }))))));
        settlement.then(Commands.literal("snapshot").then(Commands.argument("name", StringArgumentType.word()).executes(c -> run(c, () -> {
            var t = target(c); return SaveAcceptanceService.export(t.level, t.settlement, word(c, "name")).toString();
        }))));
        settlement.then(Commands.literal("watch").then(Commands.argument("name", StringArgumentType.word())
                .then(Commands.argument("ticks", IntegerArgumentType.integer(100, 72000))
                .then(Commands.argument("interval", IntegerArgumentType.integer(100, 1200)).executes(c -> run(c, () -> {
                    var t = target(c); SaveAcceptanceService.watch(t.level, t.settlement, word(c, "name"), number(c, "ticks"), number(c, "interval")); return "OK";
                }))))));
        settlement.then(Commands.literal("setting").then(Commands.argument("key", StringArgumentType.word())
                .then(Commands.argument("value", StringArgumentType.word()).executes(c -> run(c, () -> {
                    var t = target(c);
                    if (!CommunitySettings.apply(t.settlement, word(c, "key"), word(c, "value"))) throw new IllegalArgumentException("INVALID_SETTING");
                    SettlementSavedData.get(t.level).touch(); return "OK";
                })))));
        var building = Commands.argument("building", StringArgumentType.word());
        building.then(Commands.literal("category").then(Commands.argument("category",StringArgumentType.word())
                .then(Commands.argument("revision",IntegerArgumentType.integer(0)).executes(c -> run(c, () -> {
                    var t=target(c);var b=building(c,t);
                    if(!b.configurationMatches(number(c,"revision")))throw new IllegalArgumentException("CONFIGURATION_CONFLICT");
                    if(!b.toggleWarehouseCategory(WarehouseCategory.valueOf(word(c,"category"))))throw new IllegalArgumentException("INVALID_STORAGE_CATEGORY");
                    SettlementSavedData.get(t.level).touch();return "OK";
                })))));
        settlement.then(Commands.literal("worksite").then(Commands.argument("site",StringArgumentType.word())
                .then(Commands.literal("staff").then(Commands.argument("citizen",StringArgumentType.word())
                .then(Commands.argument("unlock",BoolArgumentType.bool()).then(Commands.argument("revision",IntegerArgumentType.integer(0)).executes(c->run(c,()->{
                    var t=target(c);var result=com.hkin.exiledsettlement.citizen.WorksiteAssignmentService.assign(t.settlement,t.settlement.ownerId(),
                        UUID.fromString(word(c,"site")),UUID.fromString(word(c,"citizen")),BoolArgumentType.getBool(c,"unlock"),number(c,"revision"));
                    if(!result.success())throw new IllegalArgumentException(result.reason());SettlementSavedData.get(t.level).touch();return "OK";
                }))))))));
        settlement.then(Commands.literal("worksite").then(Commands.argument("site",StringArgumentType.word())
                .then(Commands.literal("mode").then(Commands.argument("mode",StringArgumentType.word())
                .then(Commands.argument("revision",IntegerArgumentType.integer(0)).executes(c->run(c,()->{
                    var t=target(c);var result=com.hkin.exiledsettlement.citizen.WorksiteAssignmentService.setMode(t.settlement,t.settlement.ownerId(),
                        UUID.fromString(word(c,"site")),com.hkin.exiledsettlement.building.WorksiteAssignmentMode.valueOf(word(c,"mode")),number(c,"revision"));
                    if(!result.success())throw new IllegalArgumentException(result.reason());SettlementSavedData.get(t.level).touch();return "OK";
                })))))));
        building.then(Commands.literal("workers").then(Commands.argument("target", IntegerArgumentType.integer(0))
                .then(Commands.argument("revision", IntegerArgumentType.integer(0)).executes(c -> run(c, () -> {
                    var t = target(c); var b = building(c, t); var data = SettlementSavedData.get(t.level);
                    if (!b.configurationMatches(number(c, "revision"))) throw new IllegalArgumentException("CONFIGURATION_CONFLICT");
                    if (b.workerTarget() == number(c, "target")) return "OK";
                    if (!data.setBuildingWorkerTarget(t.settlement, t.settlement.ownerId(), b.id(), number(c, "target"), number(c, "revision")))
                        throw new IllegalArgumentException("CONFIGURATION_CONFLICT_OR_CAPACITY_BLOCKED");
                    ProfessionAssignmentService.refresh(data, t.settlement); return "OK";
                })))));
        building.then(Commands.literal("production").then(Commands.argument("production", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .then(Commands.argument("revision", IntegerArgumentType.integer(0)).executes(c -> run(c, () -> {
                    var t = target(c); var b = building(c, t); var production = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "production");
                    if (!b.configurationMatches(number(c, "revision"))) throw new IllegalArgumentException("CONFIGURATION_CONFLICT");
                    if (!BuildingProductionService.supported(b.type(), production)) throw new IllegalArgumentException("UNSUPPORTED_PRODUCTION");
                    if (BuildingManagementActions.production(t.settlement, b, production)) SettlementSavedData.get(t.level).touch();
                    return "OK";
                })))));
        building.then(Commands.literal("staff").then(Commands.argument("citizen", StringArgumentType.word())
                .then(Commands.argument("unlock", BoolArgumentType.bool())
                .then(Commands.argument("revision", IntegerArgumentType.integer(0)).executes(c -> run(c, () -> {
                    var t = target(c); var b = building(c, t);
                    String failure = BuildingManagementActions.staff(SettlementSavedData.get(t.level), t.settlement, t.settlement.ownerId(), b,
                            UUID.fromString(word(c, "citizen")), BoolArgumentType.getBool(c, "unlock"), number(c, "revision"));
                    if (failure != null) throw new IllegalArgumentException(failure); return "OK";
                }))))));
        settlement.then(building); root.then(settlement); dispatcher.register(root);
    }
    private record Target(ServerLevel level, SettlementRecord settlement) { }
    private static Target target(CommandContext<CommandSourceStack> c) {
        UUID id = UUID.fromString(word(c, "settlement"));
        for (var level : c.getSource().getServer().getAllLevels()) {
            var s = SettlementSavedData.get(level).findById(id).orElse(null);
            if (s != null) {
                if (!s.active()) throw new IllegalArgumentException("CORE_INACTIVE");
                return new Target(level, s);
            }
        }
        throw new IllegalArgumentException("NO_SETTLEMENT");
    }
    private static BuildingRecord building(CommandContext<CommandSourceStack> c, Target t) {
        var b = t.settlement.building(UUID.fromString(word(c, "building")));
        if (b == null || !b.active()) throw new IllegalArgumentException("NO_BUILDING");
        return b;
    }
    private static String word(CommandContext<CommandSourceStack> c, String key) { return StringArgumentType.getString(c, key); }
    private static int number(CommandContext<CommandSourceStack> c, String key) { return IntegerArgumentType.getInteger(c, key); }
    private interface Action { String run() throws Exception; }
    private static int run(CommandContext<CommandSourceStack> c, Action action) {
        try { String result = action.run(); c.getSource().sendSuccess(() -> Component.literal(result), false); return 1; }
        catch (Exception failure) { c.getSource().sendFailure(Component.literal("ERROR " + failure.getMessage())); return 0; }
    }
}

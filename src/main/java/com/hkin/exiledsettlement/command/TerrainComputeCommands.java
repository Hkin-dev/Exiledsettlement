package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.TerrainComputeDebugService;
import com.hkin.exiledsettlement.settlement.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** A local player selects their nearby settlement; a console can specify its UUID. */
public final class TerrainComputeCommands {
    private TerrainComputeCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        var branch=Commands.literal("terrain_compute").requires(s->s.hasPermission(2))
                .executes(c->run(c.getSource(),null,null));
        branch.then(Commands.argument("multiplier",IntegerArgumentType.integer(1,TerrainComputeDebugService.MAX_MULTIPLIER))
                .executes(c->run(c.getSource(),IntegerArgumentType.getInteger(c,"multiplier"),null))
                .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->
                        run(c.getSource(),IntegerArgumentType.getInteger(c,"multiplier"),StringArgumentType.getString(c,"settlement")))));
        for(String action:new String[]{"status","off","scan"}) {
            Integer value=action.equals("off")?Integer.valueOf(1):action.equals("scan")?Integer.valueOf(-1):null;
            branch.then(Commands.literal(action).executes(c->run(c.getSource(),value,null))
                    .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->
                            run(c.getSource(),value,StringArgumentType.getString(c,"settlement")))));
        }
        return branch;
    }
    private static int run(CommandSourceStack source,Integer value,String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var level=source.getLevel();var data=SettlementSavedData.get(level);SettlementRecord settlement;
        if(id==null) {
            var player=source.getPlayerOrException();
            settlement=data.findNearestForPlayer(player.getUUID(),player.blockPosition(),false).orElse(null);
        } else {
            try { settlement=data.findById(UUID.fromString(id)).orElse(null); }
            catch(IllegalArgumentException e) {
                source.sendFailure(Component.translatable("message.exiledsettlement.debug.terrain_compute.invalid_id"));return 0;
            }
        }
        if(settlement==null || !settlement.active()) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));return 0;
        }
        if(value!=null && value==-1){
            var center=source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player?player.blockPosition():settlement.corePosition();
            var scan=com.hkin.exiledsettlement.terrain.PublicGroundService.scanComplexity(level,settlement,center);
            var a=scan.actual();var p=scan.planned();
            source.sendSuccess(()->Component.translatable("message.exiledsettlement.debug.terrain_scan.coverage",scan.centerX(),scan.centerZ(),scan.radius(),a.columns(),scan.excludedColumns(),scan.unknownColumns(),scan.missingTargets(),scan.mismatchedColumns(),scan.maximumHeightError(),scan.planReady()),false);
            source.sendSuccess(()->Component.translatable("message.exiledsettlement.debug.terrain_scan.geometry",a.enclosedPits(),a.isolatedPeaks(),a.reversals(),a.narrowRidges(),a.steepEdges(),p.columns(),p.enclosedPits(),p.isolatedPeaks(),p.reversals(),p.narrowRidges(),p.steepEdges()),false);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("TERRAIN_COMPLEXITY settlement={} report={}",settlement.id(),new com.google.gson.Gson().toJson(scan));
            return a.columns();
        }
        if(value!=null)TerrainComputeDebugService.set(level,settlement,value);
        int multiplier=TerrainComputeDebugService.multiplier(level,settlement);
        var terrain=settlement.terrain();
        var state=Component.translatable(!terrain.enabled?"message.exiledsettlement.debug.terrain_compute.disabled":
                terrain.publicPaused || settlement.community().evacuation?"message.exiledsettlement.debug.terrain_compute.paused":
                "message.exiledsettlement.debug.terrain_compute.running");
        var message=Component.translatable("message.exiledsettlement.debug.terrain_compute.status",settlement.name(),
                multiplier,TerrainComputeDebugService.budgetNanos(multiplier)/1_000_000L,state,terrain.publicGround.status);
        source.sendSuccess(()->message,value!=null);
        return multiplier;
    }
}

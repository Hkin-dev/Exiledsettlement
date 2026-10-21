package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.TerrainEvolveDebugService;
import com.hkin.exiledsettlement.terrain.InstantTerrainService;
import com.hkin.exiledsettlement.settlement.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** A local player selects their nearby settlement; a console can specify its UUID. */
public final class TerrainEvolveCommands {
    private TerrainEvolveCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        var branch=Commands.literal("terrain_evolve").requires(s->s.hasPermission(2))
                .executes(c->run(c.getSource(),null,null));
        branch.then(Commands.argument("multiplier",IntegerArgumentType.integer(1,8))
                .executes(c->run(c.getSource(),IntegerArgumentType.getInteger(c,"multiplier"),null))
                .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->
                        run(c.getSource(),IntegerArgumentType.getInteger(c,"multiplier"),StringArgumentType.getString(c,"settlement")))));
        for(String action:new String[]{"status","off","finish","on"}) {
            Integer value=null;
            if(action.equals("finish") || action.equals("on"))value=-1;
            else if(action.equals("off"))value=0;
            final Integer selected=value;
            var literal=Commands.literal(action).executes(c->run(c.getSource(),selected,null))
                    .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->
                            run(c.getSource(),selected,StringArgumentType.getString(c,"settlement"))));
            if(action.equals("finish"))for(String mode:new String[]{"on","off","status"}){
                int selection=mode.equals("on")?-1:mode.equals("off")?-2:-3;
                literal.then(Commands.literal(mode).executes(c->run(c.getSource(),selection,null))
                        .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->
                                run(c.getSource(),selection,StringArgumentType.getString(c,"settlement")))));
            }
            branch.then(literal);
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
                source.sendFailure(Component.translatable("message.exiledsettlement.debug.terrain_evolve.invalid_id"));return 0;
            }
        }
        if(settlement==null || !settlement.active()) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));return 0;
        }
        if(value!=null && value==-1){
            var result=InstantTerrainService.enable(level,settlement);
            if(result!=null)source.sendSuccess(()->Component.translatable("message.exiledsettlement.debug.terrain_evolve.finished",
                    result.columns(),result.changedBlocks(),result.roadCells(),result.failedRoads(),result.planningMillis(),result.totalMillis()),true);
            source.sendSuccess(()->InstantTerrainService.statusMessage(level,settlement),true);
            return result==null?0:Math.max(1,result.changedBlocks());
        }
        if(value!=null && (value==-2 || value==-3)){
            if(value==-2)InstantTerrainService.disable(level,settlement);
            source.sendSuccess(()->InstantTerrainService.statusMessage(level,settlement),value==-2);
            return InstantTerrainService.enabled(level,settlement)?1:0;
        }
        if(value!=null && value==0)InstantTerrainService.disable(level,settlement);
        if(value!=null)TerrainEvolveDebugService.set(level,settlement,value);
        int multiplier=TerrainEvolveDebugService.multiplier(level,settlement);
        var terrain=settlement.terrain();
        var state=Component.translatable(!terrain.enabled?"message.exiledsettlement.debug.terrain_evolve.disabled":
                terrain.publicPaused || settlement.community().evacuation?"message.exiledsettlement.debug.terrain_evolve.paused":
                "message.exiledsettlement.debug.terrain_evolve.running");
        var message=Component.translatable("message.exiledsettlement.debug.terrain_evolve.status",settlement.name(),
                multiplier,TerrainEvolveDebugService.steps(level,settlement),state,terrain.publicGround.status);
        source.sendSuccess(()->message,value!=null);
        source.sendSuccess(()->InstantTerrainService.statusMessage(level,settlement),false);
        return multiplier;
    }
}

package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.WorkTargetDebugService;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;

public final class WorkTargetCommands {
    private WorkTargetCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("work_targets")
            .then(Commands.literal("on").executes(c->run(c.getSource(),"on",false))
                .then(Commands.literal("workers").executes(c->run(c.getSource(),"on",true)))
                .then(Commands.literal("all").executes(c->run(c.getSource(),"on",false))))
            .then(Commands.literal("off").executes(c->run(c.getSource(),"off",false)))
            .then(Commands.literal("status").executes(c->run(c.getSource(),"status",false)));
    }
    private static int run(CommandSourceStack source,String operation,boolean workers) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=source.getPlayerOrException();
        if(operation.equals("on")) {
            var s=SettlementSavedData.get(p.serverLevel()).findNearestForPlayer(p.getUUID(),p.blockPosition(),false).orElse(null);
            if(s==null || !s.active()){source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));return 0;}
            WorkTargetDebugService.set(p,s,true,workers);
        } else if(operation.equals("off"))WorkTargetDebugService.set(p,null,false,false);
        source.sendSuccess(()->Component.literal("居民目标连线："+WorkTargetDebugService.status(p)+"。青色为正常目标，红色为受阻目标。"),false);return 1;
    }
}

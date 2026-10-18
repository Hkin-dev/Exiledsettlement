package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.ForceWorkDebugService;
import com.hkin.exiledsettlement.settlement.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Same administrator command works in a client and headless console. */
public final class ForceWorkCommands {
    private ForceWorkCommands() { }
    public static LiteralArgumentBuilder<CommandSourceStack> branch(){
        var branch=Commands.literal("force_work").requires(s->s.hasPermission(2));
        for(var operation:new String[]{"on","off","status"})branch.then(Commands.literal(operation)
            .executes(c->run(c.getSource(),operation,null))
            .then(Commands.argument("settlement",StringArgumentType.word()).executes(c->run(c.getSource(),operation,StringArgumentType.getString(c,"settlement")))));
        return branch;
    }
    private static int run(CommandSourceStack source,String operation,String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var level=source.getLevel();var data=SettlementSavedData.get(level);SettlementRecord s;
        if(id==null){var player=source.getPlayerOrException();s=data.findNearestForPlayer(player.getUUID(),player.blockPosition(),false).orElse(null);}
        else {try{s=data.findById(UUID.fromString(id)).orElse(null);}catch(IllegalArgumentException e){source.sendFailure(Component.literal("聚落编号格式不正确"));return 0;}}
        if(s==null || !s.active()){source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));return 0;}
        if(!operation.equals("status"))ForceWorkDebugService.set(level,s,operation.equals("on"));
        boolean enabled=ForceWorkDebugService.enabled(level,s);String message=s.name()+"：强制工作调试"+(enabled?"已开启；跳过作息与生存需求中断，保留真实工具、材料、寻路和施工。服务器重启自动关闭。":"已关闭，恢复正常作息与生存需求。");
        source.sendSuccess(()->Component.literal(message),!operation.equals("status"));return 1;
    }
}

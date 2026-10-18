package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.BuildingEditBridge;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class BuildingEditCommands {
    private BuildingEditCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("buildingapi")
                .requires(s -> s.hasPermission(2) && s.getEntity() instanceof ServerPlayer p && p.isCreative())
                .executes(c -> message(c.getSource(), "/buildingapi open：在东南空地授权48×24×48现场\n/buildingapi region 名称 起点 终点：授权已有建筑副本\n/buildingapi revoke 名称 · /buildingapi off"))
                .then(Commands.literal("open").executes(c -> run(c.getSource(), p -> {
                    var bridge = BuildingEditBridge.enable(p);
                    var origin = p.blockPosition().offset(6, 0, 6);
                    bridge.authorize(p, "workbench", origin, origin.offset(47, 23, 47));
                    return bridge.summary();
                })))
                .then(Commands.literal("region").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("first", BlockPosArgument.blockPos()).then(Commands.argument("second", BlockPosArgument.blockPos())
                                .executes(c -> run(c.getSource(), p -> {
                                    var bridge = BuildingEditBridge.enable(p);
                                    bridge.authorize(p, StringArgumentType.getString(c, "name"), BlockPosArgument.getLoadedBlockPos(c, "first"), BlockPosArgument.getLoadedBlockPos(c, "second"));
                                    return bridge.summary();
                                }))))))
                .then(Commands.literal("revoke").then(Commands.argument("name", StringArgumentType.word()).executes(c -> run(c.getSource(), p -> {
                    BuildingEditBridge.enable(p).revoke(StringArgumentType.getString(c, "name")); return "区域授权已撤销；已放置的真实方块保留";
                }))))
                .then(Commands.literal("off").executes(c -> run(c.getSource(), p -> {
                    BuildingEditBridge.disable(p); return "本机编辑接口已关闭；真实方块和修改记录保留";
                }))));
    }
    private static int message(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); return 1; }
    private static int run(CommandSourceStack source, Action action) {
        try { return message(source, action.apply(source.getPlayerOrException())); }
        catch (Exception failure) { source.sendFailure(Component.literal("建筑API：" + failure.getMessage())); return 0; }
    }
    private interface Action { String apply(ServerPlayer player) throws Exception; }
}

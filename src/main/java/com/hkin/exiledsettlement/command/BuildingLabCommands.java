package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.BuildingLabService;
import com.hkin.exiledsettlement.debug.BuildingLabTemplate;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Creative-only tools for a human to arm a bounded live design site. */
public final class BuildingLabCommands {
    private BuildingLabCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("buildinglab")
                .requires(source -> source.hasPermission(2) && source.getEntity() instanceof ServerPlayer player
                        && player.isCreative())
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("start")
                        .then(Commands.argument("candidate", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildingLabTemplate.IDS, builder))
                                .executes(context -> run(context.getSource(), "start",
                                        StringArgumentType.getString(context, "candidate"), null, false))
                                .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                        .executes(context -> run(context.getSource(), "start",
                                                StringArgumentType.getString(context, "candidate"),
                                                BlockPosArgument.getLoadedBlockPos(context, "origin"), false)))))
                .then(Commands.literal("view")
                        .then(Commands.argument("candidate", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildingLabTemplate.IDS, builder))
                                .then(Commands.literal("interior").executes(context -> run(context.getSource(), "view",
                                        StringArgumentType.getString(context, "candidate"), null, true)))
                                .then(Commands.literal("full").executes(context -> run(context.getSource(), "view",
                                        StringArgumentType.getString(context, "candidate"), null, false)))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("candidate", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildingLabTemplate.IDS, builder))
                                .executes(context -> run(context.getSource(), "stop",
                                        StringArgumentType.getString(context, "candidate"), null, false))))
                .then(Commands.literal("status").executes(context -> run(context.getSource(), "status", "", null, false))));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("建筑设计现场：\n/buildinglab start town_hall_round3_v2"
                + "\n/buildinglab view town_hall_round3_v2 interior（室内剖切）"
                + "\n/buildinglab view town_hall_round3_v2 full（完整外观）"
                + "\n/buildinglab status · /buildinglab stop 候选ID"
                + "\n需要创造模式与作弊权限。JSON 改动自动刷新，无需重启。"), false);
        return 1;
    }

    private static int run(CommandSourceStack source, String action, String id, BlockPos origin, boolean interior) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            String message = switch (action) {
                case "start" -> BuildingLabService.start(player.serverLevel(), player.getUUID(), id,
                        origin == null ? player.blockPosition().offset(6, 0, 6) : origin);
                case "view" -> BuildingLabService.view(player.serverLevel(), player.getUUID(), id, interior);
                case "stop" -> BuildingLabService.stop(player.serverLevel(), player.getUUID(), id);
                default -> BuildingLabService.status(player.serverLevel(), player.getUUID());
            };
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("建筑设计现场：" + failure.getMessage()));
            return 0;
        }
    }
}

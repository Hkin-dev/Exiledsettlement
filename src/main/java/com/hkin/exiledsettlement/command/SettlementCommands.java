package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.debug.ConstructionDebugService;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.TravelingMerchantService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.UUID;

public final class SettlementCommands {
    private SettlementCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("exiledsettlement")
                .then(SeasonCommands.branch())
                .then(Commands.literal("recover_kit").executes(context -> {
                    var player=context.getSource().getPlayerOrException();
                    boolean recovered=com.hkin.exiledsettlement.settlement.FoundingService.recover(player);
                    player.displayClientMessage(Component.literal(recovered ? "已恢复尚未使用的开拓道具。" : "没有可恢复的开拓资格，或开拓机会已经使用。"),false);
                    return recovered ? 1 : 0;
                }))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("recovery").executes(context -> recovery(context.getSource())))
                .then(Commands.literal("life").executes(context -> life(context.getSource())))
                .then(Commands.literal("roads").executes(context -> roads(context.getSource())))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> rename(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("member")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> addMember(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player")
                                        ))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> removeMember(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player")
                                        )))))
                .then(Commands.literal("diagnose")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> diagnose(context.getSource())))
                .then(Commands.literal("merchant")
                        .then(Commands.literal("catalog")
                                .executes(context -> merchantCatalog(context.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> merchantCatalog(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")))))
                        .then(Commands.literal("orders")
                                .executes(context -> merchantOrders(context.getSource())))
                        .then(Commands.literal("order")
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> merchantOrder(context.getSource(),
                                                        StringArgumentType.getString(context, "item"),
                                                        IntegerArgumentType.getInteger(context, "count"))))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("orderId", StringArgumentType.word())
                                        .executes(context -> merchantCancel(context.getSource(),
                                                StringArgumentType.getString(context, "orderId"))))))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(ResidentClimateCommands.branch())
                        .then(ForceWorkCommands.branch())
                        .then(TerrainComputeCommands.branch())
                        .then(TerrainEvolveCommands.branch())
                        .then(WorkTargetCommands.branch())
                        .then(Commands.literal("work_status")
                                .executes(context -> workStatus(context.getSource())))
                        .then(Commands.literal("construction_force")
                                .then(Commands.literal("on")
                                        .executes(context -> constructionDebug(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> constructionDebug(context.getSource(), false)))
                                .then(Commands.literal("status")
                                        .executes(context -> constructionDebugStatus(context.getSource()))))));
    }

    private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        SettlementMessages.sendSummary(player, settlement);
        return 1;
    }

    private static int life(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null || !settlement.contains(player.blockPosition())) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement")); return 0;
        }
        com.hkin.exiledsettlement.settlement.CommunityManagement.open(player, settlement);
        return 1;
    }

    private static int roads(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null || !settlement.isMember(player.getUUID()) || !settlement.contains(player.blockPosition())) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement")); return 0;
        }
        for (String line : com.hkin.exiledsettlement.road.AutoRoadService.report(player.serverLevel(), settlement))
            source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int list(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        int count = 0;
        for (SettlementRecord settlement : data.settlements()) {
            if (!settlement.isMember(player.getUUID())) {
                continue;
            }
            count++;
            source.sendSuccess(
                    () -> Component.translatable(
                            "message.exiledsettlement.list.entry",
                            settlement.name(),
                            SettlementMessages.shortId(settlement.id()),
                            settlement.corePosition().toShortString(),
                            Component.translatable(settlement.active()
                                    ? "state.exiledsettlement.active"
                                    : "state.exiledsettlement.inactive")
                    ),
                    false
            );
        }
        if (count == 0) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        return count;
    }

    private static int rename(CommandSourceStack source, String newName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, true);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        if (!settlement.isOwner(player.getUUID())) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_permission"));
            return 0;
        }
        boolean changed = SettlementSavedData.get(player.serverLevel()).rename(settlement, player.getUUID(), newName);
        if (!changed) {
            source.sendFailure(Component.translatable("error.exiledsettlement.invalid_name"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.exiledsettlement.renamed", settlement.name()), false);
        return 1;
    }

    private static int addMember(CommandSourceStack source, ServerPlayer target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, true);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        if (!SettlementSavedData.get(player.serverLevel()).addMember(settlement, player.getUUID(), target.getUUID())) {
            source.sendFailure(Component.translatable("error.exiledsettlement.member_change_failed"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("message.exiledsettlement.member.added", target.getDisplayName(), settlement.name()),
                true
        );
        target.displayClientMessage(
                Component.translatable("message.exiledsettlement.member.joined", settlement.name()).withStyle(ChatFormatting.GREEN),
                false
        );
        return 1;
    }

    private static int removeMember(CommandSourceStack source, ServerPlayer target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, true);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        if (!SettlementSavedData.get(player.serverLevel()).removeMember(settlement, player.getUUID(), target.getUUID())) {
            source.sendFailure(Component.translatable("error.exiledsettlement.member_change_failed"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("message.exiledsettlement.member.removed", target.getDisplayName(), settlement.name()),
                true
        );
        target.displayClientMessage(
                Component.translatable("message.exiledsettlement.member.left", settlement.name()).withStyle(ChatFormatting.YELLOW),
                false
        );
        return 1;
    }

    private static int diagnose(CommandSourceStack source) {
        int dimensions = 0;
        int settlements = 0;
        int active = 0;
        int citizens = 0;
        int buildings = 0;
        for (var level : source.getServer().getAllLevels()) {
            dimensions++;
            for (SettlementRecord settlement : SettlementSavedData.get(level).settlements()) {
                settlements++;
                if (settlement.active()) {
                    active++;
                }
                citizens += settlement.citizens().size();
                buildings += settlement.buildings().size();
            }
        }
        int finalDimensions = dimensions;
        int finalSettlements = settlements;
        int finalActive = active;
        int finalCitizens = citizens;
        int finalBuildings = buildings;
        source.sendSuccess(
                () -> Component.translatable(
                        "message.exiledsettlement.diagnose",
                        SettlementSavedData.SCHEMA_VERSION,
                        finalDimensions,
                        finalSettlements,
                        finalActive,
                        finalCitizens,
                        finalBuildings
                ),
                false
        );
        return settlements;
    }

    private static int workStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        long tick = player.serverLevel().getGameTime();
        boolean forced = ConstructionDebugService.isEnabled(player.serverLevel(), settlement.id());
        source.sendSuccess(() -> Component.translatable("message.exiledsettlement.debug.work_status.header",
                settlement.name(), tick, forced), false);
        for (var site : settlement.blueprints()) {
            String status = site.id() + " " + site.constructionController().diagnostics()
                    + " owner=" + site.scaffoldWorker() + " " + site.constructionController().movementDiagnostics();
            source.sendSuccess(() -> Component.literal(status), false);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[construction-site] {}", status);
        }
        int count = 0;
        for (var citizen : settlement.citizens()) {
            if (!citizen.alive()) continue;
            count++;
            var entity = com.hkin.exiledsettlement.citizen.CitizenProjectionService.findProjection(player.serverLevel(), citizen);
            String position = (entity == null ? citizen.lastPosition() : entity.blockPosition()).toShortString();
            String target = citizen.targetPosition() == null ? "-" : citizen.targetPosition().toShortString();
            long slots = citizen.inventory().stream().filter(stack -> !stack.isEmpty()).count();
            int items = citizen.inventory().stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            Component failure = citizen.workFailure().isEmpty() ? Component.literal("-")
                    : Component.translatable("work_failure.exiledsettlement."
                    + citizen.workFailure().toLowerCase(java.util.Locale.ROOT));
            Component line = Component.translatable("message.exiledsettlement.debug.work_status.resident",
                    citizen.name(), Component.translatable(citizen.profession().translationKey()),
                    Component.translatable(citizen.activity().translationKey()),
                    Component.translatable(citizen.workState().translationKey()), position, target, slots, items,
                    failure, Component.translatable(entity == null
                            ? "message.exiledsettlement.debug.work_status.unloaded"
                            : "message.exiledsettlement.debug.work_status.loaded"));
            source.sendSuccess(() -> line, false);
            if (entity != null) {
                var planning = com.hkin.exiledsettlement.citizen.ConstructionPlanningBudget.snapshot(
                        player.serverLevel(), entity.getUUID());
                source.sendSuccess(() -> Component.translatable(
                        "message.exiledsettlement.debug.work_status.planning", citizen.name(),
                        planning.residentPaths(), planning.levelPaths(), planning.residentEgress(),
                        planning.levelEgress(), planning.residentTotalPaths(), planning.residentCacheHits(),
                        planning.memoEntries()), false);
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info(
                        "[resident-planning-budget] resident={} tick={} snapshot={}", citizen.id(), tick, planning);
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info(
                        "[resident-construction-diagnostic] resident={} tick={} mode={} movement={} navigation={} decisions={}",
                        citizen.id(), tick, entity.constructionMovementMode(), entity.constructionMovementDiagnostics(),
                        entity.settlementNavigationDiagnostics(), entity.constructionDecisionHistory());
            }
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info(
                    "[resident-work-status] tick={} settlement={} resident={} profession={} state={} activity={} position={} target={} failure={} inventory={} loaded={} forcedConstruction={}",
                    tick, settlement.id(), citizen.id(), citizen.profession(), citizen.workState(), citizen.activity(),
                    position, target, citizen.workFailure(), citizen.inventory(), entity != null, forced);
        }
        return count;
    }

    private static int constructionDebug(CommandSourceStack source, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        ConstructionDebugService.setEnabled(player.serverLevel(), settlement.id(), enabled);
        if(enabled)com.hkin.exiledsettlement.debug.ForceWorkDebugService.set(player.serverLevel(),settlement,false);
        source.sendSuccess(() -> Component.translatable(enabled
                        ? "message.exiledsettlement.debug.construction_force.enabled"
                        : "message.exiledsettlement.debug.construction_force.disabled",
                settlement.name(), ConstructionDebugService.ADVANCE_INTERVAL_TICKS), true);
        return 1;
    }

    private static int constructionDebugStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null) {
            source.sendFailure(Component.translatable("error.exiledsettlement.no_settlement"));
            return 0;
        }
        boolean enabled = ConstructionDebugService.isEnabled(player.serverLevel(), settlement.id());
        source.sendSuccess(() -> Component.translatable(enabled
                        ? "message.exiledsettlement.debug.construction_force.status_enabled"
                        : "message.exiledsettlement.debug.construction_force.status_disabled",
                settlement.name(), ConstructionDebugService.ADVANCE_INTERVAL_TICKS), false);
        return enabled ? 1 : 0;
    }

    private static int recovery(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayer();
        java.util.Collection<SettlementRecord> settlements=java.util.List.of();
        if(player!=null){var s=nearestSettlement(player,false);if(s!=null)settlements=java.util.List.of(s);}
        else if(source.hasPermission(2))settlements=SettlementSavedData.get(source.getLevel()).settlements();
        int count=0;
        for(var s:settlements)for(var event:s.mobRecovery().incidents.values()) {
            long done=event.cells.values().stream().filter(c->c.status==com.hkin.exiledsettlement.building.MobRecoveryState.Status.DONE).count();
            long total=event.cells.values().stream().filter(c->c.status!=com.hkin.exiledsettlement.building.MobRecoveryState.Status.UNCHANGED).count();
            if(total==0)continue;count++;
            source.sendSuccess(()->Component.literal(s.name()+" · P1 生物破坏恢复 "+event.id+" · "+event.cause+" · 已恢复 "+done+"/"+total
                    +" · 工人 "+com.hkin.exiledsettlement.citizen.WorkTaskService.assigned(s,event.id)+"/2"),false);
            event.cells.values().stream().filter(c->!c.reason.isEmpty()).limit(8).forEach(c->source.sendSuccess(()->Component.literal(c.pos.toShortString()+" · ")
                .append(Component.translatable("work_failure.exiledsettlement."+c.reason.toLowerCase(java.util.Locale.ROOT))),false));
        }
        if(count==0)source.sendSuccess(()->Component.literal("没有已记录的生物破坏恢复任务"),false);return count;
    }

    private static SettlementRecord nearestSettlement(ServerPlayer player, boolean includeInactive) {
        return SettlementSavedData.get(player.serverLevel())
                .findNearestForPlayer(player.getUUID(), player.blockPosition(), includeInactive)
                .orElse(null);
    }

    private static int merchantCatalog(CommandSourceStack source, int page)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var entries = TravelingMerchantService.catalog();
        int pages = (entries.size() + 9) / 10;
        if (page > pages) return 0;
        source.sendSuccess(() -> Component.translatable("message.exiledsettlement.merchant_catalog_header",
                page, pages), false);
        for (int index = (page - 1) * 10; index < Math.min(page * 10, entries.size()); index++) {
            var trade = entries.get(index);
            source.sendSuccess(() -> Component.translatable("message.exiledsettlement.merchant_catalog_entry",
                    BuiltInRegistries.ITEM.getKey(trade.item()), trade.count(), trade.emeralds()), false);
        }
        return 1;
    }

    private static int merchantOrders(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null) return 0;
        source.sendSuccess(() -> Component.translatable("message.exiledsettlement.merchant_orders_header",
                settlement.merchantState().nextVisitDay(), settlement.merchantState().orders().size()), false);
        settlement.merchantState().orders().forEach(order -> source.sendSuccess(
                () -> Component.literal(order.id() + "  " + order.item() + " x" + order.count()), false));
        return 1;
    }

    private static int merchantOrder(CommandSourceStack source, String itemName, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null || !settlement.isMember(player.getUUID())) return 0;
        boolean staffedMarket = settlement.buildings().stream().anyMatch(building ->
                building.type() == BuildingType.MARKET && building.active() && building.operational()
                        && settlement.citizens().stream().anyMatch(citizen -> citizen.alive()
                        && citizen.profession() == CitizenProfession.TRAVELING_MERCHANT
                        && building.id().equals(citizen.workBuildingId())));
        if (!staffedMarket) {
            source.sendFailure(Component.translatable("error.exiledsettlement.merchant_market_missing"));
            return 0;
        }
        ResourceLocation item = ResourceLocation.tryParse(itemName);
        if (item == null || !TravelingMerchantService.order(player.serverLevel(), settlement, item, count)) {
            source.sendFailure(Component.translatable("error.exiledsettlement.merchant_order_invalid"));
            return 0;
        }
        var trade = TravelingMerchantService.entry(item);
        int price = TravelingMerchantService.orderPrice(trade, count);
        source.sendSuccess(() -> Component.translatable("message.exiledsettlement.merchant_order_added",
                item, count, price), false);
        return 1;
    }

    private static int merchantCancel(CommandSourceStack source, String value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SettlementRecord settlement = nearestSettlement(player, false);
        if (settlement == null || !settlement.isMember(player.getUUID())) return 0;
        try {
            if (TravelingMerchantService.cancelOrder(player.serverLevel(), settlement, UUID.fromString(value))) {
                source.sendSuccess(() -> Component.translatable("message.exiledsettlement.merchant_order_cancelled"), false);
                return 1;
            }
        } catch (IllegalArgumentException ignored) {
        }
        source.sendFailure(Component.translatable("error.exiledsettlement.merchant_order_not_found"));
        return 0;
    }
}

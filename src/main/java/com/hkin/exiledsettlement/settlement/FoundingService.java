package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Per-player persisted entitlement. Recovery reissues only the credential, never the supplies. */
public final class FoundingService {
    private FoundingService() { }
    static CompoundTag record(ServerPlayer player) {
        var persistent = player.getPersistentData();
        if (!persistent.contains(Player.PERSISTED_NBT_TAG)) persistent.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return persistent.getCompound(Player.PERSISTED_NBT_TAG);
    }
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        // First server player tick: no game-mode, advancement or five-second gate.
        // The persisted entitlement still prevents repeat grants after reconnects or respawns.
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        issueCamp(player);
    }
    public static boolean issueCamp(ServerPlayer player) {
        if(record(player).getBoolean("ExiledFoundingIssued"))return false;
        record(player).putBoolean("ExiledFoundingIssued",true);
        record(player).putBoolean("ExiledFoundingCampKit",true);
        give(player,new ItemStack(ModItems.STARTER_CAMP_BLUEPRINT.get()));
        player.displayClientMessage(Component.literal("获得开局营地蓝图：右键地面选址，确认后展开营地、聚落核心和有限初始库存。每名玩家仅有一次开拓机会。"),false);
        return true;
    }
    public static OperationResult<Void> beginCamp(ServerPlayer player) {
        if(!record(player).getBoolean("ExiledFoundingIssued") || !record(player).getBoolean("ExiledFoundingCampKit")
                || record(player).getBoolean("ExiledFoundingRedeemed")
                || !player.getInventory().contains(new ItemStack(ModItems.STARTER_CAMP_BLUEPRINT.get())))
            return OperationResult.failure("FOUNDING_CAMP_UNAVAILABLE");
        com.hkin.exiledsettlement.blueprint.PlacementSessionService.startFoundingCamp(player);
        return OperationResult.success(null);
    }
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }
    public static boolean recover(ServerPlayer player) {
        if (!record(player).getBoolean("ExiledFoundingIssued") || record(player).getBoolean("ExiledFoundingRedeemed")) return false;
        if(record(player).getBoolean("ExiledFoundingCampKit")) {
            if(!player.getInventory().contains(new ItemStack(ModItems.STARTER_CAMP_BLUEPRINT.get())))give(player,new ItemStack(ModItems.STARTER_CAMP_BLUEPRINT.get()));
            return true;
        }
        if (!player.getInventory().contains(new ItemStack(ModItems.FOUNDING_SUPPLIES.get())))
            give(player, new ItemStack(ModItems.FOUNDING_SUPPLIES.get()));
        return true;
    }
    public static List<ItemStack> supplies() {
        return List.of(new ItemStack(Items.BREAD, 24), new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.CHARCOAL, 24),
                new ItemStack(Items.WHEAT_SEEDS, 32), new ItemStack(Items.OAK_SAPLING, 8),
                new ItemStack(Items.STICK, 32), new ItemStack(Items.IRON_INGOT, 12),
                new ItemStack(Items.STONE_AXE), new ItemStack(Items.STONE_AXE),
                new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_HOE),
                new ItemStack(Items.FISHING_ROD), new ItemStack(Items.STONE_SWORD));
    }
    public static boolean redeem(ServerPlayer player, BlockPos position) {
        var entitlement = record(player);
        if (!entitlement.getBoolean("ExiledFoundingIssued") || entitlement.getBoolean("ExiledFoundingRedeemed")
                || entitlement.getBoolean("ExiledFoundingCampKit")) return false;
        var level = player.serverLevel(); var data = SettlementSavedData.get(level);
        var settlement = data.findContaining(position).orElse(null);
        if (settlement == null || !settlement.active() || !settlement.isOwner(player.getUUID())
                || position.distSqr(player.blockPosition()) > 36 || settlement.buildingAt(position) != null
                || !level.getBlockState(position).is(net.minecraft.world.level.block.Blocks.BARREL)
                || !(level.getBlockEntity(position) instanceof Container container) || !container.isEmpty()
                || container.getContainerSize() < supplies().size()) return false;
        // No world blocks are changed. The player's own empty barrel is registered explicitly.
        var building = settlement.addBuilding(BuildingType.WAGON, position, Direction.NORTH, position, position, 0);
        building.updateFunction(true, BuildingStatus.OPERATIONAL, container.getContainerSize(), List.of(position));
        int slot = 0; for (var stack : supplies()) container.setItem(slot++, stack.copy());
        container.setChanged(); entitlement.putBoolean("ExiledFoundingRedeemed", true);
        entitlement.putUUID("ExiledFoundingSettlement", settlement.id()); data.touch();
        settlement.community().event(CommunityLifeService.day(level), "有限开拓物资进入临时公共库存；优先安排住房与食物生产。");
        CommunityLifeService.milestone(settlement.community(), CommunityLifeService.day(level), "founding", "完成聚落开拓");
        player.displayClientMessage(Component.literal("开拓物资已入库，居民可以真实取用。它只发放一次；优先建设住房、安排食物生产，并保留过冬燃料。"), false);
        return true;
    }
}

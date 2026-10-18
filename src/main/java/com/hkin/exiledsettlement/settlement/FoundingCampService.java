package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.ArrayList;
import java.util.List;

/** The one-time kit is committed only after every block and protection hook accepts the camp. */
public final class FoundingCampService {
    private FoundingCampService() { }
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public static OperationResult<SettlementRecord> deploy(ServerPlayer player, BlockPos anchor, Direction facing) {
        var entitlement = FoundingService.record(player);
        ItemStack token = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.STARTER_CAMP_BLUEPRINT.get())) { token = stack; break; }
        }
        if (!entitlement.getBoolean("ExiledFoundingIssued") || !entitlement.getBoolean("ExiledFoundingCampKit")
                || entitlement.getBoolean("ExiledFoundingRedeemed") || token.isEmpty())
            return OperationResult.failure("FOUNDING_CAMP_UNAVAILABLE");
        if (!facing.getAxis().isHorizontal() || anchor.distSqr(player.blockPosition()) > 96 * 96)
            return OperationResult.failure("FOUNDING_CAMP_SITE");
        var level = player.serverLevel();
        var data = SettlementSavedData.get(level);
        var template = BlueprintTemplates.create(BuildingType.STARTER_CAMP, anchor, facing);
        var supplies=FoundingCampSupplies.stacks();
        if(supplies.stream().mapToInt(ItemStack::getCount).sum()>WarehouseStock.CAPACITY)
            return OperationResult.failure("FOUNDING_CAMP_SITE");
        var core = template.placements().stream().filter(p -> p.state().is(ModBlocks.SETTLEMENT_CORE.get()))
                .findFirst().orElseThrow().position();
        var stores = template.placements().stream().filter(p -> p.state().is(Blocks.BARREL))
                .map(BlueprintPlacement::position).toList();
        var store = stores.getFirst();
        var chunk = new ChunkPos(core);
        int radius = SettlementRecord.DEFAULT_BOUNDARY_RADIUS_CHUNKS;
        // Do not reactivate an old settlement or overlap any current claimed land.
        if (data.findByCore(core).isPresent()) return OperationResult.failure("OVERLAPS_OTHER_SETTLEMENT");
        for (var existing : data.settlements()) if (existing.active()) {
            for (int x = chunk.x-radius; x <= chunk.x+radius; x++)
                for (int z = chunk.z-radius; z <= chunk.z+radius; z++)
                    if (existing.ownsChunk(x,z)) return OperationResult.failure("OVERLAPS_OTHER_SETTLEMENT");
        }
        var plan = FoundingCampPlacementRules.plan(level, template);
        if (!plan.valid()) return OperationResult.failure(plan.reason());
        for (var placement : plan.placements()) {
            var pos = placement.position();
            if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, Direction.UP, token))
                return OperationResult.failure("NO_PERMISSION");
            for (var box : placement.state().getCollisionShape(level,pos).toAabbs()) {
                if (!level.getEntities((net.minecraft.world.entity.Entity)null, box.move(pos), entity -> entity instanceof net.minecraft.world.entity.LivingEntity).isEmpty())
                    return OperationResult.failure("FOUNDING_CAMP_OCCUPIED");
            }
        }
        List<BlockSnapshot> snapshots = new ArrayList<>();
        try {
            for (var placement : plan.placements()) {
                var pos = placement.position();
                if (level.getBlockState(pos).equals(placement.state())) continue;
                if (!level.getBlockState(pos).isAir()) {
                    var breaking = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level,pos,level.getBlockState(pos),player);
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(breaking);
                    if (breaking.isCanceled()) { rollback(snapshots); return OperationResult.failure("NO_PERMISSION"); }
                }
                var snapshot = BlockSnapshot.create(level.dimension(), level, pos, FLAGS);
                snapshots.add(snapshot);
                if (!level.setBlock(pos, placement.state(), FLAGS)
                        || EventHooks.onBlockPlace(player, snapshot, Direction.UP)) {
                    rollback(snapshots);
                    return OperationResult.failure("NO_PERMISSION");
                }
            }
            if (stores.stream().anyMatch(p -> !(level.getBlockEntity(p) instanceof Container))) {
                rollback(snapshots);
                return OperationResult.failure("FOUNDING_CAMP_SITE");
            }
        } catch (RuntimeException failure) {
            rollback(snapshots);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("Founding camp placement rolled back", failure);
            return OperationResult.failure("FOUNDING_CAMP_SITE");
        }
        var created = data.createOrRecover(core, player);
        if (!created.success()) { rollback(snapshots); return created; }
        var settlement = created.value();
        var camp = settlement.addBuilding(BuildingType.STARTER_CAMP, anchor, facing,
                template.minCorner(), template.maxCorner(), template.transitionMargin(), BlueprintTemplates.currentTemplateVersion(BuildingType.STARTER_CAMP));
        var storageMin = new BlockPos(stores.stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                stores.stream().mapToInt(BlockPos::getY).min().orElseThrow(),stores.stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        var storageMax = new BlockPos(stores.stream().mapToInt(BlockPos::getX).max().orElseThrow(),
                stores.stream().mapToInt(BlockPos::getY).max().orElseThrow(),stores.stream().mapToInt(BlockPos::getZ).max().orElseThrow());
        var warehouse = settlement.addBuilding(BuildingType.WAGON, store, facing, storageMin, storageMax, 0,4);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.CAPACITY, stores);
        var inventory = WarehouseInventoryService.stock(level,warehouse);
        for (var stack : supplies) inventory.insert(stack);
        entitlement.putBoolean("ExiledFoundingRedeemed", true);
        entitlement.putUUID("ExiledFoundingSettlement", settlement.id());
        token.shrink(1);
        data.touch();
        BuildingFunctionService.validateSingle(level, data, settlement, camp);
        var spawns = template.placements().stream().map(BlueprintPlacement::position).distinct()
                .filter(p -> p.getY()==anchor.getY() && level.getBlockState(p).isAir()
                        && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP))
                .sorted(java.util.Comparator.comparingDouble(p -> p.distSqr(anchor))).limit(6).toList();
        int spawnIndex=0;
        for (var citizen:settlement.citizens()) if (spawnIndex<spawns.size()) citizen.updateProjection(null,spawns.get(spawnIndex++));
        CitizenProjectionService.ensureSettlementCitizens(level, settlement);
        CommunityLifeService.milestone(settlement.community(), CommunityLifeService.day(level), "founding", "完成聚落开拓");
        player.displayClientMessage(Component.translatable("message.exiledsettlement.founding_camp_deployed"), false);
        return created;
    }

    private static void rollback(List<BlockSnapshot> snapshots) {
        for (int i = snapshots.size()-1; i >= 0; i--) snapshots.get(i).restore(FLAGS);
    }
}

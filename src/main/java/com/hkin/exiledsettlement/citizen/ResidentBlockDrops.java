package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Only newly emitted loot during one synchronous resident block operation is redirected. */
public final class ResidentBlockDrops {
    private ResidentBlockDrops() {}
    private record Capture(ServerLevel level, ArrayList<ItemEntity> drops) {}
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    static { NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, ResidentBlockDrops::captureSpawn); }

    private static void captureSpawn(EntityJoinLevelEvent event) {
        var capture = ACTIVE.get();
        if (capture != null && event.getLevel() == capture.level() && !event.isCanceled()
                && event.getEntity() instanceof ItemEntity item) {
            capture.drops().add(item);
            event.setCanceled(true);
        }
    }

    public static <T> T run(ServerLevel level, Entity worker, Supplier<T> action, Predicate<T> committed) {
        if (!(worker instanceof SettlementCitizenEntity)) return action.get();
        var data = SettlementSavedData.get(level);
        var settlement = data.findById(CitizenProjectionService.settlementId(worker)).orElse(null);
        var citizen = settlement == null ? null : settlement.citizen(CitizenProjectionService.citizenId(worker));
        if (citizen == null || ACTIVE.get() != null) return action.get();
        var capture = new Capture(level, new ArrayList<>());
        ACTIVE.set(capture);
        T result;
        try { result = action.get(); }
        finally { ACTIVE.remove(); }
        // Failed staged operations restore their snapshots; do not duplicate restored container contents.
        if (committed.test(result)) {
            for (var item : capture.drops()) {
                var remainder = citizen.insertIntoInventory(item.getItem());
                if (!remainder.isEmpty()) {
                    item.setItem(remainder);
                    level.addFreshEntity(item);
                }
            }
            if (!capture.drops().isEmpty()) data.touch();
        }
        return result;
    }

    public static boolean destroy(ServerLevel level, BlockPos at, Entity worker) {
        return run(level, worker, () -> {
            if (worker instanceof LivingEntity living && !CommonHooks.canEntityDestroy(level, at, living)) return false;
            var state = level.getBlockState(at);
            var tool = worker instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
            if(worker instanceof LivingEntity living) {
                var tree=ResidentTreeFelling.clear(level,at,living,tool,p->true,state.getFluidState().createLegacyBlock());
                if(tree!=null) {
                    if(!tree.failure().isEmpty())return false;
                    tree.drops().forEach(drop->Block.popResource(level,at,drop));return true;
                }
            }
            var drops = Block.getDrops(state, level, at, level.getBlockEntity(at), worker, tool);
            if (!level.destroyBlock(at, false, worker)) return false;
            for (var drop : drops) Block.popResource(level, at, drop);
            state.spawnAfterBreak(level, at, tool, true);
            return true;
        }, Boolean.TRUE::equals);
    }
}

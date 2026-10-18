package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.*;

/** Manual selections have no area quota; terrain validation, writes and rollback are sliced. */
public final class ManualRoadPlacementService {
    private static final Map<ServerLevel, Deque<Job>> JOBS = new WeakHashMap<>();
    private static final int CELLS_PER_TICK = 256;
    private ManualRoadPlacementService() { }

    public static OperationResult<Integer> enqueue(ServerLevel level, SettlementRecord s, ServerPlayer player,
                                                   BlockPos first, BlockPos second, RoadTier tier) {
        if (!s.active() || !s.isMember(player.getUUID())) return OperationResult.failure("NO_PERMISSION");
        if (!s.contains(first) || !s.contains(second)) return OperationResult.failure("ROAD_INVALID_TERRAIN");
        var job = new Job(s.id(), player.getUUID(), first, second, tier);
        JOBS.computeIfAbsent(level, ignored -> new ArrayDeque<>()).addLast(job);
        player.displayClientMessage(Component.literal("道路已排队：" + job.total + "格，逐批检查地形并铺设。"), false);
        return OperationResult.success(0);
    }

    public static void tickLevel(ServerLevel level) {
        var jobs = JOBS.get(level);
        if (jobs == null || jobs.isEmpty()) return;
        var job = jobs.getFirst();
        long deadline = System.nanoTime() + 2_000_000L;
        for (int i = 0; i < CELLS_PER_TICK && !job.finished
                && (i == 0 || System.nanoTime() < deadline); i++) job.step(level);
        if (job.finished) jobs.removeFirst();
        if (jobs.isEmpty()) JOBS.remove(level);
    }

    public static void abortLevel(ServerLevel level) {
        var jobs = JOBS.remove(level); if (jobs == null) return;
        for (var job : jobs) {
            job.fail("世界关闭，未完成的手动道路已撤销");
            while (!job.finished) job.rollback(level);
        }
    }

    private static final class Job {
        final UUID settlement, player;
        final int minX, minZ, expectedY;
        final long depth, total;
        final RoadTier tier;
        final List<BlockPos> surfaces = new ArrayList<>();
        final List<BlockSnapshot> changes = new ArrayList<>();
        long validated;
        int written, rollbackIndex;
        String failure;
        boolean finished;

        Job(UUID settlement, UUID player, BlockPos first, BlockPos second, RoadTier tier) {
            this.settlement = settlement; this.player = player; this.tier = tier;
            minX = Math.min(first.getX(), second.getX()); minZ = Math.min(first.getZ(), second.getZ());
            depth = Math.abs((long) first.getZ() - second.getZ()) + 1L;
            total = (Math.abs((long) first.getX() - second.getX()) + 1L) * depth;
            expectedY = Math.round((first.getY() + second.getY()) / 2.0F);
        }

        void fail(String message) { if (failure == null) { failure = message; rollbackIndex = changes.size() - 1; } }

        void step(ServerLevel level) {
            if (failure != null) { rollback(level); return; }
            var s = SettlementSavedData.get(level).findById(settlement).orElse(null);
            var p = level.getServer().getPlayerList().getPlayer(player);
            if (s == null || !s.active() || p == null || p.serverLevel() != level || !s.isMember(player)) {
                fail("权限、聚落或玩家状态已改变，道路已撤销"); return;
            }
            if (validated < total) {
                int x = (int) (minX + validated / depth), z = (int) (minZ + validated % depth);
                var at = RoadService.findSurface(level, x, z, expectedY);
                if (at == null || !s.contains(at) || !s.contains(at.above())) {
                    fail("道路地形不通或区块未就绪，整次手动选择已取消"); return;
                }
                surfaces.add(at.immutable()); validated++; return;
            }
            if (written < surfaces.size()) {
                var at = surfaces.get(written++);
                if (!at.equals(RoadService.findSurface(level, at.getX(), at.getZ(), expectedY))) {
                    fail("道路检查后地形发生变化，整次手动选择已取消"); return;
                }
                var roads = RoadSpaceData.get(level);
                if (!RoadService.shouldConvert(level.getBlockState(at), tier, roads.levelAt(at))) return;
                var snapshot = BlockSnapshot.create(level.dimension(), level, at, Block.UPDATE_CLIENTS);
                if (!level.setBlock(at, tier.surfaceBlock().defaultBlockState(), Block.UPDATE_ALL)
                        || EventHooks.onBlockPlace(p, snapshot, Direction.UP)) {
                    snapshot.restore(Block.UPDATE_ALL); fail("领地保护拒绝道路，正在撤销本次铺设"); return;
                }
                changes.add(snapshot); return;
            }
            int added = RoadSpaceData.get(level).addAll(surfaces, tier);
            RoadSpaceData.get(level).pinManual(surfaces);
            p.displayClientMessage(Component.translatable("message.exiledsettlement.road.completed", added), false);
            finished = true;
        }

        void rollback(ServerLevel level) {
            if (rollbackIndex >= 0) {
                var snapshot = changes.get(rollbackIndex--);
                // Preserve an intervening player edit rather than overwriting it during rollback.
                if (level.getBlockState(snapshot.getPos()).equals(tier.surfaceBlock().defaultBlockState()))
                    snapshot.restore(Block.UPDATE_ALL);
                return;
            }
            var p = level.getServer().getPlayerList().getPlayer(player);
            if (p != null) p.displayClientMessage(Component.literal(failure), false);
            finished = true;
        }
    }
}

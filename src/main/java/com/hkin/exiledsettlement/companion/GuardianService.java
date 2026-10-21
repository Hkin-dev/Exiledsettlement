package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Loaded-only assembly, physical supply and repair. The order is not a building blueprint. */
public final class GuardianService {
    private GuardianService() {}
    public static final String GUARDIAN_ID = "exiledsettlement.guardianId";
    public static final String SETTLEMENT_ID = "exiledsettlement.guardianSettlement";
    public static final int ASSEMBLY_TICKS = 1200;
    public static final int REPAIR_TICKS = 100;
    public static final float REPAIR_HEALTH = 25;
    private static final Map<CitizenRecord, Long> RETRY = new WeakHashMap<>();
    private static final Map<CitizenRecord, Long> PATH_AT = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LEVEL_AT = new WeakHashMap<>();

    public static boolean work(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                               CitizenRecord citizen, SettlementCitizenEntity worker) {
        if (!CompanionMetrics.ENABLED) return workMeasured(level, data, s, citizen, worker);
        long started = CompanionMetrics.start();
        try { return workMeasured(level, data, s, citizen, worker); }
        finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.GUARDIAN_WORK, started); }
    }

    private static boolean workMeasured(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                                        CitizenRecord citizen, SettlementCitizenEntity worker) {
        GuardianState state = s.guardians();
        GuardianState.Entry order = state.records().stream()
                .filter(e -> e.assembling() && citizen.id().equals(e.worker)).findFirst().orElse(null);
        boolean allowed = s.active() && citizen.canWork()
                && citizen.profession() == CitizenProfession.TOOLSMITH
                && citizen.workState() == CitizenWorkState.IDLE && citizen.life().task.isEmpty()
                && CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.WORK
                && !s.community().evacuation && !s.community().danger(worker.blockPosition())
                && !worker.isDefending() && !worker.isSleeping() && !worker.hasActiveConstructionMovement()
                && !CitizenInteractionService.held(worker) && worker.isAlive() && worker.onGround();
        if (!allowed) { if (order != null) order.lastWorkTick = -1; return false; }
        BuildingRecord workshop = s.building(citizen.workBuildingId());
        if (workshop == null || workshop.type() != BuildingType.TOOL_WORKSHOP || !workshop.active()
                || !workshop.operational() || workshop.demolitionQueued()) {
            if (order != null) { order.lastWorkTick = -1; order.blocker = "工匠坊不可用"; }
            return false;
        }
        long now = level.getGameTime();
        if (now < RETRY.getOrDefault(citizen, Long.MIN_VALUE)) return false;
        if (CitizenEquipmentService.preferredEquipment(citizen) != null
                && CitizenEquipmentService.nextEquipment(level, s, citizen) != null) {
            if (order != null) order.lastWorkTick = -1;
            return false; // Existing equipment pickup remains ahead of optional defense work.
        }
        if (!state.enabled || order != null && !withinTarget(state, order)) {
            if (order != null) {
                order.lastWorkTick = -1;
                order.blocker = !state.enabled ? "自动制造关闭，保留工单及材料" : "目标数量已降低，保留工单及材料";
            }
            // The policy controls new construction, not care for already paid guardians.
            return repair(level, data, s, citizen, worker);
        }
        if (order == null) {
            if (repair(level, data, s, citizen, worker)) return true;
            order = state.records().stream().filter(e -> e.assembling()
                    && (e.worker == null || s.citizen(e.worker) == null || !s.citizen(e.worker).canWork()
                        || s.citizen(e.worker).profession() != CitizenProfession.TOOLSMITH)).findFirst().orElse(null);
            if (order != null) { order.worker = citizen.id(); order.lastWorkTick = -1; data.touch(); }
        }
        if (order != null && !withinTarget(state, order)) {
            order.lastWorkTick = -1;
            order.blocker = "目标数量已降低，保留工单及材料";
            return repair(level, data, s, citizen, worker);
        }
        if (order == null) {
            if (state.count() >= state.target || now < state.nextOrderCheck) return false;
            state.nextOrderCheck = now + 100;
            if (essentialToolsPending(level, s)) { state.blocker = "优先补充居民必要工具"; return false; }
            if (!reserveAllows(level, s, null, 36, citizen)) { state.blocker = "铁料不足或低于储备线"; return false; }
            if (count(citizen, Items.IRON_BLOCK) + SettlementInventoryService.countItem(level, s, Items.IRON_BLOCK) < 4) {
                state.blocker = "缺少4铁块；先按真实配方压制铁块"; return false;
            }
            if (count(citizen, Items.CARVED_PUMPKIN) + SettlementInventoryService.countItem(level, s, Items.CARVED_PUMPKIN) < 1) {
                state.blocker = "缺少雕刻南瓜"; return false;
            }
            BlockPos site = findSite(level, s, worker);
            if (site == null) { state.blocker = "没有可达且安全的装配空地"; return false; }
            order = state.createOrder(citizen.id(), site);
            if (order == null) return false;
            state.blocker = ""; data.touch();
        }
        if (now < order.retryAt) return false;
        if (!safeFromThreat(level, worker.blockPosition(), 10)) {
            order.lastWorkTick = -1; order.blocker = "装配暂缓：附近有危险";
            RETRY.put(citizen, now + 80); return false;
        }
        if (count(citizen, Items.IRON_BLOCK) < 4) {
            order.lastWorkTick = -1;
            if (!reserveAllows(level, s, order, 36)) return waitFor(level, citizen, worker, order, "铁料储备不足", false);
            return fetch(level, data, s, citizen, worker, Items.IRON_BLOCK, 4 - count(citizen, Items.IRON_BLOCK), order);
        }
        if (count(citizen, Items.CARVED_PUMPKIN) < 1) {
            order.lastWorkTick = -1;
            return fetch(level, data, s, citizen, worker, Items.CARVED_PUMPKIN, 1, order);
        }
        // Recheck the real space without moving any entity or changing world blocks.
        IronGolem probe = EntityType.IRON_GOLEM.create(level);
        if (probe == null || !safeSite(level, s, probe, order.anchor, false)) {
            order.lastWorkTick = -1;
            if (now >= order.nextPathTick) {
                BlockPos replacement = findSite(level, s, worker);
                order.nextPathTick = now + 100;
                if (replacement != null) { order.anchor = replacement; data.touch(); }
                else return waitFor(level, citizen, worker, order, "装配位置受阻", false);
            }
            return waitFor(level, citizen, worker, order, "重新寻找装配空地", false);
        }
        probe.moveTo(order.anchor.getX() + .5, order.anchor.getY(), order.anchor.getZ() + .5, 0, 0);
        if (!close(worker, order.anchor, 3.5) || !assemblyVisible(level, worker, probe)) {
            order.lastWorkTick = -1; order.blocker = "前往装配位置";
            activity(citizen, worker, CitizenActivity.TOOLMAKING, order.blocker);
            navigate(level, worker, citizen, order.anchor, 0.7); return true;
        }
        worker.stopSettlementNavigation();
        activity(citizen, worker, CitizenActivity.TOOLMAKING, "正在组装铁傀儡");
        order.blocker = "装配 " + (int) order.progress + "/" + ASSEMBLY_TICKS;
        long elapsed = order.lastWorkTick < 0 ? 0 : now - order.lastWorkTick;
        order.lastWorkTick = now;
        if (elapsed > 0 && elapsed <= CitizenWorkService.WORK_INTERVAL_TICKS)
            order.progress = Math.min(ASSEMBLY_TICKS, order.progress + elapsed * CitizenToolSpeed.multiplier(citizen));
        if (now % 16 < 8) worker.swing(InteractionHand.MAIN_HAND);
        data.touch();
        if (order.progress >= ASSEMBLY_TICKS) finishAssembly(level, data, s, citizen, worker, order, probe);
        return true;
    }

    private static boolean essentialToolsPending(ServerLevel level, SettlementRecord s) {
        // Existing recipe selection can actually remedy the shortage; no stock-cap test is used.
        boolean lacksTools = s.citizens().stream().filter(CitizenRecord::canWork)
                .anyMatch(c -> CitizenEquipmentService.preferredEquipment(c) != null);
        return lacksTools && SettlementInventoryService.countCategory(level, s, WarehouseCategory.TOOLS) == 0
                && s.citizens().stream().filter(c -> c.profession() == CitizenProfession.TOOLSMITH && c.canWork())
                .anyMatch(c -> ArtisanRecipes.select(level, s, c) != null);
    }
    private static boolean withinTarget(GuardianState state, GuardianState.Entry order) {
        int occupied = (int) state.records().stream().filter(e -> e.counts() && !e.assembling()).count();
        for (var e : state.records()) {
            if (!e.assembling()) continue;
            occupied++;
            if (e == order) return occupied <= Math.max(0, state.target);
        }
        return false;
    }
    private static void finishAssembly(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                                       CitizenRecord c, SettlementCitizenEntity worker, GuardianState.Entry e, IronGolem golem) {
        if (!reserveAllows(level, s, e, 36)) { waitFor(level, c, worker, e, "铁料低于储备线，保留已领材料", false); return; }
        if (!close(worker, e.anchor, 3.5) || !assemblyVisible(level, worker, golem)) {
            waitFor(level, c, worker, e, "装配位置被遮挡，等待实际到场", false); return;
        }
        if (!safeSite(level, s, golem, e.anchor, true) || !safeFromThreat(level, e.anchor, 10)) {
            waitFor(level, c, worker, e, "装配空地被占用或有危险", false); return;
        }
        Entity existing = level.getEntity(e.entityId);
        if (existing != null) {
            if (existing instanceof IronGolem found && owned(s, e, found)) { e.state = "alive"; data.touch(); return; }
            e.state = "uncertain"; e.blocker = "实体编号冲突，等待核对"; data.touch(); return;
        }
        if (count(c, Items.IRON_BLOCK) < 4 || count(c, Items.CARVED_PUMPKIN) < 1) return;
        List<ItemStack> paid = new ArrayList<>();
        if (!take(c, Items.IRON_BLOCK, 4, paid) || !take(c, Items.CARVED_PUMPKIN, 1, paid)) {
            paid.forEach(c::insertIntoInventory); return;
        }
        golem.setUUID(e.entityId);
        golem.moveTo(e.anchor.getX() + .5, e.anchor.getY(), e.anchor.getZ() + .5, worker.getYRot(), 0);
        golem.setPersistenceRequired(); golem.setPlayerCreated(true);
        golem.getPersistentData().putUUID(GUARDIAN_ID, e.id);
        golem.getPersistentData().putUUID(SETTLEMENT_ID, s.id());
        e.state = "spawning"; e.charged = true; data.touch();
        boolean admitted = false;
        try { admitted = level.addFreshEntity(golem); }
        catch (RuntimeException failure) {
            // An event can throw after admission; never refund while the paid entity exists.
            admitted = level.getEntity(e.entityId) == golem;
            e.blocker = "实体加入事件异常：" + failure.getClass().getSimpleName();
        }
        if (!admitted) {
            GuardianAi.release(golem);
            paid.forEach(c::insertIntoInventory);
            e.state = "assembling"; e.charged = false; e.lastWorkTick = -1; e.retryAt = level.getGameTime() + 200;
            e.blocker = "生成被拒，材料已退还"; data.touch(); return;
        }
        e.state = "alive"; e.blocker = ""; e.lastPosition = golem.blockPosition(); e.health = golem.getHealth();
        e.lastSeenTick = level.getGameTime(); e.lastWorkTick = -1; s.guardians().blocker = "";
        int tool = CitizenEquipmentService.toolSlotForWork(c);
        if (tool >= 0) c.damageInventoryItem(tool, 1);
        GuardianAi.attach(level, s, e, golem);
        activity(c, worker, CitizenActivity.IDLE, "铁傀儡装配完成");
        s.community().event(CommunityLifeService.day(level), c.name() + "组装了铁傀儡，开始守护聚落。");
        data.touch();
    }

    private static boolean repair(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                                  CitizenRecord c, SettlementCitizenEntity worker) {
        long now = level.getGameTime();
        for (GuardianState.Entry entry : s.guardians().records()) {
            if (!entry.state.equals("alive")) continue;
            Entity found = level.getEntity(entry.entityId);
            if (!(found instanceof IronGolem golem) || !golem.isAlive() || !owned(s, entry, golem)
                    || golem.getHealth() >= golem.getMaxHealth() || golem.getTarget() != null) continue;
            if (entry.repairWorker != null && !entry.repairWorker.equals(c.id()) && now - entry.lastRepairTick <= 40) continue;
            if (!safeFromThreat(level, golem.blockPosition(), 10)) continue;
            entry.repairWorker = c.id();
            if (count(c, Items.IRON_INGOT) < 1) {
                entry.repairProgress = 0; entry.lastRepairTick = -1;
                if (!reserveAllows(level, s, null, 1)) { entry.blocker = "待修：铁料低于储备线"; return false; }
                return fetch(level, data, s, c, worker, Items.IRON_INGOT, 1, entry);
            }
            if (!close(worker, golem.blockPosition(), 3.5) || !worker.hasLineOfSight(golem)) {
                entry.lastRepairTick = -1; entry.repairProgress = 0;
                activity(c, worker, CitizenActivity.REPAIRING, "前往维修铁傀儡");
                navigate(level, worker, c, golem.blockPosition(), .7); return true;
            }
            if (!reserveAllows(level, s, null, 1, c)) { entry.blocker = "待修：铁料低于储备线"; return false; }
            GuardianAi.holdForRepair(golem, now + 20);
            worker.stopSettlementNavigation();
            activity(c, worker, CitizenActivity.REPAIRING, "近身维修铁傀儡");
            long elapsed = entry.lastRepairTick < 0 ? 0 : now - entry.lastRepairTick;
            entry.lastRepairTick = now;
            if (golem.getDeltaMovement().horizontalDistanceSqr() > .005) { entry.repairProgress = 0; return true; }
            if (elapsed > 0 && elapsed <= CitizenWorkService.WORK_INTERVAL_TICKS)
                entry.repairProgress += elapsed * CitizenToolSpeed.multiplier(c);
            if (entry.repairProgress < REPAIR_TICKS) return true;
            ItemStack payment = c.extractInventoryItem(st -> st.is(Items.IRON_INGOT), 1);
            if (payment.isEmpty()) return true;
            float before = golem.getHealth();
            golem.heal(Math.min(REPAIR_HEALTH, golem.getMaxHealth() - before));
            if (golem.getHealth() <= before) c.insertIntoInventory(payment);
            else {
                int tool = CitizenEquipmentService.toolSlotForWork(c);
                if (tool >= 0) c.damageInventoryItem(tool, 1);
                worker.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, golem.blockPosition(), net.minecraft.sounds.SoundEvents.IRON_GOLEM_REPAIR,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 1, 1);
            }
            entry.health = golem.getHealth(); entry.repairProgress = 0; entry.lastRepairTick = -1;
            entry.repairWorker = null; entry.blocker = ""; data.touch();
            return true;
        }
        return false;
    }

    private static boolean fetch(ServerLevel level, SettlementSavedData data, SettlementRecord s, CitizenRecord c,
                                 SettlementCitizenEntity worker, Item item, int amount, GuardianState.Entry e) {
        BuildingRecord warehouse = SettlementInventoryService.warehouses(s).stream()
                .filter(b -> WarehouseInventoryService.countItem(level, b, item) > 0)
                .min(Comparator.comparingDouble(b -> b.position().distSqr(worker.blockPosition()))).orElse(null);
        if (warehouse == null) return waitFor(level, c, worker, e, "缺料：" + item.getDescription().getString(), false);
        activity(c, worker, CitizenActivity.FETCHING_TOOLMAKING_INPUT, "领取铁傀儡材料");
        if (!WarehouseInventoryService.canInteract(level, warehouse, worker)) {
            BlockPos station = WarehouseInventoryService.reserveExtractionPosition(level, warehouse, worker, item);
            if (station == null) return waitFor(level, c, worker, e, "仓库入口不可达", false);
            navigate(level, worker, c, station, .7); return true;
        }
        if (item == Items.IRON_BLOCK && !reserveAllows(level, s, e, 36)
                || item == Items.IRON_INGOT && !reserveAllows(level, s, null, 1, c))
            return waitFor(level, c, worker, e, "铁料储备不足，暂停领取", false);
        ItemStack extracted = WarehouseInventoryService.extractItemForResident(level, warehouse, worker, item,
                Math.min(amount, c.inventoryCapacityFor(new ItemStack(item))), c::inventoryCapacityFor);
        if (extracted.isEmpty()) return waitFor(level, c, worker, e, "背包已满或材料已被领走", false);
        ItemStack remainder = c.insertIntoInventory(extracted);
        if (!remainder.isEmpty()) WarehouseInventoryService.insertForResident(level, warehouse, worker, remainder);
        WarehouseInventoryService.releaseInteractionPosition(level, worker);
        data.touch(); return true;
    }
    private static boolean waitFor(ServerLevel level, CitizenRecord c, SettlementCitizenEntity worker,
                                   GuardianState.Entry e, String reason, boolean hold) {
        e.blocker = reason; e.lastWorkTick = -1;
        worker.stopSettlementNavigation(); WarehouseInventoryService.releaseInteractionPosition(level, worker);
        activity(c, worker, CitizenActivity.BLOCKED, reason);
        RETRY.put(c, level.getGameTime() + 80);
        return hold;
    }
    private static void navigate(ServerLevel level, SettlementCitizenEntity worker, CitizenRecord c, BlockPos at, double speed) {
        long now = level.getGameTime();
        if (now < PATH_AT.getOrDefault(c, Long.MIN_VALUE) && !worker.getNavigation().isDone()) return;
        PATH_AT.put(c, now + 40);
        String reason = CompanionMetrics.navigateResident(level, false, worker, at, speed);
        if (!reason.isEmpty()) c.setActivity(CitizenActivity.BLOCKED, "铁傀儡任务：" + reason);
    }
    private static void activity(CitizenRecord c, SettlementCitizenEntity e, CitizenActivity activity, String reason) {
        c.setActivity(activity, reason); e.setActivity(activity);
    }
    private static boolean close(SettlementCitizenEntity e, BlockPos at, double range) {
        return e.distanceToSqr(at.getX() + .5, at.getY(), at.getZ() + .5) <= range * range
                && Math.abs(e.getY() - at.getY()) <= 2;
    }
    private static boolean assemblyVisible(ServerLevel level, SettlementCitizenEntity worker, IronGolem positionedProbe) {
        // Check the physical assembly's body center, not an eye above a low wall.
        var hit = level.clip(new net.minecraft.world.level.ClipContext(worker.getEyePosition(),
                positionedProbe.getBoundingBox().getCenter(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }
    private static int count(CitizenRecord c, Item item) {
        return c.inventory().stream().filter(st -> st.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static boolean take(CitizenRecord c, Item item, int count, List<ItemStack> paid) {
        int left = count;
        while (left > 0) {
            ItemStack stack = c.extractInventoryItem(st -> st.is(item), left);
            if (stack.isEmpty()) return false;
            paid.add(stack); left -= stack.getCount();
        }
        return true;
    }
    public static boolean reserveAllows(ServerLevel level, SettlementRecord s, GuardianState.Entry own, int fee) {
        return reserveAllows(level, s, own, fee, own == null || own.worker == null ? null : s.citizen(own.worker));
    }
    private static boolean reserveAllows(ServerLevel level, SettlementRecord s, GuardianState.Entry own, int fee, CitizenRecord extra) {
        long iron = SettlementInventoryService.countItem(level, s, Items.IRON_INGOT)
                + 9L * SettlementInventoryService.countItem(level, s, Items.IRON_BLOCK);
        Set<UUID> counted = new HashSet<>();
        if (extra != null) { iron += count(extra, Items.IRON_INGOT) + 9L * count(extra, Items.IRON_BLOCK); counted.add(extra.id()); }
        int otherOrders = 0;
        for (var e : s.guardians().records()) {
            if (!e.assembling()) continue;
            if (e != own) otherOrders++;
            CitizenRecord c = e.worker == null ? null : s.citizen(e.worker);
            if (c != null && counted.add(c.id())) iron += count(c, Items.IRON_INGOT) + 9L * count(c, Items.IRON_BLOCK);
        }
        return iron - fee - otherOrders * 36L >= Math.max(0, s.guardians().ironReserve);
    }
    private static BlockPos findSite(ServerLevel level, SettlementRecord s, SettlementCitizenEntity worker) {
        IronGolem probe = EntityType.IRON_GOLEM.create(level);
        if (probe == null) return null;
        BlockPos base = worker.blockPosition();
        for (int radius = 4; radius <= 12; radius += 4) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos horizontal = base.relative(d, radius);
                for (int dy : new int[]{0, -1, 1, -2, 2}) {
                    BlockPos p = horizontal.offset(0, dy, 0);
                    if (!safeSite(level, s, probe, p, true) || !safeFromThreat(level, p, 10)) continue;
                    var path = CompanionMetrics.createPath(level, false, worker.getNavigation(), p, 1);
                    if (path != null && path.canReach()) return p;
                }
            }
        }
        return null;
    }
    public static boolean safeSite(ServerLevel level, SettlementRecord s, IronGolem golem, BlockPos p, boolean entities) {
        if (!level.hasChunkAt(p) || !level.hasChunkAt(p.offset(1, 3, 1)) || !level.hasChunkAt(p.offset(-1, -1, -1))
                || !s.contains(p) || s.community().danger(p) || !level.getWorldBorder().isWithinBounds(p)) return false;
        AABB box = new AABB(p.getX() - .2, p.getY(), p.getZ() - .2, p.getX() + 1.2, p.getY() + 2.7, p.getZ() + 1.2);
        if (!level.noCollision(golem, box) || level.containsAnyLiquid(box)) return false;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos under = p.offset(x, -1, z);
            if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)
                    || level.getBlockState(under).is(Blocks.MAGMA_BLOCK) || level.getBlockState(under).is(Blocks.CAMPFIRE)) return false;
        }
        return !entities || level.getEntities(golem, box).isEmpty();
    }
    private static boolean safeFromThreat(ServerLevel level, BlockPos at, int range) {
        return level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(range), mob -> mob.isAlive()
                && mob instanceof Enemy).isEmpty();
    }

    public static boolean owned(SettlementRecord s, GuardianState.Entry e, IronGolem golem) {
        var tag = golem.getPersistentData();
        return tag.hasUUID(SETTLEMENT_ID) && tag.hasUUID(GUARDIAN_ID)
                && s.id().equals(tag.getUUID(SETTLEMENT_ID)) && e.id.equals(tag.getUUID(GUARDIAN_ID))
                && e.entityId.equals(golem.getUUID());
    }
    public static void tickLevel(ServerLevel level) {
        if (!CompanionMetrics.ENABLED) { tickLevelMeasured(level); return; }
        long started = CompanionMetrics.start();
        try { tickLevelMeasured(level); }
        finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.GUARDIAN_LIFECYCLE, started); }
    }

    private static void tickLevelMeasured(ServerLevel level) {
        long now = level.getGameTime();
        if (now < LEVEL_AT.getOrDefault(level, Long.MIN_VALUE)) return;
        LEVEL_AT.put(level, now + 20);
        var data = SettlementSavedData.get(level);
        for (var s : data.settlements()) {
            boolean changed = false;
            for (var e : s.guardians().records()) {
                if (!e.counts()) continue;
                Entity entity = level.getEntity(e.entityId);
                if (entity instanceof IronGolem golem && owned(s, e, golem)) {
                    if (!golem.isAlive()) { e.state = "dead"; e.health = 0; GuardianAi.release(golem); changed = true; continue; }
                    if (!e.state.equals("alive")) { e.state = "alive"; e.charged = true; changed = true; }
                    if (!e.lastPosition.equals(golem.blockPosition()) || e.health != golem.getHealth()) changed = true;
                    e.lastPosition = golem.blockPosition(); e.health = golem.getHealth(); e.lastSeenTick = now;
                    GuardianAi.attach(level, s, e, golem);
                }
                if (e.assembling() && e.lastWorkTick >= 0 && now - e.lastWorkTick > CitizenWorkService.WORK_INTERVAL_TICKS)
                    e.lastWorkTick = -1;
            }
            if (changed) data.touch();
        }
    }
    public static void onJoin(Entity entity) {
        if (!(entity instanceof IronGolem golem) || !(entity.level() instanceof ServerLevel level)) return;
        var tag = golem.getPersistentData();
        if (!tag.hasUUID(SETTLEMENT_ID) || !tag.hasUUID(GUARDIAN_ID)) return;
        var s = SettlementSavedData.get(level).findById(tag.getUUID(SETTLEMENT_ID)).orElse(null);
        var e = s == null ? null : s.guardians().entry(tag.getUUID(GUARDIAN_ID));
        if (e == null || !owned(s, e, golem)) return;
        if (!e.counts()) { GuardianAi.release(golem); return; }
        // The creation callback is not a commit: addFreshEntity may still be cancelled by another listener.
        if (!e.state.equals("spawning")) { e.state = "alive"; e.charged = true; }
        e.lastPosition = golem.blockPosition(); e.health = golem.getHealth(); e.lastSeenTick = level.getGameTime();
        GuardianAi.attach(level, s, e, golem); SettlementSavedData.get(level).touch();
    }
    public static void onDeath(LivingEntity entity) {
        if (!(entity instanceof IronGolem golem) || !(entity.level() instanceof ServerLevel level)) return;
        var tag = entity.getPersistentData();
        if (!tag.hasUUID(SETTLEMENT_ID) || !tag.hasUUID(GUARDIAN_ID)) return;
        var data = SettlementSavedData.get(level);
        var s = data.findById(tag.getUUID(SETTLEMENT_ID)).orElse(null);
        var e = s == null ? null : s.guardians().entry(tag.getUUID(GUARDIAN_ID));
        if (e != null && owned(s, e, golem)) { e.state = "dead"; e.health = 0; e.blocker = "已经死亡"; data.touch(); }
        GuardianAi.release(golem);
    }
    public static void tickEntity(LivingEntity entity) { GuardianAi.tickEntity(entity); }
    public static void release(Entity entity) { GuardianAi.release(entity); }
    public static void releaseAll() { GuardianAi.releaseAll(); RETRY.clear(); PATH_AT.clear(); LEVEL_AT.clear(); }
    public static List<String> report(SettlementRecord s) {
        var state = s.guardians();
        List<String> lines = new ArrayList<>();
        lines.add("铁傀儡：登记" + state.aliveCount() + "只（含卸载），在制/待核对" + state.pendingCount()
                + "只，目标" + state.target + "只；自动制造" + (state.enabled ? "开" : "关"));
        lines.add("铁傀儡：铁料储备 " + state.ironReserve + "铁锭等值；装配4铁块+1雕刻南瓜；维修1铁锭/最多25生命");
        if (!state.blocker.isEmpty()) lines.add("铁傀儡：" + state.blocker);
        for (var e : state.records()) {
            if (!e.counts() || e.blocker.isEmpty()) continue;
            lines.add("铁傀儡：" + e.id.toString().substring(0, 8) + " " + e.blocker);
            if (lines.size() >= 8) break;
        }
        return lines;
    }
    public static String detail(IronGolem golem) {
        if (!(golem.level() instanceof ServerLevel level)) return "";
        var tag = golem.getPersistentData();
        if (!tag.hasUUID(SETTLEMENT_ID)) return "";
        var s = SettlementSavedData.get(level).findById(tag.getUUID(SETTLEMENT_ID)).orElse(null);
        return s == null ? "" : "铁傀儡 · " + s.name() + " · " + GuardianAi.activity(golem)
                + " · 生命 " + (int) Math.ceil(golem.getHealth()) + "/" + (int) golem.getMaxHealth()
                + (golem.getHealth() < golem.getMaxHealth() ? " · 等待工匠近身维修" : "");
    }
}

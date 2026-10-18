package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Cooperative, server-thread-only construction queries. A spent slice is not a
 * failed route. Replaying a yielded planner reuses already examined candidates,
 * including negative results, until that planning pass finishes or geometry changes.
 * Nothing here changes the live navigator, its node limit, or interaction reach.
 */
public final class ConstructionPlanningBudget {
    public static final int PATHS_PER_RESIDENT_TICK = 8;
    public static final int PATHS_PER_LEVEL_TICK = 32;
    public static final int EGRESS_PER_RESIDENT_TICK = 4;
    public static final int EGRESS_PER_LEVEL_TICK = 16;
    private static final long COMPLETED_CACHE_TICKS = 40L;
    private static final long ABANDONED_PLAN_TICKS = 200L;
    private static final int MAX_MEMO_ENTRIES = 8192;
    private static final Map<ServerLevel, LevelBudget> LEVELS = new WeakHashMap<>();
    @Nullable private static Scope active;

    private ConstructionPlanningBudget() { }

    public static Scope begin(SettlementCitizenEntity entity, @Nullable UUID task) {
        ServerLevel level = (ServerLevel) entity.level();
        long now = level.getGameTime();
        LevelBudget budget = LEVELS.computeIfAbsent(level, ignored -> new LevelBudget());
        budget.beginTick(now);
        ResidentBudget resident = budget.residents.computeIfAbsent(entity.getUUID(), ignored -> new ResidentBudget());
        if (resident.waiting && now > resident.lastSeen) {
            entity.excludeConstructionPlanningWait(Math.min(ABANDONED_PLAN_TICKS, now - resident.lastSeen));
        }
        if (resident.tick != now) {
            resident.tick = now;
            resident.paths = 0;
            resident.egress = 0;
        }
        if (!entity.blockPosition().equals(resident.origin)
                || !java.util.Objects.equals(task, resident.task)
                || resident.dirty || resident.completedAt >= 0L
                && now - resident.completedAt >= COMPLETED_CACHE_TICKS) {
            resident.reset(entity.blockPosition(), task);
        }
        resident.lastSeen = now;
        Scope scope = new Scope(entity, budget, resident, active);
        active = scope;
        return scope;
    }

    /** Only planning scopes are intercepted; all ordinary profession navigation is unchanged. */
    @Nullable
    public static Path probe(SettlementCitizenEntity entity, BlockPos target, Supplier<Path> calculation) {
        Scope scope = current(entity);
        if (scope == null) return calculation.get();
        scope.refreshIfDirty();
        scope.watch(target);
        PathKey key = new PathKey(target.immutable());
        Object cached = scope.resident.memo.get(key);
        if (cached instanceof PathResult result) {
            scope.budget.cacheHits++;
            scope.resident.cacheHits++;
            return result.path;
        }
        scope.consume(false);
        long started=System.nanoTime();Path path;
        try {path=calculation.get();} finally {scope.budget.nanos+=System.nanoTime()-started;}
        scope.remember(key, new PathResult(path));
        return path;
    }

    /** Charge actual committed searches too, but never cache or replace the live route. */
    @Nullable
    public static Path probeMany(SettlementCitizenEntity entity, java.util.List<BlockPos> targets,
                                 Supplier<Path> calculation) {
        Scope scope = current(entity);
        if (scope == null) return calculation.get();
        scope.refreshIfDirty();
        targets.forEach(scope::watch);
        MultiPathKey key = new MultiPathKey(java.util.List.copyOf(targets));
        Object cached = scope.resident.memo.get(key);
        if (cached instanceof PathResult result) {
            scope.budget.cacheHits++;
            scope.resident.cacheHits++;
            return result.path;
        }
        scope.consume(false);
        long started=System.nanoTime();Path path;
        try {path=calculation.get();} finally {scope.budget.nanos+=System.nanoTime()-started;}
        scope.remember(key, new PathResult(path));
        return path;
    }

    /** Charge actual committed searches too, but never cache or replace the live route. */
    public static void beforeNavigationCommit(SettlementCitizenEntity entity) {
        Scope scope = current(entity);
        if (scope != null) scope.consume(false);
    }

    public static boolean egress(Level level, Object key, BlockPos start,
                                 BlockPos minimum, BlockPos maximum, Supplier<Boolean> calculation) {
        Scope scope = active;
        if (scope == null || scope.entity.level() != level) return calculation.get();
        scope.refreshIfDirty();
        scope.watch(start);
        scope.watch(minimum);
        scope.watch(maximum);
        EgressKey wrapped = new EgressKey(key);
        Object cached = scope.resident.memo.get(wrapped);
        if (cached instanceof Boolean result) {
            scope.budget.cacheHits++;
            scope.resident.cacheHits++;
            return result;
        }
        scope.consume(true);
        long started=System.nanoTime();boolean result;
        try {result=calculation.get();} finally {scope.budget.nanos+=System.nanoTime()-started;}
        scope.remember(wrapped, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <T> T geometry(SettlementCitizenEntity entity, Object key, BlockPos target, Supplier<T> calculation) {
        Scope scope = current(entity);
        if (scope == null) return calculation.get();
        scope.refreshIfDirty();
        scope.watch(target);
        GeometryKey wrapped = new GeometryKey(key);
        Object cached = scope.resident.memo.get(wrapped);
        if (cached != null) return (T) cached;
        T result = calculation.get();
        scope.remember(wrapped, result);
        return result;
    }

    /** Called after a successful world write; unrelated chunks do not disturb local planning. */
    public static void blockChanged(Level level, BlockPos position) {
        if (!(level instanceof ServerLevel server)) return;
        NavigationSearchBudget.blockChanged(level,position);
        LevelBudget budget = LEVELS.get(server);
        if (budget == null || budget.residents.isEmpty()) return;
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        long now = server.getGameTime();
        for (ResidentBudget resident : budget.residents.values()) {
            if (now - resident.lastSeen <= ABANDONED_PLAN_TICKS
                    && chunkX >= resident.minChunkX && chunkX <= resident.maxChunkX
                    && chunkZ >= resident.minChunkZ && chunkZ <= resident.maxChunkZ) {
                resident.dirty = true;
            }
        }
    }

    public static void forget(Level level, UUID entityId) {
        if (!(level instanceof ServerLevel server)) return;
        LevelBudget budget = LEVELS.get(server);
        if (budget != null) {
            budget.residents.remove(entityId);
            budget.waiters.remove(entityId);
        }
    }

    /** Current real query counts; exposed for diagnostics and runtime budget regression. */
    public static Snapshot snapshot(ServerLevel level, UUID entityId) {
        LevelBudget budget = LEVELS.get(level);
        if (budget == null) return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
        ResidentBudget resident = budget.residents.get(entityId);
        boolean current = budget.tick == level.getGameTime();
        return new Snapshot(current ? budget.paths : 0, current ? budget.egress : 0,
                current && resident != null && resident.tick == budget.tick ? resident.paths : 0,
                current && resident != null && resident.tick == budget.tick ? resident.egress : 0,
                budget.totalPaths, budget.cacheHits, resident == null ? 0 : resident.memo.size(),
                resident == null ? 0 : resident.totalPaths, resident == null ? 0 : resident.cacheHits);
    }

    public record Snapshot(int levelPaths, int levelEgress, int residentPaths, int residentEgress,
                           long totalPaths, long cacheHits, int memoEntries,
                           long residentTotalPaths, long residentCacheHits) { }

    @Nullable
    private static Scope current(SettlementCitizenEntity entity) {
        return active != null && active.entity == entity ? active : null;
    }

    public static final class Scope implements AutoCloseable {
        private final SettlementCitizenEntity entity;
        private final LevelBudget budget;
        private final ResidentBudget resident;
        @Nullable private final Scope previous;
        private boolean yielded;

        private Scope(SettlementCitizenEntity entity, LevelBudget budget, ResidentBudget resident,
                      @Nullable Scope previous) {
            this.entity = entity;
            this.budget = budget;
            this.resident = resident;
            this.previous = previous;
            watch(entity.blockPosition());
        }

        private void refreshIfDirty() {
            if (resident.dirty) {
                resident.reset(entity.blockPosition(), resident.task);
                watch(entity.blockPosition());
            }
        }

        private void watch(BlockPos position) {
            // The unchanged vanilla search window is 32 blocks. Record only its
            // nearby chunks, not a world-global revision which crops/leaves churn.
            // Four bounds, not an unbounded map entry for every visited chunk.
            resident.minChunkX = Math.min(resident.minChunkX, (position.getX() - 32) >> 4);
            resident.maxChunkX = Math.max(resident.maxChunkX, (position.getX() + 32) >> 4);
            resident.minChunkZ = Math.min(resident.minChunkZ, (position.getZ() - 32) >> 4);
            resident.maxChunkZ = Math.max(resident.maxChunkZ, (position.getZ() + 32) >> 4);
        }

        private void consume(boolean egress) {
            boolean spent = egress ? resident.egress >= EGRESS_PER_RESIDENT_TICK
                    || budget.egress >= EGRESS_PER_LEVEL_TICK
                    : resident.paths >= PATHS_PER_RESIDENT_TICK || budget.paths >= PATHS_PER_LEVEL_TICK;
            // Reserve one slice for the oldest waiter, not the whole level.
            // That resident may already have spent its slice this tick, or may
            // not be scheduled again yet. Neither case should idle other workers.
            int remaining = egress ? EGRESS_PER_LEVEL_TICK - budget.egress
                    : PATHS_PER_LEVEL_TICK - budget.paths;
            int reserved = egress ? EGRESS_PER_RESIDENT_TICK : PATHS_PER_RESIDENT_TICK;
            boolean queuedBehind = resident.paths == 0 && resident.egress == 0
                    && !budget.waiters.isEmpty() && !budget.waiters.getFirst().equals(entity.getUUID())
                    && remaining <= reserved;
            if (spent || queuedBehind || budget.nanos>=2_000_000L) {
                yielded = true;
                budget.waiters.add(entity.getUUID());
                throw Deferred.INSTANCE;
            }
            budget.waiters.remove(entity.getUUID());
            if (egress) { resident.egress++; budget.egress++; }
            else { resident.paths++; budget.paths++; budget.totalPaths++; resident.totalPaths++; }
        }

        private void remember(Object key, Object value) {
            if (resident.memo.size() < MAX_MEMO_ENTRIES) resident.memo.put(key, value);
        }

        @Override
        public void close() {
            resident.waiting = yielded;
            if (yielded) resident.completedAt = -1L;
            else {
                if (resident.completedAt < 0L) resident.completedAt = entity.level().getGameTime();
                budget.waiters.remove(entity.getUUID());
            }
            active = previous;
        }
    }

    /** Stackless cooperative yield: caught only at the construction scheduler boundary. */
    public static final class Deferred extends RuntimeException {
        private static final Deferred INSTANCE = new Deferred();
        private Deferred() { super(null, null, false, false); }
    }

    private static final class LevelBudget {
        long tick = Long.MIN_VALUE;
        int paths;
        int egress;
        long nanos;
        long totalPaths;
        long cacheHits;
        final Map<UUID, ResidentBudget> residents = new HashMap<>();
        final LinkedHashSet<UUID> waiters = new LinkedHashSet<>();
        void beginTick(long now) {
            if (tick == now) return;
            tick = now;
            paths = 0;
            egress = 0;
            nanos = 0;
            residents.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > ABANDONED_PLAN_TICKS);
            waiters.removeIf(id -> !residents.containsKey(id));
        }
    }

    private static final class ResidentBudget {
        long tick = Long.MIN_VALUE;
        long lastSeen;
        long completedAt = -1L;
        long totalPaths;
        long cacheHits;
        int paths;
        int egress;
        boolean dirty;
        boolean waiting;
        @Nullable UUID task;
        @Nullable BlockPos origin;
        final Map<Object, Object> memo = new LinkedHashMap<>();
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        void reset(BlockPos start, @Nullable UUID newTask) {
            origin = start.immutable();
            task = newTask;
            dirty = false;
            completedAt = -1L;
            memo.clear();
            minChunkX = minChunkZ = Integer.MAX_VALUE;
            maxChunkX = maxChunkZ = Integer.MIN_VALUE;
        }
    }

    private record PathKey(BlockPos target) { }
    private record MultiPathKey(java.util.List<BlockPos> targets) { }
    private record PathResult(@Nullable Path path) { }
    private record EgressKey(Object key) { }
    private record GeometryKey(Object key) { }
}

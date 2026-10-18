package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bounded, runtime-only route hints shared by residents of one settlement.
 *
 * <p>The vanilla {@code Path} object is never cached because it belongs to one
 * navigator and becomes stale after world edits. Instead, a successful bounded
 * segment contributes one validated waypoint to the settlement's habitual route.
 * Other residents may reuse that waypoint and still ask their own navigator for
 * an authoritative path to it. Failed or obstructed hints are discarded.</p>
 */
public final class CitizenHabitualRouteService {
    private static final long ROUTE_TTL_TICKS = 12_000L;
    private static final int MAX_ROUTES_PER_LEVEL = 512;
    private static final int ORIGIN_CELL_SIZE = 8;
    private static final int DESTINATION_CELL_SIZE = 8;
    private static final Map<Level, LinkedHashMap<RouteKey, RouteHint>> LEVEL_ROUTES =
            new WeakHashMap<>();

    private CitizenHabitualRouteService() {
    }

    public static synchronized void remember(
            Level level, UUID settlementId, BlockPos origin, BlockPos destination,
            BlockPos waypoint, long gameTime
    ) {
        if (waypoint.distSqr(destination) >= origin.distSqr(destination)) return;
        LinkedHashMap<RouteKey, RouteHint> routes = routes(level, gameTime);
        RouteKey key = key(settlementId, origin, destination);
        routes.put(key, new RouteHint(waypoint.immutable(), gameTime + ROUTE_TTL_TICKS));
        while (routes.size() > MAX_ROUTES_PER_LEVEL) {
            Iterator<RouteKey> iterator = routes.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    @Nullable
    public static synchronized BlockPos nextWaypoint(
            Level level, UUID settlementId, BlockPos origin, BlockPos destination, long gameTime
    ) {
        RouteHint hint = routes(level, gameTime).get(key(settlementId, origin, destination));
        if (hint == null || hint.waypoint().distSqr(destination) >= origin.distSqr(destination)) return null;
        return hint.waypoint();
    }

    public static synchronized void invalidate(
            Level level, UUID settlementId, BlockPos origin, BlockPos destination
    ) {
        LinkedHashMap<RouteKey, RouteHint> routes = LEVEL_ROUTES.get(level);
        if (routes != null) routes.remove(key(settlementId, origin, destination));
    }

    public static synchronized int routeCount(Level level, long gameTime) {
        return routes(level, gameTime).size();
    }

    public static synchronized void clear(Level level) {
        LEVEL_ROUTES.remove(level);
    }

    private static LinkedHashMap<RouteKey, RouteHint> routes(Level level, long gameTime) {
        LinkedHashMap<RouteKey, RouteHint> routes = LEVEL_ROUTES.computeIfAbsent(
                level, ignored -> new LinkedHashMap<>(32, 0.75F, true));
        routes.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= gameTime);
        return routes;
    }

    private static RouteKey key(UUID settlementId, BlockPos origin, BlockPos destination) {
        return new RouteKey(settlementId, coarse(origin, ORIGIN_CELL_SIZE),
                coarse(destination, DESTINATION_CELL_SIZE));
    }

    private static BlockPos coarse(BlockPos position, int size) {
        return new BlockPos(Math.floorDiv(position.getX(), size),
                Math.floorDiv(position.getY(), 4), Math.floorDiv(position.getZ(), size));
    }

    private record RouteKey(UUID settlementId, BlockPos originCell, BlockPos destinationCell) {
    }

    private record RouteHint(BlockPos waypoint, long expiresAt) {
    }
}

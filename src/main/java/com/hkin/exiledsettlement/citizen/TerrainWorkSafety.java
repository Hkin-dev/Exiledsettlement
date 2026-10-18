package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.Path;
import java.util.*;

/** A real, reversible return route, never a promise from segmented navigation. */
public final class TerrainWorkSafety {
    private static final int MAX_NODES = 256;
    private static final Map<SettlementCitizenEntity, Proof> RETURNS = new WeakHashMap<>();
    private record Proof(int landing, List<BlockPos> route) { }
    private record EscapeKey(BlockPos at, int landing) { }
    private record RouteResult(List<BlockPos> route) { }

    public static boolean hasReturn(SettlementCitizenEntity e, BlockPos at, int landing) {
        if (!ground(e, at)) return false;
        var feet = e.navigationFeetPosition();
        List<BlockPos> tail = tail(e, feet, landing);
        if (tail == null) return independentEscape(e,at,landing);
        List<BlockPos> route = join(e, at, tail);
        if (route == null) {
            Path path = e.probeConstructionReturnPath(at);
            if (path == null || path.getNodeCount() > MAX_NODES) return independentEscape(e,at,landing);
            var reverse = new ArrayList<BlockPos>();
            reverse.add(at.immutable());
            for (int i = path.getNodeCount() - 1; i >= 0; i--) {
                if (!append(e, reverse, path.getNodePos(i))) return independentEscape(e,at,landing);
            }
            if (!append(e, reverse, feet)) return independentEscape(e,at,landing);
            for (int i = 1; i < tail.size(); i++) if (!append(e, reverse, tail.get(i))) return independentEscape(e,at,landing);
            route = reverse;
        }
        if (route.size() > MAX_NODES || !valid(e, route, landing)) return independentEscape(e,at,landing);
        RETURNS.put(e, new Proof(landing, List.copyOf(route)));
        return true;
    }

    private static boolean independentEscape(SettlementCitizenEntity e,BlockPos at,int landing){
        var key = new EscapeKey(at.immutable(), landing);
        var result = ConstructionPlanningBudget.geometry(e, key, at, () -> {
            ConstructionPlanningBudget.egress(e.level(), key, at, at.offset(-24,-24,-24),
                    at.offset(24,24,24), () -> true);
            return new RouteResult(findEscape(e, at, landing));
        });
        if (result.route == null || !valid(e, result.route, landing)) return false;
        RETURNS.put(e, new Proof(landing, List.copyOf(result.route)));
        return true;
    }

    /** Also works after restart or when the worker starts below the building floor. */
    private static List<BlockPos> findEscape(SettlementCitizenEntity e, BlockPos at, int landing) {
        var previous = new HashMap<BlockPos, BlockPos>();
        var tested = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(at); tested.add(at); previous.put(at, at);
        while (!queue.isEmpty() && tested.size() < 4096) {
            var p = queue.removeFirst();
            if (p.getY() >= landing) {
                var route = new ArrayList<BlockPos>();
                for (var cursor = p;;cursor = previous.get(cursor)) {
                    route.add(cursor); if (cursor.equals(at)) break;
                }
                Collections.reverse(route); return route;
            }
            for (var d : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0,1,-1}) {
                var next = p.relative(d).above(dy);
                if (Math.abs(next.getX()-at.getX()) > 24 || Math.abs(next.getZ()-at.getZ()) > 24
                        || Math.abs(next.getY()-at.getY()) > 24 || !tested.add(next) || !edge(e,p,next)) continue;
                previous.put(next,p);queue.add(next);
            }
        }
        return null;
    }

    private static List<BlockPos> tail(SettlementCitizenEntity e, BlockPos feet, int landing) {
        if (!ground(e, feet)) return null;
        if (feet.getY() >= landing) return List.of(feet.immutable());
        var old = RETURNS.get(e);
        if (old == null || old.landing != landing) return null;
        var result = join(e, feet, old.route);
        return result != null && valid(e, result, landing) ? result : null;
    }

    /** Rejoin after walking along the route or after one paid foundation rise. */
    private static List<BlockPos> join(SettlementCitizenEntity e, BlockPos from, List<BlockPos> tail) {
        for (int i = 0; i < tail.size(); i++) {
            var p = tail.get(i);
            if (!from.equals(p) && !edge(e, from, p)) continue;
            var joined = new ArrayList<BlockPos>();
            joined.add(from.immutable());
            for (int j = from.equals(p) ? i + 1 : i; j < tail.size(); j++) joined.add(tail.get(j));
            return joined;
        }
        return null;
    }

    private static boolean append(SettlementCitizenEntity e, List<BlockPos> nodes, BlockPos next) {
        var from = nodes.getLast();
        if (from.equals(next)) return true;
        if (edge(e, from, next)) { nodes.add(next.immutable()); return nodes.size() <= MAX_NODES; }
        // Prove a complete cardinal corner for vanilla diagonal path nodes.
        if (Math.abs(next.getX() - from.getX()) == 1 && Math.abs(next.getZ() - from.getZ()) == 1
                && Math.abs(next.getY() - from.getY()) <= 1) {
            for (int y : new int[]{from.getY(), next.getY()}) {
                for (var middle : List.of(new BlockPos(next.getX(), y, from.getZ()), new BlockPos(from.getX(), y, next.getZ()))) {
                    if (edge(e, from, middle) && edge(e, middle, next)) {
                        nodes.add(middle); nodes.add(next.immutable()); return nodes.size() <= MAX_NODES;
                    }
                }
            }
        }
        return false;
    }

    private static boolean valid(SettlementCitizenEntity e, List<BlockPos> route, int landing) {
        if (route.isEmpty() || route.getLast().getY() < landing || !ground(e, route.getFirst())) return false;
        for (int i = 1; i < route.size(); i++) if (!edge(e, route.get(i - 1), route.get(i))) return false;
        return true;
    }

    private static boolean edge(SettlementCitizenEntity e, BlockPos from, BlockPos to) {
        return ground(e, from) && ground(e, to) && e.canTraverseRecordedConstructionEdge(from, to);
    }

    private static boolean ground(SettlementCitizenEntity e, BlockPos at) {
        var l = e.level();
        return l.hasChunkAt(at) && e.canStandAtPosition(at) && l.getFluidState(at).isEmpty()
                && l.getFluidState(at.below()).isEmpty() && l.getBlockState(at.below()).isFaceSturdy(l, at.below(), Direction.UP);
    }
    private TerrainWorkSafety() { }
}

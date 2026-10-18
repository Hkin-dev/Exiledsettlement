package com.hkin.exiledsettlement.network;

import java.util.Map;
import java.util.WeakHashMap;

/** Server-thread only. Connection keys expire with player objects after disconnect. */
public final class BuildingSnapshotRequestLimiter {
    private final Map<Object, Long> lastAcceptedTick = new WeakHashMap<>();

    public boolean accept(Object connection, long serverTick, boolean refreshOnly) {
        Long previous = lastAcceptedTick.get(connection);
        // Normal refresh is once per second; explicit opens allow four per second.
        int minimumTicks = refreshOnly ? 20 : 5;
        if (previous != null && serverTick >= previous && serverTick - previous < minimumTicks) return false;
        lastAcceptedTick.put(connection, serverTick);
        return true;
    }
}

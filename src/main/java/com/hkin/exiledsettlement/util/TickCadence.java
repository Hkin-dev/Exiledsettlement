package com.hkin.exiledsettlement.util;

import java.util.UUID;

/** Stable phases spread periodic work without changing each object's check frequency. */
public final class TickCadence {
    private TickCadence() {}
    public static boolean due(long tick,UUID id,int interval,int salt) {
        return Math.floorMod(tick,interval)==Math.floorMod((long)id.hashCode()+salt,interval);
    }
}

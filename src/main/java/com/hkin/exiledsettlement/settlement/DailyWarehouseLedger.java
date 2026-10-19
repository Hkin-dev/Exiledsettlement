package com.hkin.exiledsettlement.settlement;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Actual stock-change notifications, partitioned by Minecraft world day. */
public final class DailyWarehouseLedger {
    private long day = Long.MIN_VALUE;
    private boolean partial;
    private final Map<String,Long> incoming = new LinkedHashMap<>(), outgoing = new LinkedHashMap<>();
    public long day() { return day; }
    public boolean partial() { return partial; }
    public Map<String,Long> incoming() { return Map.copyOf(incoming); }
    public Map<String,Long> outgoing() { return Map.copyOf(outgoing); }
    public boolean beginDay(long value) {
        if (value == day) return false;
        partial = day == Long.MIN_VALUE || value < day;
        day = value; incoming.clear(); outgoing.clear(); return true;
    }
    public void record(long currentDay, String item, long change) {
        beginDay(currentDay);
        if (change > 0) incoming.merge(item, change, DailyWarehouseLedger::add);
        if (change < 0) outgoing.merge(item, change == Long.MIN_VALUE ? Long.MAX_VALUE : -change, DailyWarehouseLedger::add);
    }
    private static long add(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putLong("Day", day); tag.putBoolean("Partial", partial);
        var in = new CompoundTag(); incoming.forEach(in::putLong); tag.put("In", in);
        var out = new CompoundTag(); outgoing.forEach(out::putLong); tag.put("Out", out); return tag;
    }
    public void load(CompoundTag tag) {
        day = tag.contains("Day") ? tag.getLong("Day") : Long.MIN_VALUE;
        partial = tag.getBoolean("Partial"); incoming.clear(); outgoing.clear();
        read(tag.getCompound("In"), incoming); read(tag.getCompound("Out"), outgoing);
    }
    private static void read(CompoundTag tag, Map<String,Long> into) {
        for (String id : tag.getAllKeys()) if (id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && tag.getLong(id) > 0) into.put(id, tag.getLong(id));
    }
}

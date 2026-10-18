package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** One shared calendar; settlement copies keep existing consumers/save readers coherent. */
public final class WorldSeasonData extends SavedData {
    private static final Factory<WorldSeasonData> FACTORY = new Factory<>(WorldSeasonData::new, WorldSeasonData::load);
    private boolean initialized;
    private int days = 30;
    private long offset;

    public static WorldSeasonData get(ServerLevel level) {
        var overworld = level.getServer().overworld();
        var result = overworld.getDataStorage().computeIfAbsent(FACTORY, "exiledsettlement_seasons");
        result.initialize(overworld);
        return result;
    }
    public void initialize(ServerLevel overworld) {
        if (initialized) return;
        days = SeasonCalendar.length(CommunityContent.seasonDays);
        var existing = SettlementSavedData.get(overworld).settlements().stream().filter(SettlementRecord::active).findFirst().orElse(null);
        if (existing != null) {
            existing.community().migrateCalendar(CommunityLifeService.day(overworld));
            days = existing.community().seasonLength;
            offset = existing.community().calendarOffsetDays;
        }
        initialized = true;
        setDirty();
    }
    public int days() { return days; }
    public long offsetDays() { return offset; }
    public long time(long worldTime) { return SeasonCalendar.shiftedTime(worldTime, offset); }
    public int season(long worldTime) { return SeasonCalendar.season(time(worldTime), days); }
    public boolean bind(CommunityState state, long worldDay) {
        if (state.rulesVersion >= 3 && state.seasonLength == days && state.calendarOffsetDays == offset) return false;
        boolean changed = state.seasonLength != days || state.calendarOffsetDays != offset;
        state.seasonLength = days;
        state.calendarOffsetDays = offset;
        state.rulesVersion = Math.max(3, state.rulesVersion);
        long year = state.calendarDay(worldDay) / (days * 4L);
        state.lastProcessedYear = year;
        state.lastOfferYear = year;
        state.coldFrom = -1; state.coldUntil = -1;
        state.winterStarted = state.calendarDay(worldDay) / days % 4 == 3;
        if (changed) state.event(worldDay, "已对齐共享世界季节；不追补居民消耗、成长或年度机会。季节调整仅管理员测试可用。");
        return true;
    }
    public void configure(int nextDays, long nextOffset) {
        days = SeasonCalendar.length(nextDays); offset = nextOffset; initialized = true; setDirty();
    }
    public void syncSettlements(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        for (var settlement : data.settlements()) if (bind(settlement.community(), CommunityLifeService.day(level))) data.touch();
    }
    public static WorldSeasonData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("Version") != 1) throw new IllegalArgumentException("Unsupported world season version");
        var result = new WorldSeasonData();
        result.initialized = tag.getBoolean("Initialized");
        result.days = SeasonCalendar.length(tag.getInt("Days"));
        result.offset = tag.getLong("OffsetDays");
        return result;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1); tag.putBoolean("Initialized", initialized);
        tag.putInt("Days", days); tag.putLong("OffsetDays", offset);
        return tag;
    }
}

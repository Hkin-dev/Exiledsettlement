package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Only our snow is eligible for seasonal melting. The queue never scans all world blocks. */
public final class SeasonSnowData extends SavedData {
    public static final int MAX_RECORDS = 262144;
    private static final Factory<SeasonSnowData> FACTORY = new Factory<>(SeasonSnowData::new, SeasonSnowData::load);
    private final LinkedHashMap<Long, Integer> layers = new LinkedHashMap<>();
    public static SeasonSnowData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "exiledsettlement_season_snow");
    }
    public int size() { return layers.size(); }
    public int layers(BlockPos pos) { return layers.getOrDefault(pos.asLong(), 0); }
    public boolean remember(BlockPos pos, int count) {
        long key = pos.asLong();
        if (!layers.containsKey(key)) {
            if (layers.size() >= MAX_RECORDS) return false;
        }
        layers.put(key, count); setDirty(); return true;
    }
    public void forget(BlockPos pos) { if (layers.remove(pos.asLong()) != null) setDirty(); }
    /** O(1) round-robin; deleting records also deletes their queue entry. */
    public Long next() {
        var entry = layers.pollFirstEntry();
        if (entry == null) return null;
        layers.putLast(entry.getKey(), entry.getValue());
        return entry.getKey();
    }
    public static SeasonSnowData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("Version") != 1) throw new IllegalArgumentException("Unsupported seasonal snow version");
        var result = new SeasonSnowData();
        ListTag records = tag.getList("Snow", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_RECORDS, records.size()); i++) {
            var entry = records.getCompound(i); int count = entry.getInt("Layers");
            if (count >= 1 && count <= 2) result.remember(BlockPos.of(entry.getLong("Pos")), count);
        }
        result.setDirty(false);
        return result;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1); var records = new ListTag();
        layers.forEach((pos, count) -> { var entry = new CompoundTag(); entry.putLong("Pos", pos); entry.putInt("Layers", count); records.add(entry); });
        tag.put("Snow", records); return tag;
    }
}

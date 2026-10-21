package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import net.minecraft.nbt.CompoundTag;
import java.util.HashSet;
import java.util.Set;

/** Per-mine, bounded work history. No offline credit and no shared worker multiplier. */
public final class MineExpansionState {
    public int cursor, work;
    public long lastAction = -200;
    public String reason = "";
    public final Set<Long> playerEdits = new HashSet<>();
    transient BuildingBlueprintTemplate cached;
    transient int cachedCursor = -1;

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 2); tag.putInt("Cursor", cursor); tag.putInt("Work", work);
        tag.putLong("LastAction", lastAction); tag.putString("Reason", reason);
        tag.putLongArray("PlayerEdits", playerEdits.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
    public void load(CompoundTag tag) {
        cursor = tag.getInt("Version")==2 ? Math.clamp(tag.getInt("Cursor"), 0, MineExpansionService.steps().size()) : 0;
        work = Math.clamp(tag.getInt("Work"), 0, 2);
        lastAction = tag.contains("LastAction") ? tag.getLong("LastAction") : -200;
        reason = tag.getString("Reason");
        playerEdits.clear();
        for (long p : tag.getLongArray("PlayerEdits")) playerEdits.add(p);
        cached = null; cachedCursor = -1;
    }
}

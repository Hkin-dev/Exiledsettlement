package com.hkin.exiledsettlement.decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import com.hkin.exiledsettlement.building.BuildingType;
import java.util.*;

/** Additive save data; old settlements opt in and placed components retain their version. */
public final class DecorationState {
    public boolean enabled;
    /** Refreshed by the bounded planner; never persisted as inventory truth. */
    public boolean workReady = true;
    public int density = 1, style, families = 15, dailyBudget = 256;
    public long budgetDay = -1;
    public int spent, roadCursor, buildingCursor, reconcileCursor;
    public String status = "尚未开启自动布置";
    public final Set<Long> forbiddenChunks = new LinkedHashSet<>();
    public final Map<String, Feature> features = new LinkedHashMap<>();
    public int spacing() { return new int[]{20, 16, 12}[Math.clamp(density, 0, 2)]; }
    public int limit() { return new int[]{32, 64, 96}[Math.clamp(density, 0, 2)]; }
    public boolean family(int bit) { return (families & bit) != 0; }
    public boolean forbidden(BlockPos pos) { return forbiddenChunks.contains(new net.minecraft.world.level.ChunkPos(pos).toLong()); }
    public void newDay(long day) { if (day != budgetDay) { budgetDay = day; spent = 0; } }
    public boolean reserve(int cost) { if (cost < 0 || spent + cost > dailyBudget) return false; spent += cost; return true; }
    public Feature owned(UUID blueprint) {
        if(blueprint==null || features.isEmpty())return null;
        for(var feature:features.values())if(blueprint.equals(feature.blueprint))return feature;
        return null;
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putBoolean("Enabled", enabled);
        tag.putInt("Density", density); tag.putInt("Style", style); tag.putInt("Families", families);
        tag.putInt("DailyBudget", dailyBudget); tag.putLong("BudgetDay", budgetDay); tag.putInt("Spent", spent);
        tag.putInt("RoadCursor", roadCursor); tag.putInt("BuildingCursor", buildingCursor); tag.putString("Status", status);
        tag.putLongArray("ForbiddenChunks", forbiddenChunks.stream().mapToLong(Long::longValue).toArray());
        var list = new ListTag(); features.forEach((key, f) -> {
            var t = new CompoundTag(); t.putString("Key", key); t.putString("Kind", f.kind);
            t.putString("Type", f.type.serializedName()); t.putInt("Template", f.version);
            t.putLong("Anchor", f.anchor.asLong()); t.putInt("Facing", f.facing.get2DDataValue());
            t.putUUID("Blueprint", f.blueprint); if (f.building != null) t.putUUID("Building", f.building);
            if (f.parent != null) t.putUUID("Parent", f.parent);
            t.putInt("Family", f.family); t.putBoolean("Suppressed", f.suppressed); list.add(t);
        }); tag.put("Features", list); return tag;
    }
    public void load(CompoundTag tag) {
        if (!tag.contains("Version") || tag.getInt("Version") != 1) return;
        enabled = tag.getBoolean("Enabled"); density = Math.clamp(tag.getInt("Density"), 0, 2);
        style = Math.clamp(tag.getInt("Style"), 0, 1); families = tag.getInt("Families") & 15;
        dailyBudget = Math.clamp(tag.getInt("DailyBudget"), 32, 1024); budgetDay = tag.getLong("BudgetDay");
        spent = Math.clamp(tag.getInt("Spent"), 0, 1024); roadCursor = Math.max(0, tag.getInt("RoadCursor"));
        buildingCursor = Math.max(0, tag.getInt("BuildingCursor")); status = tag.getString("Status");
        Arrays.stream(tag.getLongArray("ForbiddenChunks")).limit(64).forEach(forbiddenChunks::add);
        var list = tag.getList("Features", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(256, list.size()); i++) {
            var t = list.getCompound(i);
            try {
                var f = new Feature(t.getString("Kind"), BuildingType.fromSerializedName(t.getString("Type")), t.getInt("Template"),
                        BlockPos.of(t.getLong("Anchor")), Direction.from2DDataValue(t.getInt("Facing")), t.getUUID("Blueprint"),
                        t.hasUUID("Parent") ? t.getUUID("Parent") : null, t.getInt("Family"));
                f.suppressed = t.getBoolean("Suppressed"); f.building = t.hasUUID("Building") ? t.getUUID("Building") : null;
                features.put(t.getString("Key"), f);
            } catch (IllegalArgumentException ignored) { enabled = false; status = "装饰性模块记录异常，已暂停自动布置"; }
        }
    }
    public static final class Feature {
        public final String kind;
        public final BuildingType type;
        public final int version, family;
        public final BlockPos anchor;
        public final Direction facing;
        public final UUID blueprint, parent;
        public UUID building;
        public boolean suppressed;
        public boolean siteReady;
        public Feature(String kind, BuildingType type, int version, BlockPos anchor, Direction facing, UUID blueprint, UUID parent, int family) {
            this.kind = kind; this.type = type; this.version = version; this.anchor = anchor.immutable();
            this.facing = facing; this.blueprint = blueprint; this.parent = parent; this.family = family;
        }
    }
}

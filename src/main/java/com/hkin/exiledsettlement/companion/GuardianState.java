package com.hkin.exiledsettlement.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/** Saved ownership and assembly orders. An absent entity is never evidence of death. */
public final class GuardianState {
    public boolean enabled = true;
    public int target = 1;
    public int ironReserve = 18;
    public String blocker = "";
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    public transient long nextOrderCheck;

    public static final class Entry {
        public final UUID id;
        public UUID entityId;
        public UUID worker;
        public BlockPos anchor;
        public BlockPos lastPosition;
        public String state = "assembling";
        public String blocker = "";
        public double progress;
        public boolean charged;
        public float health = 100;
        public transient long lastWorkTick = -1;
        public transient long nextPathTick;
        public transient long retryAt;
        public transient long lastSeenTick;
        public transient UUID repairWorker;
        public transient double repairProgress;
        public transient long lastRepairTick = -1;

        public Entry(UUID id, UUID worker, BlockPos anchor) {
            this.id = id;
            this.entityId = UUID.randomUUID();
            this.worker = worker;
            this.anchor = anchor.immutable();
            this.lastPosition = this.anchor;
        }
        public boolean counts() { return !state.equals("dead") && !state.equals("cancelled"); }
        public boolean assembling() { return state.equals("assembling"); }
        CompoundTag save() {
            var t = new CompoundTag();
            t.putUUID("Id", id); t.putUUID("Entity", entityId);
            if (worker != null) t.putUUID("Worker", worker);
            t.putLong("Anchor", anchor.asLong()); t.putLong("Position", lastPosition.asLong());
            t.putString("State", state); t.putString("Blocker", blocker);
            t.putDouble("Progress", progress); t.putBoolean("Charged", charged);
            t.putFloat("Health", health);
            return t;
        }
        static Entry load(CompoundTag t) {
            if (!t.hasUUID("Id") || !t.hasUUID("Entity") || !t.contains("Anchor")) return null;
            var e = new Entry(t.getUUID("Id"), t.hasUUID("Worker") ? t.getUUID("Worker") : null,
                    BlockPos.of(t.getLong("Anchor")));
            e.entityId = t.getUUID("Entity");
            e.lastPosition = t.contains("Position") ? BlockPos.of(t.getLong("Position")) : e.anchor;
            e.state = t.getString("State");
            if (!Set.of("assembling", "spawning", "alive", "dead", "cancelled", "uncertain").contains(e.state))
                e.state = "uncertain";
            e.progress = Double.isFinite(t.getDouble("Progress"))
                    ? Math.max(0, Math.min(1200, t.getDouble("Progress"))) : 0;
            e.charged = t.getBoolean("Charged");
            e.blocker = t.getString("Blocker");
            e.health = Float.isFinite(t.getFloat("Health")) ? Math.max(0, t.getFloat("Health")) : 100;
            // Never replay a transaction which was saved while entity admission was unresolved.
            if (e.state.equals("spawning")) { e.state = "uncertain"; e.blocker = "装配登记待核对；保留数量额度"; }
            return e;
        }
    }

    public Collection<Entry> records() { return Collections.unmodifiableCollection(entries.values()); }
    public Entry entry(UUID id) { return entries.get(id); }
    public Entry forEntity(UUID id) { return entries.values().stream().filter(e -> e.entityId.equals(id)).findFirst().orElse(null); }
    public int count() { return (int) entries.values().stream().filter(Entry::counts).count(); }
    public int aliveCount() { return (int) entries.values().stream().filter(e -> e.state.equals("alive")).count(); }
    public int pendingCount() { return (int) entries.values().stream().filter(e -> e.counts() && !e.state.equals("alive")).count(); }
    public Entry createOrder(UUID worker, BlockPos at) {
        if (count() >= Math.max(0, target)) return null;
        var entry = new Entry(UUID.randomUUID(), worker, at);
        entries.put(entry.id, entry);
        return entry;
    }
    public CompoundTag save() {
        var t = new CompoundTag();
        t.putBoolean("Enabled", enabled); t.putInt("Target", target); t.putInt("IronReserve", ironReserve);
        t.putString("Blocker", blocker);
        var list = new ListTag(); entries.values().forEach(e -> list.add(e.save())); t.put("Entries", list);
        return t;
    }
    public void load(CompoundTag t) {
        entries.clear();
        // New objects default on; a legacy settlement with no component opts in explicitly.
        enabled = !t.isEmpty() && t.getBoolean("Enabled");
        target = t.contains("Target") ? Math.max(0, Math.min(8, t.getInt("Target"))) : 1;
        ironReserve = t.contains("IronReserve") ? Math.max(0, Math.min(4096, t.getInt("IronReserve"))) : 18;
        blocker = t.getString("Blocker"); nextOrderCheck = 0;
        ListTag list = t.getList("Entries", Tag.TAG_COMPOUND);
        Set<UUID> entities = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Entry e = Entry.load(list.getCompound(i));
            if (e != null && !entries.containsKey(e.id) && entities.add(e.entityId)) entries.put(e.id, e);
        }
    }
}

package com.hkin.exiledsettlement.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Durable identity and work facts; a missing entity never requests another pet. */
public final class PetState {
    public boolean enabled = true;
    public int catLimit = 2;
    public int dogLimit = 2;
    public final LinkedHashMap<UUID, Pet> entries = new LinkedHashMap<>();
    public String lastSearch = "";

    public enum Status { ADOPTING, ACTIVE, DEAD, RELEASED }

    public static final class Pet {
        public final UUID id;
        public final UUID entityId;
        public final boolean cat;
        public Status status = Status.ADOPTING;
        public UUID caretaker;
        public UUID home;
        public UUID worker;
        public BlockPos lastPosition;
        public long lastFedDay = -1;
        public long attemptDay = -1;
        public int attempts;
        public long consumedLures;
        public long consumedMeals;
        public long retryAt;
        public long workerUntil;
        public String blocker = "";
        public String activity = "等待领养";
        long nextMove;
        long progressAt;
        BlockPos workerPosition;
        BlockPos workerTarget;

        public Pet(UUID id, UUID entityId, boolean cat, UUID caretaker, UUID home, BlockPos position) {
            this.id = id;
            this.entityId = entityId;
            this.cat = cat;
            this.caretaker = caretaker;
            this.home = home;
            this.lastPosition = position.immutable();
        }

        public boolean occupiesSlot() { return status == Status.ADOPTING || status == Status.ACTIVE; }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putUUID("Entity", entityId);
            tag.putBoolean("Cat", cat);
            tag.putString("Status", status.name());
            if (caretaker != null) tag.putUUID("Caretaker", caretaker);
            if (home != null) tag.putUUID("Home", home);
            if (worker != null) tag.putUUID("Worker", worker);
            tag.putLong("WorkerUntil", workerUntil);
            tag.putLong("Position", lastPosition.asLong());
            tag.putLong("LastFedDay", lastFedDay);
            tag.putLong("AttemptDay", attemptDay);
            tag.putInt("Attempts", attempts);
            tag.putLong("ConsumedLures", consumedLures);
            tag.putLong("ConsumedMeals", consumedMeals);
            tag.putLong("RetryAt", retryAt);
            tag.putString("Blocker", blocker);
            tag.putString("Activity", activity);
            return tag;
        }

        static Pet load(CompoundTag tag) {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Entity")) return null;
            Pet pet = new Pet(tag.getUUID("Id"), tag.getUUID("Entity"), tag.getBoolean("Cat"),
                    tag.hasUUID("Caretaker") ? tag.getUUID("Caretaker") : null,
                    tag.hasUUID("Home") ? tag.getUUID("Home") : null,
                    BlockPos.of(tag.getLong("Position")));
            try { pet.status = Status.valueOf(tag.getString("Status")); }
            catch (IllegalArgumentException ignored) { pet.status = Status.RELEASED; }
            pet.lastFedDay = tag.contains("LastFedDay") ? tag.getLong("LastFedDay") : -1;
            pet.attemptDay = tag.contains("AttemptDay") ? tag.getLong("AttemptDay") : -1;
            pet.attempts = Math.max(0, Math.min(3, tag.getInt("Attempts")));
            pet.consumedLures = Math.max(0, tag.getLong("ConsumedLures"));
            pet.consumedMeals = Math.max(0, tag.getLong("ConsumedMeals"));
            pet.retryAt = tag.getLong("RetryAt");
            pet.blocker = tag.getString("Blocker");
            pet.activity = tag.getString("Activity");
            // Workers retain real supplies, but leases are reacquired after loading.
            return pet;
        }
    }

    public long count(boolean cat) {
        return entries.values().stream().filter(pet -> pet.cat == cat && pet.occupiesSlot()).count();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        tag.putInt("CatLimit", catLimit);
        tag.putInt("DogLimit", dogLimit);
        tag.putString("LastSearch", lastSearch);
        ListTag list = new ListTag();
        entries.values().forEach(pet -> list.add(pet.save()));
        tag.put("Pets", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        entries.clear();
        enabled = tag.contains("Enabled") && tag.getBoolean("Enabled");
        catLimit = tag.contains("CatLimit") ? Math.max(0, Math.min(32, tag.getInt("CatLimit"))) : 2;
        dogLimit = tag.contains("DogLimit") ? Math.max(0, Math.min(32, tag.getInt("DogLimit"))) : 2;
        lastSearch = tag.getString("LastSearch");
        ListTag list = tag.getList("Pets", Tag.TAG_COMPOUND);
        java.util.HashSet<UUID> entities = new java.util.HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Pet pet = Pet.load(list.getCompound(i));
            if (pet != null && !entries.containsKey(pet.id) && entities.add(pet.entityId)) entries.put(pet.id, pet);
        }
    }
}

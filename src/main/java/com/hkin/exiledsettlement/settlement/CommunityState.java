package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

/** Bounded per-settlement record, independent of loaded entity UUIDs. */
public final class CommunityState {
    public String policy = "normal";
    public int rulesVersion = 2;
    public int seasonLength = CommunityContent.seasonDays;
    public long calendarOffsetDays;
    public int birthInterval = CommunityContent.birthIntervalDays;
    public int foodReserve = 24;
    public int fuelReserve = 12;
    public int mealTarget = 64;
    public boolean stewardEnabled = true, wheatPaused;
    public int snowLimit = 64, seedRounds = 2, wheatTarget = 512, boneTarget = 256, lifeSlots = 4;
    public String stewardStatus = "库存整理待命";
    public boolean birthsEnabled = false;
    public boolean evacuation = false;
    public BlockPos rally;
    public final Set<Long> dangerChunks = new LinkedHashSet<>();
    public long lastDay = -1;
    public long lastNeedTick = -1;
    public long lastOfferYear = -1;
    public long offerExpires = -1;
    public int migrantAdults;
    public int migrantChildren;
    public long lastProcessedYear = -1;
    public long coldUntil = -1;
    public long coldFrom = -1;
    public long frostDay = -1;
    public boolean frostApplied;
    public final Set<String> milestones = new LinkedHashSet<>();
    public final Set<String> barterClaims = new LinkedHashSet<>();
    public final Deque<String> events = new ArrayDeque<>();
    public final Map<String, Integer> yearProduced = new LinkedHashMap<>();
    public final Map<String, Integer> yearConsumed = new LinkedHashMap<>();
    public final Deque<String> reports = new ArrayDeque<>();
    public int mealsProduced;
    public int mealsEaten;
    public int coldFuelBurned;
    public long lastWarningDay = -1;
    public boolean winterStarted, winterLoss;

    public void event(long day, String message) {
        events.addLast("第" + (day + 1) + "日：" + message);
        while (events.size() > 32) events.removeFirst();
    }
    public boolean danger(BlockPos position) {
        return dangerChunks.contains(new net.minecraft.world.level.ChunkPos(position).toLong());
    }
    public long calendarDay(long worldDay) { return SeasonCalendar.shiftedDay(worldDay, calendarOffsetDays); }
    public long calendarTime(long worldTime) { return SeasonCalendar.shiftedTime(worldTime, calendarOffsetDays); }
    /** One-time upgrade, preserving year, season and within-season day fraction. No life catch-up. */
    public boolean migrateCalendar(long worldDay) {
        if (rulesVersion >= 2) return false;
        if (seasonLength < 30) {
            int oldLength = Math.max(1, seasonLength), newLength = Math.max(30, SeasonCalendar.length(CommunityContent.seasonDays));
            long oldOffset = calendarOffsetDays, oldDay = calendarDay(worldDay);
            long newDay = oldDay / oldLength * newLength + oldDay % oldLength * newLength / oldLength;
            calendarOffsetDays = newDay - Math.max(0, worldDay);
            coldFrom = retimeColdDate(coldFrom, oldLength, newLength, oldOffset);
            coldUntil = retimeColdDate(coldUntil, oldLength, newLength, oldOffset);
            seasonLength = newLength;
            event(worldDay, "季长升级为每季" + newLength + "日、每年" + (newLength * 4)
                    + "日；保留当前季节与季内日进度，居民成长和日常消耗不补算。");
        }
        rulesVersion = 2; return true;
    }
    private long retimeColdDate(long worldDate, int oldLength, int newLength, long oldOffset) {
        if (worldDate < 0) return -1;
        long oldDay = SeasonCalendar.shiftedDay(worldDate, oldOffset);
        long newDay = oldDay / oldLength * newLength + oldDay % oldLength * newLength / oldLength;
        return Math.max(0, newDay - calendarOffsetDays);
    }
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Policy", policy); t.putInt("FoodReserve", foodReserve);
        t.putInt("RulesVersion", rulesVersion); t.putInt("SeasonLength", seasonLength); t.putInt("BirthInterval", birthInterval);
        t.putLong("CalendarOffsetDays", calendarOffsetDays);
        t.putInt("FuelReserve", fuelReserve); t.putInt("MealTarget", mealTarget);
        t.putBoolean("StewardEnabled",stewardEnabled); t.putBoolean("WheatPaused",wheatPaused);
        t.putInt("SnowLimit",snowLimit); t.putInt("SeedRounds",seedRounds); t.putInt("WheatTarget",wheatTarget);
        t.putInt("BoneTarget",boneTarget); t.putInt("LifeSlots",lifeSlots);
        t.putBoolean("BirthsEnabled", birthsEnabled); t.putBoolean("Evacuation", evacuation);
        if (rally != null) t.putLong("Rally", rally.asLong());
        t.putLongArray("DangerChunks", dangerChunks.stream().mapToLong(Long::longValue).toArray());
        t.putLong("LastDay", lastDay); t.putLong("LastNeedTick", lastNeedTick);
        t.putLong("LastOfferYear", lastOfferYear); t.putLong("OfferExpires", offerExpires);
        t.putInt("MigrantAdults", migrantAdults); t.putInt("MigrantChildren", migrantChildren);
        t.putLong("LastProcessedYear", lastProcessedYear); t.putLong("ColdUntil", coldUntil);
        t.putLong("ColdFrom", coldFrom);
        t.putLong("FrostDay", frostDay); t.putBoolean("FrostApplied", frostApplied);
        t.putInt("MealsProduced", mealsProduced); t.putInt("MealsEaten", mealsEaten);
        t.putInt("ColdFuelBurned", coldFuelBurned); t.putLong("LastWarningDay", lastWarningDay);
        t.putBoolean("WinterStarted", winterStarted); t.putBoolean("WinterLoss", winterLoss);
        t.put("Events", strings(events)); t.put("Reports", strings(reports));
        t.put("Milestones", strings(milestones));
        t.put("BarterClaims", strings(barterClaims));
        t.put("YearProduced", counts(yearProduced)); t.put("YearConsumed", counts(yearConsumed));
        return t;
    }
    public void load(CompoundTag t) {
        if (t.isEmpty()) return;
        rulesVersion = t.contains("RulesVersion") ? t.getInt("RulesVersion") : 1;
        seasonLength = Math.max(1, Math.min(64, t.contains("SeasonLength") ? t.getInt("SeasonLength")
                : rulesVersion < 2 ? 4 : CommunityContent.seasonDays));
        calendarOffsetDays = t.contains("CalendarOffsetDays") ? t.getLong("CalendarOffsetDays") : 0;
        birthInterval = Math.max(4, Math.min(4096, t.contains("BirthInterval") ? t.getInt("BirthInterval") : 32));
        policy = Set.of("normal", "harvest", "emergency").contains(t.getString("Policy")) ? t.getString("Policy") : "normal";
        foodReserve = limit(t.getInt("FoodReserve")); fuelReserve = limit(t.getInt("FuelReserve"));
        mealTarget = limit(t.getInt("MealTarget")); birthsEnabled = t.getBoolean("BirthsEnabled");
        stewardEnabled=!t.contains("StewardEnabled") || t.getBoolean("StewardEnabled"); wheatPaused=t.getBoolean("WheatPaused");
        snowLimit=t.contains("SnowLimit")?limit(t.getInt("SnowLimit")):64;
        seedRounds=t.contains("SeedRounds")?Math.max(1,Math.min(4,t.getInt("SeedRounds"))):2;
        wheatTarget=t.contains("WheatTarget")?limit(t.getInt("WheatTarget")):512;
        boneTarget=t.contains("BoneTarget")?limit(t.getInt("BoneTarget")):256;
        lifeSlots=t.contains("LifeSlots")?Math.max(0,Math.min(8,t.getInt("LifeSlots"))):4;
        evacuation = t.getBoolean("Evacuation"); rally = t.contains("Rally") ? BlockPos.of(t.getLong("Rally")) : null;
        dangerChunks.clear(); Arrays.stream(t.getLongArray("DangerChunks")).limit(64).forEach(dangerChunks::add);
        lastDay = t.contains("LastDay") ? t.getLong("LastDay") : -1;
        lastNeedTick = t.contains("LastNeedTick") ? t.getLong("LastNeedTick") : -1;
        lastOfferYear = t.contains("LastOfferYear") ? t.getLong("LastOfferYear") : -1;
        offerExpires = t.contains("OfferExpires") ? t.getLong("OfferExpires") : -1;
        migrantAdults = Math.max(0, Math.min(4, t.getInt("MigrantAdults")));
        migrantChildren = Math.max(0, Math.min(2, t.getInt("MigrantChildren")));
        lastProcessedYear = t.contains("LastProcessedYear") ? t.getLong("LastProcessedYear") : -1;
        coldUntil = t.contains("ColdUntil") ? t.getLong("ColdUntil") : -1;
        coldFrom = t.contains("ColdFrom") ? t.getLong("ColdFrom") : -1;
        frostDay = t.contains("FrostDay") ? t.getLong("FrostDay") : -1;
        frostApplied = t.getBoolean("FrostApplied"); mealsProduced = t.getInt("MealsProduced");
        mealsEaten = t.getInt("MealsEaten"); coldFuelBurned = t.getInt("ColdFuelBurned");
        lastWarningDay = t.contains("LastWarningDay") ? t.getLong("LastWarningDay") : -1;
        winterStarted = t.getBoolean("WinterStarted"); winterLoss = t.getBoolean("WinterLoss");
        events.clear(); events.addAll(readStrings(t, "Events", 32));
        reports.clear(); reports.addAll(readStrings(t, "Reports", 8));
        milestones.clear(); milestones.addAll(readStrings(t, "Milestones", 16));
        barterClaims.clear(); barterClaims.addAll(readStrings(t, "BarterClaims", 2));
        yearProduced.clear(); yearConsumed.clear();
        readCounts(t.getCompound("YearProduced"), yearProduced);
        readCounts(t.getCompound("YearConsumed"), yearConsumed);
    }
    private static int limit(int value) { return Math.max(0, Math.min(4096, value)); }
    private static ListTag strings(Collection<String> values) {
        ListTag result = new ListTag(); values.forEach(v -> result.add(StringTag.valueOf(v))); return result;
    }
    private static List<String> readStrings(CompoundTag t, String key, int maximum) {
        ListTag list = t.getList(key, Tag.TAG_STRING); List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maximum, list.size()); i++) result.add(list.getString(i)); return result;
    }
    private static CompoundTag counts(Map<String, Integer> values) {
        CompoundTag t = new CompoundTag(); values.forEach(t::putInt); return t;
    }
    private static void readCounts(CompoundTag t, Map<String, Integer> target) {
        t.getAllKeys().stream().limit(1024).forEach(k -> target.put(k, Math.max(0, t.getInt(k))));
    }
}

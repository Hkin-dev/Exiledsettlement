package com.hkin.exiledsettlement.citizen;

import net.minecraft.nbt.CompoundTag;
import java.util.EnumMap;

/** Independent experience per profession; teacher output has a persistent daily budget. */
public final class CitizenProfessionExperience {
    public static final int MAX_LEVEL = 4;
    public static final int MAX_EXPERIENCE = 185;
    public static final int DAILY_POINTS = 10;
    public static final int TEACHING_TICKS_PER_POINT = 1000;
    private static final int[] COSTS = {10, 25, 50, 100};
    private final EnumMap<CitizenProfession, Integer> experience = new EnumMap<>(CitizenProfession.class);
    private long teachingDay = -1;
    private int pointsToday;
    private int teachingTicks;
    // Deliberately transient: unloading/restarting never credits the time away.
    private long lastTeachingTick = -1;

    public int experience(CitizenProfession profession) { return experience.getOrDefault(profession, 0); }
    public int level(CitizenProfession profession) { return levelFor(experience(profession)); }
    public int progress(CitizenProfession profession) { return progressFor(experience(profession)); }
    public int nextCost(CitizenProfession profession) { return costFor(level(profession)); }
    public double multiplier(CitizenProfession profession) { return multiplierFor(level(profession)); }
    public boolean maxed(CitizenProfession profession) { return experience(profession) >= MAX_EXPERIENCE; }
    public long teachingDay() { return teachingDay; }
    public int pointsToday() { return pointsToday; }
    public int teachingTicks() { return teachingTicks; }

    public static int levelFor(int total) {
        int remaining = Math.max(0, total), level = 0;
        while (level < MAX_LEVEL && remaining >= COSTS[level]) remaining -= COSTS[level++];
        return level;
    }
    public static int progressFor(int total) {
        int remaining = Math.max(0, Math.min(MAX_EXPERIENCE, total));
        for (int cost : COSTS) { if (remaining < cost) return remaining; remaining -= cost; }
        return 0;
    }
    public static int costFor(int level) { return level >= MAX_LEVEL ? 0 : COSTS[Math.max(0, level)]; }
    public static double multiplierFor(int level) { return 1.0 + Math.max(0, Math.min(MAX_LEVEL, level)) * 0.125; }
    public boolean addPoint(CitizenProfession profession) {
        if (maxed(profession)) return false;
        experience.put(profession, experience(profession) + 1);
        return true;
    }

    /** Called only while physically at school, on the existing eight-tick work cadence. */
    public boolean teach(long day, long gameTick) {
        if (day < 0 || day < teachingDay) { interrupt(); return false; }
        if (day > teachingDay) {
            teachingDay = day; pointsToday = 0; teachingTicks = 0; lastTeachingTick = -1;
        }
        long elapsed = lastTeachingTick < 0 ? 0 : gameTick - lastTeachingTick;
        lastTeachingTick = gameTick;
        if (pointsToday >= DAILY_POINTS) return false;
        if (elapsed > 0 && elapsed <= CitizenWorkService.WORK_INTERVAL_TICKS)
            teachingTicks = Math.min(TEACHING_TICKS_PER_POINT, teachingTicks + (int)(elapsed * multiplier(CitizenProfession.TEACHER)));
        return teachingTicks >= TEACHING_TICKS_PER_POINT;
    }
    public void finishPoint(boolean awarded) {
        if (teachingTicks < TEACHING_TICKS_PER_POINT || pointsToday >= DAILY_POINTS) return;
        if (awarded) pointsToday++;
        teachingTicks = 0;
    }
    public void interrupt() { lastTeachingTick = -1; }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var professions = new CompoundTag();
        experience.forEach((profession, points) -> professions.putInt(profession.serializedName(), points));
        tag.put("Professions", professions);
        tag.putLong("TeachingDay", teachingDay);
        tag.putInt("PointsToday", pointsToday);
        tag.putInt("TeachingTicks", teachingTicks);
        return tag;
    }
    public void load(CompoundTag tag) {
        experience.clear();
        var professions = tag.getCompound("Professions");
        for (CitizenProfession profession : CitizenProfession.values()) {
            int points = Math.max(0, Math.min(MAX_EXPERIENCE, professions.getInt(profession.serializedName())));
            if (points > 0) experience.put(profession, points);
        }
        teachingDay = tag.contains("TeachingDay") ? Math.max(-1, tag.getLong("TeachingDay")) : -1;
        pointsToday = Math.max(0, Math.min(DAILY_POINTS, tag.getInt("PointsToday")));
        teachingTicks = Math.max(0, Math.min(TEACHING_TICKS_PER_POINT, tag.getInt("TeachingTicks")));
        lastTeachingTick = -1;
    }
}

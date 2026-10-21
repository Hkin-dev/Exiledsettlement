package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.settlement.CommunityLifeService;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.pathfinder.Path;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Opt-in test-harness observation. Buckets are inclusive, and must never be added
 * together: path calls are children of work/AI, and all are children of game ticks.
 * Formatting and logging happen after measurement, outside every execution bucket.
 */
public final class CompanionMetrics {
    private CompanionMetrics() {}
    public static final boolean ENABLED = Boolean.getBoolean("exiledsettlement.testHarness")
            && Boolean.getBoolean("exiledsettlement.companionMetrics");
    public enum Bucket {
        GUARDIAN_WORK("guardianWork"), PET_WORK("petWork"),
        GUARDIAN_AI("guardianAi"), PET_AI("petAi"),
        GUARDIAN_PATH_CREATE("guardianPathCreate"), PET_PATH_CREATE("petPathCreate"),
        GUARDIAN_PATH_SUBMIT("guardianPathSubmit"), PET_PATH_SUBMIT("petPathSubmit"),
        GUARDIAN_RESIDENT_NAV("guardianResidentNavigation"), PET_RESIDENT_NAV("petResidentNavigation"),
        GUARDIAN_LIFECYCLE("guardianLifecycle"), PET_LIFECYCLE("petLifecycle"),
        LIFECYCLE_EVENT("lifecycleEvent"), ENTITY_CALLBACK("entityCallback");
        final String key;
        Bucket(String key) { this.key = key; }
    }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Bucket[] BUCKETS = Bucket.values();
    private static final Map<ServerLevel, Sample> SAMPLES = new IdentityHashMap<>();
    private static long sequence;
    private static boolean announced;
    private static final class Sample {
        final long[] nanos = new long[BUCKETS.length];
        final long[] calls = new long[BUCKETS.length];
    }
    public static long start() { return ENABLED ? System.nanoTime() : 0; }
    public static long elapsed(long start) { return ENABLED && start != 0 ? System.nanoTime() - start : 0; }
    public static void finish(ServerLevel level, Bucket bucket, long start) {
        if (!ENABLED || start == 0) return;
        long duration = System.nanoTime() - start;
        Sample sample = SAMPLES.computeIfAbsent(level, ignored -> new Sample());
        sample.nanos[bucket.ordinal()] += Math.max(0, duration);
        sample.calls[bucket.ordinal()]++;
    }
    public static Path createPath(ServerLevel level, boolean pet, PathNavigation navigation, BlockPos target, int reach) {
        if (!ENABLED) return navigation.createPath(target, reach);
        long start = start();
        try { return navigation.createPath(target, reach); }
        finally { finish(level, pet ? Bucket.PET_PATH_CREATE : Bucket.GUARDIAN_PATH_CREATE, start); }
    }
    public static Path createPath(ServerLevel level, boolean pet, PathNavigation navigation, Entity target, int reach) {
        if (!ENABLED) return navigation.createPath(target, reach);
        long start = start();
        try { return navigation.createPath(target, reach); }
        finally { finish(level, pet ? Bucket.PET_PATH_CREATE : Bucket.GUARDIAN_PATH_CREATE, start); }
    }
    public static boolean submitPath(ServerLevel level, boolean pet, PathNavigation navigation, Path path, double speed) {
        if (!ENABLED) return navigation.moveTo(path, speed);
        long start = start();
        try { return navigation.moveTo(path, speed); }
        finally { finish(level, pet ? Bucket.PET_PATH_SUBMIT : Bucket.GUARDIAN_PATH_SUBMIT, start); }
    }
    /** Measures the resident command, not a later asynchronous search performed by its shared navigation. */
    public static String navigateResident(ServerLevel level, boolean pet, SettlementCitizenEntity worker,
                                          BlockPos target, double speed) {
        if (!ENABLED) return worker.navigateTo(target, speed);
        long start = start();
        try { return worker.navigateTo(target, speed); }
        finally { finish(level, pet ? Bucket.PET_RESIDENT_NAV : Bucket.GUARDIAN_RESIDENT_NAV, start); }
    }
    public static void movePetCaretaker(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity worker,
                                       BlockPos target, CitizenActivity activity, String reason) {
        if (!ENABLED) { CommunityLifeService.move(citizen, worker, target, activity, reason); return; }
        long start = start();
        try { CommunityLifeService.move(citizen, worker, target, activity, reason); }
        finally { finish(level, Bucket.PET_RESIDENT_NAV, start); }
    }
    public static boolean petStorage(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity worker,
                                     BuildingRecord source, Item item, CitizenActivity activity) {
        if (!ENABLED) return CommunityLifeService.atStorage(level, citizen, worker, source, item, activity);
        long start = start();
        try { return CommunityLifeService.atStorage(level, citizen, worker, source, item, activity); }
        finally { finish(level, Bucket.PET_RESIDENT_NAV, start); }
    }
    public static void flush(MinecraftServer server) {
        if (!ENABLED) return;
        if (!announced) {
            announced = true;
            LOGGER.info("COMPANION_TIMING_META {}", "{\"version\":1,\"unit\":\"ns\",\"inclusiveBuckets\":true,"
                    + "\"pathCallsAreNested\":true,\"logTimeExcluded\":true,"
                    + "\"residentNavigationIsCommandOnly\":true,"
                    + "\"initialization\":\"use sample/serverTick/gameTime and test fixture event timestamps\"}");
        }
        StringBuilder json = new StringBuilder(1200);
        json.append("{\"sample\":").append(sequence++).append(",\"serverTick\":").append(server.getTickCount())
                .append(",\"phase20\":").append(Math.floorMod(server.getTickCount(), 20)).append(",\"levels\":[");
        boolean firstLevel = true;
        for (ServerLevel level : server.getAllLevels()) {
            if (!firstLevel) json.append(',');
            firstLevel = false;
            Sample sample = SAMPLES.remove(level);
            json.append("{\"dimension\":\"").append(level.dimension().location()).append("\",\"gameTime\":")
                    .append(level.getGameTime()).append(",\"dayTime\":").append(level.getDayTime())
                    .append(",\"buckets\":{");
            boolean firstBucket = true;
            if (sample != null) {
                for (Bucket bucket : BUCKETS) {
                    int i = bucket.ordinal();
                    if (sample.calls[i] == 0) continue;
                    if (!firstBucket) json.append(',');
                    firstBucket = false;
                    json.append('"').append(bucket.key).append("\":{\"ns\":").append(sample.nanos[i])
                            .append(",\"calls\":").append(sample.calls[i]).append('}');
                }
            }
            json.append("},\"pathRequests\":")
                    .append(sample == null ? 0 : sample.calls[Bucket.GUARDIAN_PATH_CREATE.ordinal()]
                            + sample.calls[Bucket.PET_PATH_CREATE.ordinal()])
                    .append(",\"pathSubmissions\":")
                    .append(sample == null ? 0 : sample.calls[Bucket.GUARDIAN_PATH_SUBMIT.ordinal()]
                            + sample.calls[Bucket.PET_PATH_SUBMIT.ordinal()])
                    .append('}');
        }
        json.append("]}");
        LOGGER.info("COMPANION_TIMING {}", json);
    }
    public static void stop(MinecraftServer server) {
        if (!ENABLED) return;
        if (!SAMPLES.isEmpty()) flush(server);
        LOGGER.info("COMPANION_TIMING_END {}", "{\"samples\":" + sequence + ",\"serverTick\":" + server.getTickCount() + "}");
        SAMPLES.clear(); sequence = 0; announced = false;
    }
}

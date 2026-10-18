package com.hkin.exiledsettlement.terrain;
import net.neoforged.neoforge.common.ModConfigSpec;
public final class TerrainConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DEPTH, MARGIN, TRANSITION, ACTIONS_PER_WORKER;
    public static final ModConfigSpec.IntValue ROAD_CURVE_RADIUS;
    public static final ModConfigSpec.IntValue PUBLIC_CLIFF_RISE, PUBLIC_GRADE_RUN;
    public static final ModConfigSpec.IntValue NIGHT_PLANNING_SLICES;
    public static final ModConfigSpec.IntValue OPTIONAL_PENDING_BLOCKS;
    public static final ModConfigSpec.IntValue PUBLIC_SCAN_COLUMNS, PUBLIC_SAMPLE_COLUMNS, PUBLIC_PUBLISH_COLUMNS;
    public static final ModConfigSpec.DoubleValue ROAD_CURVE_EXTRA_COST;
    public static final ModConfigSpec.DoubleValue PUBLIC_TERRAIN_WEIGHT, PUBLIC_WALL_WEIGHT, PUBLIC_CHECKPOINT_WEIGHT, PUBLIC_ROAD_WEIGHT;
    static {var b=new ModConfigSpec.Builder();b.push("settlementTerrain");
        DEPTH=b.comment("Retired compatibility setting, ignored by public works. Saved plans keep their frozen targets.").defineInRange("engineeringDepth",6,1,12);
        NIGHT_PLANNING_SLICES=b.comment("Maximum mathematical slices per tick at night (daytime: 4), sharing a 2ms soft budget. Survey and publication run once each tick. No extra resident work or world edits.").defineInRange("nightPlanningSlices",4,1,8);
        PUBLIC_SCAN_COLUMNS=b.comment("Maximum public boundary columns inspected per tick; also limited by the shared 2ms soft budget.").defineInRange("publicScanColumnsPerTick",1024,32,4096);
        PUBLIC_SAMPLE_COLUMNS=b.comment("Maximum public height columns sampled per tick; also limited by the shared 2ms soft budget.").defineInRange("publicSampleColumnsPerTick",256,32,1024);
        PUBLIC_PUBLISH_COLUMNS=b.comment("Maximum solved public columns checked for resident work per tick. Shared 2ms soft budget and unfinished-block admission limit still apply.").defineInRange("publicPublishColumnsPerTick",64,16,256);
        OPTIONAL_PENDING_BLOCKS=b.comment("Maximum distinct unfinished blocks released for public grading and automatic road work. Building preparation is independent. Existing work is preserved and drains before further publication.").defineInRange("optionalPendingBlocks",1000,128,100000);
        MARGIN=b.comment("Usable outdoor platform margin, in blocks.").defineInRange("platformMargin",2,1,4);
        TRANSITION=b.comment("Maximum terraced transition reach beyond the platform, in blocks.").defineInRange("transitionReach",6,2,12);
        ACTIONS_PER_WORKER=b.comment("Remaining actions per suggested worker, a scheduling weight.").defineInRange("actionsPerSuggestedWorker",128,1,Integer.MAX_VALUE);
        ROAD_CURVE_RADIUS=b.comment("Local road corner rounding reach in blocks. 0 disables rounding; narrow corners remain unchanged. This is not a road size limit.").defineInRange("roadCurveRadius",8,0,16);
        PUBLIC_CLIFF_RISE=b.comment("Reference terrain rise between adjacent columns that separates public grading regions, in blocks.").defineInRange("publicCliffRise",3,2,16);
        PUBLIC_GRADE_RUN=b.comment("Minimum continuous horizontal run per one block of rise inside a grading region. Conflicting fixed levels form retaining boundaries.").defineInRange("publicGradeRun",4,2,16);
        PUBLIC_TERRAIN_WEIGHT=b.comment("Retired compatibility key, ignored. Original terrain never attracts the solved height.").defineInRange("publicTerrainWeight",0D,0D,4D);
        PUBLIC_WALL_WEIGHT=b.comment("Retaining walls are structural boundaries, never height sources. Fixed at zero.").defineInRange("publicWallWeight",0D,0D,0D);
        PUBLIC_CHECKPOINT_WEIGHT=b.comment("Relative attraction to the nearest reachable fixed checkpoint, divided by (distance + 1)^2. Fixed checkpoint cells remain exact.").defineInRange("publicCheckpointWeight",8,.01,100);
        PUBLIC_ROAD_WEIGHT=b.comment("Relative attraction to the nearest reachable registered, actual road surface, divided by (distance + 1)^2. Automatic road grades remain adjustable.").defineInRange("publicRoadWeight",6,.01,100);
        ROAD_CURVE_EXTRA_COST=b.comment("Permitted extra local weighted pavement/earthwork cost for a gentler bend, plus two raster cells. An aesthetic preference, not a road quota.").defineInRange("roadCurveExtraCost",.25,0,1);
        b.pop();SPEC=b.build();}
    private TerrainConfig(){}
}

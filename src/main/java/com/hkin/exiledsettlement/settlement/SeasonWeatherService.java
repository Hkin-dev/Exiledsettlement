package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** Real, server-owned snow using vanilla random precipitation columns. No camera-centered terrain overlay. */
public final class SeasonWeatherService {
    private SeasonWeatherService() { }
    private static final TagKey<Block> SUPPORTS = TagKey.create(Registries.BLOCK, ExiledSettlementMod.id("seasonal_snow_supports"));
    private static final TagKey<Block> EXCLUDED = TagKey.create(Registries.BLOCK, ExiledSettlementMod.id("seasonal_snow_excluded"));
    private static final Map<ServerLevel, Runtime> RUNTIMES = new WeakHashMap<>();
    private static final Map<com.hkin.exiledsettlement.building.BuildingRecord, List<com.hkin.exiledsettlement.blueprint.BlueprintEntrance>> ENTRANCES = new WeakHashMap<>();
    private static final class Runtime {
        boolean internal, raining, weatherSeen;
        int season = -1, writes;
        long tick = -1;
    }
    private static Runtime runtime(ServerLevel level) { return RUNTIMES.computeIfAbsent(level, ignored -> new Runtime()); }
    private static boolean budget(ServerLevel level) {
        var state = runtime(level);
        if (state.tick != level.getGameTime()) { state.tick = level.getGameTime(); state.writes = 0; }
        return state.writes < SeasonWeatherSettings.writeBudget;
    }
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && level.dimension().equals(Level.OVERWORLD)) {
            var runtime = RUNTIMES.get(server);
            if (runtime != null && !runtime.internal) SeasonSnowData.get(server).forget(pos);
        }
    }
    /** Called before vanilla precipitation; native cold biomes keep their normal behavior. */
    public static boolean precipitationColumn(ServerLevel level, BlockPos column) {
        if (!level.dimension().equals(Level.OVERWORLD) || !level.isRaining() || !level.hasChunkAt(column) || !budget(level)) return false;
        var calendar = WorldSeasonData.get(level);
        var pos = surface(level, column);
        var biome = level.getBiome(pos).value();
        // Native high-altitude snow remains vanilla-owned, including naturally frozen water.
        if (!SeasonClimate.seasonalSnow(biome, calendar.season(level.getDayTime()))
                || biome.getPrecipitationAt(pos) != net.minecraft.world.level.biome.Biome.Precipitation.RAIN) return false;
        return accumulate(level, pos);
    }
    public static BlockPos surface(ServerLevel level, BlockPos column) {
        var pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
        return level.getBlockState(pos.below()).is(Blocks.SNOW) ? pos.below() : pos;
    }
    private static boolean protectedPassage(ServerLevel level, BlockPos pos) {
        if (RoadSpaceData.get(level).contains(pos.below())) return true;
        var settlement = SettlementSavedData.get(level).findContaining(pos).orElse(null);
        if (settlement == null) return false;
        for (var building : settlement.buildings()) if (building.active()) {
            if (!com.hkin.exiledsettlement.blueprint.BlueprintTemplates.supports(building.type())) continue;
            var entrances = ENTRANCES.computeIfAbsent(building, b -> com.hkin.exiledsettlement.blueprint.BlueprintTemplates.createForBuilding(b).entrances());
            for (var entrance : entrances) {
                var entry = entrance.position();
                if (Math.abs(pos.getY() - entry.getY()) <= 1 && Math.abs(pos.getX() - entry.getX()) <= 1 && Math.abs(pos.getZ() - entry.getZ()) <= 1) return true;
            }
            for (var point : building.servicePositions()) if (point.distSqr(pos) <= 4) return true;
        }
        return false;
    }
    public static boolean accumulate(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD) || !level.isRaining() || !level.hasChunkAt(pos)
                || !SeasonClimate.seasonalSnow(level.getBiome(pos).value(), WorldSeasonData.get(level).season(level.getDayTime()))
                || !budget(level) || !level.getWorldBorder().isWithinBounds(pos)
                || level.getBrightness(LightLayer.BLOCK, pos) >= 10 || !pos.equals(surface(level, pos))) return false;
        int limit = Math.min(2, level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT));
        if (limit <= 0) return false;
        var snow = SeasonSnowData.get(level); var current = level.getBlockState(pos);
        int old = snow.layers(pos);
        if (current.is(Blocks.SNOW)) {
            if (old == 0 || current.getValue(SnowLayerBlock.LAYERS) != old || old >= limit) return false;
        } else if (!current.isAir() || old > 0 || snow.size() >= SeasonSnowData.MAX_RECORDS) return false;
        var support = level.getBlockState(pos.below());
        if (!support.is(SUPPORTS) || support.is(EXCLUDED) || !support.getFluidState().isEmpty()
                || protectedPassage(level, pos) || !Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) return false;
        var next = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, old + 1);
        if (!level.noCollision(new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX()+1, pos.getY()+(old+1)/8.0, pos.getZ()+1))) return false;
        var snapshot = BlockSnapshot.create(level.dimension(), level, pos, Block.UPDATE_ALL);
        var runtime = runtime(level); runtime.internal = true;
        try {
            if (!level.setBlock(pos, next, Block.UPDATE_ALL)) return false;
            if (EventHooks.onBlockPlace(null, snapshot, Direction.UP)) { snapshot.restore(Block.UPDATE_ALL); return false; }
            snow.remember(pos, old + 1); runtime.writes++; return true;
        } finally { runtime.internal = false; }
    }
    /** Only owned, unedited snow melts; native and player-created snow is never enrolled. */
    public static boolean melt(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !budget(level)) return false;
        var snow = SeasonSnowData.get(level); int count = snow.layers(pos);
        if (count == 0) return false;
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.SNOW) || state.getValue(SnowLayerBlock.LAYERS) != count) { snow.forget(pos); return false; }
        var event = new BlockEvent.BreakEvent(level, pos, state, FakePlayerFactory.getMinecraft(level));
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return false;
        var runtime = runtime(level); runtime.internal = true;
        try {
            var next = count == 1 ? Blocks.AIR.defaultBlockState() : state.setValue(SnowLayerBlock.LAYERS, count - 1);
            if (!level.setBlock(pos, next, Block.UPDATE_ALL)) return false;
            if (count == 1) snow.forget(pos); else snow.remember(pos, count - 1);
            runtime.writes++; return true;
        } finally { runtime.internal = false; }
    }
    public static void tickLevel(ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        var calendar = WorldSeasonData.get(level);
        weather(level, calendar);
        if (level.getGameTime() % 20 != 0) return;
        processMelt(level);
    }
    public static void processMelt(ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        var calendar = WorldSeasonData.get(level);
        var snow = SeasonSnowData.get(level);
        long time = calendar.time(level.getDayTime()); int season = calendar.season(level.getDayTime());
        long springAge = season == 0 ? (SeasonCalendar.dayWithin(time, calendar.days())-1)*24000L + Math.floorMod(time, 24000L) : Long.MAX_VALUE;
        // The fixed budget also bounds unloaded/stale entries. No chunk forcing and no all-record sweep.
        for (int i = 0, total = Math.min(SeasonWeatherSettings.meltBudget, snow.size()); i < total; i++) {
            Long key = snow.next(); if (key == null) break;
            var pos = BlockPos.of(key);
            if (!level.hasChunkAt(pos) || snow.layers(pos) == 0) continue;
            if (!level.getBlockState(pos).is(Blocks.SNOW)) { snow.forget(pos); continue; }
            if (season != 3 && springAge >= Math.floorMod(Long.hashCode(key), 48000)) melt(level, pos);
        }
    }
    private static void weather(ServerLevel level, WorldSeasonData calendar) {
        var state = runtime(level); boolean rain = level.getLevelData().isRaining(); int season = calendar.season(level.getDayTime());
        boolean transition = !state.weatherSeen || state.raining != rain || state.season != season;
        state.weatherSeen = true; state.raining = rain; state.season = season;
        if (!transition || !level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)
                || ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).getClearWeatherTime() > 0) return;
        int minimum = rain ? SeasonWeatherSettings.rainMin[season] : SeasonWeatherSettings.clearMin[season];
        int maximum = rain ? SeasonWeatherSettings.rainMax[season] : SeasonWeatherSettings.clearMax[season];
        int duration = minimum + level.random.nextInt(maximum-minimum+1);
        level.setWeatherParameters(0, duration, rain, rain && level.isThundering());
    }
    public static String diagnostics(ServerLevel level) {
        var snow = SeasonSnowData.get(level);
        return "季节积雪 " + snow.size() + "/" + SeasonSnowData.MAX_RECORDS + "；每tick写入上限 " + SeasonWeatherSettings.writeBudget
                + "；每秒融雪检查 " + SeasonWeatherSettings.meltBudget + (snow.size() >= SeasonSnowData.MAX_RECORDS ? "；登记已满，暂停新增" : "");
    }
}

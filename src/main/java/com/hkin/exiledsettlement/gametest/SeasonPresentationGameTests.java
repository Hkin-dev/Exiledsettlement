package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.network.SeasonSnapshotPayload;
import com.hkin.exiledsettlement.settlement.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonPresentationGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void calendarBoundariesAndSavedLength(GameTestHelper h) {
        h.assertTrue(SeasonCalendar.season(95999, 4) == 0 && SeasonCalendar.remainingTicks(95999, 4) == 1, "Spring ended early");
        h.assertTrue(SeasonCalendar.season(96000, 4) == 1 && SeasonCalendar.dayWithin(96000, 4) == 1, "Summer boundary wrong");
        h.assertTrue(SeasonCalendar.season(192000, 4) == 2 && SeasonCalendar.season(288000, 4) == 3, "Autumn/winter boundary wrong");
        h.assertTrue(SeasonCalendar.season(384000, 4) == 0 && SeasonCalendar.year(384000, 4) == 2, "Year rollover wrong");
        h.assertTrue(SeasonCalendar.countdown(95999, 4).contains("0.01") && SeasonCalendar.season(-1, 4) == 0, "Countdown/rewind invalid");
        var s = new SettlementRecord(UUID.randomUUID(), "Calendar", new BlockPos(190000, 10, 190000), UUID.randomUUID());
        s.community().seasonLength = 45;
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.community().seasonLength == 45 && SeasonCalendar.season(45 * 24000, loaded.community().seasonLength) == 1,
                "Presentation ignored saved season length");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void snapshotRoundTripAndBounds(GameTestHelper h) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            var input = new SeasonSnapshotPayload("minecraft:overworld", "测试聚落", 30, 91, false);
            SeasonSnapshotPayload.STREAM_CODEC.encode(buffer, input);
            h.assertTrue(input.equals(SeasonSnapshotPayload.STREAM_CODEC.decode(buffer)), "Calendar authority/frozen flag lost");
            buffer.clear(); buffer.writeUtf("minecraft:overworld"); buffer.writeUtf("test"); buffer.writeVarInt(0); buffer.writeBoolean(true);
            boolean rejected = false;
            try { SeasonSnapshotPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "Invalid network calendar accepted");
        } finally { buffer.release(); }
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void transitionNoticeOnceAndNoReplay(GameTestHelper h) {
        var level = h.getLevel(); long original = level.getDayTime();
        var s = new SettlementRecord(UUID.randomUUID(), "Notices", new BlockPos(190000, 10, 190000), UUID.randomUUID());
        s.community().seasonLength = 4;
        var data = new SettlementSavedData(); s.community().lastDay = 11;
        try {
            level.setDayTime(12 * 24000L); CommunityLifeService.tick(level, data, s);
            long count = s.community().events.stream().filter(v -> v.contains("冬季 1/4日")).count();
            h.assertTrue(count == 1 && CommunityLifeService.winter(s, 12), "Winter notice and simulation disagree");
            CommunityLifeService.tick(level, data, s);
            h.assertTrue(s.community().events.stream().filter(v -> v.contains("冬季 1/4日")).count() == 1, "Notice repeated each tick");
            level.setDayTime(0); CommunityLifeService.tick(level, data, s);
            h.assertTrue(s.community().events.stream().noneMatch(v -> v.contains("春季 1/4日")), "Rewind replayed transition");
        } finally { level.setDayTime(original); }
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void climatePaletteAndSnowSurfaceExclusions(GameTestHelper h) {
        int green = 0x58A040;
        h.assertTrue(SeasonVisualPalette.tint(green, 2, true, 0.8F) != green, "Temperate autumn unchanged");
        h.assertTrue(SeasonVisualPalette.tint(green, 2, true, 2F) == green
                && SeasonVisualPalette.tint(green, 3, false, -0.5F) == green
                && SeasonVisualPalette.tint(green, -1, true, 0.8F) == green, "Desert/cold/disabled palette changed");
        h.assertTrue(SeasonVisualPalette.supportsSnow(Blocks.GRASS_BLOCK.defaultBlockState())
                && SeasonVisualPalette.supportsSnow(Blocks.OAK_LEAVES.defaultBlockState()), "Natural surfaces excluded");
        for (var block : new net.minecraft.world.level.block.Block[] {Blocks.WATER, Blocks.FARMLAND, Blocks.WHEAT,
                Blocks.DIRT_PATH, Blocks.CHEST, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS})
            h.assertTrue(!SeasonVisualPalette.supportsSnow(block.defaultBlockState()), "Visual snow covers protected surface: " + block);
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void thirtyDayDefaultAndLegacyPhaseMigration(GameTestHelper h) {
        h.assertTrue(CommunityContent.seasonDays == 30 && new CommunityState().seasonLength == 30, "Default is not 30 days");
        h.assertTrue(SeasonCalendar.season(30L * 24000 - 1, 30) == 0
                && SeasonCalendar.season(30L * 24000, 30) == 1
                && SeasonCalendar.season(90L * 24000, 30) == 3
                && SeasonCalendar.year(120L * 24000, 30) == 2, "30-day season boundaries wrong");
        for (int oldLength : new int[] {1, 4, 7, 29}) for (int year = 0; year < 3; year++)
            for (int season = 0; season < 4; season++) for (int progress = 0; progress < oldLength; progress++) {
                long day = (year * 4L + season) * oldLength + progress;
                var state = new CommunityState(); state.rulesVersion = 1; state.seasonLength = oldLength;
                h.assertTrue(state.migrateCalendar(day) && state.seasonLength == 30, "Legacy short season did not upgrade");
                long time = state.calendarTime(day * 24000);
                h.assertTrue(SeasonCalendar.year(time, 30) == year + 1 && SeasonCalendar.season(time, 30) == season,
                        "Migration changed year or season");
                h.assertTrue(SeasonCalendar.dayWithin(time, 30) == progress * 30 / oldLength + 1, "Migration lost day progress");
                long offset = state.calendarOffsetDays;
                h.assertTrue(!state.migrateCalendar(day + 1) && state.calendarOffsetDays == offset, "Migration repeated");
            }
        var longer = new CommunityState(); longer.rulesVersion = 1; longer.seasonLength = 45;
        longer.migrateCalendar(80);
        h.assertTrue(longer.seasonLength == 45 && longer.calendarOffsetDays == 0, "Existing longer season was shortened");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="season_presentation")
    public static void legacyCalendarKeepsColdSaveAndLifeTimers(GameTestHelper h) {
        var level = h.getLevel(); long original = level.getDayTime();
        var s = new SettlementRecord(UUID.randomUUID(), "Upgrade", new BlockPos(190000, 10, 190000), UUID.randomUUID());
        var c = s.citizens().iterator().next(); c.life().ageDays = 71;
        var state = s.community(); state.rulesVersion = 1; state.seasonLength = 4;
        state.lastDay = 14; state.lastProcessedYear = 0; state.coldFrom = 12; state.coldUntil = 16;
        state.frostDay = 15; state.offerExpires = 17; state.mealsProduced = 12; state.winterStarted = true;
        try {
            level.setDayTime(14 * 24000L); CommunityLifeService.tick(level, new SettlementSavedData(), s);
            h.assertTrue(state.seasonLength == 30 && state.calendarOffsetDays == 91 && CommunityLifeService.winter(s, 14), "Winter jumped on upgrade");
            h.assertTrue(CommunityLifeService.severeCold(state, 14) && state.coldUntil == 29
                    && !CommunityLifeService.severeCold(state, 29) && !CommunityLifeService.winter(s, 29), "Cold end no longer matches spring");
            h.assertTrue(c.life().ageDays == 71 && state.frostDay == 15 && state.offerExpires == 17
                    && state.mealsProduced == 12 && state.lastProcessedYear == 0, "Upgrade fabricated life or annual accounting");
            var loaded = SettlementRecord.load(s.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(loaded.community().calendarOffsetDays == 91 && loaded.community().rulesVersion == 2
                    && CommunityLifeService.winter(loaded, 14), "Reload lost upgraded calendar");
            h.assertTrue(SeasonCalendar.season(loaded.community().calendarTime(level.getDayTime()), loaded.community().seasonLength)
                    == CommunityLifeService.season(loaded, 14), "HUD and simulation disagree");
        } finally { level.setDayTime(original); }
        h.succeed();
    }
}

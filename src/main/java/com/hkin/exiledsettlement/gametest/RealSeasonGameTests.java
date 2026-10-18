package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("exiledsettlement_seasons")
@PrefixGameTestTemplate(false)
public final class RealSeasonGameTests {
    /** Every weather test restores shared authority and vanilla weather, including failed assertions. */
    private static final class Fixture implements AutoCloseable {
        final ServerLevel level; final WorldSeasonData calendar; final BlockPos pos;
        final int days, snowLimit, clear, rainTime, thunderTime;
        final long offset, worldTime; final boolean raining, thunder;
        final float rainLevel, thunderLevel;
        final List<BlockPos> touched=new ArrayList<>();
        Fixture(GameTestHelper h) {
            level=h.getLevel();calendar=WorldSeasonData.get(level);days=calendar.days();offset=calendar.offsetDays();worldTime=level.getDayTime();
            var data=(ServerLevelData)level.getLevelData();clear=data.getClearWeatherTime();rainTime=data.getRainTime();thunderTime=data.getThunderTime();
            raining=data.isRaining();thunder=data.isThundering();rainLevel=level.getRainLevel(1);thunderLevel=level.getThunderLevel(1);
            snowLimit=level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT);
            level.getGameRules().getRule(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT).set(1,level.getServer());
            calendar.configure(30,94-CommunityLifeService.day(level));
            level.setWeatherParameters(0,12000,true,false);level.setRainLevel(1);level.setThunderLevel(0);
            pos=h.absolutePos(new BlockPos(8,112,8));ground(pos,Blocks.GRASS_BLOCK);
        }
        void ground(BlockPos p,Block block) {
            touched.add(p);level.setBlock(p.below(),block.defaultBlockState(),3);level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
        }
        @Override public void close() {
            for(var p:touched) {level.setBlock(p,Blocks.AIR.defaultBlockState(),3);level.setBlock(p.below(),Blocks.AIR.defaultBlockState(),3);SeasonSnowData.get(level).forget(p);}
            calendar.configure(days,offset);level.setDayTime(worldTime);
            level.setWeatherParameters(clear,rainTime,raining,thunder);((ServerLevelData)level.getLevelData()).setThunderTime(thunderTime);
            level.setRainLevel(rainLevel);level.setThunderLevel(thunderLevel);
            level.getGameRules().getRule(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT).set(snowLimit,level.getServer());
        }
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void sharedCalendarAlignsWithoutReplayingConsumptionAndPersists(GameTestHelper h) {
        var calendar=new WorldSeasonData();calendar.configure(30,91);
        var a=new CommunityState();a.seasonLength=4;a.calendarOffsetDays=13;a.lastDay=14;a.lastNeedTick=999;a.mealsEaten=7;a.yearConsumed.put("bread",12);a.offerExpires=20;a.frostDay=17;
        var b=new CommunityState();b.seasonLength=45;b.calendarOffsetDays=0;
        h.assertTrue(calendar.bind(a,14)&&calendar.bind(b,14),"Legacy communities did not align");
        h.assertTrue(a.calendarDay(14)==b.calendarDay(14)&&a.seasonLength==b.seasonLength&&a.rulesVersion==3,"Communities retain separate seasons");
        h.assertTrue(a.lastDay==14&&a.lastNeedTick==999&&a.mealsEaten==7&&a.yearConsumed.get("bread")==12&&a.offerExpires==20&&a.frostDay==17,"Calendar alignment replayed/erased life or short-lived opportunities");
        h.assertTrue(a.lastProcessedYear==a.lastOfferYear&&!calendar.bind(a,15),"Alignment repeated or reopened yearly opportunities");
        var loaded=WorldSeasonData.load(calendar.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(loaded.days()==30&&loaded.offsetDays()==91&&loaded.season(14*24000L)==3,"Shared winter lost on reload");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void seasonalPrecipitationRespectsBiomeAndSummer(GameTestHelper h) {
        var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);var pos=new BlockPos(0,64,0);
        h.assertTrue(SeasonClimate.precipitation(biomes.get(Biomes.PLAINS),pos,3)==Biome.Precipitation.SNOW,"Temperate winter remains rainy");
        h.assertTrue(SeasonClimate.precipitation(biomes.get(Biomes.PLAINS),pos,1)==Biome.Precipitation.RAIN,"Summer became snowy");
        h.assertTrue(SeasonClimate.precipitation(biomes.get(Biomes.DESERT),pos,3)==Biome.Precipitation.NONE,"Dry desert receives winter precipitation");
        h.assertTrue(SeasonClimate.precipitation(biomes.get(Biomes.JUNGLE),pos,3)==Biome.Precipitation.RAIN,"Hot jungle became temperate snowland");
        h.assertTrue(SeasonClimate.precipitation(biomes.get(Biomes.SNOWY_PLAINS),pos,1)==Biome.Precipitation.SNOW,"Native cold climate was replaced");
        int original=0x58A040;
        h.assertTrue(SeasonVisualPalette.tintTransition(original,2,0,true,.8F)==SeasonVisualPalette.tint(original,2,true,.8F)
            &&SeasonVisualPalette.tintTransition(original,2,15,true,.8F)!=SeasonVisualPalette.tint(original,2,true,.8F),"Season-end foliage transition does not progress");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void vanillaPrecipitationCreatesPersistentSnowAndClearWeatherDoesNot(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var level=f.level;var snow=SeasonSnowData.get(level);
            level.tickPrecipitation(f.pos);
            h.assertTrue(level.getBlockState(f.pos).is(Blocks.SNOW)&&snow.layers(f.pos)==1,"Real precipitation hook did not place owned snow");
            var loaded=SeasonSnowData.load(snow.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            h.assertTrue(loaded.layers(f.pos)==1,"Real seasonal snow origin lost on reload");
            level.setWeatherParameters(12000,0,false,false);level.setRainLevel(0);
            var next=f.pos.east();f.ground(next,Blocks.GRASS_BLOCK);level.tickPrecipitation(next);
            h.assertTrue(level.getBlockState(next).isAir()&&level.getBlockState(f.pos).is(Blocks.SNOW),"Clear weather spawns snow or deletes existing snow");
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void springMeltsOwnedSnowButPreservesPlayerEditedAndNativeSnow(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var edited=f.pos.east();var nativeSnow=f.pos.east(2);f.ground(edited,Blocks.GRASS_BLOCK);f.ground(nativeSnow,Blocks.GRASS_BLOCK);
            h.assertTrue(SeasonWeatherService.accumulate(f.level,f.pos)&&SeasonWeatherService.accumulate(f.level,edited),"Snow setup failed");
            f.level.setBlock(edited,Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,2),3);
            f.level.setBlock(nativeSnow,Blocks.SNOW.defaultBlockState(),3);
            h.assertTrue(SeasonSnowData.get(f.level).layers(edited)==0,"Player edits retain seasonal ownership");
            f.calendar.configure(30,2-CommunityLifeService.day(f.level));SeasonWeatherService.processMelt(f.level);
            h.assertTrue(f.level.getBlockState(f.pos).isAir()&&SeasonSnowData.get(f.level).layers(f.pos)==0,"Spring did not melt owned snow");
            h.assertTrue(f.level.getBlockState(edited).getValue(SnowLayerBlock.LAYERS)==2&&f.level.getBlockState(nativeSnow).is(Blocks.SNOW),"Spring erased player/native snow");
            var unloaded=new BlockPos(30000000,80,30000000);var snow=SeasonSnowData.get(f.level);snow.remember(unloaded,1);
            SeasonWeatherService.processMelt(f.level);h.assertTrue(!f.level.hasChunkAt(unloaded)&&snow.layers(unloaded)==1,"Melt forced an unloaded chunk");snow.forget(unloaded);
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void snowProtectsCropsRoadsWaterAndIndoorSpaceButCoversRoof(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.pos;var level=f.level;
            level.setBlock(p.below(),Blocks.FARMLAND.defaultBlockState(),3);level.setBlock(p,Blocks.WHEAT.defaultBlockState(),3);
            h.assertTrue(!SeasonWeatherService.accumulate(level,p)&&level.getBlockState(p).is(Blocks.WHEAT),"Snow overwrote crops");
            level.setBlock(p,Blocks.AIR.defaultBlockState(),3);level.setBlock(p.below(),Blocks.DIRT_PATH.defaultBlockState(),3);
            h.assertTrue(!SeasonWeatherService.accumulate(level,p),"Snow covers dirt path");
            level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);RoadSpaceData.get(level).addAll(List.of(p.below()));
            try {h.assertTrue(!SeasonWeatherService.accumulate(level,p),"Snow covers registered road");}finally {RoadSpaceData.get(level).remove(p.below());}
            level.setBlock(p.below(),Blocks.WATER.defaultBlockState(),3);level.tickPrecipitation(p);
            h.assertTrue(level.getBlockState(p.below()).is(Blocks.WATER)&&level.getBlockState(p).isAir(),"Seasonal snow froze/replaced water");
            level.setBlock(p.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);var roof=p.above(4);f.ground(roof.above(),Blocks.OAK_PLANKS);
            h.assertTrue(!SeasonWeatherService.accumulate(level,p),"Indoor space gets snow");
            boolean roofPlaced=SeasonWeatherService.accumulate(level,roof.above());
            h.assertTrue(roofPlaced,"Roof cannot accumulate: sky="+level.canSeeSky(roof.above())+" survive="+Blocks.SNOW.defaultBlockState().canSurvive(level,roof.above())
                +" support="+level.getBlockState(roof)+" target="+level.getBlockState(roof.above())+" light="+level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,roof.above())
                +" rain="+level.isRaining()+" season="+f.calendar.season(level.getDayTime())+" rule="+level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT));
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void snowProtectsRegisteredBuildingEntrance(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var data=SettlementSavedData.get(f.level);var s=data.createOrRecover(f.pos.west(20),UUID.randomUUID(),"Snow entrance").value();
            try {
                var building=s.addBuilding(BuildingType.HOUSE_2,f.pos.east(12));
                var entry=BlueprintTemplates.createForBuilding(building).entrances().getFirst().position();f.ground(entry,Blocks.GRASS_BLOCK);
                h.assertTrue(!SeasonWeatherService.accumulate(f.level,entry)&&f.level.getBlockState(entry).isAir(),"Snow blocks actual registered entrance");
            }finally {s.deactivate();data.touch();}
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void snowPlacementAndMeltingHonorProtectionAndWriteBudget(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            Consumer<BlockEvent.EntityPlaceEvent> place=e->{if(e.getLevel()==f.level&&e.getPos().equals(f.pos))e.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(place);
            try {h.assertTrue(!SeasonWeatherService.accumulate(f.level,f.pos)&&f.level.getBlockState(f.pos).isAir()&&SeasonSnowData.get(f.level).layers(f.pos)==0,"Denied snowfall left a block/record");}
            finally {NeoForge.EVENT_BUS.unregister(place);}
            h.assertTrue(SeasonWeatherService.accumulate(f.level,f.pos),"Snow setup failed after protection release");
            Consumer<BlockEvent.BreakEvent> melt=e->{if(e.getLevel()==f.level&&e.getPos().equals(f.pos))e.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(melt);
            try {h.assertTrue(!SeasonWeatherService.melt(f.level,f.pos)&&f.level.getBlockState(f.pos).is(Blocks.SNOW)&&SeasonSnowData.get(f.level).layers(f.pos)==1,"Denied melting lost protected snow");}
            finally {NeoForge.EVENT_BUS.unregister(melt);}
            int original=SeasonWeatherSettings.writeBudget;SeasonWeatherSettings.writeBudget=1;
            try {var p=f.pos.east();f.ground(p,Blocks.GRASS_BLOCK);h.assertTrue(!SeasonWeatherService.accumulate(f.level,p),"Per-tick snow mutation budget exceeded");}
            finally {SeasonWeatherSettings.writeBudget=original;}
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void worldDayCommandIsAdministratorOnlyPreservesClockAndTestsFourSeasons(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var commands=f.level.getServer().getCommands();var player=h.makeMockServerPlayerInLevel();long now=f.level.getDayTime(),gameTime=f.level.getGameTime();
            var node=commands.getDispatcher().getRoot().getChild("exiledsettlement").getChild("season").getChild("worldday");
            h.assertTrue(!node.canUse(player.createCommandSourceStack().withPermission(0)),"World-day testing offered to ordinary player");
            boolean refused=false;try {commands.getDispatcher().execute("exiledsettlement season worldday 91",player.createCommandSourceStack().withPermission(0));}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){refused=true;}
            h.assertTrue(refused&&f.level.getDayTime()==now,"Player changed world day");
            f.calendar.configure(30,0);int[] days={1,31,61,91};
            for(int i=0;i<days.length;i++) {
                int result=commands.getDispatcher().execute("exiledsettlement season worldday "+days[i],f.level.getServer().createCommandSourceStack());
                h.assertTrue(result==1&&f.level.getDayTime()/24000L==days[i]-1&&f.calendar.season(f.level.getDayTime())==i,"World day does not select expected real season");
                h.assertTrue(Math.floorMod(f.level.getDayTime(),24000L)==Math.floorMod(now,24000L)&&f.level.getGameTime()==gameTime,"World-day command changed time of day or simulation clock");
            }
            for(var level:f.level.getServer().getAllLevels())level.setDayTime(now);
        }catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalStateException(error);}h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_seasons",template="empty",batch="real_seasons")
    public static void seasonalWeatherHonorsRulesExplicitClearAndSeasonalDurations(GameTestHelper h) {
        boolean cycle=h.getLevel().getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
        try(var f=new Fixture(h)) {
            f.level.getGameRules().getRule(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT).set(0,f.level.getServer());
            f.level.tickPrecipitation(f.pos);
            h.assertTrue(f.level.getBlockState(f.pos).isAir(),"Zero snow accumulation rule ignored");
            var data=(ServerLevelData)f.level.getLevelData();
            f.level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,f.level.getServer());
            f.level.setWeatherParameters(0,777,false,false);SeasonWeatherService.tickLevel(f.level);
            h.assertTrue(data.getRainTime()==777&&!data.isRaining(),"Disabled weather cycle was overridden");
            f.level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(true,f.level.getServer());
            f.level.setWeatherParameters(777,888,true,false);SeasonWeatherService.tickLevel(f.level);
            h.assertTrue(data.getClearWeatherTime()==777&&data.getRainTime()==888,"Explicit weather duration was overridden");
            f.calendar.configure(30,30-CommunityLifeService.day(f.level));
            f.level.setWeatherParameters(0,777,false,false);SeasonWeatherService.tickLevel(f.level);
            h.assertTrue(data.getRainTime()>=SeasonWeatherSettings.clearMin[1]&&data.getRainTime()<=SeasonWeatherSettings.clearMax[1]
                &&!data.isRaining(),"Summer weather did not use configured clear durations");
        }finally {h.getLevel().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(cycle,h.getLevel().getServer());}
        h.succeed();
    }
}

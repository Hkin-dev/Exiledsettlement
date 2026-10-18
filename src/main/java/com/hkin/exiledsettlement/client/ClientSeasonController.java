package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.SeasonSnapshotPayload;
import com.hkin.exiledsettlement.settlement.SeasonCalendar;
import com.hkin.exiledsettlement.settlement.SeasonVisualPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Client presentation. Actual weather and persistent snow are server-authoritative. */
public final class ClientSeasonController {
    private ClientSeasonController() { }
    private static SeasonSnapshotPayload snapshot;
    private static ClientLevel snapshotLevel, currentLevel;
    private static int previousSeason = -1;
    // Chunk compilation can query colors on a worker thread. Only this immutable scalar crosses threads.
    private static volatile int visualSeason = -1;
    private static volatile int blendStep;
    public static int visualSeason() { return visualSeason; }
    public static int blendStep() { return blendStep; }
    public static int weatherSeason() {
        var game = Minecraft.getInstance();
        return valid(game) && game.level.dimension().equals(Level.OVERWORLD)
                ? SeasonCalendar.season(SeasonCalendar.shiftedTime(game.level.getDayTime(), snapshot.offsetDays()), snapshot.days()) : -1;
    }

    public static void receive(SeasonSnapshotPayload payload) {
        snapshot = payload; snapshotLevel = Minecraft.getInstance().level;
    }
    private static boolean valid(Minecraft game) {
        return game.level != null && snapshot != null && snapshotLevel == game.level
                && snapshot.dimension().equals(game.level.dimension().location().toString());
    }
    public static void tick() {
        var game = Minecraft.getInstance();
        if (game.level != currentLevel) {
            currentLevel = game.level; previousSeason = -1; blendStep = 0;
            if (snapshotLevel != currentLevel) { snapshot = null; snapshotLevel = null; }
        }
        if (!valid(game) || game.player == null) { updateTint(game, -1); return; }
        int season = SeasonCalendar.season(SeasonCalendar.shiftedTime(game.level.getDayTime(), snapshot.offsetDays()), snapshot.days());
        if (previousSeason >= 0 && previousSeason != season && SeasonClientConfig.HUD.get()) {
            game.gui.setTimes(10, 80, 20);
            game.gui.setSubtitle(Component.literal(SeasonCalendar.hint(season)));
            game.gui.setTitle(Component.literal(SeasonCalendar.name(season) + "季到来"));
        }
        previousSeason = season;
        boolean scenery = SeasonClientConfig.SCENERY.get() && game.level.dimension().equals(Level.OVERWORLD);
        long time = SeasonCalendar.shiftedTime(game.level.getDayTime(), snapshot.offsetDays());
        long fadeTicks = Math.min(3, snapshot.days()) * SeasonCalendar.DAY_TICKS;
        int nextStep = scenery ? (int) Math.max(0, Math.min(15, (fadeTicks - SeasonCalendar.remainingTicks(time, snapshot.days())) * 16 / fadeTicks)) : 0;
        if (blendStep != nextStep) { blendStep = nextStep; if (game.level != null) { game.level.clearTintCaches(); game.levelRenderer.allChanged(); } }
        updateTint(game, scenery ? season : -1);
        if (!scenery || game.isPaused()) return;
        blowingSnow(game);
        if (game.options.particles().get() == net.minecraft.client.ParticleStatus.MINIMAL) return;
        if (season == 2 && game.level.getGameTime() % 3 == 0) fallingLeaves(game);
    }
    private static void updateTint(Minecraft game, int season) {
        if (visualSeason == season) return;
        visualSeason = season;
        if (game.level != null) {
            game.level.clearTintCaches(); game.levelRenderer.allChanged();
        }
    }
    public static float stormIntensity() {
        var game=Minecraft.getInstance();if(!valid(game) || game.player==null || !game.level.isRaining() || weatherSeason()!=3)return 0;
        var at=game.player.blockPosition();
        if(com.hkin.exiledsettlement.settlement.SeasonClimate.precipitation(game.level.getBiome(at).value(),at,3)
                !=net.minecraft.world.level.biome.Biome.Precipitation.SNOW)return 0;
        return com.hkin.exiledsettlement.settlement.BlizzardData.intensity(game.level.getGameTime(),snapshot.stormStart(),snapshot.stormEnd(),snapshot.stormStrength());
    }
    public static float shelteredStormIntensity() {
        var game=Minecraft.getInstance();float power=stormIntensity();
        return game.player!=null && !game.level.canSeeSky(game.player.blockPosition().above())?power*.12F:power;
    }
    private static void blowingSnow(Minecraft game) {
        float power=stormIntensity();if(power<=0 || !SeasonClientConfig.SCENERY.get())return;
        var level=game.level;var center=game.player.position();var random=level.random;
        int particles=game.options.particles().get()==net.minecraft.client.ParticleStatus.MINIMAL?2:Math.round(power*16);
        double vx=Math.cos(snapshot.wind())*.35*power,vz=Math.sin(snapshot.wind())*.35*power;
        for(int i=0;i<particles;i++) {
            double x=center.x+random.nextDouble()*32-16,y=center.y+random.nextDouble()*9,z=center.z+random.nextDouble()*32-16;
            var at=BlockPos.containing(x,y,z);
            if(!loaded(level,at.getX(),at.getZ()) || !level.canSeeSky(at) || !level.getBlockState(at).isAir())continue;
            level.addParticle(ParticleTypes.SNOWFLAKE,x,y,z,vx,-.06,vz);
        }
        if(level.getGameTime()%60==0)level.playLocalSound(center.x,center.y,center.z,net.minecraft.sounds.SoundEvents.ELYTRA_FLYING,
                net.minecraft.sounds.SoundSource.WEATHER,shelteredStormIntensity()*.5F,.65F,false);
    }
    private static boolean temperate(ClientLevel level, BlockPos position) {
        return SeasonVisualPalette.temperate(level.getBiome(position).value().getBaseTemperature());
    }
    private static boolean loaded(ClientLevel level, int x, int z) {
        // ClientLevel.hasChunk always returns true; use the actual loaded chunk cache.
        return level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }
    private static void fallingLeaves(Minecraft game) {
        var level = game.level; var random = level.random; var center = game.player.blockPosition();
        int x = center.getX() + random.nextInt(21) - 10, z = center.getZ() + random.nextInt(21) - 10;
        if (!loaded(level, x, z)) return;
        var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z)).below();
        if (!level.getBlockState(top).is(BlockTags.LEAVES) || !temperate(level, top)) return;
        for (int i = 0; i < 8; i++, top = top.below()) {
            var state = level.getBlockState(top);
            if (state.is(BlockTags.LEAVES) && level.getBlockState(top.below()).isAir()) {
                level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, state),
                        x + random.nextDouble(), top.getY() - 0.05, z + random.nextDouble(), 0.02, -0.03, 0.01);
                return;
            }
        }
    }
    public static void renderHud(RenderGuiEvent.Post event) {
        var game = Minecraft.getInstance();
        if (!valid(game) || game.player == null || game.screen != null || game.options.hideGui
                || !SeasonClientConfig.HUD.get()) return;
        long time = SeasonCalendar.shiftedTime(game.level.getDayTime(), snapshot.offsetDays());
        String date = SeasonCalendar.date(time, snapshot.days());
        String next = snapshot.cycling() ? SeasonCalendar.countdown(time, snapshot.days()) : "日夜循环已关闭 · 季节暂停";
        int width = Math.max(game.font.width(date), game.font.width(next));
        var graphics = event.getGuiGraphics(); int x = graphics.guiWidth() - width - 10;
        boolean storm=snapshot.stormStart()>=0 && game.level.getGameTime()<snapshot.stormEnd() && game.level.isRaining();
        String weather=storm?(stormIntensity()>.05F?"暴风雪 · 强度 "+Math.round(stormIntensity()*100)+"%":"暴风雪将至 · 检查衣物与燃料"):"";
        if(storm){width=Math.max(width,game.font.width(weather));x=graphics.guiWidth()-width-10;}
        graphics.fill(x - 4, 5, graphics.guiWidth() - 6, storm?43:31, 0xA8202B24);
        int color = switch (SeasonCalendar.season(time, snapshot.days())) {
            case 0 -> 0xFFB6E68A; case 1 -> 0xFF8EE6A3; case 2 -> 0xFFFFCF73; default -> 0xFFE1F1FF;
        };
        graphics.drawString(game.font, date, x, 9, color, true);
        graphics.drawString(game.font, next, x, 20, 0xFFE5E5E5, true);
        if(storm)graphics.drawString(game.font,weather,x,32,0xFFFFCD8D,true);
    }
}

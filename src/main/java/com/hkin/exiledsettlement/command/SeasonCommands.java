package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.settlement.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import java.util.*;

public final class SeasonCommands {
 private SeasonCommands() { }
 public static LiteralArgumentBuilder<CommandSourceStack> branch() {
  var root=Commands.literal("season"); populate(root);
  root.then(Commands.literal("blizzard").requires(source->source.hasPermission(2))
    .then(Commands.argument("ticks",IntegerArgumentType.integer(1200,24000))
      .executes(ctx->blizzard(ctx,1))
      .then(Commands.argument("strength",FloatArgumentType.floatArg(.1F,1F)).executes(ctx->blizzard(ctx,FloatArgumentType.getFloat(ctx,"strength"))))));
  root.then(Commands.literal("worldday").requires(source->source.hasPermission(2))
   .then(Commands.argument("day",LongArgumentType.longArg(1,Long.MAX_VALUE/SeasonCalendar.DAY_TICKS)).executes(SeasonCommands::worldDay)));
  var target=Commands.argument("settlement",StringArgumentType.word()); populate(target);
  root.then(Commands.literal("for").requires(source->source.hasPermission(2)).then(target)); return root;
 }
 private static void populate(ArgumentBuilder<CommandSourceStack,?> root) {
  root.then(Commands.literal("status").executes(ctx->run(ctx,-1,0,0)));
  var set=Commands.literal("set").requires(source->source.hasPermission(2));
  String[] seasons={"spring","summer","autumn","winter"};
  for (int i=0;i<seasons.length;i++) {
   final int season=i;
   set.then(Commands.literal(seasons[i]).executes(ctx->run(ctx,season,1,0))
    .then(Commands.argument("day",IntegerArgumentType.integer(1,64)).executes(ctx->run(ctx,season,IntegerArgumentType.getInteger(ctx,"day"),0))));
  }
  root.then(set).then(Commands.literal("length").requires(source->source.hasPermission(2)).then(Commands.argument("days",IntegerArgumentType.integer(1,64))
   .executes(ctx->run(ctx,4,0,IntegerArgumentType.getInteger(ctx,"days")))))
   .then(Commands.literal("reset").requires(source->source.hasPermission(2)).executes(ctx->run(ctx,5,0,0)));
 }
 private static int run(CommandContext<CommandSourceStack> ctx,int mode,int day,int length) {
  var source=ctx.getSource(); var level=source.getLevel(); var data=SettlementSavedData.get(level);
  String explicit=null; try { explicit=StringArgumentType.getString(ctx,"settlement"); } catch (IllegalArgumentException ignored) { }
  SettlementRecord settlement=null;
  if (explicit!=null) {
   if (!source.hasPermission(2)) { source.sendFailure(Component.literal("指定聚落需要管理员权限")); return 0; }
   try { UUID id=UUID.fromString(explicit); settlement=data.settlements().stream().filter(s->s.id().equals(id)).findFirst().orElse(null); }
   catch (IllegalArgumentException ignored) { source.sendFailure(Component.literal("聚落ID格式不正确")); return 0; }
  } else {
   var player=source.getPlayer();
   if (player==null) { source.sendFailure(Component.literal("控制台请使用 /exiledsettlement season for <聚落UUID> ...")); return 0; }
   BlockPos at=player.blockPosition();
   settlement=data.findContaining(at).filter(s->source.hasPermission(2)||s.isMember(player.getUUID())).orElseGet(()->
    data.settlements().stream().filter(SettlementRecord::active).filter(s->source.hasPermission(2)||s.isMember(player.getUUID()))
     .min(Comparator.comparingDouble(s->s.corePosition().distSqr(at))).orElse(null));
  }
  if (settlement==null) { source.sendFailure(Component.literal("找不到有权限的聚落")); return 0; }
  if (mode>=0 && !source.hasPermission(2)) {
   source.sendFailure(Component.literal("季节调整仅用于管理员测试，需要管理员权限")); return 0;
  }
  CommunityLifeService.migrateCalendar(level,data,settlement);
  var state=settlement.community(); long worldDay=level.getDayTime()/SeasonCalendar.DAY_TICKS;
  try {
   if (mode>=0) {
    var edited=new CommunityState();edited.load(state.save());
    if (mode<4) SeasonControlService.set(edited,worldDay,mode,day);
    else if (mode==4) SeasonControlService.length(edited,worldDay,length);
    else if (mode==5) SeasonControlService.reset(edited,worldDay);
    var calendar=WorldSeasonData.get(level);calendar.configure(edited.seasonLength,edited.calendarOffsetDays);
    for(var affected:level.getServer().getAllLevels()) { calendar.syncSettlements(affected);SeasonSyncService.tickLevel(affected); }
   }
  } catch (IllegalArgumentException error) { source.sendFailure(Component.literal(error.getMessage())); return 0; }
  String result="共享世界历法："+SeasonCalendar.date(state.calendarTime(level.getDayTime()),state.seasonLength);
  if (mode>=0) { state.event(worldDay,"管理员历法调整："+result); data.touch(); SeasonSyncService.tickLevel(level); }
  source.sendSuccess(()->Component.literal(result),false);
  if(mode<0&&source.hasPermission(2))source.sendSuccess(()->Component.literal(SeasonWeatherService.diagnostics(level.getServer().overworld())),false);
  return 1;
 }
 private static int worldDay(CommandContext<CommandSourceStack> ctx) {
  var source=ctx.getSource();if(!source.hasPermission(2))return 0;
  long day=LongArgumentType.getLong(ctx,"day");
  long time=(day-1)*SeasonCalendar.DAY_TICKS+Math.floorMod(source.getServer().overworld().getDayTime(),SeasonCalendar.DAY_TICKS);
  var calendar=WorldSeasonData.get(source.getServer().overworld());
  for(var level:source.getServer().getAllLevels()) {
   level.setDayTime(time);calendar.syncSettlements(level);SeasonSyncService.tickLevel(level);
  }
  String result="世界时间已设为第"+day+"日（保留当天时刻）；"+SeasonCalendar.date(calendar.time(time),calendar.days())+"。所有群系和聚落使用同一季节。";
  source.sendSuccess(()->Component.literal(result),true);return 1;
 }
 private static int blizzard(CommandContext<CommandSourceStack> ctx,float strength) {
  var source=ctx.getSource();var level=source.getServer().overworld();
  if(WorldSeasonData.get(level).season(level.getDayTime())!=3) { source.sendFailure(Component.literal("暴风雪测试需要先设置冬季"));return 0; }
  int ticks=IntegerArgumentType.getInteger(ctx,"ticks");level.setWeatherParameters(0,ticks+1200,true,false);
  BlizzardData.get(level).begin(level,ticks,strength);
  source.sendSuccess(()->Component.literal("暴风雪将在1200游戏刻后增强；持续"+ticks+"刻，强度"+strength),true);return 1;
 }
}

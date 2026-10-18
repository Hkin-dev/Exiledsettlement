package com.hkin.exiledsettlement.settlement;

/** Calendar edits preserve world time and daily life clocks; they never replay yearly opportunities. */
public final class SeasonControlService {
 private SeasonControlService() { }
 public static void set(CommunityState state, long worldDay, int season, int day) {
  if (season < 0 || season > 3 || day < 1 || day > state.seasonLength) throw new IllegalArgumentException("季内日期必须为1—"+state.seasonLength);
  long year = state.calendarDay(worldDay) / (state.seasonLength * 4L);
  apply(state, worldDay, year * state.seasonLength * 4L + season * (long) state.seasonLength + day - 1, state.seasonLength);
 }
 public static void length(CommunityState state, long worldDay, int length) {
  if (length < 1 || length > 64) throw new IllegalArgumentException("季长必须为1—64个游戏日");
  long oldDay = state.calendarDay(worldDay); int oldLength=state.seasonLength;
  apply(state,worldDay,oldDay/oldLength*length+oldDay%oldLength*length/oldLength,length);
 }
 public static void reset(CommunityState state,long worldDay) { apply(state,worldDay,Math.max(0,worldDay),SeasonCalendar.length(CommunityContent.seasonDays)); }
 private static void apply(CommunityState state,long worldDay,long calendarDay,int length) {
  state.rulesVersion=Math.max(2,state.rulesVersion); state.seasonLength=length;
  state.calendarOffsetDays=calendarDay-Math.max(0,worldDay);
  state.lastProcessedYear=calendarDay/(length*4L); state.lastOfferYear=state.lastProcessedYear;
  state.coldFrom=-1; state.coldUntil=-1; state.frostDay=-1; state.frostApplied=false;
  state.winterStarted=calendarDay/length%4==3;
 }
}

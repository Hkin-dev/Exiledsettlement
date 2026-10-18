package com.hkin.exiledsettlement.settlement;

/** The existing world-day calendar, shared by reports and presentation. */
public final class SeasonCalendar {
    private SeasonCalendar() { }
    public static final long DAY_TICKS = 24000L;
    private static final String[] NAMES = {"春", "夏", "秋", "冬"};
    private static final String[] HINTS = {"播种与建设", "生长与备料", "收获与冬储", "露天农业停工，注意燃料"};
    public static int length(int days) { return Math.max(1, Math.min(64, days)); }
    public static long shiftedDay(long worldDay, long offsetDays) {
        try { return Math.max(0, Math.addExact(Math.max(0, worldDay), offsetDays)); }
        catch (ArithmeticException overflow) { return offsetDays < 0 ? 0 : Long.MAX_VALUE; }
    }
    public static long shiftedTime(long worldTime, long offsetDays) {
        try { return Math.max(0, Math.addExact(Math.max(0, worldTime), Math.multiplyExact(offsetDays, DAY_TICKS))); }
        catch (ArithmeticException overflow) { return offsetDays < 0 ? 0 : Long.MAX_VALUE; }
    }
    public static int season(long dayTime, int days) { return (int) (Math.max(0, dayTime) / (DAY_TICKS * length(days)) % 4); }
    public static long year(long dayTime, int days) { return Math.max(0, dayTime) / (DAY_TICKS * length(days) * 4) + 1; }
    public static int dayWithin(long dayTime, int days) { return (int) (Math.max(0, dayTime) / DAY_TICKS % length(days)) + 1; }
    public static long remainingTicks(long dayTime, int days) {
        long duration = DAY_TICKS * length(days); return duration - Math.max(0, dayTime) % duration;
    }
    public static String name(int season) { return NAMES[Math.floorMod(season, 4)]; }
    public static String hint(int season) { return HINTS[Math.floorMod(season, 4)]; }
    public static String date(long dayTime, int days) {
        return "第" + year(dayTime, days) + "年 · " + name(season(dayTime, days)) + "季 " + dayWithin(dayTime, days) + "/" + length(days) + "日";
    }
    public static String countdown(long dayTime, int days) {
        long hundredths = (remainingTicks(dayTime, days) * 100 + DAY_TICKS - 1) / DAY_TICKS, whole = hundredths / 100;
        int percent = (int) (hundredths % 100);
        return "距" + name(season(dayTime, days) + 1) + "季 " + whole + "." + (percent < 10 ? "0" : "") + percent + "游戏日";
    }
}

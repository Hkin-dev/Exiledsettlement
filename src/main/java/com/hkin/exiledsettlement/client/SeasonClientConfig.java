package com.hkin.exiledsettlement.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local preferences, with no settlement simulation changes. */
public final class SeasonClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HUD, SCENERY, BOUNDARY;
    static {
        var builder = new ModConfigSpec.Builder(); builder.push("seasons");
        HUD = builder.comment("Show the server calendar and time until the next season.").define("showCalendar", true);
        SCENERY = builder.comment("Seasonal plant colors and autumn leaves. Actual server weather and snow remain visible.").define("showScenery", true);
        builder.pop(); builder.push("territory");
        BOUNDARY = builder.comment("Show the selected settlement boundary in normal play. Overhead always shows it.").define("showBoundary", true);
        builder.pop(); SPEC = builder.build();
    }
    private SeasonClientConfig() { }
}

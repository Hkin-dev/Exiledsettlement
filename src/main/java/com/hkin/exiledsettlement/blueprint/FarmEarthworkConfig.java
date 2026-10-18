package com.hkin.exiledsettlement.blueprint;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server engineering limits, in blocks; synced to the placement client by NeoForge. */
public final class FarmEarthworkConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DIG_HEIGHT, FILL_DEPTH;
    static {
        var b = new ModConfigSpec.Builder(); b.push("farmEarthwork");
        DIG_HEIGHT = b.comment("Maximum excavation above the chosen soil plane, in blocks. Existing confirmed plans retain their bounds.")
                .defineInRange("maxDigHeight", 4, 1, 8);
        FILL_DEPTH = b.comment("Maximum dirt layers from a verified solid support to the chosen soil plane, in blocks.")
                .defineInRange("maxFillDepth", 4, 1, 8);
        b.pop(); SPEC = b.build();
    }
    private FarmEarthworkConfig() { }
}

package com.hkin.exiledsettlement.compat;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;

/** Safe during mixin bootstrap and client rendering, without loading either optional mod. */
public final class YsmCompatibility {
    private YsmCompatibility() {}

    public static boolean available() {
        var mods = FMLLoader.getLoadingModList();
        return FMLLoader.getDist() == Dist.CLIENT && mods != null
                && mods.getModFileById("yes_steve_model") != null
                && mods.getModFileById("ysm_mapping_api") != null;
    }
}

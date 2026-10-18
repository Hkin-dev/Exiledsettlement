package com.hkin.exiledsettlement.compat.ysm;

import net.minecraft.world.entity.player.Player;

/** Stable class alias remapped to YSM's verified player-state holder at runtime. */
public final class YsmPlayerStateCapabilityAlias {
    private YsmPlayerStateCapabilityAlias(Player player) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }
}

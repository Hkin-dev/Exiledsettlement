package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.network.SeasonSnapshotPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.network.PacketDistributor;

public final class SeasonSyncService {
    private SeasonSyncService() { }
    /** One tiny packet per player per five seconds, no block or inventory scan. */
    public static void tickLevel(ServerLevel level) {
        var calendar = WorldSeasonData.get(level);
        var storm = BlizzardData.get(level);
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (player.serverLevel() != level || player.connection == null
                    || !player.connection.hasChannel(SeasonSnapshotPayload.TYPE)) continue;
            PacketDistributor.sendToPlayer(player, new SeasonSnapshotPayload(level.dimension().location().toString(),
                    "世界历法", calendar.days(), calendar.offsetDays(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),storm.start,storm.end,storm.direction,storm.strength));
        }
    }
}

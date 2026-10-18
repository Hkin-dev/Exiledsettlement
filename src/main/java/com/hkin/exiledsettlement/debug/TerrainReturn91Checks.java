package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.TerrainWorkSafety;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit geometry and actor inputs, never counted as autonomous resident work. */
public final class TerrainReturn91Checks {
    private static void check(boolean ok, String name, List<String> checks) {
        if (!ok) throw new IllegalStateException(name); checks.add(name);
    }
    public static String run(ServerLevel l) {
        if (!SaveAcceptanceService.enabled()) throw new IllegalStateException("TEST_ONLY");
        var origin = new BlockPos(49000,248,49000);
        var saved = new LinkedHashMap<BlockPos, BlockState>();
        for (var raw : BlockPos.betweenClosed(origin.offset(-2,-12,-3),origin.offset(20,4,3))) {
            var p = raw.immutable();
            if (!l.getBlockState(p).isAir()) throw new IllegalStateException("UNUSED_AIR_REQUIRED");
            saved.put(p,l.getBlockState(p));
        }
        var e = ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        var checks = new ArrayList<String>();
        try {
            for (int x=0;x<=16;x++) for(int z=-1;z<=1;z++) {
                int floor=247-Math.min(6,x/2);
                for(int y=236;y<=floor;y++) l.setBlock(origin.offset(x,0,z).atY(y),Blocks.STONE.defaultBlockState(),2);
            }
            e.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);e.setOnGround(true);
            if (!l.addFreshEntity(e)) throw new IllegalStateException("ENTITY_SPAWN_FAILED");
            var low=origin.east(14).atY(242);
            e.setPos(low.getX()+.5,low.getY(),low.getZ()+.5);e.setOnGround(true);
            check(TerrainWorkSafety.hasReturn(e,low,247),"low worker without remembered route finds actual escape",checks);
            e.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);e.setOnGround(true);
            check(TerrainWorkSafety.hasReturn(e,low,247),"connected low slope beyond four-block window accepted",checks);
            e.setPos(low.getX()+.5,low.getY(),low.getZ()+.5);e.setOnGround(true);
            check(TerrainWorkSafety.hasReturn(e,low,247),"actual low position retains validated return",checks);
            for(int z=-1;z<=1;z++)for(int y=245;y<=248;y++)l.setBlock(origin.offset(6,0,z).atY(y),Blocks.STONE.defaultBlockState(),2);
            check(!TerrainWorkSafety.hasReturn(e,low,247),"changed wall invalidates cached escape",checks);
            for(int z=-1;z<=1;z++)for(int y=245;y<=248;y++)l.setBlock(origin.offset(6,0,z).atY(y),Blocks.AIR.defaultBlockState(),2);
            check(TerrainWorkSafety.hasReturn(e,low,247),"restored real route accepted",checks);
            l.setBlock(low.above(),Blocks.STONE.defaultBlockState(),2);
            check(!TerrainWorkSafety.hasReturn(e,low,247),"blocked workface headroom refused",checks);
            l.setBlock(low.above(),Blocks.AIR.defaultBlockState(),2);
            var isolated=origin.offset(19,-8,0);l.setBlock(isolated.below(),Blocks.STONE.defaultBlockState(),2);
            check(!TerrainWorkSafety.hasReturn(e,isolated,247),"disconnected low pillar refused",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit geometry fixture, not movement acceptance";
        } finally { e.discard();saved.forEach((p,state)->l.setBlock(p,state,2)); }
    }
    private TerrainReturn91Checks() { }
}

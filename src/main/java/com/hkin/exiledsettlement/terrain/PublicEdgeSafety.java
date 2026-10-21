package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;

/** Rails occupy public shoulders, never the three-wide walking lane or a fixed entrance. */
final class PublicEdgeSafety {
    private static boolean eligible(ServerLevel level,SettlementRecord s,BlockPos at){
        var w=s.terrain().publicGround;long k=BlockPos.asLong(at.getX(),0,at.getZ());
        if(w.anchors.containsKey(k) || w.passageGroups.containsKey(k) || w.stairs.containsKey(k) || PublicGroundService.roadColumn(level,s,at))return false;
        for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated && !r.centerline.isEmpty()){
            for(var mouth:java.util.List.of(r.centerline.getFirst(),r.centerline.getLast()))
                if(Math.abs(mouth.getX()-at.getX())+Math.abs(mouth.getZ()-at.getZ())<=2)return false;
        }
        for(var d:Direction.Plane.HORIZONTAL){var facing=w.stairs.get(BlockPos.asLong(at.getX()+d.getStepX(),0,at.getZ()+d.getStepZ()));
            if(facing!=null && (facing==d||facing==d.getOpposite()))return false;
        }
        return true;
    }
    private static boolean hazard(ServerLevel level,SettlementRecord s,BlockPos at){
        if(!eligible(level,s,at))return false;var w=s.terrain().publicGround;
        boolean hazard=false;
        for(var d:Direction.Plane.HORIZONTAL){
            var n=at.relative(d);if(!level.hasChunkAt(n))continue;
            long nk=BlockPos.asLong(n.getX(),0,n.getZ());
            Integer target=w.goals.get(nk);
            // Leave the upper and lower stair mouths open, including their landing rows.
            var facing=w.stairs.get(nk);
            if(facing!=null && (facing==d || facing==d.getOpposite()))return false;
            int actual=PublicGroundService.currentGround(level,n);
            int below=target==null?actual:Math.min(actual,target);
            if(at.getY()-below>=TerrainConfig.PUBLIC_EDGE_DROP.get())hazard=true;
            // Shallow lava/fire edges are also guarded. Water itself is not classified as lethal.
            var state=level.getBlockState(n.atY(Math.min(actual,at.getY())));
            if(at.getY()>actual && (state.is(Blocks.LAVA)||state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.FIRE)))hazard=true;
        }
        return hazard;
    }
    private static boolean endpoint(ServerLevel l,SettlementRecord s,BlockPos p){
        return l.hasChunkAt(p) && java.util.Objects.equals(s.terrain().publicGround.goals.get(BlockPos.asLong(p.getX(),0,p.getZ())),p.getY()) && hazard(l,s,p);
    }
    static boolean needed(ServerLevel l,SettlementRecord s,BlockPos at){
        if(!eligible(l,s,at))return false;
        if(hazard(l,s,at))return true;
        if(!java.util.Objects.equals(s.terrain().publicGround.goals.get(BlockPos.asLong(at.getX(),0,at.getZ())),at.getY()))return false;
        // Join a single missing segment between same-level danger edges, without growing
        // recursively across safe ground or occupying any full-width passage/landing.
        for(var d:Direction.Plane.HORIZONTAL){
            if(endpoint(l,s,at.relative(d)) && endpoint(l,s,at.relative(d.getOpposite())))return true;
            var side=d.getClockWise();
            if(!endpoint(l,s,at.relative(d)) || !endpoint(l,s,at.relative(side)))continue;
            var other=at.relative(d).relative(side);
            if(!l.hasChunkAt(other))continue;
            boolean alternative=java.util.Objects.equals(s.terrain().publicGround.goals.get(BlockPos.asLong(other.getX(),0,other.getZ())),other.getY()) && eligible(l,s,other);
            if(alternative && hazard(l,s,other))continue;
            if(!alternative || at.getX()<other.getX() || at.getX()==other.getX() && at.getZ()<other.getZ())return true;
        }
        return false;
    }
    private PublicEdgeSafety(){ }
}

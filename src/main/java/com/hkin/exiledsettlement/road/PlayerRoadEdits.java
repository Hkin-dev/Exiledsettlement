package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Ordinary edits request local maintenance; only explicit zones protect player terrain. */
public final class PlayerRoadEdits {
    private PlayerRoadEdits() { }
    private record Edit(BlockPos at,BlockState before,long tick) { }
    private static final Map<ServerLevel,Map<BlockPos,Edit>> PENDING=new WeakHashMap<>();
    private static long column(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    public static boolean holding(ServerLevel level,BlockPos p){return false;}
    public static boolean retained(ServerLevel level,BlockPos p){return false;}
    public static void note(ServerLevel level,BlockPos p,BlockState before){
        PENDING.computeIfAbsent(level,l->new LinkedHashMap<>()).put(p.immutable(),new Edit(p.immutable(),before,level.getGameTime()));
    }
    public static void flush(ServerLevel level){
        var pending=PENDING.get(level);if(pending==null)return;
        var it=pending.values().iterator();int budget=128;
        while(it.hasNext() && budget-->0){var edit=it.next();if(edit.tick>=level.getGameTime())continue;it.remove();
            if(level.hasChunkAt(edit.at) && !level.getBlockState(edit.at).equals(edit.before))changed(level,edit.at);
        }
    }
    public static void changed(ServerLevel level,BlockPos p){
        var data=SettlementSavedData.get(level);var roads=RoadSpaceData.get(level);
        for(var s:data.settlements())if(s.active() && s.contains(p)){
            if(s.decorations().forbidden(p) || s.community().danger(p))continue;
            var affected=s.autoRoads().routes.values().stream().filter(r->!r.suppressed && r.cells.stream().anyMatch(q->column(q)==column(p))).toList();
            Integer oldY=roads.columnHeight(p);
            PublicGroundService.queueSurfaceReview(s,p,!affected.isEmpty() || oldY!=null);
            // Preserve target geometry, paid steps and active route searches.
            // Damage is repaired at the registered grade, never adopted as a new anchor.
            for(var r:affected)for(int i=0;i<r.cells.size();i++)if(column(r.cells.get(i))==column(p)){
                r.repairs.set(i);r.earthworkCursor=Math.min(r.earthworkCursor,i);
                r.status="局部地形变化，按登记道路标高复查";
            }
            data.touch();
        }
    }
    public static void adopt(AutoRoadState.Route route,BlockPos actual){
        route.cells=route.cells.stream().map(p->column(p)==column(actual)?actual:p).distinct().toList();
        route.centerline=route.centerline.stream().map(p->column(p)==column(actual)?actual:p).toList();
        route.completed.clear();route.progress=0;route.earthworkCursor=0;route.reviewAfter=0;
    }
}

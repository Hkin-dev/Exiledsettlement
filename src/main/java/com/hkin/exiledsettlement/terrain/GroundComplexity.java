package com.hkin.exiledsettlement.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.*;

/** Geometric observations, not permission to edit. Missing/intentional columns are boundaries. */
public final class GroundComplexity {
    public record Metrics(int columns,int comparedEdges,int steepEdges,int reversals,
                          int enclosedPits,int isolatedPeaks,int narrowRidges) { }
    private GroundComplexity() { }
    public static Metrics measure(Map<Long,Integer> surface) {
        int edges=0,steep=0,turns=0,ridges=0,pits=0,peaks=0;
        for(var e:surface.entrySet()) {
            var p=BlockPos.of(e.getKey());int y=e.getValue();boolean narrow=false;
            for(var d:List.of(Direction.EAST,Direction.SOUTH)) {
                Integer a=surface.get(p.relative(d).asLong()),b=surface.get(p.relative(d.getOpposite()).asLong());
                if(a!=null){edges++;if(Math.abs(a-y)>=2)steep++;}
                if(a!=null && b!=null){if((long)(a-y)*(b-y)>0)turns++;if(y-a>=2 && y-b>=2)narrow=true;}
            }
            if(narrow)ridges++;
        }
        // Equal-height plateaus must be fully enclosed: clipped scan edges and protected
        // features never turn into a supposed pit/peak. Large basins are not small defects.
        var seen=new HashSet<Long>();
        for(long start:surface.keySet())if(seen.add(start)) {
            int y=surface.get(start),size=0;boolean lower=false,higher=false,open=false;
            var queue=new ArrayDeque<Long>();queue.add(start);
            while(!queue.isEmpty()) {
                var p=BlockPos.of(queue.removeFirst());size++;
                for(var d:Direction.Plane.HORIZONTAL){long k=p.relative(d).asLong();Integer n=surface.get(k);
                    if(n==null){open=true;continue;}
                    if(n<y)lower=true;else if(n>y)higher=true;else if(seen.add(k))queue.add(k);
                }
            }
            if(!open && size<=64){if(higher&&!lower)pits++;if(lower&&!higher)peaks++;}
        }
        return new Metrics(surface.size(),edges,steep,turns,pits,peaks,ridges);
    }
}

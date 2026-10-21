package com.hkin.exiledsettlement.terrain;

import net.minecraft.core.*;
import java.util.*;

/** Transactional three-wide passages. No partial flight is committed on a failed fit. */
final class PublicPassages {
    private record Row(BlockPos p,int y,Direction down,boolean stair) { }
    private static long key(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    /** Support road shoulders. Do not invent repeated shelves on a solved retaining face. */
    static void terraceColumn(PublicGroundService.State w,long column){
        var p=BlockPos.of(column);int high=w.goals.get(column);
        if(w.integratedRoads.contains(column)){
            for(var d:Direction.Plane.HORIZONTAL){
                long next=key(p.relative(d));var y=w.goals.get(next);
                if(y!=null && high-y>=2 && !w.anchors.containsKey(next) && !w.integratedRoads.contains(next))
                    w.goals.put(next,high);
            }
        }
    }
    static boolean advance(PublicGroundService.State w){
        var entries=w.keys();
        for(int work=0;work<64 && w.planCursor<entries.size() && w.timeFor(work);work++){
            long k=entries.get(w.planCursor++);if(!w.seams.contains(k) || w.seenSeams.contains(k) || w.anchors.containsKey(k))continue;
            var p=BlockPos.of(k);int high=w.goals.get(k);
            boolean covered=false;
            for(int dx=-12;dx<=12 && !covered;dx++)for(int dz=-12;dz<=12;dz++)
                if(w.stairs.containsKey(key(p.offset(dx,0,dz)))){covered=true;break;}
            if(covered)continue;
            for(var down:Direction.Plane.HORIZONTAL){
                Integer low=w.goals.get(key(p.relative(down)));if(low==null || low>=high)continue;
                if(high-low==1 && !PublicGroundService.continuousSlope(w,k,down))continue;
                if(attempt(w,p,high,down,false,1) || attempt(w,p,high,down,true,1)
                        || attempt(w,p,high,down,true,-1)){
                    var queue=new ArrayDeque<Long>();queue.add(k);w.seenSeams.add(k);
                    for(int n=0;n<512 && !queue.isEmpty();n++){
                        var edge=BlockPos.of(queue.removeFirst());
                        for(var direction:Direction.Plane.HORIZONTAL){
                            long next=key(edge.relative(direction));
                            if(w.seams.contains(next) && Objects.equals(w.goals.get(next),high)
                                    && w.seenSeams.add(next))queue.add(next);
                        }
                    }
                    break;
                }
            }
        }
        return w.planCursor>=entries.size();
    }
    private static boolean attempt(PublicGroundService.State w,BlockPos start,int high,Direction down,boolean folded,int hand){
        var rows=new ArrayList<Row>();
        int destination=high;
        for(int distance=1;distance<=128;distance++){
            var grade=w.goals.get(key(start.relative(down,distance)));
            if(grade==null)break;
            destination=Math.min(destination,grade);
        }
        // The upper landing is part of the proof, not an isolated first stair.
        rows.add(new Row(start.relative(down.getOpposite()),high,down,false));
        var p=start;int y=high,flight=0,drops=0;
        Direction direction=down;
        for(int count=0;count<256 && drops<=64;count++){
            Integer existing=w.goals.get(key(p));
            if(existing==null)return false;
            // Three complete flat rows connect the bottom into the existing terrace.
            if(drops>=2 && existing==y && y<=destination){
                var end=new ArrayList<>(rows);
                for(int n=0;n<3;n++)end.add(new Row(p.relative(direction,n),y,direction,false));
                if(commit(w,end,high,y))return true;
            }
            if(drops==64)return false;
            rows.add(new Row(p,y,direction,true));
            p=p.relative(direction);y--;drops++;flight++;
            if(flight==4){
                if(folded){
                    // A 3-deep, 7-wide switchback landing; the flights retain 3 clear lanes.
                    for(int n=0;n<3;n++)rows.add(new Row(p.relative(direction,n),y,direction,false));
                    p=p.relative(direction);
                    var side=hand>0?direction.getClockWise():direction.getCounterClockWise();
                    for(int n=0;n<=4;n++)rows.add(new Row(p.relative(side,n),y,side,false));
                    p=p.relative(side,4);direction=direction.getOpposite();
                    rows.add(new Row(p,y,direction,false));p=p.relative(direction);
                }else{
                    for(int n=0;n<3;n++){rows.add(new Row(p,y,direction,false));p=p.relative(direction);}
                }
                flight=0;
            }
        }
        return false;
    }
    private static boolean commit(PublicGroundService.State w,List<Row> rows,int high,int low){
        var grades=new LinkedHashMap<Long,Integer>();var stairs=new LinkedHashMap<Long,Direction>();
        for(var row:rows){
            var side=row.down.getClockWise();
            for(int lane=-1;lane<=1;lane++){
                long k=key(row.p.relative(side,lane));Integer old=w.goals.get(k);
                if(old==null || w.passageGroups.containsKey(k) || w.stairs.containsKey(k) || w.roadHeights.containsKey(k))return false;
                // Never tunnel through a fixed doorstep or borrow a different terrace.
                if(w.anchors.containsKey(k) && !Objects.equals(w.anchors.get(k),row.y))return false;
                if(old>high+1 || old<low-1)return false;
                Integer previous=grades.putIfAbsent(k,row.y);
                if(previous!=null && previous!=row.y)return false;
                if(row.stair){
                    if(w.anchors.containsKey(k))return false;
                    var facing=row.down.getOpposite();var prior=stairs.putIfAbsent(k,facing);
                    if(prior!=null && prior!=facing)return false;
                }
            }
        }
        // Both ends must already meet the solved platform across the whole clear width.
        for(var row:List.of(rows.getFirst(),rows.getLast()))
            for(int lane=-1;lane<=1;lane++)
                if(!Objects.equals(w.goals.get(key(row.p.relative(row.down.getClockWise(),lane))),row.y))return false;
        for(var e:stairs.entrySet()){
            long lower=key(BlockPos.of(e.getKey()).relative(e.getValue().getOpposite()));
            if(!Objects.equals(grades.get(lower),grades.get(e.getKey())-1))return false;
        }
        // Side shoulders are outside all three walking lanes. They carry their own
        // retaining face and any necessary rail; never occupy a landing or another flight.
        var shoulders=new LinkedHashMap<Long,Integer>();
        for(var row:rows)for(int lane:new int[]{-2,2}){
            long k=key(row.p.relative(row.down.getClockWise(),lane));
            if(!grades.containsKey(k) && w.goals.containsKey(k) && !w.anchors.containsKey(k)
                    && !w.roadHeights.containsKey(k) && !w.stairs.containsKey(k))
                shoulders.merge(k,row.y,Math::max);
        }
        w.goals.putAll(shoulders);w.goals.putAll(grades);w.stairs.putAll(stairs);
        long group=key(rows.getFirst().p);for(long k:grades.keySet())w.passageGroups.put(k,group);
        return true;
    }
    /** An old stair waits for its nearby replacement, never for an unrelated city-wide flight.
     * Cells inside the replacement must be allowed to change, or the dependency is circular. */
    static boolean replacementReady(net.minecraft.server.level.ServerLevel level,PublicGroundService.State w,BlockPos at){
        long column=key(at);if(w.passageGroups.containsKey(column))return true;
        Long group=null;int nearest=Integer.MAX_VALUE;
        for(var e:w.passageGroups.entrySet()){
            var p=BlockPos.of(e.getKey());Integer y=w.goals.get(e.getKey());if(y==null)continue;
            int distance=Math.abs(p.getX()-at.getX())+Math.abs(p.getZ()-at.getZ());
            if(distance<=8 && Math.abs(y-at.getY())<=4 && distance<nearest){nearest=distance;group=e.getValue();}
        }
        if(group!=null){
            for(var e:w.passageGroups.entrySet())if(e.getValue().equals(group) && !ready(level,w,e.getKey()))return false;
        }else{
            // Compatibility for a saved/manual diagnostic state without passage membership.
            for(long k:w.stairs.keySet()){var p=BlockPos.of(k);Integer y=w.goals.get(k);
                if(y!=null && Math.abs(p.getX()-at.getX())+Math.abs(p.getZ()-at.getZ())<=8
                        && Math.abs(y-at.getY())<=4 && !ready(level,w,k))return false;
            }
            // A failed fit is not proof that the old climb is obsolete. Preserve
            // it when the nearby solved terrain still needs an unplanned crossing.
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                var p=at.offset(dx,0,dz);long k=key(p);Integer y=w.goals.get(k);if(y==null)continue;
                for(var d:Direction.Plane.HORIZONTAL){Integer other=w.goals.get(key(p.relative(d)));
                    if(other!=null && (Math.abs(y-other)>=2 || PublicGroundService.continuousSlope(w,k,d)))return false;
                }
            }
        }
        return true;
    }
    private static boolean ready(net.minecraft.server.level.ServerLevel level,PublicGroundService.State w,long k){
        Integer y=w.goals.get(k);if(y==null)return false;var p=BlockPos.of(k).atY(y);
        if(!level.hasChunkAt(p) || !com.hkin.exiledsettlement.road.AdaptiveRoadService.walkSurface(level,p))return false;
        var facing=w.stairs.get(k);
        return facing==null?TerrainSurface.ground(level,p):com.hkin.exiledsettlement.blueprint.BlueprintService.matchesPlacedState(level.getBlockState(p),
                net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,facing));
    }
    private PublicPassages(){ }
}

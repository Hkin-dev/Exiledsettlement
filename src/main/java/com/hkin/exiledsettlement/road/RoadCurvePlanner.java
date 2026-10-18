package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Resumable local corner rounding. Straight runs and paid network geometry stay intact. */
final class RoadCurvePlanner {
    private static final int MIN_RADIUS=3;
    private final int maxRadius=com.hkin.exiledsettlement.terrain.TerrainConfig.ROAD_CURVE_RADIUS.get();
    private final double extraCost=com.hkin.exiledsettlement.terrain.TerrainConfig.ROAD_CURVE_EXTRA_COST.get();
    private final List<BlockPos> original;
    private final Set<BlockPos> paid, suppressed;
    private final Set<BlockPos> replacing;
    private final ArrayList<BlockPos> output=new ArrayList<>();
    private final LinkedHashSet<BlockPos> baseline=new LinkedHashSet<>(), candidateArea=new LinkedHashSet<>();
    private final Map<BlockPos,Double> effort=new HashMap<>();
    private List<BlockPos> horizontal=List.of();
    private final ArrayList<BlockPos> candidate=new ArrayList<>();
    private int cursor, corner, radius, start, end, row, stage, rounded;
    private double baselineCost, candidateCost;
    private boolean done;

    RoadCurvePlanner(List<BlockPos> line,Set<BlockPos> paid,Set<BlockPos> suppressed) {
        this(line,paid,suppressed,Set.of());
    }
    RoadCurvePlanner(List<BlockPos> line,Set<BlockPos> paid,Set<BlockPos> suppressed,Set<BlockPos> replacing) {
        this.original=List.copyOf(line);this.paid=Set.copyOf(paid);this.suppressed=Set.copyOf(suppressed);
        this.replacing=Set.copyOf(replacing);
    }
    boolean done(){return done;}
    int rounded(){return rounded;}
    List<BlockPos> result(){return List.copyOf(output);}

    void advance(ServerLevel level,SettlementRecord settlement) {
        if(done)return;
        if(stage==0) {
            if(cursor>=original.size()){done=true;return;}
            int r=roundingRadius(level,cursor);
            if(r==0){output.add(original.get(cursor++));return;}
            corner=cursor;radius=r;beginCandidate();return;
        }
        if(stage==1) {
            var at=horizontal.get(row);
            BlockPos projected;
            if(row==0)projected=original.get(start);
            else if(row==horizontal.size()-1)projected=original.get(end);
            else {
                int hint=(int)Math.round(original.get(start).getY()+(original.get(end).getY()-original.get(start).getY())
                        *(double)row/(horizontal.size()-1));
                int previous=candidate.getLast().getY();projected=null;double best=Double.MAX_VALUE;
                for(int dy:new int[]{0,1,-1}) {
                    var p=at.atY(hint+dy);
                    if(Math.abs(p.getY()-previous)>1 || !usable(level,settlement,p))continue;
                    double cost=effort.computeIfAbsent(p,k->RoadEarthworkService.effort(level,settlement,k));
                    if(cost<best){best=cost;projected=p;}
                }
            }
            if(projected==null || !usable(level,settlement,projected)
                    || !candidate.isEmpty() && Math.abs(projected.getY()-candidate.getLast().getY())>1){retry();return;}
            candidate.add(projected);if(++row==horizontal.size()){row=start;stage=2;}return;
        }
        if(stage==2) {
            var cells=AutoRoadService.corridorCell(level,settlement,original,row++);
            if(cells==null){abandon();return;}
            baseline.addAll(cells);
            if(row>end){row=0;stage=3;}return;
        }
        if(stage==3) {
            var cells=AutoRoadService.corridorCell(level,settlement,candidate,row++,false);
            if(cells==null || cells.stream().anyMatch(p->!usable(level,settlement,p))){retry();return;}
            candidateArea.addAll(cells);
            if(row==candidate.size()){row=0;baselineCost=0;candidateCost=0;stage=4;}return;
        }
        // Price at most one footprint cell per planner step, including real earthwork.
        if(stage==4) {
            var list=List.copyOf(baseline);
            if(row<list.size()){baselineCost+=price(level,settlement,list.get(row++));return;}
            row=0;stage=5;return;
        }
        if(stage==5) {
            var list=List.copyOf(candidateArea);
            if(row<list.size()){candidateCost+=price(level,settlement,list.get(row++));return;}
            // An aesthetic preference, never a road length/area quota. Expensive rounding keeps the safe straight route.
            if(candidateCost>baselineCost*(1D+extraCost)+2D){retry();return;}
            int trim=output.lastIndexOf(original.get(start));
            if(trim<0){abandon();return;}
            output.subList(trim,output.size()).clear();output.addAll(candidate);
            cursor=end+1;rounded++;stage=0;
        }
    }

    private int roundingRadius(ServerLevel level,int i) {
        if(i<2 || i>=original.size()-2)return 0;
        var prev=original.get(i-1);var center=original.get(i);var next=original.get(i+1);
        int ax=center.getX()-prev.getX(),az=center.getZ()-prev.getZ();
        int bx=next.getX()-center.getX(),bz=next.getZ()-center.getZ();
        if(Math.abs(ax)+Math.abs(az)!=1 || Math.abs(bx)+Math.abs(bz)!=1 || ax*bx+az*bz!=0)return 0;
        int before=0,after=0;
        for(int j=i;j>0 && before<maxRadius*2;j--) {
            var a=original.get(j-1);var b=original.get(j);
            if(b.getX()-a.getX()!=ax || b.getZ()-a.getZ()!=az)break;before++;
        }
        for(int j=i;j<original.size()-1 && after<maxRadius*2;j++) {
            var a=original.get(j);var b=original.get(j+1);
            if(b.getX()-a.getX()!=bx || b.getZ()-a.getZ()!=bz)break;after++;
        }
        int r=Math.min(maxRadius,Math.min(before/2,after/2));
        if(r<MIN_RADIUS)return 0;
        for(int j=i-r;j<=i+r;j++)if(paid.contains(original.get(j)) || RoadSpaceData.get(level).contains(original.get(j)) && !replacing.contains(original.get(j)))return 0;
        return r;
    }
    private void beginCandidate() {
        start=corner-radius;end=corner+radius;
        var a=original.get(start);var b=original.get(corner);var c=original.get(end);
        var points=new ArrayList<BlockPos>();
        for(int i=0;i<=radius*8;i++) {
            double t=(double)i/(radius*8),u=1-t;
            var p=new BlockPos((int)Math.round(u*u*a.getX()+2*u*t*b.getX()+t*t*c.getX()),0,
                    (int)Math.round(u*u*a.getZ()+2*u*t*b.getZ()+t*t*c.getZ()));
            if(points.isEmpty() || !points.getLast().equals(p))points.add(p);
        }
        horizontal=List.copyOf(points);candidate.clear();baseline.clear();candidateArea.clear();row=0;stage=1;
    }
    private void retry(){if(--radius>=MIN_RADIUS)beginCandidate();else abandon();}
    private void abandon(){output.add(original.get(cursor++));stage=0;}
    private boolean usable(ServerLevel level,SettlementRecord s,BlockPos p) {
        return !(suppressed.contains(p) && !RoadSpaceData.get(level).contains(p))
                && level.hasChunkAt(p) && level.getBlockState(p).getFluidState().isEmpty()
                && RoadEarthworkService.plannable(level,s,p);
    }
    private double price(ServerLevel level,SettlementRecord s,BlockPos p) {
        return (paid.contains(p)||RoadSpaceData.get(level).contains(p)?0D:1D)+.25D*effort.computeIfAbsent(p,k->RoadEarthworkService.effort(level,s,k));
    }
}

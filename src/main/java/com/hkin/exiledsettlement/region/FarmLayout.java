package com.hkin.exiledsettlement.region;

import net.minecraft.core.*;
import java.util.*;

/** Internal paths and irrigation on one confirmed soil plane. */
public final class FarmLayout {
    public static final int VERSION=19;
    private FarmLayout() { }
    /** One stable elevation for each independent selection. Residents perform every cut/fill. */
    public static void grade(ProductionRegion r) { RegionGroundLayout.grade(r); }
    public static void plan(ProductionRegion r) {
        var previous=new HashSet<>(r.farmPath);r.farmPath.clear();
        if(r.usableArea()<7)return;
        var gate=r.cells.values().stream().filter(c->c.use()==ProductionRegion.Use.GATE && r.gateFacing(c.ground())==r.gateFacing)
                .min(Comparator.comparingDouble(c->c.ground().distSqr(r.first))).orElse(null);
        if(gate==null)return;
        var start=r.cell(gate.ground().relative(r.gateFacing.getOpposite()));
        if(!usable(r,start))return;
        var inward=r.gateFacing.getOpposite();
        var end=r.cells.values().stream().filter(c->usable(r,c))
                .max(Comparator.comparingDouble(c->progress(c.ground(),gate.ground(),inward)
                        -Math.abs(lateral(c.ground(),gate.ground(),inward))*.35)).orElse(start);
        double length=Math.max(1,progress(end.ground(),start.ground(),inward));
        double amplitude=length<8?0:Math.min(3,length/7);
        record Node(long key,double cost) { }
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::cost).thenComparingLong(Node::key));
        var cost=new HashMap<Long,Double>();var parent=new HashMap<Long,Long>();
        long first=ProductionRegion.key(start.ground().getX(),start.ground().getZ());
        long last=ProductionRegion.key(end.ground().getX(),end.ground().getZ());
        cost.put(first,0D);open.add(new Node(first,0));
        while(!open.isEmpty()) {
            var node=open.remove();if(node.cost()>cost.getOrDefault(node.key(),Double.MAX_VALUE))continue;
            if(node.key()==last)break;
            var cell=r.cells.get(node.key());
            for(var side:Direction.Plane.HORIZONTAL) {
                var next=r.cell(cell.ground().relative(side));if(!usable(r,next))continue;
                double t=Math.clamp(progress(next.ground(),start.ground(),inward)/length,0,1);
                double guide=lateral(end.ground(),start.ground(),inward)*t+amplitude*Math.sin(Math.PI*2*t);
                double deviation=Math.abs(lateral(next.ground(),start.ground(),inward)-guide);
                double value=node.cost()+(previous.contains(ProductionRegion.key(next.ground().getX(),next.ground().getZ()))?.5:1)+deviation*.8+Math.abs(next.ground().getY()-cell.ground().getY())*2;
                long key=ProductionRegion.key(next.ground().getX(),next.ground().getZ());
                if(value>=cost.getOrDefault(key,Double.MAX_VALUE))continue;
                cost.put(key,value);parent.put(key,node.key());open.add(new Node(key,value));
            }
        }
        if(!cost.containsKey(last))last=cost.keySet().stream().max(Comparator.comparingInt(k->progress(r.cells.get(k).ground(),gate.ground(),inward))).orElse(first);
        var line=new ArrayList<Long>();for(Long key=last;key!=null;key=parent.get(key))line.add(key);Collections.reverse(line);
        if(line.size()*2>=r.usableArea())return;
        // Confine grading to this narrow corridor, with a walkable one-block height change.
        int y=gate.ground().getY();
        for(long key:line) {
            var c=r.cells.get(key);int next=Math.clamp(c.ground().getY(),y-1,y+1);
            var at=c.ground().atY(next);r.cells.put(key,new ProductionRegion.Cell(at,ProductionRegion.Use.PATH,c.production(),c.type()));
            if(next!=c.ground().getY())r.gradeOrigins.putIfAbsent(key,c.ground().getY());
            r.farmPath.add(key);y=next;
        }
        // Two-block passing width where the actual terrain permits; never consume water points.
        for(long key:List.copyOf(r.farmPath)) {
            var c=r.cells.get(key);var n=r.cell(c.ground().relative(inward.getClockWise()));
            if(usable(r,n) && Math.abs(n.ground().getY()-c.ground().getY())<=1) {
                long k=ProductionRegion.key(n.ground().getX(),n.ground().getZ());
                if(n.ground().getY()!=c.ground().getY())r.gradeOrigins.putIfAbsent(k,n.ground().getY());
                r.cells.put(k,new ProductionRegion.Cell(n.ground().atY(c.ground().getY()),ProductionRegion.Use.PATH,n.production(),n.type()));
            }
        }
        // Join distributed gates to the spine. A gate alone is not an internal route.
        for(var entry:List.copyOf(r.gateFacings.entrySet())) {
            var c=r.cells.get(entry.getKey());var inside=r.cell(c.ground().relative(entry.getValue().getOpposite()));
            if(usable(r,inside))join(r,inside,c.ground().getY());
        }
        // Each disconnected broad bench needs one connector, rather than scattered stairs.
        var attempted=new HashSet<Long>();
        while(true) {
            var connected=reachable(r,new HashSet<>(r.farmPath));
            var isolated=r.cells.entrySet().stream().filter(e->usable(r,e.getValue()) && !connected.contains(e.getKey()) && !attempted.contains(e.getKey()))
                    .min(Comparator.comparingLong(Map.Entry::getKey)).orElse(null);
            if(isolated==null)break;
            var component=reachable(r,Set.of(isolated.getKey()));attempted.addAll(component);
            join(r,isolated.getValue(),isolated.getValue().ground().getY());
        }
        r.invalidate();
    }
    private static boolean join(ProductionRegion r,ProductionRegion.Cell start,int height) {
        long begin=ProductionRegion.key(start.ground().getX(),start.ground().getZ());
        if(r.farmPath.contains(begin))return Math.abs(start.ground().getY()-height)<=1;
        record Node(long key,double cost) { }
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::cost));
        var costs=new HashMap<Long,Double>();var parents=new HashMap<Long,Long>();costs.put(begin,0D);open.add(new Node(begin,0));
        Long found=null;
        while(!open.isEmpty()) {
            var n=open.remove();if(n.cost()>costs.getOrDefault(n.key(),Double.MAX_VALUE))continue;
            if(r.farmPath.contains(n.key())){found=n.key();break;}
            var c=r.cells.get(n.key());
            for(var d:Direction.Plane.HORIZONTAL) {
                var next=r.cell(c.ground().relative(d));if(!usable(r,next))continue;
                long k=ProductionRegion.key(next.ground().getX(),next.ground().getZ());
                double value=n.cost()+1+Math.abs(next.ground().getY()-c.ground().getY())*3;
                if(value>=costs.getOrDefault(k,Double.MAX_VALUE))continue;
                costs.put(k,value);parents.put(k,n.key());open.add(new Node(k,value));
            }
        }
        if(found==null)return false;
        var line=new ArrayList<Long>();for(Long k=found;k!=null;k=parents.get(k))line.add(k);
        int y=r.cells.get(found).ground().getY();if(Math.abs(height-y)>line.size())return false;
        var targets=new LinkedHashMap<Long,Integer>();
        for(int i=1;i<line.size();i++) {
            long k=line.get(i);var c=r.cells.get(k);int remaining=line.size()-1-i;
            int next=Math.clamp(c.ground().getY(),y-1,y+1);
            next=Math.clamp(next,height-remaining-1,height+remaining+1);
            if(Math.abs(next-y)>1 || r.reviewFixed.contains(k) && next!=c.ground().getY())return false;
            targets.put(k,next);y=next;
        }
        for(var entry:targets.entrySet()) {
            long k=entry.getKey();int next=entry.getValue();var c=r.cells.get(k);
            if(next!=c.ground().getY())r.gradeOrigins.putIfAbsent(k,c.ground().getY());
            r.cells.put(k,new ProductionRegion.Cell(c.ground().atY(next),ProductionRegion.Use.PATH,c.production(),c.type()));
            r.farmPath.add(k);
        }
        return true;
    }
    private static Set<Long> reachable(ProductionRegion r,Set<Long> seeds) {
        var seen=new HashSet<Long>();var queue=new ArrayDeque<Long>();
        for(long k:seeds)if(r.cells.containsKey(k)){seen.add(k);queue.add(k);}
        while(!queue.isEmpty()) {
            var c=r.cells.get(queue.removeFirst());
            for(var d:Direction.Plane.HORIZONTAL) {
                var n=r.cell(c.ground().relative(d));if(n==null || !usable(r,n) || Math.abs(c.ground().getY()-n.ground().getY())>1)continue;
                long k=ProductionRegion.key(n.ground().getX(),n.ground().getZ());if(seen.add(k))queue.add(k);
            }
        }
        return seen;
    }
    public static int connectedCrops(ProductionRegion r) {
        var connected=reachable(r,r.gateFacings.keySet());
        return (int)r.cells.entrySet().stream().filter(e->connected.contains(e.getKey()) && e.getValue().use()==ProductionRegion.Use.CROP).count();
    }
    static boolean usable(ProductionRegion r,ProductionRegion.Cell c) {
        if(c==null)return false;
        return c.use()!=ProductionRegion.Use.BOUNDARY && c.use()!=ProductionRegion.Use.GATE
                && c.use()!=ProductionRegion.Use.WATER && !r.waterBarriers.contains(ProductionRegion.key(c.ground().getX(),c.ground().getZ()));
    }
    private static int progress(BlockPos p,BlockPos origin,Direction d){return (p.getX()-origin.getX())*d.getStepX()+(p.getZ()-origin.getZ())*d.getStepZ();}
    private static int lateral(BlockPos p,BlockPos origin,Direction d){return progress(p,origin,d.getClockWise());}
}

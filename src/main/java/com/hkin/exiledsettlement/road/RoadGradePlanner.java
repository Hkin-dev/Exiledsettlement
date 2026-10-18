package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Resumable longitudinal grading; this only chooses heights, never edits a block. */
final class RoadGradePlanner {
    // Keep the last non-flat direction across landings: up-flat-down is still a hump.
    private record Key(int y,int slope,int trend) { }
    // Geometry is compared before soft terrain cost. Otherwise a wider/longer
    // hollow can always overpower any finite penalty for reversing direction.
    private record Entry(int travel,int reversals,double cost,Key parent) { }
    private final List<BlockPos> original;
    private final List<Map<Key,Entry>> layers=new ArrayList<>();
    private final List<BlockPos> corridor;
    private List<BlockPos> result;
    private int row;
    private List<Integer> rowCandidates;
    private Map<Key,Entry> rowLayer;
    private int candidateCursor;
    RoadGradePlanner(List<BlockPos> line){original=List.copyOf(line);corridor=new ArrayList<>(line);}
    boolean done(){return result!=null;}
    List<BlockPos> result(){return result;}
    private boolean corner(int i){
        if(i<=0 || i>=original.size()-1)return false;
        var a=original.get(i-1);var p=original.get(i);var b=original.get(i+1);
        return (a.getX()!=p.getX() || b.getX()!=p.getX()) && (a.getZ()!=p.getZ() || b.getZ()!=p.getZ());
    }
    private boolean fixed(ServerLevel level,SettlementRecord s,int i){
        var p=original.get(i);return BridgeService.span(level,s,p) || i==0 || i==original.size()-1 || RoadSpaceData.get(level).manual(p)
                || PlayerRoadEdits.retained(level,p) || AdaptiveRoadService.fixedPassage(level,s,p)
                || AdaptiveRoadService.sharedAt(s,p) && AdaptiveRoadService.walkSurface(level,p) && !JointRoadGradePlanner.adjustable(s,p);
    }
    private static int compare(Entry a,Entry b){
        int order=Integer.compare(a.travel(),b.travel());
        if(order==0)order=Integer.compare(a.reversals(),b.reversals());
        return order==0?Double.compare(a.cost(),b.cost()):order;
    }
    void advance(ServerLevel level,SettlementRecord s){
        advance(level,s,System.nanoTime()+2_000_000L);
    }
    void advance(ServerLevel level,SettlementRecord s,long deadline){
        if(done())return;
        if(original.isEmpty()){result=original;return;}
        var p=original.get(row);
        if(rowCandidates==null){
        var candidates=new LinkedHashSet<Integer>();
        boolean fixed=fixed(level,s,row);
        if(fixed)candidates.add(p.getY());
        else {
            // Local alternatives plus an endpoint interpolation allow a deep
            // supported crossing without turning a calculation window into a depth quota.
            for(int dy=-4;dy<=4;dy++)candidates.add(p.getY()+dy);
            int interpolated=(int)Math.round(original.getFirst().getY()
                    +(original.getLast().getY()-original.getFirst().getY())*(double)row/Math.max(1,original.size()-1));
            for(int dy=-1;dy<=1;dy++)candidates.add(interpolated+dy);
            var shared=com.hkin.exiledsettlement.terrain.PublicGroundService.planningTargetAt(s,p);
            if(shared!=null)for(int dy=-1;dy<=1;dy++)candidates.add(shared+dy);
            for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL){var edited=RoadSpaceData.get(level).playerSurface(p.relative(d));
                if(edited!=null && PlayerRoadEdits.retained(level,edited))candidates.add(RoadSurfaceProfile.landingGround(level,edited,d.getOpposite()));
            }
        }
        var player=RoadSpaceData.get(level).playerSurface(p);
        if(player!=null && PlayerRoadEdits.retained(level,player)){candidates.clear();candidates.add(player.getY());}
        rowCandidates=List.copyOf(candidates);rowLayer=new HashMap<>();candidateCursor=0;
        }
        var layer=rowLayer;
        // Yield between individual candidate heights, not after a complete row.
        // World/protection reads stay fresh in each candidate; only mathematical
        // costs and the cursor survive to the next tick.
        for(int work=0;candidateCursor<rowCandidates.size() && (work==0 || System.nanoTime()<deadline);work++){
            int y=rowCandidates.get(candidateCursor++);
            var at=p.atY(y);corridor.set(row,at);
            var cross=AutoRoadService.corridorCell(level,s,corridor,row);
            if(cross==null)continue;
            double earth=cross.stream().mapToDouble(cell->RoadEarthworkService.effort(level,s,cell)).sum()*1.5D
                    +Math.abs(y-p.getY())*.15D;
            if(row==0){layer.put(new Key(y,0,0),new Entry(0,0,earth,null));continue;}
            for(var previous:layers.getLast().entrySet()){
                int slope=y-previous.getKey().y();if(Math.abs(slope)>1)continue;
                // A turn and its two adjoining rows form one landing. Otherwise
                // overlapping three-wide corner cells acquire contradictory heights.
                if(slope!=0 && (corner(row) || corner(row-1)))continue;
                int trend=slope==0?previous.getKey().trend():slope;
                double cost=previous.getValue().cost()+earth+Math.abs(slope)*32D
                        +(slope!=previous.getKey().slope()?12D:0D)
                        +(slope!=0 && previous.getKey().slope()!=0?10D:0D)
                        +(slope!=0 && previous.getKey().trend()!=0 && slope!=previous.getKey().trend()?80D:0D);
                int reversal=slope!=0 && previous.getKey().trend()!=0 && slope!=previous.getKey().trend()?1:0;
                var value=new Entry(previous.getValue().travel()+Math.abs(slope),previous.getValue().reversals()+reversal,cost,previous.getKey());
                var key=new Key(y,slope,trend);var old=layer.get(key);
                if(old==null || compare(value,old)<0)layer.put(key,value);
            }
        }
        if(candidateCursor<rowCandidates.size())return;
        if(layer.isEmpty()){result=original;return;}
        layers.add(layer);
        rowCandidates=null;rowLayer=null;
        if(++row==original.size()){
            Key selected=layer.entrySet().stream().min((a,b)->{
                int order=compare(a.getValue(),b.getValue());return order!=0?order:Integer.compare(a.getKey().y(),b.getKey().y());
            }).orElseThrow().getKey();
            var graded=new ArrayList<BlockPos>(original);
            for(int i=row-1;i>=0;i--){graded.set(i,original.get(i).atY(selected.y()));selected=layers.get(i).get(selected).parent();}
            result=List.copyOf(graded);
        }
    }
}

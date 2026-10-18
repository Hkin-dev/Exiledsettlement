package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.TerrainService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Bounded, read-only survey: selected body first, then independently fitted landings. */
final class BridgeBankSurvey {
    static final int SEARCH_LIMIT=64;
    record Column(int floor,boolean dry,boolean water) {}
    record Landing(int outer,int rampStart,int ground) {}
    private final Map<Long,Column> columns=new HashMap<>();
    private int bodyCursor,side,offset,lateral,dryStart,dryRun;
    private Landing candidate;
    private int candidateColumn;
    private ArrayDeque<BlockPos> exitQueue;
    private final Set<BlockPos> exitSeen=new HashSet<>();
    private PlayerBridgePlan.Obstruction rejected;
    private int highestWater=Integer.MIN_VALUE;
    private boolean bodyDone;
    Landing near,far;
    String problem="";
    BlockPos conflict;
    private long key(int row,int side){return BlockPos.asLong(row,0,side);}
    Column column(int row,int side){return columns.get(key(row,side));}
    private Column sample(ServerLevel l,SettlementRecord s,PlayerBridgePlan p,int row,int lateral){
        var at=p.at(row,lateral,p.surface);
        if(!s.contains(at) || !l.hasChunkAt(at)){
            problem="自动接岸超出已加载领地，请缩短桥体或调整方向";conflict=at;return null;
        }
        int y=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
        while(y>l.getMinBuildHeight()){
            var state=l.getBlockState(at.atY(y));
            if(!(state.isAir() || TerrainService.soft(state) || state.is(net.minecraft.tags.BlockTags.LOGS)
                    || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.hasBlockEntity()))break;
            y--;
        }
        var floor=at.atY(y);var state=l.getBlockState(floor);
        boolean water=state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean dry=state.getFluidState().isEmpty() && FarmEarthworkPlan.support(l,floor);
        var result=new Column(y,dry,water);columns.put(key(row,lateral),result);
        if(water)highestWater=Math.max(highestWater,y);
        return result;
    }
    boolean advance(ServerLevel l,SettlementRecord s,PlayerBridgePlan p,int budget){
        long deadline=System.nanoTime()+2_000_000;
        for(int read=0;read<budget && (read==0 || System.nanoTime()<deadline);read++){
            if(candidate!=null){
                int direction=side==0?-1:1,edge=side==0?p.start:p.end;
                if(exitQueue!=null){
                    if(exitQueue.isEmpty()){
                        rejected=new PlayerBridgePlan.Obstruction("出口只接到孤立高坎，需继续拟合可往返岸地",p.at(candidate.outer()+direction,p.sideMin+p.width/2,candidate.ground()));
                        candidate=null;exitQueue=null;if(!nextOffset(p))return false;continue;
                    }
                    var feet=exitQueue.removeFirst();int outward=direction*(p.longitudinal(feet)-candidate.outer());
                    if(outward>=3){
                        exitQueue=null;
                        if(side==0){near=candidate;candidate=null;side=1;offset=0;dryRun=0;rejected=null;}
                        else {far=candidate;candidate=null;return true;}
                        continue;
                    }
                    for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++){
                        var next=feet.relative(d).above(dy);int rowDistance=direction*(p.longitudinal(next)-candidate.outer());
                        int cross=p.alongX?next.getZ():next.getX();
                        if(rowDistance<1 || rowDistance>5 || cross<p.sideMin-8 || cross>p.sideMin+p.width+7
                                || Math.abs(next.getY()-candidate.ground())>5 || exitSeen.contains(next) || !s.contains(next))continue;
                        if(dy>0 && !l.getBlockState(feet.above(2)).getCollisionShape(l,feet.above(2)).isEmpty())continue;
                        if(BridgeConstructionSequence.dryStand(l,next)){exitSeen.add(next);exitQueue.addLast(next);}
                    }
                    continue;
                }
                int row=edge+direction*(candidateColumn/p.width),lat=p.sideMin+candidateColumn%p.width;
                int distance=Math.max(0,direction*(row-candidate.rampStart()));
                int y=p.surface+Integer.signum(candidate.ground()-p.surface)*Math.min(Math.abs(candidate.ground()-p.surface),distance/2);
                var sampled=column(row,lat);
                boolean bank=sampled.dry() && direction*(row-candidate.rampStart())>=0;
                var blocked=p.columnObstruction(l,s,row,lat,y,bank,sampled.floor());
                if(blocked!=null){
                    if(rejected==null)rejected=blocked;
                    candidate=null;
                    if(!nextOffset(p))return false;
                }else if(++candidateColumn==(Math.abs(candidate.outer()-edge)+1)*p.width){
                    // A single dry block atop an isolated ridge is not an exit.
                    // Check a short bidirectional walking connection on untouched
                    // land, permitting lateral detours instead of demanding flat rows.
                    exitQueue=new ArrayDeque<>();exitSeen.clear();
                    var exit=p.at(candidate.outer()+direction,p.sideMin+p.width/2,candidate.ground());
                    exitQueue.add(exit);exitSeen.add(exit);
                }
                continue;
            }
            if(!bodyDone){
                int row=p.start+bodyCursor/p.width,lat=p.sideMin+bodyCursor%p.width;
                if(sample(l,s,p,row,lat)==null)return false;
                if(++bodyCursor==(p.end-p.start+1)*p.width){
                    if(p.automaticHeight)p.surface=Math.max(highestWater==Integer.MIN_VALUE?l.getMinBuildHeight()+3:highestWater+3,Math.max(p.first.getY(),p.second.getY())+1);
                    p.suggestedSurface=Math.max(p.surface,highestWater+3);
                    bodyDone=true;
                }
                continue;
            }
            int direction=side==0?-1:1,edge=side==0?p.start:p.end,row=edge+direction*offset;
            if(sample(l,s,p,row,p.sideMin+lateral)==null)return false;
            if(++lateral<p.width)continue;
            lateral=0;
            boolean dry=true;for(int i=0;i<p.width;i++)dry &= column(row,p.sideMin+i).dry();
            if(!dry)dryRun=0;
            else {
                int ground=column(row,p.sideMin+p.width/2).floor()+1;
                // Uneven natural ground is the material to cut/fill, not a
                // requirement for an already-built flat ramp. Only water
                // interrupts the dry run; the chosen exit fixes its end height.
                if(dryRun++==0)dryStart=row;
                // Current row stays untouched as the actual bank exit; the previous
                // row is the last constructed landing. Only dry rows may slope.
                int outer=row-direction;
                int available=Math.abs(outer-dryStart);
                // The selected edge is already surveyed land when applicable.
                // A level connection needs no extra flat apron; stairs consume
                // exactly two rows per block of elevation, ending at this exit.
                if(direction*(outer-edge)>=0 && available>=2*Math.abs(p.surface-ground)
                        && BridgeConstructionSequence.dryStand(l,p.at(row,p.sideMin+p.width/2,ground))){
                    // Keep the approach suspended until the shortest safe ramp
                    // fits on land. Validate its actual volume before accepting
                    // an exit, and try the next profile if this one is obstructed.
                    candidate=new Landing(outer,outer-direction*2*Math.abs(p.surface-ground),ground);
                    candidateColumn=0;
                    continue;
                }
            }
            if(!nextOffset(p))return false;
        }
        return near!=null && far!=null;
    }
    private boolean nextOffset(PlayerBridgePlan p){
        if(++offset<=SEARCH_LIMIT)return true;
        problem=rejected==null?"桥体这一端在64格内未找到可接上的陆地，请调整桥体方向或位置":"自动桥头无法接通："+rejected.reason()+"："+rejected.position().toShortString();
        conflict=rejected==null?p.at((side==0?p.start:p.end)+(side==0?-SEARCH_LIMIT:SEARCH_LIMIT),p.sideMin+p.width/2,p.surface):rejected.position();
        return false;
    }
}

package com.hkin.exiledsettlement.road;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Independent structural order. Residents advance it from a fixed dry shore worksite. */
public final class BridgeConstructionSequence {
    public record Batch(int phase, int row, List<Integer> actions, List<BlockPos> workArea) {}
    private final List<Batch> batches = new ArrayList<>();
    private final int[] batchOf;
    private final Map<BlockPos,BlockState> virtual=new HashMap<>();
    private int verifiedBatches;

    public BridgeConstructionSequence(PlayerBridgePlan plan) {
        batchOf = new int[plan.steps.size()];
        var area=plan.stagingArea();
        for (int from=0; from<plan.steps.size();) {
            int to=from+1;
            var first=plan.steps.get(from);
            while(to<plan.steps.size() && plan.phases.get(to).equals(plan.phases.get(from))
                    && plan.rows.get(to).equals(plan.rows.get(from))
                    && plan.steps.get(to).fill()==first.fill()
                    && plan.steps.get(to).position().getY()==first.position().getY())to++;
            var actions=new ArrayList<Integer>();
            for(int i=from;i<to;i++){batchOf[i]=batches.size();actions.add(i);}
            int phase=plan.phases.get(from),row=plan.rows.get(from);
            batches.add(new Batch(phase,row,List.copyOf(actions),List.copyOf(area)));
            from=to;
        }
    }
    public List<Batch> batches(){return List.copyOf(batches);}
    public Batch forAction(int index){return index>=0 && index<batchOf.length?batches.get(batchOf[index]):null;}

    /** Dry run only. The virtual world changes, never the real one. */
    public boolean verified(){return verifiedBatches==batches.size();}
    public String verifySlice(ServerLevel level,PlayerBridgePlan plan) {
        if(plan.buildingModel){
            // Ordinary timed buildings do not require a pre-existing standing
            // station, connected beam or virtual return corridor at confirmation.
            verifiedBatches=batches.size();return "";
        }
        long deadline=System.nanoTime()+2_000_000;
        for(int count=0;verifiedBatches<batches.size() && count<32 && (count==0 || System.nanoTime()<deadline);count++) {
            var batch=batches.get(verifiedBatches);
            int previous=batch.row()-plan.direction();
            if(Math.abs(plan.heightAt(batch.row())-plan.heightAt(previous))>1)
                return "接岸坡度不能形成连续施工退路";
            var usable=batch.workArea().stream().filter(p->standable(level,virtual,p)).toList();
            if(usable.isEmpty())return "起岸桥头缺少稳定工作区，请调整选区或清理桥头";
            for(int i:batch.actions()){
                var step=plan.steps.get(i);
                if(step.fill()) {
                    var at=step.position();boolean supported=false;
                    if(plan.anchors.contains(at) || plan.deck.contains(at) || plan.rails.contains(at)) {
                        var below=virtual.getOrDefault(at.below(),level.getBlockState(at.below()));
                        supported=!below.getCollisionShape(level,at.below()).isEmpty() && below.getFluidState().isEmpty();
                    } else for(var d:net.minecraft.core.Direction.values()) {
                        var neighbour=at.relative(d);
                        if(plan.beams.contains(neighbour) && virtual.containsKey(neighbour) && !virtual.get(neighbour).isAir())supported=true;
                    }
                    if(!supported)return "施工序列缺少先行支撑："+at.toShortString();
                }
                virtual.put(step.position(),step.target());
            }
            // Every newly completed row must extend a continuous walkable return corridor.
            if(batch.phase()<2 && batch.actions().stream().anyMatch(i->plan.steps.get(i).fill())) {
                int height=plan.heightAt(batch.row())-(batch.phase()==0?1:0);
                boolean finalLayer=batch.actions().stream().anyMatch(i->plan.steps.get(i).position().getY()==height-1);
                if(finalLayer)for(int side=plan.sideMin+1;side<plan.sideMin+plan.width-1;side++)
                    if(!standable(level,virtual,plan.at(batch.row(),side,height)))
                        return "单岸施工通道不能保持净空和退路："+plan.at(batch.row(),side,height).toShortString();
            }
            verifiedBatches++;
        }
        if(verified())virtual.clear();
        return "";
    }
    private static boolean standable(ServerLevel level,Map<BlockPos,BlockState> world,BlockPos p) {
        if(!level.hasChunkAt(p))return false;
        var below=world.getOrDefault(p.below(),level.getBlockState(p.below()));
        if(!below.getFluidState().isEmpty() || below.getCollisionShape(level,p.below()).isEmpty())return false;
        for(var at:List.of(p,p.above())) {
            var state=world.getOrDefault(at,level.getBlockState(at));
            if(!state.getFluidState().isEmpty() || !state.getCollisionShape(level,at).isEmpty())return false;
        }
        return true;
    }
    public static boolean dryStand(ServerLevel level,BlockPos at){return standable(level,Map.of(),at);}
}

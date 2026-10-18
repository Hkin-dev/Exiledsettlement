package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;
import java.util.function.Predicate;

/** One resident action, one tree, one loot commit. No profession or vertical reach gate. */
public final class ResidentTreeFelling {
    private ResidentTreeFelling() {}
    private static final int LIMIT=4096;
    public static boolean vegetation(BlockState state) {
        return state.is(BlockTags.LOGS) || leaf(state) || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }
    private static boolean wood(BlockState s){return vegetation(s) && !leaf(s);}
    private static boolean leaf(BlockState s){return s.is(BlockTags.LEAVES) && (!s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT));}
    private static SiteClearance.Result failed(String why){return new SiteClearance.Result(List.of(),List.of(),why);}

    /** Null means an ordinary single block, not a recognised tree. Failures never cut its root. */
    public static SiteClearance.Result clear(ServerLevel level,BlockPos at,LivingEntity worker,ItemStack tool,
            Predicate<BlockPos> allowed,BlockState replacement) {
        long start=TreeWorkMetrics.ENABLED?System.nanoTime():0;
        try{return clearWork(level,at,worker,tool,allowed,replacement);}finally{TreeWorkMetrics.record("felling",start);}
    }
    private static SiteClearance.Result clearWork(ServerLevel level,BlockPos at,LivingEntity worker,ItemStack tool,
            Predicate<BlockPos> allowed,BlockState replacement) {
        if(!(worker instanceof SettlementCitizenEntity) || !level.hasChunkAt(at) || !vegetation(level.getBlockState(at)))return null;
        var settlement=SettlementSavedData.get(level).findById(CitizenProjectionService.settlementId(worker)).orElse(null);
        if(settlement==null)return null;
        var citizen=settlement.citizen(CitizenProjectionService.citizenId(worker));
        if(citizen==null)return null;
        var survey=TreeSurvey.fresh(level,at);
        if(survey.kind()==TreeSurvey.Kind.ORDINARY)return null;
        if(!survey.tree())return failed(survey.reason());
        var parts=new ArrayList<BlockPos>(survey.parts().keySet());
        var before=parts.stream().map(survey.parts()::get).toList();
        // Complete preflight before any mutation, then stage without physics/drop side effects.
        for(int i=0;i<parts.size();i++) {var p=parts.get(i);var state=before.get(i);
            if(!allowed.test(p) || !permitted(level,settlement,citizen,p) || state.getDestroySpeed(level,p)<0
                    || !CommonHooks.canEntityDestroy(level,p,worker))return failed("PROTECTED_AREA");
        }
        int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
        var snapshots=parts.stream().map(p->BlockSnapshot.create(level.dimension(),level,p,flags)).toList();
        var drops=new ArrayList<ItemStack>();
        for(int i=0;i<parts.size();i++) {var p=parts.get(i);var state=before.get(i);
            drops.addAll(Block.getDrops(state,level,p,level.getBlockEntity(p),worker,tool));
            var next=p.equals(at)?replacement:state.getFluidState().createLegacyBlock();
            if(!level.getBlockState(p).equals(state) || !level.setBlock(p,next,flags)
                    || EventHooks.onBlockPlace(worker,snapshots.get(i),Direction.UP) || !level.getBlockState(p).equals(next)) {
                for(int j=i;j>=0;j--)snapshots.get(j).restore(flags);
                return failed("PROTECTED_AREA");
            }
        }
        TreeSurvey.invalidate(level);
        for(int i=0;i<parts.size();i++)level.updateNeighborsAt(parts.get(i),before.get(i).getBlock());
        return new SiteClearance.Result(List.copyOf(drops),List.copyOf(parts),"");
    }
    /** Permission preview for automatic road preparation. External events run only during execution. */
    public static boolean plannable(ServerLevel level,SettlementRecord s,BlockPos at) {
        if(!level.hasChunkAt(at) || !TreeSurvey.candidate(level.getBlockState(at)))return false;
        var tree=TreeSurvey.inspect(level,at);
        return tree.tree() && tree.parts().entrySet().stream().allMatch(e->permitted(level,s,null,e.getKey())
                && e.getValue().getDestroySpeed(level,e.getKey())>=0 && e.getValue().getFluidState().isEmpty());
    }
    static boolean permitted(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,BlockPos p) {
        var site=BuildingWorkPolicy.assigned(settlement,citizen);
        if(!settlement.contains(p) || settlement.community().danger(p) || BuildingWorkPolicy.automaticForbidden(settlement,site,p)
                || BuildingWorkPolicy.suppressed(settlement,site,p) || level.getBlockEntity(p)!=null)return false;
        for(var b:settlement.buildings())if(b.active()) {
            if(b.type()==BuildingType.TREE_FARM && b.containsColumn(p)) {
                if(citizen==null || citizen.workState()!=CitizenWorkState.TO_FOREST || !b.id().equals(citizen.targetBuildingId()))return false;
            } else if(b.contains(p))return false;
        }
        var task=citizen==null?null:WorkTaskService.task(settlement,citizen);
        for(var b:settlement.formalBlueprints())if(!b.cancellationQueued()) {
            var goal=BlueprintTemplates.create(b).placementAt(p);
            if(goal!=null && goal.mode().isFinalStructure() && !goal.state().isAir()
                    && (BlueprintService.matchesPlacedState(level.getBlockState(p),goal.state())
                        || task==null || !b.id().equals(task.mother())))return false;
        }
        return true;
    }
}

package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.terrain.TerrainProject;
import com.hkin.exiledsettlement.terrain.TerrainState;
import com.hkin.exiledsettlement.terrain.TerrainProjectPages;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

/** Detached serialization fixtures, with no entity, world or completion mutation. */
public final class TerrainPersistence91Checks {
    public static String run(){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var steps=new ArrayList<TerrainProject.Step>();
        var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.WEST)
                .setValue(StairBlock.HALF,Half.TOP);
        for(int i=0;i<8193;i++)steps.add(new TerrainProject.Step(new BlockPos(i%31,140+i%17,i/31),
                i%3==0?stair:i%3==1?Blocks.AIR.defaultBlockState():Blocks.ANDESITE.defaultBlockState(),
                i%2==0?Blocks.DIRT.defaultBlockState():Blocks.STONE.defaultBlockState()));
        var p=new TerrainProject(UUID.randomUUID(),"Detached exact ordered paid history",steps.getFirst().position(),steps);
        p.mark(0);p.mark(20);p.abandon(22);p.workCursor=91;p.layoutVersion=4;p.stairLayoutVersion=2;
        p.publicGround=true;p.road=true;p.explicit=true;p.heightSuperseded=true;p.connectionWarning="saved warning";
        p.regionOwner=UUID.randomUUID();p.dependencies.add(UUID.randomUUID());p.dependencies.add(UUID.randomUUID());
        p.bridge=true;p.areaClearance=true;p.bridgeReviewCursor=7;p.escapeFor=steps.get(9).position();p.escapingCitizen=UUID.randomUUID();
        p.bridgeBeams.add(steps.get(3).position());p.bridgeAnchors.add(steps.get(4).position());p.bridgeDeck.add(steps.get(5).position());
        p.bridgeRails.add(steps.get(6).position());p.bridgeOpenFaces.add(steps.get(7).position());
        p.groundSurfaces.add(steps.get(8).position());p.accessSurfaces.add(steps.get(10).position());
        var saved=p.save();var restored=TerrainProject.load(saved.copy());
        if(!restored.steps.equals(p.steps) || !restored.save().equals(saved))throw new IllegalStateException("ORDER_PROPERTIES_OR_PAID_HISTORY_LOST");
        if(saved.getList("StepPalette",Tag.TAG_COMPOUND).size()!=5 || saved.contains("Pages")
                || saved.getLongArray("StepPositions").length!=8193)throw new IllegalStateException("PALETTE_DID_NOT_REMOVE_REPEATED_COMPOUNDS");
        var old=saved.copy();old.putInt("Version",3);old.remove("StepPalette");old.remove("StepPositions");old.remove("StepTargets");old.remove("StepOriginals");
        var pages=new ListTag();var page=new CompoundTag();var entries=new ListTag();
        for(var step:p.steps){var entry=new CompoundTag();entry.putLong("At",step.position().asLong());
            entry.put("Target",NbtUtils.writeBlockState(step.target()));entry.put("Original",NbtUtils.writeBlockState(step.original()));entries.add(entry);
            if(entries.size()==4096){page.put("Steps",entries);pages.add(page);page=new CompoundTag();entries=new ListTag();}}
        if(!entries.isEmpty()){page.put("Steps",entries);pages.add(page);}old.put("Pages",pages);
        if(!TerrainProject.load(old).save().equals(saved))throw new IllegalStateException("CURRENT_TEST_HISTORY_RELOAD_LOST");
        var malformed=saved.copy();malformed.putIntArray("StepTargets",new int[1]);
        boolean rejected=false;try{TerrainProject.load(malformed);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("MISMATCHED_ARRAYS_SILENTLY_ACCEPTED");
        malformed=saved.copy();var bad=malformed.getIntArray("StepOriginals");bad[0]=1000;malformed.putIntArray("StepOriginals",bad);
        rejected=false;try{TerrainProject.load(malformed);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("INVALID_PALETTE_SILENTLY_ACCEPTED");
        malformed=saved.copy();malformed.putInt("Version",5);
        rejected=false;try{TerrainProject.load(malformed);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("UNSUPPORTED_VERSION_SILENTLY_BECAME_EMPTY_WORK");
        var state=new TerrainState();state.index(p);
        for(int i=0;i<256;i++){
            var at=new BlockPos(i,70,12);var order=new TerrainProject(UUID.randomUUID(),"history "+i,at,
                    List.of(new TerrainProject.Step(at,Blocks.COBBLESTONE.defaultBlockState(),Blocks.STONE.defaultBlockState())));
            order.publicGround=true;order.road=i%2==0;order.dependencies.add(p.id);
            if(i%3==0)order.mark(0);if(i%3==1)order.cancelled=true;if(i%3==2)order.abandon(0);
            state.index(order);
        }
        var compact=state.save();
        if(compact.contains("Projects") || compact.getInt("ProjectCount")!=257
                || compact.getList("ProjectPages",Tag.TAG_COMPOUND).size()!=3)throw new IllegalStateException("HISTORY_NOT_BOUNDED_IN_BATCHES");
        var loadedState=new TerrainState();loadedState.load(compact.copy());
        if(!new ArrayList<>(state.projects.keySet()).equals(new ArrayList<>(loadedState.projects.keySet()))
                || state.projects.values().stream().anyMatch(project->!project.save().equals(loadedState.projects.get(project.id).save())))
            throw new IllegalStateException("BATCH_HISTORY_PAID_PENDING_OR_STATUS_LOST");
        var legacy=compact.copy();legacy.remove("ProjectsEncoding");legacy.remove("ProjectCount");legacy.remove("ProjectPages");
        var flat=new ListTag();state.projects.values().forEach(project->flat.add(project.save()));legacy.put("Projects",flat);
        var legacyState=new TerrainState();legacyState.load(legacy);
        if(!legacyState.save().equals(compact))throw new IllegalStateException("FLAT_HISTORY_MIGRATION_CHANGED_RECORDS");
        var broken=compact.copy();broken.putInt("ProjectCount",256);
        rejected=false;try{new TerrainState().load(broken);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("TRUNCATED_TOTAL_SILENTLY_ACCEPTED");
        broken=compact.copy();broken.getList("ProjectPages",Tag.TAG_COMPOUND).getCompound(0).putInt("Count",127);
        rejected=false;try{new TerrainState().load(broken);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("TRUNCATED_BATCH_SILENTLY_ACCEPTED");
        broken=compact.copy();broken.putInt("ProjectsEncoding",2);
        rejected=false;try{new TerrainState().load(broken);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("FUTURE_BATCH_VERSION_SILENTLY_ACCEPTED");
        return "PASS 12: 8193 ordered steps and all paid/status/dependency metadata survive deep-copy reload; five exact states replace repeated compounds; legacy three-page steps preserve every step; malformed arrays, invalid palette and future step version rejected; 257 complete, cancelled, abandoned and pending records survive three bounded batches and deep-copy; legacy flat history migrates exactly; invalid total, batch count and future encoding rejected; detached fixtures only";
    }
    private TerrainPersistence91Checks(){}
}

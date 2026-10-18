package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit blocker inputs, classification never credits autonomous work. */
public final class Clearance108Checks {
    private static void check(boolean ok,String label,List<String> checks){if(!ok)throw new IllegalStateException(label);checks.add(label);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(59600,250,59600);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Unified clearance actual blocker inputs").value();
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        var target=core.east(20);var b=s.addBlueprint(BuildingType.HOUSE,target,core.above(40),Direction.NORTH,s.ownerId(),false);
        b.withCustomTemplate(CustomTemplateSnapshot.create("actual clearance goal",BuildingType.HOUSE,target,Direction.NORTH,List.of(new BlueprintPlacement(target,Blocks.COMPOSTER.defaultBlockState(),Items.COMPOSTER)),List.of(new BlueprintEntrance(target.west(),Direction.NORTH))));
        c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,b.id());
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        try {
            for(var raw:BlockPos.betweenClosed(target.offset(-1,-1,-1),target.offset(1,5,1))){var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));}
            for(int roof=1;roof<=4;roof++) {
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=5;y++)l.setBlock(target.offset(x,y,z),y<roof?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
                b.addClearanceTarget(target,l.getBlockState(target));
                var feet=target.west().above(roof);
                check(target.above(roof-1).equals(WorkerAccessService.previewCut(l,s,c,feet,target)),"actual obstruction supplies the next top-down cut for cover "+roof,checks);
            }
            check(!b.cancellationQueued() && b.hasClearanceTarget(target),"original clearance stays pending and uncancelled",checks);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=5;y++)l.setBlock(target.offset(x,y,z),y<2?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            l.setBlock(target,Blocks.AIR.defaultBlockState(),2);b.removeClearanceTarget(target);
            l.setBlock(target.above(),Blocks.DIRT.defaultBlockState(),2);
            check(target.above().equals(WorkerAccessService.previewCut(l,s,c,target.west().above(2),target)),"missing air structure still requests its actual ordinary-soil access face",checks);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=5;y++)l.setBlock(target.offset(x,y,z),y<4?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            var feet=target.west().above(4);l.setBlock(target.above(3),Blocks.CHEST.defaultBlockState(),2);
            check(WorkerAccessService.previewCut(l,s,c,feet,target)==null,"unknown container stays protected",checks);
            l.setBlock(target.above(3),Blocks.BEDROCK.defaultBlockState(),2);
            check(WorkerAccessService.previewCut(l,s,c,feet,target)==null,"unbreakable blocker stays protected",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs restored; actual resident chain separate";
        } finally {s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private Clearance108Checks(){}
}

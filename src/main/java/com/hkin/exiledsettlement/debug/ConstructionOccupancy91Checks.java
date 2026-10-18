package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

/** Reproduces the real low-floor head overlap; fixture actors are input, not worker movement credit. */
public final class ConstructionOccupancy91Checks {
    private static void check(boolean ok,String message,List<String> checks){if(!ok)throw new IllegalStateException(message);checks.add(message);}
    public static String run(ServerLevel l)throws Exception {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(60000,250,60000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Actual low floor occupancy inputs").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();SettlementCitizenEntity e=null;net.minecraft.world.entity.animal.Cow cow=null;
        try {
            for(var raw:BlockPos.betweenClosed(core.offset(-5,-2,-3),core.offset(7,5,3))){var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));}
            for(int x=-5;x<=7;x++)for(int z=-3;z<=3;z++)l.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
            e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            var target=core.east(2).above();e.setPos(target.getX()+.5,250,target.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),e.blockPosition());
            var plank=new BlueprintPlacement(target,Blocks.SPRUCE_PLANKS.defaultBlockState(),Items.SPRUCE_PLANKS,BlueprintPlacementMode.STRUCTURE);
            var site=s.addBlueprint(BuildingType.HOUSE,target,Direction.NORTH,s.ownerId(),false);
            site.withCustomTemplate(CustomTemplateSnapshot.create("head floor input",BuildingType.HOUSE,target,Direction.NORTH,List.of(plank),List.of(new BlueprintEntrance(target,Direction.NORTH))));s.terrain().surveyed.add(site.id());
            var template=BlueprintTemplates.create(site);var stack=new ItemStack(Items.SPRUCE_PLANKS,3);
            check(l.getEntities().get(e.getUUID())==e,"actual worker is active in world occupancy",checks);
            check(ConstructionOccupancy.occupied(l,template,plank,e),"head overlaps the proposed low floor",checks);
            float health=e.getHealth();var result=BlueprintService.placeAt(l,data,s,site,target,stack,e);
            check(!result.success() && result.reason().equals(ConstructionOccupancy.FAILURE),"actual placement refuses the suffocating head block",checks);
            check(l.getBlockState(target).isAir() && stack.getCount()==3 && e.getHealth()==health,"refusal leaves world, material and health unchanged",checks);
            var station=CitizenWorkService.class.getDeclaredMethod("safeStructureStanding",ServerLevel.class,BlueprintRecord.class,SettlementCitizenEntity.class,BlockPos.class,BlockPos.class);station.setAccessible(true);
            check(!(boolean)station.invoke(null,l,site,e,e.blockPosition(),target),"under-floor worker is not admitted as ready",checks);
            check(!(boolean)station.invoke(null,l,site,e,core.west(2),target),"a distant station below the final floor is also rejected",checks);
            var side=target.east(2);l.setBlock(side.below(),Blocks.STONE.defaultBlockState(),2);
            check((boolean)station.invoke(null,l,site,e,side,target),"a real side workface at floor level remains legal",checks);
            var approach=CitizenWorkService.class.getDeclaredMethod("localWorkApproach",ServerLevel.class,SettlementRecord.class,BlueprintRecord.class,SettlementCitizenEntity.class,BlockPos.class,boolean.class);approach.setAccessible(true);
            var face=(BlockPos)approach.invoke(null,l,s,site,e,target,false);
            check(face!=null && face.getY()>=target.getY() && ConstructionOccupancy.safeStanding(l,template,plank,e,face),"workface search finds safe actual ground without returning the underground gap",checks);
            var slab=new BlueprintPlacement(target,Blocks.STONE_SLAB.defaultBlockState(),Items.STONE_SLAB,BlueprintPlacementMode.STRUCTURE);
            var upper=e.getBoundingBox().move(0,1.5,0);
            check(!ConstructionOccupancy.intersects(l,template,slab,upper) && ConstructionOccupancy.intersects(l,template,plank,upper),"partial collision geometry permits standing above a slab but not inside a full block",checks);
            e.setPos(side.getX()+.5,side.getY(),side.getZ()+.5);e.setOnGround(true);
            cow=EntityType.COW.create(l);cow.setPos(target.getX()+.5,250,target.getZ()+.5);
            if(!l.addFreshEntity(cow))throw new IllegalStateException("COW_SPAWN_FAILED");
            check(ConstructionOccupancy.occupied(l,template,plank,e),"another living animal is checked independently of the acting worker",checks);
            result=BlueprintService.placeAt(l,data,s,site,target,stack,e);
            check(!result.success() && result.reason().equals(ConstructionOccupancy.FAILURE) && stack.getCount()==3 && l.getBlockState(target).isAir(),"animal occupancy refuses actual placement without payment",checks);
            cow.discard();cow=null;
            result=BlueprintService.placeAt(l,data,s,site,target,stack,e);
            check(result.success() && l.getBlockState(target).is(Blocks.SPRUCE_PLANKS) && stack.getCount()==2,"after actual input actors clear the volume, normal paid placement succeeds",checks);
            check(e.isAlive() && e.getHealth()==health,"worker remains alive without healing or damage exemptions",checks);
            var foot=core.west(3);var head=foot.east();
            var bedFoot=new BlueprintPlacement(foot,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST).setValue(BedBlock.PART,BedPart.FOOT),Items.RED_BED,BlueprintPlacementMode.STRUCTURE);
            var bedHead=new BlueprintPlacement(head,bedFoot.state().setValue(BedBlock.PART,BedPart.HEAD),Items.RED_BED,BlueprintPlacementMode.STRUCTURE);
            var paired=CustomTemplateSnapshot.create("paired bed input",BuildingType.HOUSE,foot,Direction.NORTH,List.of(bedFoot,bedHead),List.of(new BlueprintEntrance(foot,Direction.NORTH))).template();
            e.setPos(head.getX()+.5,head.getY(),head.getZ()+.5);
            check(ConstructionOccupancy.occupied(l,paired,bedFoot,e),"primary placement checks the occupied second bed half",checks);
            check(!ConstructionOccupancy.safeStanding(l,paired,bedFoot,e,head),"paired future shape also excludes unsafe standing candidates",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit actors and workface inputs restored; no resurrection or movement credit";
        }finally{if(e!=null)e.discard();if(cow!=null)cow.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private ConstructionOccupancy91Checks(){}
}

package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit placement inputs test distant vs on-site clearance, not autonomous navigation. */
public final class Clearance91Checks {
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(51000,250,51000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var created=data.createOrRecover(core,UUID.randomUUID(),"Clearance91 isolated workface");
        if(!created.success())throw new IllegalStateException(created.reason());var s=created.value();
        s.autoRoads().enabled=false;s.terrain().enabled=true;s.decorations().enabled=false;
        var b=s.addBlueprint(BuildingType.HOUSE,core.east(18),Direction.NORTH,s.ownerId(),false);
        var template=BlueprintTemplates.create(b);s.terrain().surveyed.add(b.id());
        var min=template.minCorner();var max=template.maxCorner();
        var target=template.placements().stream().filter(p->p.position().getY()==b.anchor().getY()+1)
                .max(Comparator.comparingInt(p->p.position().getX())).orElseThrow().position();
        var start=new BlockPos(min.getX()-1,b.anchor().getY()+1,target.getZ());
        var saved=new LinkedHashMap<BlockPos,BlockState>();var entities=new ArrayList<SettlementCitizenEntity>();
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        long day=l.getDayTime();var checks=new ArrayList<String>();
        try{
            for(var raw:BlockPos.betweenClosed(new BlockPos(min.getX()-6,248,min.getZ()-6),new BlockPos(max.getX()+6,254,max.getZ()+6))){
                var p=raw.immutable();if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,l.getBlockState(p));
            }
            for(int x=min.getX()-2;x<=max.getX()+2;x++)for(int z=min.getZ()-2;z<=max.getZ()+2;z++)l.setBlock(new BlockPos(x,250,z),Blocks.STONE.defaultBlockState(),2);
            l.setDayTime(6000);l.setBlock(target,Blocks.STONE.defaultBlockState(),2);b.addClearanceTarget(target,Blocks.STONE.defaultBlockState());
            c.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
            var far=entity(l,s,c,start);entities.add(far);c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,b.id());
            check(!CitizenInteractionRange.contains(far,target),"fixture target beyond real interaction range",checks);
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,far);
            check(l.getBlockState(target).is(Blocks.STONE) && b.hasClearanceTarget(target),"distant target neither cleared nor credited",checks);
            check(!CitizenWorkService.constructionWorkObservation(l,s,c,far).contains("stage=ground ready"),"arrival at building edge does not stop clearance approach",checks);
            check(c.inventoryItem(0).getDamageValue()==0,"distant attempt consumes no tool durability",checks);
            far.discard();
            var near=entity(l,s,c,target.west());entities.add(near);c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,b.id());
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,near);
            check(l.getBlockState(target).isAir() && !b.hasClearanceTarget(target),"on-site visible obstruction actually cleared",checks);
            check(c.inventory().stream().filter(a->a.is(Items.IRON_PICKAXE)).mapToInt(ItemStack::getDamageValue).sum()==1,"actual clearance wears correct tool once",checks);
            check(c.inventory().stream().filter(a->a.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==1,"real stone drop retained",checks);
            var finished=template.placements().stream().filter(p->p.mode()==BlueprintPlacementMode.STRUCTURE
                    && p.position().getY()>target.getY() && p.position().getY()<=254
                    && p.state().isCollisionShapeFullBlock(l,p.position())).findFirst().orElseThrow();
            l.setBlock(finished.position(),finished.state(),2);b.addClearanceTarget(finished.position(),Blocks.STONE.defaultBlockState());
            l.setBlock(target,Blocks.STONE.defaultBlockState(),2);b.addClearanceTarget(target,Blocks.STONE.defaultBlockState());
            var batch=BlueprintService.pendingPlacements(l,b,Set.of());
            check(!b.hasClearanceTarget(finished.position()) && b.hasClearanceTarget(target)
                    && batch.stream().anyMatch(p->p.placement().position().equals(target))
                    && l.getBlockState(target).is(Blocks.STONE) && l.getBlockState(finished.position()).equals(finished.state()),
                    "batch dispatch reconciles finished upper cell while preserving real lower obstruction and world",checks);
            b.addClearanceTarget(finished.position(),Blocks.STONE.defaultBlockState());
            check(BlueprintService.pendingAt(l,b,target)!=null && !b.hasClearanceTarget(finished.position())
                    && b.hasClearanceTarget(target) && l.getBlockState(target).is(Blocks.STONE),
                    "direct target lookup uses the same truthful clearance reconciliation",checks);
            near.discard();
            l.setBlock(target,Blocks.AIR.defaultBlockState(),2);b.removeClearanceTarget(target);
            var groundGoal=template.placements().stream().filter(p->p.mode().isFinalStructure()
                    && p.position().getY()<b.anchor().getY() && p.material()!=null
                    && p.state().isCollisionShapeFullBlock(l,p.position()))
                    .max(Comparator.comparingInt(p->p.position().getX())).orElseThrow();
            var groundTarget=groundGoal.position();
            // Isolate workface selection and one paid placement from the
            // unfinished full house's unrelated structural prerequisites.
            var groundSnapshot=CustomTemplateSnapshot.create("ground workface fixture",BuildingType.HOUSE,b.anchor(),Direction.NORTH,
                    List.of(groundGoal,new BlueprintPlacement(b.anchor().above(2),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS,BlueprintPlacementMode.STRUCTURE)),
                    List.of(new BlueprintEntrance(groundTarget.above(),Direction.WEST)));
            int originalProgress=b.progressIndex();var originalClearance=new LinkedHashMap<>(b.clearanceTargets());
            originalClearance.keySet().forEach(b::removeClearanceTarget);
            b.withCustomTemplate(groundSnapshot);b.setProgressIndex(0);
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
                for(int y=groundTarget.getY();y<=254;y++)l.setBlock(groundTarget.offset(dx,0,dz).atY(y),Blocks.AIR.defaultBlockState(),2);
                l.setBlock(groundTarget.offset(dx,-1,dz),Blocks.STONE.defaultBlockState(),2);
            }
            c.insertIntoInventory(new ItemStack(groundGoal.material()));
            int groundMaterialBefore=c.inventory().stream().filter(stack->stack.is(groundGoal.material())).mapToInt(ItemStack::getCount).sum();
            var groundFar=entity(l,s,c,start);entities.add(groundFar);c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,groundTarget,null,b.id());
            check(!CitizenInteractionRange.contains(groundFar,groundTarget),"ground final cell is beyond actual range at the opposite service edge",checks);
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,groundFar);
            var groundObservation=CitizenWorkService.constructionWorkObservation(l,s,c,groundFar);
            check(groundObservation.contains("ground approach") && !groundObservation.contains("ground ready")
                    && l.getBlockState(groundTarget).isAir() && c.hasInventoryItem(stack->stack.is(groundGoal.material())),
                    "low final cell selects its own workface without remote placement or consumption: "+groundObservation
                    +"; state="+c.workState()+"; target="+groundTarget+"; actual="+l.getBlockState(groundTarget),checks);
            groundFar.discard();
            var groundNear=entity(l,s,c,groundTarget.west());entities.add(groundNear);
            c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,groundTarget,null,b.id());
            var groundPending=BlueprintService.pendingAt(l,b,groundTarget);
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,groundNear);
            check(BlueprintService.matchesPlacedState(l.getBlockState(groundTarget),groundGoal.state())
                    && c.inventory().stream().filter(stack->stack.is(groundGoal.material())).mapToInt(ItemStack::getCount).sum()==groundMaterialBefore-1,
                    "on-site low final structure consumes the carried real material: "+CitizenWorkService.constructionWorkObservation(l,s,c,groundNear)
                    +"; actual="+l.getBlockState(groundTarget)+"; failure="+c.workFailure()+"; pending="+groundPending
                    +"; next="+c.targetPosition()+"; activity="+c.activity()+"; progress="+b.progressIndex()+"; clearance="+b.clearanceTargets(),checks);
            groundNear.discard();
            b.withCustomTemplate(null);b.setProgressIndex(originalProgress);
            originalClearance.forEach(b::addClearanceTarget);
            l.setBlock(target,Blocks.STONE.defaultBlockState(),2);b.addClearanceTarget(target,Blocks.STONE.defaultBlockState());
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=0;dy<=2;dy++)
                l.setBlock(target.offset(dx,dy,dz),Blocks.STONE.defaultBlockState(),2);
            var bank=entity(l,s,c,target.west().above(3));entities.add(bank);
            c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,b.id());
            check(!CitizenInteractionRange.visible(bank,target),"buried real clearance has no direct line of sight from the bank",checks);
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,bank);
            check(c.workerAccess.block!=null && c.workerAccess.block.getY()>target.getY()
                    && b.id().equals(c.targetBlueprintId()) && l.getBlockState(target).is(Blocks.STONE),
                    "buried building clearance starts paid upper access cut without abandoning mother or clearing remote goal",checks);
            bank.discard();c.workerAccess.clear();
            for(int dx=1;dx<=3;dx++)for(int dy=0;dy<=2;dy++)l.setBlock(target.offset(dx,dy,0),Blocks.AIR.defaultBlockState(),2);
            var upper=entity(l,s,c,target.west().above(3));entities.add(upper);
            var lowerFace=target.east(3);
            l.setBlock(lowerFace.below(),Blocks.STONE.defaultBlockState(),2);
            var eye=new net.minecraft.world.phys.Vec3(lowerFace.getX()+.5,lowerFace.getY()+upper.getEyeHeight(),lowerFace.getZ()+.5);
            var hit=l.clip(new net.minecraft.world.level.ClipContext(eye,net.minecraft.world.phys.Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,upper));
            check(upper.canStandAtPosition(lowerFace) && hit.getBlockPos().equals(target)
                    && upper.navigationFeetPosition().distSqr(lowerFace)>2,
                    "legal visible lower workface exists away from the actual upper-bank worker",checks);
            c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,b.id());
            CitizenWorkService.advanceSiteConstruction(l,data,s,c,upper);
            check(c.workerAccess.block!=null && c.workerAccess.block.getY()>target.getY()
                    && b.id().equals(c.targetBlueprintId()) && l.getBlockState(target).is(Blocks.STONE),
                    "upper-bank access can start before arrival at an available lower workface",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; fixture positions and clock input restored; not autonomous walking acceptance";
        }finally{entities.forEach(SettlementCitizenEntity::discard);s.deactivate();saved.forEach((p,state)->l.setBlock(p,state,2));l.setDayTime(day);data.touch();}
    }
    private static SettlementCitizenEntity entity(ServerLevel l,SettlementRecord s,CitizenRecord c,BlockPos at){
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        e.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);e.setOnGround(true);
        if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");
        SettlementSavedData.get(l).updateProjection(s,c,e.getUUID(),at);return e;
    }
    private Clearance91Checks(){}
}

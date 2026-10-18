package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit fixture input and production paid actions; excluded from the ten-building resident ledger. */
public final class Foundation91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(48000,250,48000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var saved=new LinkedHashMap<BlockPos,BlockState>();
        for(var raw:BlockPos.betweenClosed(core.offset(-3,-3,-3),core.offset(5,7,3))){
            var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
        }
        var created=data.createOrRecover(core,UUID.randomUUID(),"Foundation91 isolated paid raise checks");
        if(!created.success())throw new IllegalStateException(created.reason());var s=created.value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);var at=core.east(2);var checks=new ArrayList<String>();
        try{
            e.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");
            l.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);l.setBlock(at.east().below(2),Blocks.STONE.defaultBlockState(),2);
            var parent=s.addBlueprint(BuildingType.HOUSE,at.above(3),core.above(6),Direction.NORTH,s.ownerId(),false);
            var site=s.addBlueprint(BuildingType.HOUSE,at,core.west(),Direction.NORTH,s.ownerId(),false);
            var steps=new ArrayList<TerrainProject.Step>();for(int y=0;y<3;y++)steps.add(new TerrainProject.Step(at.above(y),Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            var p=new TerrainProject(site.id(),"Foundation91 cobble column",at,steps);p.explicit=true;p.dependencies.add(parent.id());site.withTerrainProject(p);s.terrain().index(p);
            var sibling=s.addBlueprint(BuildingType.HOUSE,at.east().below(),core.west(2),Direction.NORTH,s.ownerId(),false);
            var other=new TerrainProject(sibling.id(),"Foundation91 low sibling",at.east().below(),List.of(new TerrainProject.Step(at.east().below(),Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            other.explicit=true;other.dependencies.add(parent.id());sibling.withTerrainProject(other);s.terrain().index(other);
            c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,2));c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));
            check(!WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"full-column material shortage refused",checks);
            c.insertIntoInventory(new ItemStack(Items.COBBLESTONE));
            check(!WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"isolated paid pillar without actual exit refused",checks);
            for(int y=249;y<=252;y++)l.setBlock(at.north().atY(y),Blocks.STONE.defaultBlockState(),2);
            check(Objects.equals(WorkerAccessService.foundationColumnSurface(site,at),252),"actual registered cap used instead of anchor",checks);
            check(WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"three paid cobble cells accepted",checks);
            l.setBlock(at.north().atY(253),Blocks.STONE.defaultBlockState(),2);
            check(WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"actual one-block ascending exit accepted",checks);
            l.setBlock(at.north().atY(254),Blocks.STONE.defaultBlockState(),2);
            check(!WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"two-block ascending exit refused",checks);
            l.setBlock(at.north().atY(254),Blocks.AIR.defaultBlockState(),2);l.setBlock(at.north().atY(253),Blocks.AIR.defaultBlockState(),2);
            l.setBlock(at.above(4),Blocks.STONE.defaultBlockState(),2);
            check(!WorkerAccessService.canRaiseFoundation(l,s,c,e,site,at,252),"blocked landing headroom refused",checks);l.setBlock(at.above(4),Blocks.AIR.defaultBlockState(),2);
            TerrainLayerService.invalidate(s);check(TerrainService.at(l,site,at)==null,"ordinary mother layer remains below worker",checks);
            for(int y=0;y<3;y++){
                var feet=at.above(y);c.startTask(CitizenWorkState.TO_BLUEPRINT,feet,null,site.id());
                check(WorkerAccessService.requestFoundationRaise(l,s,c,e,site,252),"existing column safety request "+y,checks);
                CitizenWorkService.advanceAccessFill(l,data,s,site,c,e,feet);c.workerAccess.clear();TerrainLayerService.invalidate(s);
                check(l.getBlockState(feet).is(Blocks.COBBLESTONE),"real paid placement "+y,checks);
                check(e.getY()>=feet.getY()+1-1.0E-6 && l.noCollision(e),"actual entity safely raised "+y+" y="+e.getY()+" collisionFree="+l.noCollision(e),checks);
            }
            check(c.inventory().stream().filter(stack->stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==0,"three materials consumed exactly once",checks);
            check(!other.finished.get(0) && l.getBlockState(at.east().below()).isAir(),"sibling retained and not credited",checks);
            check(TerrainProject.load(p.save()).finished.cardinality()==3,"actual fill survives save reload",checks);
            check(!WorkerAccessService.allowed(l,s,at.below(),c),"unreplaced original bottom of paid column is protected",checks);
            var repairAt=core.west(2);
            l.setBlock(repairAt.below(),Blocks.STONE.defaultBlockState(),2);l.setBlock(repairAt.below(2),Blocks.STONE.defaultBlockState(),2);
            var repairSite=s.addBlueprint(BuildingType.HOUSE,repairAt,core.above(7),Direction.NORTH,s.ownerId(),false);
            var repair=new TerrainProject(repairSite.id(),"Foundation91 missing original base",repairAt,List.of(new TerrainProject.Step(repairAt,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            repair.explicit=true;repair.dependencies.add(parent.id());repairSite.withTerrainProject(repair);s.terrain().index(repair);
            check(!WorkerAccessService.allowed(l,s,repairAt.below(),c),"unbuilt column original support cannot be cut for access",checks);
            l.setBlock(repairAt.below(),Blocks.AIR.defaultBlockState(),2);
            FoundationSupportService.review(l,s,repairSite);
            check(repair.steps.size()==2 && repair.indices(repairAt.below()).size()==1 && repair.remaining()==2
                    && l.getBlockState(repairAt.below()).isAir(),"missing base adds real pending support without world edit or completion",checks);
            FoundationSupportService.review(l,s,repairSite);
            check(repair.steps.size()==2 && TerrainProject.load(repair.save()).steps.size()==2,"support repair is idempotent and survives save",checks);
            s.terrain().surveyed.add(parent.id());
            check(!TerrainService.allowed(s,parent),"unready foundation still gates ordinary structure",checks);
            var template=BlueprintTemplates.create(parent);
            var obstruction=template.placements().stream().filter(q->saved.containsKey(q.position()) && q.mode()==BlueprintPlacementMode.STRUCTURE && q.material()!=null)
                    .findFirst().orElseThrow().position();
            var obstructionBefore=l.getBlockState(obstruction);
            l.setBlock(obstruction,Blocks.STONE.defaultBlockState(),2);parent.addClearanceTarget(obstruction,Blocks.STONE.defaultBlockState());
            check(TerrainService.allowed(s,parent),"own clearance can run before sibling foundation completes",checks);
            parent.removeClearanceTarget(obstruction);l.setBlock(obstruction,obstructionBefore,2);
            var structure=template.placements().stream().filter(q->q.mode()==BlueprintPlacementMode.STRUCTURE && q.material()!=null
                    && !q.position().equals(obstruction) && l.getBlockState(q.position()).isAir()
                    && BlueprintService.pendingAt(l,parent,q.position())!=null)
                    .findFirst().orElseThrow();
            var before=l.getBlockState(structure.position());var material=new ItemStack(structure.material());
            var attempt=BlueprintService.placeAt(l,data,s,parent,structure.position(),material,e);
            check(!attempt.success() && attempt.reason().equals("TERRAIN_FOUNDATION_PENDING")
                    && l.getBlockState(structure.position()).equals(before) && material.getCount()==1,
                    "structure remains blocked without consuming material or editing world: result="+attempt.reason()+", itemCount="+material.getCount(),checks);
            parent.removeClearanceTarget(obstruction);
            check(!TerrainService.dependenciesReady(s,parent.id()) && !other.finished.get(0),"preparation does not cancel or credit unfinished sibling",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture, not autonomous supply/navigation acceptance";
        }finally{e.discard();s.deactivate();saved.forEach((pos,state)->l.setBlock(pos,state,2));TerrainLayerService.invalidate(s);data.touch();}
    }
    private Foundation91Checks(){}
}

package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** A sealed ten-block engineering void; only explicit fixture setup edits its enclosing ground. */
public final class SurfaceFill91Checks {
    private static void check(boolean ok,String message,List<String> checks){if(!ok)throw new IllegalStateException(message);checks.add(message);}
    public static String run(ServerLevel l)throws Exception {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(58000,250,58000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Surface fill sealed void inputs").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();SettlementCitizenEntity e=null;
        try {
            for(var raw:BlockPos.betweenClosed(core.offset(-1,-12,-3),core.offset(9,3,3))){
                var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));
            }
            var base=core.east(2).below(10);
            for(var raw:BlockPos.betweenClosed(base.offset(-1,-1,-1),base.offset(1,10,1)))l.setBlock(raw,Blocks.STONE.defaultBlockState(),2);
            var steps=new ArrayList<TerrainProject.Step>();
            for(int y=0;y<10;y++){var at=base.above(y);l.setBlock(at,Blocks.AIR.defaultBlockState(),2);steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));}
            var site=s.addBlueprint(BuildingType.HOUSE,base,core.above(20),Direction.NORTH,s.ownerId(),false);
            var p=new TerrainProject(site.id(),"Ten hidden paid fill cells",base,steps);p.explicit=true;site.withTerrainProject(p);s.terrain().index(p);
            var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
            c.insertIntoInventory(new ItemStack(Items.DIRT,16));c.reservePitBlocks(PitEscapeService::ordinary,16);
            c.insertIntoInventory(new ItemStack(Items.DIRT,10));c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));
            e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
            e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
            e.setPos(base.getX()+.5,251,base.getZ()+.5);e.setOnGround(true);
            if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),e.blockPosition());
            check(l.getEntities().get(e.getUUID())==e,"fixture actor participates in actual world occupancy",checks);
            check(!CitizenInteractionRange.visible(e,base) && !CitizenInteractionRange.contains(e,base),"sealed bottom is invisible and beyond normal interaction",checks);
            check(Objects.equals(TerrainService.groundFillSurface(l,p,steps.getFirst()),base.atY(250)),"registered hidden column selects its real roof ground",checks);
            var action=CitizenWorkService.class.getDeclaredMethod("advanceTerrain",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,
                    com.hkin.exiledsettlement.blueprint.BlueprintRecord.class,CitizenRecord.class,SettlementCitizenEntity.class,long.class);action.setAccessible(true);
            c.startTask(CitizenWorkState.TO_BLUEPRINT,base,null,site.id());
            var bankSearch=CitizenWorkService.class.getDeclaredMethod("waterBankApproach",ServerLevel.class,SettlementCitizenEntity.class,BlockPos.class,boolean.class);
            bankSearch.setAccessible(true);var roof=base.atY(250);
            l.setBlock(base.east(6).atY(250),Blocks.STONE.defaultBlockState(),2);
            var tallSteps=new ArrayList<TerrainProject.Step>(steps);
            tallSteps.add(new TerrainProject.Step(base.atY(260),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            var tall=new TerrainProject(UUID.randomUUID(),"Unfinished high grade input",base,tallSteps);
            check(roof.equals(TerrainService.groundFillSurface(l,tall,tallSteps.getFirst())),
                    "low real roof is the workface even with an unfinished higher final grade",checks);
            var shallowAt=base.east(6).atY(251);
            var shallow=new TerrainProject(UUID.randomUUID(),"Open shallow rising column input",shallowAt,List.of(
                    new TerrainProject.Step(shallowAt,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),
                    new TerrainProject.Step(shallowAt.atY(260),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            check(TerrainService.groundFillSurface(l,shallow,shallow.steps.getFirst())==null,
                    "shallow open fill uses ordinary real footing instead of waiting at a phantom high cap",checks);
            e.setPos(base.getX()+6.5,251,base.getZ()+.5);
            check(bankSearch.invoke(null,l,e,roof,false)==null,"ordinary bank search rejects an isolated unreachable workface",checks);
            var frontier=(BlockPos)bankSearch.invoke(null,l,e,roof,true);
            check(frontier!=null && e.canStandAtPosition(frontier) && l.getBlockState(frontier.below()).isFaceSturdy(l,frontier.below(),Direction.UP),
                    "dry fill retains a physically supported bank for paid passage instead of discarding the job",checks);
            check(l.getBlockState(base).isAir() && p.finished.isEmpty() && c.targetBlueprintId().equals(site.id()),
                    "selecting a disconnected bank neither fills remotely nor cancels the mother task",checks);
            e.setPos(base.getX()+6.5,251,base.getZ()+.5);action.invoke(null,l,data,s,site,c,e,l.getGameTime());
            check(l.getBlockState(base).isAir() && c.hasInventoryItem(stack->stack.is(Items.DIRT)) && p.finished.isEmpty(),"distant actor cannot remotely fill or credit work",checks);
            e.setPos(base.getX()+.5,251,base.getZ()+.5);e.setOnGround(false);action.invoke(null,l,data,s,site,c,e,l.getGameTime());
            check(l.getBlockState(base).isAir() && p.finished.isEmpty(),"airborne actor cannot use surface fill",checks);e.setOnGround(true);
            l.setBlock(base.below(),Blocks.AIR.defaultBlockState(),2);action.invoke(null,l,data,s,site,c,e,l.getGameTime());
            check(l.getBlockState(base).isAir() && p.finished.isEmpty(),"actual bottom support remains mandatory",checks);l.setBlock(base.below(),Blocks.STONE.defaultBlockState(),2);
            s.terrain().suppressed.add(base.asLong());action.invoke(null,l,data,s,site,c,e,l.getGameTime());
            check(l.getBlockState(base).isAir() && p.finished.isEmpty(),"explicit protection prevents hidden fill",checks);s.terrain().suppressed.remove(base.asLong());
            var unrelated=base.east(3);l.setBlock(unrelated,Blocks.AIR.defaultBlockState(),2);
            check(TerrainService.groundFillSurface(l,p,new TerrainProject.Step(unrelated,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()))==null,"unregistered cave cells have no surface construction permission",checks);
            p.bridge=true;check(TerrainService.groundFillSurface(l,p,steps.getFirst())==null,"bridge geometry does not acquire underground fill permission",checks);p.bridge=false;
            for(int y=0;y<10;y++) {
                var at=base.above(y);TerrainLayerService.invalidate(s);c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
                action.invoke(null,l,data,s,site,c,e,l.getGameTime());
                check(l.getBlockState(at).is(Blocks.DIRT) && p.finished.get(y),"real paid bottom-up hidden fill "+y
                        +" failure="+c.workFailure()+" state="+c.workState()+" target="+c.targetPosition(),checks);
                check(e.getY()==251 && l.getBlockState(base.atY(250)).is(Blocks.STONE),"actor stays on intact roof while filling layer "+y,checks);
            }
            check(c.pitReserve().getCount()==16 && !c.hasInventoryItem(stack->stack.is(Items.DIRT)),"exactly ten construction blocks consumed; emergency reserve untouched",checks);
            check(c.inventory().stream().filter(stack->stack.is(Items.IRON_SHOVEL)).findFirst().orElseThrow().getDamageValue()==10,"ten actual fill actions consume tool durability",checks);
            check(TerrainProject.load(p.save()).finished.cardinality()==10 && p.done(),"all ten realized steps survive save reload",checks);
            check(l.getBlockState(unrelated).isAir() && s.terrain().projects.size()==1,"outside cave unchanged; no extra underground or recovery orders",checks);
            var retained=base.north(2).above(4);
            for(var d:Direction.values())l.setBlock(retained.relative(d),Blocks.STONE.defaultBlockState(),2);
            l.setBlock(retained,Blocks.DIRT.defaultBlockState(),2);s.terrain().recordEscapeSupport(retained,Blocks.DIRT.defaultBlockState());
            var reuseSite=s.addBlueprint(BuildingType.HOUSE,retained,core.above(21),Direction.NORTH,s.ownerId(),false);
            var reuse=new TerrainProject(reuseSite.id(),"Retained paid hidden escape block input",retained,List.of(new TerrainProject.Step(retained,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            reuse.explicit=true;reuseSite.withTerrainProject(reuse);s.terrain().index(reuse);TerrainLayerService.invalidate(s);
            int reserve=c.pitReserve().getCount();TerrainService.pending(l,reuseSite,Set.of());
            check(reuse.done() && reuse.steps.getFirst().target().is(Blocks.DIRT),"hidden foundation adopts the retained paid escape material",checks);
            check(l.getBlockState(retained).is(Blocks.DIRT) && c.pitReserve().getCount()==reserve,"reuse makes no world edit and consumes no second material",checks);
            check(TerrainProject.load(reuse.save()).steps.getFirst().target().is(Blocks.DIRT),"actual reused material survives project save reload",checks);
            var visible=base.south(2).above(4);l.setBlock(visible.below(),Blocks.STONE.defaultBlockState(),2);l.setBlock(visible,Blocks.DIRT.defaultBlockState(),2);
            s.terrain().recordEscapeSupport(visible,Blocks.DIRT.defaultBlockState());
            var capSite=s.addBlueprint(BuildingType.HOUSE,visible,core.above(22),Direction.NORTH,s.ownerId(),false);
            var cap=new TerrainProject(capSite.id(),"Visible palette input",visible,List.of(new TerrainProject.Step(visible,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            cap.explicit=true;cap.groundSurfaces.add(visible);capSite.withTerrainProject(cap);s.terrain().index(cap);TerrainLayerService.invalidate(s);TerrainService.pending(l,capSite,Set.of());
            check(!cap.done() && cap.steps.getFirst().target().is(Blocks.COBBLESTONE) && l.getBlockState(visible).is(Blocks.DIRT),"exposed specified material is not silently accepted as hidden fill",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture inputs restored; not autonomous ten-building acceptance";
        }finally{if(e!=null)e.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private SurfaceFill91Checks(){}
}

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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;
import java.util.function.Consumer;

/** Explicit isolated fixture; production selection and paid action, not autonomous logistics proof. */
public final class Foundation78Checks {
    private static void check(boolean value,String name,List<String> out) {
        if(!value)throw new IllegalStateException(name);out.add(name);
    }
    public static String run(ServerLevel level)throws Exception {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(32900,250,32900);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_ALREADY_EXISTS");
        for(var p:BlockPos.betweenClosed(core.offset(-4,-2,-3),core.offset(4,3,3)))
            if(!level.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Foundation78 checks").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;
        var c=s.citizens().iterator().next();var worker=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        var occupant=ModEntities.SETTLEMENT_CITIZEN.get().create(level);var out=new ArrayList<String>();
        var at=core.east(2);level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);
        worker.setPos(at.getX()-1.5,at.getY(),at.getZ()+.5);worker.setOnGround(true);
        occupant.setPos(core.getX()-3,core.getY(),core.getZ());
        if(!level.addFreshEntity(worker) || !level.addFreshEntity(occupant))throw new IllegalStateException("FIXTURE_ENTITY_SPAWN_FAILED");
        c.insertIntoInventory(new ItemStack(Items.DIRT,16));c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));c.insertIntoInventory(new ItemStack(Items.DIAMOND_SHOVEL));
        var method=CitizenWorkService.class.getDeclaredMethod("advanceTerrain",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,BlueprintRecord.class,CitizenRecord.class,SettlementCitizenEntity.class,long.class);
        method.setAccessible(true);
        try {
            level.setBlock(at,Blocks.STONE.defaultBlockState(),2);
            var site=site(s,at,Blocks.STONE.defaultBlockState(),true);c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
            check(TerrainService.pending(level,site,Set.of()).stream().allMatch(p->!p.placement().state().isAir()),"dig_deferred_to_atomic_fill",out);
            check(!site.terrainProject().finished.get(0),"dig_not_credited_before_fill",out);
            check(TerrainService.at(level,site,at).placement().state().is(Blocks.DIRT),"selection_allows_solid",out);
            method.invoke(null,level,data,s,site,c,worker,level.getGameTime());
            check(level.getBlockState(at).is(Blocks.DIRT),"solid_replaced_by_production_action",out);
            check(count(c,Items.DIRT)==15 && count(c,Items.COBBLESTONE)==1,"one_material_one_real_drop",out);
            check(c.inventoryItem(c.findInventorySlot(i->i.is(Items.DIAMOND_PICKAXE))).getDamageValue()==1,"replacement_tool_durability",out);
            TerrainService.pending(level,site,Set.of());check(site.terrainProject().done(),"old_dig_closed_by_actual_fill",out);
            var restored=TerrainProject.load(site.terrainProject().save());check(restored.done(),"progress_survives_save_reload",out);
            s.removeBlueprint(site.markerPosition());

            level.setBlock(at,Blocks.AIR.defaultBlockState(),2);site=site(s,at,Blocks.AIR.defaultBlockState(),false);
            c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());occupant.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            check(!FoundationPlacement.occupied(level,at,true) && FoundationPlacement.occupied(level,at,false),"resident_occupancy_only_exempted",out);
            method.invoke(null,level,data,s,site,c,worker,level.getGameTime()+5);
            check(level.getBlockState(at).is(Blocks.DIRT) && count(c,Items.DIRT)==14,"occupied_fill_paid_once",out);
            check(occupant.getY()>=at.getY()+1 && level.noCollision(occupant),"occupant_raised_clear_of_ground",out);
            s.removeBlueprint(site.markerPosition());occupant.setPos(core.getX()-3,core.getY(),core.getZ());

            level.setBlock(at,Blocks.AIR.defaultBlockState(),2);site=site(s,at,Blocks.AIR.defaultBlockState(),false);
            c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());worker.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            method.invoke(null,level,data,s,site,c,worker,level.getGameTime()+10);
            check(level.getBlockState(at).is(Blocks.DIRT) && worker.getY()>=at.getY()+1,"worker_can_fill_own_feet",out);
            s.removeBlueprint(site.markerPosition());worker.setPos(at.getX()-1.5,at.getY(),at.getZ()+.5);

            level.setBlock(at,Blocks.STONE.defaultBlockState(),2);site=site(s,at,Blocks.AIR.defaultBlockState(),false);
            c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
            check(TerrainService.guard(level,s,site,site.terrainProject().steps.getFirst()).isEmpty(),"later_ordinary_block_does_not_stale_target",out);
            int dirt=count(c,Items.DIRT),drops=count(c,Items.COBBLESTONE);boolean[] fired={false};
            Consumer<BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getPos().equals(at)){fired[0]=true;e.setCanceled(true);}};
            NeoForge.EVENT_BUS.addListener(cancel);
            try{method.invoke(null,level,data,s,site,c,worker,level.getGameTime()+15);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
            check(fired[0] && level.getBlockState(at).is(Blocks.STONE),"protection_restores_original",out);
            check(count(c,Items.DIRT)==dirt && count(c,Items.COBBLESTONE)==drops && !site.terrainProject().done(),"denied_action_no_charge_drop_or_progress",out);
            level.setBlock(at,Blocks.BEDROCK.defaultBlockState(),2);check(TerrainService.at(level,site,at)==null,"bedrock_not_replaced",out);
            level.setBlock(at,Blocks.BARREL.defaultBlockState(),2);check(TerrainService.at(level,site,at)==null,"container_not_replaced",out);
            level.setBlock(at,Blocks.STONE.defaultBlockState(),2);level.setBlock(at.below(),Blocks.AIR.defaultBlockState(),2);
            check(TerrainService.at(level,site,at)==null,"bottom_support_still_required",out);
            level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);
            site.terrainProject().bridge=true;check(!FoundationPlacement.applies(site.terrainProject(),site.terrainProject().steps.getFirst()),"bridge_not_exempted",out);
            site.terrainProject().bridge=false;s.removeBlueprint(site.markerPosition());
            data.touch();return "PASS "+out.size()+" foundation targeted checks: "+String.join(", ",out);
        }finally{worker.discard();occupant.discard();s.deactivate();data.touch();}
    }
    private static BlueprintRecord site(SettlementRecord s,BlockPos at,net.minecraft.world.level.block.state.BlockState original,boolean dig) {
        var b=s.addBlueprint(BuildingType.HOUSE,at,at.above(30),Direction.NORTH,s.ownerId(),false);
        var steps=new ArrayList<TerrainProject.Step>();if(dig)steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),original));
        steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
        var project=new TerrainProject(b.id(),"Foundation78 fill",at,steps);project.explicit=true;b.withTerrainProject(project);return b;
    }
    private static int count(CitizenRecord c,Item item){return c.inventory().stream().filter(i->i.is(item)).mapToInt(ItemStack::getCount).sum();}

    public static String live(ServerLevel level)throws Exception {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(32400,250,32400);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_ALREADY_EXISTS");
        for(var p:BlockPos.betweenClosed(core.offset(-5,-2,-5),core.offset(8,3,5)))
            if(!level.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-5;x<=8;x++)for(int z=-5;z<=5;z++)for(int y=-2;y<=-1;y++)level.setBlock(core.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);
        level.setBlock(core,com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(core,UUID.randomUUID(),"Foundation78 native fill").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;
        var at=core.east(3).below();level.setBlock(at,Blocks.AIR.defaultBlockState(),2);
        level.setBlock(at.south(),Blocks.AIR.defaultBlockState(),2);level.setBlock(at.south(),Blocks.SHORT_GRASS.defaultBlockState(),2);
        var b=s.addBlueprint(BuildingType.HOUSE,at,at.above(30),Direction.NORTH,s.ownerId(),false);
        var steps=new ArrayList<TerrainProject.Step>();
        for(var cell:List.of(at,at.east(),at.south())) {
            steps.add(new TerrainProject.Step(cell,Blocks.AIR.defaultBlockState(),level.getBlockState(cell)));
            steps.add(new TerrainProject.Step(cell,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
        }
        var p=new TerrainProject(b.id(),"Foundation78 native three cells",at,steps);p.explicit=true;b.withTerrainProject(p);s.terrain().index(p);
        int i=0;UUID worker=null;
        for(var c:s.citizens()) {
            c.setManualAssignment(i==0?CitizenProfession.WORKER:CitizenProfession.TRAVELING_MERCHANT,null);
            c.updateProjection(null,i==0?at:core.offset(-3,0,-3+i));
            if(i==0){worker=c.id();c.insertIntoInventory(new ItemStack(Items.DIRT,8));c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));c.insertIntoInventory(new ItemStack(Items.DIAMOND_SHOVEL));c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,b.id());}i++;
        }
        ForceWorkDebugService.set(level,s,true);data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);SaveAcceptanceService.load(level,s,2);
        return "OK settlement="+s.id()+"; worker="+worker+"; native task="+b.id()+"; finite fixture stock 8 dirt and two tools; three actual fills required; first target at worker feet";
    }
}

package com.hkin.exiledsettlement.terrain;
import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Explicit isolated test setup; world blocks are restored, test actions are not resident construction. */
public final class Works74Checks {
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var checks=new JsonObject();var origin=new BlockPos(26000,249,26000);
        var previous=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        try{
            for(var raw:BlockPos.betweenClosed(origin.offset(-4,-2,-4),origin.offset(14,5,4))){
                var p=raw.immutable();var state=level.getBlockState(p);
                if(!state.isAir())throw new IllegalStateException("Fixture requires unused air");previous.put(p,state);
            }
            var a=origin.offset(-2,1,0);var b=origin.offset(12,1,0);
            level.setBlock(a,Blocks.BARREL.defaultBlockState(),2);level.setBlock(b,Blocks.BARREL.defaultBlockState(),2);
            var c1=(Container)level.getBlockEntity(a);var c2=(Container)level.getBlockEntity(b);
            for(int i=0;i<c1.getContainerSize();i++)c1.setItem(i,new ItemStack(Items.DIRT,64));
            var warehouse=BuildingRecord.create(BuildingType.WAREHOUSE,a);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,54,List.of(a,b,a));
            entity.setPos(a.getX()+1.5,a.getY(),a.getZ()+.5);
            checks.addProperty("unique_container_capacity",WarehouseInventoryService.totalSlots(level,warehouse)==54);
            checks.addProperty("full_near_empty_far_acceptance",WarehouseInventoryService.canAccept(level,warehouse,new ItemStack(Items.DIRT,32)));
            checks.addProperty("one_loading_point_inserts_far_container",WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.DIRT,32)).isEmpty()
                    && c2.getItem(0).getCount()==32);
            entity.setPos(a.getX()+100,a.getY(),a.getZ());
            checks.addProperty("remote_insertion_rejected",WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.APPLE,7)).getCount()==7);
            c1.clearContent();c2.clearContent();c1.setItem(20,new ItemStack(Items.APPLE,7));c2.setItem(4,new ItemStack(Items.APPLE,11));
            var named=new ItemStack(Items.APPLE,5);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Preserved name"));c2.setItem(5,named);
            checks.addProperty("remote_organization_rejected",WarehouseInventoryService.organizeForResident(level,warehouse,entity)==0);
            entity.setPos(a.getX()+1.5,a.getY(),a.getZ()+.5);
            for(int i=0;i<100 && WarehouseInventoryService.needsOrganization(level,warehouse);i++)WarehouseInventoryService.organizeForResident(level,warehouse,entity);
            checks.addProperty("organization_converges",!WarehouseInventoryService.needsOrganization(level,warehouse));
            checks.addProperty("organization_preserves_count_and_components",WarehouseInventoryService.countItem(level,warehouse,Items.APPLE)==23
                    && WarehouseInventoryService.loadedContainers(level,warehouse).stream().anyMatch(at->{for(int i=0;i<at.container().getContainerSize();i++)if(ItemStack.isSameItemSameComponents(named,at.container().getItem(i)) && at.container().getItem(i).getCount()==5)return true;return false;}));
            c1.clearContent();c2.clearContent();level.setBlock(a,Blocks.AIR.defaultBlockState(),2);level.setBlock(b,Blocks.AIR.defaultBlockState(),2);
            var s=new SettlementRecord(UUID.randomUUID(),"Works 74 detached fixture",origin.offset(-3,1,0),UUID.randomUUID());
            for(int x=0;x<=9;x++)for(int z=-3;z<=3;z++){
                level.setBlock(origin.offset(x,-2,z),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(origin.offset(x,-1,z),x==0 || x==9?Blocks.STONE.defaultBlockState():Blocks.WATER.defaultBlockState(),2);
                if(x==0 || x==9)level.setBlock(origin.offset(x,0,z),Blocks.DIRT.defaultBlockState(),2);
            }
            var bridge=PlayerWorksService.previewBridge(level,s,origin,origin.east(9));
            checks.addProperty("manual_bridge_validates_real_banks",bridge.success());
            if(!bridge.success())throw new IllegalStateException(bridge.reason());
            var route=bridge.value();s.autoRoads().routes.put("manual",route);
            checks.addProperty("bridge_preview_never_edits_world",level.getBlockState(origin.east(4).above()).isAir() && s.terrain().projects.isEmpty());
            checks.addProperty("automatic_bridge_publication_rejected",!BridgeService.publish(level,s,route) && s.terrain().projects.isEmpty());
            checks.addProperty("bridge_publish_preserves_river",level.getFluidState(origin.east(4).below()).is(net.minecraft.tags.FluidTags.WATER));
            var loaded=new AutoRoadState();loaded.load(s.autoRoads().save());
            checks.addProperty("manual_bridge_identity_saved",loaded.routes.get("manual").manualBridge && AutoRoadService.parentValid(level,s,loaded.routes.get("manual")));
            for(var p:s.terrain().projects.values()){
                var another=new AutoRoadState.Route(UUID.randomUUID(),origin,List.of(origin));another.sourceBridges.add(UUID.randomUUID());p.dependencies.add(another.id);s.autoRoads().routes.put("opposite",another);
                checks.addProperty("shared_bridge_not_blocked_by_consumer_order",BridgeService.workPermitted(s,p));
            }
            checks.addProperty("diagonal_bridge_rejected",!PlayerWorksService.previewBridge(level,s,origin,origin.offset(9,0,1)).success());
            var w=new PublicGroundService.State();
            for(int x=-3;x<=3;x++)w.goals.put(BlockPos.asLong(x,0,0),x<=0?10:9);
            checks.addProperty("isolated_edge_has_no_stairs",!PublicGroundService.continuousSlope(w,BlockPos.asLong(0,0,0),Direction.EAST));
            w.goals.put(BlockPos.asLong(2,0,0),8);w.goals.put(BlockPos.asLong(3,0,0),8);
            checks.addProperty("continuous_slope_can_have_stairs",PublicGroundService.continuousSlope(w,BlockPos.asLong(0,0,0),Direction.EAST));
            var project=new TerrainProject(UUID.randomUUID(),"clear",origin,List.of(new TerrainProject.Step(origin,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));project.explicit=true;project.areaClearance=true;
            checks.addProperty("clearance_flags_saved",TerrainProject.load(project.save()).areaClearance && TerrainProject.load(project.save()).explicit);
            checks.addProperty("clearance_protects_core_and_bridge",PlayerWorksService.protectedAt(s,s.corePosition()) && PlayerWorksService.protectedAt(s,origin.east(4).above()));
            var anchor=origin.offset(-30,1,0);
            var house=s.addBlueprint(BuildingType.HOUSE,anchor,anchor,Direction.WEST,s.ownerId(),false);
            var corner=com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(house).minCorner();
            checks.addProperty("rotated_house_reserves_full_footprint",!corner.equals(anchor) && house.containsColumn(corner));
            checks.addProperty("clearance_protects_pending_house_corner",PlayerWorksService.protectedAt(s,corner));
            var decor=new com.hkin.exiledsettlement.decoration.DecorationState();decor.density=2;
            decor.features.put("bench",new com.hkin.exiledsettlement.decoration.DecorationState.Feature("bench",BuildingType.BENCH,1,origin,Direction.NORTH,UUID.randomUUID(),null,8));
            checks.addProperty("same_module_ten_block_spacing_ignores_height",!com.hkin.exiledsettlement.decoration.DecorationService.spacingAllows(decor,"bench",8,origin.offset(9,30,0)));
            checks.addProperty("sparse_module_boundary_allowed",com.hkin.exiledsettlement.decoration.DecorationService.spacingAllows(decor,"bench",8,origin.east(12)));
            checks.addProperty("unlike_modules_keep_five_blocks",!com.hkin.exiledsettlement.decoration.DecorationService.spacingAllows(decor,"well",8,origin.east(4)));
        }finally{
            if(entity!=null)entity.discard();
            previous.forEach((p,state)->{var be=level.getBlockEntity(p);if(be instanceof Container c)c.clearContent();level.setBlock(p,state,2);});
        }
        boolean ok=checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean());var out=new JsonObject();out.addProperty("passed",ok);out.addProperty("fixtureOnly",true);out.add("checks",checks);
        return out.toString();
    }
    private Works74Checks(){}
}

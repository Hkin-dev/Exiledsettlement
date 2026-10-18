package com.hkin.exiledsettlement.debug;

import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.blueprint.ConstructionPlanService;
import com.hkin.exiledsettlement.road.BridgeService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** In-memory regression cases: no blocks, entities, inventories or saves are changed. */
public final class ConstructionSupplyChecks {
    private ConstructionSupplyChecks() { }
    public static String run() {
        var checks=new JsonObject();
        var carrier=new com.hkin.exiledsettlement.citizen.CitizenRecord(UUID.randomUUID(),"batch",BlockPos.ZERO);
        var source=new ArrayDeque<net.minecraft.world.item.ItemStack>();
        source.add(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE,1));
        source.add(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE,63));
        source.add(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE,64));
        java.util.function.IntFunction<net.minecraft.world.item.ItemStack> extract=count->{
            while(!source.isEmpty() && source.getFirst().isEmpty())source.removeFirst();
            return source.isEmpty()?net.minecraft.world.item.ItemStack.EMPTY:source.getFirst().split(count);
        };
        java.util.function.Consumer<net.minecraft.world.item.ItemStack> refund=source::addLast;
        int loaded=com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(carrier,Items.COBBLESTONE,100,extract,refund);
        checks.addProperty("one_then_later_stacks_loads_full_batch",loaded==100 && source.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum()==28);
        checks.addProperty("existing_batch_not_loaded_twice",com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(carrier,Items.COBBLESTONE,100,extract,refund)==0);
        checks.addProperty("short_stock_only_takes_available",com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(carrier,Items.COBBLESTONE,200,extract,refund)==28);
        checks.addProperty("empty_stock_stops_without_creation",com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(carrier,Items.COBBLESTONE,200,extract,refund)==0);
        var full=new com.hkin.exiledsettlement.citizen.CitizenRecord(UUID.randomUUID(),"capacity",BlockPos.ZERO);
        for(int i=0;i<com.hkin.exiledsettlement.citizen.CitizenRecord.INVENTORY_SIZE;i++)full.insertIntoInventory(new net.minecraft.world.item.ItemStack(Items.DIRT,64));
        source.add(new net.minecraft.world.item.ItemStack(Items.COBBLESTONE,64));
        checks.addProperty("full_backpack_does_not_withdraw",com.hkin.exiledsettlement.citizen.ConstructionMaterialBatch.load(full,Items.COBBLESTONE,64,extract,refund)==0 && source.getLast().getCount()==64);
        var steps=new ArrayList<TerrainProject.Step>();
        var origin=new BlockPos(0,64,0);
        for(int i=0;i<96;i++)steps.add(new TerrainProject.Step(origin.above(i),Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState()));
        var p=new TerrainProject(UUID.randomUUID(),"vertical foundation",origin,steps);
        checks.addProperty("unbuilt_vertical_layers_load_stack",need(p)==64);
        p.bridge=true;
        checks.addProperty("bridge_uses_same_batch_contract",need(p)==64);
        p.bridge=false;p.road=true;
        checks.addProperty("road_uses_same_batch_contract",need(p)==64);
        p.road=false;p.publicGround=true;
        checks.addProperty("public_ground_uses_same_batch_contract",need(p)==64);
        for(int i=0;i<76;i++)p.mark(i);
        checks.addProperty("remaining_twenty_not_one",need(p)==20);
        p.abandon(80);
        checks.addProperty("abandoned_not_fetched",need(p)==19);
        var restoredProject=TerrainProject.load(p.save());
        checks.addProperty("restart_retains_demand",need(restoredProject)==19);
        p.cancelled=true;checks.addProperty("cancelled_no_load",need(p)==0);p.cancelled=false;
        p.completed=true;checks.addProperty("completed_no_load",need(p)==0);p.completed=false;
        for(int i=76;i<95;i++)p.mark(i);
        checks.addProperty("legitimate_last_item_stays_one",need(p)==1);
        p.mark(95);checks.addProperty("finished_no_load",need(p)==0);
        var repeated=new TerrainProject(UUID.randomUUID(),"repair",origin,List.of(steps.getFirst(),steps.getFirst()));
        checks.addProperty("same_position_not_double_counted",need(repeated)==1);

        var settlement=new SettlementRecord(UUID.randomUUID(),"index fixture",origin,UUID.randomUUID());
        var parent=UUID.randomUUID();TerrainProject single=null;
        for(int i=0;i<80;i++) {
            var order=new TerrainProject(UUID.randomUUID(),"column",origin.east(i),List.of(new TerrainProject.Step(origin.east(i),Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            order.dependencies.add(parent);settlement.terrain().index(order);if(single==null)single=order;
        }
        checks.addProperty("split_columns_share_one_batch",ConstructionPlanService.relatedTerrainSupplyNeed(settlement,single,Items.COBBLESTONE)==64);
        var unrelated=new TerrainProject(UUID.randomUUID(),"unrelated",origin,List.of(steps.getFirst()));
        unrelated.dependencies.add(UUID.randomUUID());settlement.terrain().index(unrelated);
        checks.addProperty("unrelated_order_not_counted",ConstructionPlanService.relatedTerrainSupplyNeed(settlement,unrelated,Items.COBBLESTONE)==1);
        for(int i=0;i<2000;i++)settlement.terrain().index(new TerrainProject(UUID.randomUUID(),"ordinary",origin,List.of()));
        var first=bridge(origin);settlement.terrain().index(first);
        var second=bridge(origin);settlement.terrain().index(second);
        checks.addProperty("shared_surface_keeps_original_owner",BridgeService.owner(settlement,origin)==first);
        checks.addProperty("unrelated_position_absent",BridgeService.owner(settlement,origin.east())==null);
        checks.addProperty("bridge_column_ignores_height",BridgeService.column(settlement,origin.above(20)));
        first.cancelled=true;
        checks.addProperty("cancelled_owner_falls_back_to_shared",BridgeService.owner(settlement,origin)==second);
        second.cancelled=true;
        checks.addProperty("both_cancelled_absent",BridgeService.owner(settlement,origin)==null);
        checks.addProperty("cancelled_column_absent",!BridgeService.column(settlement,origin));
        first.completed=true;
        checks.addProperty("paid_completed_bridge_retained",BridgeService.owner(settlement,origin)==first);
        var restored=new TerrainState();restored.load(settlement.terrain().save());
        checks.addProperty("restart_rebuilds_surface_index",restored.bridgeSurface(origin)!=null && restored.bridgeSurface(origin).id.equals(first.id));
        checks.addProperty("restart_rebuilds_column_index",restored.bridgeColumn(origin));
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);
        out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
    private static int need(TerrainProject p) {return ConstructionPlanService.terrainSupplyPlan(p).requirements().getOrDefault(Items.COBBLESTONE,0);}
    private static TerrainProject bridge(BlockPos at) {
        var p=new TerrainProject(UUID.randomUUID(),"bridge",at,List.of(new TerrainProject.Step(at,Blocks.OAK_PLANKS.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        p.bridge=true;p.accessSurfaces.add(at);p.bridgeDeck.add(at);return p;
    }
}

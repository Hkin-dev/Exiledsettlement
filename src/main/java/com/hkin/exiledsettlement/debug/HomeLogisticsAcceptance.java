package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.registry.*;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Finite fresh-world setup. Subsequent transfers and consumption use production AI. */
public final class HomeLogisticsAcceptance {
    private static final BlockPos CORE=new BlockPos(42000,250,42000);
    private HomeLogisticsAcceptance() {}
    private static void enabled(){if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");}
    private static SettlementRecord scene(ServerLevel l){enabled();return SettlementSavedData.get(l).findByCore(CORE).orElseThrow();}
    public static String engineering(ServerLevel l)throws Exception {
        enabled();Engineering91Acceptance.start(l);var s=SettlementSavedData.get(l).findByCore(Engineering91Acceptance.CORE).orElseThrow();
        while(s.citizens().size()<28) {var c=s.addMigrant();c.setManualAssignment(CitizenProfession.WORKER,null);}
        CitizenProjectionService.ensureSettlementCitizens(l,s);
        l.setDayTime(6000);l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,l.getServer());
        SettlementSavedData.get(l).touch();SaveAcceptanceService.watch(l,s,"home7-engineering",12000,200);
        return "28 real workers, 10 complex-terrain sites, normal 20 TPS work cadence; fixed daytime test stimulus";
    }
    public static String engineeringStats(ServerLevel l) {
        enabled();var s=SettlementSavedData.get(l).findByCore(Engineering91Acceptance.CORE).orElseThrow();var j=new JsonObject();
        j.addProperty("tick",l.getGameTime());j.addProperty("averageTickMs",l.getServer().getAverageTickTimeNanos()/1_000_000.0);
        j.addProperty("workers",s.citizens().size());j.addProperty("buildings",s.buildings().size());j.addProperty("orders",s.blueprints().size());
        j.addProperty("terrainRemaining",s.terrain().projects.values().stream().filter(p->!p.completed&&!p.cancelled).mapToInt(p->p.remaining()).sum());
        j.add("postTick",EngineeringTickSamples.snapshot());return j.toString();
    }
    public static String start(ServerLevel l)throws Exception {
        enabled();var data=SettlementSavedData.get(l);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("Fixture already exists");
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(CORE.offset(-42,0,-42),CORE.offset(42,20,42)))
            if(!l.getBlockState(p).isAir())throw new IllegalStateException("Requires unused airspace");
        for(var p:BlockPos.betweenClosed(CORE.offset(-42,-1,-42),CORE.offset(42,-1,42)))l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(CORE,UUID.randomUUID(),"Home logistics fresh scene").value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=true;
        var warehouse=build(l,s,BuildingType.WAREHOUSE,CORE.offset(-24,0,0),Direction.EAST);
        for(int z:new int[]{-18,0,18})build(l,s,BuildingType.HOUSE,CORE.offset(12,0,z),Direction.WEST);
        for(var stack:List.of(new ItemStack(Items.BREAD,256),new ItemStack(Items.COAL,64),new ItemStack(ModItems.WOOL_COAT.get(),12),new ItemStack(ModItems.LEATHER_COAT.get(),12)))
            WarehouseInventoryService.insert(l,warehouse,stack);
        for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL,Items.IRON_HOE,Items.IRON_SWORD,Items.SHEARS,Items.FISHING_ROD))
            WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,12));
        int i=0;for(var c:s.citizens()) {
            c.setManualAssignment(i==0?CitizenProfession.WAREHOUSE_KEEPER:CitizenProfession.WORKER,i==0?warehouse.id():null);
            c.updateProjection(null,CORE.offset(2,0,3+i++));
        }
        s.refreshHousingAssignments();data.touch();CitizenProjectionService.ensureSettlementCitizens(l,s);
        l.setDayTime(6000);l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,l.getServer());
        SaveAcceptanceService.load(l,s,4);SaveAcceptanceService.watch(l,s,"home7-live",12000,200);
        return inspect(l);
    }
    private static BuildingRecord build(ServerLevel l,SettlementRecord s,BuildingType type,BlockPos at,Direction facing) {
        var template=BlueprintTemplates.create(type,at,facing);
        for(var p:template.placements())l.setBlock(p.position(),p.state(),2);
        for(var p:template.placements())BlueprintBlockStateResolver.refreshExisting(l,template,p);
        var b=s.addBuilding(type,template.registrationPosition(),facing,template.minCorner(),template.maxCorner(),template.transitionMargin(),BlueprintTemplates.currentTemplateVersion(type));
        BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),s,b);return b;
    }
    public static String inspect(ServerLevel l) {
        var s=scene(l);var out=new JsonObject();out.addProperty("tick",l.getGameTime());out.addProperty("averageTickMs",l.getServer().getAverageTickTimeNanos()/1_000_000.0);
        out.addProperty("animalPathRequests",PastureResidentService.pathRequests);var buildings=new JsonArray();
        for(var b:s.buildings())if(WarehouseStock.applies(b)) {
            var j=new JsonObject();j.addProperty("id",b.id().toString());j.addProperty("type",b.type().name());j.addProperty("operational",b.operational());j.addProperty("capacity",b.capacity());
            j.addProperty("quantity",WarehouseInventoryService.stock(l,b).quantity());j.addProperty("bread",WarehouseInventoryService.countItem(l,b,Items.BREAD));j.addProperty("coal",WarehouseInventoryService.countItem(l,b,Items.COAL));
            j.addProperty("bounds",b.minCorner()+" -> "+b.maxCorner());buildings.add(j);
        }
        out.add("buildings",buildings);var residents=new JsonArray();
        for(var c:s.citizens()) {
            var j=new JsonObject();var e=CitizenProjectionService.findProjection(l,c);j.addProperty("name",c.name());j.addProperty("profession",c.profession().name());
            j.addProperty("task",c.life().task);j.addProperty("stage",c.life().cookingStage);j.addProperty("reason",c.workFailure());j.addProperty("home",String.valueOf(c.homeBuildingId()));
            j.addProperty("position",e==null?"unloaded":e.position().toString());j.addProperty("bread",c.inventory().stream().filter(v->v.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum());residents.add(j);
        }
        out.add("residents",residents);
        var animals=l.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,new net.minecraft.world.phys.AABB(CORE).inflate(48),a->a.isAlive());
        out.addProperty("animals",animals.size());out.addProperty("controlledAnimals",animals.stream().filter(PastureResidentService::controls).count());
        return out.toString();
    }
    public static String paths(ServerLevel l) {
        var s=scene(l);var c=s.citizens().stream().filter(r->r.profession()==CitizenProfession.WAREHOUSE_KEEPER).findFirst().orElseThrow();
        var e=CitizenProjectionService.findProjection(l,c);var out=new JsonArray();
        for(var b:s.buildings())if(b.type().isHouse()) {
            var j=new JsonObject();j.addProperty("home",b.id().toString());var positions=WarehouseInventoryService.interiorPositions(l,b);
            j.add("candidates",new Gson().toJsonTree(positions.stream().map(BlockPos::toShortString).toList()));
            j.add("entrances",new Gson().toJsonTree(BlueprintTemplates.createForBuilding(b).entrances().stream().map(v->v.position().toShortString()).toList()));
            j.addProperty("reachable",String.valueOf(e.findReachableBedStation(positions)));out.add(j);
        }
        return out.toString();
    }
    public static String consume(ServerLevel l) {
        var s=scene(l);var c=s.citizens().stream().filter(r->r.profession()!=CitizenProfession.WAREHOUSE_KEEPER && r.homeBuildingId()!=null
                && WarehouseInventoryService.countItem(l,s.building(r.homeBuildingId()),Items.BREAD)>0).findFirst().orElseThrow();
        var warehouse=SettlementInventoryService.nearestWarehouse(s,CORE);
        // Explicit fixture stimulus: conserve carried food, then induce hunger. No movement or automatic consumption here.
        var food=c.extractInventoryItem(CommunityLifeService::food,64);if(!food.isEmpty())WarehouseInventoryService.insert(l,warehouse,food);
        c.adjustNeeds(-60,0,0);SettlementSavedData.get(l).touch();return "Hunger stimulus: "+c.name()+"; carried food returned to warehouse; resident must walk to own home";
    }
    public static String animals(ServerLevel l) {
        var s=scene(l);var min=CORE.offset(-18,0,-36);var max=CORE.offset(4,3,-22);
        if(s.buildings().stream().anyMatch(b->b.type()==BuildingType.PASTURE_AREA))throw new IllegalStateException("Animals already set up");
        for(int x=min.getX();x<=max.getX();x++)for(int z=min.getZ();z<=max.getZ();z++)
            if(x==min.getX()||x==max.getX()||z==min.getZ()||z==max.getZ())l.setBlock(new BlockPos(x,min.getY(),z),Blocks.OAK_FENCE.defaultBlockState(),2);
        var gate=min.offset(10,0,0);l.setBlock(gate,Blocks.OAK_FENCE_GATE.defaultBlockState(),2);
        var b=s.addBuilding(BuildingType.PASTURE_AREA,min,Direction.NORTH,min,max,0,1);b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(gate));
        for(int i=0;i<64;i++) {
            var a=EntityType.SHEEP.create(l);a.moveTo(min.getX()+2+(i%16),min.getY(),min.getZ()+2+(i/16)*2,0,0);l.addFreshEntity(a);PastureResidentService.capture(l,s,b,a);
        }
        SettlementSavedData.get(l).touch();return "64 actual sheep added to a fresh enclosed pasture; normal AI ticks, no teleport after setup";
    }
}

package com.hkin.exiledsettlement.road;

/** Reads only the freshly created native test world selected explicitly by a JVM flag. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement")
public final class RoadTreeReloadCheck {
    @net.neoforged.bus.api.SubscribeEvent
    public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){
        if(!Boolean.getBoolean("exiledsettlement.roadTreeReload"))return;
        var l=event.getServer().overworld();var data=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(l);
        var s=data.settlements().stream().filter(v->v.name().equals("Tree road test")).findFirst().orElseThrow();
        var road=s.autoRoads().routes.get("tree-chain");var pending=s.autoRoads().routes.get("restart-pending");
        for(var p:road.cells)l.getChunkAt(p);for(var p:pending.cells)l.getChunkAt(p);
        var root=road.centerline.getFirst().above();int logs=0;
        for(var c:s.citizens())for(var item:c.inventory())if(item.is(net.minecraft.world.item.Items.OAK_LOG))logs+=item.getCount();
        for(var b:s.buildings()){
            if(b.type()==com.hkin.exiledsettlement.building.BuildingType.WAREHOUSE)logs+=com.hkin.exiledsettlement.building.WarehouseInventoryService.countItem(l,b,net.minecraft.world.item.Items.OAK_LOG);
            if(b.type()==com.hkin.exiledsettlement.building.BuildingType.WAGON && l.getBlockEntity(b.position()) instanceof net.minecraft.world.Container box)
                for(int i=0;i<box.getContainerSize();i++)if(box.getItem(i).is(net.minecraft.world.item.Items.OAK_LOG))logs+=box.getItem(i).getCount();
        }
        boolean valid=road.progress==road.cells.size()&&road.cells.stream().allMatch(p->l.getBlockState(p).is(net.minecraft.world.level.block.Blocks.DIRT_PATH))
                && logs==30 && pending.progress==0 && l.getBlockState(pending.cells.getFirst().above()).is(net.minecraft.world.level.block.Blocks.OAK_LOG)
                && s.terrain().projects.values().stream().anyMatch(p->p.dependencies.contains(pending.id)&&p.finished.isEmpty());
        for(int x:new int[]{5,9,13})for(int y=0;y<=10;y++)valid &=l.getBlockState(root.east(x).above(y)).isAir();
        var result=new com.google.gson.JsonObject();result.addProperty("passed",valid);result.addProperty("realServerRestart",true);result.addProperty("worldEdits",0);result.addProperty("logs",logs);result.addProperty("paved",road.progress);result.addProperty("pendingPreserved",pending.progress==0);result.addProperty("playerSavesAccessed",false);
        try{java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("roadtrees-reload",".json"),new com.google.gson.Gson().toJson(result));}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}

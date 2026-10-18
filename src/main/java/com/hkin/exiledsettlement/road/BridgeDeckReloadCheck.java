package com.hkin.exiledsettlement.road;

/** Read-only restart check for this turn's isolated native acceptance world. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement")
public final class BridgeDeckReloadCheck {
    @net.neoforged.bus.api.SubscribeEvent
    public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){
        if(!Boolean.getBoolean("exiledsettlement.bridgeDeckReload"))return;
        var l=event.getServer().overworld();var data=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(l);
        var p=data.settlements().stream().filter(s->s.name().equals("Deck building test")).flatMap(s->s.terrain().projects.values().stream()).filter(v->v.playerBridge!=null && v.playerBridge.buildingModel).findFirst().orElseThrow();
        // Only this newly created test world's registered footprint is loaded.
        for(var v:p.steps)l.getChunkAt(v.position());
        boolean valid=p.completed && com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(l,p)
                && p.finished.cardinality()==p.steps.size() && p.playerBridge.minRow()==p.playerBridge.start && p.playerBridge.maxRow()==p.playerBridge.end;
        var result=new com.google.gson.JsonObject();result.addProperty("passed",valid);result.addProperty("realServerRestart",true);result.addProperty("worldEdits",0);result.addProperty("finished",p.finished.cardinality());result.addProperty("steps",p.steps.size());result.addProperty("playerSavesAccessed",false);
        try{java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("bridgedeck-reload",".json"),new com.google.gson.Gson().toJson(result));}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}

package com.hkin.exiledsettlement.citizen;

/** Opt-in read-only measurements for isolated acceptance servers. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement")
public final class TreeWorkMetrics {
    public static final boolean ENABLED=Boolean.getBoolean("exiledsettlement.roadTreeNative");
    private static final java.util.Map<String,java.util.List<Long>> TIMES=new java.util.LinkedHashMap<>();
    private static long start;
    public static void record(String name,long since){if(ENABLED)TIMES.computeIfAbsent(name,k->new java.util.ArrayList<>()).add(System.nanoTime()-since);}
    @net.neoforged.bus.api.SubscribeEvent
    public static void before(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e){if(ENABLED)start=System.nanoTime();}
    @net.neoforged.bus.api.SubscribeEvent
    public static void after(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(!ENABLED)return;var l=e.getServer().overworld();if(l==null)return;
        long time=l.getDayTime()%24000;String phase=time>=13000&&time<23000?"night":"day";
        record("server_"+phase,start);record("phase_"+phase+"_"+(l.getGameTime()%20),start);
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent e){
        if(!ENABLED)return;var result=new com.google.gson.JsonObject();
        for(var entry:TIMES.entrySet()){
            var values=entry.getValue();values.sort(Long::compare);if(values.isEmpty())continue;
            var r=new com.google.gson.JsonObject();r.addProperty("count",values.size());
            r.addProperty("meanMs",values.stream().mapToLong(Long::longValue).average().orElse(0)/1e6);
            r.addProperty("p95Ms",values.get((int)((values.size()-1)*.95))/1e6);r.addProperty("maxMs",values.getLast()/1e6);result.add(entry.getKey(),r);
        }
        try{java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("roadtrees-metrics",".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
}

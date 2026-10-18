package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.*;

/** Bounded full-engine test ticks in a manifest-verified, empty dedicated test server. */
public final class Engineering91Simulation {
    private static boolean active;
    public static String run(ServerLevel l,int ticks)throws Exception{
        var server=l.getServer();
        if(!SaveAcceptanceService.enabled() || !server.isDedicatedServer() || server.getPlayerCount()!=0 || active)
            throw new IllegalStateException("EMPTY_DEDICATED_TEST_SERVER_REQUIRED");
        var file=Path.of("inputs.json");
        if(!Files.isRegularFile(file))throw new IllegalStateException("FRESH_TEST_MANIFEST_REQUIRED");
        var input=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(!input.has("freshWorld") || !input.get("freshWorld").getAsBoolean()
                || !input.has("playerWorldReadOrCopied") || input.get("playerWorldReadOrCopied").getAsBoolean())
            throw new IllegalStateException("FRESH_TEST_MANIFEST_REQUIRED");
        if(ticks<1 || ticks>1000)throw new IllegalArgumentException("BOUNDED_TICK_COUNT_REQUIRED");
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(l).settlements().stream()
                .filter(s->s.active() && s.name().equals("Engineering91 fresh complex terrain")).findFirst()
                .orElseThrow(()->new IllegalStateException("FRESH_ENGINEERING_SCENE_REQUIRED"));
        // Explicit test tickets are transient and must be reacquired after restart.
        SaveAcceptanceService.load(l,settlement,6);
        var actors=new java.util.LinkedHashMap<java.util.UUID,Integer>();
        for(var c:settlement.citizens()){
            var e=com.hkin.exiledsettlement.citizen.CitizenProjectionService.findProjection(l,c);
            if(e!=null && e.isAlive())actors.put(e.getUUID(),e.tickCount);
        }
        var rate=server.tickRateManager();
        if(rate.isSprinting())throw new IllegalStateException("STOP_NATIVE_SPRINT_FIRST");
        boolean frozen=rate.isFrozen();long before=l.getGameTime(),started=System.nanoTime();
        active=true;
        try{
            rate.setFrozen(false);
            // This is the public full server tick, including vanilla entities,
            // navigation, collisions, block updates, needs and all mod events.
            // No material, position, work state or world clock is assigned here.
            for(int i=0;i<ticks;i++)server.tickServer(()->true);
        }finally{active=false;rate.setFrozen(frozen);}
        long elapsed=System.nanoTime()-started,after=l.getGameTime();
        var evidence=new JsonObject();evidence.addProperty("method","full MinecraftServer.tickServer");
        evidence.addProperty("accelerated",true);evidence.addProperty("requestedTicks",ticks);
        evidence.addProperty("before",before);evidence.addProperty("after",after);
        evidence.addProperty("wallSeconds",elapsed/1.0E9);evidence.addProperty("normalLifeAndSchedules",true);
        var entityTicks=new JsonObject();int advanced=0;
        for(var entry:actors.entrySet()){
            var e=l.getEntity(entry.getKey());int delta=e==null?0:e.tickCount-entry.getValue();
            entityTicks.addProperty(entry.getKey().toString(),delta);if(delta>0)advanced++;
        }
        evidence.add("actualEntityTicks",entityTicks);evidence.addProperty("actorsAdvanced",advanced);
        Files.createDirectories(SaveAcceptanceService.directory());
        Files.writeString(SaveAcceptanceService.file("engineering91-acceleration",".jsonl"),new Gson().toJson(evidence)+"\n",
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        if(after-before!=ticks)throw new IllegalStateException("FULL_ENGINE_TICK_MISMATCH actual="+(after-before));
        if(!actors.isEmpty() && advanced==0)throw new IllegalStateException("ENTITY_PHYSICS_DID_NOT_ADVANCE");
        return "SIMULATED "+ticks+" full engine ticks; gameTime="+before+".."+after+"; wallSeconds="+elapsed/1.0E9
                +"; actual entities/materials/collision and normal life retained; accelerated evidence";
    }
    private Engineering91Simulation(){}
}

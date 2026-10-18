package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit test-only, bounded in-memory samples; no per-tick serialization or disk I/O. */
public final class TickPhaseProfiler {
    private static final String[] NAMES={"debugAndNetwork","roadEdits","decorationPolicy","weather","blizzard","projections","decorationRemoval","roadRemoval","productionRegions","terrain","roadPlanner","roadEarthwork","bridges","roads","seasonCalendar","migration","chunkLoading","citizenProjection","buildingValidation","inventoryLedger","townHall","merchant","communityLife","seasonSync","decoration","autoRoadReview","blueprints","citizenWork","demolition","construction","constructionDebug"};
    private static long[][] samples;
    private static long[] ticks,times,full,post;
    private static int cursor=-1,limit;
    private static long before,postStart,mark;
    private static String name;
    private TickPhaseProfiler() {}
    public static String start(String label,int count) {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        if(samples!=null)throw new IllegalStateException("PROFILE_RUNNING");
        if(count<20 || count>12000)throw new IllegalArgumentException("20..12000 ticks");
        if(Files.exists(SaveAcceptanceService.file(label,".json")))throw new IllegalArgumentException("REPORT_EXISTS");
        name=label;limit=count;cursor=-1;before=0;
        samples=new long[count][NAMES.length];ticks=new long[count];times=new long[count];full=new long[count];post=new long[count];
        return "Profiling next "+count+" normal ticks: "+label;
    }
    public static void before(ServerTickEvent.Pre event) {
        if(samples==null || !event.getServer().tickRateManager().runsNormally())return;
        cursor++;before=System.nanoTime();ticks[cursor]=event.getServer().getTickCount();times[cursor]=event.getServer().overworld().getDayTime();
    }
    public static void beginPost(){if(samples!=null && cursor>=0)postStart=mark=System.nanoTime();}
    public static void mark(int stage){if(samples==null || cursor<0)return;long now=System.nanoTime();samples[cursor][stage]+=now-mark;mark=now;}
    public static void finish(MinecraftServer server) {
        if(samples==null || cursor<0)return;
        long now=System.nanoTime();post[cursor]=now-postStart;full[cursor]=now-before;
        if(cursor+1<limit)return;
        var result=new JsonObject();result.addProperty("scope","server Pre through this mod Post; excludes later Post listeners and report export");
        result.add("stages",new Gson().toJsonTree(NAMES));result.add("serverTicks",new Gson().toJsonTree(ticks));result.add("dayTimes",new Gson().toJsonTree(times));
        result.add("stageNanos",new Gson().toJsonTree(samples));result.add("postNanos",new Gson().toJsonTree(post));result.add("preThroughPostNanos",new Gson().toJsonTree(full));
        try {Files.createDirectories(SaveAcceptanceService.directory());Files.writeString(SaveAcceptanceService.file(name,".json"),new Gson().toJson(result),StandardOpenOption.CREATE_NEW);}
        catch(Exception e){throw new IllegalStateException("Profile export failed",e);}
        finally{samples=null;ticks=times=full=post=null;cursor=-1;}
    }
}

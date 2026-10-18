package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Explicit test-harness fixtures. Setup blocks are not resident construction evidence. */
public final class NavigationLiveAcceptance {
    private static final List<Case> CASES=new ArrayList<>();
    private static JsonObject result;
    private static long started;
    private static final class Case {
        String name;ServerLevel level;SettlementCitizenEntity entity;BlockPos start,target;
        int calls,failures,maxStage;long searchesAtStart,arrivedAt=-1;
        double initialDistance,maxDistance,maxDisplacement;
        final Set<Long> chunks=new LinkedHashSet<>();
    }
    public static String start(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        if(!CASES.isEmpty())throw new IllegalStateException("ALREADY_RUNNING");
        result=null;started=level.getGameTime();
        for(int i=0;i<3;i++){
            var c=new Case();c.name=new String[]{"straight","u_wall_reverse_detour","sealed_start"}[i];c.level=level;
            c.start=new BlockPos(24000+i*128,240,24000);c.target=c.start.offset(i==1?32:20,0,0);
            for(int x=-32;x<=48;x+=16)for(int z=-32;z<=32;z+=16){var chunk=new net.minecraft.world.level.ChunkPos(c.start.offset(x,0,z));if(!level.getForcedChunks().contains(chunk.toLong())){level.setChunkForced(chunk.x,chunk.z,true);c.chunks.add(chunk.toLong());}}
            for(var p:BlockPos.betweenClosed(c.start.offset(-28,-1,-28),c.start.offset(44,3,28)))level.setBlock(p,p.getY()<240?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            if(i==1){
                for(int y=0;y<3;y++){
                    for(int z=-8;z<=8;z++)level.setBlock(c.start.offset(8,y,z),Blocks.STONE_BRICKS.defaultBlockState(),2);
                    for(int x=-16;x<=8;x++)for(int z:new int[]{-8,8})level.setBlock(c.start.offset(x,y,z),Blocks.STONE_BRICKS.defaultBlockState(),2);
                }
            }
            if(i==2)for(int y=0;y<3;y++)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0 || z!=0)level.setBlock(c.start.offset(x,y,z),Blocks.STONE_BRICKS.defaultBlockState(),2);
            c.entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);if(c.entity==null)throw new IllegalStateException("NO_ENTITY");
            c.entity.moveTo(c.start.getX()+.5,240,c.start.getZ()+.5,0,0);c.entity.setOnGround(true);c.entity.setPersistenceRequired();level.addFreshEntity(c.entity);
            c.initialDistance=Math.sqrt(c.start.distSqr(c.target));c.maxDistance=c.initialDistance;CASES.add(c);
        }
        return "OK isolated fixture blocks created; real entity movement only after spawn; 4000 tick maximum";
    }
    public static void tick(MinecraftServer server){
        if(CASES.isEmpty())return;
        long now=CASES.getFirst().level.getGameTime();
        for(var c:CASES){
            c.level.resetEmptyTime();if(c.arrivedAt>=0)continue;
            double distance=Math.sqrt(c.entity.distanceToSqr(c.target.getX()+.5,c.target.getY(),c.target.getZ()+.5));
            c.maxDistance=Math.max(c.maxDistance,distance);c.maxDisplacement=Math.max(c.maxDisplacement,Math.sqrt(c.entity.distanceToSqr(c.start.getX()+.5,c.start.getY(),c.start.getZ()+.5)));
            if(distance<.9){c.arrivedAt=now-started;c.entity.stopSettlementNavigation();continue;}
            if(now%10==0){c.calls++;String failure=c.entity.navigateTo(c.target,.7);if(!failure.isEmpty())c.failures++;c.maxStage=Math.max(c.maxStage,c.entity.navigationRetryStage());}
        }
        if(now-started>=4000 || now-started>=1200 && CASES.get(0).arrivedAt>=0 && CASES.get(1).arrivedAt>=0)finish(now);
    }
    private static void finish(long now){
        result=report(now);boolean passed=CASES.get(0).arrivedAt>=0 && CASES.get(1).arrivedAt>=0
                && CASES.get(1).maxDistance>CASES.get(1).initialDistance+3 && CASES.get(2).arrivedAt<0
                && CASES.get(2).maxDisplacement<.75 && CASES.get(2).maxStage>=3 && CASES.get(2).entity.navigationSearchCount()<(now-started);
        result.addProperty("finished",true);result.addProperty("passed",passed);
        for(var c:CASES){c.entity.discard();for(long packed:c.chunks){var p=new net.minecraft.world.level.ChunkPos(packed);c.level.setChunkForced(p.x,p.z,false);}}
        CASES.clear();
    }
    private static JsonObject report(long now){
        var out=new JsonObject();out.addProperty("elapsed",now-started);out.addProperty("teleportsAfterSpawn",0);out.addProperty("fixtureWorldEdits",true);
        var cases=new JsonArray();for(var c:CASES){var j=new JsonObject();j.addProperty("name",c.name);j.addProperty("arrivedAt",c.arrivedAt);j.addProperty("calls",c.calls);j.addProperty("returnedFailures",c.failures);j.addProperty("searches",c.entity.navigationSearchCount());j.addProperty("maxRetryStage",c.maxStage);j.addProperty("initialDistance",c.initialDistance);j.addProperty("maximumDistance",c.maxDistance);j.addProperty("maximumDisplacement",c.maxDisplacement);j.addProperty("position",c.entity.position().toString());j.addProperty("diagnostic",c.entity.settlementNavigationDiagnostics());cases.add(j);}out.add("cases",cases);return out;
    }
    public static String status(){return result!=null?result.toString():CASES.isEmpty()?"NOT_STARTED":report(CASES.getFirst().level.getGameTime()).toString();}
    private NavigationLiveAcceptance(){ }
}

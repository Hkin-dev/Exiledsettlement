package com.hkin.exiledsettlement.gametest;

import com.google.gson.*;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.debug.BuildingEditBridge;
import com.hkin.exiledsettlement.debug.BuildingEditWorkspace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingEditGameTests {
    private static BuildingEditWorkspace workspace(GameTestHelper h) throws Exception {
        BlockPos min = h.absolutePos(new BlockPos(1, 5, 1));
        BlockPos max = min.offset(2, 3, 2);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) h.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), 18);
        return new BuildingEditWorkspace(h.getLevel(), UUID.randomUUID(), "test", min, max,
                Files.createTempDirectory("building-api-test-"));
    }
    private static JsonObject request(BuildingEditWorkspace w, String id, BlockPos[] positions, BlockState[] states) {
        JsonObject snapshot = w.read(), body = new JsonObject();
        body.addProperty("request_id", id); body.addProperty("snapshot_id", snapshot.get("snapshot_id").getAsString());
        body.add("expected_revision", snapshot.get("revision"));
        JsonArray ops = new JsonArray();
        for (int i = 0; i < positions.length; i++) {
            String expected = snapshot.get("air_fingerprint").getAsString();
            for (JsonElement e : snapshot.getAsJsonArray("blocks")) {
                JsonObject cell = e.getAsJsonObject();
                if (BuildingEditWorkspace.position(cell.getAsJsonArray("pos")).equals(positions[i])) expected = cell.get("fingerprint").getAsString();
            }
            JsonObject op = BuildingEditWorkspace.encode(states[i]); op.add("pos", BuildingEditWorkspace.vector(positions[i]));
            op.addProperty("expected", expected); ops.add(op);
        }
        body.add("operations", ops); return body;
    }
    private static JsonObject one(BuildingEditWorkspace w, String id, BlockState state) {
        return request(w, id, new BlockPos[]{w.min}, new BlockState[]{state});
    }
    private static void rejected(GameTestHelper h, Action action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, message);
    }
    private interface Action { void run() throws Exception; }
    private static BlockState stone() { return Blocks.STONE.defaultBlockState(); }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void patchAndUndoAreActualWorldChanges(GameTestHelper h) throws Exception {
        var w = workspace(h); var body = one(w,"first",stone());
        w.patch(body,null); h.assertTrue(h.getLevel().getBlockState(w.min).isAir(),"Patch wrote before tick");
        w.advance(null,128); h.assertTrue(h.getLevel().getBlockState(w.min).is(Blocks.STONE),"Patch not applied");
        h.assertTrue(w.patch(body,null).get("applied").getAsInt()==1,"Retry was not idempotent");
        JsonObject undo = new JsonObject(); undo.addProperty("request_id","undo");
        w.undo("first",undo,null); h.assertTrue(!w.job("first").get("undone").getAsBoolean(),"Marked undone before application");
        w.advance(null,128); h.assertTrue(h.getLevel().getBlockState(w.min).isAir(),"Undo failed");
        h.assertTrue(w.job("first").get("undone").getAsBoolean(),"Undo not committed");
        w.undo("first",undo,null); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void manualEditsRejectStalePatch(GameTestHelper h) throws Exception {
        var w=workspace(h); var body=one(w,"first",stone());
        h.getLevel().setBlock(w.min,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
        rejected(h,()->w.patch(body,null),"Stale patch accepted");
        h.assertTrue(h.getLevel().getBlockState(w.min).is(Blocks.DIAMOND_BLOCK),"Player edit overwritten"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void tickBudgetAndPartialConflictUndo(GameTestHelper h) throws Exception {
        var w=workspace(h); BlockPos next=w.min.east();
        w.patch(request(w,"first",new BlockPos[]{w.min,next},new BlockState[]{stone(),stone()}),null);
        h.assertTrue(w.advance(null,1)==1,"Budget exceeded");
        h.getLevel().setBlock(next,Blocks.GOLD_BLOCK.defaultBlockState(),2); w.advance(null,128);
        h.assertTrue(w.job("first").get("state").getAsString().equals("conflict"),"Mid-job conflict ignored");
        JsonObject undo=new JsonObject(); undo.addProperty("request_id","undo"); w.undo("first",undo,null); w.advance(null,128);
        h.assertTrue(h.getLevel().getBlockState(w.min).isAir() && h.getLevel().getBlockState(next).is(Blocks.GOLD_BLOCK),"Partial undo overwrote player edit"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void undoCannotOverwriteManualChanges(GameTestHelper h) throws Exception {
        var w=workspace(h); w.patch(one(w,"first",stone()),null); w.advance(null,128);
        h.getLevel().setBlock(w.min,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
        JsonObject undo=new JsonObject(); undo.addProperty("request_id","undo");
        rejected(h,()->w.undo("first",undo,null),"Undo destroyed manual edit"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void usedContainerProtected(GameTestHelper h) throws Exception {
        var w=workspace(h); h.getLevel().setBlock(w.min,Blocks.CHEST.defaultBlockState(),2);
        Container chest=(Container)h.getLevel().getBlockEntity(w.min); chest.setItem(0,new ItemStack(Items.DIAMOND));
        rejected(h,()->w.patch(one(w,"first",stone()),null),"Used inventory accepted");
        h.assertTrue(chest.getItem(0).is(Items.DIAMOND),"Item lost"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void doorPairIsAtomic(GameTestHelper h) throws Exception {
        var w=workspace(h); var lower=Blocks.OAK_DOOR.defaultBlockState(); var upper=lower.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER);
        rejected(h,()->w.patch(one(w,"orphan",lower),null),"Orphan door accepted");
        w.patch(request(w,"pair",new BlockPos[]{w.min,w.min.above()},new BlockState[]{lower,upper}),null);
        h.assertTrue(w.advance(null,1)==0,"Split door across tick budget");
        h.assertTrue(w.advance(null,2)==2,"Pair not placed");
        h.assertTrue(h.getLevel().getBlockState(w.min.above()).is(Blocks.OAK_DOOR),"Upper door missing"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void dryRunAndInvalidInputNeverMutate(GameTestHelper h) throws Exception {
        var w=workspace(h); var dry=one(w,"dry",stone()); dry.addProperty("dry_run",true);
        h.assertTrue(w.patch(dry,null).get("state").getAsString().equals("validated"),"Dry validation failed"); w.advance(null,128);
        h.assertTrue(h.getLevel().getBlockState(w.min).isAir() && w.status().get("revision").getAsInt()==0,"Dry run mutated");
        var dup=one(w,"dup",stone()); dup.getAsJsonArray("operations").add(dup.getAsJsonArray("operations").get(0).deepCopy());
        rejected(h,()->w.patch(dup,null),"Duplicate accepted");
        var outside=one(w,"out",stone()); outside.getAsJsonArray("operations").get(0).getAsJsonObject().add("pos",BuildingEditWorkspace.vector(w.max.east()));
        rejected(h,()->w.patch(outside,null),"Outside region accepted"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void unsafeStatesAndUnknownPropertiesRejected(GameTestHelper h) throws Exception {
        for(String id:new String[]{"minecraft:tnt","minecraft:water","minecraft:sand","minecraft:command_block","exiledsettlement:settlement_core","minecraft:not_a_block"}) {
            JsonObject entry=new JsonObject(); entry.addProperty("block",id);
            rejected(h,()->BuildingEditWorkspace.parseState(entry),"Unsafe block accepted: "+id);
        }
        var entry=BuildingEditWorkspace.encode(stone()); entry.getAsJsonObject("state").addProperty("facing","north");
        rejected(h,()->BuildingEditWorkspace.parseState(entry),"Unknown state property accepted"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void allBuildingTypesHaveDesignEntry(GameTestHelper h) {
        h.assertTrue(BuildingEditBridge.catalog().get("count").getAsInt()==35,"Catalog incomplete");
        int templates=0;
        for(var type:BuildingType.values()) if(BlueprintTemplates.supports(type)) {
            var result=BuildingEditBridge.template(URI.create("/v1/template?type="+type.serializedName()));
            h.assertTrue(!result.getAsJsonArray("blocks").isEmpty(),"Empty template: "+type); templates++;
        }
        h.assertTrue(templates==34,"Template count incomplete"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void tokenHostAndOriginProtection(GameTestHelper h) {
        h.assertTrue(BuildingEditBridge.authorized("Bearer secret","secret","127.0.0.1:1234",1234,null),"Valid authorization rejected");
        h.assertTrue(!BuildingEditBridge.authorized(null,"secret","127.0.0.1:1234",1234,null),"Anonymous allowed");
        h.assertTrue(!BuildingEditBridge.authorized("Bearer wrong","secret","127.0.0.1:1234",1234,null),"Wrong key allowed");
        h.assertTrue(!BuildingEditBridge.authorized("Bearer secret","secret","evil.test:1234",1234,null),"DNS rebinding allowed");
        h.assertTrue(!BuildingEditBridge.authorized("Bearer secret","secret","127.0.0.1:1234",1234,"https://evil.test"),"Browser-origin allowed"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void cancelPreservesAlreadyAppliedBlocks(GameTestHelper h) throws Exception {
        var w=workspace(h); w.patch(request(w,"first",new BlockPos[]{w.min,w.min.east()},new BlockState[]{stone(),stone()}),null);
        w.advance(null,1); w.cancel(); w.advance(null,128);
        h.assertTrue(h.getLevel().getBlockState(w.min).is(Blocks.STONE) && h.getLevel().getBlockState(w.min.east()).isAir(),"Cancel erased or continued mutation"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api")
    public static void requestIdCannotBeReusedWithDifferentContent(GameTestHelper h) throws Exception {
        var w=workspace(h); var body=one(w,"first",stone()); w.patch(body,null); w.advance(null,128);
        body.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("block","minecraft:oak_planks");
        rejected(h,()->w.patch(body,null),"Conflicting request id accepted"); h.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="building_api_http", timeoutTicks=2400)
    public static void authenticatedHttpPatchAndUndoReachRealWorld(GameTestHelper h) throws Exception {
        var player=h.makeMockServerPlayerInLevel();
        var players=h.getLevel().getServer().getPlayerList();
        // GameTestServer defaults to op level 0; give this isolated mock explicit level 2.
        players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(),2,false));
        var w=workspace(h);
        var bridge=BuildingEditBridge.enable(player); bridge.authorize(player,"http",w.min,w.max);
        var connection=JsonParser.parseString(Files.readString(BuildingEditBridge.directory().resolve("connection.json"))).getAsJsonObject();
        String url=connection.get("url").getAsString(), key=connection.get("token").getAsString();
        var probe=CompletableFuture.supplyAsync(()-> {
            try {
                var client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
                var anonymous=client.send(HttpRequest.newBuilder(URI.create(url+"/v1/catalog")).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(anonymous.statusCode()!=403) throw new IllegalStateException("Anonymous HTTP request accepted");
                var snapshot=http(client,url,key,"/v1/regions/http/snapshot",null);
                var body=new JsonObject(); body.addProperty("request_id","http_patch"); body.add("snapshot_id",snapshot.get("snapshot_id")); body.add("expected_revision",snapshot.get("revision"));
                var op=BuildingEditWorkspace.encode(stone()); op.add("pos",BuildingEditWorkspace.vector(w.min)); op.add("expected",snapshot.get("air_fingerprint"));
                var operations=new JsonArray(); operations.add(op); body.add("operations",operations);
                http(client,url,key,"/v1/regions/http/patch",body);
                var applied=awaitHttp(client,url,key,"http_patch");
                if(applied.get("applied").getAsInt()!=1) throw new IllegalStateException("HTTP patch not applied");
                return applied;
            } catch(Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(probe.isDone(),"HTTP probe pending"))
                .thenExecute(()-> {
                    probe.join(); h.assertTrue(h.getLevel().getBlockState(w.min).is(Blocks.STONE),"HTTP did not place real stone");
                }).thenExecute(()-> {
                    var undo=CompletableFuture.supplyAsync(()-> {
                        try {
                            var client=HttpClient.newHttpClient(); var body=new JsonObject(); body.addProperty("request_id","http_undo");
                            http(client,url,key,"/v1/regions/http/jobs/http_patch/undo",body); return awaitHttp(client,url,key,"http_undo");
                        } catch(Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                    });
                    h.startSequence().thenWaitUntil(()->h.assertTrue(undo.isDone(),"HTTP undo pending"))
                            .thenExecute(()-> {
                                undo.join(); h.assertTrue(h.getLevel().getBlockState(w.min).isAir(),"HTTP undo did not restore world");
                                try { BuildingEditBridge.disable(player); } catch(Exception e) { throw new IllegalStateException(e); }
                                players.deop(player.getGameProfile()); players.remove(player);
                            }).thenSucceed();
                });
        h.runAtTickTime(2390,()->BuildingEditBridge.shutdown(h.getLevel().getServer()));
    }
    private static JsonObject http(HttpClient client,String url,String key,String path,JsonObject body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json");
        if(body==null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("HTTP "+response.statusCode()+": "+response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static JsonObject awaitHttp(HttpClient client,String url,String key,String id) throws Exception {
        for(int i=0;i<100;i++) {
            var job=http(client,url,key,"/v1/regions/http/jobs/"+id,null);
            if(job.get("state").getAsString().equals("complete")) return job;
            if(!job.get("state").getAsString().equals("queued")) throw new IllegalStateException("HTTP job failed: "+job);
            Thread.sleep(30);
        }
        throw new IllegalStateException("HTTP job timed out");
    }
}

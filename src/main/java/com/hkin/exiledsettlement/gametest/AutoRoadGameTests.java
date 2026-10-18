package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.event.SettlementEvents;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AutoRoadGameTests {
    private static int fixtureIndex;
    private record Fixture(SettlementRecord s, List<net.minecraft.world.level.ChunkPos> chunks) { }
    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel(); var core = h.absolutePos(new BlockPos(260000 + fixtureIndex++ * 512, 4, 0));
        var chunks = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int x = (core.getX() - 30) >> 4; x <= (core.getX() + 30) >> 4; x++)
            for (int z = (core.getZ() - 30) >> 4; z <= (core.getZ() + 30) >> 4; z++) {
                level.setChunkForced(x,z,true); chunks.add(new net.minecraft.world.level.ChunkPos(x,z));
            }
        for (var p : BlockPos.betweenClosed(core.offset(-28,-1,-28),core.offset(28,-1,28))) {
            level.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
            for(int y=1;y<=4;y++) level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);
        }
        level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),3);
        var s=SettlementSavedData.get(level).createOrRecover(core,UUID.randomUUID(),"Auto road fixture").value();
        h.assertTrue(s!=null,"Road fixture overlaps another settlement"); return new Fixture(s,chunks);
    }
    private static void clean(GameTestHelper h, Fixture f) {
        f.s().autoRoads().enabled=false; f.s().deactivate();
        for(var c:f.s().citizens()) { var e=CitizenProjectionService.findProjection(h.getLevel(),c); if(e!=null)e.discard(); }
        f.chunks().forEach(c->h.getLevel().setChunkForced(c.x,c.z,false));
    }
    private static BuildingRecord house(GameTestHelper h, SettlementRecord s) {
        var t=BlueprintTemplates.create(BuildingType.HOUSE,s.corePosition().east(18),Direction.WEST);
        for(var p:t.placements()) h.getLevel().setBlock(p.position(),p.state(),3);
        var b=s.addBuilding(BuildingType.HOUSE,t.anchor(),Direction.WEST,t.minCorner(),t.maxCorner(),2,BlueprintTemplates.currentTemplateVersion(BuildingType.HOUSE));
        b.updateFunction(true,BuildingStatus.OPERATIONAL,2,List.of()); return b;
    }
    private static AutoRoadState.Route job(SettlementRecord s, BuildingRecord b, List<BlockPos> cells) {
        var r=new AutoRoadState.Route(b.id(),cells.getLast(),cells);
        s.autoRoads().routes.put(b.id()+":"+r.entrance.asLong(),r); return r;
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void explicitSettingsRejectInvalidValuesAndSurviveReload(GameTestHelper h) {
        var s = new SettlementRecord(UUID.randomUUID(), "Explicit settings", new BlockPos(0,10,0), UUID.randomUUID());
        h.assertTrue(CommunitySettings.apply(s,"auto_roads","on") && CommunitySettings.apply(s,"auto_roads","on")
                && s.autoRoads().enabled, "Repeated explicit selection toggled roadwork back off");
        h.assertTrue(CommunitySettings.apply(s,"policy","harvest") && CommunitySettings.apply(s,"policy","normal")
                && CommunitySettings.apply(s,"road_budget","128") && CommunitySettings.apply(s,"meal_target","64")
                && CommunitySettings.apply(s,"decor_road","off"), "Valid explicit selection rejected");
        var before = s.save(h.getLevel().registryAccess());
        for (String[] invalid : List.of(new String[]{"road_budget","99999"}, new String[]{"auto_roads","true"},
                new String[]{"policy","unknown"}, new String[]{"decor_density","-1"},
                new String[]{"local_season_hud","off"}, new String[]{"road_cancel_here","on"}))
            h.assertTrue(!CommunitySettings.apply(s,invalid[0],invalid[1]), "Untrusted setting accepted");
        h.assertTrue(before.equals(s.save(h.getLevel().registryAccess())), "Rejected setting changed save data");
        var loaded = SettlementRecord.load(before,h.getLevel().registryAccess());
        h.assertTrue(loaded.autoRoads().enabled && loaded.autoRoads().dailyBudget==128
                && loaded.community().policy.equals("normal") && loaded.community().mealTarget==64
                && !loaded.decorations().family(1) && loaded.decorations().family(2), "Explicit settings lost on reload or touched other family");
        var payload = new com.hkin.exiledsettlement.network.CommunitySnapshotPayload(s.corePosition(),s.name(),
                List.of("测试道路状态"),CommunitySettings.values(loaded,s.corePosition()));
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            com.hkin.exiledsettlement.network.CommunitySnapshotPayload.STREAM_CODEC.encode(buf,payload);
            h.assertTrue(payload.equals(com.hkin.exiledsettlement.network.CommunitySnapshotPayload.STREAM_CODEC.decode(buf)),
                    "Current setting values lost during network roundtrip");
        } finally { buf.release(); }
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void visitorCannotUseExplicitSettingsToBypassMembership(GameTestHelper h) {
        var f=fixture(h); var s=f.s(); var visitor=h.makeMockServerPlayerInLevel();
        visitor.moveTo(s.corePosition().getCenter()); var before=s.save(h.getLevel().registryAccess());
        CommunityManagement.action(visitor,s.corePosition(),"auto_roads=on");
        CommunityManagement.action(visitor,s.corePosition(),"policy=emergency");
        CommunityManagement.action(visitor,s.corePosition(),"road_budget=128");
        h.assertTrue(before.equals(s.save(h.getLevel().registryAccess())), "Visitor bypassed member check with explicit settings");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void diagnosticsExplainHarvestWithoutMutatingOrTrustingOldPlannerText(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var level=h.getLevel();s.setCreativeCoreInstalled(true);s.autoRoads().enabled=true;
        s.autoRoads().status="旧的等待提示";s.community().policy="harvest";
        var before=s.save(level.registryAccess());
        h.assertTrue(AutoRoadService.blockers(level,s).stream().anyMatch(v->v.contains("秋收优先") && v.contains("正常") && v.contains("与当前季节无关")),
                "Active harvest policy was hidden by stale planner text");
        h.assertTrue(before.equals(s.save(level.registryAccess())),"Reading diagnostics changed the world or saved settings");
        CommunitySettings.apply(s,"policy","normal");
        h.assertTrue(AutoRoadService.blockers(level,s).stream().noneMatch(v->v.contains("秋收优先")),"Policy diagnosis failed to update immediately");
        s.setCreativeCoreInstalled(false);s.community().foodReserve=24;s.community().fuelReserve=12;
        var reasons=AutoRoadService.blockers(level,s);
        h.assertTrue(reasons.stream().anyMatch(v->v.contains("公共食物 0件") && v.contains("24件"))
                && reasons.stream().anyMatch(v->v.contains("煤炭与木炭 0件") && v.contains("12件"))
                && reasons.stream().anyMatch(v->v.contains("缺少铲子")),"Stock/tool shortage omitted actionable quantities");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void legacyOptInProgressSuppressionAndBudgetSurviveReload(GameTestHelper h) {
        var s=new SettlementRecord(UUID.randomUUID(),"Road save",new BlockPos(0,10,0),UUID.randomUUID());
        var b=s.addBuilding(BuildingType.HOUSE,s.corePosition().east(15));
        var r=job(s,b,List.of(s.corePosition().below(),s.corePosition().east().below()));
        r.progress=1;r.suppressed=true;s.autoRoads().enabled=true;s.autoRoads().dailyBudget=128;
        s.autoRoads().newDay(12);s.autoRoads().spent=27;
        var tag=s.save(h.getLevel().registryAccess());var loaded=SettlementRecord.load(tag,h.getLevel().registryAccess());
        var copy=loaded.autoRoads().routes.values().iterator().next();
        h.assertTrue(loaded.autoRoads().enabled && loaded.autoRoads().dailyBudget==128 && loaded.autoRoads().spent==27
                && copy.suppressed && copy.progress==1 && copy.cells.equals(r.cells),"Road jobs or budget lost on reload");
        loaded.autoRoads().newDay(12);h.assertTrue(loaded.autoRoads().spent==27,"Budget refilled on same day");
        loaded.autoRoads().newDay(13);h.assertTrue(loaded.autoRoads().spent==0,"Budget did not reset next world day");
        tag.remove("AutoRoads");loaded=SettlementRecord.load(tag,h.getLevel().registryAccess());
        h.assertTrue(!loaded.autoRoads().enabled && loaded.autoRoads().routes.isEmpty() && loaded.aliveCitizenCount()==6,
                "Old saves silently started roadwork or changed residents");h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void plannerConnectsActualEntranceWithoutChangingTerrain(GameTestHelper h) {
        var f=fixture(h);var s=f.s();s.setCreativeCoreInstalled(true);var b=house(h,s);
        AutoRoadService.tick(h.getLevel(),s);h.assertTrue(s.autoRoads().routes.isEmpty(),"Disabled planner placed jobs");
        s.autoRoads().enabled=true;AutoRoadService.tick(h.getLevel(),s);
        h.assertTrue(s.autoRoads().routes.size()==1,"No automatic entrance route: "+s.autoRoads().status);
        var r=s.autoRoads().routes.values().iterator().next();
        h.assertTrue(r.building.equals(b.id()) && AutoRoadService.entrances(h.getLevel(),s,b).contains(r.entrance)
                && r.centerline.getLast().equals(r.entrance),"Planner missed actual building entrance");
        h.assertTrue(r.cells.stream().allMatch(p->h.getLevel().getBlockState(p).is(Blocks.DIRT)
                && !RoadSpaceData.get(h.getLevel()).contains(p)),"Planning changed terrain or registered unbuilt roads");
        for(int i=0;i<r.centerline.size()-1;i++) {
            var a=r.centerline.get(i);var next=r.centerline.get(i+1);
            h.assertTrue(Math.abs(a.getX()-next.getX())+Math.abs(a.getZ()-next.getZ())==1
                    && Math.abs(a.getY()-next.getY())<=1,"Road has diagonal gaps or steep steps");
        }
        AutoRoadService.cancelNear(s,r.entrance);for(int i=0;i<4;i++)AutoRoadService.tick(h.getLevel(),s);
        h.assertTrue(r.suppressed && s.autoRoads().routes.size()==1,"Canceled route was replanned");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void roadsAvoidCropsWaterPlayerBlocksForbiddenAndPreferExistingRoad(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var level=h.getLevel();var start=s.corePosition().west(16).below();var end=start.east(12);
        for(int i=4;i<=8;i++)level.setBlock(start.east(i), i==4?Blocks.FARMLAND.defaultBlockState():i==5?Blocks.WATER.defaultBlockState():Blocks.STONE_BRICKS.defaultBlockState(),3);
        var existing=new ArrayList<BlockPos>();
        for(int i=0;i<=12;i++) { var p=start.east(i).north(2);level.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),3);existing.add(p); }
        RoadSpaceData.get(level).addAll(existing,RoadTier.LEVEL_3);
        var cells=AutoRoadService.route(level,s,start,end);
        h.assertTrue(!cells.isEmpty() && cells.stream().noneMatch(p->p.getZ()==start.getZ() && p.getX()>=start.getX()+4 && p.getX()<=start.getX()+8),"Road crossed protected terrain");
        h.assertTrue(cells.stream().anyMatch(existing::contains) && existing.stream().allMatch(p->RoadSpaceData.get(level).levelAt(p)==3),"Existing high-level road was ignored or downgraded");
        s.decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(start).toLong());
        h.assertTrue(AutoRoadService.route(level,s,start,end).isEmpty(),"Forbidden area used");
        s.decorations().forbiddenChunks.clear();s.community().dangerChunks.add(new net.minecraft.world.level.ChunkPos(end).toLong());
        h.assertTrue(AutoRoadService.route(level,s,start,end).isEmpty(),"Danger area used");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void toolShortagePolicyConstructionAndDailyBudgetPauseWork(GameTestHelper h) {
        var f=fixture(h);var s=f.s();s.autoRoads().enabled=true;s.setCreativeCoreInstalled(true);var b=house(h,s);
        AutoRoadService.tick(h.getLevel(),s);s.setCreativeCoreInstalled(false);
        var worker=s.citizens().iterator().next();worker.assignProfession(CitizenProfession.WORKER,null);
        h.assertTrue(!AutoRoadService.assign(h.getLevel(),s,worker,new HashSet<>())
                && s.autoRoads().status.contains("缺少铲子"),"Tool shortage did not explain blocked work");
        s.community().policy="harvest";h.assertTrue(!AutoRoadService.allowed(s),"Harvest did not pause");
        s.community().policy="emergency";h.assertTrue(!AutoRoadService.allowed(s),"Emergency did not pause");
        s.community().policy="normal";s.community().evacuation=true;h.assertTrue(!AutoRoadService.allowed(s),"Evacuation did not pause");
        s.community().evacuation=false;s.autoRoads().newDay(h.getLevel().getDayTime()/24000);s.autoRoads().spent=64;
        worker.receiveEquipment(new ItemStack(Items.STONE_SHOVEL));
        h.assertTrue(!AutoRoadService.assign(h.getLevel(),s,worker,new HashSet<>()),"Budget exceeded");
        AutoRoadService.tick(h.getLevel(),s);h.assertTrue(!AutoRoadService.allowed(s),"Low survival stocks did not pause");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void protectionRejectsRemotePlacementAndCanceledBreakDoesNotSuppress(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var level=h.getLevel();s.autoRoads().enabled=true;s.setCreativeCoreInstalled(true);
        var b=house(h,s);AutoRoadService.tick(level,s);var r=s.autoRoads().routes.values().iterator().next();var p=r.cells.getFirst();
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);c.receiveEquipment(new ItemStack(Items.STONE_SHOVEL));
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(level);h.assertTrue(e!=null,"Missing road worker");e.setNoAi(true);
        e.moveTo(p.getX()+10.5,p.getY()+1,p.getZ()+.5,0,0);e.setOnGround(true);
        h.assertTrue(!AutoRoadService.layCell(level,s,c,e,p).success() && s.autoRoads().spent==0,"Remote worker paved terrain");
        e.moveTo(p.getX()+.5,p.getY()+1,p.getZ()+.5,0,0);e.setOnGround(true);
        boolean[] rejected={false};
        Consumer<BlockEvent.EntityPlaceEvent> cancel=event->{if(event.getPos().equals(p)){rejected[0]=true;event.setCanceled(true);}};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {
            h.assertTrue(!AutoRoadService.layCell(level,s,c,e,p).success() && rejected[0] && level.getBlockState(p).is(Blocks.DIRT)
                    && !RoadSpaceData.get(level).contains(p) && c.inventoryItem(AutoRoadService.shovelSlot(c)).getDamageValue()==0
                    && s.autoRoads().spent==0,"Protection rejection charged tool, left terrain or registered road");
        } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(AutoRoadService.layCell(level,s,c,e,p).success() && s.autoRoads().spent==1
                && RoadSpaceData.get(level).contains(p) && c.inventoryItem(AutoRoadService.shovelSlot(c)).getDamageValue()==1,
                "Valid on-site work failed after protection listener was removed");
        var visitor=h.makeMockServerPlayerInLevel();
        var event=new BlockEvent.BreakEvent(level,p,level.getBlockState(p),visitor);new SettlementEvents().onBlockBreak(event);
        h.assertTrue(event.isCanceled() && !r.suppressed,"Visitor suppressed managed road");
        AutoRoadService.notePlayerRemoval(level,p);AutoRoadService.flushPlayerRemovals(level);
        h.assertTrue(!r.suppressed,"Canceled break suppressed intact terrain");
        level.setBlock(p,Blocks.AIR.defaultBlockState(),3);AutoRoadService.playerRemoved(level,p);
        h.assertTrue(r.suppressed && !RoadSpaceData.get(level).contains(p),"Successful removal did not suppress route or clear road identity");
        var other=r.cells.get(1);RoadSpaceData.get(level).addAll(List.of(other));AutoRoadService.playerRemoved(level,other);
        h.assertTrue(!RoadSpaceData.get(level).contains(other),"Suppressed route retained removed invisible road");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void plannedRoadReservesGroundAgainstFutureDecoration(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var p=s.corePosition().west(12);
        var template=BlueprintTemplates.create(BuildingType.LAMP_SMALL,p,Direction.NORTH,1);
        h.assertTrue(com.hkin.exiledsettlement.decoration.DecorationService.safeSite(h.getLevel(),s,template),"Invalid independent lamp fixture");
        var b=s.addBuilding(BuildingType.HOUSE,s.corePosition().east(20));
        var r=job(s,b,List.of(p.below(),p.east().below()));
        h.assertTrue(!com.hkin.exiledsettlement.decoration.DecorationService.safeSite(h.getLevel(),s,template),"Future lamp blocked a reserved road");
        r.suppressed=true;
        h.assertTrue(com.hkin.exiledsettlement.decoration.DecorationService.safeSite(h.getLevel(),s,template),"Canceled road kept unused reservation");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void disablingAndNormalConstructionReleaseRoadWorkerWithoutFreePaving(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var level=h.getLevel();s.setCreativeCoreInstalled(true);s.autoRoads().enabled=true;
        house(h,s);AutoRoadService.tick(level,s);var r=s.autoRoads().routes.values().iterator().next();var p=r.cells.getFirst();
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);c.receiveEquipment(new ItemStack(Items.STONE_SHOVEL));
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(level);h.assertTrue(e!=null,"Missing road worker");
        e.moveTo(p.getX()+.5,p.getY()+1,p.getZ()+.5,0,0);e.setOnGround(true);c.startTask(CitizenWorkState.TO_ROAD,p,null,null);
        s.autoRoads().enabled=false;AutoRoadService.process(level,SettlementSavedData.get(level),s,c,e);
        h.assertTrue(c.workState()==CitizenWorkState.IDLE && r.progress==0 && s.autoRoads().spent==0 && level.getBlockState(p).is(Blocks.DIRT),"Disabled road kept worker or paved");
        s.autoRoads().enabled=true;
        var at=s.corePosition().west(20);s.addBlueprint(BuildingType.HOUSE,at,at,Direction.NORTH,s.ownerId(),false);
        AutoRoadService.tick(level,s);
        h.assertTrue(!AutoRoadService.allowed(s) && s.autoRoads().status.contains("普通建筑"),"Roadwork competed with normal construction");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads")
    public static void oldRoadWidensSafelyAndRespectsSuppression(GameTestHelper h) {
        var f=fixture(h);var s=f.s();s.setCreativeCoreInstalled(true);s.autoRoads().enabled=true;house(h,s);AutoRoadService.tick(h.getLevel(),s);
        var route=s.autoRoads().routes.values().iterator().next();var line=List.copyOf(route.centerline);route.width=1;route.cells=line;route.progress=2;route.suppressed=true;
        AutoRoadService.tick(h.getLevel(),s);h.assertTrue(route.width==1&&route.cells.equals(line)&&route.progress==2,"Canceled old road was replanned or widened");
        route.suppressed=false;AutoRoadService.tick(h.getLevel(),s);
        h.assertTrue(route.width==3&&route.centerline.equals(line)&&route.cells.size()>line.size()*2,"Existing one-wide road did not create paid side cells");
        h.assertTrue(route.cells.stream().allMatch(p->h.getLevel().getBlockState(p).is(Blocks.DIRT)),"Widen planner changed terrain for free");
        var loaded=SettlementRecord.load(s.save(h.getLevel().registryAccess()),h.getLevel().registryAccess());var restored=loaded.autoRoads().routes.values().iterator().next();
        h.assertTrue(restored.width==3&&restored.cells.equals(route.cells)&&restored.centerline.equals(line),"Wide road shape lost on reload");clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="auto_roads_physical",timeoutTicks=3000)
    public static void workerFetchesRealShovelWalksAndConsumesDurabilityToLayRoad(GameTestHelper h) {
        var f=fixture(h);var s=f.s();var level=h.getLevel();var data=SettlementSavedData.get(level);
        var residents=new ArrayList<>(s.citizens());var worker=residents.getFirst();
        for(int i=1;i<residents.size();i++)residents.get(i).markDead(s.corePosition());
        worker.assignProfession(CitizenProfession.WORKER,null);
        var b=house(h,s);
        var storage=s.addBuilding(BuildingType.WAGON,s.corePosition().west(8));
        level.setBlock(storage.position(),Blocks.BARREL.defaultBlockState(),3);
        storage.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(storage.position()));
        var inventory=(Container)level.getBlockEntity(storage.position());
        var suppliedShovel=new ItemStack(Items.STONE_SHOVEL);suppliedShovel.setDamageValue(3);
        inventory.setItem(0,suppliedShovel);inventory.setItem(1,new ItemStack(Items.BREAD,64));
        inventory.setItem(2,new ItemStack(Items.CHARCOAL,32));
        s.autoRoads().enabled=true;s.autoRoads().dailyBudget=128;s.community().foodReserve=0;s.community().fuelReserve=0;
        AutoRoadService.tick(level,s);AutoRoadService.tick(level,s);
        var r=s.autoRoads().routes.values().stream().filter(v->v.building.equals(b.id())).findFirst().orElseThrow();
        h.assertTrue(r.cells.stream().allMatch(p->level.getBlockState(p).is(Blocks.DIRT)),"Planner secretly paved");
        level.setDayTime(6000);CitizenProjectionService.ensureSettlementCitizens(level,s);
        boolean[] visited={false};long start=level.getGameTime();
        h.startSequence().thenWaitUntil(()->h.assertTrue(CitizenProjectionService.findProjection(level,worker)!=null,"Waiting for worker projection"))
                .thenWaitUntil(()->{
                    level.setDayTime(6000);var e=CitizenProjectionService.findProjection(level,worker);
                    if(e!=null) {
                        worker.updateProjection(e.getUUID(),e.blockPosition());
                        if(e.distanceToSqr(storage.position().getCenter())<20)visited[0]=true;
                        if((level.getGameTime()-start)%8==0)CitizenWorkService.tickSettlement(level,data,s);
                    }
                    h.assertTrue(r.progress==r.cells.size(),"Physical road incomplete: "+worker.workState()+" "+worker.workFailure()+" "+r.status+" "+r.progress+"/"+r.cells.size()+" @ "+worker.lastPosition()+" target="+worker.targetPosition()+" first="+r.cells.getFirst()+" roadSpent="+s.autoRoads().spent+" shovel="+AutoRoadService.shovelSlot(worker));
                }).thenExecute(()->{
                    h.assertTrue(visited[0] && WarehouseInventoryService.countItem(level,storage,Items.STONE_SHOVEL)==0
                            && r.cells.stream().allMatch(p->RoadSpaceData.get(level).contains(p) && level.getBlockState(p).is(Blocks.DIRT_PATH)),
                            "Resident bypassed stock, walking, terrain or road registration");
                    h.assertTrue(worker.inventoryItem(AutoRoadService.shovelSlot(worker)).getDamageValue()==3+s.autoRoads().spent
                            && s.autoRoads().spent>=r.cells.size(),"Road work did not pay exact shovel durability");
                    var reload=SettlementRecord.load(s.save(level.registryAccess()),level.registryAccess());
                    h.assertTrue(reload.autoRoads().routes.get(b.id()+":"+r.entrance.asLong()).progress==r.progress,"Completed road progress lost");
                    clean(h,f);
                }).thenSucceed();
    }
}

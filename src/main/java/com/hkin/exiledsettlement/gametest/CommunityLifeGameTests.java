package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommunityLifeGameTests {
    private static SettlementRecord record() {
        var record = new SettlementRecord(UUID.randomUUID(), "Community regression", new BlockPos(150000, 10, 150000), UUID.randomUUID());
        // These isolated fixtures retain the original short calendar to exercise fixed boundaries.
        record.community().seasonLength = 4;
        return record;
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void matureResidentsNeverRetireOrLoseIdentity(GameTestHelper h) {
        var s = record(); var c = s.citizens().iterator().next(); c.rename("保留姓名");
        c.life().ageDays = 1000000; c.setManualAssignment(CitizenProfession.FARMER, UUID.randomUUID());
        var loaded = CitizenRecord.load(c.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.canWork() && loaded.alive() && loaded.health() == 100, "Age retired or harmed an adult");
        h.assertTrue(loaded.id().equals(c.id()) && loaded.name().equals("保留姓名") && loaded.hasManualAssignment(), "Identity/assignment changed");
        var old = c.save(h.getLevel().registryAccess()); old.remove("Life");
        h.assertTrue(CitizenRecord.load(old, h.getLevel().registryAccess()).canWork(), "Legacy resident became a child");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void childrenCannotBeAssignedUntilPersistentAdulthood(GameTestHelper h) {
        var s = record(); var parents = new ArrayList<>(s.citizens()); var c = s.addChild(parents.get(0), parents.get(1));
        h.assertTrue(!c.canWork() && !c.setManualAssignment(CitizenProfession.WORKER, null), "Child accepted labour assignment");
        var loaded = CitizenRecord.load(c.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.life().parentA.equals(parents.get(0).id()) && !loaded.canWork(), "Child relationship/stage lost");
        loaded.life().ageDays = loaded.life().adulthoodAt;
        h.assertTrue(loaded.canWork() && loaded.setManualAssignment(CitizenProfession.WORKER, null), "Adult did not gain labour eligibility");
        loaded.life().ageDays = Long.MAX_VALUE;
        h.assertTrue(loaded.canWork() && loaded.alive(), "Old adult retired/died"); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void largeNamePoolHasUniquePersistentResidents(GameTestHelper h) {
        h.assertTrue(CommunityContent.nameCapacity() >= 14000, "Expanded data-driven names not loaded");
        var s = record(); for (int i = 0; i < 250; i++) s.addMigrant();
        var names = new HashSet<String>();
        for (var c : s.citizens()) h.assertTrue(names.add(c.name()), "Generated duplicate resident name");
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        for (var c : s.citizens()) h.assertTrue(loaded.citizen(c.id()).name().equals(c.name()), "Reload rerolled a name");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void policyDangerOffersAndReportsSurviveReload(GameTestHelper h) {
        var s = record(); var state = s.community();
        state.policy = "emergency"; state.foodReserve = 48; state.birthsEnabled = true;
        state.evacuation = true; state.rally = s.corePosition().east(2);
        state.dangerChunks.add(new ChunkPos(s.corePosition().east(16)).toLong());
        state.migrantAdults = 2; state.migrantChildren = 1; state.offerExpires = 20;
        state.reports.add("年度记录"); for (int i = 0; i < 60; i++) state.event(i, "事件" + i);
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess()).community();
        h.assertTrue(loaded.policy.equals("emergency") && loaded.foodReserve == 48 && loaded.birthsEnabled && loaded.evacuation, "Policy lost");
        h.assertTrue(loaded.rally.equals(state.rally) && loaded.danger(state.rally.east(16)), "Safety state lost");
        h.assertTrue(loaded.migrantAdults == 2 && loaded.offerExpires == 20 && loaded.events.size() == 32 && loaded.reports.size() == 1, "Bounded records/offers lost");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void cookingRequiresFuelAndCommitsOnlyOnce(GameTestHelper h) {
        var s = record(); var c = s.citizens().iterator().next(); var l = c.life();
        l.cookingInput = "minecraft:wheat"; l.cookingInputCount = 3;
        l.cookingOutput = "minecraft:bread"; l.cookingOutputCount = 2;
        l.cookingFuel = "minecraft:charcoal"; l.cookingStage = 2;
        c.insertIntoInventory(new ItemStack(Items.WHEAT, 3));
        h.assertTrue(!CommunityCookingService.completeBatch(s, c) && c.inventory().stream().mapToInt(ItemStack::getCount).sum() == 3, "Missing fuel consumed input");
        c.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        h.assertTrue(CommunityCookingService.completeBatch(s, c), "Funded recipe failed");
        h.assertTrue(c.inventory().stream().filter(v -> v.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum() == 2
                && !c.hasInventoryItem(v -> v.is(Items.WHEAT) || v.is(Items.CHARCOAL)), "Cooking did not conserve items");
        h.assertTrue(!CommunityCookingService.completeBatch(s, c), "Committed recipe replayed");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void unloadedResidentsAreNotConsumedAndClockJumpIsBounded(GameTestHelper h) {
        var s = record(); var level = h.getLevel(); var data = new SettlementSavedData();
        long originalTime = level.getDayTime();
        level.setDayTime(100L * 24000L);
        s.community().lastDay = 0;
        s.community().lastNeedTick = level.getGameTime() - 100000;
        var c = s.citizens().iterator().next(); long age = c.life().ageDays;
        CommunityLifeService.tick(level, data, s);
        level.setDayTime(originalTime);
        h.assertTrue(c.life().ageDays == age + 1 && c.nutrition() == 100 && c.alive(), "Offline jump aged/consumed/killed residents");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=800)
    public static void hungryResidentWalksToRealPublicFoodAndConsumesExactlyOne(GameTestHelper h) {
        withPhysical(h, 0, f -> {
             f.citizen.adjustNeeds(-60, 0, 0);
            var storage = barrel(h, f, f.core.east(12), BuildingType.WAGON);
            ((Container) h.getLevel().getBlockEntity(storage.position())).setItem(0, new ItemStack(Items.BREAD, 5));
            long start = h.getLevel().getGameTime();
            h.startSequence().thenWaitUntil(() -> {
                if ((h.getLevel().getGameTime() - start) % 8 == 0) {
                    f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
                    CommunityLifeService.personal(h.getLevel(), f.data, f.settlement, f.citizen, f.entity);
                }
                h.assertTrue(f.citizen.nutrition() >= 80, "Resident did not reach/consume food: " + f.citizen.workFailure()
                        + " pos=" + f.entity.position() + " ticks=" + f.entity.tickCount + " onGround=" + f.entity.onGround()
                        + " nav=" + f.entity.settlementNavigationDiagnostics());
                h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(), storage, Items.BREAD) == 0
                        && CommunityLifeService.carriedFood(f.citizen) == 4 && f.settlement.community().mealsEaten == 1,
                        "Food withdrawal, carried rations and one consumed meal did not conserve five bread");
                h.assertTrue(f.entity.distanceToSqr(f.core.getX()+0.5, f.core.getY(), f.core.getZ()+0.5) > 16, "Food bypassed actual movement");
            }).thenExecute(() -> cleanup(h, f)).thenSucceed();
        });
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=1500)
    public static void restaurantWorkerFetchesProcessesAndDeliversRealItems(GameTestHelper h) {
        withPhysical(h, 1, f -> {
             f.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 4));
             f.settlement.community().fuelReserve = 0; f.settlement.community().foodReserve = 0;
            var storage = barrel(h, f, f.core.east(8), BuildingType.WAGON);
            var input = (Container) h.getLevel().getBlockEntity(storage.position());
            input.setItem(0, new ItemStack(Items.WHEAT, 3)); input.setItem(1, new ItemStack(Items.CHARCOAL));
            var restaurant=barrel(h, f, f.core.north(8), BuildingType.RESTAURANT);
            f.citizen.setManualAssignment(CitizenProfession.CHEF,restaurant.id());
            long start = h.getLevel().getGameTime();
            h.startSequence().thenWaitUntil(() -> {
                if ((h.getLevel().getGameTime() - start) % 8 == 0) {
                    f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
                    CommunityLifeService.prepareWork(h.getLevel(), f.data, f.settlement);
                }
                h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(), storage, Items.BREAD) == 2, "Cook did not deliver: " + f.citizen.life().task + " " + f.citizen.workFailure()
                        + " pos=" + f.entity.position() + " stage=" + f.citizen.life().cookingStage + " work=" + f.citizen.life().workTicks
                        + " nav=" + f.entity.settlementNavigationDiagnostics());
                h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(), storage, Items.WHEAT) == 0
                        && WarehouseInventoryService.countItem(h.getLevel(), storage, Items.CHARCOAL) == 0, "Inputs not consumed exactly");
                h.assertTrue(f.settlement.community().mealsProduced == 2 && f.citizen.life().task.isEmpty()
                        && CommunityLifeService.carriedFood(f.citizen) == 4, "Cooking did not retain personal rations or close the batch");
            }).thenExecute(() -> cleanup(h, f)).thenSucceed();
        });
    }
    private record Fixture(SettlementRecord settlement, CitizenRecord citizen, SettlementCitizenEntity entity,
                           SettlementSavedData data, BlockPos core, ChunkPos min, ChunkPos max) { }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=800)
    public static void fullBackpackResidentWalksToFoodWithoutLosingWorkMaterials(GameTestHelper h) {
        var base = physical(h, 3, false);
        Fixture[] ready = new Fixture[1];
        var storage = barrel(h, base, base.core.east(12), BuildingType.WAGON);
        ((Container) h.getLevel().getBlockEntity(storage.position())).setItem(0, new ItemStack(Items.BREAD, 5));
        long start = h.getLevel().getGameTime();
        h.startSequence().thenWaitUntil(() -> {
            CitizenProjectionService.ensureSettlementCitizens(h.getLevel(), base.settlement);
            var entity = CitizenProjectionService.findProjection(h.getLevel(), base.citizen);
            h.assertTrue(entity != null, "Waiting for fixture entity chunk to load");
            entity.moveTo(base.core.getX()+0.5, base.core.getY(), base.core.getZ()+0.5, 0, 0);
            entity.setOnGround(true); base.citizen.updateProjection(entity.getUUID(), base.core);
            ready[0] = new Fixture(base.settlement, base.citizen, entity, base.data, base.core, base.min, base.max);
        }).thenExecute(() -> {
            base.citizen.adjustNeeds(-60, 0, 0);
            for (int i = 0; i < CitizenRecord.INVENTORY_SIZE; i++)
                h.assertTrue(base.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 64)).isEmpty(), "Cannot fill fixture backpack");
        }).thenWaitUntil(() -> {
            var f = ready[0];
            if ((h.getLevel().getGameTime() - start) % 8 == 0) {
                f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
                CommunityLifeService.personal(h.getLevel(), f.data, f.settlement, f.citizen, f.entity);
            }
            h.assertTrue(f.citizen.nutrition() >= 80, "Full backpack prevented eating: " + f.citizen.workFailure());
            h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(), storage, Items.BREAD) == 4
                    && f.settlement.community().mealsEaten == 1, "Meal was lost or consumed more than once");
            h.assertTrue(f.citizen.inventory().stream().filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum()
                    == CitizenRecord.INVENTORY_SIZE * 64, "Eating discarded or replaced work materials");
            h.assertTrue(f.entity.distanceToSqr(f.core.getX()+0.5, f.core.getY(), f.core.getZ()+0.5) > 16,
                    "Full-backpack eating bypassed physical movement");
        }).thenExecute(() -> cleanup(h, ready[0])).thenSucceed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void emergencyPausesExistingMaintenanceAndPreservesDeliveryMaterials(GameTestHelper h) {
        withPhysical(h, 4, f -> {
             f.settlement.community().policy = "emergency";
            f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 17));
            for (var work : List.of(CitizenWorkState.TO_REPAIR, CitizenWorkState.TO_REPAIR_SUPPLY,
                    CitizenWorkState.TO_CONSTRUCTION_SUPPLY, CitizenWorkState.TO_FOOTING_SUPPLY,
                    CitizenWorkState.TO_BLUEPRINT, CitizenWorkState.TO_DEMOLITION)) {
                h.assertTrue(f.citizen.startTask(work, f.core.east(5), null, null), "Cannot start fixture task");
                CommunityLifeService.prepareWork(h.getLevel(), f.data, f.settlement);
                h.assertTrue(f.citizen.workState() == CitizenWorkState.IDLE, "Emergency left existing task active: " + work);
                h.assertTrue(f.citizen.inventory().stream().filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 17,
                        "Emergency lost or duplicated work materials");
            }
            f.citizen.startTask(CitizenWorkState.TO_WAREHOUSE, f.core.east(5), null, null);
            CommunityLifeService.prepareWork(h.getLevel(), f.data, f.settlement);
            h.assertTrue(f.citizen.workState() == CitizenWorkState.TO_WAREHOUSE, "Emergency also paused supply delivery");
            cleanup(h, f); h.succeed();
        });
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=200)
    public static void evacuationPreservesInventoryAndStopsWork(GameTestHelper h) {
        withPhysical(h, 2, f -> {
             f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG,17));
            f.citizen.startTask(CitizenWorkState.TO_REPAIR,f.core.east(5),null,null);
            f.settlement.community().evacuation=true; f.settlement.community().rally=f.core.north(3);
            CommunityLifeService.prepareWork(h.getLevel(),f.data,f.settlement);
            h.assertTrue(f.citizen.workState()==CitizenWorkState.IDLE && f.citizen.activity()==CitizenActivity.EVACUATING,
                    "Evacuation did not pause the work state");
            h.assertTrue(f.citizen.inventory().stream().filter(s->s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum()==17,
                    "Evacuation lost/duplicated carried resources");
            cleanup(h,f); h.succeed();
        });
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void birthNeedsFoodHomeSpaceAndCreatesPersistentChild(GameTestHelper h) {
        var level=h.getLevel(); var s=record(); var parents=new ArrayList<>(s.citizens());
        for(var c:parents) if(c!=parents.get(0)&&c!=parents.get(1)) c.markDead(s.corePosition());
        var home=s.addBuilding(BuildingType.ADVANCED_HOUSE_3,s.corePosition().east(10));
        home.updateFunction(true,BuildingStatus.OPERATIONAL,4,List.of(home.position(),home.position().east(),home.position().east(2),home.position().east(3)));
        s.refreshHousingAssignments(); var a=parents.get(0); var b=parents.get(1);
        a.life().partner=b.id(); b.life().partner=a.id(); a.life().lastBirthDay=b.life().lastBirthDay=-1000;
        var storage=s.addBuilding(BuildingType.WAGON,h.absolutePos(new BlockPos(0,2,2)));
        level.setBlock(storage.position(),Blocks.BARREL.defaultBlockState(),3);
        storage.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(storage.position()));
        s.community().birthsEnabled=true; long original=level.getDayTime(); level.setDayTime(100*24000L);
        s.community().lastDay=99; CommunityLifeService.tick(level,new SettlementSavedData(),s);
        h.assertTrue(s.aliveCitizenCount()==2,"Birth ignored missing food");
        ((Container)level.getBlockEntity(storage.position())).setItem(0,new ItemStack(Items.BREAD,64));
        s.community().lastDay=99; CommunityLifeService.tick(level,new SettlementSavedData(),s);
        level.setDayTime(original);
        h.assertTrue(s.aliveCitizenCount()==3,"Birth did not create one child with food and free household bed");
        var child=s.citizens().stream().filter(c->c.alive()&&!c.canWork()).findFirst().orElseThrow();
        h.assertTrue(child.life().parentA.equals(a.id())||child.life().parentA.equals(b.id()),"Parent link missing");
        var loaded=SettlementRecord.load(s.save(level.registryAccess()),level.registryAccess());
        h.assertTrue(!loaded.citizen(child.id()).canWork()&&loaded.citizen(child.id()).homeBuildingId().equals(home.id()),"Child home or stage lost");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void coldForecastDoesNotFreezeSummerAndFrostIsBounded(GameTestHelper h) {
        var s=record(); s.community().coldFrom=12; s.community().coldUntil=16;
        h.assertTrue(!CommunityLifeService.severeCold(s.community(),7)&&CommunityLifeService.severeCold(s.community(),12)
                &&!CommunityLifeService.severeCold(s.community(),16),"Forecast applied cold before/after event");
        s.community().frostDay=9; int total=0;
        for(int i=0;i<100;i++) total+=CommunityLifeService.frostLoss(s,9,new BlockPos(i,1,0),1);
        h.assertTrue(total>=20&&total<=30,"Small wheat drops never suffer the forecast frost");
        h.assertTrue(CommunityLifeService.frostLoss(s,8,new BlockPos(0,1,0),10)==0,"Early harvest was penalized");
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void visitorCannotChangeCommunityPolicy(GameTestHelper h) {
        var level=h.getLevel(); var visitor=h.makeMockServerPlayerInLevel(); var data=SettlementSavedData.get(level);
        var core=h.absolutePos(new BlockPos(90000,10,100)); data.createOrRecover(core,UUID.randomUUID(),"Permission fixture");
        var s=data.findByCore(core).orElseThrow(); visitor.moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5,0,0);
        CommunityManagement.action(visitor,core,"births"); CommunityManagement.action(visitor,core,"evacuate");
        h.assertTrue(!s.community().birthsEnabled&&!s.community().evacuation,"Visitor changed population/safety policy");
        s.deactivate(); data.touch(); h.succeed();
    }
    private static Fixture physical(GameTestHelper h, int scenario) {
        return physical(h, scenario, false);
    }
    private static void withPhysical(GameTestHelper h, int scenario, java.util.function.Consumer<Fixture> action) {
        var base = physical(h, scenario);
        Fixture[] ready = new Fixture[1];
        h.startSequence().thenWaitUntil(() -> {
            CitizenProjectionService.ensureSettlementCitizens(h.getLevel(), base.settlement);
            var entity = CitizenProjectionService.findProjection(h.getLevel(), base.citizen);
            h.assertTrue(entity != null, "Waiting for fixture entity chunk to load");
            entity.moveTo(base.core.getX()+0.5, base.core.getY(), base.core.getZ()+0.5, 0, 0);
            entity.setOnGround(true); base.citizen.updateProjection(entity.getUUID(), base.core);
            ready[0] = new Fixture(base.settlement, base.citizen, entity, base.data, base.core, base.min, base.max);
        }).thenExecute(() -> action.accept(ready[0]));
    }
    private static Fixture physical(GameTestHelper h, int scenario, boolean spawnImmediately) {
        var level = h.getLevel(); BlockPos core = h.absolutePos(new BlockPos(28000 + scenario * 96, 10, 8));
        for (var p : BlockPos.betweenClosed(core.offset(-12, -1, -12), core.offset(12, -1, 12))) level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        ChunkPos min = new ChunkPos(core.offset(-16, 0, -16)), max = new ChunkPos(core.offset(16, 0, 16));
        for (int x=min.x; x<=max.x; x++) for(int z=min.z; z<=max.z; z++) level.setChunkForced(x,z,true);
        level.setDayTime(6000L);
        var s = new SettlementRecord(UUID.randomUUID(), "Physical community", core, UUID.randomUUID());
        var c = s.citizens().iterator().next(); for (var other : s.citizens()) if (other != c) other.markDead(core);
        if (!spawnImmediately) return new Fixture(s,c,null,new SettlementSavedData(),core,min,max);
        CitizenProjectionService.ensureSettlementCitizens(level, s);
        var e = CitizenProjectionService.findProjection(level, c); h.assertTrue(e != null, "Missing projection");
        e.moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5,0,0); e.setOnGround(true); c.updateProjection(e.getUUID(),core);
        return new Fixture(s,c,e,new SettlementSavedData(),core,min,max);
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void foundingVoucherRequiresEntitlementOwnershipAndCannotReplay(GameTestHelper h) {
        var level=h.getLevel(); var owner=h.makeMockServerPlayerInLevel(); var visitor=h.makeMockServerPlayerInLevel();
        var data=SettlementSavedData.get(level); var core=h.absolutePos(new BlockPos(100000,10,200));
        data.createOrRecover(core,owner.getUUID(),"Founding fixture"); var s=data.findByCore(core).orElseThrow();
        var barrel=core.east(3); level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);
        owner.moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5,0,0);
        visitor.moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5,0,0);
        var entitlement=new net.minecraft.nbt.CompoundTag(); entitlement.putBoolean("ExiledFoundingIssued",true);
        owner.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG,entitlement);
        visitor.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG,entitlement.copy());
        h.assertTrue(!FoundingService.redeem(visitor,barrel),"Visitor redeemed supplies inside another settlement");
        h.assertTrue(FoundingService.redeem(owner,barrel),"Owner could not redeem in their empty barrel");
        var registered=s.buildingAt(barrel);
        h.assertTrue(registered!=null&&registered.type()==BuildingType.WAGON&&registered.operational(),"Starter stock not registered");
        h.assertTrue(WarehouseInventoryService.countItem(level,registered,Items.BREAD)==24,"Finite startup food not present");
        h.assertTrue(!FoundingService.redeem(owner,barrel)&&!FoundingService.recover(owner),"Redeemed entitlement replayed/recovered supplies");
        s.deactivate(); data.touch(); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void diseaseRecoveryAndAnnualReportPersist(GameTestHelper h) {
        var s=record(); var level=h.getLevel(); var c=s.citizens().iterator().next();
        c.life().sickUntil=16; s.community().lastDay=15; s.community().lastProcessedYear=0;
        long original=level.getDayTime(); level.setDayTime(16L*24000L);
        CommunityLifeService.tick(level,new SettlementSavedData(),s); level.setDayTime(original);
        h.assertTrue(c.life().sickUntil==-1&&s.community().milestones.contains("recovery"),"Disease did not recover");
        h.assertTrue(s.community().reports.size()==1,"Calendar rollover did not produce annual report");
        var loaded=SettlementRecord.load(s.save(level.registryAccess()),level.registryAccess());
        h.assertTrue(loaded.citizen(c.id()).life().sickUntil==-1&&loaded.community().reports.size()==1,"Recovery/report lost on reload");
        h.succeed();
    }
    private static BuildingRecord barrel(GameTestHelper h, Fixture f, BlockPos position, BuildingType type) {
        h.getLevel().setBlock(position, Blocks.BARREL.defaultBlockState(),3);
        var b=f.settlement.addBuilding(type,position); b.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(position)); return b;
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=200)
    public static void barterNeedsSpaceAndCannotReplayDuringOneVisit(GameTestHelper h) {
        var level=h.getLevel(); var core=h.absolutePos(new BlockPos(120000,10,300));
        var s=new SettlementRecord(UUID.randomUUID(),"Barter fixture",core,UUID.randomUUID());
        var chunk=new ChunkPos(core); level.setChunkForced(chunk.x,chunk.z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-1,-1,-1),core.offset(1,-1,1))) level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        var storage=s.addBuilding(BuildingType.WAGON,core.east(3)); level.setBlock(storage.position(),Blocks.BARREL.defaultBlockState(),3);
        storage.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(storage.position()));
        var inventory=(Container)level.getBlockEntity(storage.position());
        inventory.setItem(0,new ItemStack(Items.OAK_LOG,32));
        for(int i=1;i<inventory.getContainerSize();i++) inventory.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        var trader=net.minecraft.world.entity.EntityType.WANDERING_TRADER.create(level);
        trader.moveTo(core.getX()+0.5,core.getY(),core.getZ()+0.5); trader.setNoAi(true);
        h.assertTrue(level.addFreshEntity(trader),"Merchant fixture spawn failed");
        s.merchantState().beginVisit(trader.getUUID(),CommunityLifeService.day(level),Map.of());
        h.startSequence().thenWaitUntil(()-> h.assertTrue(level.getEntity(trader.getUUID())!=null,"Merchant fixture chunk not ready")).thenExecute(()-> {
            CommunityManagement.barter(level,s,Items.OAK_LOG,16,Items.MELON_SEEDS,8);
            h.assertTrue(WarehouseInventoryService.countItem(level,storage,Items.OAK_LOG)==32 && s.community().barterClaims.isEmpty(),"Full stock took payment");
            inventory.setItem(1,ItemStack.EMPTY);
        }).thenIdle(5).thenExecute(()-> {
            String result=CommunityManagement.barter(level,s,Items.OAK_LOG,16,Items.MELON_SEEDS,8);
            h.assertTrue(WarehouseInventoryService.countItem(level,storage,Items.OAK_LOG)==16
                    && WarehouseInventoryService.countItem(level,storage,Items.MELON_SEEDS)==8,"Barter did not exchange real items: "+result);
        }).thenIdle(5).thenExecute(()-> {
            CommunityManagement.barter(level,s,Items.OAK_LOG,16,Items.MELON_SEEDS,8);
            h.assertTrue(WarehouseInventoryService.countItem(level,storage,Items.OAK_LOG)==16
                    && WarehouseInventoryService.countItem(level,storage,Items.MELON_SEEDS)==8,"Same quote replayed");
            trader.discard(); s.deactivate(); level.setChunkForced(chunk.x,chunk.z,false);
        }).thenSucceed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void winterGoalNeedsObservedWinterWithoutResidentLoss(GameTestHelper h) {
        var level=h.getLevel(); long original=level.getDayTime(); level.setDayTime(16*24000L);
        var skipped=record(); skipped.community().lastDay=15;
        CommunityLifeService.tick(level,new SettlementSavedData(),skipped);
        h.assertTrue(!skipped.community().milestones.contains("winter"),"Skipping winter earned survival goal");
        var lost=record(); lost.community().lastDay=15; lost.community().winterStarted=true; lost.community().winterLoss=true;
        var saved=SettlementRecord.load(lost.save(level.registryAccess()),level.registryAccess());
        CommunityLifeService.tick(level,new SettlementSavedData(),saved);
        h.assertTrue(!saved.community().milestones.contains("winter"),"Resident loss earned safe winter goal");
        var safe=record(); safe.community().lastDay=15; safe.community().winterStarted=true;
        CommunityLifeService.tick(level,new SettlementSavedData(),safe);
        h.assertTrue(safe.community().milestones.contains("winter"),"Observed safe winter did not earn goal");
        level.setDayTime(original); h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life")
    public static void harvestHelpersKeepJobsToolsAndWorkAcrossRefreshAndReload(GameTestHelper h) {
        var s = record(); var data = new SettlementSavedData();
        s.community().policy = "harvest"; s.community().lastDay = 0;
        var farm = s.addBuilding(BuildingType.FARM_FIELD, BlockPos.ZERO);
        farm.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(BlockPos.ZERO));
        farm.setWorkerTarget(0, farm.configurationRevision());
        var locked = s.citizens().iterator().next(); locked.setManualAssignment(CitizenProfession.WORKER, null);
        ProfessionAssignmentService.refresh(data, s);
        var helpers = s.citizens().stream().filter(c -> c.profession() == CitizenProfession.FARMER).toList();
        h.assertTrue(helpers.size() == 2 && locked.profession() == CitizenProfession.WORKER,
                "Harvest must borrow exactly two available workers and respect manual locks");
        for (var c : helpers) {
            ItemStack hoe = new ItemStack(Items.STONE_HOE); hoe.setDamageValue(7);
            c.insertIntoInventory(hoe); c.startTask(CitizenWorkState.TO_FARM, BlockPos.ZERO, null, null);
        }
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        for (int i = 0; i < 30; i++) {
            ProfessionAssignmentService.refresh(data, loaded);
            for (var original : helpers) {
                var c = loaded.citizen(original.id());
                h.assertTrue(c.profession() == CitizenProfession.FARMER && c.workState() == CitizenWorkState.TO_FARM
                        && c.inventoryItem(0).getDamageValue() == 7 && CitizenEquipmentService.isReservedEquipment(c, 0),
                        "Regular allocation reset a seasonal farmer, cancelled work or surrendered the hoe");
            }
            h.assertTrue(loaded.professionActual(CitizenProfession.FARMER) == 2, "Harvest helpers grew on every refresh");
        }
        loaded.community().policy = "normal"; ProfessionAssignmentService.refresh(data, loaded);
        h.assertTrue(loaded.professionActual(CitizenProfession.FARMER) == 0, "Ending harvest retained temporary jobs");
        h.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=1200)
    public static void staleToolDeliveryKeepsHoeSeedsAndRationsThenPlants(GameTestHelper h) {
        withPhysical(h, 6, f -> {
            var store = barrel(h, f, f.core.east(8), BuildingType.WAREHOUSE);
            var template = com.hkin.exiledsettlement.blueprint.BlueprintTemplates.createArea(BuildingType.FARM_FIELD,
                    f.core.offset(-10, 0, -4), f.core.offset(-3, 0, 4), net.minecraft.core.Direction.EAST);
            var farm = f.settlement.addBuilding(template.type(), template.registrationPosition(), template.facing(),
                    template.minCorner(), template.maxCorner(), template.transitionMargin(),
                    com.hkin.exiledsettlement.blueprint.BlueprintTemplates.currentTemplateVersion(template.type()));
            var ground = com.hkin.exiledsettlement.blueprint.BlueprintTemplates.createForBuilding(farm).placements().stream()
                    .filter(p -> p.state().is(Blocks.FARMLAND)).map(p -> p.position())
                    .min(Comparator.comparingDouble(p -> p.distSqr(f.core))).orElse(null);
            h.assertTrue(ground != null, "Formal area template must contain planting ground");
            h.getLevel().setBlock(ground, Blocks.DIRT.defaultBlockState(), 3); // The retained hoe must actually till before planting.
            h.getLevel().setBlock(ground.north(), Blocks.WATER.defaultBlockState(), 3);
            farm.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(ground));
            f.citizen.assignProfession(CitizenProfession.FARMER, farm.id());
            ItemStack hoe = new ItemStack(Items.STONE_HOE); hoe.setDamageValue(7);
            hoe.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Personal hoe"));
            f.citizen.insertIntoInventory(hoe);
            f.citizen.insertIntoInventory(new ItemStack(Items.IRON_HOE));
            f.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 22));
            f.citizen.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS, 64));
            f.citizen.startInventoryDelivery(store.id(), 0); // A saved/redirected task points at the working hoe.
            long start = h.getLevel().getGameTime(); boolean[] delivered = {false}; String[] failure = {""};
            h.startSequence().thenWaitUntil(() -> {
                h.getLevel().setDayTime((WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 6 ? 6000L : 23000L)
                        + CitizenWorkService.scheduleOffset(f.citizen));
                if ((h.getLevel().getGameTime() - start) % 8 == 0) {
                    f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
                    CitizenWorkService.tickSettlement(h.getLevel(), f.data, f.settlement);
                }
                if (WarehouseInventoryService.countItem(h.getLevel(), store, Items.STONE_HOE) != 0
                        || !f.citizen.hasInventoryItem(s -> s.is(Items.STONE_HOE))) failure[0] = "Personal hoe left the farmer";
                delivered[0] |= WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 6;
                h.assertTrue(failure[0].isEmpty(), failure[0]);
                h.assertTrue(delivered[0] && h.getLevel().getBlockState(ground.above()).is(Blocks.WHEAT),
                        "Farmer did not deliver surplus and resume actual planting: " + f.citizen.activity() + "/" + f.citizen.workFailure()
                                + " position=" + f.entity.blockPosition() + " ground=" + ground + " storedBread="
                                + WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) + " inventory=" + f.citizen.inventory());
                h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(), store, Items.IRON_HOE) == 1
                        && CommunityLifeService.carriedFood(f.citizen) == 16
                        && f.citizen.inventory().stream().filter(s -> s.is(Items.WHEAT_SEEDS)).mapToInt(ItemStack::getCount).sum() == 63,
                        "Delivery lost/duplicated spare tools, personal food or planting seeds");
                var retained = f.citizen.inventoryItem(f.citizen.findInventorySlot(s -> s.is(Items.STONE_HOE)));
                h.assertTrue(retained.getDamageValue() > 7
                        && retained.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString().equals("Personal hoe"),
                        "Planting replaced the real named/damaged hoe instead of using it");
            }).thenExecute(() -> cleanup(h, f)).thenSucceed();
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=900)
    public static void healthyResidentCollectsRationsAndEatsThemWithoutAnotherTrip(GameTestHelper h) {
        withPhysical(h, 7, f -> {
            var store = barrel(h, f, f.core.east(8), BuildingType.WAGON);
            ((Container)h.getLevel().getBlockEntity(store.position())).setItem(0, new ItemStack(Items.BREAD, 32));
            long start = h.getLevel().getGameTime();
            h.startSequence().thenWaitUntil(() -> {
                h.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(f.citizen));
                if ((h.getLevel().getGameTime() - start) % 8 == 0) {
                    f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
                    CitizenWorkService.tickSettlement(h.getLevel(), f.data, f.settlement);
                }
                h.assertTrue(CommunityLifeService.carriedFood(f.citizen) == 16, "Healthy resident did not fetch sixteen rest-time rations");
                h.assertTrue(f.entity.distanceToSqr(f.core.getX()+.5, f.core.getY(), f.core.getZ()+.5) > 16,
                        "Rations bypassed the real approach to public inventory");
            }).thenExecute(() -> f.citizen.adjustNeeds(-60, 0, 0)).thenWaitUntil(() -> {
                CommunityLifeService.personal(h.getLevel(), f.data, f.settlement, f.citizen, f.entity);
                h.assertTrue(f.citizen.nutrition() == 80 && CommunityLifeService.carriedFood(f.citizen) == 15
                        && WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 16
                        && f.settlement.community().mealsEaten == 1, "Eating did not consume exactly one carried ration");
            }).thenExecute(() -> {
                var loaded = CitizenRecord.load(f.citizen.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
                h.assertTrue(CommunityLifeService.carriedFood(loaded) == 15, "Rations did not survive save/reload");
                cleanup(h, f);
            }).thenSucceed();
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=200)
    public static void optionalRationsDoNotBlockWorkWhenStockIsEmptyOrBackpackFull(GameTestHelper h) {
        withPhysical(h, 8, f -> {
            h.assertTrue(!CommunityLifeService.personal(h.getLevel(), f.data, f.settlement, f.citizen, f.entity),
                    "Optional rations blocked work without food");
            var store = barrel(h, f, f.core.east(8), BuildingType.WAGON);
            ((Container)h.getLevel().getBlockEntity(store.position())).setItem(0, new ItemStack(Items.BREAD, 12));
            for (int i = 0; i < CitizenRecord.INVENTORY_SIZE; i++) f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 64));
            h.assertTrue(!CommunityLifeService.personal(h.getLevel(), f.data, f.settlement, f.citizen, f.entity)
                    && WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 12,
                    "Full backpack optional refill blocked work or lost food");
            cleanup(h, f); h.succeed();
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="community_life", timeoutTicks=800)
    public static void namedFoodIsNotLostWhenOnlyPlainFoodStackHasRoom(GameTestHelper h) {
        withPhysical(h, 9, f -> {
            var store = barrel(h, f, f.core.east(8), BuildingType.WAGON);
            var named = new ItemStack(Items.BREAD, 12);
            named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Named bread"));
            ((Container)h.getLevel().getBlockEntity(store.position())).setItem(0, named);
            f.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 3));
            for (int i = 1; i < CitizenRecord.INVENTORY_SIZE; i++) f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 64));
            h.startSequence().thenWaitUntil(() -> h.assertTrue(
                    CommunityLifeService.atStorage(h.getLevel(), f.citizen, f.entity, store, Items.BREAD, CitizenActivity.FETCHING_MEAL),
                    "Waiting for actual food station")).thenExecute(() -> {
                CommunityLifeService.topUpRations(h.getLevel(), f.settlement, f.citizen, f.entity, store);
                h.assertTrue(CommunityLifeService.carriedFood(f.citizen) == 3
                        && WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 12
                        && ItemStack.matches(((Container)h.getLevel().getBlockEntity(store.position())).getItem(0), named),
                        "Prototype capacity allowed custom food to leave storage and disappear");
                cleanup(h, f);
            }).thenSucceed();
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_life")
    public static void regenerationChargesActualHealthAndPersistsFractionalCost(GameTestHelper h) {
        withPhysical(h, 10, f -> {
            f.entity.setHealth(18);
            int nutrition = f.citizen.nutrition();
            CommunityLifeService.recoverLife(h.getLevel(), f.data, f.citizen, f.entity);
            h.assertTrue(f.entity.getHealth() == 19 && f.citizen.nutrition() == nutrition - 2, "Recovery did not pay for actual life");
            f.entity.setHealth(19.75F);
            CommunityLifeService.recoverLife(h.getLevel(), f.data, f.citizen, f.entity);
            h.assertTrue(f.entity.getHealth() == 20 && f.citizen.life().recoveryNutritionRemainder == 0.5D,
                    "Fractional recovery was overcharged or free");
            var loaded = CitizenRecord.load(f.citizen.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(loaded.life().recoveryNutritionRemainder == 0.5D, "Reload lost recovery charge remainder");
            int full = f.citizen.nutrition();
            CommunityLifeService.recoverLife(h.getLevel(), f.data, f.citizen, f.entity);
            h.assertTrue(full == f.citizen.nutrition(), "Full health consumed food");
            f.entity.setHealth(18);
            f.citizen.life().lastDamageGameTime = h.getLevel().getGameTime();
            CommunityLifeService.recoverLife(h.getLevel(), f.data, f.citizen, f.entity);
            h.assertTrue(f.entity.getHealth() == 18, "Recent damage allowed recovery");
            f.citizen.life().lastDamageGameTime = -1;
            f.citizen.adjustNeeds(-100, 0, 0);
            CommunityLifeService.recoverLife(h.getLevel(), f.data, f.citizen, f.entity);
            h.assertTrue(f.entity.getHealth() == 18, "Hungry resident regenerated");
            cleanup(h, f); h.succeed();
        });
    }

    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="resident_life",timeoutTicks=400)
    public static void dirtPathWarehouseInteractionRetainsSevenToolsAndSixteenRations(GameTestHelper h) {
        withPhysical(h, 11, f -> {
            var store = barrel(h, f, f.core.east(2), BuildingType.WAGON);
            Container stock = (Container) h.getLevel().getBlockEntity(store.position());
            var tools = java.util.List.of(Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_SHOVEL, Items.STONE_HOE,
                    Items.SHEARS, Items.FISHING_ROD, Items.LEAD);
            for (int i = 0; i < tools.size(); i++) stock.setItem(i, new ItemStack(tools.get(i), 1));
            stock.setItem(7, new ItemStack(Items.BREAD, 32));
            h.getLevel().setBlock(f.core.below(), Blocks.DIRT_PATH.defaultBlockState(), 2);
            f.entity.moveTo(f.core.getX()+.5, f.core.getY()-.0625, f.core.getZ()+.5, 0, 0);
            h.startSequence().thenWaitUntil(() -> h.assertTrue(WarehouseInventoryService.canInteract(h.getLevel(), store, f.entity), "Waiting for loaded warehouse"))
                    .thenExecute(() -> {
                        BlockPos target = WarehouseInventoryService.reserveExtractionPosition(h.getLevel(), store, f.entity, Items.BREAD);
                        h.assertTrue(target != null && target.equals(f.entity.navigationFeetPosition())
                                && f.entity.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(target)) < .81,
                                "Interaction returned the dirt path floor instead of usable feet position");
                        CommunityLifeService.topUpRations(h.getLevel(), f.settlement, f.citizen, f.entity, store);
                        h.assertTrue(CommunityLifeService.carriedFood(f.citizen) == 16 && WarehouseInventoryService.countItem(h.getLevel(), store, Items.BREAD) == 16,
                                "Sixteen rations did not transfer physically from stock");
                        for (var item : tools) {
                            int slot = f.citizen.findInventorySlot(stack -> stack.is(item));
                            h.assertTrue(slot >= 0 && com.hkin.exiledsettlement.citizen.CitizenEquipmentService.deliverableCount(f.citizen, slot) == 0,
                                    "Personal tool was absent or delivered again: " + item);
                        }
                        f.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
                        h.assertTrue(java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE)
                                .map(i -> com.hkin.exiledsettlement.citizen.CitizenEquipmentService.deliverableCount(f.citizen,i)).sum() == 1,
                                "Duplicate tool must remain deliverable");
                        cleanup(h,f);
                    }).thenSucceed();
        });
    }

    private static void cleanup(GameTestHelper h, Fixture f) {
        f.entity.discard(); f.settlement.deactivate();
        for(int x=f.min.x;x<=f.max.x;x++) for(int z=f.min.z;z<=f.max.z;z++) h.getLevel().setChunkForced(x,z,false);
    }
}

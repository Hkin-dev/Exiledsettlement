package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.companion.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

/** Finite-material companion rules and real entity work in isolated test worlds. */
@GameTestHolder("exiledsettlement_companions")
@PrefixGameTestTemplate(false)
public final class CompanionGameTests {
    private static int serial;
    private static final String TEMPLATE = "exiledsettlement_companions";

    private static void passed(String name) {
        ExiledSettlementMod.LOGGER.info("COMPANION_PASS {}", name);
    }

    private static final class Fixture {
        final GameTestHelper h;
        final ServerLevel level;
        final SettlementSavedData data;
        final BlockPos at;
        final SettlementRecord settlement;
        final CitizenRecord citizen;
        final SettlementCitizenEntity resident;
        final List<Entity> animals = new ArrayList<>();
        final List<Long> chunks = new ArrayList<>();
        final List<Runnable> cleanupActions = new ArrayList<>();
        boolean done;
        Vec3 previous;
        double moved;
        double largestStep;

        Fixture(GameTestHelper helper, boolean registered) {
            h = helper; level = h.getLevel(); data = SettlementSavedData.get(level);
            at = new BlockPos(620000 + serial++ * 256, 100, 620000);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                int cx = (at.getX() >> 4) + x, cz = (at.getZ() >> 4) + z;
                level.setChunkForced(cx, cz, true);
                chunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
            }
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-24, -1, -24), at.offset(31, -1, 31)))
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            settlement = registered
                    ? data.createOrRecover(at, UUID.randomUUID(), "Companion isolated " + serial).value()
                    : new SettlementRecord(UUID.randomUUID(), "Companion service " + serial, at, UUID.randomUUID());
            settlement.terrain().enabled = false;
            settlement.autoRoads().enabled = false;
            settlement.decorations().enabled = false;
            settlement.guardians().enabled = false;
            settlement.pets().enabled = false;
            settlement.deactivate();
            citizen = settlement.citizens().iterator().next();
            for (CitizenRecord other : settlement.citizens())
                if (other != citizen) other.markDead(at);
            citizen.assignProfession(CitizenProfession.WORKER, null);
            citizen.insertIntoInventory(new ItemStack(Items.BREAD, CommunityContent.carriedFoodTarget));
            resident = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            Objects.requireNonNull(resident);
            place(resident, at.offset(-16, 0, 0));
            resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
            resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, citizen.id().toString());
            citizen.updateProjection(resident.getUUID(), resident.blockPosition());
            h.assertTrue(level.addFreshEntity(resident), "Could not add real resident");
            previous = resident.position();
            level.setDayTime(6000);
        }

        void observe() {
            double step = previous.distanceTo(resident.position());
            moved += step; largestStep = Math.max(largestStep, step); previous = resident.position();
            citizen.updateProjection(resident.getUUID(), resident.blockPosition());
        }

        BuildingRecord warehouse(BlockPos position) {
            level.setBlock(position, Blocks.BARREL.defaultBlockState(), 2);
            var building = settlement.addBuilding(BuildingType.WAGON, position, Direction.NORTH,
                    position, position, 0, 2);
            building.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.CAPACITY, List.of(position));
            return building;
        }

        BuildingRecord home(BlockPos position) {
            level.setBlock(position, Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(position.north(), Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 2);
            BlockPos doorway = position.south(2);
            level.setBlock(doorway, Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
            level.setBlock(doorway.above(), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            var building = settlement.addBuilding(BuildingType.HOUSE, position, Direction.NORTH,
                    position.offset(-2, 0, -3), position.offset(2, 2, 2), 0, 1);
            building.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(position.north()));
            citizen.assignHome(building.id(), position.north());
            return building;
        }

        BuildingRecord workshop(BlockPos position) {
            level.setBlock(position, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            var building = settlement.addBuilding(BuildingType.TOOL_WORKSHOP, position, Direction.NORTH,
                    position, position, 0, 1);
            building.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(position));
            citizen.assignProfession(CitizenProfession.TOOLSMITH, building.id());
            return building;
        }

        <T extends Entity> T add(T entity, BlockPos position) {
            Objects.requireNonNull(entity); place(entity, position);
            if (entity instanceof Mob mob) mob.setPersistenceRequired();
            h.assertTrue(level.addFreshEntity(entity), "Could not add fixture animal");
            animals.add(entity); return entity;
        }

        void clean() {
            if (done) return;
            done = true;
            cleanupActions.forEach(Runnable::run);
            resident.discard();
            for (Entity entity : animals) entity.discard();
            settlement.deactivate();
            for (long key : chunks) level.setChunkForced(
                    net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key), false);
            data.touch();
        }
    }

    private static void place(Entity entity, BlockPos at) {
        entity.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        entity.setOnGround(true);
    }

    private static int bag(CitizenRecord citizen, Item item) {
        return citizen.inventory().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int total(Fixture f, BuildingRecord warehouse, Item item) {
        return bag(f.citizen, item) + WarehouseInventoryService.countItem(f.level, warehouse, item);
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void legacyPolicyDefaultsOffAndNewPolicyPersists(GameTestHelper h) {
        var s = new SettlementRecord(UUID.randomUUID(), "Policy fixture", BlockPos.ZERO, UUID.randomUUID());
        h.assertTrue(s.guardians().enabled && s.pets().enabled, "New settlements should permit automatic attempts");
        var saved = s.save(h.getLevel().registryAccess());
        saved.remove("Guardians"); saved.remove("Pets");
        var legacy = SettlementRecord.load(saved, h.getLevel().registryAccess());
        h.assertTrue(!legacy.guardians().enabled && !legacy.pets().enabled,
                "Legacy settlement silently enabled new resource consumption");
        s.guardians().target = 3; s.guardians().ironReserve = 27;
        s.pets().catLimit = 1; s.pets().dogLimit = 4;
        var loaded = SettlementRecord.load(s.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.guardians().target == 3 && loaded.guardians().ironReserve == 27
                && loaded.pets().catLimit == 1 && loaded.pets().dogLimit == 4,
                "Policy changed through settlement save/load");
        var values = CommunitySettings.values(s, BlockPos.ZERO);
        h.assertTrue(values.size() <= 32 && values.get("guardian_auto").equals("on")
                && values.get("guardian_target").equals("3") && values.get("guardian_reserve").equals("27")
                && values.get("pets_auto").equals("on") && values.get("pets_cats").equals("1")
                && values.get("pets_dogs").equals("4"), "New policy values exceed the packet limit or disagree");
        var before = s.save(h.getLevel().registryAccess());
        h.assertTrue(!CommunitySettings.apply(s, "guardian_target", "-1")
                && !CommunitySettings.apply(s, "pets_dogs", "999")
                && !CommunitySettings.apply(s, "guardian_auto", "broken")
                && before.equals(s.save(h.getLevel().registryAccess())), "Illegal policy values mutated saved state");
        h.assertTrue(CommunitySettings.apply(s, "guardian_target", "2")
                && CommunitySettings.apply(s, "pets_dogs", "2")
                && s.guardians().target == 2 && s.pets().dogLimit == 2, "Valid policy changes were rejected");
        passed("legacy_and_policy"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void guardianQuotaRetainsUnloadedAndUncertainTransactions(GameTestHelper h) {
        var state = new GuardianState(); state.target = 3;
        var live = state.createOrder(UUID.randomUUID(), BlockPos.ZERO); live.state = "alive"; live.charged = true;
        var pending = state.createOrder(UUID.randomUUID(), BlockPos.ZERO.east()); pending.progress = 511.25;
        var interrupted = state.createOrder(UUID.randomUUID(), BlockPos.ZERO.west()); interrupted.state = "spawning";
        h.assertTrue(state.createOrder(UUID.randomUUID(), BlockPos.ZERO.north()) == null, "Concurrent orders overrun quota");
        var restored = new GuardianState(); restored.load(state.save());
        h.assertTrue(restored.count() == 3 && restored.aliveCount() == 1
                && restored.entry(pending.id).progress == 511.25
                && restored.entry(interrupted.id).state.equals("uncertain")
                && restored.entry(live.id).entityId.equals(live.entityId),
                "Unloaded ownership or interrupted transaction released its slot");
        h.assertTrue(restored.createOrder(UUID.randomUUID(), BlockPos.ZERO) == null, "Reload created duplicate quota");
        restored.entry(live.id).state = "dead";
        h.assertTrue(restored.createOrder(UUID.randomUUID(), BlockPos.ZERO) != null, "Confirmed death did not release quota");
        passed("guardian_quota_save"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void petReservationsMealsAndDayHistoryPersist(GameTestHelper h) {
        var state = new PetState();
        var cat = new PetState.Pet(UUID.randomUUID(), UUID.randomUUID(), true,
                UUID.randomUUID(), UUID.randomUUID(), new BlockPos(1, 2, 3));
        cat.attemptDay = 91; cat.attempts = 3; cat.consumedLures = 3;
        state.entries.put(cat.id, cat);
        var dog = new PetState.Pet(UUID.randomUUID(), UUID.randomUUID(), false,
                UUID.randomUUID(), UUID.randomUUID(), new BlockPos(4, 5, 6));
        dog.status = PetState.Status.ACTIVE; dog.lastFedDay = 91; dog.consumedMeals = 7;
        state.entries.put(dog.id, dog);
        var restored = new PetState(); restored.load(state.save());
        h.assertTrue(restored.count(true) == 1 && restored.count(false) == 1,
                "Unloaded or adopting pets lost their quota");
        var savedCat = restored.entries.get(cat.id); var savedDog = restored.entries.get(dog.id);
        h.assertTrue(savedCat.entityId.equals(cat.entityId) && savedCat.attemptDay == 91
                && savedCat.attempts == 3 && savedCat.consumedLures == 3
                && savedDog.lastFedDay == 91 && savedDog.consumedMeals == 7
                && savedDog.caretaker.equals(dog.caretaker) && savedDog.home.equals(dog.home),
                "Pet identity, attempt limits, meal history or family were reset");
        savedCat.status = PetState.Status.RELEASED; savedDog.status = PetState.Status.DEAD;
        h.assertTrue(restored.count(true) == 0 && restored.count(false) == 0, "Terminal pet records retain live quota");
        passed("pet_quota_save"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void guardianTargetsOnlySafeHostiles(GameTestHelper h) {
        var f = new Fixture(h, false);
        var zombie = f.add(EntityType.ZOMBIE.create(f.level), f.at.east(4));
        var creeper = f.add(EntityType.CREEPER.create(f.level), f.at.west(4));
        var cat = f.add(EntityType.CAT.create(f.level), f.at.north(4));
        var cow = f.add(EntityType.COW.create(f.level), f.at.south(4));
        h.assertTrue(GuardianAi.hostile(f.settlement, zombie), "Clear hostile was ignored");
        h.assertTrue(!GuardianAi.hostile(f.settlement, creeper)
                && !GuardianAi.hostile(f.settlement, cat)
                && !GuardianAi.hostile(f.settlement, cow)
                && !GuardianAi.hostile(f.settlement, f.resident),
                "Guardian targets explosive or friendly entities");
        zombie.setTarget(f.resident);
        h.assertTrue(GuardianAi.threateningResident(f.level, f.settlement, zombie),
                "A monster attacking a resident was not identified");
        place(zombie, f.at.east(96));
        h.assertTrue(!GuardianAi.hostile(f.settlement, zombie), "Guard target escaped settlement boundary");
        f.clean(); passed("guard_target_policy"); h.succeed();
    }

    private static void smith(Fixture f) {
        f.workshop(f.at.offset(0, 0, 15));
        f.citizen.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
        f.settlement.guardians().enabled = true; f.settlement.activate();
    }

    private static IronGolem registeredGuard(Fixture f, BlockPos at) {
        var entry = f.settlement.guardians().createOrder(f.citizen.id(), at);
        f.h.assertTrue(entry != null, "No guardian quota for fixture");
        var golem = Objects.requireNonNull(EntityType.IRON_GOLEM.create(f.level));
        golem.setUUID(entry.entityId); entry.state = "alive"; entry.charged = true;
        golem.getPersistentData().putUUID(GuardianService.GUARDIAN_ID, entry.id);
        golem.getPersistentData().putUUID(GuardianService.SETTLEMENT_ID, f.settlement.id());
        f.add(golem, at); GuardianAi.attach(f.level, f.settlement, entry, golem);
        return golem;
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void guardianReserveAndRemoteSupplyPreserveRealStock(GameTestHelper h) {
        var f = new Fixture(h, false); smith(f);
        var warehouse = f.warehouse(f.at.east(12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_BLOCK, 4));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.CARVED_PUMPKIN));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_INGOT, 17));
        h.assertTrue(!GuardianService.reserveAllows(f.level, f.settlement, null, 36),
                "Manufacture consumed the independent tool-iron reserve");
        GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
        h.assertTrue(f.settlement.guardians().count() == 0 && total(f, warehouse, Items.IRON_BLOCK) == 4
                && total(f, warehouse, Items.IRON_INGOT) == 17 && total(f, warehouse, Items.CARVED_PUMPKIN) == 1,
                "Missing reserve consumed stock or created an unfunded order");
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_INGOT));
        h.assertTrue(GuardianService.reserveAllows(f.level, f.settlement, null, 36), "Exact reserve was rejected");
        f.settlement.guardians().nextOrderCheck = 0;
        GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
        h.assertTrue(bag(f.citizen, Items.IRON_BLOCK) == 0 && bag(f.citizen, Items.CARVED_PUMPKIN) == 0,
                "Remote resident took warehouse materials");
        f.clean(); passed("guardian_reserve_remote"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void rejectedGuardianSpawnRefundsAllRealMaterials(GameTestHelper h) {
        var f = new Fixture(h, false); smith(f);
        var warehouse = f.warehouse(f.at.east(12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_INGOT, 18));
        f.citizen.insertIntoInventory(new ItemStack(Items.IRON_BLOCK, 4));
        f.citizen.insertIntoInventory(new ItemStack(Items.CARVED_PUMPKIN));
        var order = f.settlement.guardians().createOrder(f.citizen.id(), f.at);
        order.progress = GuardianService.ASSEMBLY_TICKS;
        place(f.resident, f.at.west(3));
        order.worker = UUID.randomUUID(); // A different smith takes over after the former worker is gone.
        f.settlement.guardians().target = 0;
        GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
        h.assertTrue(order.assembling() && !order.charged && order.blocker.contains("目标数量")
                && bag(f.citizen, Items.IRON_BLOCK) == 4 && bag(f.citizen, Items.CARVED_PUMPKIN) == 1,
                "Reassigned assembly ignored the reduced target or consumed supplies");
        f.settlement.guardians().target = 1;
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> cancel = event -> {
            if (event.getEntity() instanceof IronGolem && event.getEntity().getUUID().equals(order.entityId))
                event.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
        try { GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident); }
        finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(order.assembling() && !order.charged && f.level.getEntity(order.entityId) == null
                && bag(f.citizen, Items.IRON_BLOCK) == 4 && bag(f.citizen, Items.CARVED_PUMPKIN) == 1
                && total(f, warehouse, Items.IRON_INGOT) == 18, "Rejected entity lost material or committed a ghost");
        h.assertTrue(order.blocker.contains("生成被拒"), "The spawn refusal was not reached or not explained");
        f.clean(); passed("guardian_spawn_refund"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 2400)
    public static void smithPhysicallyFetchesAndBuildsExactlyOneGuardian(GameTestHelper h) {
        var f = new Fixture(h, false); smith(f);
        var warehouse = f.warehouse(f.at.east(12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_BLOCK, 4));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.CARVED_PUMPKIN));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_INGOT, 18));
        final int[] ticks = {0}; final boolean[] fetched = {false};
        h.onEachTick(() -> {
            if (f.done) return;
            ticks[0]++; f.observe();
            if (ticks[0] % 5 == 0) GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
            fetched[0] |= bag(f.citizen, Items.IRON_BLOCK) == 4 && bag(f.citizen, Items.CARVED_PUMPKIN) == 1;
            if (f.settlement.guardians().aliveCount() == 0) {
                h.assertTrue(total(f, warehouse, Items.IRON_BLOCK) == 4
                        && total(f, warehouse, Items.CARVED_PUMPKIN) == 1,
                        "Materials vanished before actual entity admission");
            }
            h.assertTrue(total(f, warehouse, Items.IRON_INGOT) == 18, "Independent reserve was spent");
            h.assertTrue(f.largestStep < 2, "Worker teleported during assembly chain");
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(f.settlement.guardians().aliveCount() == 1,
                "Assembly pending: " + GuardianService.report(f.settlement) + " at=" + f.resident.blockPosition()
                        + " activity=" + f.citizen.workFailure()))
                .thenExecute(() -> {
                    var entry = f.settlement.guardians().records().iterator().next();
                    h.assertTrue(f.level.getEntity(entry.entityId) instanceof IronGolem, "No real constructed entity");
                    f.animals.add(f.level.getEntity(entry.entityId));
                    h.assertTrue(fetched[0] && f.moved > 20 && entry.charged
                            && total(f, warehouse, Items.IRON_BLOCK) == 0
                            && total(f, warehouse, Items.CARVED_PUMPKIN) == 0,
                            "Construction did not pass through real pickup and movement");
                }).thenIdle(101).thenExecute(() -> {
                    h.assertTrue(f.settlement.guardians().count() == 1 && f.settlement.guardians().aliveCount() == 1,
                            "Completed order replayed into another guardian");
                    ExiledSettlementMod.LOGGER.info("COMPANION_BUILD ticks={} moved={} maxStep={} ironBlocks=4 pumpkin=1 reserve=18",
                            ticks[0], f.moved, f.largestStep);
                    f.clean(); passed("guardian_live_build");
                }).thenSucceed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 1800)
    public static void smithWalksToRepairAndSpendsOneIronIngot(GameTestHelper h) {
        var f = new Fixture(h, false); smith(f);
        var warehouse = f.warehouse(f.at.east(5));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.IRON_INGOT, 19));
        var golem = registeredGuard(f, f.at.east(20)); golem.setHealth(75);
        f.settlement.guardians().enabled = false;
        final int[] ticks = {0}; final boolean[] sawCarried = {false};
        h.onEachTick(() -> {
            if (f.done) return;
            ticks[0]++; f.observe();
            if (ticks[0] % 5 == 0) GuardianService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
            sawCarried[0] |= bag(f.citizen, Items.IRON_INGOT) == 1;
            if (golem.getHealth() <= 75)
                h.assertTrue(total(f, warehouse, Items.IRON_INGOT) == 19, "Repair fee lost before near healing");
            else h.assertTrue(total(f, warehouse, Items.IRON_INGOT) == 18, "Healing did not spend exactly one ingot");
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(golem.getHealth() == 100,
                "Repair pending " + f.citizen.workFailure() + " moved=" + f.moved))
                .thenExecute(() -> {
                    h.assertTrue(sawCarried[0] && f.moved > 20 && f.largestStep < 2,
                            "Repair skipped real supply pickup/walking");
                    ExiledSettlementMod.LOGGER.info("COMPANION_REPAIR ticks={} moved={} health=75->100 fee=1 reserve=18",
                            ticks[0], f.moved);
                    f.clean(); passed("guardian_live_repair");
                }).thenSucceed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 900)
    public static void guardianWalksToRescueThenReleasesDistantThreat(GameTestHelper h) {
        var f = new Fixture(h, false); f.settlement.activate();
        var golem = registeredGuard(f, f.at);
        place(f.resident, f.at.east(12));
        var zombie = f.add(EntityType.ZOMBIE.create(f.level), f.at.east(9));
        zombie.setNoAi(true); zombie.setTarget(f.resident);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        zombie.setHealth(100); zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        final Vec3[] previous = {golem.position()}; final double[] moved = {0};
        final boolean[] struck = {false};
        h.onEachTick(() -> {
            if (f.done) return;
            double step = previous[0].distanceTo(golem.position());
            h.assertTrue(step < 2, "Guardian teleported");
            moved[0] += step; previous[0] = golem.position();
            struck[0] |= zombie.getHealth() < 100;
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(struck[0] && moved[0] > 3,
                "Guardian has not physically rescued resident: " + GuardianAi.activity(golem)))
                .thenExecute(() -> place(zombie, f.at.east(96)))
                .thenIdle(101).thenExecute(() -> {
                    h.assertTrue(golem.getTarget() == null && f.settlement.contains(golem.blockPosition()),
                            "Guardian pursued the remote enemy beyond its settlement");
                    ExiledSettlementMod.LOGGER.info("COMPANION_DEFENSE moved={} enemyHealth={} activity={}",
                            moved[0], zombie.getHealth(), GuardianAi.activity(golem));
                    f.clean(); passed("guardian_live_defense");
                }).thenSucceed();
    }

    private static PetState.Pet bindPet(Fixture f, Animal animal, BuildingRecord home, PetState.Status status) {
        var pet = new PetState.Pet(UUID.randomUUID(), animal.getUUID(), animal instanceof Cat,
                f.citizen.id(), home.id(), animal.blockPosition());
        pet.status = status;
        f.settlement.pets().entries.put(pet.id, pet);
        animal.getPersistentData().putUUID(PetCareService.PET_ID, pet.id);
        animal.getPersistentData().putUUID(PetCareService.SETTLEMENT_ID, f.settlement.id());
        if (status == PetState.Status.ACTIVE && animal instanceof TamableAnimal tame) tame.setTame(true, false);
        PetAi.attach(f.level, f.settlement, pet, animal);
        return pet;
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 120)
    public static void playerClaimsCancelAdoptionWithoutFoodOrAnimalLoss(GameTestHelper h) {
        var f = new Fixture(h, true); var home = f.home(f.at.north(10));
        f.settlement.pets().enabled = true;
        f.citizen.insertIntoInventory(new ItemStack(Items.COD, 6));
        var cats = new ArrayList<Cat>(); var pets = new ArrayList<PetState.Pet>();
        for (int variant = 0; variant < 4; variant++) {
            var cat = f.add(EntityType.CAT.create(f.level), f.at.east(4 + variant * 4));
            cats.add(cat); pets.add(bindPet(f, cat, home, PetState.Status.ADOPTING));
        }
        h.startSequence().thenIdle(2).thenWaitUntil(() ->
                        h.assertTrue(cats.stream().allMatch(cat -> f.level.getEntity(cat.getUUID()) == cat),
                                "Fixture animals have not joined the loaded entity table"))
                .thenExecute(() -> {
            f.settlement.activate();
            try {
                for (int variant = 0; variant < cats.size(); variant++) {
                    var cat = cats.get(variant); var pet = pets.get(variant);
                    h.assertTrue(PetCareService.isProtected(cat), "Reservation is not protected from hunting");
                    h.assertTrue(PetCareService.work(f.level, f.data, f.settlement, f.citizen, f.resident)
                                    && f.resident.currentNavigationDestination() != null,
                            "Claim fixture did not start a real distant adoption walk");
                    switch (variant) {
                        case 0 -> { cat.setTame(true, false); cat.setOwnerUUID(UUID.randomUUID()); }
                        case 1 -> cat.setCustomName(net.minecraft.network.chat.Component.literal("Player named"));
                        case 2 -> cat.setLeashedTo(f.resident, false);
                        case 3 -> cat.getPersistentData().putUUID("exiledsettlement.pastureBuilding", UUID.randomUUID());
                        default -> throw new IllegalStateException();
                    }
                    PetCareService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
                    h.assertTrue(pet.status == PetState.Status.RELEASED && cat.isAlive()
                            && !PetCareService.isProtected(cat) && !PetAi.controls(cat)
                            && pet.consumedLures == 0 && bag(f.citizen, Items.COD) == 6
                            && f.resident.currentNavigationDestination() == null && f.resident.getNavigation().isDone(),
                            "Player claim variant " + variant + " did not cancel cleanly; status=" + pet.status
                                    + " alive=" + cat.isAlive() + " protected=" + PetCareService.isProtected(cat)
                                    + " controlled=" + PetAi.controls(cat) + " lures=" + pet.consumedLures
                                    + " cod=" + bag(f.citizen, Items.COD) + " onGround=" + f.resident.onGround()
                                    + " work=" + f.citizen.workState() + " life=" + f.citizen.life().task
                                    + " schedule=" + CitizenWorkService.schedulePhase(f.level.getDayTime(), f.citizen)
                                    + " day=" + f.level.getDayTime() + " now=" + f.level.getGameTime()
                                    + " retryAt=" + pet.retryAt + " attemptDay=" + pet.attemptDay
                                    + " attempts=" + pet.attempts + " active=" + f.settlement.active()
                                    + " enabled=" + f.settlement.pets().enabled + " defending=" + f.resident.isDefending()
                                    + " sleeping=" + f.resident.isSleeping() + " loaded=" + (f.level.getEntity(cat.getUUID()) == cat)
                                    + " nav=" + f.resident.currentNavigationDestination()
                                    + " navDone=" + f.resident.getNavigation().isDone()
                                    + " blocker=" + pet.blocker);
                    if (variant == 0) h.assertTrue(cat.getOwnerUUID() != null && cat.isTame(), "Player ownership was erased");
                }
                passed("pet_player_claim_protection");
            } finally {
                f.clean();
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 100)
    public static void deniedResidentFeedingRetainsFoodHealthAndDailyAllowance(GameTestHelper h) {
        var f = new Fixture(h, true); var home = f.home(f.at.north(10));
        var cat = f.add(EntityType.CAT.create(f.level), f.at.west(14));
        var pet = bindPet(f, cat, home, PetState.Status.ACTIVE);
        cat.setHealth(Math.max(1, cat.getMaxHealth() - 3));
        float beforeHealth = cat.getHealth();
        f.citizen.insertIntoInventory(new ItemStack(Items.COD));
        f.settlement.activate();
        final int[] intercepted = {0};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract> deny =
                event -> {
                    if (event.getTarget() == cat) {
                        intercepted[0]++;
                        h.assertTrue(event.getEntity().getUUID().equals(f.citizen.id())
                                && event.getEntity().distanceToSqr(f.resident) < .01,
                                "Protection event actor did not represent the physical resident");
                        event.setCanceled(true);
                    }
                };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try { PetCareService.work(f.level, f.data, f.settlement, f.citizen, f.resident); }
        finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny); }
        h.assertTrue(intercepted[0] == 1 && bag(f.citizen, Items.COD) == 1
                && pet.consumedMeals == 0 && pet.lastFedDay == -1 && cat.getHealth() == beforeHealth
                && pet.status == PetState.Status.ACTIVE, "Denied feeding consumed payment, allowance, health or ownership");
        f.clean(); passed("pet_protection_refusal"); h.succeed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 2600)
    public static void registeredCommunityRoutineAdoptsActualCatAndWolf(GameTestHelper h) {
        var f = new Fixture(h, true);
        f.level.setBlock(f.at, com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 2);
        f.home(f.at.north(10));
        var warehouse = f.warehouse(f.at.east(10));
        f.settlement.community().foodReserve = 0;
        f.settlement.pets().enabled = true; f.settlement.pets().catLimit = 1; f.settlement.pets().dogLimit = 1;
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.COD, 12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.BONE, 12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.COOKED_BEEF, 12));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.BREAD, 64));
        var cat = f.add(EntityType.CAT.create(f.level), f.at.offset(5, 0, -8));
        var wolf = f.add(EntityType.WOLF.create(f.level), f.at.offset(15, 0, -8));
        UUID catId = cat.getUUID(), wolfId = wolf.getUUID();
        var seeded = new HashSet<UUID>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract> seed =
                event -> {
                    if ((event.getTarget() == cat || event.getTarget() == wolf)
                            && event.getEntity().getUUID().equals(f.citizen.id())
                            && seeded.add(event.getTarget().getUUID()))
                        // Fixed success-branch seed immediately before the service's real nextInt(3).
                        ((Animal) event.getTarget()).getRandom().setSeed(0L);
                };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(seed);
        f.cleanupActions.add(() -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(seed));
        final int[] ticks = {0}; final boolean[] handled = {false};
        f.settlement.activate();
        h.onEachTick(() -> {
            if (f.done) return;
            try {
                ticks[0]++; f.observe();
                if (ticks[0] >= 2590) h.fail("Routine timed out: " + PetCareService.report(f.settlement));
                if (ticks[0] % 5 == 0) {
                    CommunityLifeService.prepareWork(f.level, f.data, f.settlement);
                    handled[0] |= CommunityLifeService.handles(f.citizen);
                }
                long catLures = f.settlement.pets().entries.values().stream().filter(p -> p.cat).mapToLong(p -> p.consumedLures).sum();
                long dogLures = f.settlement.pets().entries.values().stream().filter(p -> !p.cat).mapToLong(p -> p.consumedLures).sum();
                h.assertTrue(total(f, warehouse, Items.COD) + catLures == 12
                        && total(f, warehouse, Items.BONE) + dogLures == 12,
                        "Adoption material conservation failed");
                h.assertTrue(f.largestStep < 2, "Caretaker teleported during routine");
            } catch (RuntimeException | Error failure) {
                f.clean(); throw failure;
            }
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(f.settlement.pets().entries.values().stream()
                        .filter(p -> p.status == PetState.Status.ACTIVE).count() == 2,
                "Routine adoption pending " + PetCareService.report(f.settlement)
                        + " work=" + f.citizen.workState() + " life=" + f.citizen.life().task
                        + " actor=" + f.citizen.workFailure()))
                .thenExecute(() -> {
                    try {
                        h.assertTrue(handled[0] && f.moved > 15 && cat.getUUID().equals(catId) && wolf.getUUID().equals(wolfId),
                                "Community routine skipped real movement or replaced animals");
                        h.assertTrue(cat.isTame() && wolf.isTame() && cat.getOwnerUUID() == null && wolf.getOwnerUUID() == null
                                && PetCareService.isProtected(cat) && PetCareService.isProtected(wolf)
                                && PetAi.controls(cat) && PetAi.controls(wolf), "Adopted ownership/AI not installed");
                        h.assertTrue(f.settlement.pets().entries.values().stream().allMatch(p -> p.consumedLures >= 1),
                                "An animal was adopted without real lure payment");
                        ExiledSettlementMod.LOGGER.info("COMPANION_ADOPTION ticks={} moved={} records={} catUUID={} dogUUID={}",
                                ticks[0], f.moved, f.settlement.pets().entries.size(), catId, wolfId);
                        passed("pet_routine_adoption");
                    } finally { f.clean(); }
                }).thenSucceed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 1300)
    public static void petMealsRequirePhysicalPickupAndCannotRepeatTheSameDay(GameTestHelper h) {
        var f = new Fixture(h, false); var home = f.home(f.at.north(10));
        var warehouse = f.warehouse(f.at.east(8));
        f.settlement.community().foodReserve = 0;
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.COD, 1));
        WarehouseInventoryService.insert(f.level, warehouse, new ItemStack(Items.COOKED_BEEF, 1));
        var cat = f.add(EntityType.CAT.create(f.level), f.at.offset(15, 0, 8));
        var dog = f.add(EntityType.WOLF.create(f.level), f.at.offset(16, 0, 5));
        var catPet = bindPet(f, cat, home, PetState.Status.ACTIVE);
        var dogPet = bindPet(f, dog, home, PetState.Status.ACTIVE);
        f.settlement.activate();
        final int[] ticks = {0}; final boolean[] sawFood = {false};
        h.onEachTick(() -> {
            if (f.done) return;
            ticks[0]++; f.observe();
            if (ticks[0] % 5 == 0) PetCareService.work(f.level, f.data, f.settlement, f.citizen, f.resident);
            sawFood[0] |= bag(f.citizen, Items.COD) > 0 || bag(f.citizen, Items.COOKED_BEEF) > 0;
            h.assertTrue(total(f, warehouse, Items.COD) + catPet.consumedMeals == 1
                    && total(f, warehouse, Items.COOKED_BEEF) + dogPet.consumedMeals == 1,
                    "Pet meal vanished or was debited twice");
            h.assertTrue(bag(f.citizen, Items.BREAD) == CommunityContent.carriedFoodTarget,
                    "Pet feeding consumed protected personal rations");
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(catPet.consumedMeals == 1 && dogPet.consumedMeals == 1,
                "Pet meals pending cat=" + catPet.blocker + "/" + catPet.activity
                        + " dog=" + dogPet.blocker + "/" + dogPet.activity))
                .thenIdle(101).thenExecute(() -> {
                    h.assertTrue(sawFood[0] && f.moved > 15 && f.largestStep < 2
                            && catPet.consumedMeals == 1 && dogPet.consumedMeals == 1,
                            "Meal chain skipped actual carry/walking or repeated today");
                    h.assertTrue(catPet.lastFedDay == Math.floorDiv(f.level.getDayTime(), 24000L)
                            && dogPet.lastFedDay == catPet.lastFedDay, "Feeding day was not committed");
                    ExiledSettlementMod.LOGGER.info("COMPANION_FEED ticks={} moved={} catMeals=1 dogMeals=1 reservedBread={}",
                            ticks[0], f.moved, bag(f.citizen, Items.BREAD));
                    f.clean(); passed("pet_live_meals");
                }).thenSucceed();
    }

    @GameTest(templateNamespace = TEMPLATE, template = "empty", timeoutTicks = 700)
    public static void catEscapesWhileDogDefendsThenWithdrawsWhenHurt(GameTestHelper h) {
        var f = new Fixture(h, false); var home = f.home(f.at.west(12));
        place(f.resident, f.at.east(5));
        var cat = f.add(EntityType.CAT.create(f.level), f.at.north(2));
        var dog = f.add(EntityType.WOLF.create(f.level), f.at.south(2));
        var wild = f.add(EntityType.CAT.create(f.level), f.at.offset(-18, 0, -18));
        int wildGoals = wild.goalSelector.getAvailableGoals().size();
        var catPet = bindPet(f, cat, home, PetState.Status.ACTIVE);
        var dogPet = bindPet(f, dog, home, PetState.Status.ACTIVE);
        var zombie = f.add(EntityType.ZOMBIE.create(f.level), f.at.east(5));
        zombie.setNoAi(true); zombie.setTarget(f.resident);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        f.settlement.activate();
        final boolean[] catEscaped = {false}, dogDefended = {false};
        final Vec3 catStart = cat.position();
        h.onEachTick(() -> {
            if (f.done) return;
            catEscaped[0] |= catPet.activity.equals("避险") && cat.position().distanceTo(catStart) > 1;
            dogDefended[0] |= dog.getTarget() == zombie && dogPet.activity.equals("保护受袭居民");
            h.assertTrue(cat.getTarget() == null, "Cat attacked instead of escaping");
            h.assertTrue(!cat.isNoAi() && !dog.isNoAi(), "Pet controller permanently disabled AI");
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(catEscaped[0] && dogDefended[0],
                "Distinct reactions pending cat=" + catPet.activity + " dog=" + dogPet.activity))
                .thenExecute(() -> dog.setHealth(dog.getMaxHealth() * .4F))
                .thenIdle(31).thenExecute(() -> {
                    h.assertTrue(dog.getTarget() == null && dogPet.activity.equals("避险"),
                            "Injured dog continued attacking");
                    PetAi.release(cat); PetAi.release(dog);
                    h.assertTrue(!PetAi.controls(cat) && !PetAi.controls(dog) && !PetAi.controls(wild)
                            && wild.goalSelector.getAvailableGoals().size() == wildGoals,
                            "Scoped controller changed unregistered animals or failed to release");
                    ExiledSettlementMod.LOGGER.info("COMPANION_PET_AI catMoved={} catEscape=true dogDefend=true dogRetreat=true",
                            cat.position().distanceTo(catStart));
                    f.clean(); passed("pet_distinct_ai");
                }).thenSucceed();
    }

    /** Loaded only in the explicitly selected test harness, never normal gameplay. */
    @net.neoforged.fml.common.EventBusSubscriber(modid = "exiledsettlement",
            bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
    public static final class RestartCommands {
        private static final String NAME = "Companion restart fixture";

        @net.neoforged.bus.api.SubscribeEvent
        public static void register(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
            if (!Boolean.getBoolean("exiledsettlement.testHarness")
                    || !"companions".equals(System.getProperty("exiledsettlement.testSuite"))) return;
            event.getDispatcher().register(net.minecraft.commands.Commands.literal("companiontest")
                    .requires(source -> source.hasPermission(4))
                    .then(net.minecraft.commands.Commands.literal("seed").executes(context -> seed(context.getSource())))
                    .then(net.minecraft.commands.Commands.literal("verify").executes(context -> verify(context.getSource()))));
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException(message);
        }

        private static int seed(net.minecraft.commands.CommandSourceStack source) {
            ServerLevel level = source.getLevel();
            SettlementSavedData data = SettlementSavedData.get(level);
            require(data.settlements().stream().noneMatch(s -> s.name().equals(NAME)), "Restart seed already exists");
            BlockPos at = new BlockPos(640000, 100, 640000);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                level.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, true);
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(15, -1, 15)))
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            var s = data.createOrRecover(at, UUID.randomUUID(), NAME).value();
            require(s != null, "Cannot create restart fixture");
            s.deactivate(); s.guardians().enabled = false; s.pets().enabled = true;
            s.terrain().enabled = false; s.autoRoads().enabled = false; s.decorations().enabled = false;
            s.guardians().target = 3; s.guardians().ironReserve = 27;
            var citizen = s.citizens().iterator().next();
            BlockPos bed = at.north(5);
            level.setBlock(bed, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                    .setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(bed.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                    .setValue(BedBlock.PART, BedPart.HEAD), 2);
            BlockPos door = bed.south(2);
            level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
            level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            var home = s.addBuilding(BuildingType.HOUSE, bed, Direction.NORTH,
                    bed.offset(-2, 0, -3), bed.offset(2, 2, 2), 0, 1);
            home.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(bed.north()));
            citizen.assignHome(home.id(), bed.north());
            citizen.insertIntoInventory(new ItemStack(Items.IRON_BLOCK, 4));
            citizen.insertIntoInventory(new ItemStack(Items.CARVED_PUMPKIN, 1));
            citizen.insertIntoInventory(new ItemStack(Items.SALMON, 9));
            var pending = s.guardians().createOrder(citizen.id(), at.east(7));
            pending.progress = 619.5;
            var alive = s.guardians().createOrder(citizen.id(), at.east(3));
            alive.state = "alive"; alive.charged = true; alive.health = 75;
            IronGolem golem = Objects.requireNonNull(EntityType.IRON_GOLEM.create(level));
            golem.setUUID(alive.entityId); golem.setHealth(75); golem.setPersistenceRequired();
            golem.getPersistentData().putUUID("exiledsettlement.guardianId", alive.id);
            golem.getPersistentData().putUUID("exiledsettlement.guardianSettlement", s.id());
            place(golem, alive.anchor); require(level.addFreshEntity(golem), "Cannot add restart golem");
            var dead = s.guardians().createOrder(citizen.id(), at.west(6));
            dead.state = "dead"; dead.charged = true;
            for (int i = 0; i < 3; i++) {
                boolean cat = i != 1;
                Animal animal = cat ? EntityType.CAT.create(level) : EntityType.WOLF.create(level);
                Objects.requireNonNull(animal);
                BlockPos position = at.offset(i * 3, 0, 6);
                var pet = new PetState.Pet(UUID.randomUUID(), animal.getUUID(), cat, citizen.id(), home.id(), position);
                pet.status = i == 2 ? PetState.Status.ADOPTING : PetState.Status.ACTIVE;
                pet.lastFedDay = i == 2 ? -1 : 6; pet.consumedMeals = i == 2 ? 0 : 7;
                pet.attemptDay = 7; pet.attempts = 2; pet.consumedLures = 2;
                s.pets().entries.put(pet.id, pet);
                animal.getPersistentData().putUUID("exiledsettlement.petId", pet.id);
                animal.getPersistentData().putUUID("exiledsettlement.petSettlement", s.id());
                animal.setPersistenceRequired(); place(animal, position);
                require(level.addFreshEntity(animal), "Cannot add restart pet");
            }
            data.touch();
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "COMPANION_RESTART_SEEDED settlement=" + s.id()
                            + " liveGuard=1 assembling=1 dead=1 pets=3 cargo=4ironBlocks+1pumpkin+9salmon"), false);
            return 1;
        }

        private static int verify(net.minecraft.commands.CommandSourceStack source) {
            ServerLevel level = source.getLevel();
            var s = SettlementSavedData.get(level).settlements().stream().filter(record -> record.name().equals(NAME))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Restart settlement absent"));
            require(!s.active() && !s.guardians().enabled && s.pets().enabled, "Restart policy changed");
            require(s.guardians().target == 3 && s.guardians().ironReserve == 27, "Restart settings changed");
            require(s.guardians().records().size() == 3 && s.guardians().count() == 2
                    && s.guardians().aliveCount() == 1 && s.guardians().pendingCount() == 1, "Guardian identity/quotas changed");
            var pending = s.guardians().records().stream().filter(GuardianState.Entry::assembling).findFirst().orElseThrow();
            require(pending.progress == 619.5 && !pending.charged && level.getEntity(pending.entityId) == null,
                    "Pending assembly replayed or lost progress");
            var alive = s.guardians().records().stream().filter(entry -> entry.state.equals("alive")).findFirst().orElseThrow();
            require(level.getEntity(alive.entityId) instanceof IronGolem, "Saved guardian entity is not loaded");
            require(alive.charged && ((IronGolem) level.getEntity(alive.entityId)).getHealth() == 75,
                    "Guardian payment or health changed");
            var dead = s.guardians().records().stream().filter(entry -> entry.state.equals("dead")).findFirst().orElseThrow();
            require(level.getEntity(dead.entityId) == null, "Dead guardian resurrected");
            require(s.pets().entries.size() == 3 && s.pets().count(true) == 2 && s.pets().count(false) == 1,
                    "Pet identity or unloaded count changed");
            for (var pet : s.pets().entries.values()) {
                require(level.getEntity(pet.entityId) instanceof Animal, "Saved pet entity is not loaded");
                require(pet.attemptDay == 7 && pet.attempts == 2 && pet.consumedLures == 2,
                        "Attempt history was reset");
                if (pet.status == PetState.Status.ACTIVE)
                    require(pet.lastFedDay == 6 && pet.consumedMeals == 7, "Paid meal history changed");
                else require(pet.status == PetState.Status.ADOPTING && pet.lastFedDay == -1, "Adoption replayed");
            }
            var citizen = s.citizen(pending.worker);
            require(citizen != null && bag(citizen, Items.IRON_BLOCK) == 4
                    && bag(citizen, Items.CARVED_PUMPKIN) == 1 && bag(citizen, Items.SALMON) == 9,
                    "Carried real materials changed across restart");
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "COMPANION_RESTART_VERIFIED settlement=" + s.id()
                            + " exactEntities=4 liveGuard=1 assembling=1 dead=1 pets=3 materialsUnchanged=true"), false);
            return 1;
        }
    }
}

package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenEquipmentService;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.network.OpenCitizenAssignmentPayload;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EquipmentInventoryGameTests {
    private EquipmentInventoryGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment")
    public static void ysmResidentSelectionPersistsAndRejectsControlCharacters(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "YSM resident", BlockPos.ZERO);
        helper.assertTrue(citizen.setYsmModel("example:resident_model", "winter")
                        && citizen.usesYsmModel(),
                "Valid YSM resident selection was not accepted");
        helper.assertTrue(!citizen.setYsmModel("bad\nmodel", "winter")
                        && citizen.ysmModelId().equals("example:resident_model"),
                "Invalid control characters changed the resident selection");
        CitizenRecord loaded = CitizenRecord.load(
                citizen.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.assertTrue(loaded.ysmModelId().equals("example:resident_model")
                        && loaded.ysmTextureName().equals("winter"),
                "YSM model or texture selection did not survive save/load");
        helper.assertTrue(loaded.setYsmModel("", "ignored")
                        && !loaded.usesYsmModel() && loaded.ysmTextureName().isEmpty(),
                "Disabling YSM did not also clear the texture selection");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment")
    public static void legacyBackpackEquipmentMigratesOnceWithoutLosingComponents(GameTestHelper helper) {
        CitizenRecord original = new CitizenRecord(UUID.randomUUID(), "Legacy gear", BlockPos.ZERO);
        original.insertIntoInventory(new ItemStack(Items.IRON_HELMET));
        ItemStack best = new ItemStack(Items.DIAMOND_HELMET);
        best.setDamageValue(17);
        best.set(DataComponents.CUSTOM_NAME, Component.literal("Owned helmet"));
        original.insertIntoInventory(best);
        original.insertIntoInventory(new ItemStack(Items.IRON_CHESTPLATE));
        original.insertIntoInventory(new ItemStack(Items.IRON_LEGGINGS));
        original.insertIntoInventory(new ItemStack(Items.IRON_BOOTS));
        original.insertIntoInventory(new ItemStack(Items.SHIELD));
        for (int slot = 0; slot < 30; slot++) original.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 64));
        helper.assertTrue(!original.hasInventoryCapacity(), "Migration fixture must start with all 36 slots occupied");
        int total = countAll(original);
        CompoundTag legacy = original.save(helper.getLevel().registryAccess());
        legacy.remove("Equipment");
        CitizenRecord migrated = CitizenRecord.load(legacy, helper.getLevel().registryAccess());
        helper.assertTrue(ItemStack.matches(best, migrated.equipmentItem(EquipmentSlot.HEAD)),
                "Legacy migration did not preserve and move the best helmet including its components");
        helper.assertTrue(migrated.hasInventoryItem(stack -> stack.is(Items.IRON_HELMET))
                        && migrated.inventory().stream().filter(stack -> !stack.isEmpty()).count() == 31,
                "Spare armor was lost or equipped armor still occupies working backpack slots");
        for (int reload = 0; reload < 3; reload++) {
            migrated = CitizenRecord.load(migrated.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertTrue(countAll(migrated) == total
                            && ItemStack.matches(best, migrated.equipmentItem(EquipmentSlot.HEAD)),
                    "Repeated saving duplicated, lost or changed equipment");
        }
        migrated.clearWork();
        migrated.assignProfession(CitizenProfession.FARMER, null);
        helper.assertTrue(countAll(migrated) == total, "Rest/job handoff changed equipment ownership");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment")
    public static void fullWorkBackpackCanReceiveIndependentArmorAndOffhand(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Full backpack", BlockPos.ZERO);
        for (int slot = 0; slot < 36; slot++) citizen.insertIntoInventory(new ItemStack(Items.STONE, 64));
        helper.assertTrue(citizen.inventoryCapacityFor(new ItemStack(Items.IRON_HELMET)) == 0
                        && citizen.equipmentCapacityFor(new ItemStack(Items.IRON_HELMET)) == 1,
                "Independent equipment capacity was confused with working storage");
        helper.assertTrue(citizen.receiveEquipment(new ItemStack(Items.IRON_HELMET)).isEmpty()
                        && citizen.receiveEquipment(new ItemStack(Items.SHIELD)).isEmpty(),
                "Full working backpack prevented receiving wearable equipment");
        citizen.selectActiveSlot(0);
        helper.assertTrue(CitizenEquipmentService.visibleHand(citizen).is(Items.STONE)
                        && countAll(citizen) == 36 * 64 + 2
                        && citizen.inventory().stream().filter(stack -> !stack.isEmpty()).count() == 36,
                "Main-hand reference copied an item, or wearables displaced working inventory");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment")
    public static void residentEquipmentPacketKeepsActualItemComponents(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Packet gear", BlockPos.ZERO);
        ItemStack armor = new ItemStack(Items.IRON_CHESTPLATE);
        armor.setDamageValue(37);
        armor.set(DataComponents.CUSTOM_NAME, Component.literal("Personal armor"));
        citizen.receiveEquipment(armor);
        citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        var payload = new OpenCitizenAssignmentPayload(UUID.randomUUID(), citizen.id(), citizen.name(),
                "example:model", "default",
                CitizenProfession.LUMBERJACK, false, false, null, CitizenActivity.IDLE, "", null,
                List.of(), List.of(new OpenCitizenAssignmentPayload.InventoryEntry(0, citizen.inventoryItem(0), true)),
                CitizenRecord.EQUIPMENT_SLOTS.stream().map(citizen::equipmentItem).toList(),
                CitizenEquipmentService.visibleHand(citizen), 73, 13.5F, 20.0F, true, false);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            OpenCitizenAssignmentPayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = OpenCitizenAssignmentPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(ItemStack.matches(decoded.equipment().get(1), armor)
                            && decoded.inventory().getFirst().stack().is(Items.STONE_AXE)
                            && decoded.mainHand().is(Items.STONE_AXE)
                            && decoded.ysmModelId().equals("example:model")
                            && decoded.ysmTextureName().equals("default")
                            && decoded.nutrition() == 73 && decoded.life() == 13.5F && decoded.maxLife() == 20.0F
                            && decoded.projectionLoaded() && !decoded.armorWorn()
                            && buffer.readableBytes() == 0,
                    "Equipment packet lost components, durability or main-hand reference");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment", timeoutTicks = 200)
    public static void actualArmorDamagePersistsAndBrokenArmorDoesNotReappear(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(14504, 10, 8200));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, UUID.randomUUID(), "Equipment damage fixture");
        helper.assertTrue(created.success(), "Equipment damage settlement could not be created");
        SettlementRecord settlement = created.value();
        settlement.deactivate(); // This is a combat component test, not a work/commute acceptance scene.
        CitizenRecord citizen = settlement.citizens().iterator().next();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.setDamageValue(helmet.getMaxDamage() - 1);
        citizen.receiveEquipment(helmet);
        citizen.receiveEquipment(new ItemStack(Items.IRON_CHESTPLATE));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Equipment damage projection could not be created");
        entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, citizen.id().toString());
        entity.moveTo(core.getX() + 0.5D, core.getY(), core.getZ() + 0.5D, 0, 0);
        entity.setNoGravity(true);
        entity.setNoAi(true);
        citizen.updateProjection(entity.getUUID(), core);
        ChunkPos chunk = new ChunkPos(core);
        // The randomized test origin may put the projection at a chunk edge.
        // Load its surrounding entity-ticking neighborhood, not just the center.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            helper.getLevel().setChunkForced(chunk.x + dx, chunk.z + dz, true);
        Runnable release = () -> {
            entity.discard();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                helper.getLevel().setChunkForced(chunk.x + dx, chunk.z + dz, false);
        };
        helper.runAtTickTime(199, release); // Also clean up if readiness or damage assertions time out.
        CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Equipment damage projection could not enter the level");
        // A newly forced remote chunk is not guaranteed to enter entity ticking
        // within five server ticks. Wait for the real tick that applies vanilla
        // item modifiers; never synthesize attributes or tick the entity here.
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(entity.tickCount > 0,
                "Equipment projection has not received a real level tick: tickCount=" + entity.tickCount
                        + ", removed=" + entity.isRemoved())).thenExecute(() -> {
            try {
                helper.assertTrue(entity.getArmorValue() == 0 && entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                        "Resident wore armor before danger");
                float health = entity.getHealth();
                // Vanilla minecraft:generic is explicitly in bypasses_armor.
                // A contact hit exercises real armor reduction and durability.
                var attacker = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
                var damageSource = helper.getLevel().damageSources().mobAttack(attacker);
                helper.assertTrue(!damageSource.is(DamageTypeTags.BYPASSES_ARMOR),
                        "Armor fixture selected an armor-bypassing damage source");
                boolean hurt = entity.hurt(damageSource, 8.0F);
                helper.assertTrue(hurt && health - entity.getHealth() > 0.0F && health - entity.getHealth() < 8.0F
                                && citizen.equipmentItem(EquipmentSlot.CHEST).getDamageValue() > 0
                                && citizen.equipmentItem(EquipmentSlot.HEAD).isEmpty(),
                        "Armor protection or durable wear/breakage did not reach the authoritative citizen record: hurt="
                                + hurt + ", healthLoss=" + (health - entity.getHealth())
                                + ", chestDamage=" + citizen.equipmentItem(EquipmentSlot.CHEST).getDamageValue()
                                + ", head=" + citizen.equipmentItem(EquipmentSlot.HEAD));
                int damage = citizen.equipmentItem(EquipmentSlot.CHEST).getDamageValue();
                CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
                CitizenRecord reloaded = CitizenRecord.load(citizen.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
                helper.assertTrue(entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                                && reloaded.equipmentItem(EquipmentSlot.HEAD).isEmpty()
                                && reloaded.equipmentItem(EquipmentSlot.CHEST).getDamageValue() == damage,
                        "Projection sync or save/load resurrected broken armor or reset durability");
            } finally {
                release.run();
            }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment", timeoutTicks = 80)
    public static void unloadingKeepsBelongingsAndActualDeathDropsThemExactlyOnce(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(14904, 10, 8200));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, UUID.randomUUID(), "Equipment death fixture");
        helper.assertTrue(created.success(), "Equipment death settlement could not be created");
        SettlementRecord settlement = created.value();
        settlement.deactivate();
        CitizenRecord citizen = settlement.citizens().iterator().next();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.setDamageValue(17);
        helmet.set(DataComponents.CUSTOM_NAME, Component.literal("Death handoff helmet"));
        citizen.receiveEquipment(helmet);
        citizen.receiveEquipment(new ItemStack(Items.IRON_CHESTPLATE));
        citizen.receiveEquipment(new ItemStack(Items.IRON_LEGGINGS));
        citizen.receiveEquipment(new ItemStack(Items.IRON_BOOTS));
        citizen.receiveEquipment(new ItemStack(Items.SHIELD));
        citizen.insertIntoInventory(new ItemStack(Items.IRON_AXE));
        citizen.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 32));
        citizen.insertIntoInventory(new ItemStack(Items.GOLDEN_HELMET));
        citizen.selectActiveSlot(citizen.findInventorySlot(stack -> stack.is(Items.COBBLESTONE)));
        int ownedCount = countAll(citizen);
        AABB dropArea = new AABB(core).inflate(6);
        ChunkPos chunk = new ChunkPos(core);
        helper.getLevel().setChunkForced(chunk.x, chunk.z, true);
        SettlementCitizenEntity initial = equipmentProjection(helper, settlement, citizen, core);
        SettlementCitizenEntity[] dyingProjection = {null};
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(initial.tickCount > 0,
                "Equipment unload fixture has not received a real level tick: " + initial.tickCount))
                .thenExecute(() -> {
                    initial.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    helper.assertTrue(citizen.alive() && countAll(citizen) == ownedCount
                                    && helper.getLevel().getEntitiesOfClass(ItemEntity.class, dropArea).isEmpty(),
                            "Ordinary chunk removal dropped or erased resident belongings");
                    CitizenRecord savedAfterUnload = CitizenRecord.load(citizen.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
                    helper.assertTrue(countAll(savedAfterUnload) == ownedCount
                                    && ItemStack.matches(savedAfterUnload.equipmentItem(EquipmentSlot.HEAD), helmet),
                            "Saving an unloaded resident changed independent equipment");
                    dyingProjection[0] = equipmentProjection(helper, settlement, citizen, core);
                }).thenWaitUntil(() -> helper.assertTrue(dyingProjection[0].tickCount > 0,
                        "Replacement equipment projection has not received a real level tick"))
                .thenExecute(() -> {
                    // Bypass armor here: this test measures ownership transfer, not wear.
                    helper.assertTrue(dyingProjection[0].hurt(helper.getLevel().damageSources().fellOutOfWorld(), Float.MAX_VALUE)
                                    && !citizen.alive(),
                            "Actual entity damage did not reach the registered LivingDeathEvent handler");
                }).thenWaitUntil(() -> {
                    List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, dropArea);
                    int droppedCount = dropped.stream().mapToInt(item -> item.getItem().getCount()).sum();
                    helper.assertTrue(droppedCount == ownedCount
                                    && dropped.stream().anyMatch(item -> ItemStack.matches(item.getItem(), helmet))
                                    && countAll(citizen) == 0,
                            "Death failed to transfer all backpack/armor/offhand belongings once, preserving components: "
                                    + "dropped=" + droppedCount + ", ownedBefore=" + ownedCount
                                    + ", recordRemaining=" + countAll(citizen) + ", items="
                                    + dropped.stream().map(ItemEntity::getItem).toList());
                }).thenExecute(() -> {
            SettlementCitizenEntity dying = dyingProjection[0];
            try {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    helper.assertTrue(dying.getItemBySlot(slot).isEmpty(), "Vanilla projection retained a second equipment copy");
                }
                CitizenWorkService.recoverCarriedItem(helper.getLevel(), data, citizen, dying);
                CitizenRecord deadReloaded = CitizenRecord.load(citizen.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
                CitizenWorkService.recoverCarriedItem(helper.getLevel(), data, deadReloaded, dying);
                helper.assertTrue(!deadReloaded.alive() && countAll(deadReloaded) == 0
                                && helper.getLevel().getEntitiesOfClass(ItemEntity.class, dropArea).stream()
                                .mapToInt(item -> item.getItem().getCount()).sum() == ownedCount,
                        "Repeated death recovery or reloaded dead records duplicated belongings");
            } finally {
                dying.discard();
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, dropArea).forEach(Entity::discard);
                helper.getLevel().setChunkForced(chunk.x, chunk.z, false);
            }
        }).thenSucceed();
    }

    private static SettlementCitizenEntity equipmentProjection(
            GameTestHelper helper, SettlementRecord settlement, CitizenRecord citizen, BlockPos position
    ) {
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Equipment projection could not be created");
        entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, citizen.id().toString());
        entity.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0, 0);
        entity.setNoAi(true);
        entity.setNoGravity(true);
        citizen.updateProjection(entity.getUUID(), position);
        CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Equipment projection could not enter the level");
        return entity;
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_equipment")
    public static void personalFoodAllowanceIsSharedAcrossStacksAndSpareToolsRemainDeliverable(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Ration allocation", BlockPos.ZERO);
        citizen.assignProfession(CitizenProfession.FARMER, null);
        citizen.insertIntoInventory(new ItemStack(Items.BREAD, 2));
        citizen.insertIntoInventory(new ItemStack(Items.CARROT, 64));
        citizen.insertIntoInventory(new ItemStack(Items.APPLE, 12));
        citizen.insertIntoInventory(new ItemStack(Items.STONE_HOE));
        citizen.insertIntoInventory(new ItemStack(Items.IRON_HOE));
        citizen.insertIntoInventory(new ItemStack(Items.LEAD, 8));
        helper.assertTrue(CitizenEquipmentService.deliverableCount(citizen, 0) == 0
                && CitizenEquipmentService.deliverableCount(citizen, 1) == 50
                && CitizenEquipmentService.deliverableCount(citizen, 2) == 12
                && CitizenEquipmentService.deliverableCount(citizen, 3) == 0
                && CitizenEquipmentService.deliverableCount(citizen, 4) == 1
                && CitizenEquipmentService.deliverableCount(citizen, 5) == 7,
                "Reserve swallowed whole food stacks, kept duplicate tools, or failed to keep the working hoe");
        helper.succeed();
    }

    private static int countAll(CitizenRecord citizen) {
        return citizen.inventory().stream().mapToInt(ItemStack::getCount).sum()
                + CitizenRecord.EQUIPMENT_SLOTS.stream().map(citizen::equipmentItem).mapToInt(ItemStack::getCount).sum();
    }
}

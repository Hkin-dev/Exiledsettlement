package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentExperienceGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_experience", timeoutTicks=500)
    public static void nearbyPublicDropsArePickedUpDuringAnotherTask(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 0);
        f.settlement.deactivate();
        f.citizen.startTask(CitizenWorkState.TO_OUTPUT_CONTAINER, f.core.east(20), null, null);
        f.spawn(f.core.east(3), false);
        ItemEntity publicDrop = new ItemEntity(f.level, f.entity.getX(), f.entity.getY(), f.entity.getZ(), new ItemStack(Items.COBBLESTONE, 7));
        publicDrop.setNoPickUpDelay(); f.level.addFreshEntity(publicDrop);
        ItemEntity privateDrop = new ItemEntity(f.level, f.entity.getX(), f.entity.getY(), f.entity.getZ(), new ItemStack(Items.DIAMOND, 3));
        privateDrop.setNoPickUpDelay(); privateDrop.setTarget(UUID.randomUUID()); f.level.addFreshEntity(privateDrop);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.count(Items.COBBLESTONE) == 7, "Resident did not spontaneously pick up nearby public items"))
                .thenExecute(() -> {
                    helper.assertTrue(!publicDrop.isAlive() && privateDrop.isAlive() && f.count(Items.DIAMOND) == 0
                                    && f.citizen.workState() == CitizenWorkState.TO_OUTPUT_CONTAINER,
                            "Pickup duplicated items, stole an owned drop, or interrupted the job");
                    privateDrop.discard(); f.finish();
                }).thenSucceed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_experience", timeoutTicks=500)
    public static void armorProtectsFirstAttackAndIsStoredAfterDanger(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1); f.settlement.deactivate();
        f.citizen.receiveEquipment(new ItemStack(Items.IRON_CHESTPLATE));
        f.spawn(f.core.east(3), false);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.entity.tickCount > 0, "Waiting for the real entity tick"))
                .thenExecute(() -> {
                    helper.assertTrue(f.entity.getArmorValue() == 0 && f.entity.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "Peaceful resident wore armor");
                    var attacker = EntityType.ZOMBIE.create(f.level);
                    attacker.moveTo(f.core.getCenter());
                    float before = f.entity.getHealth();
                    helper.assertTrue(f.entity.hurt(f.level.damageSources().mobAttack(attacker), 8)
                                    && before - f.entity.getHealth() > 0 && before - f.entity.getHealth() < 8,
                            "First surprise attack bypassed personal armor");
                    attacker.discard(); f.entity.setLastHurtByMob(null);
                }).thenWaitUntil(() -> helper.assertTrue(!f.entity.isArmorReady() && f.entity.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "Armor remained worn after danger ended"))
                .thenExecute(() -> {
                    helper.assertTrue(f.citizen.equipmentItem(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                                    && f.citizen.equipmentItem(EquipmentSlot.CHEST).getDamageValue() > 0,
                            "Removing armor lost personal ownership or durable battle damage");
                    f.finish();
                }).thenSucceed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_demolition", timeoutTicks=500)
    public static void nightDemolitionRemovesOneBlockPerWorkerPerFiveTicks(GameTestHelper helper) {
        demolitionCadence(helper, 1);
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_demolition", timeoutTicks=500)
    public static void twoWorkersDemolishTwoBlocksPerFiveTicks(GameTestHelper helper) {
        demolitionCadence(helper, 2);
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_demolition", timeoutTicks=500)
    public static void threeWorkersDemolishThreeBlocksPerFiveTicks(GameTestHelper helper) {
        demolitionCadence(helper, 3);
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="resident_demolition", timeoutTicks=500)
    public static void demolitionUsesAndRetainsOwnedToolAtNight(GameTestHelper helper) {
        demolitionCadence(helper, 1, false);
    }

    private static void demolitionCadence(GameTestHelper helper, int workers) {
        demolitionCadence(helper, workers, true);
    }

    private static void demolitionCadence(GameTestHelper helper, int workers, boolean equipped) {
        Fixture f = new Fixture(helper, equipped ? 1 + workers : 5);
        var template = BlueprintTemplates.create(BuildingType.HOUSE, f.core.offset(8,0,8), Direction.NORTH);
        var building = f.settlement.addBuilding(BuildingType.HOUSE, template.registrationPosition(), template.facing(),
                template.minCorner(), template.maxCorner(), template.transitionMargin(), BlueprintTemplates.currentTemplateVersion(BuildingType.HOUSE));
        for (int x=template.minCorner().getX()-4;x<=template.maxCorner().getX()+4;x++) {
            for (int z=template.minCorner().getZ()-4;z<=template.maxCorner().getZ()+4;z++) f.level.setBlock(new BlockPos(x,f.core.getY()-1,z),Blocks.DIRT.defaultBlockState(),2);
        }
        var cells = template.placements().stream().filter(p -> p.mode().isFinalStructure() && !p.state().isAir()
                && p.position().getY() > f.core.getY() + 2 && !BlueprintPlacementGroups.isPaired(p.state())).limit(10 * workers + 1).toList();
        int blockCount=10 * workers;
        helper.assertTrue(cells.size()==blockCount + 1,"Demolition fixture has too few roof cells");
        for (int i=0;i<blockCount;i++) f.level.setBlock(cells.get(i).position(),cells.get(i).state(),2);
        BlockPos protectedCell=cells.get(blockCount).position(); f.level.setBlock(protectedCell,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
        helper.assertTrue(building.queueDemolition(),"Demolition was not queued");
        ItemStack ownedTool=new ItemStack(Items.IRON_SHOVEL);
        ownedTool.setDamageValue(equipped ? 0 : 7);
        ownedTool.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Personal demolition shovel"));
        f.citizen.insertIntoInventory(ownedTool);
        f.spawn(new BlockPos(template.minCorner().getX()-2, f.core.getY(),template.minCorner().getZ()),false);
        for (int i=1;i<workers;i++) {
            var worker=f.settlement.addMigrant(); worker.assignProfession(CitizenProfession.WORKER,null);
            worker.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));
            var resident=ModEntities.SETTLEMENT_CITIZEN.get().create(f.level);
            resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.settlement.id().toString());
            resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,worker.id().toString());
            BlockPos feet=new BlockPos(template.minCorner().getX()-2, f.core.getY(),template.minCorner().getZ()+i*2);
            resident.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);
            worker.updateProjection(resident.getUUID(),feet); f.extras.add(resident);
            helper.assertTrue(f.level.addFreshEntity(resident),"Could not spawn additional demolition worker");
        }
        int[] previous={0}; long[] first={-1},last={-1};
        helper.onEachTick(() -> {
            if (f.finished) return;
            f.level.setDayTime(16000);
            int removed=(int)cells.subList(0,blockCount).stream().filter(p -> f.level.getBlockState(p.position()).isAir()).count();
            if (removed>previous[0]) {
                long now=f.level.getGameTime();
                helper.assertTrue(removed-previous[0]==workers && (last[0]<0 || now-last[0]==5),
                        "Demolition cadence deviated: workers="+workers+", delta="+(removed-previous[0])+", elapsed="+(now-last[0])
                                +", states="+f.settlement.citizens().stream().filter(CitizenRecord::alive)
                                .map(c->c.workState()+"/"+c.workFailure()+"@"+c.lastPosition()).toList());
                if(first[0]<0)first[0]=now; last[0]=now; previous[0]=removed;
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(previous[0]==blockCount,"Night demolition did not advance: "+f.citizen.activity()+"/"+f.citizen.workFailure()
                        +" feet="+f.entity.position()+" ticks="+f.entity.tickCount+" ground="+f.entity.onGround()+" bounds="+template.minCorner()+".."+template.maxCorner()))
                .thenExecute(() -> {
                    helper.assertTrue(last[0]-first[0]==45 && f.level.getBlockState(protectedCell).is(Blocks.DIAMOND_BLOCK),"Fast demolition altered a player replacement or lost its rate");
                    int slot=f.citizen.findInventorySlot(s->s.is(Items.IRON_SHOVEL));
                    helper.assertTrue(slot>=0 && f.citizen.inventoryItem(slot).getDamageValue()==(equipped?10:17)
                                    && CitizenEquipmentService.isReservedEquipment(f.citizen,slot)
                                    && f.citizen.inventoryItem(slot).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                                    .getString().equals("Personal demolition shovel"),
                            "Demolition replaced, surrendered, or failed to wear the real personal tool");
                    f.finish();
                }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper helper; final ServerLevel level; final SettlementSavedData data;
        final SettlementRecord settlement; final CitizenRecord citizen; final BlockPos core;
        final List<ChunkPos> chunks=new ArrayList<>(); SettlementCitizenEntity entity; boolean finished;
        final List<SettlementCitizenEntity> extras=new ArrayList<>();
        Fixture(GameTestHelper h,int index) {
            helper=h;level=h.getLevel();data=SettlementSavedData.get(level);
            core=h.absolutePos(new BlockPos(160000+index*192,10,92000));
            ChunkPos center=new ChunkPos(core);
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
                ChunkPos c=new ChunkPos(center.x+dx,center.z+dz);chunks.add(c);level.setChunkForced(c.x,c.z,true);level.getChunk(c.x,c.z);
            }
            for(BlockPos p:BlockPos.betweenClosed(core.offset(-5,-1,-5),core.offset(32,12,32))) {
                level.setBlock(p,p.getY()<core.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            }
            level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),3);
            var result=data.createOrRecover(core,UUID.randomUUID(),"Resident experience fixture");
            helper.assertTrue(result.success(),"Could not create resident fixture"); settlement=result.value();
            citizen=settlement.citizens().iterator().next();
            for(var other:settlement.citizens())if(other!=citizen)other.markDead(core);
            citizen.assignProfession(CitizenProfession.WORKER,null);
            helper.runAtTickTime(499,this::finish);
        }
        void spawn(BlockPos feet,boolean noAi) {
            entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,settlement.id().toString());
            entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,citizen.id().toString());
            entity.moveTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,0,0);entity.setNoAi(noAi);
            citizen.updateProjection(entity.getUUID(),feet); CitizenEquipmentService.syncVisibleEquipment(citizen,entity);
            helper.assertTrue(level.addFreshEntity(entity),"Could not spawn resident fixture"); data.touch();
        }
        int count(Item item) { return citizen.inventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
        void finish() { if(finished)return;finished=true; settlement.deactivate();if(entity!=null)entity.discard();extras.forEach(Entity::discard);for(var c:chunks)level.setChunkForced(c.x,c.z,false);data.touch(); }
    }
}

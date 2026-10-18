package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CitizenNamingGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void nameTagRequiresMembershipAndPersists(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = helper.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;
        var data = SettlementSavedData.get(level);
        BlockPos core = helper.absolutePos(new BlockPos(40000, 3, 40000));
        UUID owner = UUID.randomUUID();
        data.createOrRecover(core, owner, "Naming fixture");
        var settlement = data.findByCore(core).orElseThrow();
        var citizen = settlement.citizens().iterator().next();
        String original = citizen.name();
        var entity = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        helper.assertTrue(entity != null, "Missing resident entity");
        entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, citizen.id().toString());
        entity.moveTo(helper.absolutePos(new BlockPos(2, 2, 2)).getCenter());
        player.moveTo(entity.position());
        player.setShiftKeyDown(true);
        CitizenProjectionService.syncName(citizen, entity);
        var tag = new ItemStack(Items.NAME_TAG, 2);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("阿木"));
        player.setItemInHand(InteractionHand.MAIN_HAND, tag);
        player.interactOn(entity, InteractionHand.MAIN_HAND);
        helper.assertTrue(citizen.name().equals(original) && tag.getCount() == 2, "Visitor renamed or spent tag");
        settlement.addMember(player.getUUID());
        player.interactOn(entity, InteractionHand.MAIN_HAND);
        helper.assertTrue(citizen.name().equals("阿木") && tag.getCount() == 1, "Rename did not consume exactly one tag");
        helper.assertTrue(CitizenRecord.load(citizen.save(level.registryAccess()), level.registryAccess()).name().equals("阿木"), "Name lost on reload");
        helper.assertTrue(entity.getCustomName().getString().endsWith("] 阿木"), "Profession prefix lost");
        entity.setCustomName(Component.literal("stale"));
        CitizenProjectionService.syncName(CitizenRecord.load(citizen.save(level.registryAccess()), level.registryAccess()), entity);
        helper.assertTrue(entity.getCustomName().getString().endsWith("] 阿木"), "Saved name/profession lost on refresh");
        helper.assertTrue(!citizen.rename("\n") && !citizen.rename("x".repeat(65)), "Invalid name accepted");
        settlement.deactivate();
        data.touch();
        helper.succeed();
    }

    private record Fixture(net.minecraft.server.level.ServerPlayer player,
            com.hkin.exiledsettlement.settlement.SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity entity, ItemStack tag) {}

    private static void withFixture(GameTestHelper h, int offset, java.util.function.Consumer<Fixture> assertions) {
        var player = h.makeMockServerPlayerInLevel(); player.getAbilities().instabuild = false;
        // Mock players skip negotiation. Assert the real registrations before configuring their test connection.
        var openId = com.hkin.exiledsettlement.network.OpenCitizenRenamePayload.TYPE.id();
        var confirmId = com.hkin.exiledsettlement.network.RenameCitizenPayload.TYPE.id();
        h.assertTrue(net.neoforged.neoforge.network.registration.NetworkRegistry.getCodec(openId,
                net.minecraft.network.ConnectionProtocol.PLAY, net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) != null,
                "Rename UI not registered clientbound");
        h.assertTrue(net.neoforged.neoforge.network.registration.NetworkRegistry.getCodec(confirmId,
                net.minecraft.network.ConnectionProtocol.PLAY, net.minecraft.network.protocol.PacketFlow.SERVERBOUND) != null,
                "Rename confirmation not registered serverbound");
        net.neoforged.neoforge.network.registration.ChannelAttributes.setPayloadSetup(player.connection.getConnection(),
                new net.neoforged.neoforge.network.registration.NetworkPayloadSetup(java.util.Map.of(
                        net.minecraft.network.ConnectionProtocol.PLAY, java.util.Map.of(
                                openId, new net.neoforged.neoforge.network.registration.NetworkChannel(openId, "14"),
                                confirmId, new net.neoforged.neoforge.network.registration.NetworkChannel(confirmId, "14")))));
        var data = SettlementSavedData.get(h.getLevel());
        var core = h.absolutePos(new BlockPos(40400 + offset * 400, 3, 40000));
        data.createOrRecover(core, player.getUUID(), "Naming UI fixture");
        var s = data.findByCore(core).orElseThrow(); var c = s.citizens().iterator().next();
        var e = ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, c.id().toString());
        e.moveTo(h.absolutePos(new BlockPos(2, 2, 2)).getCenter()); e.setNoAi(true);
        player.moveTo(e.position()); h.assertTrue(h.getLevel().addFreshEntity(e), "Cannot insert projection");
        data.updateProjection(s, c, e.getUUID(), e.blockPosition()); CitizenProjectionService.syncName(c, e);
        var tag = new ItemStack(Items.NAME_TAG, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, tag);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(h.getLevel().getEntity(e.getUUID()) == e,
                "Projection not yet loaded")).thenExecute(() -> {
            assertions.accept(new Fixture(player, s, c, e, tag));
            CitizenNamingService.clear(player); e.discard(); s.deactivate(); data.touch();
        }).thenSucceed();
    }
    private static UUID open(Fixture f) {
        f.player.interactOn(f.entity, InteractionHand.MAIN_HAND);
        return CitizenNamingService.pendingToken(f.player);
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void namingUiBypassesVanillaAndConfirmsOnlyOnce(GameTestHelper h) {
        withFixture(h, 0, f -> {
            String old = f.citizen.name();
            f.tag.set(DataComponents.CUSTOM_NAME, Component.literal("铁砧名字"));
            UUID token = open(f);
            h.assertTrue(token != null && f.tag.getCount() == 2 && f.citizen.name().equals(old), "Opening consumed/renamed");
            h.assertTrue(f.entity.getCustomName().getString().endsWith("] " + old), "Vanilla erased profession before UI");
            h.assertTrue(CitizenNamingService.confirm(f.player, token, "新名字", false), "Confirmation failed");
            h.assertTrue(!CitizenNamingService.confirm(f.player, token, "重复请求", false), "Replay accepted");
            var reloaded = CitizenRecord.load(f.citizen.save(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            CitizenProjectionService.syncName(reloaded, f.entity);
            h.assertTrue(f.tag.getCount() == 1 && reloaded.name().equals("新名字")
                    && f.entity.getCustomName().getString().endsWith("] 新名字"), "Name/profession/inventory lost across save refresh");
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void blankTagCancelInvalidSwapDistanceAndStaleSession(GameTestHelper h) {
        withFixture(h, 1, f -> {
            String old = f.citizen.name(); UUID token = open(f);
            h.assertTrue(token != null, "Blank tag did not open UI"); CitizenNamingService.confirm(f.player, token, "", true);
            h.assertTrue(!CitizenNamingService.confirm(f.player, open(f), "\n", false), "Invalid name accepted");
            token = open(f); f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE));
            h.assertTrue(!CitizenNamingService.confirm(f.player, token, "偷改", false), "Missing tag accepted");
            f.player.setItemInHand(InteractionHand.MAIN_HAND, f.tag);
            token = open(f); UUID replacement = open(f);
            h.assertTrue(!CitizenNamingService.confirm(f.player, token, "旧窗口", false), "Replaced token accepted");
            f.player.moveTo(f.entity.position().add(20, 0, 0));
            h.assertTrue(!CitizenNamingService.confirm(f.player, replacement, "远程", false), "Out of range accepted");
            f.player.moveTo(f.entity.position()); token = open(f); f.settlement.deactivate();
            h.assertTrue(!CitizenNamingService.confirm(f.player, token, "已撤销", false), "Inactive settlement accepted");
            h.assertTrue(f.citizen.name().equals(old) && f.tag.getCount() == 2, "Rejected operation changed name/tag");
        });
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void namingRespectsProtectionAndCreativeOffhand(GameTestHelper h) {
        withFixture(h, 2, f -> {
            String old = f.citizen.name();
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract> protection = event -> {
                if (event.getTarget() == f.entity) { event.setCanceled(true); event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL); }
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, protection);
            try { h.assertTrue(open(f) == null, "Protected interaction opened session"); }
            finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(protection); }
            h.assertTrue(f.tag.getCount() == 2 && f.citizen.name().equals(old), "Protection bypassed");
            f.player.getAbilities().instabuild = true;
            f.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            f.player.setItemInHand(InteractionHand.OFF_HAND, f.tag);
            f.player.interactOn(f.entity, InteractionHand.OFF_HAND);
            h.assertTrue(CitizenNamingService.confirm(f.player, CitizenNamingService.pendingToken(f.player), "副手名字", false), "Offhand confirmation failed");
            h.assertTrue(f.tag.getCount() == 2 && f.entity.getCustomName().getString().endsWith("] 副手名字"), "Creative consumed tag/profession lost");
        });
    }
}

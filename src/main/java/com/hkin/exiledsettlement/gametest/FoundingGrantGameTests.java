package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.settlement.FoundingService;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("exiledsettlement_foundinggrant")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class FoundingGrantGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        if("founding_grant".equals(System.getProperty("exiledsettlement.testSuite")))event.register(FoundingGrantGameTests.class);
    }
    private static ServerPlayer fresh(GameTestHelper h,GameType mode) {
        var player=h.makeMockServerPlayerInLevel();player.setGameMode(mode);player.getInventory().clearContent();
        player.getPersistentData().remove(Player.PERSISTED_NBT_TAG);
        var root=player.getServer().getAdvancements().get(ResourceLocation.withDefaultNamespace("story/root"));
        h.assertTrue(root!=null,"Vanilla root advancement missing from fixture");
        for(var criterion:root.value().criteria().keySet())player.getAdvancements().revoke(root,criterion);
        h.assertTrue(!player.getAdvancements().getOrStartProgress(root).isDone(),"Fixture already has root advancement");
        player.tickCount=1;return player;
    }
    private static int tokens(ServerPlayer player) { return player.getInventory().countItem(ModItems.STARTER_CAMP_BLUEPRINT.get()); }
    private static void tick(ServerPlayer player) { NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player)); }
    private static void checkFirstGrant(GameTestHelper h,GameType mode) {
        var player=fresh(h,mode);tick(player);
        h.assertTrue(tokens(player)==1,"First player tick did not grant without advancements in "+mode);
        h.assertTrue(player.gameMode.getGameModeForPlayer()==mode,"Grant changed game mode");
        h.assertTrue(player.getInventory().countItem(ModItems.SETTLEMENT_CORE.get())==0
                && player.getInventory().countItem(ModItems.FOUNDING_SUPPLIES.get())==0
                && player.getInventory().countItem(Items.BARREL)==0,"Legacy items were granted");
        var saved=player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).copy();
        h.assertTrue(saved.getBoolean("ExiledFoundingIssued") && saved.getBoolean("ExiledFoundingCampKit"),"One-time entitlement missing");
        for(int i=2;i<=105;i++){player.tickCount=i;tick(player);}
        h.assertTrue(tokens(player)==1,"Repeated ticks duplicated kit");
        player.setGameMode(mode==GameType.CREATIVE?GameType.SURVIVAL:GameType.CREATIVE);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG,saved);player.tickCount=1;tick(player);
        h.assertTrue(tokens(player)==1,"Mode change or restored entitlement reissued kit");
        player.getInventory().clearContent();tick(player);
        h.assertTrue(tokens(player)==0,"Dropping blueprint automatically minted another");
        h.assertTrue(FoundingService.recover(player)&&tokens(player)==1,"Unspent blueprint recovery broken");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_foundinggrant",template="empty")
    public static void creativeGetsBlueprintOnFirstTickWithoutProgress(GameTestHelper h) { checkFirstGrant(h,GameType.CREATIVE); }
    @GameTest(templateNamespace="exiledsettlement_foundinggrant",template="empty")
    public static void survivalGetsBlueprintOnFirstTickWithoutProgress(GameTestHelper h) { checkFirstGrant(h,GameType.SURVIVAL); }
    @GameTest(templateNamespace="exiledsettlement_foundinggrant",template="empty")
    public static void oldAndRedeemedEntitlementsDoNotReceiveAnotherKit(GameTestHelper h) {
        var player=fresh(h,GameType.CREATIVE);
        var old=new CompoundTag();old.putBoolean("ExiledFoundingIssued",true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG,old);tick(player);
        h.assertTrue(tokens(player)==0 && !old.getBoolean("ExiledFoundingCampKit"),"Old entitlement silently replaced");
        h.assertTrue(FoundingService.recover(player) && player.getInventory().countItem(ModItems.FOUNDING_SUPPLIES.get())==1,"Legacy recovery broken");
        player.getInventory().clearContent();old.putBoolean("ExiledFoundingCampKit",true);old.putBoolean("ExiledFoundingRedeemed",true);tick(player);
        h.assertTrue(tokens(player)==0 && !FoundingService.recover(player),"Redeemed entitlement reissued");
        h.succeed();
    }
}

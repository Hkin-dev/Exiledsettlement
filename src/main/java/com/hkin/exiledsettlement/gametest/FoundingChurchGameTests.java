package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_founding")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class FoundingChurchGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        if ("founding_church".equals(System.getProperty("exiledsettlement.testSuite"))) event.register(FoundingChurchGameTests.class);
    }
    private static CompoundTag flags(ServerPlayer p) { return p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG); }
    private static int tokens(ServerPlayer p) { return p.getInventory().countItem(ModItems.STARTER_CAMP_BLUEPRINT.get()); }
    private static ServerPlayer owner(GameTestHelper h, BlockPos anchor) {
        var p=h.makeMockServerPlayerInLevel();p.getInventory().clearContent();
        // Mock clients skip negotiation; require actual registered codecs, then negotiate test channels.
        var channels=new HashMap<net.minecraft.resources.ResourceLocation,net.neoforged.neoforge.network.registration.NetworkChannel>();
        for(var id:List.of(com.hkin.exiledsettlement.network.StartPlacementPayload.TYPE.id(),com.hkin.exiledsettlement.network.ClearPlacementPayload.TYPE.id())) {
            h.assertTrue(net.neoforged.neoforge.network.registration.NetworkRegistry.getCodec(id,net.minecraft.network.ConnectionProtocol.PLAY,net.minecraft.network.protocol.PacketFlow.CLIENTBOUND)!=null,"Placement payload not registered");
            channels.put(id,new net.neoforged.neoforge.network.registration.NetworkChannel(id,"14"));
        }
        net.neoforged.neoforge.network.registration.ChannelAttributes.setPayloadSetup(p.connection.getConnection(),
                new net.neoforged.neoforge.network.registration.NetworkPayloadSetup(Map.of(net.minecraft.network.ConnectionProtocol.PLAY,channels)));
        p.moveTo(anchor.getX()+24.5,anchor.getY(),anchor.getZ()+.5,0,0);
        h.assertTrue(FoundingService.issueCamp(p),"First kit was not issued");return p;
    }
    private static BuildingBlueprintTemplate site(GameTestHelper h, BlockPos anchor, Direction facing) {
        var t=BlueprintTemplates.create(BuildingType.STARTER_CAMP,anchor,facing);var l=h.getLevel();
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-3,-1,-3),t.maxCorner().offset(3,2,3)))
            l.setBlock(p,p.getY()<=t.minCorner().getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),18);
        return t;
    }
    private static void deactivate(GameTestHelper h, SettlementRecord s) {
        for(var c:s.citizens()) {var e=CitizenProjectionService.findProjection(h.getLevel(),c);if(e!=null)e.discard();}
        s.deactivate();SettlementSavedData.get(h.getLevel()).touch();
    }
    private static void uneven(GameTestHelper h,BuildingBlueprintTemplate t,int kind) {
        int ground=t.minCorner().getY();
        for(var pos:BlockPos.betweenClosed(t.minCorner().offset(-1,-7,-1),t.maxCorner().offset(1,0,1))) {
            boolean bed=pos.getY()==ground-7;
            boolean slope=kind==0 && pos.getX()<t.minCorner().getX()+6 && pos.getY()<=ground+2;
            var state=bed?Blocks.STONE.defaultBlockState():slope?Blocks.DIRT.defaultBlockState():
                    kind==1 && pos.getY()<ground?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
            h.getLevel().setBlock(pos,state,18);
        }
        if(kind==3) {
            for(int y=1;y<=4;y++)h.getLevel().setBlock(t.anchor().above(y),Blocks.OAK_LOG.defaultBlockState(),18);
            h.getLevel().setBlock(t.anchor().above(4).east(),Blocks.OAK_LEAVES.defaultBlockState(),18);
        }
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void slopesWaterAirAndTreesReceiveContinuousFoundations(GameTestHelper h) {
        int kind=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            var a=h.absolutePos(new BlockPos(158000+kind*160,20,158000));var t=site(h,a,facing);
            uneven(h,t,kind);var p=owner(h,a);var l=h.getLevel();
            var plan=FoundingCampPlacementRules.plan(l,t);
            h.assertTrue(plan.valid(),"Terrain plan rejected kind="+kind+" "+plan.reason());
            var outside=t.minCorner().offset(-1,1,-1);l.setBlock(outside,Blocks.OAK_LOG.defaultBlockState(),18);
            var result=FoundingCampService.deploy(p,a,facing);
            h.assertTrue(result.success(),"Terrain camp failed kind="+kind+" "+result.reason());
            h.assertTrue(l.getBlockState(outside).is(Blocks.OAK_LOG),"Cleared outside footprint");
            for(var b:plan.placements())h.assertTrue(l.getBlockState(b.position()).getBlock()==b.state().getBlock(),"Plan differs from deployed camp "+b.position());
            var columns=new HashSet<BlockPos>();
            for(var b:t.placements())columns.add(new BlockPos(b.position().getX(),t.minCorner().getY(),b.position().getZ()));
            for(var top:columns)for(int y=top.getY();y>=top.getY()-7;y--) {
                var pos=new BlockPos(top.getX(),y,top.getZ());
                h.assertTrue(l.getBlockState(pos).isFaceSturdy(l,pos,Direction.UP),"Gap in foundation "+pos);
            }
            h.assertTrue(result.value().buildingAt(a).operational() && result.value().citizens().size()==6,"Terrain camp lost housing/residents");
            deactivate(h,result.value());kind++;
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void terrainProtectionRollsBackDeepFoundationAndWater(GameTestHelper h) {
        var a=h.absolutePos(new BlockPos(159000,20,159000));var t=site(h,a,Direction.SOUTH);uneven(h,t,1);
        var p=owner(h,a);var l=h.getLevel();var plan=FoundingCampPlacementRules.plan(l,t);
        h.assertTrue(plan.valid(),"Water fixture invalid");
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> before=new HashMap<>();
        for(var b:plan.placements())before.put(b.position(),l.getBlockState(b.position()));
        int[] changes={0};java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny=e->{if(before.containsKey(e.getPos()) && ++changes[0]>=300)e.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try{h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Foundation protection bypassed");}
        finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(changes[0]>=300,"Deep rollback not exercised");
        before.forEach((pos,state)->h.assertTrue(l.getBlockState(pos).equals(state),"Water/foundation rollback mismatch "+pos));
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.BreakEvent> denyBreak=e->{if(before.containsKey(e.getPos()))e.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(denyBreak);
        try{h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Clearance break protection bypassed");}
        finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(denyBreak);}
        before.forEach((pos,state)->h.assertTrue(l.getBlockState(pos).equals(state),"Break rejection changed terrain"));
        h.assertTrue(tokens(p)==1 && !flags(p).getBoolean("ExiledFoundingRedeemed"),"Failed terrain camp consumed entitlement");
        var result=FoundingCampService.deploy(p,a,Direction.SOUTH);h.assertTrue(result.success(),"Retry failed "+result.reason());deactivate(h,result.value());
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=400)
    public static void blueprintOnlyOncePreviewCancelAndRecovery(GameTestHelper h) {
        var a=h.absolutePos(new BlockPos(151000,12,151000));var p=owner(h,a);
        h.assertTrue(tokens(p)==1 && !FoundingService.issueCamp(p),"Kit duplicated");
        h.assertTrue(!p.getInventory().contains(new ItemStack(ModItems.SETTLEMENT_CORE.get()))
                && !p.getInventory().contains(new ItemStack(ModItems.FOUNDING_SUPPLIES.get()))
                && !p.getInventory().contains(new ItemStack(Items.BARREL)),"Old startup items still granted");
        int count=SettlementSavedData.get(h.getLevel()).settlements().size();
        h.assertTrue(FoundingService.beginCamp(p).success(),"Could not start without a core");
        h.assertTrue(PlacementSessionService.activeSession(p.getUUID())!=null,"Preview session missing");
        PlacementSessionService.cancel(p,true);
        h.assertTrue(tokens(p)==1 && SettlementSavedData.get(h.getLevel()).settlements().size()==count,"Preview/cancel created camp or consumed kit");
        p.getInventory().clearContent();h.assertTrue(FoundingService.recover(p)&&tokens(p)==1,"Lost blueprint cannot recover before core");
        FoundingService.recover(p);h.assertTrue(tokens(p)==1,"Recovery duplicated token in inventory");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void completeCampFourRotationsSupplyHousingAndReplay(GameTestHelper h) {
        for(var facing:Direction.Plane.HORIZONTAL) {
            var a=h.absolutePos(new BlockPos(152000+facing.ordinal()*160,12,152000));
            var t=site(h,a,facing);var p=owner(h,a);
            h.assertTrue(FoundingService.beginCamp(p).success(),"Preview refused");
            h.assertTrue(PlacementSessionService.activeSession(p.getUUID()).templateVersion()==4,"Preview uses obsolete camp");
            var placed=PlacementSessionService.place(p,a,facing);
            h.assertTrue(placed.success(),"Deploy failed "+facing+" "+placed.reason());
            var core=t.placements().stream().filter(b->b.state().is(ModBlocks.SETTLEMENT_CORE.get())).findFirst().orElseThrow().position();
            var s=SettlementSavedData.get(h.getLevel()).findByCore(core).orElseThrow();
            h.assertTrue(tokens(p)==0 && flags(p).getBoolean("ExiledFoundingRedeemed") && PlacementSessionService.activeSession(p.getUUID())==null,"Success did not consume exactly once/close preview");
            h.assertTrue(s.citizens().size()==6 && s.aliveCitizenCount()==6,"Wrong initial residents");
            for(var b:t.placements()) if(b.mode().isFinalStructure())
                h.assertTrue(h.getLevel().getBlockState(b.position()).getBlock()==b.state().getBlock(),"Missing camp block "+b);
            var camp=s.buildingAt(a);h.assertTrue(camp.operational() && camp.capacity()==7,"Camp beds not functional "+camp.status());
            h.assertTrue(s.citizens().stream().allMatch(c->camp.id().equals(c.homeBuildingId())),"Residents not assigned camp beds");
            for(var c:s.citizens()) h.assertTrue(h.getLevel().getBlockState(c.lastPosition()).isAir() && h.getLevel().getBlockState(c.lastPosition().above()).isAir(),"Resident spawned in structure");
            var storage=s.buildings().stream().filter(b->b.type()==BuildingType.WAGON).findFirst().orElseThrow();
            h.assertTrue(storage.templateVersion()==4 && storage.servicePositions().size()==15,"New camp must expose all fifteen public barrels");
            for(var publicBarrel:storage.servicePositions())h.assertTrue(WarehouseInventoryService.registeredPublicContainer(h.getLevel(),camp,publicBarrel),"Camp barrel was adopted as personal storage");
            BuildingFunctionService.validateSingle(h.getLevel(),SettlementSavedData.get(h.getLevel()),s,storage);
            h.assertTrue(storage.servicePositions().size()==15,"Function refresh lost public barrels");
            h.assertTrue(storage.servicePositions().stream().allMatch(pos->h.getLevel().getBlockState(pos).is(Blocks.BARREL)),"Camp furnace adopted as public storage");
            WarehouseInventoryService.stock(h.getLevel(),camp);
            h.assertTrue(WarehouseInventoryService.registeredPublicContainer(h.getLevel(),camp,storage.position()),"Public barrel lost ownership");
            var inventory=WarehouseInventoryService.stock(h.getLevel(),storage);
            h.assertTrue(inventory.summary().equals(FoundingCampSupplies.quantities()),"Initial supply mismatch");
            h.assertTrue(WarehouseStock.applies(storage) && storage.templateVersion()==4,"Camp bulk storage missing");
            h.assertTrue(WarehouseInventoryService.countItem(h.getLevel(),storage,Items.BREAD)==FoundingCampSupplies.BREAD,"Supplies not public");
            p.getInventory().add(new ItemStack(ModItems.STARTER_CAMP_BLUEPRINT.get()));
            h.assertTrue(!FoundingCampService.deploy(p,a.east(1),facing).success() && !FoundingService.recover(p),"Redeemed kit replayed");
            var saved=s.save(h.getLevel().registryAccess());var restored=SettlementRecord.load(saved,h.getLevel().registryAccess());
            h.assertTrue(restored.buildingAt(a).templateVersion()==4 && restored.citizens().size()==6,"Camp reload lost version/residents");
            var persisted=flags(p).copy();p.getPersistentData().put(Player.PERSISTED_NBT_TAG,persisted);
            h.assertTrue(!FoundingService.issueCamp(p) && !FoundingService.recover(p),"Reloaded entitlement reused");
            camp.queueDemolition();h.assertTrue(!BuildingDemolitionService.isRemovable(h.getLevel(),camp,core)
                    && !BuildingDemolitionService.remainingBlocks(h.getLevel(),camp,Set.of(),a).contains(core),"Camp demolition removes core");
            deactivate(h,s);
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void obstaclesTerritoryEntitiesAndLateProtectionLeaveNoPartialCamp(GameTestHelper h) {
        var a=h.absolutePos(new BlockPos(154000,12,154000));var t=site(h,a,Direction.SOUTH);var p=owner(h,a);var l=h.getLevel();
        var core=t.placements().stream().filter(b->b.state().is(ModBlocks.SETTLEMENT_CORE.get())).findFirst().orElseThrow().position();
        l.setBlock(a.above(),Blocks.CHEST.defaultBlockState(),18);
        h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success() && l.getBlockState(a.above()).is(Blocks.CHEST),"Obstacle was deleted");
        l.setBlock(a.above(),Blocks.AIR.defaultBlockState(),18);
        l.setBlock(a.above(),Blocks.BEDROCK.defaultBlockState(),18);
        h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Bedrock removed");l.setBlock(a.above(),Blocks.AIR.defaultBlockState(),18);
        var other=SettlementSavedData.get(l).createOrRecover(core.east(32),UUID.randomUUID(),"Protected neighbor").value();
        h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Overlapping settlement accepted");other.deactivate();
        var blocked=t.placements().stream().filter(b->!b.state().getCollisionShape(l,b.position()).isEmpty() && b.position().getY()==a.getY()).findFirst().orElseThrow().position();
        var animal=net.minecraft.world.entity.EntityType.COW.create(l);animal.moveTo(blocked.getX()+.5,blocked.getY(),blocked.getZ()+.5);l.addFreshEntity(animal);
        h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Animal enclosed by camp");animal.discard();
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> before=new HashMap<>();
        for(var b:FoundingCampPlacementRules.plan(l,t).placements())before.put(b.position(),l.getBlockState(b.position()));
        int[] changes={0};java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny=e->{if(before.containsKey(e.getPos()) && ++changes[0]>=80)e.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try {h.assertTrue(!FoundingCampService.deploy(p,a,Direction.SOUTH).success(),"Protection bypassed");}
        finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(changes[0]>=80,"Late rollback was not exercised");
        before.forEach((pos,state)->h.assertTrue(l.getBlockState(pos).equals(state),"Rollback left partial camp at "+pos));
        h.assertTrue(tokens(p)==1 && !flags(p).getBoolean("ExiledFoundingRedeemed") && SettlementSavedData.get(l).findByCore(core).isEmpty(),"Rejected camp consumed kit/registered core");
        var success=FoundingCampService.deploy(p,a,Direction.SOUTH);h.assertTrue(success.success(),"Retry after protection failed "+success.reason());deactivate(h,success.value());
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void churchFacilitiesFourRotationsAndResidentDoorClearance(GameTestHelper h) {
        for(var facing:Direction.Plane.HORIZONTAL) {
            var a=h.absolutePos(new BlockPos(155000+facing.ordinal()*48,12,155000));
            var t=BlueprintTemplates.create(BuildingType.CHURCH,a,facing);
            for(var b:t.placements())h.getLevel().setBlock(b.position(),b.state(),18);
            h.assertTrue(t.placements().stream().filter(b->b.state().is(Blocks.BREWING_STAND)).count()==2,"Church lost brewing facilities");
            h.assertTrue(t.placements().stream().filter(b->b.state().getBlock() instanceof BedBlock && b.state().getValue(BedBlock.PART)==BedPart.HEAD).count()==6,"Church lost patient beds");
            for(var entrance:t.entrances()) {
                var inside=entrance.position().relative(entrance.outward().getOpposite());
                var door=h.getLevel().getBlockState(entrance.position());
                if(door.getBlock() instanceof DoorBlock) {
                    h.getLevel().setBlock(entrance.position(),door.setValue(DoorBlock.OPEN,true),18);
                    var upper=entrance.position().above();h.getLevel().setBlock(upper,h.getLevel().getBlockState(upper).setValue(DoorBlock.OPEN,true),18);
                }
                var box=new net.minecraft.world.phys.AABB(inside.getX()+.2,inside.getY(),inside.getZ()+.2,inside.getX()+.8,inside.getY()+1.95,inside.getZ()+.8);
                h.assertTrue(h.getLevel().noCollision(box),"Resident hits church threshold/headroom "+facing);
            }
            var s=new SettlementRecord(UUID.randomUUID(),"Church fixture",a,UUID.randomUUID());var b=s.addBuilding(BuildingType.CHURCH,a,facing,t.minCorner(),t.maxCorner(),0,1);
            BuildingFunctionService.validateSingle(h.getLevel(),new SettlementSavedData(),s,b);
            h.assertTrue(!b.operational() && b.capacity()==0,"Church falsely treated as housing/healing");
            h.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(b.save())).placements().equals(t.placements()),"Church reload altered geometry");
        }
        var choices=Arrays.stream(com.hkin.exiledsettlement.menu.SettlementCoreMenu.BUILDING_CATALOG).map(e->e.type()).toList();
        h.assertTrue(choices.contains(BuildingType.CHURCH) && !choices.contains(BuildingType.STARTER_CAMP),"Church/camp catalog boundary wrong");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=200)
    public static void oldVoucherRemainsRedeemableWithoutNewKit(GameTestHelper h) {
        CommunityLifeGameTests.foundingVoucherRequiresEntitlementOwnershipAndCannotReplay(h);
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=600)
    public static void startupBudgetPaysNineProjectsAndSurvivesReload(GameTestHelper h) {
        var a=h.absolutePos(new BlockPos(160000,20,160000));site(h,a,Direction.SOUTH);var p=owner(h,a);var l=h.getLevel();
        var placed=FoundingCampService.deploy(p,a,Direction.SOUTH);h.assertTrue(placed.success(),"Supply camp failed");
        var s=placed.value();var store=s.buildings().stream().filter(b->b.type()==BuildingType.WAGON).findFirst().orElseThrow();
        var stock=WarehouseInventoryService.stock(l,store);var initial=new LinkedHashMap<>(stock.summary());
        h.assertTrue(stock.quantity()>27*64 && stock.quantity()<WarehouseStock.CAPACITY,"Starter budget does not use finite bulk storage");
        for(int i=0;i<stock.getContainerSize();i++)h.assertTrue(stock.getItem(i).getCount()<=stock.getItem(i).getMaxStackSize(),"Oversized physical stack");
        p.moveTo(store.position().getX()+.5,store.position().getY(),store.position().getZ()+1.5,0,0);
        var menu=new WarehouseDisplayMenu(7,p.getInventory(),p,s,store,store.position(),null);
        int shown=-1;
        for(int i=0;i<menu.displaySlots();i++)if(menu.displayedCount(i)>64){shown=i;break;}
        h.assertTrue(shown>=0 && menu.stillValid(p),"Camp barrel does not expose large real quantities");
        var shownItem=menu.getSlot(shown).getItem().getItem();int shownCount=initial.get(shownItem);
        h.assertTrue(menu.displayedCount(shown)==shownCount,"Camp display truncates total");
        menu.clicked(shown,0,net.minecraft.world.inventory.ClickType.PICKUP,p);
        int picked=menu.getCarried().getCount();
        h.assertTrue(picked==new ItemStack(shownItem).getMaxStackSize() && stock.summary().get(shownItem)==shownCount-picked,"UI withdrawal does not debit finite stock");
        menu.clicked(shown,0,net.minecraft.world.inventory.ClickType.PICKUP,p);
        h.assertTrue(menu.getCarried().isEmpty() && stock.summary().equals(initial),"UI return lost/duplicated startup items");
        var copied=SettlementRecord.load(s.save(l.registryAccess()),l.registryAccess());
        var restored=copied.building(store.id());
        h.assertTrue(WarehouseStock.applies(restored) && WarehouseInventoryService.stock(l,restored).summary().equals(initial),"Reload lost starter stock");
        h.assertTrue(restored.servicePositions().size()==15,"Reload lost public barrels");
        for(var barrel:store.servicePositions()) {
            p.moveTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()-1.5,0,0);
            var barrelMenu=new WarehouseDisplayMenu(8,p.getInventory(),p,s,store,barrel,null);
            h.assertTrue(barrelMenu.stillValid(p) && WarehouseInventoryService.registeredPublicContainer(l,s.buildingAt(a),barrel),"Second public barrel cannot be opened");
        }
        BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),s,store);
        h.assertTrue(store.capacity()==WarehouseStock.CAPACITY && stock.summary().equals(initial),"Function refresh changed stock/capacity");
        var requirements=new LinkedHashMap<Item,Integer>();
        for(var type:List.of(BuildingType.HOUSE,BuildingType.HOUSE,BuildingType.HOUSE_2,BuildingType.HOUSE_2,
                BuildingType.FARM_FIELD,BuildingType.MELON_FIELD,BuildingType.TREE_FARM,BuildingType.PASTURE_AREA,BuildingType.MINE)) {
            var t=type.isDynamicArea()?BlueprintTemplates.createArea(type,BlockPos.ZERO,new BlockPos(12,0,12),Direction.SOUTH):
                    BlueprintTemplates.create(type,BlockPos.ZERO,Direction.SOUTH);
            var bill=t.requirements();
            h.assertTrue(BlueprintMaterials.missing(l,s,p,bill).isEmpty(),"Cannot pay for "+type);
            BlueprintMaterials.consume(l,s,p,bill);
            bill.forEach((item,n)->requirements.merge(item,n,Integer::sum));
        }
        initial.forEach((item,n)->h.assertTrue(stock.summary().getOrDefault(item,0)==n-requirements.getOrDefault(item,0),"Budget did not debit real stock: "+item));
        for(var item:List.of(Items.WHEAT_SEEDS,Items.MELON_SEEDS,Items.PUMPKIN_SEEDS,Items.OAK_SAPLING,
                Items.IRON_PICKAXE,Items.IRON_HOE,Items.IRON_AXE,Items.IRON_SHOVEL,Items.BREAD,Items.CHARCOAL))
            h.assertTrue(stock.summary().getOrDefault(item,0)>0,"Startup operation supply missing "+item);
        var saved=stock.quantity();BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),s,store);
        h.assertTrue(!FoundingService.issueCamp(p) && !FoundingService.recover(p) && stock.quantity()==saved,"Refresh/reconnect refilled spent supplies");
        var legacy=BuildingRecord.create(BuildingType.WAGON,a.east(40),Direction.SOUTH,a.east(40),a.east(40),0,1);
        h.assertTrue(!WarehouseStock.applies(legacy),"Old physical wagon converted");
        var report=new com.google.gson.JsonObject();var materials=new com.google.gson.JsonObject();var supplied=new com.google.gson.JsonObject();
        requirements.forEach((item,n)->materials.addProperty(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(),n));
        initial.forEach((item,n)->supplied.addProperty(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(),n));
        report.add("construction",materials);report.add("supplied",supplied);report.addProperty("total",initial.values().stream().mapToInt(Integer::intValue).sum());
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FOUNDING_BUDGET_OK {}",report);
        deactivate(h,s);h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_founding",template="empty",timeoutTicks=1800)
    public static void campResidentsSpawnWalkFetchAndEatInitialFood(GameTestHelper h) {
        var a=h.absolutePos(new BlockPos(157000,12,157000));var t=site(h,a,Direction.SOUTH);var l=h.getLevel();
        uneven(h,t,1);
        for(int x=(t.minCorner().getX()>>4)-1;x<=(t.maxCorner().getX()>>4)+1;x++)
            for(int z=(t.minCorner().getZ()>>4)-1;z<=(t.maxCorner().getZ()>>4)+1;z++) l.setChunkForced(x,z,true);
        var p=owner(h,a);long started=System.nanoTime();long tick=l.getGameTime();
        var result=FoundingCampService.deploy(p,a,Direction.SOUTH);h.assertTrue(result.success(),"Physical camp failed "+result.reason());
        var s=result.value();s.autoRoads().enabled=false;s.community().foodReserve=0;l.setDayTime(6000);
        var c=s.citizens().iterator().next();c.adjustNeeds(-60,0,0);var initial=c.lastPosition();
        h.startSequence().thenWaitUntil(()->{
            l.setDayTime(6000);CitizenProjectionService.ensureSettlementCitizens(l,s);
            h.assertTrue(s.citizens().stream().allMatch(v->CitizenProjectionService.findProjection(l,v)!=null),"Waiting for all six residents to appear");
        }).thenWaitUntil(()->{
            l.setDayTime(6000);
            var actor=CitizenProjectionService.findProjection(l,c);
            h.assertTrue(c.nutrition()>=80,"Resident has not eaten: "+c.workState()+" "+c.workFailure()+" at "+actor.blockPosition());
            h.assertTrue(actor.blockPosition().distSqr(initial)>9,"Food bypassed walking to storage");
        }).thenExecute(()->{
            var storage=s.buildings().stream().filter(b->b.type()==BuildingType.WAGON).findFirst().orElseThrow();
            int remaining=WarehouseInventoryService.summarize(l,storage).entrySet().stream().filter(e->CommunityLifeService.food(new ItemStack(e.getKey()))).mapToInt(Map.Entry::getValue).sum();
            int carried=s.citizens().stream().mapToInt(CommunityLifeService::carriedFood).sum();
            h.assertTrue(remaining+carried+s.community().mealsEaten==FoundingCampSupplies.stacks().stream().filter(CommunityLifeService::food).mapToInt(ItemStack::getCount).sum(),"Initial food not conserved: stored="+remaining+" carried="+carried+" meals="+s.community().mealsEaten);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FOUNDING_PHYSICAL_OK residents={} foodStored={} foodCarried={} meals={} ticks={} wallSeconds={}",
                    s.citizens().size(),remaining,carried,s.community().mealsEaten,l.getGameTime()-tick,(System.nanoTime()-started)/1e9);
            var actor=CitizenProjectionService.findProjection(l,c);
            int before=WarehouseInventoryService.countItem(l,storage,Items.COBBLESTONE);
            var materials=WarehouseInventoryService.extractItemForResident(l,storage,actor,Items.COBBLESTONE,64);
            h.assertTrue(materials.getCount()==64 && c.insertIntoInventory(materials).isEmpty(),"Resident cannot take construction stock at camp barrel");
            var tool=WarehouseInventoryService.extractItemForResident(l,storage,actor,Items.IRON_PICKAXE,1);
            h.assertTrue(tool.getCount()==1 && c.insertIntoInventory(tool).isEmpty(),"Resident cannot take startup tool");
            h.assertTrue(WarehouseInventoryService.countItem(l,storage,Items.COBBLESTONE)==before-64,"Resident material withdrawal not debited");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FOUNDING_SUPPLY_ACCESS_OK cobblestone=64 ironPickaxe=1");
            deactivate(h,s);
        }).thenSucceed();
    }
}

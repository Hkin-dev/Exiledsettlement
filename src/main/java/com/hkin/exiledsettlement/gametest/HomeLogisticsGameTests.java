package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.region.PastureResidentService;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_home")
@PrefixGameTestTemplate(false)
public final class HomeLogisticsGameTests {
    private static int serial;
    private record Fixture(SettlementRecord s,BuildingRecord warehouse,BuildingRecord home,BlockPos at,SettlementCitizenEntity e) {}
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(120000+(serial++)*128,3,0));
        for(var p:BlockPos.betweenClosed(at.offset(-8,-1,-8),at.offset(25,4,8))) {
            l.getChunkAt(p);l.setBlock(p,p.getY()==at.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        }
        var s=new SettlementRecord(UUID.randomUUID(),"Home tests",at,UUID.randomUUID());
        var w=s.addBuilding(BuildingType.WAREHOUSE,at,Direction.NORTH,at.offset(-6,-1,-6),at.offset(6,4,6),0,7);
        var home=s.addBuilding(BuildingType.HOUSE,at.east(18),Direction.NORTH,at.offset(13,-1,-5),at.offset(23,4,5),0,7);
        w.updateFunction(true,BuildingStatus.OPERATIONAL,WarehouseStock.CAPACITY,List.of());home.updateFunction(true,BuildingStatus.OPERATIONAL,2,List.of(at.east(18)));
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);
        return new Fixture(s,w,home,at,e);
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void homeStockPreservesHousingComponentsAndPublicSeparation(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var named=new ItemStack(Items.IRON_AXE);named.set(DataComponents.CUSTOM_NAME,Component.literal("Family axe"));named.setDamageValue(17);
        h.assertTrue(WarehouseInventoryService.insert(l,f.home,named).isEmpty(),"House rejected real inventory");
        WarehouseInventoryService.insert(l,f.home,new ItemStack(Items.BREAD,32));
        h.assertTrue(f.home.capacity()==2 && f.home.workerCapacity()==2,"Storage inflated residential capacity");
        h.assertTrue(SettlementInventoryService.warehouses(f.s).size()==1 && SettlementInventoryService.countItem(l,f.s,Items.BREAD)==0,"Household food leaked into public production pool");
        var restored=BuildingRecord.load(f.home.save(l.registryAccess()));
        var stock=WarehouseInventoryService.stock(l,restored);
        h.assertTrue(stock.quantity()==33 && restored.capacity()==2,"Home inventory/capacity changed on reload");
        var tool=WarehouseInventoryService.extractItem(l,restored,Items.IRON_AXE,1);
        h.assertTrue(ItemStack.isSameItemSameComponents(tool,named),"Item components lost on save");
        h.assertTrue(stock.quantity()==32 && WarehouseInventoryService.countItem(l,restored,Items.IRON_AXE)==0,"Read index stale after extraction");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void transfersRequireInteriorAndWholeRoomIsUsable(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();WarehouseInventoryService.insert(l,f.warehouse,new ItemStack(Items.BREAD,16));
        f.e.moveTo(f.at.getX()-6.5,f.at.getY(),f.at.getZ()+.5,0,0);
        h.assertTrue(WarehouseInventoryService.extractItemForResident(l,f.warehouse,f.e,Items.BREAD,4).isEmpty(),"Door exterior allowed remote extraction");
        for(var delta:List.of(new BlockPos(-4,0,-4),new BlockPos(4,0,4))) {
            f.e.moveTo(f.at.getX()+delta.getX()+.5,f.at.getY(),f.at.getZ()+delta.getZ()+.5,0,0);
            h.assertTrue(WarehouseInventoryService.extractItemForResident(l,f.warehouse,f.e,Items.BREAD,4).getCount()==4,"Whole interior not usable");
        }
        h.assertTrue(WarehouseInventoryService.countItem(l,f.warehouse,Items.BREAD)==8,"Extraction changed wrong quantity");
        f.e.moveTo(f.at.getX()+18.5,f.at.getY(),f.at.getZ()+.5,0,0);
        h.assertTrue(WarehouseInventoryService.insertForResident(l,f.home,f.e,new ItemStack(Items.BREAD,8)).isEmpty(),"House interior transfer failed");
        h.assertTrue(WarehouseInventoryService.countItem(l,f.home,Items.BREAD)==8,"Delivered goods missing");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void openDoorThresholdIsNotAnIndoorTransferStation(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var door=f.at.east(4);
        l.setBlock(door,Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.OPEN,true),2);
        f.e.moveTo(door.getX()+.5,door.getY(),door.getZ()+.5,0,0);
        WarehouseInventoryService.insert(l,f.warehouse,new ItemStack(Items.BREAD,8));
        h.assertTrue(!WarehouseInventoryService.canInteract(l,f.warehouse,f.e)
                && WarehouseInventoryService.extractItemForResident(l,f.warehouse,f.e,Items.BREAD,1).isEmpty(),"Resident transferred while still in door threshold");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void savedDeliveryResumesWithRealCarriedQuantityAndCancelsSafely(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var c=f.s.addMigrant();c.setManualAssignment(CitizenProfession.WAREHOUSE_KEEPER,f.warehouse.id());c.assignHome(f.home.id());
        f.s.community().stewardEnabled=true;WarehouseInventoryService.insert(l,f.warehouse,new ItemStack(Items.BREAD,16));
        h.assertTrue(HomeDeliveryService.assign(l,f.s,c,f.warehouse),"No household delivery assigned");
        HomeDeliveryService.work(l,SettlementSavedData.get(l),f.s,c,f.e);
        h.assertTrue(c.life().cookingStage==1 && WarehouseInventoryService.countItem(l,f.warehouse,Items.BREAD)==0,"First leg did not take real goods");
        var saved=c.life().save();c.life().clearTask();c.life().load(saved);
        h.assertTrue(f.home.id().equals(c.life().deliveryHome) && c.life().cookingOutputCount==16,"Delivery target/count not persisted");
        f.e.moveTo(f.at.getX()+18.5,f.at.getY(),f.at.getZ()+.5,0,0);
        HomeDeliveryService.work(l,SettlementSavedData.get(l),f.s,c,f.e);
        h.assertTrue(WarehouseInventoryService.countItem(l,f.home,Items.BREAD)==16 && c.inventory().stream().noneMatch(i->i.is(Items.BREAD)),"Second leg duplicated or lost inventory");
        c.life().load(saved);c.insertIntoInventory(new ItemStack(Items.BREAD,7));c.assignProfession(CitizenProfession.WORKER,null);
        HomeDeliveryService.work(l,SettlementSavedData.get(l),f.s,c,f.e);
        h.assertTrue(c.life().task.isEmpty() && c.inventory().stream().filter(i->i.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum()==7,"Canceled job deleted carried goods");
        WarehouseInventoryService.extractItem(l,f.home,Items.BREAD,16);c.setManualAssignment(CitizenProfession.WAREHOUSE_KEEPER,f.warehouse.id());
        h.assertTrue(HomeDeliveryService.assign(l,f.s,c,f.warehouse) && c.life().cookingStage==1 && c.life().cookingOutputCount==7,"Retry did not reuse carried goods before fetching another load");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void stockReadIndexAvoidsRepeatedLargeInventoryWalks(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();WarehouseInventoryService.insert(l,f.warehouse,new ItemStack(Items.DIRT,190000));
        var stock=WarehouseInventoryService.stock(l,f.warehouse);var first=stock.summary();long start=System.nanoTime();long total=0;
        for(int i=0;i<10000;i++){total+=WarehouseInventoryService.countItem(l,f.warehouse,Items.DIRT);h.assertTrue(first==stock.summary(),"Read rebuilt unchanged inventory index");}
        long cached=System.nanoTime()-start;start=System.nanoTime();long raw=0;
        for(int i=0;i<10000;i++)for(int slot=0;slot<stock.getContainerSize();slot++)if(stock.getItem(slot).is(Items.DIRT))raw+=stock.getItem(slot).getCount();
        long scan=System.nanoTime()-start;
        h.assertTrue(total==raw && total==1900000000L,"Large-stock count changed");
        WarehouseInventoryService.extractItem(l,f.warehouse,Items.DIRT,10);
        h.assertTrue(WarehouseInventoryService.countItem(l,f.warehouse,Items.DIRT)==189990 && first!=stock.summary(),"Mutation failed to invalidate count cache");
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("HOME_STOCK_BENCH reads=10000 items=190000 cachedMs={} scanMs={}",cached/1e6,scan/1e6);h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void pastureControllerIsReversibleAndDoesNotDisablePhysics(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var b=f.s.addBuilding(BuildingType.PASTURE_AREA,f.at,Direction.NORTH,f.at.offset(-6,-1,-6),f.at.offset(6,3,6),0,1);
        b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of());var a=EntityType.SHEEP.create(l);a.moveTo(f.at.getX()+.5,f.at.getY(),f.at.getZ()+.5,0,0);
        int before=a.goalSelector.getAvailableGoals().size();PastureResidentService.capture(l,f.s,b,a);
        h.assertTrue(!PastureResidentService.controls(a) && PastureResidentService.ownedBy(f.s,b,a)
                && !a.isNoAi() && !a.isNoGravity(),"Inside pasture monopolized native grazing/mating AI");
        h.assertTrue(a.goalSelector.getAvailableGoals().size()==before+1,"Takeover replaced original AI");
        PastureResidentService.release(a);
        h.assertTrue(!PastureResidentService.controls(a) && a.goalSelector.getAvailableGoals().size()==before,"Original AI not restored");h.succeed();
    }
}

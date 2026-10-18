package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Opted-in isolated checks. Fixture edits and detached entities are not resident live acceptance. */
public final class Warehouse75Checks {
    private Warehouse75Checks() {}
    private static void check(boolean ok,String name,List<String> results){if(!ok)throw new IllegalStateException(name);results.add(name);}
    public static String run(ServerLevel level) {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var origin=new BlockPos(28000,250,28000);var saved=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();var results=new ArrayList<String>();
        var entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        try {
            for(var raw:BlockPos.betweenClosed(origin.offset(-5,-1,-5),origin.offset(5,4,5))) {
                var p=raw.immutable();var state=level.getBlockState(p);if(!state.isAir())throw new IllegalStateException("Requires unused air");saved.put(p,state);
            }
            for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            var a=origin.offset(-4,0,-3);var b=origin.offset(4,0,-3);level.setBlock(a,Blocks.BARREL.defaultBlockState(),2);level.setBlock(b,Blocks.BARREL.defaultBlockState(),2);
            var first=(Container)level.getBlockEntity(a);var second=(Container)level.getBlockEntity(b);
            for(int i=0;i<first.getContainerSize();i++)first.setItem(i,new ItemStack(Items.DIRT,64));
            second.setItem(0,new ItemStack(Items.APPLE,7));second.setItem(1,new ItemStack(Items.APPLE,11));
            var named=new ItemStack(Items.APPLE,5);named.set(DataComponents.CUSTOM_NAME,Component.literal("Original components"));second.setItem(2,named.copy());
            var owner=UUID.randomUUID();var s=new SettlementRecord(UUID.randomUUID(),"Detached warehouse75",origin,owner);
            var warehouse=s.addBuilding(BuildingType.WAREHOUSE,origin,Direction.NORTH,origin.offset(-5,-1,-5),origin.offset(5,4,5),2,7);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,54,List.of(a,b));
            check(WarehouseInventoryService.totalSlots(level,warehouse)==200000,"capacity_200000_items",results);
            check(WarehouseInventoryService.countItem(level,warehouse,Items.DIRT)==1728 && WarehouseInventoryService.countItem(level,warehouse,Items.APPLE)==23,"old_boxes_migrate_exactly",results);
            check(first.isEmpty() && second.isEmpty(),"physical_boxes_contain_no_projection_copies",results);
            check(WarehouseInventoryService.usedSlots(level,warehouse)==1751,"quantity_counts_individual_items",results);
            var restored=BuildingRecord.load(warehouse.save(level.registryAccess()));
            check(WarehouseInventoryService.countItem(level,restored,Items.APPLE)==23,"reload_no_duplicate_import",results);
            var ledger=WarehouseInventoryService.stock(level,restored);
            check(java.util.stream.IntStream.range(0,ledger.getContainerSize()).mapToObj(ledger::getItem).anyMatch(i->ItemStack.isSameItemSameComponents(i,named) && i.getCount()==5),"migration_and_reload_preserve_components",results);
            entity.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
            check(WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.SNOWBALL,16)).isEmpty(),"interior_deposit_without_box_proximity",results);
            entity.setPos(origin.getX()+3.5,origin.getY(),origin.getZ()+3.5);
            check(WarehouseInventoryService.extractItemForResident(level,warehouse,entity,Items.APPLE,1).getCount()==1,"different_interior_position_withdraws",results);
            entity.setPos(origin.getX()+5.5,origin.getY(),origin.getZ()+.5);
            check(WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.APPLE,7)).getCount()==7,"outside_wall_deposit_rejected",results);
            check(WarehouseInventoryService.extractItemForResident(level,warehouse,entity,Items.APPLE,7).isEmpty(),"outside_wall_withdraw_rejected",results);
            entity.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,54,List.of());
            check(WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.IRON_AXE,2)).isEmpty(),"storage_works_without_any_display_box",results);
            check(warehouse.workerCapacity()==1,"keeper_capacity_belongs_to_building",results);
            check(warehouse.accepts(new ItemStack(Items.APPLE)) && !warehouse.toggleWarehouseCategory(WarehouseCategory.FOOD),"old_category_rejection_removed",results);
            s.community().wheatPaused=true;check(!WarehouseStewardService.pauseWheat(level,s),"old_grain_limit_no_longer_stops_sowing",results);
            int before=WarehouseInventoryService.stock(level,warehouse).quantity();
            var remainder=WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.DIRT,200001));
            check(WarehouseInventoryService.stock(level,warehouse).quantity()==200000 && remainder.getCount()==200001-(200000-before),"capacity_boundary_returns_exact_remainder",results);
            check(!WarehouseInventoryService.canAccept(level,warehouse,new ItemStack(Items.IRON_AXE)),"real_capacity_blocks_only_when_full",results);
            WarehouseInventoryService.extractItem(level,warehouse,Items.DIRT,10);
            check(WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.IRON_AXE,11)).getCount()==1,"unstackable_tools_count_one_per_item",results);
            WarehouseInventoryService.stock(level,warehouse).clearContent();
            WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.SNOWBALL,96));
            var keeper=s.addMigrant();keeper.setManualAssignment(CitizenProfession.WAREHOUSE_KEEPER,warehouse.id());keeper.updateProjection(null,origin);
            check(WarehouseStewardService.assign(level,s,keeper) && keeper.life().destination.equals(warehouse.id()),"keeper_assigns_only_bound_warehouse",results);
            WarehouseStewardService.work(level,SettlementSavedData.get(level),s,keeper,entity);
            check(keeper.inventory().stream().filter(i->i.is(Items.SNOWBALL)).mapToInt(ItemStack::getCount).sum()==16,"keeper_fetches_real_batch",results);
            check(WarehouseInventoryService.insertForResident(level,warehouse,entity,new ItemStack(Items.SNOWBALL,16)).isEmpty(),"concurrent_garbage_deposit_not_rejected",results);
            for(int i=0;i<30 && WarehouseStewardService.handles(keeper);i++)WarehouseStewardService.work(level,SettlementSavedData.get(level),s,keeper,entity);
            check(!WarehouseStewardService.handles(keeper) && WarehouseInventoryService.countItem(level,warehouse,Items.SNOWBALL)==96,"garbage_batch_conserves_deposit_and_disposal",results);
            // A changed policy must preserve a batch that is already physically carried.
            keeper.insertIntoInventory(WarehouseInventoryService.extractItemForResident(level,warehouse,entity,Items.SNOWBALL,16));
            var life=keeper.life();life.task="steward:discard";life.destination=warehouse.id();life.taskStarted=level.getGameTime();life.cookingStage=1;
            life.cookingInput="minecraft:snowball";life.cookingOutput="minecraft:snowball";life.cookingInputCount=16;life.cookingOutputCount=16;life.workTicks=96;
            WarehouseInventoryService.stock(level,warehouse).rule("minecraft:snowball",96,true);
            WarehouseStewardService.work(level,SettlementSavedData.get(level),s,keeper,entity);
            check(life.cookingStage==2 && keeper.inventory().stream().filter(i->i.is(Items.SNOWBALL)).mapToInt(ItemStack::getCount).sum()==16,"raised_reserve_preserves_carried_batch",results);
            WarehouseStewardService.work(level,SettlementSavedData.get(level),s,keeper,entity);
            check(WarehouseInventoryService.countItem(level,warehouse,Items.SNOWBALL)==96 && !WarehouseStewardService.handles(keeper),"cancelled_disposal_returns_batch_to_stock",results);
            var other=s.addBuilding(BuildingType.WAREHOUSE,origin.east(32),Direction.NORTH,origin.offset(28,-1,-5),origin.offset(37,4,5),2,7);
            other.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of());WarehouseInventoryService.insert(level,other,new ItemStack(Items.APPLE,9));
            var combined=WarehouseManagementService.snapshot(level,s,-1,0,"","test");
            check(combined.warehouses()==2 && combined.used()==105 && combined.capacity()==400000,"panel_aggregates_all_warehouses",results);
            WarehouseInventoryService.stock(level,warehouse).rule("minecraft:snowball",12,false);
            var afterLoad=BuildingRecord.load(warehouse.save(level.registryAccess()));
            check(!WarehouseInventoryService.stock(level,afterLoad).rule("minecraft:snowball").discard() && WarehouseInventoryService.stock(level,afterLoad).rule("minecraft:snowball").keep()==12,"treatment_rule_persists",results);
            WarehouseInventoryService.stock(level,other).rule("minecraft:apple",0,true);
            var bound=s.addMigrant();bound.setManualAssignment(CitizenProfession.WAREHOUSE_KEEPER,warehouse.id());
            check(!WarehouseStewardService.assign(level,s,bound),"keeper_does_not_take_another_warehouse_surplus",results);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of(a));
            var player=new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(owner,"WarehouseTest"),ClientInformation.createDefault());player.setPos(a.getX()+1.5,a.getY(),a.getZ()+.5);
            var menu=new WarehouseDisplayMenu(1,player.getInventory(),player,s,warehouse,a,WarehouseCategory.MATERIALS);
            int quantity=WarehouseInventoryService.stock(level,warehouse).quantity();
            menu.clicked(0,0,ClickType.PICKUP,player);
            check(menu.getCarried().is(Items.SNOWBALL) && WarehouseInventoryService.stock(level,warehouse).quantity()+menu.getCarried().getCount()==quantity,"display_pickup_debits_exact_stock",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            check(menu.getCarried().isEmpty() && WarehouseInventoryService.stock(level,warehouse).quantity()==quantity,"display_deposit_returns_exact_stock",results);
            menu.clicked(0,0,ClickType.CLONE,player);menu.clicked(0,0,ClickType.PICKUP_ALL,player);menu.clicked(0,0,ClickType.SWAP,player);
            check(menu.getCarried().isEmpty() && WarehouseInventoryService.stock(level,warehouse).quantity()==quantity,"projection_cannot_clone_or_vanilla_collect",results);
            var taken=menu.quickMoveStack(player,0);
            check(taken.getCount()>0 && WarehouseInventoryService.stock(level,warehouse).quantity()+player.getInventory().items.stream().mapToInt(ItemStack::getCount).sum()==quantity,"display_shift_withdraw_conserves_items",results);
            WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.DIRT,200000));player.getInventory().setItem(9,new ItemStack(Items.APPLE,1));
            menu.quickMoveStack(player,WarehouseDisplayMenu.PAGE_SIZE);
            check(WarehouseInventoryService.stock(level,warehouse).quantity()==200000 && player.getInventory().getItem(9).getCount()==1,"full_projection_deposit_retains_player_item",results);
            // UI regression: exact counters are separate from legal, component-preserving icons.
            var stock=WarehouseInventoryService.stock(level,warehouse);stock.clearContent();
            stock.insert(new ItemStack(Items.DIRT,200000));menu.broadcastChanges();
            check(menu.displayedCount(0)==200000 && menu.getSlot(0).getItem().getCount()==1,"display_exact_200000_with_safe_icon",results);
            var clientMenu=new WarehouseDisplayMenu(1,player.getInventory(),(net.minecraft.network.RegistryFriendlyByteBuf)null);
            menu.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
                public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu m,NonNullList<ItemStack> items,ItemStack carried,int[] data) {
                    for(int i=0;i<data.length;i++)clientMenu.setData(i,(short)data[i]);
                }
                public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu m,int slot,ItemStack stack) {}
                public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu m,ItemStack stack) {}
                public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu m,int id,int value) {clientMenu.setData(id,(short)value);}
            });
            menu.broadcastChanges();
            check(clientMenu.displayedCount(0)==200000,"counter_survives_vanilla_signed_short_wire",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            check(menu.getCarried().getCount()==64 && stock.quantity()==199936 && menu.displayedCount(0)==199936,"large_count_pickup_is_one_real_stack",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            menu.clicked(0,1,ClickType.PICKUP,player);
            check(menu.getCarried().getCount()==32 && stock.quantity()==199968,"large_count_right_click_is_half_stack",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            stock.clearContent();stock.insert(new ItemStack(Items.DIRT,100000));menu.broadcastChanges();
            check(clientMenu.displayedCount(0)==100000,"counter_low_word_above_32767_is_unsigned",results);
            stock.clearContent();stock.insert(new ItemStack(Items.SNOWBALL,96));menu.broadcastChanges();
            check(menu.displayedCount(0)==96,"sixteen_stack_item_shows_actual_total",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            check(menu.getCarried().getCount()==16 && stock.quantity()==80,"sixteen_stack_withdraw_stays_legal",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            stock.clearContent();stock.insert(new ItemStack(Items.IRON_AXE,7));
            var namedDisplay=new ItemStack(Items.IRON_AXE);namedDisplay.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Named axe"));
            stock.insert(namedDisplay);menu.broadcastChanges();
            check(menu.displayedCount(0)==7 && menu.displayedCount(1)==1,"component_variants_not_merged",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            check(menu.getCarried().getCount()==1 && stock.quantity()==7,"unstackable_withdraw_stays_one",results);
            menu.clicked(0,0,ClickType.PICKUP,player);
            stock.clearContent();
            var many=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR).limit(65).toList();
            for(var item:many)stock.insert(new ItemStack(item,2));menu.broadcastChanges();
            check(java.util.stream.IntStream.range(0,27).allMatch(i->menu.getSlot(i).hasItem()),"classic_box_keeps_27_slots",results);
            check(!menu.clickMenuButton(player,1) && !menu.clickMenuButton(player,10),"classic_box_has_no_page_or_category_controls",results);
            check(stock.quantity()==130,"undisplayed_variants_remain_in_stock",results);
            check(menu.getSlot(0).x==8 && menu.getSlot(26).x==152 && menu.getSlot(26).y==54
                    && menu.getSlot(27).y==85 && menu.getSlot(54).y==143,"vanilla_chest_slot_layout",results);
            stock.clearContent();stock.insert(new ItemStack(Items.BREAD,80));stock.insert(new ItemStack(Items.WHEAT,100));
            stock.insert(new ItemStack(Items.WHEAT_SEEDS,90));stock.insert(new ItemStack(Items.OAK_LOG,120));stock.insert(new ItemStack(Items.COAL,130));
            stock.insert(new ItemStack(Items.RAW_IRON,140));stock.insert(new ItemStack(Items.COBBLESTONE,150));
            stock.insert(new ItemStack(Items.OAK_SAPLING,160));stock.insert(new ItemStack(Items.IRON_AXE));stock.insert(new ItemStack(Items.IRON_CHESTPLATE));
            var expected=java.util.Map.of(Items.BREAD,WarehouseDisplayCategory.FOOD,Items.WHEAT,WarehouseDisplayCategory.INGREDIENTS,
                    Items.WHEAT_SEEDS,WarehouseDisplayCategory.SEEDS,Items.OAK_LOG,WarehouseDisplayCategory.WOOD,
                    Items.COAL,WarehouseDisplayCategory.FUEL,Items.RAW_IRON,WarehouseDisplayCategory.ORES,
                    Items.COBBLESTONE,WarehouseDisplayCategory.STONE,Items.OAK_SAPLING,WarehouseDisplayCategory.PLANTS,
                    Items.IRON_AXE,WarehouseDisplayCategory.TOOLS,Items.IRON_CHESTPLATE,WarehouseDisplayCategory.EQUIPMENT);
            for(var entry:expected.entrySet()) {
                check(WarehouseDisplayCategory.classify(new ItemStack(entry.getKey()))==entry.getValue(),"category_"+entry.getValue().id(),results);
                var panel=WarehouseManagementService.snapshot(level,s,-1,0,entry.getValue().filter(),"test");
                check(panel.rows().stream().anyMatch(r->r.sample().is(entry.getKey()))
                        && panel.rows().stream().allMatch(r->WarehouseDisplayCategory.classify(r.sample())==entry.getValue()),"management_"+entry.getValue().id(),results);
            }
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of(b,a));
            var boxA=new WarehouseDisplayMenu(2,player.getInventory(),player,s,warehouse,a,null);
            var boxB=new WarehouseDisplayMenu(3,player.getInventory(),player,s,warehouse,b,null);
            var inA=new HashSet<Item>();var inB=new HashSet<Item>();
            for(int i=0;i<27;i++) {
                if(boxA.getSlot(i).hasItem())inA.add(boxA.getSlot(i).getItem().getItem());
                if(boxB.getSlot(i).hasItem())inB.add(boxB.getSlot(i).getItem().getItem());
            }
            check(!inA.isEmpty() && !inB.isEmpty() && Collections.disjoint(inA,inB)
                    && inA.size()+inB.size()==10,"physical_boxes_display_different_categories",results);
            check(inA.stream().allMatch(item->boxA.displayedCategories().contains(WarehouseDisplayCategory.classify(new ItemStack(item))))
                    && inB.stream().allMatch(item->boxB.displayedCategories().contains(WarehouseDisplayCategory.classify(new ItemStack(item)))),
                    "category_labels_match_physical_box_contents",results);
            check(!boxA.displayedCategories().equals(boxB.displayedCategories()),"different_boxes_have_different_category_labels",results);
            var labelClient=new WarehouseDisplayMenu(2,player.getInventory(),(net.minecraft.network.RegistryFriendlyByteBuf)null);
            boxA.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
                public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu m,NonNullList<ItemStack> items,ItemStack carried,int[] data) {
                    for(int i=0;i<data.length;i++)labelClient.setData(i,(short)data[i]);
                }
                public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu m,int slot,ItemStack stack) {}
                public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu m,ItemStack stack) {}
                public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu m,int id,int value) {labelClient.setData(id,(short)value);}
            });
            check(labelClient.displayedCategories().equals(boxA.displayedCategories()),"category_labels_sync_to_client",results);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of(a,b));
            var reopened=new WarehouseDisplayMenu(4,player.getInventory(),player,s,warehouse,a,null);
            check(java.util.stream.IntStream.range(0,27).allMatch(i->ItemStack.isSameItemSameComponents(
                    boxA.getSlot(i).getItem(),reopened.getSlot(i).getItem())),"box_assignment_independent_of_service_list_order",results);
            var half=a.east();
            level.setBlock(a,Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.ChestType.LEFT),2);
            level.setBlock(half,Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of(a,half,b));
            var leftHalf=new WarehouseDisplayMenu(5,player.getInventory(),player,s,warehouse,a,null);
            var rightHalf=new WarehouseDisplayMenu(6,player.getInventory(),player,s,warehouse,half,null);
            check(java.util.stream.IntStream.range(0,27).allMatch(i->ItemStack.isSameItemSameComponents(
                    leftHalf.getSlot(i).getItem(),rightHalf.getSlot(i).getItem())),"double_chest_halves_share_display",results);
            check(leftHalf.displaySlots()==54 && rightHalf.displaySlots()==54 && menu.displaySlots()==27,
                    "large_chest_54_small_box_27",results);
            check(leftHalf.slots.size()==90 && leftHalf.getSlot(53).y==108 && leftHalf.getSlot(54).y==139
                    && leftHalf.getSlot(81).y==197,"large_chest_vanilla_layout_and_inventory_offset",results);
            warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of(a,half));
            stock.clearContent();
            for(int i=0;i<60;i++) {
                var variant=new ItemStack(Items.DIRT,2000);variant.set(DataComponents.CUSTOM_NAME,Component.literal("Variant "+i));stock.insert(variant);
            }
            var large=new WarehouseDisplayMenu(7,player.getInventory(),player,s,warehouse,a,null);
            check(java.util.stream.IntStream.range(0,54).allMatch(i->large.getSlot(i).hasItem() && large.displayedCount(i)==2000)
                    && stock.quantity()==120000,"large_chest_displays_all_54_slots_without_losing_hidden_stock",results);
            var wire=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
            wire.writeVarInt(WarehouseDisplayService.displayRows(level,a));
            var largeClient=new WarehouseDisplayMenu(7,player.getInventory(),wire);wire.release();
            large.setSynchronizer(new net.minecraft.world.inventory.ContainerSynchronizer() {
                public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu m,NonNullList<ItemStack> items,ItemStack carried,int[] data) {
                    for(int i=0;i<items.size();i++)largeClient.getSlot(i).set(items.get(i).copy());
                    for(int i=0;i<data.length;i++)largeClient.setData(i,(short)data[i]);
                }
                public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu m,int slot,ItemStack stack) {largeClient.getSlot(slot).set(stack.copy());}
                public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu m,ItemStack stack) {}
                public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu m,int id,int value) {largeClient.setData(id,(short)value);}
            });
            check(largeClient.rows()==6 && largeClient.slots.size()==90 && largeClient.displayedCount(53)==2000
                    && largeClient.getSlot(53).hasItem(),"large_chest_rows_icons_and_counts_sync_to_client",results);
            check(largeClient.displayedCategories().equals(List.of(WarehouseDisplayCategory.ALL)),"single_double_chest_label_is_all",results);
            large.clicked(53,0,ClickType.PICKUP,player);
            check(large.getCarried().getCount()==64 && stock.quantity()==119936 && large.displayedCount(53)==1936,
                    "large_chest_last_slot_pickup_debits_stock",results);
            large.clicked(53,0,ClickType.PICKUP,player);
            check(large.getCarried().isEmpty() && stock.quantity()==120000,"large_chest_last_slot_deposit_conserves_stock",results);
            player.getInventory().setItem(9,new ItemStack(Items.APPLE,3));
            large.quickMoveStack(player,54);
            check(player.getInventory().getItem(9).isEmpty() && WarehouseInventoryService.countItem(level,warehouse,Items.APPLE)==3,
                    "large_chest_shift_deposit_uses_dynamic_inventory_offset",results);
            int heldBefore=player.getInventory().items.stream().filter(st->st.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum();
            large.quickMoveStack(player,53);
            check(player.getInventory().items.stream().filter(st->st.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==heldBefore+64
                    && large.displayedCount(53)==1936,"large_chest_last_slot_shift_withdraw_conserves_items",results);
            stock.clearContent();
            long previousDay=level.getDayTime();
            try {
                level.setDayTime(6000);entity.setPos(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
                var c=s.addMigrant();c.clearWork();c.updateProjection(null,origin);
                var coat=com.hkin.exiledsettlement.registry.ModItems.WOOL_COAT.get();
                c.thermal().garment=new ItemStack(coat);c.thermal().ambient=22;c.thermal().body=37;
                c.thermal().lastSwap=level.getGameTime()-601;
                CitizenClothingService.personal(level,SettlementSavedData.get(level),s,c,entity);
                int oldSlot=c.findInventorySlot(CitizenClothingService::isWornGarment);
                check(c.thermal().garment.isEmpty() && oldSlot>=0 && c.inventoryItem(oldSlot).getDamageValue()==0,
                        "freshly_worn_undamaged_coat_retained_when_removed",results);
                check(CitizenEquipmentService.deliverableCount(c,oldSlot)==0,"worn_coat_excluded_from_cargo",results);
                var reloaded=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());
                check(reloaded.hasInventoryItem(CitizenClothingService::isWornGarment)
                        && CitizenEquipmentService.deliverableCount(reloaded,oldSlot)==0,"worn_marker_survives_resident_save",results);
                c.thermal().ambient=-10;c.thermal().lastSwap=level.getGameTime()-601;
                CitizenClothingService.personal(level,SettlementSavedData.get(level),s,c,entity);
                check(c.thermal().garment.is(coat) && CitizenClothingService.isWornGarment(c.thermal().garment)
                        && !c.hasInventoryItem(CitizenClothingService::isGarment),"resident_reuses_carried_coat",results);
                c.thermal().ambient=22;c.thermal().lastSwap=level.getGameTime()-601;
                CitizenClothingService.personal(level,SettlementSavedData.get(level),s,c,entity);
                var oldDamaged=new ItemStack(com.hkin.exiledsettlement.registry.ModItems.LEATHER_COAT.get());oldDamaged.setDamageValue(1);
                c.insertIntoInventory(oldDamaged);
                int damagedSlot=c.findInventorySlot(st->st.is(oldDamaged.getItem()));
                check(CitizenEquipmentService.deliverableCount(c,damagedSlot)==0,"legacy_damaged_coat_retained_without_marker",results);
                c.insertIntoInventory(new ItemStack(coat));
                int freshSlot=c.findInventorySlot(st->CitizenClothingService.isGarment(st) && !CitizenClothingService.isWornGarment(st));
                check(CitizenEquipmentService.deliverableCount(c,freshSlot)==1,"unworn_coat_remains_deliverable",results);
                c.life().task="tailor:deliver";int beforeCoats=WarehouseInventoryService.countItem(level,warehouse,coat);
                CitizenClothingService.personal(level,SettlementSavedData.get(level),s,c,entity);
                check(WarehouseInventoryService.countItem(level,warehouse,coat)==beforeCoats+1
                        && c.inventory().stream().filter(CitizenClothingService::isWornGarment).count()==2
                        && c.life().task.isEmpty(),"tailor_delivers_new_coat_and_keeps_old_coats",results);
                c.insertIntoInventory(new ItemStack(Items.DIRT,32));c.insertIntoInventory(new ItemStack(coat));
                int beforeDirt=WarehouseInventoryService.countItem(level,warehouse,Items.DIRT);
                var deposit=CitizenWorkService.class.getDeclaredMethod("depositRestCargo",ServerLevel.class,SettlementSavedData.class,
                        SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class,BuildingRecord.class);
                deposit.setAccessible(true);deposit.invoke(null,level,SettlementSavedData.get(level),s,c,entity,warehouse);
                check(c.inventory().stream().filter(CitizenClothingService::isWornGarment).count()==2
                        && WarehouseInventoryService.countItem(level,warehouse,coat)==beforeCoats+2
                        && WarehouseInventoryService.countItem(level,warehouse,Items.DIRT)==beforeDirt+32,
                        "rest_deposit_keeps_worn_clothes_and_delivers_real_cargo",results);
            } catch(ReflectiveOperationException ex) {throw new IllegalStateException(ex);}
            finally {level.setDayTime(previousDay);}
            check(!menu.clickMenuButton(player,9999),"invalid_category_button_rejected",results);
            var restoredDisplay=BuildingRecord.load(warehouse.save(level.registryAccess()));
            check(WarehouseInventoryService.stock(level,restoredDisplay).quantity()==stock.quantity(),"display_changes_preserve_saved_stock",results);
            check(!s.finishBuildingDemolition(warehouse.id()) && warehouse.active(),"nonempty_inventory_prevents_building_loss",results);
            return new com.google.gson.Gson().toJson(Map.of("passed",results.size(),"checks",results,"fixtureEdits",true,"liveResidentAcceptance",false));
        } finally {
            entity.discard();for(var e:saved.entrySet())level.setBlock(e.getKey(),e.getValue(),2);
        }
    }
}

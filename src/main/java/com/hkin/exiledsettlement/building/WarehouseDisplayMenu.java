package com.hkin.exiledsettlement.building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import java.util.*;

/** Classic chest projection for this physical box, with independent exact stock counters. */
public final class WarehouseDisplayMenu extends AbstractContainerMenu {
    public static final int PAGE_SIZE=27;
    private final ServerPlayer owner;
    private final SettlementRecord settlement;
    private final BuildingRecord warehouse;
    private final BlockPos box;
    private final SimpleContainerData totals;
    private final int rows, displaySlots;
    private final int group, groups, offset;
    private final SimpleContainer view;
    private long shownRevision=Long.MIN_VALUE;
    public WarehouseDisplayMenu(int id, Inventory inventory, net.minecraft.network.RegistryFriendlyByteBuf data) {
        this(id, inventory, null, null, null, BlockPos.ZERO, data==null?3:data.readVarInt());
    }
    public WarehouseDisplayMenu(int id,Inventory inventory,ServerPlayer player,SettlementRecord s,BuildingRecord b,BlockPos p,WarehouseCategory ignored) {
        this(id,inventory,player,s,b,p,WarehouseDisplayService.displayRows(player.serverLevel(),p));
    }
    private WarehouseDisplayMenu(int id,Inventory inventory,ServerPlayer player,SettlementRecord s,BuildingRecord b,BlockPos p,int requestedRows) {
        super(com.hkin.exiledsettlement.registry.ModMenus.WAREHOUSE_DISPLAY.get(),id);
        rows=requestedRows==6?6:3;displaySlots=rows*9;totals=new SimpleContainerData(displaySlots*2+2);
        owner=player;settlement=s;warehouse=b;box=p;view=new SimpleContainer(displaySlots);
        var layout=player==null || b==null ? new WarehouseDisplayLayout(0,1,0)
                : WarehouseDisplayLayout.forBox(player.serverLevel(),b,p);
        groups=layout.groups();group=layout.group();offset=layout.offset();
        totals.set(displaySlots*2,group);totals.set(displaySlots*2+1,groups);
        for(int row=0;row<rows;row++)for(int col=0;col<9;col++)
            addSlot(new Slot(view,row*9+col,8+col*18,18+row*18) {
                @Override public boolean mayPlace(ItemStack stack){return false;}
                @Override public boolean mayPickup(Player player){return false;}
            });
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)
            addSlot(new Slot(inventory,9+row*9+col,8+col*18,31+rows*18+row*18));
        for(int col=0;col<9;col++)addSlot(new Slot(inventory,col,8+col*18,89+rows*18));
        addDataSlots(totals);refresh();
    }
    public int rows() { return rows; }
    public int displaySlots() { return displaySlots; }
    public java.util.List<WarehouseDisplayCategory> displayedCategories() {
        int totalGroups=Math.max(1,totals.get(displaySlots*2+1));
        if(totalGroups==1)return java.util.List.of(WarehouseDisplayCategory.ALL);
        int currentGroup=totals.get(displaySlots*2);
        return WarehouseDisplayCategory.columns().stream()
                .filter(category->category.columnIndex()%totalGroups==currentGroup).toList();
    }
    public int displayedCount(int slot) { return (totals.get(slot*2)&65535) | ((totals.get(slot*2+1)&65535)<<16); }
    private void refresh() {
        if(owner==null)return;
        var stock=WarehouseInventoryService.stock(owner.serverLevel(),warehouse);
        if(shownRevision==stock.revision())return;
        shownRevision=stock.revision();
        // Linked identity includes components, so damaged/enchanted/named items remain separate.
        List<ItemStack> samples=new ArrayList<>();
        List<Integer> amounts=new ArrayList<>();
        var indices=new it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap<ItemStack>(net.minecraft.world.item.ItemStackLinkedSet.TYPE_AND_TAG);
        indices.defaultReturnValue(-1);
        for(int i=0;i<stock.getContainerSize();i++) {
            var item=stock.getItem(i);
            if(item.isEmpty() || WarehouseDisplayCategory.classify(item).columnIndex()%groups!=group)continue;
            int index=indices.getInt(item);
            if(index>=0)amounts.set(index,amounts.get(index)+item.getCount());
            else {indices.put(item,samples.size());samples.add(item.copyWithCount(1));amounts.add(item.getCount());}
        }
        for(int i=0;i<displaySlots;i++) {
            int index=offset+i, count=index<amounts.size()?amounts.get(index):0;
            view.setItem(i,index<samples.size()?samples.get(index):ItemStack.EMPTY);
            totals.set(i*2,count&65535);totals.set(i*2+1,count>>>16);
        }
    }
    @Override public boolean clickMenuButton(Player player,int button) { return false; }
    @Override public void broadcastChanges(){refresh();super.broadcastChanges();}
    @Override public boolean stillValid(Player player) {
        return owner!=null && player==owner && settlement.active() && settlement.isMember(player.getUUID()) && warehouse.active()
                && warehouse.operational() && player.distanceToSqr(box.getX()+.5,box.getY()+.5,box.getZ()+.5)<=64
                && (owner.serverLevel().getBlockEntity(box) instanceof net.minecraft.world.Container
                    || warehouse.type().isHouse() && owner.serverLevel().getBlockState(box).getBlock() instanceof net.minecraft.world.level.block.BedBlock);
    }
    private ItemStack take(ItemStack sample,int count) {
        var stock=WarehouseInventoryService.stock(owner.serverLevel(),warehouse);var result=ItemStack.EMPTY;
        for(int i=0;i<stock.getContainerSize() && count>0;i++) {
            if(!ItemStack.isSameItemSameComponents(sample,stock.getItem(i)))continue;
            var part=stock.removeItem(i,Math.min(count,stock.getItem(i).getCount()));count-=part.getCount();
            if(result.isEmpty())result=part;else result.grow(part.getCount());
        }
        return result;
    }
    @Override public void clicked(int slot,int button,ClickType type,Player player) {
        if(!stillValid(player))return;
        // These vanilla operations traverse all slots, including the copied projection.
        if(type==ClickType.QUICK_CRAFT || type==ClickType.PICKUP_ALL)return;
        if(slot<0 || slot>=displaySlots){super.clicked(slot,button,type,player);return;}
        var sample=view.getItem(slot);var held=getCarried();
        int batch=sample.isEmpty()?0:Math.min(displayedCount(slot),sample.getMaxStackSize());
        if(type==ClickType.QUICK_MOVE) { quickMoveStack(player,slot);return; }
        if(type==ClickType.PICKUP) {
            if(!held.isEmpty()) {
                int n=button==1?1:held.getCount();
                var rem=WarehouseInventoryService.insert(owner.serverLevel(),warehouse,held.copyWithCount(n));
                held.shrink(n-rem.getCount());setCarried(held);
            } else if(!sample.isEmpty())setCarried(take(sample,button==1?(batch+1)/2:batch));
        } else if(type==ClickType.THROW && held.isEmpty() && !sample.isEmpty())owner.drop(take(sample,button==0?1:batch),false);
        refresh();broadcastChanges();
    }
    @Override public ItemStack quickMoveStack(Player player,int slot) {
        if(!stillValid(player) || slot<0 || slot>=slots.size())return ItemStack.EMPTY;
        if(slot>=displaySlots) {
            var item=slots.get(slot).getItem();var before=item.copy();
            var rem=WarehouseInventoryService.insert(owner.serverLevel(),warehouse,item);
            slots.get(slot).set(rem);refresh();return before.copyWithCount(before.getCount()-rem.getCount());
        }
        var sample=view.getItem(slot);if(sample.isEmpty())return ItemStack.EMPTY;
        int room=0;
        for(int i=0;i<owner.getInventory().items.size();i++) {
            var item=owner.getInventory().items.get(i);
            if(item.isEmpty())room+=sample.getMaxStackSize();
            else if(ItemStack.isSameItemSameComponents(item,sample))room+=Math.max(0,sample.getMaxStackSize()-item.getCount());
        }
        if(room<=0)return ItemStack.EMPTY;
        var extracted=take(sample,Math.min(room,sample.getMaxStackSize()));var before=extracted.copy();
        owner.getInventory().add(extracted);
        if(!extracted.isEmpty())WarehouseInventoryService.insert(owner.serverLevel(),warehouse,extracted);
        refresh();return before;
    }
}

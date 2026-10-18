package com.hkin.exiledsettlement.building;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import java.util.*;

/** The building owns these real item stacks; container blocks only provide a view. */
public final class WarehouseStock implements Container {
    public static final int CAPACITY = 200_000;
    private final List<ItemStack> stacks = new ArrayList<>();
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private CompoundTag pending;
    private ServerLevel level;
    private long importedTick = Long.MIN_VALUE;
    private long revision;
    private int cachedQuantity = -1;
    private Map<Item,Integer> summary;
    public Map<Item,Integer> summary() {
        if(summary==null) {
            var counts=new LinkedHashMap<Item,Integer>();
            for(var stack:stacks)if(!stack.isEmpty())counts.merge(stack.getItem(),stack.getCount(),Integer::sum);
            summary=Collections.unmodifiableMap(counts);
        }
        return summary;
    }
    public long revision() { return revision; }
    /** Atomic server-thread replacement after a read-only crafting simulation. */
    public boolean commitCraft(long expectedRevision, List<ItemStack> remaining) {
        if (revision != expectedRevision || pending != null) return false;
        long total=0;
        for (var stack:remaining) {
            if(stack.isEmpty() || stack.getCount()>stack.getMaxStackSize())return false;
            total+=stack.getCount();
        }
        if(total>CAPACITY)return false;
        stacks.clear();for(var stack:remaining)stacks.add(stack.copy());
        if(stacks.isEmpty())stacks.add(ItemStack.EMPTY);
        setChanged();return true;
    }
    public record Rule(int keep, boolean discard) {}
    public WarehouseStock() { rules.put("minecraft:snowball", new Rule(64, true)); }
    public static boolean applies(BuildingRecord b) { return b.type()==BuildingType.WAREHOUSE || b.type()==BuildingType.MARKET || b.type()==BuildingType.MINE || b.type().isHouse()
            || b.type()==BuildingType.WAGON && b.templateVersion()>=com.hkin.exiledsettlement.settlement.FoundingCampSupplies.STORAGE_VERSION; }
    public void load(CompoundTag tag) { pending=tag.copy(); }
    public WarehouseStock bind(ServerLevel world) {
        level=world;
        if(pending!=null) {
            var list=pending.getList("Stacks",Tag.TAG_COMPOUND);
            for(int i=0;i<list.size();i++) {
                var stack=ItemStack.parse(world.registryAccess(),list.getCompound(i)).orElseThrow(() -> new IllegalStateException("Invalid warehouse stack"));
                stacks.add(stack);
            }
            rules.clear(); var saved=pending.getCompound("Rules");
            for(String id:saved.getAllKeys()) { var r=saved.getCompound(id); rules.put(id,new Rule(Math.max(0,r.getInt("Keep")),r.getBoolean("Discard"))); }
            pending=null; cachedQuantity=-1; summary=null; revision++;
        }
        // Grow only at the API boundary, never during a container iterator.
        if(stacks.isEmpty()) for(int i=0;i<64;i++) stacks.add(ItemStack.EMPTY);
        return this;
    }
    public void importBoxes(ServerLevel world, BuildingRecord building) {
        if(importedTick<=world.getGameTime() && world.getGameTime()-importedTick>=0 && world.getGameTime()-importedTick<20)return;
        importedTick=world.getGameTime();
        for(var at:WarehouseInventoryService.physicalContainers(world,building)) {
            var box=at.container();
            for(int i=0;i<box.getContainerSize();i++) {
                var stack=box.getItem(i); if(stack.isEmpty())continue;
                var remaining=insert(stack);
                if(remaining.getCount()!=stack.getCount()) { box.setItem(i,remaining);box.setChanged(); }
            }
        }
    }
    public int quantity() { if(cachedQuantity<0)cachedQuantity=stacks.stream().mapToInt(ItemStack::getCount).sum();return cachedQuantity; }
    public int room() { return Math.max(0,CAPACITY-quantity()); }
    public Rule rule(String item) { return rules.getOrDefault(item,new Rule(CAPACITY,false)); }
    public void rule(String item,int keep,boolean discard) { rules.put(item,new Rule(Math.max(0,Math.min(CAPACITY,keep)),discard));setChanged(); }
    public ItemStack insert(ItemStack offered) {
        var remaining=offered.copy();int moved=Math.min(room(),remaining.getCount());
        var input=remaining.copyWithCount(moved);remaining.shrink(moved);
        for(var existing:stacks) {
            if(input.isEmpty())break;
            if(!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing,input)) {
                int n=Math.min(input.getCount(),Math.max(0,existing.getMaxStackSize()-existing.getCount())); existing.grow(n);input.shrink(n);
            }
        }
        while(!input.isEmpty()) {
            int slot=-1;for(int i=0;i<stacks.size();i++)if(stacks.get(i).isEmpty()){slot=i;break;}
            int n=Math.min(input.getCount(),input.getMaxStackSize());var part=input.copyWithCount(n);
            if(slot<0)stacks.add(part);else stacks.set(slot,part);input.shrink(n);
        }
        if(moved>0)setChanged();return remaining;
    }
    public CompoundTag save(HolderLookup.Provider registries) {
        if(pending!=null)return pending.copy();
        var tag=new CompoundTag();tag.putInt("Version",1);var list=new ListTag();
        for(var stack:stacks)if(!stack.isEmpty())list.add(stack.save(registries));tag.put("Stacks",list);
        var rs=new CompoundTag();rules.forEach((id,r)->{var t=new CompoundTag();t.putInt("Keep",r.keep);t.putBoolean("Discard",r.discard);rs.put(id,t);});tag.put("Rules",rs);return tag;
    }
    public CompoundTag snapshot() { return pending!=null?pending.copy():level!=null?save(level.registryAccess()):new CompoundTag(); }
    @Override public int getContainerSize(){return stacks.size();}
    @Override public boolean isEmpty(){return pending!=null?pending.getList("Stacks",Tag.TAG_COMPOUND).isEmpty():stacks.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot){return slot>=0 && slot<stacks.size()?stacks.get(slot):ItemStack.EMPTY;}
    @Override public ItemStack removeItem(int slot,int amount){var result=getItem(slot).split(Math.max(0,amount));if(!result.isEmpty())setChanged();return result;}
    @Override public ItemStack removeItemNoUpdate(int slot){var result=getItem(slot);if(slot>=0 && slot<stacks.size())stacks.set(slot,ItemStack.EMPTY);setChanged();return result;}
    @Override public void setItem(int slot,ItemStack stack){while(stacks.size()<=slot)stacks.add(ItemStack.EMPTY);stacks.set(slot,stack);setChanged();}
    @Override public void setChanged(){cachedQuantity=-1;summary=null;revision++;if(level!=null)SettlementSavedData.get(level).touch();}
    @Override public boolean stillValid(Player player){return true;}
    @Override public void clearContent(){stacks.clear();setChanged();}
}

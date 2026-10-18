package com.hkin.exiledsettlement.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent visit clock and unpaid orders. Offers are paid for only at the next visit. */
public final class MerchantState {
    public record Order(UUID id, ResourceLocation item, int count, long placedDay) { }

    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Integer> activeOfferIndices = new LinkedHashMap<>();
    private long nextVisitDay = -1L;
    private long visitEndsDay = -1L;
    @Nullable private UUID traderId;

    public Collection<Order> orders() { return java.util.List.copyOf(orders.values()); }
    public long nextVisitDay() { return nextVisitDay; }
    public long visitEndsDay() { return visitEndsDay; }
    @Nullable public UUID traderId() { return traderId; }
    public Map<UUID, Integer> activeOfferIndices() { return Map.copyOf(activeOfferIndices); }

    public boolean addOrder(ResourceLocation item, int count, long day) {
        if (orders.size() >= 8 || count < 1 || count > 64) return false;
        Order order = new Order(UUID.randomUUID(), item, count, day);
        orders.put(order.id(), order);
        return true;
    }

    public boolean cancelOrder(UUID id) {
        activeOfferIndices.remove(id);
        return orders.remove(id) != null;
    }

    public void scheduleFirstVisit(long day) {
        if (nextVisitDay < 0) nextVisitDay = day + 1;
    }

    public void beginVisit(UUID trader, long day, Map<UUID, Integer> offerIndices) {
        traderId = trader;
        visitEndsDay = day + 1;
        activeOfferIndices.clear();
        activeOfferIndices.putAll(offerIndices);
    }

    public void completeOrder(UUID orderId) {
        orders.remove(orderId);
        activeOfferIndices.remove(orderId);
    }

    public void finishVisit(long day) {
        traderId = null;
        activeOfferIndices.clear();
        visitEndsDay = -1;
        nextVisitDay = day + 4;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("NextVisitDay", nextVisitDay);
        tag.putLong("VisitEndsDay", visitEndsDay);
        if (traderId != null) tag.putUUID("TraderId", traderId);
        ListTag list = new ListTag();
        for (Order order : orders.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", order.id());
            entry.putString("Item", order.item().toString());
            entry.putInt("Count", order.count());
            entry.putLong("PlacedDay", order.placedDay());
            list.add(entry);
        }
        tag.put("Orders", list);
        ListTag offers = new ListTag();
        activeOfferIndices.forEach((id, index) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("OrderId", id);
            entry.putInt("Index", index);
            offers.add(entry);
        });
        tag.put("ActiveOfferIndices", offers);
        return tag;
    }

    public void load(CompoundTag tag) {
        nextVisitDay = tag.contains("NextVisitDay") ? tag.getLong("NextVisitDay") : -1L;
        visitEndsDay = tag.contains("VisitEndsDay") ? tag.getLong("VisitEndsDay") : -1L;
        traderId = tag.hasUUID("TraderId") ? tag.getUUID("TraderId") : null;
        orders.clear();
        ListTag list = tag.getList("Orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && orders.size() < 8; i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation item = ResourceLocation.tryParse(entry.getString("Item"));
            int count = entry.getInt("Count");
            if (item == null || !entry.hasUUID("Id") || count < 1 || count > 64) continue;
            UUID id = entry.getUUID("Id");
            orders.put(id, new Order(id, item, count, entry.getLong("PlacedDay")));
        }
        activeOfferIndices.clear();
        ListTag offers = tag.getList("ActiveOfferIndices", Tag.TAG_COMPOUND);
        for (int i = 0; i < offers.size(); i++) {
            CompoundTag entry = offers.getCompound(i);
            if (entry.hasUUID("OrderId") && orders.containsKey(entry.getUUID("OrderId"))) {
                activeOfferIndices.put(entry.getUUID("OrderId"), entry.getInt("Index"));
            }
        }
    }
}

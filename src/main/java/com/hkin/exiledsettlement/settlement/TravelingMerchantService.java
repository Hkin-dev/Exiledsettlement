package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One market visit every four days. Vanilla merchant menus perform the emerald/item exchange. */
public final class TravelingMerchantService {
    public record Trade(Item item, int count, int emeralds) { }

    private static final List<Trade> CATALOG = List.of(
            trade(Items.OAK_SAPLING, 4, 2), trade(Items.SPRUCE_SAPLING, 4, 2),
            trade(Items.BIRCH_SAPLING, 4, 2), trade(Items.JUNGLE_SAPLING, 4, 3),
            trade(Items.ACACIA_SAPLING, 4, 3), trade(Items.DARK_OAK_SAPLING, 4, 3),
            trade(Items.CHERRY_SAPLING, 4, 4), trade(Items.MANGROVE_PROPAGULE, 4, 4),
            trade(Items.WHEAT_SEEDS, 16, 1), trade(Items.BEETROOT_SEEDS, 16, 1),
            trade(Items.MELON_SEEDS, 8, 2), trade(Items.PUMPKIN_SEEDS, 8, 2),
            trade(Items.CARROT, 16, 2), trade(Items.POTATO, 16, 2),
            trade(Items.SWEET_BERRIES, 16, 2), trade(Items.COCOA_BEANS, 16, 3),
            trade(Items.BAMBOO, 16, 2), trade(Items.SUGAR_CANE, 16, 2),
            trade(Items.OAK_LOG, 16, 3), trade(Items.SPRUCE_LOG, 16, 3),
            trade(Items.BIRCH_LOG, 16, 3), trade(Items.JUNGLE_LOG, 16, 4),
            trade(Items.ACACIA_LOG, 16, 4), trade(Items.DARK_OAK_LOG, 16, 4),
            trade(Items.CHERRY_LOG, 16, 5), trade(Items.MANGROVE_LOG, 16, 5),
            trade(Items.COBBLESTONE, 32, 2), trade(Items.STONE, 32, 3),
            trade(Items.BRICKS, 16, 4), trade(Items.GLASS, 16, 3),
            trade(Items.SAND, 32, 2), trade(Items.GRAVEL, 32, 2),
            trade(Items.CLAY_BALL, 16, 3), trade(Items.COAL, 16, 3),
            trade(Items.IRON_INGOT, 8, 5), trade(Items.COPPER_INGOT, 16, 4),
            trade(Items.REDSTONE, 16, 3), trade(Items.LAPIS_LAZULI, 16, 3),
            trade(Items.QUARTZ, 16, 5), trade(Items.OBSIDIAN, 8, 5),
            trade(Items.LEATHER, 8, 4), trade(Items.STRING, 16, 3),
            trade(Items.FEATHER, 16, 2), trade(Items.BONE, 16, 3),
            trade(Items.EGG, 16, 2), trade(Items.MILK_BUCKET, 1, 3),
            trade(Items.LEAD, 2, 4), trade(Items.SADDLE, 1, 8),
            trade(Items.NAME_TAG, 1, 5), trade(Items.BOOK, 8, 4),
            trade(Items.PAPER, 16, 2), trade(Items.MAP, 1, 4),
            trade(Items.COMPASS, 1, 6), trade(Items.CLOCK, 1, 6),
            trade(Items.FISHING_ROD, 1, 4), trade(Items.IRON_AXE, 1, 6),
            trade(Items.IRON_HOE, 1, 6), trade(Items.IRON_PICKAXE, 1, 6),
            trade(Items.IRON_SWORD, 1, 6), trade(Items.BOW, 1, 5),
            trade(Items.ARROW, 32, 4), trade(Items.SCAFFOLDING, 16, 3),
            trade(Items.BREAD, 16, 3), trade(Items.COOKED_BEEF, 8, 4),
            trade(Items.COOKED_CHICKEN, 8, 4), trade(Items.COOKED_PORKCHOP, 8, 4)
    );

    private TravelingMerchantService() { }

    private static Trade trade(Item item, int count, int emeralds) {
        return new Trade(item, count, emeralds);
    }

    public static List<Trade> catalog() { return CATALOG; }

    @Nullable
    public static Trade entry(ResourceLocation id) {
        return CATALOG.stream().filter(trade -> BuiltInRegistries.ITEM.getKey(trade.item()).equals(id))
                .findFirst().orElse(null);
    }

    /** A reserved order is intentionally more expensive than the ready-stock offer. */
    public static int orderPrice(Trade trade, int count) {
        if (count < 1 || count > trade.item().getDefaultMaxStackSize()) return -1;
        long bundles = (count + trade.count() - 1L) / trade.count();
        long price = (trade.emeralds() * bundles * 5L + 3L) / 4L;
        return price <= 64 ? (int) Math.max(1, price) : -1;
    }

    public static boolean order(ServerLevel level, SettlementRecord settlement, ResourceLocation item, int count) {
        Trade trade = entry(item);
        if (trade == null || orderPrice(trade, count) < 0) return false;
        boolean added = settlement.merchantState().addOrder(item, count, level.getDayTime() / 24000L);
        if (added) SettlementSavedData.get(level).touch();
        return added;
    }

    public static boolean cancelOrder(ServerLevel level, SettlementRecord settlement, UUID orderId) {
        MerchantState state = settlement.merchantState();
        Integer offerIndex = state.activeOfferIndices().get(orderId);
        if (offerIndex != null && state.traderId() != null) {
            Entity entity = level.getEntity(state.traderId());
            if (entity instanceof WanderingTrader trader) {
                MerchantOffers offers = trader.getOffers();
                if (offerIndex >= 0 && offerIndex < offers.size()) {
                    MerchantOffer offer = offers.get(offerIndex);
                    if (offer.getUses() > 0) {
                        state.completeOrder(orderId);
                        SettlementSavedData.get(level).touch();
                        return false;
                    }
                    offer.setToOutOfStock();
                }
            }
        }
        if (!state.cancelOrder(orderId)) return false;
        SettlementSavedData.get(level).touch();
        return true;
    }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        long day = level.getDayTime() / 24000L;
        for (SettlementRecord settlement : data.settlements()) {
            if (!settlement.active()) continue;
            BuildingRecord market = settlement.buildings().stream()
                    .filter(building -> building.type() == BuildingType.MARKET
                            && building.active() && building.operational() && level.hasChunkAt(building.position()))
                    .filter(building -> settlement.citizens().stream().anyMatch(citizen -> citizen.alive()
                            && citizen.profession() == CitizenProfession.TRAVELING_MERCHANT
                            && building.id().equals(citizen.workBuildingId())))
                    .min(Comparator.comparing(building -> building.id().toString())).orElse(null);
            if (market == null) continue;
            MerchantState state = settlement.merchantState();
            if (state.nextVisitDay() < 0) {
                state.scheduleFirstVisit(day);
                data.touch();
            }
            if (state.traderId() != null) {
                Entity active = level.getEntity(state.traderId());
                if (active instanceof WanderingTrader trader && trader.isAlive()) {
                    MerchantOffers offers = trader.getOffers();
                    for (Map.Entry<UUID, Integer> entry : state.activeOfferIndices().entrySet()) {
                        if (entry.getValue() >= 0 && entry.getValue() < offers.size()
                                && offers.get(entry.getValue()).getUses() > 0) {
                            state.completeOrder(entry.getKey());
                            data.touch();
                        }
                    }
                }
                if (day >= state.visitEndsDay()) {
                    if (active != null) active.discard();
                    state.finishVisit(day);
                    data.touch();
                }
                continue;
            }
            if (day < state.nextVisitDay()) continue;
            BlockPos arrival = arrivalPosition(level, market);
            if (arrival == null) continue;
            WanderingTrader trader = EntityType.WANDERING_TRADER.create(level);
            if (trader == null) continue;
            trader.moveTo(arrival.getX() + 0.5D, arrival.getY(), arrival.getZ() + 0.5D, 0.0F, 0.0F);
            trader.setCustomName(Component.translatable("entity.exiledsettlement.traveling_merchant"));
            trader.setPersistenceRequired();
            trader.restrictTo(arrival, 12);
            trader.setDespawnDelay(24000);
            MerchantOffers offers = trader.getOffers();
            offers.clear();
            for (Trade trade : CATALOG) offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, trade.emeralds()),
                    new ItemStack(trade.item(), trade.count()), 8, 1, 0.0F));
            Map<UUID, Integer> orderIndices = new LinkedHashMap<>();
            for (MerchantState.Order order : state.orders()) {
                if (order.placedDay() >= day) continue;
                Trade trade = entry(order.item());
                if (trade == null) continue;
                int price = orderPrice(trade, order.count());
                if (price < 0) continue;
                orderIndices.put(order.id(), offers.size());
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, price),
                        new ItemStack(trade.item(), order.count()), 1, 1, 0.0F));
            }
            if (!level.addFreshEntity(trader)) continue;
            state.beginVisit(trader.getUUID(), day, orderIndices);
            data.touch();
            level.players().stream().filter(player -> player.distanceToSqr(trader) < 4096.0D)
                    .forEach(player -> player.displayClientMessage(
                            Component.translatable("message.exiledsettlement.merchant_arrived", settlement.name()), false));
        }
    }

    @Nullable
    private static BlockPos arrivalPosition(ServerLevel level, BuildingRecord market) {
        var entrances = BlueprintTemplates.createForBuilding(market).entrances();
        BlockPos start = entrances.isEmpty() ? market.position() :
                entrances.getFirst().position().relative(entrances.getFirst().outward(), 2);
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(start);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(start.relative(direction));
            candidates.add(start.relative(direction, 2));
        }
        for (BlockPos candidate : candidates) {
            if (!level.hasChunkAt(candidate)) continue;
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos feet = candidate.offset(0, dy, 0);
                if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                    return feet.immutable();
                }
            }
        }
        return null;
    }
}

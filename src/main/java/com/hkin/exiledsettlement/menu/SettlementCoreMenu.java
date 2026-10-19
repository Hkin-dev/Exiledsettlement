package com.hkin.exiledsettlement.menu;

import com.hkin.exiledsettlement.blueprint.PlacementSessionService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.registry.ModMenus;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.SettlementProgressionService;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementLevel;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.network.PlacementNetworking;
import com.hkin.exiledsettlement.road.RoadTier;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

public final class SettlementCoreMenu extends AbstractContainerMenu {
    public static final BuildingCatalogEntry[] BUILDING_CATALOG = {
            current(BuildingType.HOUSE), current(BuildingType.HOUSE_2), current(BuildingType.HOUSE_3),
            current(BuildingType.ADVANCED_HOUSE_1), current(BuildingType.ADVANCED_HOUSE_2), current(BuildingType.ADVANCED_HOUSE_3),
            current(BuildingType.APARTMENT), current(BuildingType.WAREHOUSE),
            current(BuildingType.FARM_FIELD), current(BuildingType.PASTURE_AREA),
            current(BuildingType.HUNTING_LODGE),
            current(BuildingType.TOOL_WORKSHOP),
            current(BuildingType.TREE_FARM),
            current(BuildingType.TOWN_HALL),
            current(BuildingType.FISHING_HUT),
            current(BuildingType.MINE),
            current(BuildingType.SAWMILL), current(BuildingType.SCHOOL),
            current(BuildingType.MARKET), current(BuildingType.TAVERN),
            current(BuildingType.MELON_FIELD),
            current(BuildingType.LEISURE_GARDEN), current(BuildingType.CHURCH), current(BuildingType.BRICK_KILN)
    };
    public static final BuildingType[] BUILDING_CHOICES = java.util.Arrays.stream(BUILDING_CATALOG)
            .map(BuildingCatalogEntry::type).toArray(BuildingType[]::new);
    public static final int BRIDGE_BUTTON = 203;
    public static final int ROAD_LEVEL_1_BUTTON = 200;
    public static final int ROAD_LEVEL_2_BUTTON = 201;
    public static final int ROAD_LEVEL_3_BUTTON = 202;
    public static final int UPGRADE_BUTTON = 50;
    public static final int RECRUIT_BUTTON = 51;
    public static final int OPTIMIZE_HOUSING_BUTTON = 52;
    public static final int MANAGEMENT_VIEW_BUTTON = 53;
    public static final int CREATIVE_CORE_BUTTON = 54;
    public static final int CHUNK_LOADING_BUTTON = 55;
    public static final int COMMUNITY_BUTTON = 56;
    private static final int PROFESSION_DECREASE_BASE = 100;
    private static final int PROFESSION_INCREASE_BASE = 120;
    private static final int SUMMARY_DATA_COUNT = 17;
    private static final int DATA_COUNT = SUMMARY_DATA_COUNT + CitizenProfession.values().length * 3;

    private final BlockPos corePosition;
    private final BlockPos accessPosition;
    private final ContainerData settlementData;

    public SettlementCoreMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos(), buffer.readBlockPos(),
                new SimpleContainerData(DATA_COUNT));
    }

    public SettlementCoreMenu(
            int containerId,
            Inventory inventory,
            BlockPos corePosition,
            SettlementRecord settlement
    ) {
        this(containerId, inventory, corePosition, corePosition, dynamicData(settlement, inventory));
    }

    public SettlementCoreMenu(
            int containerId, Inventory inventory, BlockPos corePosition, BlockPos accessPosition,
            SettlementRecord settlement
    ) {
        this(containerId, inventory, corePosition, accessPosition, dynamicData(settlement, inventory));
    }

    private SettlementCoreMenu(
            int containerId,
            Inventory inventory,
            BlockPos corePosition,
            BlockPos accessPosition,
            ContainerData settlementData
    ) {
        super(ModMenus.SETTLEMENT_CORE.get(), containerId);
        this.corePosition = corePosition.immutable();
        this.accessPosition = accessPosition.immutable();
        this.settlementData = settlementData;
        addDataSlots(settlementData);
    }

    public BlockPos corePosition() {
        return corePosition;
    }

    public int aliveCitizens() {
        return settlementData.get(0);
    }

    public int totalCitizens() {
        return settlementData.get(1);
    }

    public int operationalBuildings() {
        return settlementData.get(2);
    }

    public int activeBuildings() {
        return settlementData.get(3);
    }

    public int pendingBlueprints() {
        return settlementData.get(4);
    }

    public int levelOrdinal() { return settlementData.get(5); }
    public int populationCap() { return settlementData.get(6); }
    public int housingCapacity() { return settlementData.get(7); }
    public int availableFood() { return settlementData.get(8); }
    public int recruitmentCost() { return settlementData.get(9); }
    public int nextLevelBuildingRequirement() { return settlementData.get(10); }
    public int boundaryRadiusChunks() { return settlementData.get(11); }
    public int nextBoundaryRadiusChunks() { return settlementData.get(12); }
    public boolean creativeCoreInstalled() { return settlementData.get(13) != 0; }
    public boolean chunkLoadingEnabled() { return settlementData.get(14) != 0; }
    public int forcedChunkCount() { return settlementData.get(15); }
    public int chunkLoadingLimit() { return settlementData.get(16); }

    public int professionTarget(CitizenProfession profession) {
        return settlementData.get(SUMMARY_DATA_COUNT + profession.ordinal());
    }

    public int professionActual(CitizenProfession profession) {
        return settlementData.get(SUMMARY_DATA_COUNT + CitizenProfession.values().length + profession.ordinal());
    }

    public int professionCapacity(CitizenProfession profession) {
        return settlementData.get(SUMMARY_DATA_COUNT + CitizenProfession.values().length * 2 + profession.ordinal());
    }

    public static int professionDecreaseButton(CitizenProfession profession) {
        return PROFESSION_DECREASE_BASE + profession.ordinal();
    }

    public static int professionIncreaseButton(CitizenProfession profession) {
        return PROFESSION_INCREASE_BASE + profession.ordinal();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BRIDGE_BUTTON && player instanceof ServerPlayer serverPlayer && stillValid(player)) {
            var settlement = SettlementSavedData.get(serverPlayer.serverLevel()).findByCore(corePosition).orElse(null);
            if (settlement == null || !settlement.active() || !settlement.isMember(serverPlayer.getUUID())) return false;
            serverPlayer.closeContainer();
            com.hkin.exiledsettlement.terrain.PlayerWorksService.begin(serverPlayer, settlement, "bridge");
            return true;
        }
        if (id == COMMUNITY_BUTTON && player instanceof ServerPlayer serverPlayer && stillValid(player)) {
            var settlement = SettlementSavedData.get(serverPlayer.serverLevel()).findByCore(corePosition).orElse(null);
            if (settlement == null || !settlement.isMember(serverPlayer.getUUID())) return false;
            com.hkin.exiledsettlement.settlement.CommunityManagement.openLedger(serverPlayer, settlement, 0);
            return true;
        }
        if (id >= PROFESSION_DECREASE_BASE
                && id < PROFESSION_DECREASE_BASE + CitizenProfession.values().length) {
            return changeProfession(player, CitizenProfession.values()[id - PROFESSION_DECREASE_BASE], -1);
        }
        if (id >= PROFESSION_INCREASE_BASE
                && id < PROFESSION_INCREASE_BASE + CitizenProfession.values().length) {
            return changeProfession(player, CitizenProfession.values()[id - PROFESSION_INCREASE_BASE], 1);
        }
        if (id == UPGRADE_BUTTON || id == RECRUIT_BUTTON || id == OPTIMIZE_HOUSING_BUTTON) {
            return progressSettlement(player, id);
        }
        if (id == CREATIVE_CORE_BUTTON || id == CHUNK_LOADING_BUTTON) {
            return toggleCoreOperation(player, id);
        }
        if (id == MANAGEMENT_VIEW_BUTTON && player instanceof ServerPlayer serverPlayer
                && stillValid(player) && serverPlayer.level() instanceof ServerLevel level) {
            SettlementRecord settlement = SettlementSavedData.get(level).findByCore(corePosition).orElse(null);
            if (settlement == null || !settlement.isMember(serverPlayer.getUUID())) return false;
            PlacementSessionService.cancel(serverPlayer, false);
            PlacementNetworking.startBuildingManagement(serverPlayer, settlement);
            serverPlayer.closeContainer();
            return true;
        }
        if (id >= ROAD_LEVEL_1_BUTTON && id <= ROAD_LEVEL_3_BUTTON && player instanceof ServerPlayer serverPlayer
                && stillValid(player) && serverPlayer.level() instanceof ServerLevel level) {
            SettlementRecord settlement = SettlementSavedData.get(level).findByCore(corePosition).orElse(null);
            if (settlement == null) {
                SettlementMessages.sendFailure(serverPlayer, "CORE_INACTIVE");
                return false;
            }
            PlacementSessionService.startRoad(serverPlayer, settlement, corePosition,
                    RoadTier.fromLevel(id - ROAD_LEVEL_1_BUTTON + 1));
            serverPlayer.closeContainer();
            return true;
        }
        BuildingCatalogEntry choice = id >= 0 && id < BUILDING_CATALOG.length ? BUILDING_CATALOG[id] : null;
        if (choice == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (!stillValid(player)) {
            return false;
        }
        if (!(serverPlayer.level() instanceof ServerLevel level)) {
            return false;
        }

        SettlementRecord settlement = SettlementSavedData.get(level).findByCore(corePosition).orElse(null);
        if (settlement == null) {
            SettlementMessages.sendFailure(serverPlayer, "CORE_INACTIVE");
            return false;
        }
        PlacementSessionService.start(serverPlayer, settlement, choice.type(), choice.templateVersion(),
                choice.translationKey(), corePosition);
        serverPlayer.closeContainer();
        return true;
    }

    private static BuildingCatalogEntry current(BuildingType type) {
        return BuildingCatalogEntry.current(type);
    }

    private static BuildingCatalogEntry variant(BuildingType type, int templateVersion, String translationKey) {
        return new BuildingCatalogEntry(type, templateVersion, translationKey);
    }

    private boolean progressSettlement(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || !stillValid(player)
                || !(serverPlayer.level() instanceof ServerLevel level)) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findByCore(corePosition).orElse(null);
        if (settlement == null) {
            SettlementMessages.sendFailure(serverPlayer, "CORE_INACTIVE");
            return false;
        }
        if (id == OPTIMIZE_HOUSING_BUTTON) {
            int changed = settlement.optimizeHousingAssignments();
            if (changed > 0) data.touch();
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.exiledsettlement.housing.optimized", changed), false);
        } else if (id == UPGRADE_BUTTON) {
            OperationResult<SettlementLevel> result = SettlementProgressionService.upgrade(data, settlement, serverPlayer);
            if (!result.success() || result.value() == null) {
                SettlementMessages.sendFailure(serverPlayer, result.reason());
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.exiledsettlement.level.upgraded",
                        Component.translatable(result.value().translationKey())), false);
            }
        } else {
            OperationResult<CitizenRecord> result = SettlementProgressionService.recruit(level, data, settlement, serverPlayer);
            if (!result.success() || result.value() == null) {
                SettlementMessages.sendFailure(serverPlayer, result.reason());
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.exiledsettlement.migrant.arrived",
                        result.value().name()), false);
            }
        }
        return true;
    }

    private boolean toggleCoreOperation(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || !stillValid(player)
                || !(serverPlayer.level() instanceof ServerLevel level)) return false;
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findByCore(corePosition).orElse(null);
        if (settlement == null) return false;
        if (id == CHUNK_LOADING_BUTTON) {
            if (!settlement.isOwner(serverPlayer.getUUID())) {
                SettlementMessages.sendFailure(serverPlayer, "NO_PERMISSION");
                return false;
            }
            settlement.toggleChunkLoading();
            data.touch();
            com.hkin.exiledsettlement.settlement.SettlementChunkLoadingService.reconcile(level);
            return true;
        }
        if (!serverPlayer.isCreative()) {
            SettlementMessages.sendFailure(serverPlayer, "CREATIVE_ONLY");
            return false;
        }
        if (settlement.creativeCoreInstalled()) {
            settlement.setCreativeCoreInstalled(false);
            ItemStack core = new ItemStack(ModItems.CREATIVE_SUPPLY_CORE.get());
            if (!serverPlayer.getInventory().add(core)) serverPlayer.drop(core, false);
            data.touch();
            return true;
        }
        for (int slot = 0; slot < serverPlayer.getInventory().getContainerSize(); slot++) {
            ItemStack stack = serverPlayer.getInventory().getItem(slot);
            if (!stack.is(ModItems.CREATIVE_SUPPLY_CORE.get())) continue;
            stack.shrink(1);
            settlement.setCreativeCoreInstalled(true);
            data.touch();
            return true;
        }
        SettlementMessages.sendFailure(serverPlayer, "CREATIVE_CORE_MISSING");
        return false;
    }

    private boolean changeProfession(Player player, CitizenProfession profession, int delta) {
        if (!profession.specialist()
                || !(player instanceof ServerPlayer serverPlayer)
                || !stillValid(player)
                || !(serverPlayer.level() instanceof ServerLevel level)) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findByCore(corePosition).orElse(null);
        if (settlement == null || !data.changeProfessionTarget(
                settlement,
                serverPlayer.getUUID(),
                profession,
                delta
        )) {
            return false;
        }
        ProfessionAssignmentService.refresh(data, settlement);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        boolean validAccessBlock = player.level().getBlockState(accessPosition).is(ModBlocks.SETTLEMENT_CORE.get())
                || player.level().getBlockState(accessPosition).is(ModBlocks.TOWN_HALL_MARKER.get());
        boolean physicallyValid = validAccessBlock
                && player.distanceToSqr(
                accessPosition.getX() + 0.5D,
                accessPosition.getY() + 0.5D,
                accessPosition.getZ() + 0.5D
        ) <= 64.0D;
        if (!physicallyValid) {
            return false;
        }
        if (player.level() instanceof ServerLevel level) {
            SettlementRecord settlement = SettlementSavedData.get(level).findByCore(corePosition).orElse(null);
            return settlement != null && settlement.active() && settlement.isMember(player.getUUID());
        }
        return true;
    }

    private static ContainerData dynamicData(SettlementRecord settlement, Inventory inventory) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> Math.toIntExact(settlement.aliveCitizenCount());
                    case 1 -> settlement.citizens().size();
                    case 2 -> Math.toIntExact(settlement.operationalBuildingCount());
                    case 3 -> Math.toIntExact(settlement.activeBuildingCount());
                    case 4 -> settlement.blueprints().size();
                    case 5 -> settlement.level().ordinal();
                    case 6 -> settlement.level().populationCap();
                    case 7 -> settlement.housingCapacity();
                    case 8 -> inventory.player.level() instanceof ServerLevel level
                            ? Math.min(32767, SettlementProgressionService.availableFood(level, settlement)) : 0;
                    case 9 -> settlement.level().migrantFoodCost();
                    case 10 -> settlement.level().next() == null ? -1 : settlement.level().buildingsForNextLevel();
                    case 11 -> settlement.boundaryRadiusChunks();
                    case 12 -> settlement.level().next() == null
                            ? settlement.boundaryRadiusChunks() : settlement.level().next().boundaryRadiusChunks();
                    case 13 -> settlement.creativeCoreInstalled() ? 1 : 0;
                    case 14 -> settlement.chunkLoadingEnabled() ? 1 : 0;
                    case 15 -> settlement.forcedChunkKeys().size();
                    case 16 -> settlement.level().chunkLoadingLimit();
                    default -> professionData(settlement, index);
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    private static int professionData(SettlementRecord settlement, int index) {
        int relative = index - SUMMARY_DATA_COUNT;
        int count = CitizenProfession.values().length;
        if (relative >= 0 && relative < count) {
            return settlement.professionTarget(CitizenProfession.values()[relative]);
        }
        if (relative >= count && relative < count * 2) {
            return Math.toIntExact(settlement.professionActual(CitizenProfession.values()[relative - count]));
        }
        if (relative >= count * 2 && relative < count * 3) {
            return settlement.professionCapacity(CitizenProfession.values()[relative - count * 2]);
        }
        return 0;
    }
}

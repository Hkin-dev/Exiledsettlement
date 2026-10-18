package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.menu.BuildingCatalogEntry;
import com.hkin.exiledsettlement.network.RenameBuildingPayload;
import com.hkin.exiledsettlement.network.RequestBuildingManagementPayload;
import com.hkin.exiledsettlement.network.RequestSettlementDetailsPayload;
import com.hkin.exiledsettlement.network.SettlementDetailsPayload;
import com.hkin.exiledsettlement.settlement.SettlementLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

public final class SettlementCoreScreen extends AbstractContainerScreen<SettlementCoreMenu> {
    private static final int PANEL = 0xF2D8C79C;
    private static final int BORDER = 0xFF4A321E;
    private static final int HEADER = 0xFF6A472A;
    private static final int CARD = 0xE8F1E4C6;
    private static final int TEXT = 0xFF2C2118;
    private static final int MUTED = 0xFF665647;
    private static final int CATALOG_PAGE_SIZE = 8;
    private static final int MANAGEMENT_PAGE_SIZE = 6;

    private Tab tab = Tab.OVERVIEW;
    private int catalogPage;
    private int professionPage;
    private int managementPage;
    private List<SettlementDetailsPayload.BuildingEntry> buildingDetails = List.of();
    private boolean detailsReceived;
    private UUID selectedBuildingId;
    private EditBox nameField;

    public SettlementCoreScreen(SettlementCoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 420;
        imageHeight = 352;
        inventoryLabelY = 1000;
    }

    @Override
    protected void init() {
        super.init();
        ClientPlacementController.observeTerritory(menu.corePosition(), menu.boundaryRadiusChunks());
        requestDetails();
        rebuildWidgets();
    }

    public static void receiveDetails(SettlementDetailsPayload payload) {
        // Paged replies start with an empty header, not an empty completed inventory.
        if (!payload.buildings().isEmpty()) receiveBuildings(payload.corePosition(), payload.buildings());
    }

    public static void receiveBuildings(BlockPos core, List<SettlementDetailsPayload.BuildingEntry> buildings) {
        if (Minecraft.getInstance().screen instanceof SettlementCoreScreen screen
                && screen.menu.corePosition().equals(core)) {
            screen.buildingDetails = List.copyOf(buildings);
            screen.detailsReceived = true;
            if (screen.selectedBuildingId != null && screen.selectedEntry() == null) screen.selectedBuildingId = null;
            int warehouseCount = (int) screen.buildingDetails.stream()
                    .filter(entry -> entry.type().isStorage()).count();
            screen.managementPage = Math.min(screen.managementPage,
                    Math.max(0, (warehouseCount - 1) / MANAGEMENT_PAGE_SIZE));
            screen.rebuildWidgets();
        }
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        nameField = null;
        addTabButton(Tab.OVERVIEW, 6, "gui.exiledsettlement.core.overview");
        addTabButton(Tab.PROFESSIONS, 88, "gui.exiledsettlement.core.professions");
        addTabButton(Tab.CATALOG, 170, "gui.exiledsettlement.core.buildings");
        addTabButton(Tab.MANAGEMENT, 252, "gui.exiledsettlement.core.manage_buildings");
        addTabButton(Tab.INVENTORY, 334, "gui.exiledsettlement.core.inventory");

        switch (tab) {
            case OVERVIEW -> addOverviewWidgets();
            case PROFESSIONS -> addProfessionWidgets();
            case CATALOG -> addCatalogWidgets();
            case MANAGEMENT -> addManagementWidgets();
            case INVENTORY -> addInventoryWidgets();
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.close"), button -> onClose())
                .bounds(leftPos + 180, tab == Tab.PROFESSIONS ? professionFooterY() + 24 : topPos + 328, 60, 18).build());
    }

    private void addOverviewWidgets() {
        addRenderableWidget(Button.builder(Component.translatable(menu.creativeCoreInstalled()
                        ? "gui.exiledsettlement.core.creative_remove"
                        : "gui.exiledsettlement.core.creative_install"),
                button -> request(SettlementCoreMenu.CREATIVE_CORE_BUTTON))
                .bounds(leftPos + 36, topPos + 232, 164, 22).build());
        addRenderableWidget(Button.builder(Component.translatable(menu.chunkLoadingEnabled()
                        ? "gui.exiledsettlement.core.chunk_loading_disable"
                        : "gui.exiledsettlement.core.chunk_loading_enable",
                        menu.forcedChunkCount(), menu.chunkLoadingLimit()),
                button -> request(SettlementCoreMenu.CHUNK_LOADING_BUTTON))
                .bounds(leftPos + 220, topPos + 232, 164, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.upgrade"),
                button -> request(SettlementCoreMenu.UPGRADE_BUTTON))
                .bounds(leftPos + 36, topPos + 264, 164, 24).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.recruit"),
                button -> request(SettlementCoreMenu.RECRUIT_BUTTON))
                .bounds(leftPos + 220, topPos + 264, 164, 24).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.optimize_housing"),
                button -> request(SettlementCoreMenu.OPTIMIZE_HOUSING_BUTTON))
                .bounds(leftPos + 36, topPos + 296, 164, 24).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.community"),
                button -> request(SettlementCoreMenu.COMMUNITY_BUTTON))
                .bounds(leftPos + 220, topPos + 296, 164, 24).build());
    }

    private void addProfessionWidgets() {
        professionPage = Math.max(0, Math.min(professionPage, professionPageCount() - 1));
        int y = 79;
        for (CitizenProfession profession : visibleProfessions()) {
            if (profession.specialist()) {
                addRenderableWidget(Button.builder(Component.literal("−"),
                        button -> request(SettlementCoreMenu.professionDecreaseButton(profession)))
                        .bounds(leftPos + 349, topPos + y + 4, 24, 18).build());
                addRenderableWidget(Button.builder(Component.literal("+"),
                        button -> request(SettlementCoreMenu.professionIncreaseButton(profession)))
                        .bounds(leftPos + 380, topPos + y + 4, 24, 18).build());
            }
            y += 30;
        }
        Button previous = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.previous_page"), button -> {
            professionPage = Math.max(0, professionPage - 1);
            rebuildWidgets();
        }).bounds(leftPos + 80, professionFooterY(), 88, 18).build());
        previous.active = professionPage > 0;
        Button next = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.next_page"), button -> {
            professionPage = Math.min(professionPageCount() - 1, professionPage + 1);
            rebuildWidgets();
        }).bounds(leftPos + 252, professionFooterY(), 88, 18).build());
        next.active = professionPage + 1 < professionPageCount();
    }

    private int professionFooterY() {
        return Math.min(topPos + 304, height - 48);
    }

    private int professionsPerPage() {
        return Math.max(1, (professionFooterY() - (topPos + 79) - 6) / 30);
    }

    private int professionPageCount() {
        return Math.max(1, (CitizenProfession.values().length + professionsPerPage() - 1) / professionsPerPage());
    }

    private List<CitizenProfession> visibleProfessions() {
        List<CitizenProfession> professions = List.of(CitizenProfession.values());
        int start = Math.min(professions.size(), professionPage * professionsPerPage());
        return professions.subList(start, Math.min(professions.size(), start + professionsPerPage()));
    }

    private void addCatalogWidgets() {
        int start = catalogPage * CATALOG_PAGE_SIZE;
        int end = Math.min(SettlementCoreMenu.BUILDING_CHOICES.length, start + CATALOG_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int x = 18 + (local % 2) * 200;
            int y = 79 + (local / 2) * 47;
            final int id = index;
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.select"),
                    button -> request(id)).bounds(leftPos + x + 133, topPos + y + 17, 48, 18).build());
        }
        if (SettlementCoreMenu.BUILDING_CHOICES.length > CATALOG_PAGE_SIZE) {
            int pages = (SettlementCoreMenu.BUILDING_CHOICES.length + CATALOG_PAGE_SIZE - 1) / CATALOG_PAGE_SIZE;
            Button previous = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.previous_page"), button -> {
                catalogPage = Math.max(0, catalogPage - 1); rebuildWidgets();
            }).bounds(leftPos + 116, topPos + 268, 88, 18).build());
            previous.active = catalogPage > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.next_page"), button -> {
                catalogPage = Math.min(pages - 1, catalogPage + 1); rebuildWidgets();
            }).bounds(leftPos + 216, topPos + 268, 88, 18).build());
            next.active = catalogPage + 1 < pages;
        }
        addRoadButton(1, 18);
        addRoadButton(2, 116);
        addRoadButton(3, 214);
        addRenderableWidget(Button.builder(Component.literal("桥梁"),
                button -> request(SettlementCoreMenu.BRIDGE_BUTTON))
                .bounds(leftPos + 312, topPos + 296, 90, 22)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "只需框选桥体，系统自动适配两岸桥头和高度，确认后才施工。首点方向为施工岸。"))).build());
    }

    private void addRoadButton(int level, int x) {
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.plan_road_level", level),
                button -> request(SettlementCoreMenu.ROAD_LEVEL_1_BUTTON + level - 1))
                .bounds(leftPos + x, topPos + 296, 90, 22).build());
    }

    private void addManagementWidgets() {
        addRenderableWidget(Button.builder(Component.literal("从当前投影创建建筑"),
                button -> minecraft.setScreen(new ProjectionImportScreen(this)))
                .bounds(leftPos + 105, topPos + 266, 210, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.management.enter"),
                button -> request(SettlementCoreMenu.MANAGEMENT_VIEW_BUTTON))
                .bounds(leftPos + 105, topPos + 196, 210, 30).build());
        addRenderableWidget(Button.builder(Component.translatable(SeasonClientConfig.BOUNDARY.get()
                        ? "gui.exiledsettlement.boundary.hide" : "gui.exiledsettlement.boundary.show"), button -> {
                    ClientPlacementController.toggleBoundary(); rebuildWidgets();
                }).bounds(leftPos + 105, topPos + 236, 210, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.exiledsettlement.boundary.hint"))).build());
    }

    private void addInventoryWidgets() {
        addRenderableWidget(Button.builder(Component.literal("刷新仓库"),button -> requestDetails())
                .bounds(leftPos+145,topPos+271,80,18).build());
        addRenderableWidget(Button.builder(Component.literal("统一库存管理"),button -> minecraft.setScreen(
                new WarehouseManagementScreen(this,menu.corePosition()))).bounds(leftPos+235,topPos+271,165,18).build());
        List<SettlementDetailsPayload.BuildingEntry> warehouses = warehouseEntries();
        int start = managementPage * MANAGEMENT_PAGE_SIZE;
        int end = Math.min(warehouses.size(), start + MANAGEMENT_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            SettlementDetailsPayload.BuildingEntry entry = warehouses.get(index);
            int y = 80 + (index - start) * 31;
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.warehouse.manage"),
                    button -> PacketDistributor.sendToServer(new RequestBuildingManagementPayload(
                            menu.corePosition(), entry.id())))
                    .bounds(leftPos + 338, topPos + y + 4, 62, 18).build());
        }
        int pages = Math.max(1, (warehouses.size() + MANAGEMENT_PAGE_SIZE - 1) / MANAGEMENT_PAGE_SIZE);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                managementPage = Math.max(0, managementPage - 1); rebuildWidgets();
            }).bounds(leftPos + 18, topPos + 271, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                managementPage = Math.min(pages - 1, managementPage + 1); rebuildWidgets();
            }).bounds(leftPos + 48, topPos + 271, 24, 18).build());
        }
    }

    private void addTabButton(Tab target, int x, String key) {
        addRenderableWidget(Button.builder(Component.translatable(key), button -> {
            tab = target;
            if (target == Tab.MANAGEMENT || target == Tab.INVENTORY) requestDetails();
            rebuildWidgets();
        }).bounds(leftPos + x, topPos + 50, 78, 20).build());
    }

    private void request(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void requestDetails() {
        detailsReceived = false;
        PacketDistributor.sendToServer(new RequestSettlementDetailsPayload(menu.corePosition()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        bordered(graphics, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL, BORDER);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 44, HEADER);
        switch (tab) {
            case OVERVIEW -> renderOverview(graphics);
            case PROFESSIONS -> renderProfessions(graphics);
            case CATALOG -> renderCatalog(graphics);
            case MANAGEMENT -> renderManagement(graphics);
            case INVENTORY -> renderInventory(graphics);
        }
    }

    private void renderOverview(GuiGraphics graphics) {
        SettlementLevel[] levels = SettlementLevel.values();
        SettlementLevel level = levels[Math.max(0, Math.min(levels.length - 1, menu.levelOrdinal()))];
        overviewCard(graphics, 28, 84, "gui.exiledsettlement.core.level", Component.translatable(level.translationKey()));
        overviewCard(graphics, 218, 84, "gui.exiledsettlement.core.population", Component.literal(menu.aliveCitizens() + " / " + menu.populationCap()));
        overviewCard(graphics, 28, 136, "gui.exiledsettlement.core.housing", Component.literal(menu.aliveCitizens() + " / " + menu.housingCapacity()));
        overviewCard(graphics, 218, 136, "gui.exiledsettlement.core.food", Component.literal(
                (menu.availableFood() == 32767 ? "∞" : Integer.toString(menu.availableFood()))
                        + " / " + menu.recruitmentCost()));
        overviewCard(graphics, 28, 188, "gui.exiledsettlement.core.operational_buildings",
                Component.literal(menu.operationalBuildings() + " / " + (menu.nextLevelBuildingRequirement() < 0 ? "—" : menu.nextLevelBuildingRequirement())));
        overviewCard(graphics, 218, 188, "gui.exiledsettlement.core.pending", Component.literal(Integer.toString(menu.pendingBlueprints())));
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.territory",
                        menu.boundaryRadiusChunks(), menu.nextBoundaryRadiusChunks()),
                leftPos + imageWidth / 2, topPos + 224, MUTED);
    }

    private void overviewCard(GuiGraphics graphics, int x, int y, String label, Component value) {
        bordered(graphics, leftPos + x, topPos + y, leftPos + x + 174, topPos + y + 42, CARD, 0xFF8B6B42);
        graphics.drawString(font, Component.translatable(label), leftPos + x + 10, topPos + y + 7, MUTED, false);
        graphics.drawString(font, value, leftPos + x + 10, topPos + y + 23, TEXT, false);
    }

    private void renderProfessions(GuiGraphics graphics) {
        int y = topPos + 79;
        for (CitizenProfession profession : visibleProfessions()) {
            bordered(graphics, leftPos + 18, y, leftPos + 404, y + 26, CARD, 0xFF8B6B42);
            graphics.drawString(font, Component.translatable(profession.translationKey()), leftPos + 29, y + 5, TEXT, false);
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.core.profession_numbers",
                    menu.professionTarget(profession), menu.professionActual(profession), menu.professionCapacity(profession)),
                    leftPos + 140, y + 9, MUTED, false);
            y += 30;
        }
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.page_number",
                professionPage + 1, professionPageCount()), leftPos + imageWidth / 2, professionFooterY() + 5, TEXT);
    }

    private void renderCatalog(GuiGraphics graphics) {
        int start = catalogPage * CATALOG_PAGE_SIZE;
        int end = Math.min(SettlementCoreMenu.BUILDING_CHOICES.length, start + CATALOG_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int x = leftPos + 18 + (local % 2) * 200;
            int y = topPos + 79 + (local / 2) * 47;
            BuildingCatalogEntry entry = SettlementCoreMenu.BUILDING_CATALOG[index];
            BuildingType type = entry.type();
            bordered(graphics, x, y, x + 188, y + 41, CARD, 0xFF8B6B42);
            graphics.renderItem(buildingIcon(type), x + 8, y + 12);
            graphics.drawString(font, Component.translatable(entry.translationKey()), x + 31, y + 6, TEXT, false);
            Component dimensions;
            if (type.isDynamicArea()) {
                dimensions = Component.translatable("gui.exiledsettlement.area.catalog_size", type.minimumAreaSize());
            } else {
                var template = BlueprintTemplates.create(type, BlockPos.ZERO, Direction.NORTH, entry.templateVersion());
                dimensions = Component.literal(template.width() + "×" + template.depth() + "×" + template.height());
            }
            graphics.drawString(font, dimensions, x + 31, y + 23, MUTED, false);
        }
        for (int level = 1; level <= 3; level++) graphics.renderItem(roadIcon(level), leftPos + 60 + (level - 1) * 105, topPos + 299);
    }

    private void renderManagement(GuiGraphics graphics) {
        graphics.renderItem(new ItemStack(Items.SPYGLASS), leftPos + 197, topPos + 98);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.management.title"),
                leftPos + imageWidth / 2, topPos + 126, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.management.description"),
                leftPos + imageWidth / 2, topPos + 150, MUTED);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.management.description_2"),
                leftPos + imageWidth / 2, topPos + 166, MUTED);
    }

    private void renderInventory(GuiGraphics graphics) {
        List<SettlementDetailsPayload.BuildingEntry> warehouses = warehouseEntries();
        int start = managementPage * MANAGEMENT_PAGE_SIZE;
        int end = Math.min(warehouses.size(), start + MANAGEMENT_PAGE_SIZE);
        if (warehouses.isEmpty()) {
            graphics.drawCenteredString(font, detailsReceived
                            ? Component.translatable("gui.exiledsettlement.warehouse.none")
                            : Component.literal("正在读取已登记仓库…"),
                    leftPos + imageWidth / 2, topPos + 120, MUTED);
            return;
        }
        for (int index = start; index < end; index++) {
            int y = topPos + 80 + (index - start) * 31;
            SettlementDetailsPayload.BuildingEntry entry = warehouses.get(index);
            bordered(graphics, leftPos + 18, y, leftPos + 404, y + 26, CARD, 0xFF8B6B42);
            graphics.drawString(font, displayName(entry), leftPos + 27, y + 5, TEXT, false);
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.capacity",
                            entry.usedSlots(), entry.type()==com.hkin.exiledsettlement.building.BuildingType.WAREHOUSE
                            || entry.type()==com.hkin.exiledsettlement.building.BuildingType.MARKET
                            ? com.hkin.exiledsettlement.building.WarehouseStock.CAPACITY : entry.capacity()), leftPos + 174, y + 5,
                    entry.operational() ? 0xFF287A31 : 0xFFA13A2A, false);
            if (entry.creativeSupply()) {
                graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.creative_supply_short"),
                        leftPos + 174, y + 14, 0xFF8A35C1, false);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(font, title, imageWidth / 2, 9, 0xFFF8E8C8);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.summary",
                menu.aliveCitizens(), menu.totalCitizens(), menu.operationalBuildings(), menu.activeBuildings(), menu.pendingBlueprints()),
                imageWidth / 2, 27, 0xFFFFF4DA);
    }

    private SettlementDetailsPayload.BuildingEntry selectedEntry() {
        if (selectedBuildingId == null) return null;
        return buildingDetails.stream().filter(entry -> entry.id().equals(selectedBuildingId)).findFirst().orElse(null);
    }

    private List<SettlementDetailsPayload.BuildingEntry> warehouseEntries() {
        return buildingDetails.stream().filter(entry -> entry.type().isStorage()).toList();
    }

    private static Component displayName(SettlementDetailsPayload.BuildingEntry entry) {
        return entry.name().isBlank() ? Component.translatable(entry.type().translationKey()) : Component.literal(entry.name());
    }

    private static ItemStack buildingIcon(BuildingType type) {
        return switch (type) {
            case HOUSE, HOUSE_2, HOUSE_3, SPRUCE_HOME, GALLERY_HOME, COURTYARD_HOME, STARTER_CAMP -> new ItemStack(Blocks.RED_BED);
            case ADVANCED_HOUSE_1, ADVANCED_HOUSE_2, ADVANCED_HOUSE_3 -> new ItemStack(Blocks.BRICKS);
            case WAREHOUSE, MARKET -> new ItemStack(Blocks.BARREL);
            case FARM_FIELD -> new ItemStack(Items.WHEAT_SEEDS);
            case PASTURE_AREA -> new ItemStack(Blocks.OAK_FENCE);
            case HUNTING_LODGE -> new ItemStack(Blocks.SMOKER);
            case TOOL_WORKSHOP -> new ItemStack(Blocks.ANVIL);
            case FORESTER_LODGE -> new ItemStack(Blocks.OAK_SAPLING);
            case TREE_FARM -> new ItemStack(Blocks.OAK_LEAVES);
            case TOWN_HALL -> new ItemStack(Blocks.LECTERN);
            case FISHING_HUT -> new ItemStack(Items.COD);
            case MINE -> new ItemStack(Items.IRON_PICKAXE);
            case SAWMILL -> new ItemStack(Blocks.STONECUTTER);
            case SCHOOL -> new ItemStack(Blocks.BOOKSHELF);
            case APARTMENT -> new ItemStack(Blocks.WHITE_BED);
            case BRICK_KILN -> new ItemStack(Blocks.FURNACE);
            case CHURCH -> new ItemStack(Blocks.BREWING_STAND);
            case MELON_FIELD -> new ItemStack(Items.MELON_SEEDS);
            case TAVERN -> new ItemStack(Items.HONEY_BOTTLE);
            case RESTAURANT -> new ItemStack(Items.COOKED_BEEF);
            case LEISURE_GARDEN -> new ItemStack(Blocks.POPPY);
            case LAMP_SMALL, LAMP_ARM, LAMP_DOUBLE -> new ItemStack(Blocks.LANTERN);
            case BENCH -> new ItemStack(Blocks.SPRUCE_STAIRS);
            case FLOWERBED -> new ItemStack(Blocks.FLOWER_POT);
            case SIGNPOST -> new ItemStack(Items.SPRUCE_SIGN);
            case WELL -> new ItemStack(Items.WATER_BUCKET);
            case WOODSHED -> new ItemStack(Blocks.SPRUCE_LOG);
            case WAGON -> new ItemStack(Blocks.CHEST);
        };
    }

    private static ItemStack roadIcon(int level) {
        return level == 1 ? new ItemStack(Items.DIRT)
                : new ItemStack(level == 2 ? Blocks.GRAVEL : Blocks.STONE_BRICKS);
    }

    private static void bordered(GuiGraphics graphics, int left, int top, int right, int bottom, int fill, int border) {
        graphics.fill(left, top, right, bottom, border);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, fill);
    }

    private enum Tab { OVERVIEW, PROFESSIONS, CATALOG, MANAGEMENT, INVENTORY }
}

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
    private int planningCategory;
    private boolean warehouseList;
    private com.hkin.exiledsettlement.network.CommunityLedgerData.Page ledgerPage;
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
        if (tab == Tab.INVENTORY && !warehouseList) requestLedger(ledgerPage == null ? 0 : ledgerPage.index());
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
        addTabButton(Tab.PROFESSIONS, 110, "gui.exiledsettlement.core.professions");
        addTabButton(Tab.CATALOG, 214, "gui.exiledsettlement.core.buildings");
        addTabButton(Tab.INVENTORY, 318, "gui.exiledsettlement.core.inventory");

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
                .bounds(leftPos + 36, topPos + 264, 164, 24)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                        "gui.exiledsettlement.ui.upgrade_details", menu.operationalBuildings(),
                        menu.nextLevelBuildingRequirement() < 0 ? "—" : menu.nextLevelBuildingRequirement(),
                        menu.nextBoundaryRadiusChunks()))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.ui.recruit_cost", menu.recruitmentCost()),
                button -> request(SettlementCoreMenu.RECRUIT_BUTTON))
                .bounds(leftPos + 220, topPos + 264, 164, 24).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.optimize_housing"),
                button -> request(SettlementCoreMenu.OPTIMIZE_HOUSING_BUTTON))
                .bounds(leftPos + 105, topPos + 296, 210, 24).build());
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
        addPlanningTabs();
        for (int category = 0; category < BuildingPlanningCatalog.CATEGORY_COUNT; category++) {
            final int selected = category;
            var button = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.planning.category." + category), b -> {
                planningCategory = selected; catalogPage = 0; rebuildWidgets();
            }).bounds(leftPos + 18 + category * 78, topPos + 99, 75, 18).build());
            button.active = planningCategory != category;
        }
        if (planningCategory == 3) {
            for (int index = 0; index < 4; index++) {
                final int id = index < 3 ? SettlementCoreMenu.ROAD_LEVEL_1_BUTTON + index : SettlementCoreMenu.BRIDGE_BUTTON;
                addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.select"), b -> request(id))
                        .bounds(leftPos + 151 + (index % 2) * 200, topPos + 140 + (index / 2) * 60, 48, 18).build());
            }
            return;
        }
        var choices = planningIndices();
        catalogPage = Math.clamp(catalogPage, 0, BuildingPlanningCatalog.pages(choices.size()) - 1);
        int start = catalogPage * BuildingPlanningCatalog.PAGE_SIZE;
        for (int index = start; index < Math.min(choices.size(), start + BuildingPlanningCatalog.PAGE_SIZE); index++) {
            int local = index - start, x = 18 + (local % 2) * 200, y = 123 + (local / 2) * 47;
            final int id = choices.get(index);
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.select"),
                    b -> request(id)).bounds(leftPos + x + 133, topPos + y + 17, 48, 18).build());
        }
        int pages = BuildingPlanningCatalog.pages(choices.size());
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {catalogPage--;rebuildWidgets();})
                .bounds(leftPos + 116, topPos + 269, 34, 18).build());
        previous.active = catalogPage > 0;
        var next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {catalogPage++;rebuildWidgets();})
                .bounds(leftPos + 270, topPos + 269, 34, 18).build());
        next.active = catalogPage + 1 < pages;
        if (planningCategory == 4) {
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.planning.fill_pits"),
                    b -> PacketDistributor.sendToServer(new com.hkin.exiledsettlement.network.CommunityActionPayload(menu.corePosition(), "works_fill_pits")))
                    .bounds(leftPos + 18, topPos + 182, 188, 22)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.exiledsettlement.planning.fill_pits_hint"))).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.planning.projection"),
                    b -> minecraft.setScreen(new SchematicImportScreen(this, menu.corePosition(), menu.boundaryRadiusChunks())))
                    .bounds(leftPos + 218, topPos + 182, 188, 22).build());
        }
    }

    private List<Integer> planningIndices() {
        return BuildingPlanningCatalog.indices(SettlementCoreMenu.BUILDING_CHOICES, planningCategory);
    }

    private void addPlanningTabs() {
        var plan = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.planning.new"), b -> {
            tab = Tab.CATALOG; rebuildWidgets();
        }).bounds(leftPos + 105, topPos + 75, 100, 18).build());
        plan.active = tab != Tab.CATALOG;
        var manage = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.manage_buildings"), b -> {
            tab = Tab.MANAGEMENT; rebuildWidgets();
        }).bounds(leftPos + 215, topPos + 75, 100, 18).build());
        manage.active = tab != Tab.MANAGEMENT;
    }

    private void addRoadButton(int level, int x) {
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.plan_road_level", level),
                button -> request(SettlementCoreMenu.ROAD_LEVEL_1_BUTTON + level - 1))
                .bounds(leftPos + x, topPos + 296, 90, 22).build());
    }

    private void addManagementWidgets() {
        addPlanningTabs();
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

    public static boolean receiveLedger(com.hkin.exiledsettlement.network.CommunitySnapshotPayload payload) {
        if (!(Minecraft.getInstance().screen instanceof SettlementCoreScreen screen)
                || !screen.menu.corePosition().equals(payload.core())) return false;
        screen.ledgerPage = com.hkin.exiledsettlement.network.CommunityLedgerData.decode(payload.lines());
        // Late replies update the cache without changing the user's current tab or subpage.
        if (screen.tab == Tab.INVENTORY && !screen.warehouseList) screen.rebuildWidgets();
        return true;
    }

    private void requestLedger(int page) {
        PacketDistributor.sendToServer(new com.hkin.exiledsettlement.network.CommunityActionPayload(menu.corePosition(), "ledger:" + page));
    }

    private void addInventoryWidgets() {
        var stock = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.inventory.flows"), b -> {
            warehouseList = false; requestLedger(ledgerPage == null ? 0 : ledgerPage.index()); rebuildWidgets();
        }).bounds(leftPos + 18, topPos + 76, 92, 18).build()); stock.active = warehouseList;
        var list = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.inventory.warehouses"), b -> {
            warehouseList = true; requestDetails(); rebuildWidgets();
        }).bounds(leftPos + 116, topPos + 76, 92, 18).build()); list.active = !warehouseList;
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.inventory.rules"),
                b -> minecraft.setScreen(new WarehouseManagementScreen(this, menu.corePosition())))
                .bounds(leftPos + 214, topPos + 76, 92, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.ledger.management"),
                b -> PacketDistributor.sendToServer(new com.hkin.exiledsettlement.network.CommunityActionPayload(menu.corePosition(), "management")))
                .bounds(leftPos + 312, topPos + 76, 92, 18).build());
        if (warehouseList) { addWarehouseListWidgets(); return; }
        int page = ledgerPage == null ? 0 : ledgerPage.index();
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> requestLedger(page - 1))
                .bounds(leftPos + 18, topPos + 291, 30, 18).build()); previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(Component.literal(">"), b -> requestLedger(page + 1))
                .bounds(leftPos + 54, topPos + 291, 30, 18).build());
        next.active = ledgerPage != null && page + 1 < ledgerPage.pages();
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.ledger.refresh"), b -> requestLedger(page))
                .bounds(leftPos + 330, topPos + 291, 74, 18).build());
    }

    private void renderInventory(GuiGraphics graphics) {
        if (warehouseList) { renderWarehouseList(graphics); return; }
        String[] headers = {"item", "stock", "outgoing", "incoming", "change"};
        int[] xs = {18, 142, 211, 280, 349};
        for (int i = 0; i < headers.length; i++) graphics.drawString(font,
                Component.translatable("gui.exiledsettlement.ledger." + headers[i]), leftPos + xs[i], topPos + 101, MUTED, false);
        if (ledgerPage == null) {
            graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.inventory.loading"), leftPos + 210, topPos + 174, MUTED); return;
        }
        for (int i = 0; i < ledgerPage.rows().size(); i++) {
            var row = ledgerPage.rows().get(i); int y = topPos + 116 + i * 14;
            graphics.fill(leftPos + 16, y - 2, leftPos + 405, y + 11, i % 2 == 0 ? 0x448B6B42 : 0x228B6B42);
            var item = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(row.item())));
            String name = item.isEmpty() ? row.item() : item.getHoverName().getString();
            graphics.drawString(font, font.plainSubstrByWidth(name, 116), leftPos + 18, y, TEXT, false);
            ledgerNumber(graphics, row.stock(), 142, y, false, TEXT);
            ledgerNumber(graphics, row.outgoing(), 211, y, false, 0xFF9A3C25);
            ledgerNumber(graphics, row.incoming(), 280, y, false, 0xFF287A31);
            ledgerNumber(graphics, row.change(), 349, y, true, row.change() < 0 ? 0xFF9A3C25 : 0xFF287A31);
        }
        if (ledgerPage.rows().isEmpty()) graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.ledger.empty"), leftPos + 210, topPos + 174, MUTED);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.page_number", ledgerPage.index() + 1, ledgerPage.pages()), leftPos + 210, topPos + 296, TEXT);
        graphics.drawString(font, Component.translatable(ledgerPage.partial() ? "gui.exiledsettlement.ledger.day_partial" : "gui.exiledsettlement.ledger.day", ledgerPage.day() + 1), leftPos + 18, topPos + 314, MUTED, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.inventory.scope"), leftPos + 340, topPos + 314, MUTED, false);
    }

    private void ledgerNumber(GuiGraphics graphics, long value, int x, int y, boolean signed, int color) {
        String text = (signed && value > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%,d", value);
        float scale = Math.min(1, 54f / Math.max(1, font.width(text)));
        graphics.pose().pushPose(); graphics.pose().translate(leftPos + x, y, 0); graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, false); graphics.pose().popPose();
    }

    private void renderLedgerTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (tab != Tab.INVENTORY || warehouseList || ledgerPage == null) return;
        if (mouseX >= leftPos + 330 && mouseX <= leftPos + 405 && mouseY >= topPos + 312 && mouseY < topPos + 326) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable(ledgerPage.legacyStores() > 0
                    ? "gui.exiledsettlement.ledger.scope_legacy" : "gui.exiledsettlement.ledger.scope_daily", ledgerPage.legacyStores())), mouseX, mouseY); return;
        }
        int index = (mouseY - topPos - 114) / 14;
        if (mouseY < topPos + 114 || mouseX < leftPos + 16 || mouseX > leftPos + 405 || index >= ledgerPage.rows().size()) return;
        var row = ledgerPage.rows().get(index);
        var item = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(row.item())));
        graphics.renderComponentTooltip(font, List.of(item.isEmpty() ? Component.literal(row.item()) : item.getHoverName(),
                Component.translatable("gui.exiledsettlement.ledger.stock").append(": " + row.stock()),
                Component.translatable("gui.exiledsettlement.ledger.outgoing").append(": " + row.outgoing()),
                Component.translatable("gui.exiledsettlement.ledger.incoming").append(": " + row.incoming()),
                Component.translatable("gui.exiledsettlement.ledger.change").append(": " + row.change())), mouseX, mouseY);
    }

    private void addWarehouseListWidgets() {
        addRenderableWidget(Button.builder(Component.literal("刷新仓库"),button -> requestDetails())
                .bounds(leftPos+145,topPos+291,80,18).build());
        List<SettlementDetailsPayload.BuildingEntry> warehouses = warehouseEntries();
        int start = managementPage * MANAGEMENT_PAGE_SIZE;
        int end = Math.min(warehouses.size(), start + MANAGEMENT_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            SettlementDetailsPayload.BuildingEntry entry = warehouses.get(index);
            int y = 103 + (index - start) * 27;
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.warehouse.manage"),
                    button -> PacketDistributor.sendToServer(new RequestBuildingManagementPayload(
                            menu.corePosition(), entry.id())))
                    .bounds(leftPos + 338, topPos + y + 4, 62, 18).build());
        }
        int pages = Math.max(1, (warehouses.size() + MANAGEMENT_PAGE_SIZE - 1) / MANAGEMENT_PAGE_SIZE);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                managementPage = Math.max(0, managementPage - 1); rebuildWidgets();
            }).bounds(leftPos + 18, topPos + 291, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                managementPage = Math.min(pages - 1, managementPage + 1); rebuildWidgets();
            }).bounds(leftPos + 48, topPos + 291, 24, 18).build());
        }
    }

    private void addTabButton(Tab target, int x, String key) {
        var button = addRenderableWidget(Button.builder(Component.translatable(key), b -> {
            tab = target;
            if (target == Tab.INVENTORY) {
                warehouseList = false; requestLedger(ledgerPage == null ? 0 : ledgerPage.index());
            }
            rebuildWidgets();
        }).bounds(leftPos + x, topPos + 50, 96, 20).build());
        button.active = tab != target && !(target == Tab.CATALOG && tab == Tab.MANAGEMENT);
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
        renderLedgerTooltip(graphics, mouseX, mouseY);
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
        bordered(graphics, leftPos + 218, topPos + 84, leftPos + 392, topPos + 126, CARD, 0xFF8B6B42);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ui.population", menu.aliveCitizens()),
                leftPos + 228, topPos + 91, TEXT, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ui.population_limits",
                menu.populationCap(), menu.housingCapacity()), leftPos + 228, topPos + 107, MUTED, false);
        overviewCard(graphics, 28, 136, "gui.exiledsettlement.ui.food", Component.literal(
                menu.availableFood() == 32767 ? "∞" : Integer.toString(menu.availableFood())));
        overviewCard(graphics, 218, 136, "gui.exiledsettlement.ui.buildings", Component.literal(Integer.toString(menu.operationalBuildings())));
        overviewCard(graphics, 28, 188, "gui.exiledsettlement.core.pending", Component.literal(Integer.toString(menu.pendingBlueprints())));
        overviewCard(graphics, 218, 188, "gui.exiledsettlement.ui.territory", Component.translatable(
                "gui.exiledsettlement.ui.chunks", menu.boundaryRadiusChunks()));
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
                    menu.professionActual(profession), menu.professionCapacity(profession)),
                    leftPos + 140, y + 9, MUTED, false);
            y += 30;
        }
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.page_number",
                professionPage + 1, professionPageCount()), leftPos + imageWidth / 2, professionFooterY() + 5, TEXT);
    }

    private void renderCatalog(GuiGraphics graphics) {
        if (planningCategory == 3) {
            for (int index = 0; index < 4; index++) {
                int x = leftPos + 18 + (index % 2) * 200, y = topPos + 123 + (index / 2) * 60;
                bordered(graphics, x, y, x + 188, y + 41, CARD, 0xFF8B6B42);
                graphics.renderItem(index < 3 ? roadIcon(index + 1) : new ItemStack(Items.STONE_BRICKS), x + 8, y + 12);
                graphics.drawString(font, index < 3 ? Component.translatable("gui.exiledsettlement.core.plan_road_level", index + 1)
                        : Component.translatable("gui.exiledsettlement.planning.bridge"), x + 31, y + 6, TEXT, false);
            }
            return;
        }
        var choices = planningIndices();
        int start = catalogPage * BuildingPlanningCatalog.PAGE_SIZE;
        int end = Math.min(choices.size(), start + BuildingPlanningCatalog.PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int x = leftPos + 18 + (local % 2) * 200;
            int y = topPos + 123 + (local / 2) * 47;
            BuildingCatalogEntry entry = SettlementCoreMenu.BUILDING_CATALOG[choices.get(index)];
            BuildingType type = entry.type();
            bordered(graphics, x, y, x + 188, y + 41, CARD, 0xFF8B6B42);
            graphics.renderItem(buildingIcon(type), x + 8, y + 12);
            graphics.drawString(font, Component.translatable(entry.translationKey()), x + 31, y + 6, TEXT, false);
            Component dimensions;
            if (BlueprintTemplates.flexibleProduction(type,entry.templateVersion())
                    || type.isDynamicArea() && !BlueprintTemplates.fixedProduction(type,entry.templateVersion())) {
                dimensions = Component.translatable("gui.exiledsettlement.area.catalog_size", type.minimumAreaSize(),
                        com.hkin.exiledsettlement.blueprint.BlueprintService.MAX_DYNAMIC_AREA_BLOCKS);
            } else {
                var template = BlueprintTemplates.create(type, BlockPos.ZERO, Direction.NORTH, entry.templateVersion());
                dimensions = Component.literal(template.width() + "×" + template.depth() + "×" + template.height());
            }
            graphics.drawString(font, dimensions, x + 31, y + 23, MUTED, false);
        }
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.page_number",
                catalogPage + 1, BuildingPlanningCatalog.pages(choices.size())), leftPos + 210, topPos + 274, TEXT);
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

    private void renderWarehouseList(GuiGraphics graphics) {
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
            int y = topPos + 103 + (index - start) * 27;
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
        if (tab != Tab.OVERVIEW) graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.summary",
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

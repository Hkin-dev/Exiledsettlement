package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.building.WarehouseCategory;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.network.BuildingActionPayload;
import com.hkin.exiledsettlement.network.BuildingStaffPayload;
import com.hkin.exiledsettlement.network.OpenBuildingManagementPayload;
import com.hkin.exiledsettlement.network.RenameBuildingPayload;
import com.hkin.exiledsettlement.network.RequestBuildingManagementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public final class BuildingManagementScreen extends Screen {
    private static final int WIDTH = 380;
    private static final int HEIGHT = 322;
    private static final int PAGE_SIZE = 5;
    private static final int WAREHOUSE_PAGE_SIZE = 4;

    private OpenBuildingManagementPayload payload;
    @Nullable
    private final Screen parent;
    private int inventoryPage;
    private int personnelPage;
    private int snapshotTicks;
    private boolean warehousePersonnelView;
    @Nullable
    private EditBox nameField;

    private BuildingManagementScreen(OpenBuildingManagementPayload payload, @Nullable Screen parent) {
        super(Component.translatable("gui.exiledsettlement.building_management.title"));
        this.payload = payload;
        this.parent = parent;
    }

    public static void open(OpenBuildingManagementPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.refreshOnly() && (!(minecraft.screen instanceof BuildingManagementScreen current)
                || !current.payload.settlementId().equals(payload.settlementId())
                || !current.payload.building().id().equals(payload.building().id()))) return;
        if (minecraft.screen instanceof BuildingManagementScreen screen
                && screen.payload.building().id().equals(payload.building().id())) {
            String editingName = screen.nameField != null && screen.nameField.isFocused()
                    ? screen.nameField.getValue() : null;
            screen.payload = payload;
            ClientPlacementController.updateManagedBuilding(payload.building());
            int inventoryPageSize = (payload.building().type().isStorage() || payload.building().type()==BuildingType.MINE)
                    ? WAREHOUSE_PAGE_SIZE : PAGE_SIZE;
            screen.inventoryPage = Math.min(screen.inventoryPage,
                    Math.max(0, (payload.inventory().size() - 1) / inventoryPageSize));
            screen.personnelPage = Math.min(screen.personnelPage,
                    Math.max(0, (payload.personnel().size() - 1) / screen.personnelPageSize()));
            screen.rebuildWidgets();
            if (editingName != null && screen.nameField != null) {
                screen.nameField.setValue(editingName);
                screen.setFocused(screen.nameField);
            }
            return;
        }
        ClientPlacementController.updateManagedBuilding(payload.building());
        Screen parent = minecraft.screen instanceof SettlementCoreScreen ? null : minecraft.screen;
        minecraft.setScreen(new BuildingManagementScreen(payload, parent));
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Workforce counts are live snapshots, not frozen at the moment the GUI opened.
        if (++snapshotTicks % 20 == 0 && (nameField == null || !nameField.isFocused())) {
            PacketDistributor.sendToServer(new RequestBuildingManagementPayload(
                    payload.corePosition(), payload.building().id(), true));
        }
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int left = width / 2 - WIDTH / 2;
        int top = height / 2 - HEIGHT / 2;
        nameField = null;
        if (!payload.building().blueprint()) {
            nameField = new EditBox(font, left + 78, top + 45, 190, 20,
                    Component.translatable("gui.exiledsettlement.core.building_name"));
            nameField.setMaxLength(32);
            nameField.setValue(displayName());
            addRenderableWidget(nameField);
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.rename_building"), button -> {
                if (nameField != null && !nameField.getValue().isBlank()) {
                    PacketDistributor.sendToServer(new RenameBuildingPayload(
                            payload.corePosition(), payload.building().id(), nameField.getValue()));
                }
            }).bounds(left + 276, top + 45, 84, 20).build());
        }

        if (payload.building().blueprint()
                && payload.building().status() != com.hkin.exiledsettlement.building.BuildingStatus.CANCELLATION_QUEUED) {
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.exiledsettlement.building_management.cancel_construction"),
                    button -> send(BuildingActionPayload.Action.CANCEL_CONSTRUCTION, 0, air()))
                    .bounds(left + 140, top + 72, 100, 20).build());
        } else if (!payload.building().blueprint() && !payload.building().demolitionQueued()
                && payload.building().type() != BuildingType.WAGON) {
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.building_management.demolish"),
                    button -> send(BuildingActionPayload.Action.QUEUE_DEMOLITION, 0, air()))
                    .bounds(left + 276, top + 72, 84, 20).build());
        }
        boolean warehouse = !payload.building().blueprint() && (payload.building().type().isStorage() || payload.building().type()==BuildingType.MINE);
        if (warehouse) {
            addWarehouseViewButtons(left, top);
            if (warehousePersonnelView) addWarehouseStaffingWidgets(left, top);
            else addWarehouseWidgets(left, top);
        }
        if (!payload.building().blueprint() && payload.building().type() == BuildingType.TOWN_HALL) {
            addLedgerWidgets(left, top);
        }
        if (!payload.building().blueprint() && (payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA)) {
            addProductionWidgets(left, top);
        }
        if(payload.building().type()==BuildingType.TREE_FARM || payload.building().type()==BuildingType.PASTURE_AREA){
            var region=ClientRegionCache.get(payload.building().id());
            var groundButton=Button.builder(Component.literal(region!=null && region.status.contains("已暂缓")?"继续地面施工":"暂缓地面施工"),b->send(BuildingActionPayload.Action.TOGGLE_GROUND,0,air()))
                .bounds(left+110,top+96,110,20).build();
            if(region!=null)groundButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(region.status)));
            addRenderableWidget(groundButton);
        }
        if (worksiteTaskMode()) {
            addWorksitePriorityWidgets(left, top);
        } else if (!payload.building().blueprint() && !warehouse) {
            addStaffingWidgets(left, top);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.close"), button -> onClose())
                .bounds(left + 150, top + HEIGHT - 27, 80, 18).build());
    }

    private void addWorksitePriorityWidgets(int left, int top) {
        WorksiteAssignmentMode nextMode = payload.building().worksiteAssignmentMode()
                == WorksiteAssignmentMode.AUTO ? WorksiteAssignmentMode.MANUAL : WorksiteAssignmentMode.AUTO;
        addRenderableWidget(Button.builder(Component.translatable(
                        payload.building().worksiteAssignmentMode() == WorksiteAssignmentMode.AUTO
                                ? "gui.exiledsettlement.building_management.worksite_mode_auto"
                                : "gui.exiledsettlement.building_management.worksite_mode_manual"),
                ignored -> send(BuildingActionPayload.Action.SET_WORKSITE_MODE, nextMode.ordinal(), air()))
                .bounds(left + 252, top + (payload.building().blueprint() ? 137 : 88), 108, 18).build());
        int pageSize = personnelPageSize();
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + worksiteRowStart() + (index - start) * 25;
            Component action = Component.translatable(entry.releasePending()
                    ? "gui.exiledsettlement.building_management.worksite_release_pending"
                    : entry.lockedHere()
                    ? "gui.exiledsettlement.building_management.worksite_unassign"
                    : "gui.exiledsettlement.building_management.worksite_assign");
            Button button = Button.builder(action, ignored -> PacketDistributor.sendToServer(
                            new BuildingStaffPayload(payload.corePosition(), payload.building().id(),
                                    entry.citizenId(), entry.lockedHere(),
                                    payload.building().configurationRevision())))
                    .bounds(left + 252, y - 4, 108, 18).build();
            button.active = !entry.releasePending() && (entry.lockedHere() || (!entry.lockedElsewhere()
                    && payload.building().lockedWorkers() < payload.building().capacity()));
            addRenderableWidget(button);
        }
        int pages = Math.max(1, (payload.personnel().size() + pageSize - 1) / pageSize);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                personnelPage = Math.max(0, personnelPage - 1); rebuildWidgets();
            }).bounds(left + 20, top + HEIGHT - 52, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                personnelPage = Math.min(pages - 1, personnelPage + 1); rebuildWidgets();
            }).bounds(left + 50, top + HEIGHT - 52, 24, 18).build());
        }
    }

    private void addProductionWidgets(int left, int top) {
        if(payload.building().type()==BuildingType.BRICK_KILN) {
            var recipe=com.hkin.exiledsettlement.building.KilnRecipes.forOutput(BuiltInRegistries.ITEM.get(payload.building().productionId()));
            addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.kiln.choose"),
                    b -> minecraft.setScreen(new KilnProductScreen(this,payload.building().productionId(),id -> send(BuildingActionPayload.Action.SET_PRODUCTION,0,id))))
                    .bounds(left+58,top+137,112,20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(KilnProductScreen.details(recipe))).build());
            return;
        }
        java.util.List<ResourceLocation> choices = payload.building().type() == BuildingType.FARM_FIELD
                ? BuildingProductionService.FARM_PRODUCTIONS : payload.building().type() == BuildingType.MELON_FIELD
                ? BuildingProductionService.MELON_CROPS : payload.building().type() == BuildingType.BRICK_KILN
                ? BuildingProductionService.KILN_PRODUCTS : BuildingProductionService.PASTURE_ANIMALS;
        int current = Math.max(0, choices.indexOf(payload.building().productionId()));
        ResourceLocation previous = choices.get(Math.floorMod(current - 1, choices.size()));
        ResourceLocation next = choices.get(Math.floorMod(current + 1, choices.size()));
        addRenderableWidget(Button.builder(Component.literal("<"),
                button -> send(BuildingActionPayload.Action.SET_PRODUCTION, 0, previous))
                .bounds(left + 58, top + 137, 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                button -> send(BuildingActionPayload.Action.SET_PRODUCTION, 0, next))
                .bounds(left + 294, top + 137, 28, 20).build());
    }

    private void addStaffingWidgets(int left, int top) {
        if (payload.building().staffingProfessionOrdinal() < 0) {
            Button unavailable = Button.builder(
                    Component.translatable("gui.exiledsettlement.building_management.staffing_unavailable"),
                    button -> {}).bounds(left + 104, top + 96, 172, 20).build();
            unavailable.active = false;
            addRenderableWidget(unavailable);
            return;
        }
        Button decrease = Button.builder(Component.literal("−"), button -> send(
                        BuildingActionPayload.Action.SET_WORKER_TARGET,
                        Math.max(payload.building().lockedWorkers(), payload.building().workerTarget() - 1), air()))
                .bounds(left + 104, top + 96, 24, 20).build();
        decrease.active = payload.building().workerTarget() > payload.building().lockedWorkers();
        addRenderableWidget(decrease);
        Button increase = Button.builder(Component.literal("+"), button -> send(
                        BuildingActionPayload.Action.SET_WORKER_TARGET,
                        Math.min(payload.building().capacity(), payload.building().workerTarget() + 1), air()))
                .bounds(left + 252, top + 96, 24, 20).build();
        increase.active = payload.building().operational()
                && payload.building().workerTarget() < payload.building().capacity();
        addRenderableWidget(increase);

        int rowStart = payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA ? 202 : 146;
        int pageSize = personnelPageSize();
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + rowStart + (index - start) * 23;
            Component action = Component.translatable(entry.releasePending()
                    ? "gui.exiledsettlement.building_management.unlock_pending"
                    : entry.lockedHere()
                    ? "gui.exiledsettlement.building_management.unlock"
                    : entry.assignedHere()
                    ? "gui.exiledsettlement.building_management.lock_current"
                    : "gui.exiledsettlement.building_management.assign_lock");
            Button personnelButton = Button.builder(action, button -> PacketDistributor.sendToServer(
                            new BuildingStaffPayload(payload.corePosition(), payload.building().id(),
                                    entry.citizenId(), entry.lockedHere(),
                                    payload.building().configurationRevision())))
                    .bounds(left + 266, y - 3, 94, 18).build();
            personnelButton.active = !entry.releasePending() && (entry.lockedHere() || (!entry.lockedElsewhere()
                    && payload.building().operational()
                    && payload.building().lockedWorkers() < payload.building().capacity()));
            addRenderableWidget(personnelButton);
        }
        int pages = Math.max(1, (payload.personnel().size() + pageSize - 1) / pageSize);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                personnelPage = Math.max(0, personnelPage - 1); rebuildWidgets();
            }).bounds(left + 20, top + HEIGHT - 52, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                personnelPage = Math.min(pages - 1, personnelPage + 1); rebuildWidgets();
            }).bounds(left + 50, top + HEIGHT - 52, 24, 18).build());
        }
    }

    private void addWarehouseWidgets(int left, int top) {
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.warehouse.deposit_all"),
                button -> send(BuildingActionPayload.Action.DEPOSIT_ALL, 0, air()))
                .bounds(left + 20, top + 91, 112, 20).build());
        if(payload.building().type()!=BuildingType.MINE) {
        addLimitButtons(left, top, true, payload.toolStockLimit(), 91);
        addLimitButtons(left, top, false, payload.equipmentStockLimit(), 116);
        if(payload.building().type()==BuildingType.WAREHOUSE || payload.building().type()==BuildingType.MARKET) {
            addRenderableWidget(Button.builder(Component.literal("统一库存与余量规则"),b->minecraft.setScreen(
                    new WarehouseManagementScreen(this,payload.corePosition()))).bounds(left+20,top+143,340,20).build());
        } else for (WarehouseCategory category : WarehouseCategory.values()) {
            int x = left + 20 + category.ordinal() * 86;
            boolean accepted = (payload.building().warehouseCategoryMask() & category.bit()) != 0;
            addRenderableWidget(Button.builder(Component.translatable(
                            accepted ? "gui.exiledsettlement.warehouse.category_allowed"
                                    : "gui.exiledsettlement.warehouse.category_blocked",
                            Component.translatable(category.translationKey())),
                    button -> send(BuildingActionPayload.Action.TOGGLE_CATEGORY, category.ordinal(), air()))
                    .bounds(x, top + 143, 80, 20).build());
        }

        }
        int start = inventoryPage * WAREHOUSE_PAGE_SIZE;
        int end = Math.min(payload.inventory().size(), start + WAREHOUSE_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.InventoryEntry entry = payload.inventory().get(index);
            int y = top + 195 + (index - start) * 22;
            Button withdraw = Button.builder(Component.translatable("gui.exiledsettlement.warehouse.withdraw"),
                    button -> send(BuildingActionPayload.Action.WITHDRAW_STACK, 0, entry.itemId()))
                    .bounds(left + 294, y - 2, 66, 18).build();
            withdraw.active = !entry.itemId().equals(BuiltInRegistries.ITEM.getKey(
                    com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get()));
            addRenderableWidget(withdraw);
        }
        int pages = Math.max(1, (payload.inventory().size() + WAREHOUSE_PAGE_SIZE - 1) / WAREHOUSE_PAGE_SIZE);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                inventoryPage = Math.max(0, inventoryPage - 1); rebuildWidgets();
            }).bounds(left + 20, top + 278, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                inventoryPage = Math.min(pages - 1, inventoryPage + 1); rebuildWidgets();
            }).bounds(left + 50, top + 278, 24, 18).build());
        }
    }

    private void addWarehouseViewButtons(int left, int top) {
        Button inventory = addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.core.inventory"), button -> {
                    warehousePersonnelView = false;
                    rebuildWidgets();
                }).bounds(left + 94, top + 168, 92, 18).build());
        inventory.active = warehousePersonnelView;
        Button personnel = addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.core.professions"), button -> {
                    warehousePersonnelView = true;
                    rebuildWidgets();
                }).bounds(left + 194, top + 168, 92, 18).build());
        personnel.active = !warehousePersonnelView;
    }

    private void addWarehouseStaffingWidgets(int left, int top) {
        if (payload.building().staffingProfessionOrdinal() < 0) return;
        Button decrease = Button.builder(Component.literal("−"), button -> send(
                        BuildingActionPayload.Action.SET_WORKER_TARGET,
                        Math.max(payload.building().lockedWorkers(), payload.building().workerTarget() - 1), air()))
                .bounds(left + 104, top + 137, 24, 20).build();
        decrease.active = payload.building().workerTarget() > payload.building().lockedWorkers();
        addRenderableWidget(decrease);
        Button increase = Button.builder(Component.literal("+"), button -> send(
                        BuildingActionPayload.Action.SET_WORKER_TARGET,
                        Math.min(payload.building().capacity(), payload.building().workerTarget() + 1), air()))
                .bounds(left + 252, top + 137, 24, 20).build();
        increase.active = payload.building().operational()
                && payload.building().workerTarget() < payload.building().capacity();
        addRenderableWidget(increase);

        int pageSize = 3;
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + 218 + (index - start) * 23;
            Component action = Component.translatable(entry.releasePending()
                    ? "gui.exiledsettlement.building_management.unlock_pending"
                    : entry.lockedHere() ? "gui.exiledsettlement.building_management.unlock"
                    : entry.assignedHere() ? "gui.exiledsettlement.building_management.lock_current"
                    : "gui.exiledsettlement.building_management.assign_lock");
            Button button = Button.builder(action, ignored -> PacketDistributor.sendToServer(
                            new BuildingStaffPayload(payload.corePosition(), payload.building().id(),
                                    entry.citizenId(), entry.lockedHere(),
                                    payload.building().configurationRevision())))
                    .bounds(left + 266, y - 3, 94, 18).build();
            button.active = !entry.releasePending() && (entry.lockedHere() || (!entry.lockedElsewhere()
                    && payload.building().operational()
                    && payload.building().lockedWorkers() < payload.building().capacity()));
            addRenderableWidget(button);
        }
        int pages = Math.max(1, (payload.personnel().size() + pageSize - 1) / pageSize);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                personnelPage = Math.max(0, personnelPage - 1); rebuildWidgets();
            }).bounds(left + 20, top + 278, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                personnelPage = Math.min(pages - 1, personnelPage + 1); rebuildWidgets();
            }).bounds(left + 50, top + 278, 24, 18).build());
        }
    }

    private void addLimitButtons(int left, int top, boolean tools, int value, int y) {
        BuildingActionPayload.Action action = tools
                ? BuildingActionPayload.Action.SET_TOOL_LIMIT : BuildingActionPayload.Action.SET_EQUIPMENT_LIMIT;
        addRenderableWidget(Button.builder(Component.literal("−"),
                button -> send(action, Math.max(0, value - 16), air()))
                .bounds(left + 146, top + y, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"),
                button -> send(action, Math.min(4096, value + 16), air()))
                .bounds(left + 338, top + y, 22, 20).build());
    }

    private void send(BuildingActionPayload.Action action, int value, ResourceLocation itemId) {
        PacketDistributor.sendToServer(new BuildingActionPayload(
                payload.corePosition(), payload.building().id(), action, value,
                payload.building().configurationRevision(), itemId));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - WIDTH / 2;
        int top = height / 2 - HEIGHT / 2;
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xF2D8C79C);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF4A321E);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + 35, 0xFF6A472A);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.literal(displayName()), width / 2, top + 12, 0xFFF8E8C8);
        int typeStatusY = payload.building().blueprint() ? top + 51 : top + 73;
        graphics.drawString(font, Component.translatable(payload.building().type().translationKey()),
                left + 20, typeStatusY, 0xFF2C2118, false);
        graphics.drawString(font, Component.translatable(payload.building().status().translationKey()),
                left + (payload.building().blueprint() ? 220 : 120), typeStatusY,
                payload.building().operational() ? 0xFF287A31 : 0xFFA13A2A, false);
        if (!payload.building().blueprint()) {
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.core.building_name"),
                    left + 20, top + 51, 0xFF665647, false);
        } else {
            graphics.drawCenteredString(font, Component.translatable(
                            "gui.exiledsettlement.building_management.construction_progress",
                            payload.building().constructionProgress(), payload.building().constructionTotal()),
                    width / 2, top + 108, 0xFF2C2118);
            Component diagnostic = payload.building().repairCount() > 0
                    ? Component.translatable("gui.exiledsettlement.building_management.footing_cleanup_remaining",
                    payload.building().repairCount())
                    : Component.translatable("gui.exiledsettlement.building_management.construction_remaining",
                    Math.max(0, payload.building().constructionTotal()
                            - payload.building().constructionProgress()));
            graphics.drawCenteredString(font, Component.literal("待放"+payload.building().remainingPlacements()+" · 地基/支撑"+payload.building().foundationPlacements()+" · 准备"+payload.building().preparationActions()+" · 建议"+payload.building().suggestedWorkers()+"人"),width/2,top+119,0xFF665647);
            graphics.drawCenteredString(font, Component.translatable(
                            "gui.exiledsettlement.building_management.cancel_cleanup_hint"),
                    width / 2, top + 130, 0xFF665647);
        }
        boolean warehouse = !payload.building().blueprint() && (payload.building().type().isStorage() || payload.building().type()==BuildingType.MINE);
        if (warehouse) {
            if (warehousePersonnelView) renderWarehouseStaffing(graphics, left, top);
            else renderWarehouse(graphics, left, top);
        }
        if (!payload.building().blueprint() && payload.building().type() == BuildingType.TOWN_HALL) {
            renderTownHallLedger(graphics, left, top);
        }
        if (!payload.building().blueprint() && (payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA)) {
            renderProduction(graphics, left, top);
        }
        if (worksiteTaskMode()) renderWorksitePriority(graphics, left, top);
        else if (!payload.building().blueprint() && !warehouse) renderStaffing(graphics, left, top);
    }

    private void renderWorksitePriority(GuiGraphics graphics, int left, int top) {
        graphics.drawCenteredString(font, Component.translatable(
                        "gui.exiledsettlement.building_management.worksite_priority_summary",
                        payload.building().lockedWorkers(), payload.building().assignedWorkers(),
                        payload.building().arrivedWorkers(), payload.building().workingWorkers()),
                width / 2, top + (payload.building().blueprint() ? 158 : 112), 0xFF2C2118);
        int pageSize = personnelPageSize();
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + worksiteRowStart() + (index - start) * 25;
            int color = entry.blocked() ? 0xFFA13A2A : entry.lockedHere() ? 0xFF287A31 : 0xFF2C2118;
            graphics.drawString(font, Component.literal(entry.name()), left + 20, y, color, false);
            graphics.drawString(font, Component.translatable(entry.releasePending()
                            ? "gui.exiledsettlement.building_management.worksite_release_pending"
                            : entry.assignedHere()
                            ? "gui.exiledsettlement.building_management.worksite_active"
                            : entry.lockedHere()
                            ? "gui.exiledsettlement.building_management.worksite_waiting"
                            : "gui.exiledsettlement.building_management.worksite_available"),
                    left + 128, y, color, false);
        }
    }

    private boolean worksiteTaskMode() {
        return payload.building().blueprint() || payload.building().demolitionQueued();
    }

    private int personnelPageSize() {
        if (worksiteTaskMode()) return payload.building().blueprint() ? 4 : 5;
        return payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA ? 3 : 4;
    }

    private int worksiteRowStart() {
        return payload.building().blueprint() ? 174 : 137;
    }

    private void renderStaffing(GuiGraphics graphics, int left, int top) {
        if (payload.building().staffingProfessionOrdinal() < 0) {
            graphics.drawCenteredString(font, Component.translatable(
                            "gui.exiledsettlement.building_management.staffing_not_connected"),
                    width / 2, top + 121, 0xFF8A5B16);
            return;
        }
        graphics.drawCenteredString(font, Component.translatable(
                        "gui.exiledsettlement.building_management.staffing_target",
                        payload.building().workerTarget(),
                        payload.building().capacity()),
                width / 2, top + 102, 0xFF2C2118);
        boolean productionArea = payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA;
        graphics.drawCenteredString(font, Component.translatable(
                        "gui.exiledsettlement.building_management.staffing_summary",
                        payload.building().assignedWorkers(), payload.building().arrivedWorkers(),
                        payload.building().workingWorkers(), payload.building().lockedWorkers()),
                width / 2, top + (productionArea ? 185 : 124), 0xFF2C2118);
        int rowStart = payload.building().type() == BuildingType.FARM_FIELD
                || payload.building().type() == BuildingType.MELON_FIELD
                || payload.building().type() == BuildingType.BRICK_KILN
                || payload.building().type() == BuildingType.PASTURE_AREA ? 202 : 146;
        int pageSize = personnelPageSize();
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + rowStart + (index - start) * 23;
            Component state = Component.translatable(entry.releasePending()
                    ? "gui.exiledsettlement.building_management.unlock_pending"
                    : entry.blocked()
                    ? "gui.exiledsettlement.building_management.locked_blocked"
                    : entry.lockedHere()
                    ? "gui.exiledsettlement.building_management.locked_here"
                    : entry.lockedElsewhere()
                    ? "gui.exiledsettlement.building_management.locked_elsewhere"
                    : entry.assignedHere()
                    ? "gui.exiledsettlement.building_management.auto_here"
                    : "gui.exiledsettlement.building_management.candidate");
            graphics.drawString(font, Component.literal(entry.name()), left + 20, y, 0xFF2C2118, false);
            graphics.drawString(font, state, left + 122, y,
                    entry.blocked() ? 0xFFAA3333 : entry.lockedHere() ? 0xFF8A5B16 : 0xFF287A31, false);
        }
    }

    private void renderProduction(GuiGraphics graphics, int left, int top) {
        BuildingType type = payload.building().type();
        ItemStack icon = new ItemStack(BuildingProductionService.displayItem(type, payload.building().productionId()));
        graphics.drawCenteredString(font, Component.translatable((type == BuildingType.FARM_FIELD || type == BuildingType.MELON_FIELD)
                        ? "gui.exiledsettlement.production.crop" : type == BuildingType.BRICK_KILN ? "gui.exiledsettlement.production.building_material" : type == BuildingType.MINE ? "gui.exiledsettlement.production.mineral" : "gui.exiledsettlement.production.animal"),
                width / 2, top + 123, 0xFF665647);
        graphics.renderItem(icon, left + 181, top + 135);
        Component productionName = type == BuildingType.MELON_FIELD
                ? Component.translatable("gui.exiledsettlement.production." + (payload.building().productionId().equals(BuildingProductionService.MIXED_MELONS)
                    ? "mixed_melons" : payload.building().productionId().equals(BuildingProductionService.PUMPKIN) ? "pumpkin" : "melon"))
                : type == BuildingType.FARM_FIELD
                ? (payload.building().productionId().equals(BuildingProductionService.MIXED_CROPS)
                    ? Component.translatable("gui.exiledsettlement.production.mixed_crops") : BuiltInRegistries.BLOCK.get(payload.building().productionId()).getName())
                : BuildingProductionService.animal(payload.building().productionId()) == null
                ? icon.getHoverName()
                : BuildingProductionService.animal(payload.building().productionId()).getDescription();
        graphics.drawCenteredString(font, productionName, width / 2, top + 160, 0xFF2C2118);
        net.minecraft.network.chat.MutableComponent detail = Component.empty();
        if (type == BuildingType.BRICK_KILN) detail.append(Component.translatable("gui.exiledsettlement.kiln.target"));
        if (type == BuildingType.PASTURE_AREA) detail.append(Component.translatable(
                "gui.exiledsettlement.production.capacity", payload.building().productionCount(),
                payload.building().productionCapacity()));
        if (payload.building().repairCount() > 0) {
            if (!detail.getString().isEmpty()) detail.append(" | ");
            detail.append(Component.translatable("gui.exiledsettlement.production.repairs",
                    payload.building().repairCount()));
        }
        graphics.drawCenteredString(font, detail, width / 2, top + 173,
                payload.building().repairCount() > 0 ? 0xFFAA3333 : 0xFF287A31);
    }

    private void renderWarehouse(GuiGraphics graphics, int left, int top) {
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.capacity",
                        payload.building().usedSlots(), payload.building().type()==BuildingType.WAREHOUSE
                        || payload.building().type()==BuildingType.MARKET || payload.building().type()==BuildingType.MINE
                        ? com.hkin.exiledsettlement.building.WarehouseStock.CAPACITY : payload.building().capacity()),
                left + 220, top + 73, 0xFF2C2118, false);
        if(payload.building().type()==BuildingType.MINE) {
            graphics.drawCenteredString(font,Component.translatable("gui.exiledsettlement.mine.mixed"),width/2,top+143,0xFF665647);
        } else {
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.tool_limit",
                        payload.toolStockLimit()), left + 178, top + 97, 0xFF2C2118, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.equipment_limit",
                        payload.equipmentStockLimit()), left + 178, top + 122, 0xFF2C2118, false);
        }
        if (payload.building().creativeSupply()) {
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.warehouse.creative_supply"),
                    left + 20, top + 169, 0xFF8A35C1, false);
        }
        int start = inventoryPage * WAREHOUSE_PAGE_SIZE;
        int end = Math.min(payload.inventory().size(), start + WAREHOUSE_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.InventoryEntry entry = payload.inventory().get(index);
            int y = top + 195 + (index - start) * 22;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()));
            graphics.renderItem(stack, left + 20, y - 2);
            graphics.drawString(font, stack.getHoverName(), left + 44, y + 2, 0xFF2C2118, false);
            graphics.drawString(font, Component.literal("× " + entry.count()), left + 232, y + 2, 0xFF287A31, false);
        }
        if (payload.inventory().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.warehouse.empty"),
                    width / 2, top + 222, 0xFF665647);
        }
    }

    private void renderWarehouseStaffing(GuiGraphics graphics, int left, int top) {
        graphics.drawCenteredString(font, Component.translatable(
                        "gui.exiledsettlement.building_management.staffing_target",
                        payload.building().workerTarget(), payload.building().capacity()),
                width / 2, top + 143, 0xFF2C2118);
        graphics.drawCenteredString(font, Component.translatable(
                        "gui.exiledsettlement.building_management.staffing_summary",
                        payload.building().assignedWorkers(), payload.building().arrivedWorkers(),
                        payload.building().workingWorkers(), payload.building().lockedWorkers()),
                width / 2, top + 197, 0xFF2C2118);
        int pageSize = 3;
        int start = personnelPage * pageSize;
        int end = Math.min(payload.personnel().size(), start + pageSize);
        for (int index = start; index < end; index++) {
            OpenBuildingManagementPayload.PersonnelEntry entry = payload.personnel().get(index);
            int y = top + 218 + (index - start) * 23;
            Component state = Component.translatable(entry.releasePending()
                    ? "gui.exiledsettlement.building_management.unlock_pending"
                    : entry.blocked() ? "gui.exiledsettlement.building_management.locked_blocked"
                    : entry.lockedHere() ? "gui.exiledsettlement.building_management.locked_here"
                    : entry.lockedElsewhere() ? "gui.exiledsettlement.building_management.locked_elsewhere"
                    : entry.assignedHere() ? "gui.exiledsettlement.building_management.auto_here"
                    : "gui.exiledsettlement.building_management.candidate");
            graphics.drawString(font, Component.literal(entry.name()), left + 20, y, 0xFF2C2118, false);
            graphics.drawString(font, state, left + 122, y,
                    entry.blocked() ? 0xFFAA3333 : entry.lockedHere() ? 0xFF8A5B16 : 0xFF287A31, false);
        }
    }

    private void addLedgerWidgets(int left, int top) {
        int pages = Math.max(1, (payload.inventory().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (pages <= 1) return;
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> {
            inventoryPage = Math.max(0, inventoryPage - 1); rebuildWidgets();
        }).bounds(left + 248, top + 153, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> {
            inventoryPage = Math.min(pages - 1, inventoryPage + 1); rebuildWidgets();
        }).bounds(left + 276, top + 153, 24, 18).build());
    }

    private void renderTownHallLedger(GuiGraphics graphics, int left, int top) {
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.town_hall.ledger"),
                left + 20, top + 96, 0xFF2C2118, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.town_hall.columns"),
                left + 178, top + 96, 0xFF665647, false);
        int start = inventoryPage * PAGE_SIZE;
        int end = Math.min(payload.inventory().size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            var entry = payload.inventory().get(index);
            int y = top + 112 + (index - start) * 22;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(entry.itemId()));
            graphics.renderItem(stack, left + 20, y - 2);
            graphics.drawString(font, stack.getHoverName(), left + 44, y + 2, 0xFF2C2118, false);
            graphics.drawString(font, Component.literal(entry.count() + " / +" + entry.produced() + " / -" + entry.consumed()),
                    left + 220, y + 2, 0xFF287A31, false);
        }
        if (payload.inventory().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.warehouse.empty"),
                    width / 2, top + 135, 0xFF665647);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private String displayName() {
        if (payload.building().blueprint()) {
            return Component.translatable("gui.exiledsettlement.building_management.construction_name",
                    Component.translatable(payload.building().type().translationKey())).getString();
        }
        return payload.building().name().isBlank()
                ? Component.translatable(payload.building().type().translationKey()).getString()
                : payload.building().name();
    }

    private static ResourceLocation air() {
        return BuiltInRegistries.ITEM.getKey(Items.AIR);
    }
}

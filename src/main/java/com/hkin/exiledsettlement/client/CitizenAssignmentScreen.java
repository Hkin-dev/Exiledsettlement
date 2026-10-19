package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.compat.YsmCompatibility;
import com.hkin.exiledsettlement.network.AssignCitizenPayload;
import com.hkin.exiledsettlement.network.OpenCitizenAssignmentPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public final class CitizenAssignmentScreen extends Screen {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 515;
    private com.hkin.exiledsettlement.network.CitizenThermalPayload thermal;
    public static void receiveThermal(com.hkin.exiledsettlement.network.CitizenThermalPayload p) {
        if(Minecraft.getInstance().screen instanceof CitizenAssignmentScreen screen && screen.payload.citizenId().equals(p.citizen())) {
            screen.thermal=p;if(screen.preview!=null)screen.preview.setVisibleGarment(p.garment());
        }
    }
    private final OpenCitizenAssignmentPayload payload;
    private CitizenProfession profession;
    private int workplaceIndex;
    private SettlementCitizenEntity preview;
    private float uiScale = 1.0F;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private Component hoveredSlotLabel;

    private CitizenAssignmentScreen(OpenCitizenAssignmentPayload payload) {
        super(Component.translatable("gui.exiledsettlement.citizen.title", payload.citizenName()));
        this.payload = payload;
        this.profession = payload.profession();
        this.workplaceIndex = initialWorkplaceIndex();
    }

    public static void open(OpenCitizenAssignmentPayload payload) {
        Minecraft.getInstance().setScreen(new CitizenAssignmentScreen(payload));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        uiScale = Math.min(1.0F, Math.min((width - 12.0F) / WIDTH, (height - 12.0F) / HEIGHT));
        if (minecraft != null && minecraft.level != null) {
            preview = ModEntities.SETTLEMENT_CITIZEN.get().create(minecraft.level);
            if (preview != null) {
                preview.setItemSlot(EquipmentSlot.MAINHAND, payload.mainHand().copy());
                for (int slot = 0; slot < CitizenRecord.EQUIPMENT_SLOTS.size(); slot++) {
                    EquipmentSlot equipmentSlot = CitizenRecord.EQUIPMENT_SLOTS.get(slot);
                    preview.setItemSlot(equipmentSlot, equipmentSlot.isArmor() && !payload.armorWorn()
                            ? ItemStack.EMPTY : payload.equipment().get(slot).copy());
                }
                preview.setYsmModelSelection(payload.ysmModelId(), payload.ysmTextureName());
            }
        }
        rebuild();
    }

    private int panelLeft() { return ((int)(width / uiScale) - WIDTH) / 2; }
    private int panelTop() { return ((int)(height / uiScale) - HEIGHT) / 2; }

    private void rebuild() {
        clearWidgets();
        int left = panelLeft();
        int top = panelTop();
        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            cycleProfession(-1); rebuild();
        }).bounds(left + 20, top + 54, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            cycleProfession(1); rebuild();
        }).bounds(left + WIDTH - 50, top + 54, 30, 20).build());
        if (profession.specialist()) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                cycleWorkplace(-1); rebuild();
            }).bounds(left + 20, top + 105, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                cycleWorkplace(1); rebuild();
            }).bounds(left + WIDTH - 50, top + 105, 30, 20).build());
        }
        boolean valid = !profession.specialist() || selectedWorkplace() != null;
        if (YsmCompatibility.available()) addRenderableWidget(Button.builder(Component.translatable(
                        payload.ysmModelId().isBlank()
                                ? "gui.exiledsettlement.citizen.ysm_model_inactive"
                                : "gui.exiledsettlement.citizen.ysm_model_active"),
                button -> Minecraft.getInstance().setScreen(new CitizenYsmModelScreen(payload)))
                .bounds(left + 124, top + 435, 152, 22).build());
        Button apply = Button.builder(Component.translatable("gui.exiledsettlement.citizen.apply"), button -> apply())
                .bounds(left + 34, top + 463, 152, 22).build();
        apply.active = valid;
        addRenderableWidget(apply);
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.citizen.clear"), button -> clear())
                .bounds(left + 214, top + 463, 152, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.close"), button -> onClose())
                .bounds(left + 160, top + 491, 80, 18).build());
    }

    private void cycleProfession(int delta) {
        CitizenProfession[] values = CitizenProfession.values();
        profession = values[Math.floorMod(profession.ordinal() + delta, values.length)];
        workplaceIndex = compatibleWorkplaces().isEmpty() ? -1 : 0;
    }

    private void cycleWorkplace(int delta) {
        int size = compatibleWorkplaces().size();
        workplaceIndex = size == 0 ? -1 : Math.floorMod(workplaceIndex + delta, size);
    }

    private void apply() {
        OpenCitizenAssignmentPayload.WorkplaceEntry workplace = selectedWorkplace();
        PacketDistributor.sendToServer(new AssignCitizenPayload(payload.settlementId(), payload.citizenId(), false,
                profession, workplace == null ? null : workplace.id(),
                workplace == null ? 0 : workplace.configurationRevision()));
    }

    private void clear() {
        PacketDistributor.sendToServer(new AssignCitizenPayload(payload.settlementId(), payload.citizenId(), true,
                profession, null, currentWorkplaceRevision()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1.0F);
        mouseX = (int)(mouseX / uiScale);
        mouseY = (int)(mouseY / uiScale);
        hoveredStack = ItemStack.EMPTY;
        hoveredSlotLabel = null;
        int left = panelLeft();
        int top = panelTop();
        int center = left + WIDTH / 2;
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xF2D8C79C);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF4A321E);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + 35, 0xFF6A472A);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, center, top + 12, 0xFFF8E8C8);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.citizen.profession"), center, top + 43, 0xFF665647);
        graphics.drawCenteredString(font, Component.translatable(profession.translationKey()), center, top + 59, 0xFF2C2118);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.citizen.workplace"), center, top + 91, 0xFF665647);
        OpenCitizenAssignmentPayload.WorkplaceEntry workplace = selectedWorkplace();
        Component workplaceName = !profession.specialist()
                ? Component.translatable("gui.exiledsettlement.citizen.no_workplace_needed")
                : workplace == null ? Component.translatable("gui.exiledsettlement.citizen.no_compatible_workplace")
                : workplace.blueprint() ? Component.translatable(
                        "gui.exiledsettlement.citizen.construction_worksite",
                        Component.translatable(workplace.type().translationKey()))
                : workplace.name().isBlank() ? Component.translatable(workplace.type().translationKey()) : Component.literal(workplace.name());
        graphics.drawCenteredString(font, workplaceName, center, top + 110,
                workplace == null && profession.specialist() ? 0xFFAA3333 : 0xFF2C2118);
        graphics.drawCenteredString(font, Component.translatable(payload.manualReleasePending()
                        ? "gui.exiledsettlement.citizen.manual_release_pending"
                        : payload.manual() ? "gui.exiledsettlement.citizen.manual_active"
                        : "gui.exiledsettlement.citizen.automatic_active"),
                center, top + 137, payload.manual() ? 0xFF8A5B16 : 0xFF287A31);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.activity",
                        Component.translatable(payload.activity().translationKey())),
                left + 20, top + 151, 0xFF665647, false);
        if (!payload.workFailure().isBlank()) {
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.blocked",
                            workFailureLabel(payload.workFailure())),
                    left + 20, top + 164, 0xFFAA3333, false);
        }
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ui.resident_details"),
                left + 20, top + 177, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.nutrition", payload.nutrition()),
                left + 20, top + 190, 0xFF665647, false);
        Component life = payload.projectionLoaded() ? Component.translatable("gui.exiledsettlement.citizen.life",
                String.format(java.util.Locale.ROOT, "%.1f", payload.life()), String.format(java.util.Locale.ROOT, "%.1f", payload.maxLife()))
                : Component.translatable("gui.exiledsettlement.citizen.life_unloaded");
        graphics.drawString(font, life, left + 210, top + 190, 0xFF665647, false);
        if (thermal != null) {
            Component state = Component.translatable(thermal.body() < 36 ? "gui.exiledsettlement.ui.cold"
                    : thermal.wet() >= 0.5F ? "gui.exiledsettlement.ui.wet" : "gui.exiledsettlement.ui.comfortable");
            Component garment = Component.translatable(thermal.garment() == 1 ? "gui.exiledsettlement.ui.wool"
                    : thermal.garment() == 2 ? "gui.exiledsettlement.ui.leather" : "gui.exiledsettlement.ui.no_garment");
            graphics.drawString(font, Component.translatable("gui.exiledsettlement.ui.warmth", state, garment),
                    left + 20, top + 204, thermal.body() < 36 ? 0xFFAA3333 : 0xFF365846, false);
        }
        int xp = payload.professionExperience().get(profession.ordinal());
        int jobLevel = com.hkin.exiledsettlement.citizen.CitizenProfessionExperience.levelFor(xp);
        String progress = jobLevel == 4 ? "MAX" : com.hkin.exiledsettlement.citizen.CitizenProfessionExperience.progressFor(xp) + "/"
                + com.hkin.exiledsettlement.citizen.CitizenProfessionExperience.costFor(jobLevel);
        Component experience = Component.translatable("gui.exiledsettlement.citizen.profession_experience", jobLevel, progress,
                String.format(java.util.Locale.ROOT, "%s", jobLevel * 12.5));
        if (payload.profession() == CitizenProfession.TEACHER) experience = Component.empty().append(experience).append(
                Component.translatable("gui.exiledsettlement.citizen.teaching_points", payload.teachingPointsToday()));
        graphics.drawString(font, font.plainSubstrByWidth(experience.getString(), WIDTH - 40), left + 20, top + 243, 0xFF365846, false);
        renderEquipment(graphics, left, top + 253, mouseX, mouseY);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.inventory"),
                left + 110, top + 341, 0xFF2C2118, false);
        renderInventory(graphics, left + 110, top + 354, mouseX, mouseY);
        // Draw after all slots and the preview, so later items cannot cover tooltips.
        if (!hoveredStack.isEmpty()) graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
        else if (hoveredSlotLabel != null) graphics.renderTooltip(font, hoveredSlotLabel, mouseX, mouseY);
        else if (mouseX >= left + 20 && mouseX < left + WIDTH - 20
                && (mouseY >= top + 175 && mouseY < top + 187 || mouseY >= top + 202 && mouseY < top + 216)) {
            var details = new java.util.ArrayList<Component>();
            if (payload.targetPosition() != null) details.add(Component.translatable("gui.exiledsettlement.citizen.target_position",
                    payload.targetPosition().getX(), payload.targetPosition().getY(), payload.targetPosition().getZ()));
            if (thermal != null) {
                details.add(Component.translatable("gui.exiledsettlement.ui.thermal_details",
                        String.format(java.util.Locale.ROOT, "%.2f", thermal.body()),
                        thermal.change() > .001F ? "↑" : thermal.change() < -.001F ? "↓" : "→",
                        String.format(java.util.Locale.ROOT, "%.1f", thermal.ambient()), Math.round(thermal.wet() * 100)));
                if (thermal.maxDamage() > 0) details.add(Component.translatable("gui.exiledsettlement.ui.garment_durability",
                        Math.max(0, thermal.maxDamage() - thermal.damage()), thermal.maxDamage()));
                if (!thermal.reason().isBlank()) details.add(Component.literal(thermal.reason()));
            }
            if (!details.isEmpty()) graphics.renderComponentTooltip(font, details, mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The background is drawn once, before the panel. The Screen.render call
        // used for widgets must not blur or darken the already drawn panel.
    }

    private void renderInventory(GuiGraphics graphics, int startX, int startY, int mouseX, int mouseY) {
        for (int slot = 0; slot < 36; slot++) {
            int currentSlot = slot;
            int x = startX + (slot % 9) * 20;
            int y = startY + (slot / 9) * 20;
            OpenCitizenAssignmentPayload.InventoryEntry entry = payload.inventory().stream()
                    .filter(value -> value.slot() == currentSlot).findFirst().orElse(null);
            graphics.fill(x, y, x + 18, y + 18, entry != null && entry.active() ? 0xFFFFC857 : 0xFF8B6B42);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFFBCA77B);
            if (entry == null || entry.stack().isEmpty()) continue;
            ItemStack stack = entry.stack();
            graphics.renderItem(stack, x + 1, y + 1);
            graphics.renderItemDecorations(font, stack, x + 1, y + 1);
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                hoveredStack = stack;
            }
        }
    }

    private void renderEquipment(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        if (preview != null) InventoryScreen.renderEntityInInventoryFollowsMouse(
                graphics, left + 160, top, left + 240, top + 84, 36, 0.0625F, mouseX, mouseY, preview);
        String[] labels = {"head", "chest", "legs", "feet", "offhand", "mainhand"};
        // Match the player-style reference: armor around the body, hands outside.
        int[] slotX = {138, 138, 244, 244, 110, 272};
        int[] slotY = {6, 54, 6, 54, 30, 30};
        for (int slot = 0; slot < 6; slot++) {
            int x = left + slotX[slot];
            int y = top + slotY[slot];
            ItemStack stack = slot == 5 ? payload.mainHand() : payload.equipment().get(slot);
            graphics.fill(x, y, x + 18, y + 18, 0xFF8B6B42);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFFBCA77B);
            Component label = Component.translatable("gui.exiledsettlement.citizen.equipment." + labels[slot]);
            int labelX = slot == 0 || slot == 1 || slot == 4 ? x - font.width(label) - 4 : x + 23;
            graphics.drawString(font, label, labelX, y + 5, 0xFF665647, false);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x + 1, y + 1);
                graphics.renderItemDecorations(font, stack, x + 1, y + 1);
            }
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                hoveredSlotLabel = label;
                hoveredStack = stack;
            }
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(x / uiScale, y / uiScale, button);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return super.mouseReleased(x / uiScale, y / uiScale, button);
    }

    private Component workFailureLabel(String failure) {
        String[] detail = failure.split("\\|", 6);
        if (detail.length == 6 && (detail[0].equals("FARM_EARTHWORK") || detail[0].equals("TERRAIN_EARTHWORK"))) {
            return Component.translatable(detail[0].equals("TERRAIN_EARTHWORK")?"work_failure.exiledsettlement.terrain_earthwork_detail":"work_failure.exiledsettlement.farm_earthwork_detail",
                    detail[2],detail[3],detail[4],detail[5],workFailureLabel(detail[1]));
        }
        if (detail.length == 6 && detail[0].equals("SCAFFOLD_ACCESS")) {
            return Component.translatable("work_failure.exiledsettlement.scaffold_access_detail",
                    detail[2], detail[3], detail[4], detail[5], workFailureLabel(detail[1]));
        }
        return failure.matches("[A-Z0-9_]+")
                ? Component.translatable("work_failure.exiledsettlement." + failure.toLowerCase(java.util.Locale.ROOT))
                : Component.literal(failure);
    }

    private int initialWorkplaceIndex() {
        List<OpenCitizenAssignmentPayload.WorkplaceEntry> entries = compatibleWorkplaces();
        if (payload.workBuildingId() == null) return entries.isEmpty() ? -1 : 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(payload.workBuildingId())) return i;
        return entries.isEmpty() ? -1 : 0;
    }

    private List<OpenCitizenAssignmentPayload.WorkplaceEntry> compatibleWorkplaces() {
        if (!profession.specialist()) return List.of();
        return payload.workplaces().stream()
                .filter(entry -> !entry.blueprint() && profession.acceptsWorkplace(entry.type()))
                .toList();
    }

    private OpenCitizenAssignmentPayload.WorkplaceEntry selectedWorkplace() {
        List<OpenCitizenAssignmentPayload.WorkplaceEntry> entries = compatibleWorkplaces();
        return workplaceIndex >= 0 && workplaceIndex < entries.size() ? entries.get(workplaceIndex) : null;
    }

    private int currentWorkplaceRevision() {
        if (payload.workBuildingId() == null) return 0;
        return payload.workplaces().stream()
                .filter(entry -> entry.id().equals(payload.workBuildingId()))
                .mapToInt(OpenCitizenAssignmentPayload.WorkplaceEntry::configurationRevision)
                .findFirst().orElse(0);
    }
}

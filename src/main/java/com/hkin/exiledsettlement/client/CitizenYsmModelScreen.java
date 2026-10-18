package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.client.ysm.YsmModelCatalogAccess;
import com.hkin.exiledsettlement.client.ysm.YsmPlayerStateAccess;
import com.hkin.exiledsettlement.network.OpenCitizenAssignmentPayload;
import com.hkin.exiledsettlement.network.SetCitizenYsmModelPayload;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CitizenYsmModelScreen extends Screen {
    private static final int WIDTH = 560;
    private static final int HEIGHT = 310;
    private static final int ROWS = 9;
    private final OpenCitizenAssignmentPayload payload;
    private final List<Button> modelButtons = new ArrayList<>();
    private List<YsmModelCatalogAccess.ModelOption> models = List.of();
    private List<YsmModelCatalogAccess.ModelOption> filteredModels = List.of();
    private EditBox search;
    private Button textureButton;
    private SettlementCitizenEntity preview;
    private String selectedModel = "";
    private String selectedTexture = "";
    private int scrollOffset;
    private Component status = Component.empty();

    CitizenYsmModelScreen(OpenCitizenAssignmentPayload payload) {
        super(Component.translatable("gui.exiledsettlement.citizen.ysm_title", payload.citizenName()));
        this.payload = payload;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        search = new EditBox(font, left + 18, top + 48, 258, 20,
                Component.translatable("gui.exiledsettlement.citizen.ysm_search"));
        search.setHint(Component.translatable("gui.exiledsettlement.citizen.ysm_search_hint"));
        search.setResponder(ignored -> filterModels());
        addRenderableWidget(search);
        for (int row = 0; row < ROWS; row++) {
            int visibleRow = row;
            Button button = Button.builder(Component.empty(), ignored -> selectVisible(visibleRow))
                    .bounds(left + 18, top + 72 + row * 20, 258, 18).build();
            modelButtons.add(addRenderableWidget(button));
        }
        textureButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> cycleTexture())
                .bounds(left + 316, top + 225, 226, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.citizen.ysm_use_current"),
                button -> useCurrentPlayerModel())
                .bounds(left + 316, top + 249, 110, 18).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.citizen.ysm_refresh"),
                button -> reloadModels())
                .bounds(left + 432, top + 249, 110, 18).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.citizen.ysm_apply"),
                button -> apply())
                .bounds(left + 18, top + 278, 164, 22).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.citizen.ysm_disable"),
                button -> disable())
                .bounds(left + 198, top + 278, 164, 22).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.exiledsettlement.citizen.ysm_back"),
                button -> CitizenAssignmentScreen.open(payload))
                .bounds(left + 378, top + 278, 164, 22).build());

        if (minecraft != null && minecraft.level != null) {
            preview = ModEntities.SETTLEMENT_CITIZEN.get().create(minecraft.level);
            if (preview != null) {
                preview.setItemSlot(EquipmentSlot.MAINHAND, payload.mainHand().copy());
                for (int slot = 0; slot < CitizenRecord.EQUIPMENT_SLOTS.size(); slot++) {
                    preview.setItemSlot(CitizenRecord.EQUIPMENT_SLOTS.get(slot),
                            payload.equipment().get(slot).copy());
                }
            }
        }
        selectedModel = payload.ysmModelId();
        selectedTexture = payload.ysmTextureName();
        reloadModels();
        setInitialFocus(search);
    }

    private void reloadModels() {
        models = YsmModelCatalogAccess.models();
        if (!selectedModel.isBlank() && models.stream().noneMatch(model -> model.id().equals(selectedModel))) {
            models = withFallback(models, new YsmModelCatalogAccess.ModelOption(
                    selectedModel, selectedModel,
                    selectedTexture.isBlank() ? List.of() : List.of(
                            new YsmModelCatalogAccess.TextureOption(selectedTexture, selectedTexture))));
        }
        if (selectedModel.isBlank() && !models.isEmpty()) selectedModel = models.getFirst().id();
        filterModels();
        status = models.isEmpty()
                ? Component.translatable("gui.exiledsettlement.citizen.ysm_catalog_unavailable")
                : Component.translatable("gui.exiledsettlement.citizen.ysm_catalog_count", models.size());
    }

    private static List<YsmModelCatalogAccess.ModelOption> withFallback(
            List<YsmModelCatalogAccess.ModelOption> source,
            YsmModelCatalogAccess.ModelOption fallback
    ) {
        List<YsmModelCatalogAccess.ModelOption> result = new ArrayList<>(source.size() + 1);
        result.add(fallback);
        result.addAll(source);
        return List.copyOf(result);
    }

    private void filterModels() {
        String query = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        filteredModels = models.stream()
                .filter(model -> query.isEmpty()
                        || model.displayName().toLowerCase(Locale.ROOT).contains(query)
                        || model.id().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        scrollOffset = Math.min(scrollOffset, Math.max(0, filteredModels.size() - ROWS));
        updateModelButtons();
    }

    private void updateModelButtons() {
        for (int row = 0; row < modelButtons.size(); row++) {
            Button button = modelButtons.get(row);
            int index = scrollOffset + row;
            if (index >= filteredModels.size()) {
                button.visible = false;
                continue;
            }
            YsmModelCatalogAccess.ModelOption model = filteredModels.get(index);
            button.visible = true;
            button.active = !model.id().equals(selectedModel);
            String marker = model.id().equals(selectedModel) ? "▶ " : "";
            button.setMessage(Component.literal(marker + font.plainSubstrByWidth(model.displayName(), 226)));
        }
        updateTextureButton();
    }

    private void selectVisible(int row) {
        int index = scrollOffset + row;
        if (index < 0 || index >= filteredModels.size()) return;
        selectedModel = filteredModels.get(index).id();
        selectedTexture = "";
        status = Component.translatable("gui.exiledsettlement.citizen.ysm_selected");
        updateModelButtons();
    }

    private void cycleTexture() {
        YsmModelCatalogAccess.ModelOption model = selectedOption();
        if (model == null || model.textures().isEmpty()) return;
        int index = -1;
        for (int candidate = 0; candidate < model.textures().size(); candidate++) {
            if (model.textures().get(candidate).id().equals(selectedTexture)) {
                index = candidate;
                break;
            }
        }
        selectedTexture = index < 0 || index + 1 >= model.textures().size()
                ? "" : model.textures().get(index + 1).id();
        updateTextureButton();
    }

    private void updateTextureButton() {
        if (textureButton == null) return;
        YsmModelCatalogAccess.ModelOption model = selectedOption();
        textureButton.active = model != null && !model.textures().isEmpty();
        String textureName = selectedTexture;
        if (model != null) {
            textureName = model.textures().stream()
                    .filter(texture -> texture.id().equals(selectedTexture))
                    .map(YsmModelCatalogAccess.TextureOption::displayName)
                    .findFirst().orElse(selectedTexture);
        }
        textureButton.setMessage(Component.translatable(
                "gui.exiledsettlement.citizen.ysm_texture_value",
                selectedTexture.isBlank()
                        ? Component.translatable("gui.exiledsettlement.citizen.ysm_texture_default")
                        : Component.literal(textureName)));
    }

    private YsmModelCatalogAccess.ModelOption selectedOption() {
        return models.stream().filter(model -> model.id().equals(selectedModel)).findFirst().orElse(null);
    }

    private void useCurrentPlayerModel() {
        String current = minecraft == null ? null : YsmPlayerStateAccess.currentModelId(minecraft.player);
        if (current == null) {
            status = Component.translatable("gui.exiledsettlement.citizen.ysm_current_unavailable");
            return;
        }
        selectedModel = current;
        selectedTexture = "";
        if (models.stream().noneMatch(model -> model.id().equals(current))) {
            models = withFallback(models, new YsmModelCatalogAccess.ModelOption(current, current, List.of()));
            filterModels();
        } else {
            updateModelButtons();
        }
        status = Component.translatable("gui.exiledsettlement.citizen.ysm_current_copied");
    }

    private void apply() {
        String model = selectedModel.strip();
        String texture = selectedTexture.strip();
        if (model.isEmpty() || !CitizenRecord.isValidYsmSelection(model, texture)) {
            status = Component.translatable("gui.exiledsettlement.citizen.ysm_invalid");
            return;
        }
        PacketDistributor.sendToServer(new SetCitizenYsmModelPayload(
                payload.settlementId(), payload.citizenId(), model, texture));
        status = Component.translatable("gui.exiledsettlement.citizen.ysm_saving");
    }

    private void disable() {
        PacketDistributor.sendToServer(new SetCitizenYsmModelPayload(
                payload.settlementId(), payload.citizenId(), "", ""));
        status = Component.translatable("gui.exiledsettlement.citizen.ysm_saving");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xF2D8C79C);
        graphics.renderOutline(left, top, WIDTH, HEIGHT, 0xFF4A321E);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + 34, 0xFF6A472A);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, left + WIDTH / 2, top + 12, 0xFFF8E8C8);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.ysm_model_list"),
                left + 18, top + 38, 0xFF2C2118, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.citizen.ysm_preview"),
                left + 316, top + 38, 0xFF2C2118, false);
        graphics.drawCenteredString(font, status, left + WIDTH / 2, top + 268, 0xFF8A5B16);
        if (!selectedModel.isBlank()) {
            YsmModelCatalogAccess.ModelOption selected = selectedOption();
            String displayName = selected == null ? selectedModel : selected.displayName();
            graphics.drawCenteredString(font, font.plainSubstrByWidth(displayName, 220),
                    left + 429, top + 210, 0xFF2C2118);
        }
        if (preview != null) {
            preview.setYsmModelSelection(selectedModel, selectedTexture);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, left + 306, top + 46, left + 552, top + 208,
                    64, 0.0625F, mouseX, mouseY, preview);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        if (mouseX >= left + 18 && mouseX < left + 276 && mouseY >= top + 72 && mouseY < top + 252) {
            int next = scrollOffset - (int) Math.signum(scrollY);
            scrollOffset = Math.max(0, Math.min(next, Math.max(0, filteredModels.size() - ROWS)));
            updateModelButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        CitizenAssignmentScreen.open(payload);
    }

    @Override public boolean isPauseScreen() { return false; }
}

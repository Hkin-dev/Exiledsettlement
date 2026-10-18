package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.OpenCitizenRenamePayload;
import com.hkin.exiledsettlement.network.RenameCitizenPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class CitizenRenameScreen extends Screen {
    private final OpenCitizenRenamePayload payload;
    private EditBox name;
    private boolean submitted;
    public CitizenRenameScreen(OpenCitizenRenamePayload payload) {
        super(Component.translatable("gui.exiledsettlement.rename_citizen.title"));
        this.payload = payload;
    }
    public static void open(OpenCitizenRenamePayload payload) {
        // Keep the underlying resident panel and its selections. Native GUI layers
        // render it first, route input only to this dialog, and restore it on close.
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.screen == null) client.setScreen(new CitizenRenameScreen(payload));
        else client.pushGuiLayer(new CitizenRenameScreen(payload));
        client.mouseHandler.releaseMouse();
    }
    @Override protected void init() {
        int left = width / 2 - 140, top = height / 2 - 78;
        String previous = name == null ? payload.suggestedName() : name.getValue();
        name = new EditBox(font, left + 16, top + 65, 248, 20,
                Component.translatable("gui.exiledsettlement.rename_citizen.name"));
        name.setMaxLength(64); name.setValue(previous); addRenderableWidget(name);
        Button apply = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.rename_citizen.confirm"),
                button -> submit()).bounds(left + 16, top + 117, 118, 20).build());
        name.setResponder(value -> apply.active = valid(value));
        apply.active = valid(name.getValue());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 146, top + 117, 118, 20).build());
        setInitialFocus(name);
    }
    private boolean valid(String value) {
        return !value.strip().isEmpty() && value.length() <= 64
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private void submit() {
        if (submitted || !valid(name.getValue())) return;
        submitted = true;
        PacketDistributor.sendToServer(new RenameCitizenPayload(payload.token(), name.getValue(), false));
        super.onClose();
    }
    @Override public void onClose() {
        if (!submitted) {
            submitted = true;
            PacketDistributor.sendToServer(new RenameCitizenPayload(payload.token(), "", true));
        }
        super.onClose();
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render also calls renderBackground. Blurring here would blur the
        // resident panel underneath and this dialog's text drawn before super.render.
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = width / 2 - 140, top = height / 2 - 78;
        graphics.fill(left, top, left + 280, top + 156, 0xFF20272E);
        graphics.renderOutline(left, top, 280, 156, 0xFF8DC9FF);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFF);
        graphics.drawString(font, font.plainSubstrByWidth(Component.translatable(
                "gui.exiledsettlement.rename_citizen.current", payload.currentName()).getString(), 248), left + 16, top + 32, 0xCED6E0);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.rename_citizen.profession",
                Component.translatable(payload.professionKey())), left + 16, top + 47, 0x8DC9FF);
        graphics.drawWordWrap(font, Component.translatable("gui.exiledsettlement.rename_citizen.cost"), left + 16, top + 94, 248, 0xB7C2CE);
        // Render widgets after the panel so the text field and buttons remain visible.
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}

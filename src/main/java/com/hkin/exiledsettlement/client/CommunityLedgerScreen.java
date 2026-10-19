package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** The community landing page contains only stock movements; settings open separately. */
public final class CommunityLedgerScreen extends Screen {
    private static final int WIDTH = 680, HEIGHT = 350;
    private CommunitySnapshotPayload snapshot;
    private CommunityLedgerData.Page page;
    private final Screen parent;
    private float scale = 1;

    private CommunityLedgerScreen(CommunitySnapshotPayload snapshot, Screen parent) {
        super(Component.translatable("gui.exiledsettlement.ledger.title", snapshot.name()));
        this.parent = parent;
        update(snapshot);
    }
    private void update(CommunitySnapshotPayload value) { snapshot = value; page = CommunityLedgerData.decode(value.lines()); }
    public static void receive(CommunitySnapshotPayload value) {
        var game = Minecraft.getInstance();
        if (game.screen instanceof CommunityLedgerScreen current && current.snapshot.core().equals(value.core())) {
            current.update(value); current.rebuildWidgets();
        } else game.setScreen(new CommunityLedgerScreen(value, game.screen));
    }
    private int left() { return ((int)(width / scale) - WIDTH) / 2; }
    private int top() { return ((int)(height / scale) - HEIGHT) / 2; }
    private void send(String action) { PacketDistributor.sendToServer(new CommunityActionPayload(snapshot.core(), action)); }
    @Override protected void init() {
        scale = Math.min(1, Math.min((width - 12f) / WIDTH, (height - 12f) / HEIGHT));
        int x = left(), y = top();
        var previous = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.previous_page"),
                b -> send("ledger:" + (page.index() - 1))).bounds(x + 16, y + 283, 66, 20).build());
        previous.active = page.index() > 0;
        var next = addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.core.next_page"),
                b -> send("ledger:" + (page.index() + 1))).bounds(x + 88, y + 283, 66, 20).build());
        next.active = page.index() + 1 < page.pages();
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.ledger.refresh"),
                b -> send("ledger:" + page.index())).bounds(x + WIDTH - 170, y + 283, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + WIDTH - 92, y + 283, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.ledger.management"),
                b -> send("management")).bounds(x + WIDTH - 112, y + 13, 96, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.exiledsettlement.ledger.management_hint"))).build());
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        super.renderBackground(graphics, mouseX, mouseY, tick);
        graphics.pose().pushPose(); graphics.pose().scale(scale, scale, 1);
        mouseX = (int)(mouseX / scale); mouseY = (int)(mouseY / scale);
        int x = left(), y = top();
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF2D8C79C);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, 0xFF4A321E);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), WIDTH - 136), x + 16, y + 18, 0xFF2C2118, false);
        graphics.drawString(font, Component.translatable(page.partial() ? "gui.exiledsettlement.ledger.day_partial" : "gui.exiledsettlement.ledger.day", page.day() + 1), x + 16, y + 34, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ledger.item"), x + 16, y + 47, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ledger.stock"), x + 240, y + 47, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ledger.outgoing"), x + 350, y + 47, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ledger.incoming"), x + 460, y + 47, 0xFF665647, false);
        graphics.drawString(font, Component.translatable("gui.exiledsettlement.ledger.change"), x + 570, y + 47, 0xFF665647, false);
        java.util.List<Component> hovered = java.util.List.of();
        for (int i = 0; i < page.rows().size(); i++) {
            var row = page.rows().get(i);
            int rowY = y + 66 + i * 17;
            graphics.fill(x + 12, rowY - 1, x + WIDTH - 12, rowY + 16, i % 2 == 0 ? 0x448B6B42 : 0x228B6B42);
            var item = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.item())));
            graphics.renderItem(item, x + 16, rowY);
            Component label = item.isEmpty() ? Component.literal(row.item()) : item.getHoverName();
            graphics.drawString(font, font.plainSubstrByWidth(label.getString(), 190), x + 38, rowY + 4, 0xFF2C2118, false);
            drawCount(graphics, row.stock(), x + 240, rowY + 4, false, 0xFF2C2118);
            drawCount(graphics, row.outgoing(), x + 350, rowY + 4, false, 0xFF9A3C25);
            drawCount(graphics, row.incoming(), x + 460, rowY + 4, false, 0xFF287A31);
            drawCount(graphics, row.change(), x + 570, rowY + 4, true, row.change() < 0 ? 0xFF9A3C25 : 0xFF287A31);
            if (mouseX >= x + 16 && mouseX < x + WIDTH - 16 && mouseY >= rowY && mouseY < rowY + 16)
                hovered = java.util.List.of(label, Component.translatable("gui.exiledsettlement.ledger.row_details", row.stock(), row.outgoing(), row.incoming(), row.change()));
        }
        if (page.rows().isEmpty()) graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.ledger.empty"), x + WIDTH / 2, y + 150, 0xFF665647);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.core.page_number", page.index() + 1, page.pages()), x + WIDTH / 2, y + 289, 0xFF665647);
        graphics.drawWordWrap(font, Component.translatable(page.legacyStores() > 0 ? "gui.exiledsettlement.ledger.scope_legacy" : "gui.exiledsettlement.ledger.scope_daily", page.legacyStores()), x + 16, y + 313, WIDTH - 32, 0xFF665647);
        super.render(graphics, mouseX, mouseY, tick);
        if (!hovered.isEmpty()) graphics.renderComponentTooltip(font, hovered, mouseX, mouseY);
        graphics.pose().popPose();
    }
    private void drawCount(GuiGraphics graphics, long value, int x, int y, boolean signed, int color) {
        String text = (signed && value > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%,d", value);
        float size = Math.min(1, 94f / Math.max(1, font.width(text)));
        graphics.pose().pushPose(); graphics.pose().translate(x, y, 0); graphics.pose().scale(size, size, 1);
        graphics.drawString(font, text, 0, 0, color, false); graphics.pose().popPose();
    }
    @Override public boolean mouseClicked(double x, double y, int button) { return super.mouseClicked(x / scale, y / scale, button); }
    @Override public boolean mouseReleased(double x, double y, int button) { return super.mouseReleased(x / scale, y / scale, button); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

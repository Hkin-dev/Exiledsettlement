package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.building.BrewingRecipes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;
import java.util.function.Consumer;

/** The icons and hover details retain each potion's complete contents. */
public final class BrewingProductScreen extends Screen {
    private static final int WIDTH = 380;
    private static final int HEIGHT = 314;
    private static final int PAGE_SIZE = 6;
    private final Screen parent;
    private final ResourceLocation selected;
    private final Consumer<ResourceLocation> select;
    private int category;
    private int page;

    public BrewingProductScreen(Screen parent, ResourceLocation selected, Consumer<ResourceLocation> select) {
        super(Component.translatable("gui.exiledsettlement.brewing.choose"));
        this.parent = parent;
        this.selected = selected;
        this.select = select;
        category = category(BrewingRecipes.stack(selected));
        page = Math.max(0, products().indexOf(selected)) / PAGE_SIZE;
    }

    private static int category(ItemStack stack) {
        return stack.is(Items.LINGERING_POTION) ? 2 : stack.is(Items.SPLASH_POTION) ? 1 : 0;
    }

    private List<ResourceLocation> products() {
        return BrewingRecipes.products().stream().filter(id -> category(BrewingRecipes.stack(id)) == category).toList();
    }

    /** Vanilla item names omit the long/strong distinction, so expose it explicitly. */
    public static Component productName(ResourceLocation id) {
        ItemStack stack = BrewingRecipes.stack(id);
        var text = Component.empty().append(stack.getHoverName());
        String potion = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
                .flatMap(holder -> holder.unwrapKey()).map(key -> key.location().getPath()).orElse("");
        if (potion.startsWith("long_"))
            text.append(Component.translatable("gui.exiledsettlement.brewing.extended"));
        else if (potion.startsWith("strong_"))
            text.append(Component.translatable("gui.exiledsettlement.brewing.enhanced"));
        return text;
    }

    @Override
    protected void init() {
        int left = width / 2 - WIDTH / 2, top = height / 2 - HEIGHT / 2;
        String[] groups = {"normal", "splash", "lingering"};
        for (int index = 0; index < groups.length; index++) {
            final int group = index;
            var button = addRenderableWidget(Button.builder(
                    Component.translatable("gui.exiledsettlement.brewing." + groups[index]), ignored -> {
                        category = group;
                        page = 0;
                        rebuildWidgets();
                    }).bounds(left + 12 + index * 120, top + 31, 116, 20).build());
            button.active = category != group;
        }
        List<ResourceLocation> products = products();
        int pages = Math.max(1, (products.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE, end = Math.min(products.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            ResourceLocation id = products.get(index);
            Component label = Component.empty().append(id.equals(selected) ? "✓ " : "").append(productName(id));
            addRenderableWidget(Button.builder(label, ignored -> {
                minecraft.setScreen(parent);
                select.accept(id);
            }).bounds(left + 38, top + 74 + (index - start) * 26, 328, 23).build());
        }
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        }).bounds(left + 96, top + 241, 30, 20).build());
        previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> {
            page = Math.min(pages - 1, page + 1);
            rebuildWidgets();
        }).bounds(left + 254, top + 241, 30, 20).build());
        next.active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), ignored -> onClose())
                .bounds(width / 2 - 50, top + 277, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = width / 2 - WIDTH / 2, top = height / 2 - HEIGHT / 2;
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xEE201C18);
        graphics.drawCenteredString(font, title, width / 2, top + 13, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.brewing.picker_hint"),
                width / 2, top + 57, 0xFFD6CBB9);
        List<ResourceLocation> products = products();
        int pages = Math.max(1, (products.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.drawCenteredString(font, Component.translatable("gui.exiledsettlement.brewing.page", page + 1, pages),
                width / 2, top + 247, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
        int start = page * PAGE_SIZE, end = Math.min(products.size(), start + PAGE_SIZE);
        ItemStack hovered = ItemStack.EMPTY;
        for (int index = start; index < end; index++) {
            ItemStack stack = BrewingRecipes.stack(products.get(index));
            int y = top + 74 + (index - start) * 26;
            graphics.renderItem(stack, left + 14, y + 3);
            if (mouseX >= left + 12 && mouseX < left + WIDTH - 12 && mouseY >= y && mouseY < y + 23)
                hovered = stack;
        }
        if (!hovered.isEmpty()) graphics.renderTooltip(font, hovered, mouseX, mouseY);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

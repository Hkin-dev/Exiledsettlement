package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.building.KilnRecipes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Consumer;

/** Category picker groups kiln outputs and color variants. */
public final class KilnProductScreen extends Screen {
    private final Screen parent;
    private final Consumer<ResourceLocation> select;
    private final ResourceLocation selected;
    private int category;
    public KilnProductScreen(Screen parent,ResourceLocation selected,Consumer<ResourceLocation> select) {
        super(Component.translatable("gui.exiledsettlement.kiln.choose"));
        this.parent=parent;this.select=select;this.selected=selected;
        int index=KilnRecipes.PRODUCTS.indexOf(selected);category=index<9?0:index<25?1:2;
    }
    @Override protected void init() {
        int left=width/2-174,top=height/2-141;
        String[] names={"base","terracotta","concrete"};
        for(int i=0;i<3;i++) {
            final int group=i;
            var button=addRenderableWidget(Button.builder(Component.translatable("gui.exiledsettlement.kiln."+names[i]),b -> {category=group;rebuildWidgets();})
                    .bounds(left+i*116,top+25,112,20).build());
            button.active=category!=i;
        }
        int first=category==0?0:category==1?9:25,number=category==0?9:16;
        for(int i=0;i<number;i++) {
            var id=KilnRecipes.PRODUCTS.get(first+i);var recipe=KilnRecipes.forOutput(BuiltInRegistries.ITEM.get(id));
            var label=Component.empty().append(id.equals(selected)?"✓ ":"").append(KilnRecipes.stack(recipe.output(),1).getHoverName());
            addRenderableWidget(Button.builder(label,b -> {minecraft.setScreen(parent);select.accept(id);})
                    .bounds(left+(i%2)*174,top+53+(i/2)*23,170,21).tooltip(Tooltip.create(details(recipe))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b -> onClose()).bounds(width/2-50,top+251,100,20).build());
    }
    public static Component details(KilnRecipes.Recipe recipe) {
        var cost = KilnRecipes.fullCost(recipe.output(), recipe.outputCount());
        var direct = new java.util.LinkedHashMap<net.minecraft.world.item.Item,Integer>();
        for (var input : recipe.inputs()) direct.merge(input.item(), input.count(), Integer::sum);
        var returned = new java.util.LinkedHashMap<net.minecraft.world.item.Item,Integer>();
        if (recipe.returnedCount() > 0) returned.put(recipe.returnedItem(), recipe.returnedCount());
        boolean expanded = !direct.equals(cost.inputs()) || !returned.equals(cost.returned())
                || cost.fuel() != (recipe.usesFuel() ? 1 : 0) || cost.needsWater() != (recipe.station() == KilnRecipes.Station.WATER);
        var text = Component.translatable("gui.exiledsettlement.kiln.batch", recipe.outputCount()).append("\n");
        if (expanded) text.append(Component.translatable("gui.exiledsettlement.ui.direct_cost")).append("\n");
        boolean first = true;
        for (var input : recipe.inputs()) {
            if (!first) text.append(" + "); first = false;
            text.append(KilnRecipes.stack(input.item(), 1).getHoverName()).append(" ×" + input.count());
        }
        if (recipe.usesFuel()) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.fuel"));
        if (recipe.station() == KilnRecipes.Station.WATER) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.water"));
        if (recipe.returnedCount() > 0) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.returns",
                recipe.returnedCount(), KilnRecipes.stack(recipe.returnedItem(), 1).getHoverName()));
        if (expanded) {
            text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.full_cost"));
            first = true;
            for (var entry : cost.inputs().entrySet()) {
                if (!first) text.append(" + "); first = false;
                text.append(KilnRecipes.stack(entry.getKey(), 1).getHoverName()).append(" ×" + entry.getValue());
            }
            if (cost.fuel() > 0) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.fuel_total", cost.fuel()));
            for (var entry : cost.returned().entrySet()) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.returns",
                    entry.getValue(), KilnRecipes.stack(entry.getKey(), 1).getHoverName()));
            if (cost.needsWater()) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.water"));
        }
        if (cost.inputs().containsKey(Items.GLASS_BOTTLE)) text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.bottles"));
        return text.append("\n").append(Component.translatable("gui.exiledsettlement.kiln.automatic"));
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float tick) {
        renderBackground(graphics,mouseX,mouseY,tick);
        graphics.fill(width/2-184,height/2-151,width/2+184,height/2+141,0xEE201C18);
        graphics.drawCenteredString(font,title,width/2,height/2-135,0xFFFFFFFF);
        super.render(graphics,mouseX,mouseY,tick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

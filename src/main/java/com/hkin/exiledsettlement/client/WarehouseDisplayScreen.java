package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.building.WarehouseDisplayMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.ArrayList;

/** Counts are independent of icon stacks: no fake oversized transferable items. */
public final class WarehouseDisplayScreen extends AbstractContainerScreen<WarehouseDisplayMenu> {
    private static final ResourceLocation TEXTURE=ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    public WarehouseDisplayScreen(WarehouseDisplayMenu menu, Inventory inventory, Component title) {
        super(menu,inventory,title);imageWidth=176;imageHeight=114+menu.rows()*18;inventoryLabelY=imageHeight-94;
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        g.blit(TEXTURE,leftPos,topPos,0,0,imageWidth,menu.rows()*18+17);
        g.blit(TEXTURE,leftPos,topPos+menu.rows()*18+17,0,126,imageWidth,96);
    }
    private Component categoryTitle() {
        var label=Component.empty();
        for(var category:menu.displayedCategories()) {
            if(!label.getSiblings().isEmpty())label.append(" / ");
            label.append(Component.translatable(category.translationKey()));
        }
        return label.append(" · ").append(title);
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {
        String label=categoryTitle().getString();int width=imageWidth-16;
        if(font.width(label)>width)label=font.plainSubstrByWidth(label,width-font.width("…"))+"…";
        g.drawString(font,label,titleLabelX,titleLabelY,4210752,false);
        g.drawString(font,playerInventoryTitle,inventoryLabelX,inventoryLabelY,4210752,false);
    }
    private static String compactCount(int total) {
        if(total<1000)return Integer.toString(total);
        int unit=total>=1_000_000_000?1_000_000_000:total>=1_000_000?1_000_000:1000;
        String suffix=unit==1_000_000_000?"G":unit==1_000_000?"M":"K";
        int whole=total/unit,decimal=(int)((long)total*10/unit%10);
        return whole<10 && decimal>0?whole+"."+decimal+suffix:whole+suffix;
    }
    @Override protected void renderSlotContents(GuiGraphics g,net.minecraft.world.item.ItemStack stack,
            net.minecraft.world.inventory.Slot slot,String countString) {
        if(slot.index>=menu.displaySlots() || stack.isEmpty()) {
            super.renderSlotContents(g,stack,slot,countString);return;
        }
        // Keep the native icon/durability pass; replace only its quantity text.
        super.renderSlotContents(g,stack,slot,"");
        int total=menu.displayedCount(slot.index);if(total<=1)return;
        String label=compactCount(total);float scale=.75f;
        // renderSlot already contributes z=100; add the native count offset of 200.
        g.pose().pushPose();g.pose().translate(slot.x+17,slot.y+17-9*scale,200);
        g.pose().scale(scale,scale,1);
        g.drawString(font,label,-font.width(label),0,0xFFFFFF,true);
        g.pose().popPose();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        if(menu.getCarried().isEmpty() && hoveredSlot!=null && hoveredSlot.index<menu.displaySlots() && hoveredSlot.hasItem()) {
            var lines=new ArrayList<Component>(getTooltipFromContainerItem(hoveredSlot.getItem()));
            lines.add(Component.literal("实际库存："+String.format(java.util.Locale.ROOT,"%,d",menu.displayedCount(hoveredSlot.index))+" 件"));
            lines.add(Component.literal("左键取一组 · 右键取半组 · Shift 快捷取放"));
            g.renderComponentTooltip(font,lines,mx,my);
        } else if(mx>=leftPos+8 && mx<leftPos+imageWidth-8 && my>=topPos+4 && my<topPos+16) {
            var lines=new ArrayList<Component>();lines.add(title);
            for(var category:menu.displayedCategories())lines.add(Component.translatable(category.translationKey()));
            g.renderComponentTooltip(font,lines,mx,my);
        } else renderTooltip(g,mx,my);
    }
}

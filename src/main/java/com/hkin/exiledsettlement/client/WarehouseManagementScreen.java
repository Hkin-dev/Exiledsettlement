package com.hkin.exiledsettlement.client;
import com.hkin.exiledsettlement.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class WarehouseManagementScreen extends Screen {
    private final Screen parent;
    private final BlockPos core;
    private WarehouseSnapshotPayload state;
    private EditBox search;
    private int left,top,panelWidth;
    private int visibleRows=10,rowOffset,footer;
    public WarehouseManagementScreen(Screen parent,BlockPos core){super(Component.literal("库存管理"));this.parent=parent;this.core=core;}
    public static void receive(WarehouseSnapshotPayload p) {
        if(Minecraft.getInstance().screen instanceof WarehouseManagementScreen screen && screen.core.equals(p.core())) {
            if(screen.state==null || screen.state.warehouse()!=p.warehouse() || screen.state.page()!=p.page() || !screen.state.query().equals(p.query()))screen.rowOffset=0;
            screen.state=p;screen.rebuildWidgets();
        }
    }
    private void request(int warehouse,int page,String query,String item,int keep,boolean discard,boolean apply) {
        if(!query.isEmpty()) {
            var input=query.strip();
            var exact=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().filter(i->new net.minecraft.world.item.ItemStack(i).getHoverName().getString().equals(input)).findFirst().orElse(null);
            var category=java.util.Arrays.stream(com.hkin.exiledsettlement.building.WarehouseDisplayCategory.visibleCategories())
                    .filter(c->Component.translatable(c.translationKey()).getString().equals(input)).findFirst().orElse(null);
            if(category!=null)query=category.filter();
            else if(exact!=null)query=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(exact).toString();
            else query=switch(input){case "食物" -> "food";case "工具" -> "tools";case "装备" -> "equipment";case "材料" -> "materials";default -> query;};
        }
        PacketDistributor.sendToServer(new WarehouseRequestPayload(core,warehouse,page,query,item,keep,discard,apply));
    }
    @Override protected void init() {
        visibleRows=Math.max(1,Math.min(10,(height-172)/22));
        left=8;panelWidth=Math.min(580,width-16);left=(width-panelWidth)/2;
        top=Math.max(4,(height-(172+visibleRows*22))/2);footer=129+visibleRows*22+3;
        if(state==null)request(-1,0,"","",0,false,false);widgets();
    }
    private void widgets() {
        search=new EditBox(font,left+8,top+45,panelWidth-162,18,Component.literal("物品名、分类或物品ID"));search.setMaxLength(128);String query=state==null?"":state.query();
        for(var category:com.hkin.exiledsettlement.building.WarehouseDisplayCategory.visibleCategories())
            if(!query.isEmpty() && query.equals(category.filter()))query=Component.translatable(category.translationKey()).getString();
        search.setValue(query);addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("搜索"),b->request(state==null?-1:state.warehouse(),0,search.getValue(),"",0,false,false)).bounds(left+panelWidth-148,top+44,44,20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"),b->request(state==null?-1:state.warehouse(),state==null?0:state.page(),search.getValue(),"",0,false,false)).bounds(left+panelWidth-100,top+44,44,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+panelWidth-52,top+44,44,20).build());
        if(state==null)return;
        addRenderableWidget(Button.builder(Component.literal("< 建筑"),b->request(state.warehouse()<=-1?state.warehouses()-1:state.warehouse()-1,0,search.getValue(),"",0,false,false)).bounds(left+8,top+21,62,18).build());
        addRenderableWidget(Button.builder(Component.literal("建筑 >"),b->request(state.warehouse()+1>=state.warehouses()?-1:state.warehouse()+1,0,search.getValue(),"",0,false,false)).bounds(left+panelWidth-70,top+21,62,18).build());
        var categories=com.hkin.exiledsettlement.building.WarehouseDisplayCategory.visibleCategories();
        int categoryWidth=(panelWidth-16)/6;
        for(int k=0;k<categories.length;k++) {
            var category=categories[k];
            var button=addRenderableWidget(Button.builder(Component.translatable(category.translationKey()),b->request(state.warehouse(),0,category.filter(),"",0,false,false))
                    .bounds(left+8+(k%6)*categoryWidth,top+67+(k/6)*21,categoryWidth-3,18).build());
            button.active=!state.query().equals(category.filter());
        }
        int nameWidth=panelWidth-268;
        for(int i=rowOffset;i<Math.min(state.rows().size(),rowOffset+visibleRows);i++) {
            var row=state.rows().get(i);int y=top+129+(i-rowOffset)*22;
            if (state.home()) continue;
            var keep=new EditBox(font,left+nameWidth+84,y,58,18,Component.literal("保留数量（0至200000）"));keep.setMaxLength(6);
            keep.setFilter(v->v.isEmpty() || v.matches("[0-9]{1,6}") && Integer.parseInt(v)<=200000);keep.setValue(Integer.toString(row.keep()));addRenderableWidget(keep);
            final boolean[] discard={row.discard()};
            var policy=addRenderableWidget(Button.builder(Component.literal(row.mixed()?"混合规则":discard[0]?"清除余量":"全部保留"),b->{discard[0]=!discard[0];keep.visible=discard[0];keep.setEditable(discard[0]);if(keep.getValue().isEmpty())keep.setValue(Integer.toString(row.keep()));b.setMessage(Component.literal(discard[0]?"清除余量":"全部保留"));})
                    .bounds(left+nameWidth+146,y-1,70,20).tooltip(Tooltip.create(Component.literal("清除余量：仓管到仓库取走超出保留数量的物品并销毁；不会阻止交货。"))).build());
            var save=addRenderableWidget(Button.builder(Component.literal("保存"),b->{
                try{int n=keep.getValue().isEmpty() && !discard[0] ? row.keep() : Integer.parseInt(keep.getValue());if(n>=0 && n<=200000)request(state.warehouse(),state.page(),search.getValue(),row.item(),n,discard[0],true);}catch(NumberFormatException ignored){}
            }).bounds(left+nameWidth+220,y-1,40,20).build());
            keep.setResponder(v->save.active=!v.isEmpty() || !discard[0]);
            keep.visible=discard[0];keep.setEditable(discard[0]);save.active=true;
        }
        addRenderableWidget(Button.builder(Component.literal("上一页"),b->{if(rowOffset>0){rowOffset=Math.max(0,rowOffset-visibleRows);rebuildWidgets();}else request(state.warehouse(),Math.max(0,state.page()-1),search.getValue(),"",0,false,false);}).bounds(left+8,top+footer,64,18).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"),b->{if(rowOffset+visibleRows<state.rows().size()){rowOffset+=visibleRows;rebuildWidgets();}else request(state.warehouse(),Math.min(state.pages()-1,state.page()+1),search.getValue(),"",0,false,false);}).bounds(left+76,top+footer,64,18).build());
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==257 && search.isFocused()){request(state==null?-1:state.warehouse(),0,search.getValue(),"",0,false,false);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial) {
        // Screen.render invokes this after our labels; blur only before drawing the panel.
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.renderBackground(g,mx,my,partial);g.fill(left,top,left+panelWidth,top+footer+38,0xFF16212C);
        g.drawString(font,font.plainSubstrByWidth("库存管理 · "+(state==null?"读取中":state.name()),panelWidth-16),left+8,top+7,0xFFFFFF);
        if(state!=null) {
            String total="共 "+state.used()+" / "+state.capacity()+" 件 · "+state.warehouses()+" 处库存";
            g.drawCenteredString(font,total,left+panelWidth/2,top+26,0xBCD6EC);
            g.fill(left+8,top+41,left+panelWidth-8,top+43,0xFF354656);
            int fill=state.capacity()==0?0:(int)((panelWidth-16)*Math.min(1.0,(double)state.used()/state.capacity()));
            g.fill(left+8,top+41,left+8+fill,top+43,0xFF6BBCA1);
            int nw=state.home()?panelWidth-90:panelWidth-268;
            g.drawString(font,"物品",left+8,top+115,0xAABBCB);g.drawString(font,"件数",left+nw+24,top+115,0xAABBCB);if(!state.home())g.drawString(font,"保留数",left+nw+84,top+115,0xAABBCB);
            for(int i=rowOffset;i<Math.min(state.rows().size(),rowOffset+visibleRows);i++) {
                var row=state.rows().get(i);int y=top+129+(i-rowOffset)*22;g.fill(left+5,y-2,left+panelWidth-5,y+19,(i%2==0)?0xFF213142:0xFF1B2937);g.renderItem(row.sample(),left+8,y);
                g.drawString(font,font.plainSubstrByWidth(row.sample().getHoverName().getString(),Math.max(12,nw-14)),left+28,y+5,0xFFFFFF);
                String count=String.format(java.util.Locale.ROOT,"%,d",row.count());
                float scale=Math.min(1f,56f/Math.max(1,font.width(count)));
                g.pose().pushPose();g.pose().translate(left+nw+24,y+5,0);g.pose().scale(scale,scale,1);
                g.drawString(font,count,0,0,0xFFFFFF);g.pose().popPose();
            }
            g.drawString(font,"第 "+(state.page()*((10+visibleRows-1)/visibleRows)+rowOffset/visibleRows+1)+" 页",left+150,top+footer+4,0xAABBCB);
            g.drawString(font,font.plainSubstrByWidth(state.feedback(),panelWidth-16),left+8,top+footer+25,0xD4DCAC);
        }
        super.render(g,mx,my,partial);
        if(state!=null)for(int i=rowOffset;i<Math.min(state.rows().size(),rowOffset+visibleRows);i++) {
            int y=top+129+(i-rowOffset)*22;
            if(mx>=left+8 && mx<left+panelWidth-(state.home()?68:246) && my>=y && my<y+20) {
                var row=state.rows().get(i);g.renderTooltip(font,Component.literal(row.item()+" · "+row.count()+" 件"),mx,my);
            }
        }
    }
}

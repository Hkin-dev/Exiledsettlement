package com.hkin.exiledsettlement.client;
import com.hkin.exiledsettlement.network.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
/** World preview overlay: never blur the geometry or explanatory text. */
public final class BridgePlanningScreen extends Screen {
 private final WorksPreviewPayload preview;
 private int scroll;
 public BridgePlanningScreen(WorksPreviewPayload p){super(Component.literal("桥梁规划"));preview=p;}
 protected void init(){
  int buttonWidth=Math.min(94,(width-40)/4),gap=6;
  addRenderableWidget(Button.builder(Component.literal("高度 +1"),b->ClientPlacementController.changeBridge(1,false)).bounds(14,height-76,buttonWidth,20).build());
  addRenderableWidget(Button.builder(Component.literal("高度 -1"),b->ClientPlacementController.changeBridge(-1,false)).bounds(14+buttonWidth+gap,height-76,buttonWidth,20).build());
  addRenderableWidget(Button.builder(Component.literal("切换方向"),b->ClientPlacementController.changeBridge(0,true)).bounds(14+2*(buttonWidth+gap),height-76,buttonWidth,20).build());
  addRenderableWidget(Button.builder(Component.literal("使用首点高度"),b->ClientPlacementController.suggestedBridge()).bounds(14+3*(buttonWidth+gap),height-76,buttonWidth,20).build());
  var confirm=addRenderableWidget(Button.builder(Component.literal("确认施工"),b->{PacketDistributor.sendToServer(new ConfirmWorksPayload(preview.token(),true));minecraft.setScreen(null);}).bounds(14,height-50,buttonWidth,20).build());confirm.active=preview.valid();
  if(!preview.valid())confirm.setTooltip(Tooltip.create(Component.literal("预览检查未通过，请查看面板首行的具体原因。")));
  addRenderableWidget(Button.builder(Component.literal("重新框选"),b->{ClientPlacementController.editBridge();minecraft.setScreen(null);}).bounds(14+buttonWidth+gap,height-50,buttonWidth,20).build());
  addRenderableWidget(Button.builder(Component.literal("取消"),b->{PacketDistributor.sendToServer(new ConfirmWorksPayload(preview.token(),false));ClientPlacementController.clear();minecraft.setScreen(null);}).bounds(14+2*(buttonWidth+gap),height-50,buttonWidth,20).build());
 }
 @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){
  // Screen.render invokes this before widgets. Its default blur would otherwise
  // process our already rendered text, and obscure the in-world bridge preview.
 }
 private int lineWidth(){return Math.max(40,Math.min(width-28,400));}
 private int visibleLines(){return Math.max(1,(height-142)/12);}
 @Override public void render(GuiGraphics g,int x,int y,float dt){
  var lines=font.split(Component.literal(preview.description()),lineWidth());
  int visible=Math.min(visibleLines(),lines.size());scroll=Math.clamp(scroll,0,Math.max(0,lines.size()-visible));
  int bottom=42+visible*12;
  g.fill(7,7,21+lineWidth(),bottom,0xFF101720);
  g.renderOutline(7,7,14+lineWidth(),bottom-7,preview.valid()?0xFF87BCD7:0xFFFFBC88);
  g.drawString(font,preview.valid()?"桥梁规划 · 可以确认":"桥梁规划 · 暂不能施工",14,14,0xFFFFFF);
  for(int i=0;i<visible;i++)g.drawString(font,lines.get(scroll+i),14,32+i*12,preview.valid()?0xE6F1FA:0xFFE0B0);
  if(lines.size()>visible)g.drawString(font,"滚轮查看说明 "+(scroll+1)+"–"+(scroll+visible)+" / "+lines.size(),14,bottom-11,0xB5C6D4);
  super.render(g,x,y,dt);
 }
 @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
  int count=font.split(Component.literal(preview.description()),lineWidth()).size();
  scroll=Math.clamp(scroll-(int)Math.signum(vertical)*3,0,Math.max(0,count-visibleLines()));return true;
 }
 public boolean isPauseScreen(){return false;}
 public void onClose(){minecraft.setScreen(null);}
}

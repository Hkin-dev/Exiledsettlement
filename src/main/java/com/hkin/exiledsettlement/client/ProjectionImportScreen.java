package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.network.ProjectionUploadPayload;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.*;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.io.*;

public final class ProjectionImportScreen extends Screen {
 private final Screen parent;
 private ForgematicaProjectionExporter exporter;
 private String status="正在读取已选中的投影，每帧分批处理";
 private int typeIndex,page; private Direction outward=Direction.NORTH;
 private EditBox name,entry; private Button submit,direction;
 private List<String> materials=List.of(); private boolean loaded;
 private static final BuildingType[] TYPES=Arrays.stream(BuildingType.values()).filter(t->t!=BuildingType.WAGON).toArray(BuildingType[]::new);
 private static final java.util.ArrayDeque<ProjectionUploadPayload> sending=new java.util.ArrayDeque<>();
 public ProjectionImportScreen(Screen parent) {
  super(Component.literal("投影自定义建筑")); this.parent=parent;
  try { exporter=new ForgematicaProjectionExporter(); } catch (ReflectiveOperationException | RuntimeException error) { status=error.getMessage()==null?"当前Forgematica接口不兼容":error.getMessage(); }
 }
 public static void tickUpload() {
  if (net.minecraft.client.Minecraft.getInstance().getConnection()==null) { sending.clear(); return; }
  if (!sending.isEmpty()) PacketDistributor.sendToServer(sending.removeFirst());
 }
 @Override protected void init() {
  int left=width/2-170,top=height/2-135;
  String oldName=name==null?"我的投影建筑":name.getValue(),oldEntry=entry==null?null:entry.getValue();
  name=new EditBox(font,left,top+25,340,20,Component.literal("建筑名称")); name.setMaxLength(32); name.setValue(oldName);addRenderableWidget(name);
  addRenderableWidget(Button.builder(typeLabel(),b->{ typeIndex=(typeIndex+1)%TYPES.length; b.setMessage(typeLabel());refreshMaterials(); }).bounds(left,top+50,340,20).build());
  entry=new EditBox(font,left,top+78,230,20,Component.literal("入口 x,y,z"));entry.setMaxLength(64);
  if (oldEntry!=null) entry.setValue(oldEntry);else if (minecraft.player!=null) entry.setValue(minecraft.player.blockPosition().toShortString());addRenderableWidget(entry);
  direction=addRenderableWidget(Button.builder(Component.literal("入口朝"+directionName()),b->{ outward=outward.getClockWise();b.setMessage(Component.literal("入口朝"+directionName())); }).bounds(left+240,top+78,100,20).build());
  addRenderableWidget(Button.builder(Component.literal("入口取当前位置"),b->{ if (minecraft.player!=null) entry.setValue(minecraft.player.blockPosition().toShortString()); }).bounds(left,top+103,160,20).build());
  addRenderableWidget(Button.builder(Component.literal("材料下一页"),b->page=(page+1)%Math.max(1,(materials.size()+4)/5)).bounds(left+180,top+103,160,20).build());
  submit=addRenderableWidget(Button.builder(Component.literal("查看材料后提交施工"),b->submit()).bounds(left,top+240,220,20).build()); submit.active=loaded&&exporter!=null&&exporter.error.isEmpty()&&exporter.count()>0&&sending.isEmpty();
  addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+240,top+240,100,20).build());
 }
 private String directionName() { return switch(outward){case NORTH->"北";case EAST->"东";case SOUTH->"南";default->"西";}; }
 private Component typeLabel() { return TYPES[typeIndex]==BuildingType.BENCH?Component.literal("用途：纯建筑（无生产功能）"):Component.literal("用途：").append(Component.translatable(TYPES[typeIndex].translationKey())); }
 @Override public void tick() {
  if (exporter==null || loaded) return;
  exporter.tick();status=exporter.error.isEmpty()?"已读取 "+exporter.count()+" 个非空气方块；床位/容器自动识别":exporter.error;
  if (exporter.done()) {
   loaded=true;submit.active=exporter.error.isEmpty() && exporter.count()>0 && sending.isEmpty();
   if (submit.active) {
    for (var p:exporter.placements()) if (p.state().getBlock() instanceof DoorBlock && p.state().getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER) {
     entry.setValue(p.position().toShortString());outward=p.state().getValue(DoorBlock.FACING);direction.setMessage(Component.literal("入口朝"+directionName()));break;
    }
    refreshMaterials();
   }
  }
 }
 private void refreshMaterials() {
  if (exporter==null || !exporter.done() || !exporter.error.isEmpty()) return;
  try { var snapshot=snapshot(); materials=snapshot.template().requirements().entrySet().stream()
    .map(e->e.getKey().getDescription().getString()+" × "+e.getValue()).sorted().toList(); page=0; }
  catch (RuntimeException error) { status=error.getMessage(); }
 }
 private CustomTemplateSnapshot snapshot() {
  String[] xyz=entry.getValue().split(",");if (xyz.length!=3) throw new IllegalArgumentException("入口格式：x, y, z");
  BlockPos entrance=new BlockPos(Integer.parseInt(xyz[0].strip()),Integer.parseInt(xyz[1].strip()),Integer.parseInt(xyz[2].strip()));
  var placements=exporter.placements();
  int minX=placements.stream().mapToInt(p->p.position().getX()).min().orElseThrow(),minY=placements.stream().mapToInt(p->p.position().getY()).min().orElseThrow(),minZ=placements.stream().mapToInt(p->p.position().getZ()).min().orElseThrow();
  return CustomTemplateSnapshot.create(name.getValue(),TYPES[typeIndex],new BlockPos(minX,minY,minZ),outward,placements,List.of(new BlueprintEntrance(entrance,outward)));
 }
 private void submit() {
  if (!sending.isEmpty()) { status="上一份投影仍在上传";return; }
  try {
   var snapshot=snapshot(); ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.write(snapshot.save(),new DataOutputStream(out));byte[] bytes=out.toByteArray();
   if (bytes.length>CustomTemplateSnapshot.MAX_BYTES) throw new IllegalArgumentException("投影超过8MiB");
   UUID upload=UUID.randomUUID();int parts=(bytes.length+ProjectionUploadPayload.CHUNK_BYTES-1)/ProjectionUploadPayload.CHUNK_BYTES;
   for (int i=0;i<parts;i++) sending.addLast(new ProjectionUploadPayload(upload,i,parts,Arrays.copyOfRange(bytes,i*ProjectionUploadPayload.CHUNK_BYTES,Math.min(bytes.length,(i+1)*ProjectionUploadPayload.CHUNK_BYTES))));
   submit.active=false;status="已确认，正在分片上传；服务端结果将显示在聊天中";
  } catch (IOException | RuntimeException error) { status="无法提交："+error.getMessage(); }
 }
 public static void queueSnapshot(CustomTemplateSnapshot snapshot) throws IOException {
  if (!sending.isEmpty()) throw new IllegalStateException("上一份投影仍在上传，请稍后再试");
  ByteArrayOutputStream out = new ByteArrayOutputStream();
  NbtIo.write(snapshot.save(), new DataOutputStream(out));
  byte[] bytes = out.toByteArray();
  if (bytes.length > CustomTemplateSnapshot.MAX_BYTES) throw new IllegalArgumentException("投影超过8MiB");
  UUID upload = UUID.randomUUID();
  int parts = (bytes.length + ProjectionUploadPayload.CHUNK_BYTES - 1) / ProjectionUploadPayload.CHUNK_BYTES;
  for (int i = 0; i < parts; i++) sending.addLast(new ProjectionUploadPayload(upload, i, parts,
      Arrays.copyOfRange(bytes, i * ProjectionUploadPayload.CHUNK_BYTES, Math.min(bytes.length, (i + 1) * ProjectionUploadPayload.CHUNK_BYTES))));
 }
 @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
  renderBackground(graphics,mouseX,mouseY,delta);super.render(graphics,mouseX,mouseY,delta);
  int left=width/2-170,top=height/2-135;
  graphics.drawString(font,title,left,top,0xffffff);
  graphics.drawWordWrap(font,Component.literal(status),left,top+130,340,0xdddddd);
  for (int i=0;i<5 && page*5+i<materials.size();i++) graphics.drawString(font,materials.get(page*5+i),left,top+172+i*12,0xeeeeaa);
 }
 @Override public boolean isPauseScreen() { return false; }
 @Override public void onClose() { minecraft.setScreen(parent); }
}

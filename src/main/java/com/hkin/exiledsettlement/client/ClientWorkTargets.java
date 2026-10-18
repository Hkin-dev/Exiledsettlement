package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.WorkTargetsPayload;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Straight diagnostic lines through terrain, without modifying entity paths or the world. */
public final class ClientWorkTargets {
    private ClientWorkTargets() {}
    private static WorkTargetsPayload snapshot;
    private static ClientLevel level;
    private static long received;
    public static void receive(WorkTargetsPayload p){snapshot=p;level=Minecraft.getInstance().level;received=System.nanoTime();}
    public static void tick(){if(level!=Minecraft.getInstance().level){snapshot=null;level=null;}}
    public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES || snapshot==null || !snapshot.enabled() || snapshot.targets().isEmpty())return;
        var game=Minecraft.getInstance();if(game.level==null || game.level!=level || System.nanoTime()-received>3_000_000_000L)return;
        var poses=event.getPoseStack();var camera=event.getCamera().getPosition();
        poses.pushPose();poses.translate(-camera.x,-camera.y,-camera.z);
        RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.lineWidth(2);
        try {
            var vertices=Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES,DefaultVertexFormat.POSITION_COLOR);
            for(var t:snapshot.targets()) {
                var e=game.level.getEntity(t.entityId());if(!(e instanceof SettlementCitizenEntity) || !e.isAlive())continue;
                var start=e.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false)).add(0,e.getBbHeight()*.65,0);
                int color=t.blocked()?0xFFFF5555:0xFF40E6FF;
                line(vertices,poses,start,t.position(),color);
                for(var axis:new Vec3[]{new Vec3(.2,0,0),new Vec3(0,.2,0),new Vec3(0,0,.2)})line(vertices,poses,t.position().subtract(axis),t.position().add(axis),color);
            }
            var mesh=vertices.build();if(mesh!=null)BufferUploader.drawWithShader(mesh);
        } finally {RenderSystem.lineWidth(1);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.disableBlend();poses.popPose();}
    }
    private static void line(BufferBuilder b,PoseStack p,Vec3 a,Vec3 z,int color){b.addVertex(p.last().pose(),(float)a.x,(float)a.y,(float)a.z).setColor(color);b.addVertex(p.last().pose(),(float)z.x,(float)z.y,(float)z.z).setColor(color);}
}

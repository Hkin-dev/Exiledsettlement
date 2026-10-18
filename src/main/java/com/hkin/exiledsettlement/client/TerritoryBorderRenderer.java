package com.hkin.exiledsettlement.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Original Minecraft forcefield texture, visual only; never edits the world border. */
final class TerritoryBorderRenderer {
    static void render(PoseStack poses, List<TerritoryOutline.Edge> edges, Vec3 camera) {
        if (edges.isEmpty()) return;
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, ResourceLocation.withDefaultNamespace("textures/misc/forcefield.png"));
        RenderSystem.setShaderColor(0.22F, 0.62F, 1.0F, 0.42F);
        try {
            BufferBuilder vertices = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            var matrix = poses.last().pose();
            float scroll = (System.currentTimeMillis() % 3000L) / 3000.0F;
            float bottom = (float) camera.y - 48, top = (float) camera.y + 64;
            for (var edge : edges) {
                if (Math.abs((edge.x1() + edge.x2()) / 2.0 - camera.x) > 256
                        || Math.abs((edge.z1() + edge.z2()) / 2.0 - camera.z) > 256) continue;
                float u = scroll + (edge.x1() + edge.z1()) * 0.5F;
                vertices.addVertex(matrix, edge.x1(), bottom, edge.z1()).setUv(u, scroll + bottom * 0.5F);
                vertices.addVertex(matrix, edge.x2(), bottom, edge.z2()).setUv(u + 8, scroll + bottom * 0.5F);
                vertices.addVertex(matrix, edge.x2(), top, edge.z2()).setUv(u + 8, scroll + top * 0.5F);
                vertices.addVertex(matrix, edge.x1(), top, edge.z1()).setUv(u, scroll + top * 0.5F);
            }
            MeshData mesh = vertices.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }
}

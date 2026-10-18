package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.client.ysm.YsmCitizenRendererBridge;
import com.hkin.exiledsettlement.compat.YsmCompatibility;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.MultiBufferSource;

public final class SettlementCitizenRenderer
        extends HumanoidMobRenderer<SettlementCitizenEntity, SettlementCitizenModel> {
    private static final ResourceLocation[] CITIZEN_TEXTURES = java.util.stream.Stream.of(
            "ochre", "teal", "blue", "green", "wine", "sand", "slate", "rust")
            .map(name -> ResourceLocation.fromNamespaceAndPath("exiledsettlement",
                    "textures/entity/citizen/" + name + ".png"))
            .toArray(ResourceLocation[]::new);

    public SettlementCitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new SettlementCitizenModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        addLayer(new CitizenWinterClothingLayer(this));
        addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));
    }

    @Override
    public ResourceLocation getTextureLocation(SettlementCitizenEntity entity) {
        return CITIZEN_TEXTURES[Math.floorMod(entity.skinVariant(), CITIZEN_TEXTURES.length)];
    }

    @Override
    public void render(SettlementCitizenEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (!YsmCompatibility.available() || !YsmCitizenRendererBridge.render(
                entity, entityYaw, partialTick, poseStack, buffers, packedLight)) {
            super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
        }
    }
}

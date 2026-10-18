package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

/** Renders the resident's real thermal garment; armor remains an independent projection. */
public final class CitizenWinterClothingLayer
        extends RenderLayer<SettlementCitizenEntity, SettlementCitizenModel> {
    private static final ResourceLocation WOOL = ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");
    private static final int[] COLORS = {0xFF795D39, 0xFF365C5A, 0xFF3C526F, 0xFF4B5E3C,
            0xFF68414B, 0xFF8D795A, 0xFF515963, 0xFF84503C};
    private final ModelPart coat, fur, trousers, boots;

    public CitizenWinterClothingLayer(RenderLayerParent<SettlementCitizenEntity, SettlementCitizenModel> parent) {
        super(parent);
        coat = parts(false, false, false);
        fur = parts(true, false, false);
        trousers = parts(false, true, false);
        boots = parts(false, false, true);
    }

    private static ModelPart parts(boolean trim, boolean legs, boolean feet) {
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        if (legs || feet) {
            for (String side : new String[]{"left_leg", "right_leg"}) {
                root.addOrReplaceChild(side, CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-2, feet ? 7 : 0, -2, 4, feet ? 5 : 7, 4,
                                new CubeDeformation(feet ? 0.65F : 0.45F)), PartPose.ZERO);
            }
        } else {
            CubeListBuilder body = CubeListBuilder.create().texOffs(0, 0);
            if (trim) {
                // Open collar leaves the face and neck visible.
                body.addBox(-4.8F, -0.8F, -2.8F, 9.6F, 1.6F, 1.4F)
                        .addBox(-4.8F, -0.8F, 1.4F, 9.6F, 1.6F, 1.4F)
                        .addBox(-4.8F, -0.8F, -1.4F, 1.4F, 1.6F, 2.8F)
                        .addBox(3.4F, -0.8F, -1.4F, 1.4F, 1.6F, 2.8F);
            } else {
                body.addBox(-4, 0, -2, 8, 12, 4, new CubeDeformation(0.65F));
            }
            root.addOrReplaceChild("body", body, PartPose.ZERO);
            root.addOrReplaceChild("right_arm", sleeve(false, trim), PartPose.ZERO);
            root.addOrReplaceChild("left_arm", sleeve(true, trim), PartPose.ZERO);
        }
        return LayerDefinition.create(mesh, 16, 16).bakeRoot();
    }

    private static CubeListBuilder sleeve(boolean left, boolean trim) {
        return CubeListBuilder.create().texOffs(0, 0).mirror(left)
                .addBox(left ? -1 : -3, trim ? 7 : -2, -2, 4, trim ? 2 : 11, 4,
                        new CubeDeformation(trim ? 0.85F : 0.6F));
    }

    @Override
    public void render(PoseStack poses, MultiBufferSource buffers, int light, SettlementCitizenEntity citizen,
                       float swing, float amount, float partialTick, float age, float yaw, float pitch) {
        if (citizen.isInvisible() || citizen.visibleGarment() == 0) return;
        var model = getParentModel();
        poses.pushPose();
        if (model.young) {
            poses.scale(0.5F, 0.5F, 0.5F);
            poses.translate(0, 1.5D, 0);
        }
        if (citizen.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            copyUpper(coat, model); copyUpper(fur, model);
            draw(coat, poses, buffers, light, citizen.visibleGarment()==2?0xFF81512E:COLORS[Math.floorMod(citizen.skinVariant(), COLORS.length)]);
            if(citizen.visibleGarment()==1)draw(fur, poses, buffers, light, 0xFFE2D7BD);
        }
        if (citizen.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) {
            copyLegs(trousers, model); draw(trousers, poses, buffers, light, 0xFF534C41);
        }
        if (citizen.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            copyLegs(boots, model); draw(boots, poses, buffers, light, 0xFF41372D);
        }
        poses.popPose();
    }

    private static void copyUpper(ModelPart root, SettlementCitizenModel model) {
        root.getChild("body").copyFrom(model.body);
        root.getChild("left_arm").copyFrom(model.leftArm);
        root.getChild("right_arm").copyFrom(model.rightArm);
    }

    private static void copyLegs(ModelPart root, SettlementCitizenModel model) {
        root.getChild("left_leg").copyFrom(model.leftLeg);
        root.getChild("right_leg").copyFrom(model.rightLeg);
    }

    private static void draw(ModelPart root, PoseStack poses, MultiBufferSource buffers, int light, int color) {
        root.render(poses, buffers.getBuffer(RenderType.entityCutoutNoCull(WOOL)), light, OverlayTexture.NO_OVERLAY, color);
    }
}

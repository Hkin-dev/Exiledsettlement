package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/** Lightweight profession poses layered on vanilla walking and tool swings. */
public final class SettlementCitizenModel extends PlayerModel<SettlementCitizenEntity> {
    public SettlementCitizenModel(ModelPart root) {
        super(root, false);
    }

    @Override
    public void setupAnim(SettlementCitizenEntity citizen, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(citizen, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        CitizenActivity action = citizen.activity();
        float stroke = Mth.sin(ageInTicks * 0.28F);
        switch (action) {
            case FARMING, FORESTRY, DEMOLISHING -> {
                rightArm.xRot -= 0.75F + stroke * 0.38F;
                body.xRot = 0.10F;
            }
            case BUILDING, REPAIRING -> {
                rightArm.xRot -= 0.55F + stroke * 0.25F;
                leftArm.xRot -= 0.24F;
            }
            case TOOLMAKING, SAWING -> {
                rightArm.xRot -= 0.8F + stroke * 0.45F;
                leftArm.xRot -= 0.7F;
            }
            case MINING -> rightArm.xRot -= 0.9F + stroke * 0.65F;
            case FISHING -> rightArm.xRot -= 1.15F + stroke * 0.08F;
            case HERDING, CAPTURING -> rightArm.xRot -= 0.65F + stroke * 0.15F;
            case HUNTING, SELF_DEFENSE -> {
                rightArm.xRot -= 0.8F;
                leftArm.xRot -= 0.35F;
            }
            case TO_OUTPUT_CONTAINER, TO_WAREHOUSE, COLLECTING_PUBLIC_ITEM -> {
                rightArm.xRot -= 0.32F;
                leftArm.xRot -= 0.32F;
            }
            default -> {
            }
        }
        // The outer sleeves must track the base arms; vanilla copies them before these poses.
        rightSleeve.copyFrom(rightArm);
        leftSleeve.copyFrom(leftArm);
    }
}

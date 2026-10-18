package com.hkin.exiledsettlement.client.ysm;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.mixin.WalkAnimationStateAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.Map;
import java.util.WeakHashMap;

/** Draws a resident through YSM's normal player renderer without a maid-mod dependency. */
public final class YsmCitizenRendererBridge {
    private static final Map<SettlementCitizenEntity, ProxyEntry> PROXIES = new WeakHashMap<>();
    private static final EquipmentSlot[] COPIED_EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };
    private static boolean renderFailureLogged;

    private YsmCitizenRendererBridge() {
    }

    public static boolean render(
            SettlementCitizenEntity citizen,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight
    ) {
        if (!citizen.usesYsmModel()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) return false;
        String stage = "proxy";
        try {
            ProxyEntry entry = PROXIES.get(citizen);
            String profileName = residentProfileName(citizen);
            if (entry == null || entry.player.level() != level
                    || !entry.player.getGameProfile().getId().equals(citizen.getUUID())
                    || !entry.player.getGameProfile().getName().equals(profileName)) {
                GameProfile profile = new GameProfile(citizen.getUUID(), profileName);
                entry = new ProxyEntry(new RemotePlayer(level, profile));
                PROXIES.put(citizen, entry);
            }
            EntityRenderer<? super RemotePlayer> renderer = minecraft.getEntityRenderDispatcher()
                    .getRenderer(entry.player);
            copyRenderState(citizen, entry.player);
            stage = "selection";
            if (!entry.matches(citizen.ysmModelId(), citizen.ysmTextureName())) {
                YsmPlayerStateAccess.ApplyResult result = YsmPlayerStateAccess.apply(
                        entry.player, citizen.ysmModelId(), citizen.ysmTextureName());
                if (result == YsmPlayerStateAccess.ApplyResult.STATE_UNAVAILABLE && !entry.primed) {
                    renderPlayer(renderer, entry.player, entityYaw, partialTick, poseStack, buffers, packedLight);
                    entry.primed = true;
                    result = YsmPlayerStateAccess.apply(
                            entry.player, citizen.ysmModelId(), citizen.ysmTextureName());
                    if (result != YsmPlayerStateAccess.ApplyResult.APPLIED) {
                        logSelectionFailure(result);
                    } else {
                        entry.modelId = citizen.ysmModelId();
                        entry.textureName = citizen.ysmTextureName();
                    }
                    return true;
                }
                if (result != YsmPlayerStateAccess.ApplyResult.APPLIED) {
                    logSelectionFailure(result);
                    return false;
                }
                entry.modelId = citizen.ysmModelId();
                entry.textureName = citizen.ysmTextureName();
            }
            stage = "state";
            stage = "renderer";
            renderPlayer(renderer, entry.player, entityYaw, partialTick, poseStack, buffers, packedLight);
            entry.primed = true;
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                ExiledSettlementMod.LOGGER.warn(
                        "YSM resident rendering failed at semantic stage {}; affected residents will use the built-in model ({})",
                        stage, unavailable.getClass().getSimpleName());
            }
            return false;
        }
    }

    private static void renderPlayer(
            EntityRenderer<? super RemotePlayer> renderer,
            RemotePlayer player,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight
    ) {
        poseStack.pushPose();
        try {
            renderer.render(player, entityYaw, partialTick, poseStack, buffers, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    private static void logSelectionFailure(YsmPlayerStateAccess.ApplyResult result) {
        if (renderFailureLogged) return;
        renderFailureLogged = true;
        ExiledSettlementMod.LOGGER.warn(
                "YSM resident rendering could not apply the selected model after renderer initialization ({})",
                result.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String residentProfileName(SettlementCitizenEntity citizen) {
        if (citizen.getCustomName() != null) {
            String customName = citizen.getCustomName().getString().strip();
            if (!customName.isEmpty()) return customName;
        }
        String displayName = citizen.getDisplayName().getString().strip();
        return displayName.isEmpty() ? "Resident" : displayName;
    }

    private static void copyRenderState(SettlementCitizenEntity source, RemotePlayer target) {
        target.tickCount = source.tickCount;
        target.setPos(source.getX(), source.getY(), source.getZ());
        target.xo = source.xo;
        target.yo = source.yo;
        target.zo = source.zo;
        target.xOld = source.xOld;
        target.yOld = source.yOld;
        target.zOld = source.zOld;
        target.walkDistO = source.walkDistO;
        target.walkDist = source.walkDist;
        target.oAttackAnim = source.oAttackAnim;
        target.attackAnim = source.attackAnim;
        copyWalkAnimation(source, target);
        target.setXRot(source.getXRot());
        target.xRotO = source.xRotO;
        target.setYRot(source.getYRot());
        target.yRotO = source.yRotO;
        target.yBodyRot = source.yBodyRot;
        target.yBodyRotO = source.yBodyRotO;
        target.yHeadRot = source.yHeadRot;
        target.yHeadRotO = source.yHeadRotO;
        target.setPose(source.getPose());
        // Sleeping pose alone has no bed direction. YSM's player renderer needs
        // the same bed position as the resident to orient and offset its model.
        if (source.getSleepingPos().isPresent()) target.setSleepingPos(source.getSleepingPos().get());
        else target.clearSleepingPos();
        target.setShiftKeyDown(source.isShiftKeyDown());
        target.setSprinting(source.isSprinting());
        target.setSwimming(source.isSwimming());
        target.setDeltaMovement(source.getDeltaMovement());
        target.setOnGround(source.onGround());
        copyNeutralPlayerState(target);
        target.setCustomName(source.getCustomName());
        target.setCustomNameVisible(source.isCustomNameVisible());
        for (EquipmentSlot slot : COPIED_EQUIPMENT) {
            target.setItemSlot(slot, source.getItemBySlot(slot).copy());
        }
    }

    private static void copyNeutralPlayerState(RemotePlayer target) {
        // This player exists only as a YSM render adapter. Resident nutrition is
        // simulated separately, so an unset vanilla hunger value must not make
        // a model play its low-food (for example, holding-stomach) animation.
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(5.0F);
        target.getFoodData().setExhaustion(0.0F);
        YsmResidentFoodAccess.normalize(target);
        if (target.isUsingItem()) {
            target.stopUsingItem();
        }
    }

    private static void copyWalkAnimation(SettlementCitizenEntity source, RemotePlayer target) {
        WalkAnimationStateAccessor sourceAnimation =
                (WalkAnimationStateAccessor) (Object) source.walkAnimation;
        WalkAnimationStateAccessor targetAnimation =
                (WalkAnimationStateAccessor) (Object) target.walkAnimation;
        targetAnimation.exiledSettlement$setSpeedOld(sourceAnimation.exiledSettlement$getSpeedOld());
        targetAnimation.exiledSettlement$setSpeed(sourceAnimation.exiledSettlement$getSpeed());
        targetAnimation.exiledSettlement$setPosition(sourceAnimation.exiledSettlement$getPosition());
    }

    private static final class ProxyEntry {
        private final RemotePlayer player;
        private String modelId = "";
        private String textureName = "";
        private boolean primed;

        private ProxyEntry(RemotePlayer player) {
            this.player = player;
        }

        private boolean matches(String model, String texture) {
            return modelId.equals(model) && textureName.equals(texture);
        }
    }
}

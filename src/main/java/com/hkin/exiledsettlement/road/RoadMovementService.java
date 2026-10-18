package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class RoadMovementService {
    private static final int ROAD_GRACE_TICKS = 8;
    private static final String ROAD_GRACE_TAG = ExiledSettlementMod.MOD_ID + ".road_grace_ticks";
    private static final String ROAD_LEVEL_TAG = ExiledSettlementMod.MOD_ID + ".road_level";
    private static final ResourceLocation ROAD_SPEED_MODIFIER_ID = ExiledSettlementMod.id("road_speed_bonus");

    private RoadMovementService() {
    }

    public static void tick(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !(entity instanceof LivingEntity living)
                || !RoadEntityTags.receivesRoadSpeed(living)) {
            return;
        }
        AttributeInstance speed = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        int roadLevel = activeRoadLevel(level, living);
        if (roadLevel > 0) {
            RoadTier tier = RoadTier.fromLevel(roadLevel);
            int previousLevel = living.getPersistentData().getInt(ROAD_LEVEL_TAG);
            if (!speed.hasModifier(ROAD_SPEED_MODIFIER_ID) || previousLevel != roadLevel) {
                speed.removeModifier(ROAD_SPEED_MODIFIER_ID);
                speed.addTransientModifier(new AttributeModifier(ROAD_SPEED_MODIFIER_ID, tier.speedBonus(),
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                living.getPersistentData().putInt(ROAD_LEVEL_TAG, roadLevel);
            }
        } else {
            speed.removeModifier(ROAD_SPEED_MODIFIER_ID);
            living.getPersistentData().remove(ROAD_LEVEL_TAG);
        }
    }

    private static int activeRoadLevel(ServerLevel level, LivingEntity living) {
        CompoundTag persistent = living.getPersistentData();
        BlockPos belowFeet = living.blockPosition().below();
        if (RoadSpaceData.get(level).contains(belowFeet)) {
            persistent.putInt(ROAD_GRACE_TAG, ROAD_GRACE_TICKS);
            int levelAtFeet = RoadSpaceData.get(level).levelAt(belowFeet);
            persistent.putInt(ROAD_LEVEL_TAG, levelAtFeet);
            return levelAtFeet;
        }
        int grace = persistent.getInt(ROAD_GRACE_TAG);
        if (grace > 0) {
            persistent.putInt(ROAD_GRACE_TAG, grace - 1);
            return persistent.getInt(ROAD_LEVEL_TAG);
        }
        return 0;
    }
}

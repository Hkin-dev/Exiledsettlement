package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

public final class RoadEntityTags {
    public static final TagKey<EntityType<?>> ROAD_SPEED_USERS = TagKey.create(
            Registries.ENTITY_TYPE, ExiledSettlementMod.id("road_speed_users"));
    public static final TagKey<EntityType<?>> ROAD_PATHFINDERS = TagKey.create(
            Registries.ENTITY_TYPE, ExiledSettlementMod.id("road_pathfinders"));

    private RoadEntityTags() {
    }

    public static boolean receivesRoadSpeed(LivingEntity living) {
        return living.getType().is(ROAD_SPEED_USERS);
    }

    public static boolean prefersRoadPaths(Mob mob) {
        return mob.getType().is(ROAD_PATHFINDERS);
    }
}

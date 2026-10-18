package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** A real structure must not put its future collision volume into a living body. */
public final class ConstructionOccupancy {
    public static final String FAILURE="CONSTRUCTION_SPACE_OCCUPIED";
    public static boolean intersects(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement placement,AABB body) {
        for(var part:BlueprintPlacementGroups.parts(template,placement)) {
            var at=part.position();var state=BlueprintBlockStateResolver.resolve(level,part);
            for(var box:state.getCollisionShape(level,at).toAabbs())if(box.move(at).intersects(body))return true;
        }
        return false;
    }
    public static boolean occupied(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement placement,Entity worker) {
        if(worker!=null && worker.isAlive() && intersects(level,template,placement,worker.getBoundingBox()))return true;
        for(var part:BlueprintPlacementGroups.parts(template,placement)) {
            var at=part.position();var shape=BlueprintBlockStateResolver.resolve(level,part).getCollisionShape(level,at);
            if(shape.isEmpty())continue;
            for(var actor:level.getEntitiesOfClass(LivingEntity.class,shape.bounds().move(at),a->a.isAlive() && !a.isSpectator()))
                if(intersects(level,template,placement,actor.getBoundingBox()))return true;
        }
        return false;
    }
    public static boolean safeStanding(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement placement,Entity worker,BlockPos feet) {
        var body=worker.getBoundingBox().move(feet.getX()+.5-worker.getX(),feet.getY()-worker.getY(),feet.getZ()+.5-worker.getZ());
        return !intersects(level,template,placement,body);
    }
    private ConstructionOccupancy(){}
}

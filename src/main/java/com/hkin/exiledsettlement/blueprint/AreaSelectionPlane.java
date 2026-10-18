package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

/** The second corner is a ray/plane intersection, independent of terrain under the cursor. */
public final class AreaSelectionPlane {
    private AreaSelectionPlane() {}
    @Nullable
    public static BlockPos corner(Vec3 start, Vec3 end, int standingY) {
        double dy=end.y-start.y;
        if(Math.abs(dy)<1.0e-7)return null;
        double t=(standingY-start.y)/dy;
        if(t<0 || t>1)return null;
        return BlockPos.containing(start.x+(end.x-start.x)*t,standingY,start.z+(end.z-start.z)*t);
    }
}

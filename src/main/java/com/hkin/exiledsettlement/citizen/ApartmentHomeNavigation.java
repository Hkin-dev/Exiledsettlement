package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Bounded stair detours within a resident's apartment, for bed access and departure. */
public final class ApartmentHomeNavigation {
    private record ReturnHome(BuildingRecord home, BlockPos bed) {}
    private static final Map<Mob, ReturnHome> RETURNS = new WeakHashMap<>();
    private ApartmentHomeNavigation() {}

    public static void returning(Mob resident, BuildingRecord home, BlockPos bed) {
        if (home.type() == BuildingType.APARTMENT && home.operational() && home.servicePositions().contains(bed))
            RETURNS.put(resident, new ReturnHome(home, bed.immutable()));
        else RETURNS.remove(resident);
    }

    public static int allowance(Mob resident, Set<BlockPos> targets) {
        var route = RETURNS.get(resident);
        if (route == null) return 0;
        if (!route.home.active()
                || !route.home.operational() || !route.home.servicePositions().contains(route.bed)) {
            RETURNS.remove(resident);
            return 0;
        }
        // Outdoor travel never inherits this search budget. The same bounded search
        // is needed when the resident leaves an upper storey through the real door.
        if (!route.home.contains(resident.blockPosition()) || targets.isEmpty()) return 0;
        boolean bedApproach=targets.stream().allMatch(p -> route.home.contains(p) && p.distSqr(route.bed)<=16);
        boolean departure=targets.stream().allMatch(p -> Math.abs(p.getY()-route.home.position().getY())<=1
                && p.distSqr(route.home.position())<=24*24
                && (!route.home.contains(p) || p.distSqr(route.home.position())<=16));
        return bedApproach || departure ? 64 : 0;
    }

    /** Low closed trapdoors can become both head-space and jump-up path nodes.
     * Route around free-standing tables instead of under or over their tops.
     * Scope this correction to the resident's own new apartment only. */
    public static boolean lowTableBlocks(Mob resident, BlockPos feet) {
        var route = RETURNS.get(resident);
        if (route == null || route.home.templateVersion() < 2 || !route.home.active()
                || !route.home.operational() || !route.home.servicePositions().contains(route.bed)
                || !route.home.contains(feet)) return false;
        var head = resident.level().getBlockState(feet.above());
        var atFeet = resident.level().getBlockState(feet);
        return head.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                && !head.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                || atFeet.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                && !atFeet.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && resident.level().getBlockState(feet.below()).isAir();
    }

}

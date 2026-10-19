package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Ground fill may replace ordinary blocks and does not wait for resident occupancy. */
public final class FoundationPlacement {
    private FoundationPlacement() { }

    public static boolean applies(TerrainProject project, TerrainProject.Step step) {
        return project != null && !project.bridge && !project.areaClearance && step.fill()
                && (step.target().is(Blocks.DIRT) || step.target().is(Blocks.COBBLESTONE));
    }

    public static boolean ordinary(ServerLevel level, BlockPos at, BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && state.getDestroySpeed(level, at) >= 0;
    }

    public static boolean replaces(ServerLevel level, TerrainProject project, TerrainProject.Step step) {
        return applies(project, step) && !(project.pitFill && PitFillSurface.vegetation(level.getBlockState(step.position())))
                && ordinary(level, step.position(), level.getBlockState(step.position()));
    }

    /** Defer an obsolete dig until its final fill succeeds; never credit work in advance. */
    public static boolean coveredDig(ServerLevel level, TerrainProject project, TerrainProject.Step step) {
        if (step.fill()) return false;
        var goal = project.template().placementAt(step.position());
        if (goal == null) return false;
        return replaces(level, project, new TerrainProject.Step(step.position(), goal.state(), step.original()));
    }

    public static boolean occupied(ServerLevel level, BlockPos at, boolean ignoreResidents) {
        return !level.getEntities((Entity) null, new AABB(at), entity -> entity.isAlive()
                && !entity.isSpectator() && entity instanceof LivingEntity
                && !(ignoreResidents && entity instanceof SettlementCitizenEntity)).isEmpty();
    }
}

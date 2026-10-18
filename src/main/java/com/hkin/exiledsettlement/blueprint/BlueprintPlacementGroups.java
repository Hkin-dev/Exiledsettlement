package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Geometry stays immutable; one item may own two world cells. Double chests are NOT a group. */
public final class BlueprintPlacementGroups {
    private static final int STAGED_FLAGS = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private BlueprintPlacementGroups() { }

    public static boolean isPaired(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock;
    }

    public static boolean isPrimary(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        if (state.getBlock() instanceof BedBlock) return state.getValue(BedBlock.PART) == BedPart.FOOT;
        return true;
    }

    public static BlockPos partnerPosition(BlueprintPlacement placement) {
        BlockState state = placement.state();
        if (state.getBlock() instanceof DoorBlock) {
            return isPrimary(state) ? placement.position().above() : placement.position().below();
        }
        if (state.getBlock() instanceof BedBlock) {
            Direction direction = state.getValue(BedBlock.FACING);
            return placement.position().relative(isPrimary(state) ? direction : direction.getOpposite());
        }
        return placement.position();
    }

    /** Empty means malformed paired geometry; never silently turn it into a free-standing half. */
    public static List<BlueprintPlacement> parts(BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        if (!isPaired(placement.state())) return List.of(placement);
        BlueprintPlacement partner = template.placementAt(partnerPosition(placement));
        if (partner == null || !partner.mode().isFinalStructure()
                || partner.state().getBlock() != placement.state().getBlock()
                || isPrimary(partner.state()) == isPrimary(placement.state())
                || !partnerPosition(partner).equals(placement.position())) return List.of();
        BlockState first = placement.state();
        BlockState second = partner.state();
        if (first.getBlock() instanceof DoorBlock
                && (first.getValue(DoorBlock.FACING) != second.getValue(DoorBlock.FACING)
                || first.getValue(DoorBlock.HINGE) != second.getValue(DoorBlock.HINGE))) return List.of();
        if (first.getBlock() instanceof BedBlock
                && first.getValue(BedBlock.FACING) != second.getValue(BedBlock.FACING)) return List.of();
        return isPrimary(first) ? List.of(placement, partner) : List.of(partner, placement);
    }

    public static boolean claimed(BuildingBlueprintTemplate template, BlueprintPlacement placement, Set<BlockPos> claims) {
        return claims.contains(placement.position()) || parts(template, placement).stream()
                .anyMatch(part -> claims.contains(part.position()));
    }

    public static boolean satisfied(ServerLevel level, BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        List<BlueprintPlacement> parts = parts(template, placement);
        return !parts.isEmpty() && parts.stream().allMatch(part ->
                BlueprintService.matchesPlacedState(level.getBlockState(part.position()), part.state()));
    }

    /** Stage both cells before sending updates or invoking protection listeners. Roll back as a unit. */
    public static OperationResult<List<BlockSnapshot>> place(
            ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate template,
            BlueprintPlacement requested, @Nullable Entity worker, boolean debug
    ) {
        return place(level,settlement,template,requested,worker,debug,false);
    }

    public static OperationResult<List<BlockSnapshot>> placeTimed(ServerLevel level, SettlementRecord settlement,
            BuildingBlueprintTemplate template, BlueprintPlacement requested, LivingEntity worker) {
        return place(level,settlement,template,requested,worker,false,true);
    }

    private static OperationResult<List<BlockSnapshot>> place(
            ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate template,
            BlueprintPlacement requested, @Nullable Entity worker, boolean debug, boolean timed
    ) {
        List<BlueprintPlacement> parts = parts(template, requested);
        if (parts.size() != 2) return OperationResult.failure("BLUEPRINT_INVALID_PAIR");
        if (!debug) {
            for (BlueprintPlacement part : parts) {
                BlockPos pos = part.position();
                if (!settlement.contains(pos)) return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
                if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) {
                    return OperationResult.failure("BLUEPRINT_AREA_NOT_LOADED");
                }
                BlockState current = level.getBlockState(pos);
                if(timed) {
                    if(!current.isAir() && !BlueprintService.matchesPlacedState(current,part.state())
                            && worker instanceof LivingEntity living && !CommonHooks.canEntityDestroy(level,pos,living))
                        return OperationResult.failure("PROTECTED_AREA");
                    continue;
                }
                if (current.getBlock() instanceof BedBlock && current.getValue(BedBlock.OCCUPIED)) {
                    return OperationResult.failure("BLUEPRINT_PAIR_OCCUPIED");
                }
                if (!BlueprintService.matchesPlacedState(current, part.state())
                        && (!current.canBeReplaced() || !current.getFluidState().isEmpty()
                        || level.getBlockEntity(pos) != null)) return OperationResult.failure("BLUEPRINT_BLOCKED");
                if (!level.isUnobstructed(part.state(), pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
                    return OperationResult.failure("BLUEPRINT_PAIR_OCCUPIED");
                }
            }
            if(!timed) {
            BlueprintPlacement primary = parts.getFirst();
            if (primary.state().getBlock() instanceof DoorBlock) {
                if (!primary.state().canSurvive(level, primary.position())) {
                    return OperationResult.failure("BLUEPRINT_UNSUPPORTED");
                }
            } else {
                for (BlueprintPlacement part : parts) {
                    BlockPos below = part.position().below();
                    if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                        return OperationResult.failure("BLUEPRINT_UNSUPPORTED");
                    }
                }
            }
            }
        }
        List<BlockSnapshot> snapshots = new ArrayList<>();
        List<BlockState> expected = new ArrayList<>();
        // Preserve the dynamic state of a surviving half during repair (e.g. an open door).
        BlockState surviving = parts.stream().map(part -> level.getBlockState(part.position()))
                .filter(state -> state.getBlock() == requested.state().getBlock()).findFirst().orElse(null);
        for (BlueprintPlacement part : parts) {
            BlockState state = part.state();
            if (surviving != null && state.getBlock() instanceof DoorBlock) {
                state = state.setValue(DoorBlock.OPEN, surviving.getValue(DoorBlock.OPEN))
                        .setValue(DoorBlock.POWERED, surviving.getValue(DoorBlock.POWERED));
            }
            expected.add(state);
            if (debug) level.getChunkAt(part.position());
            snapshots.add(BlockSnapshot.create(level.dimension(), level, part.position(), STAGED_FLAGS));
        }
        try {
            for (int i = 0; i < parts.size(); i++) {
                BlockPos pos = parts.get(i).position();
                if (!level.getBlockState(pos).equals(expected.get(i))
                        && !level.setBlock(pos, expected.get(i), STAGED_FLAGS)) {
                    restore(level, snapshots);
                    return OperationResult.failure("PROTECTED_AREA");
                }
            }
            if (!debug && EventHooks.onMultiBlockPlace(worker, snapshots, Direction.UP)) {
                restore(level, snapshots);
                return OperationResult.failure("PROTECTED_AREA");
            }
            for (int i = 0; i < parts.size(); i++) {
                if (!level.getBlockState(parts.get(i).position()).equals(expected.get(i))) {
                    restore(level, snapshots);
                    return OperationResult.failure("CONSTRUCTION_TARGET_CHANGED");
                }
            }
            if(timed) {
                for(var snapshot:snapshots) {
                    level.sendBlockUpdated(snapshot.getPos(),snapshot.getState(),level.getBlockState(snapshot.getPos()),Block.UPDATE_CLIENTS);
                }
            } else publish(level, snapshots);
            return OperationResult.success(List.copyOf(snapshots));
        } catch (RuntimeException failure) {
            restore(level, snapshots);
            throw failure;
        }
    }

    public static boolean remove(ServerLevel level, BuildingBlueprintTemplate template,
                                 BlueprintPlacement requested, @Nullable Entity worker) {
        return com.hkin.exiledsettlement.citizen.ResidentBlockDrops.run(level, worker, () -> removeDirect(level, template, requested, worker), Boolean.TRUE::equals);
    }

    private static boolean removeDirect(ServerLevel level, BuildingBlueprintTemplate template,
                                 BlueprintPlacement requested, @Nullable Entity worker) {
        List<BlueprintPlacement> parts = parts(template, requested);
        if (parts.size() != 2) return false;
        List<BlockSnapshot> snapshots = new ArrayList<>();
        for (BlueprintPlacement part : parts) {
            if (!level.hasChunkAt(part.position())) return false;
            BlockState current = level.getBlockState(part.position());
            if (current.isAir()) continue; // Recover an already-orphaned half without two drops.
            if (!BlueprintService.matchesPlacedState(current, part.state())
                    || current.getBlock() instanceof BedBlock && current.getValue(BedBlock.OCCUPIED)
                    || worker instanceof LivingEntity living
                    && !CommonHooks.canEntityDestroy(level, part.position(), living)) return false;
            snapshots.add(BlockSnapshot.create(level.dimension(), level, part.position(), STAGED_FLAGS));
        }
        if (snapshots.isEmpty()) return false;
        for (BlockSnapshot snapshot : snapshots) {
            if (!level.setBlock(snapshot.getPos(), Blocks.AIR.defaultBlockState(), STAGED_FLAGS)) {
                restore(level, snapshots);
                return false;
            }
        }
        publish(level, snapshots);
        Block.popResource(level, parts.getFirst().position(), new ItemStack(requested.state().getBlock().asItem()));
        return true;
    }

    public static void restore(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore(STAGED_FLAGS);
        publish(level, snapshots);
    }

    private static void publish(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, snapshot.getState(), state, Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }
}

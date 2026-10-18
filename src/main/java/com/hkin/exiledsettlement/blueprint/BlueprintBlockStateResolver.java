package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.StairBlock;
import java.util.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Resolves connection properties that a normal player placement derives from surrounding blocks. */
public final class BlueprintBlockStateResolver {
    private BlueprintBlockStateResolver() {
    }

    public static BlockState resolve(ServerLevel level, BlueprintPlacement placement) {
        return connected(placement.state().getBlock()) ? resolveConnections(level,placement.position(),placement.state()) : placement.state();
    }

    public static boolean refreshExisting(ServerLevel level, BlueprintPlacement placement) {
        BlockState current = level.getBlockState(placement.position());
        if (current.getBlock() != placement.state().getBlock()) return false;
        if (placement.state().getBlock() instanceof ChestBlock) {
            return refreshChestPair(level, placement, current);
        }
        if (!connected(current.getBlock())) return false;
        return refreshConnections(level,placement.position());
    }

    private static final Map<BuildingBlueprintTemplate,List<BlueprintPlacement>> CONNECTIONS=new WeakHashMap<>();
    private static boolean connected(Block block) {
        return block instanceof FenceBlock || block instanceof IronBarsBlock || block instanceof WallBlock
                || block instanceof FenceGateBlock || block instanceof StairBlock;
    }
    private static BlockState resolveConnections(ServerLevel level,BlockPos at,BlockState current) {
        var result=current;
        for(var direction:Direction.values()) {
            var neighbor=at.relative(direction);
            // Do not load chunks or disconnect against temporarily absent neighbours.
            if(!level.hasChunkAt(neighbor))return current;
            result=result.updateShape(direction,level.getBlockState(neighbor),level,at,neighbor);
        }
        return result.getBlock()==current.getBlock()?result:current;
    }
    public static boolean refreshConnections(ServerLevel level,BlockPos at) {
        if(!level.hasChunkAt(at))return false;
        var current=level.getBlockState(at);if(!connected(current.getBlock()))return false;
        var resolved=resolveConnections(level,at,current);
        // Same physical block: preserve orientation, open state, waterlogging and inventories.
        // Only the block's own vanilla connection update runs; no break/place or material charge.
        return !resolved.equals(current) && level.setBlock(at,resolved,Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    public static void refreshCompleted(ServerLevel level,BuildingBlueprintTemplate template) {
        var placements=CONNECTIONS.computeIfAbsent(template,t->t.placements().stream()
                .filter(p->p.mode().isFinalStructure() && (connected(p.state().getBlock()) || p.state().getBlock() instanceof ChestBlock)).toList());
        for(var p:placements) {
            if(!level.hasChunkAt(p.position()) || level.getBlockState(p.position()).getBlock()!=p.state().getBlock())continue;
            refreshExisting(level,template,p);
            for(var direction:Direction.values())refreshConnections(level,p.position().relative(direction));
        }
    }

    /** Repair only a pair explicitly declared by the same versioned template. Inventories stay in place. */
    public static boolean refreshExisting(ServerLevel level, BuildingBlueprintTemplate template, BlueprintPlacement placement) {
        BlockState expected = placement.state();
        if (!(expected.getBlock() instanceof ChestBlock)) return refreshExisting(level, placement);
        BlockState current = level.getBlockState(placement.position());
        if (current.getBlock() != expected.getBlock()) return false;
        if (expected.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return !current.equals(expected) && level.setBlock(placement.position(), expected, Block.UPDATE_CLIENTS);
        }
        BlockPos neighborPos = placement.position().relative(ChestBlock.getConnectedDirection(expected));
        BlueprintPlacement partner = template.placementAt(neighborPos);
        if (partner == null || partner.state().getBlock() != expected.getBlock()
                || partner.state().getValue(ChestBlock.FACING) != expected.getValue(ChestBlock.FACING)
                || partner.state().getValue(ChestBlock.TYPE) != expected.getValue(ChestBlock.TYPE).getOpposite()
                || !neighborPos.relative(ChestBlock.getConnectedDirection(partner.state())).equals(placement.position())) return false;
        BlockState neighbor = level.getBlockState(neighborPos);
        if (neighbor.getBlock() != expected.getBlock()) return false;
        boolean changed = !current.equals(expected) && level.setBlock(placement.position(), expected, Block.UPDATE_CLIENTS);
        changed |= !neighbor.equals(partner.state()) && level.setBlock(neighborPos, partner.state(), Block.UPDATE_CLIENTS);
        return changed;
    }

    private static boolean refreshChestPair(
            ServerLevel level, BlueprintPlacement placement, BlockState current
    ) {
        BlockState expected = placement.state();
        ChestType expectedType = expected.getValue(ChestBlock.TYPE);
        if (expectedType == ChestType.SINGLE) return false;
        Direction facing = expected.getValue(ChestBlock.FACING);
        Direction connected = ChestBlock.getConnectedDirection(expected);
        BlockPos neighborPosition = placement.position().relative(connected);
        BlockState neighbor = level.getBlockState(neighborPosition);
        if (neighbor.getBlock() != expected.getBlock()
                || neighbor.getValue(ChestBlock.FACING) != facing) return false;
        ChestType neighborType = expectedType == ChestType.RIGHT ? ChestType.LEFT : ChestType.RIGHT;
        boolean changed = false;
        if (!current.equals(expected)) {
            changed |= level.setBlock(placement.position(), expected, Block.UPDATE_CLIENTS);
        }
        BlockState paired = neighbor.setValue(ChestBlock.TYPE, neighborType);
        if (!neighbor.equals(paired)) {
            changed |= level.setBlock(neighborPosition, paired, Block.UPDATE_CLIENTS);
        }
        return changed;
    }
}

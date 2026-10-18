package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/** Read-only cell and geometry rules shared by placement preview and authority. */
public final class BuildingPlacementRules {
    private BuildingPlacementRules() { }
    public record Cell(boolean valid, boolean clearable, String reason) { }
    private static final Cell READY = new Cell(true, false, "OK");
    private static final Cell CLEARABLE = new Cell(true, true, "CLEARANCE_REQUIRED");

    public static Cell checkCell(Level level, BuildingBlueprintTemplate template, BlueprintPlacement p) {
        if (p.mode() == BlueprintPlacementMode.CLEARANCE && !requiresClearance(template, p.position())) return READY;
        if (level.isOutsideBuildHeight(p.position())) return new Cell(false,false,"PLACEMENT_BUILD_HEIGHT");
        if (!level.hasChunkAt(p.position())) return new Cell(false,false,"BLUEPRINT_AREA_NOT_LOADED");
        BlockState current = level.getBlockState(p.position());
        if (current.is(ModBlocks.SETTLEMENT_CORE.get())) return new Cell(false,false,"PLACEMENT_REGISTERED_STRUCTURE");
        if (p.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return READY;
        BlueprintPlacement finished = template.placementAt(p.position());
        if (p.mode() == BlueprintPlacementMode.CLEARANCE && finished != null && finished.mode().isFinalStructure()
                && BlueprintService.matchesPlacedState(current,finished.state())) return READY;
        if (p.mode() != BlueprintPlacementMode.CLEARANCE && BlueprintService.matchesPlacedState(current,p.state())) return READY;
        if (!current.getFluidState().isEmpty()) return new Cell(false,false,"PLACEMENT_FLUID");
        if (p.mode() == BlueprintPlacementMode.FARM_TILL || p.mode() == BlueprintPlacementMode.FARM_WATER
                || p.state().is(Blocks.DIRT_PATH)) {
            if (soil(current)) return READY;
        }
        if (p.material() == Items.WATER_BUCKET && current.getBlock() == p.state().getBlock()
                && current.hasProperty(BlockStateProperties.WATERLOGGED)
                && !current.getValue(BlockStateProperties.WATERLOGGED)) return READY;
        if (current.isAir()) return READY;
        return current.getDestroySpeed(level,p.position()) >= 0.0F ? CLEARABLE
                : new Cell(false,false,"PLACEMENT_UNBREAKABLE");
    }

    private static boolean soil(BlockState s) {
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.FARMLAND);
    }

    /** Append, never reorder, so saved progress indices keep their previous meaning. */
    public static List<BlueprintPlacement> withEntranceClearances(BuildingType type,
            List<BlueprintPlacement> original,List<BlueprintEntrance> entrances) {
        if (type.isDynamicArea() || entrances.isEmpty()) return original;
        List<BlueprintPlacement> result = new ArrayList<>(original);
        Set<BlockPos> positions = new HashSet<>(); original.forEach(p -> positions.add(p.position()));
        for (BlockPos p : entranceAir(entrances)) if (positions.add(p))
            result.add(new BlueprintPlacement(p,Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE));
        return List.copyOf(result);
    }

    private static Set<BlockPos> entranceAir(List<BlueprintEntrance> entrances) {
        Set<BlockPos> air = new LinkedHashSet<>();
        for (BlueprintEntrance e : entrances) for (int step=-1;step<=1;step++) {
            BlockPos feet = e.position().relative(e.outward(),step);
            air.add(feet.immutable()); air.add(feet.above());
        }
        return air;
    }

    public record Layout(Set<BlockPos> structure, Set<BlockPos> air) {
        public boolean reserves(BlockPos p) { return structure.contains(p) || air.contains(p); }
        public BlockPos conflict(Layout other) {
            for (BlockPos p : structure) if (other.reserves(p)) return p;
            for (BlockPos p : air) if (other.structure.contains(p)) return p;
            return null;
        }
    }

    public static Layout layout(BuildingBlueprintTemplate t) {
        Set<BlockPos> structure = new LinkedHashSet<>(), air = entranceAir(t.entrances());
        Map<Long,int[]> columns = new HashMap<>();
        for (BlueprintPlacement p : t.placements()) if (p.mode().isFinalStructure() && !p.state().isAir()) {
            BlockPos pos=p.position(); structure.add(pos);
            long column=new BlockPos(pos.getX(),0,pos.getZ()).asLong();
            int[] range=columns.computeIfAbsent(column,k -> new int[]{pos.getY(),pos.getY()});
            range[0]=Math.min(range[0],pos.getY()); range[1]=Math.max(range[1],pos.getY());
        }
        for (BlueprintPlacement p : t.placements()) if (p.mode()==BlueprintPlacementMode.CLEARANCE) {
            BlockPos pos=p.position(); int[] range=columns.get(new BlockPos(pos.getX(),0,pos.getZ()).asLong());
            // Rooms under the actual shell retain their clearance. Empty bounding
            // columns outside an irregular building are not an exclusive volume.
            if (t.type().isDynamicArea() || range!=null && pos.getY()>range[0] && pos.getY()<range[1]) air.add(pos);
        }
        air.removeAll(structure);
        return new Layout(Collections.unmodifiableSet(structure),Collections.unmodifiableSet(air));
    }

    public static boolean requiresClearance(BuildingBlueprintTemplate t,BlockPos p) {
        return t.terrainWork() || t.type().isDynamicArea() || t.placementLayout().reserves(p);
    }
}

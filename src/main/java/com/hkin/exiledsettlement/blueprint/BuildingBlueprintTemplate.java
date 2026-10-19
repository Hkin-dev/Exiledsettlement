package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildingBlueprintTemplate {
    private BuildingPlacementRules.Layout placementLayout;
    public BuildingPlacementRules.Layout placementLayout() {
        if (placementLayout == null) placementLayout = BuildingPlacementRules.layout(this);
        return placementLayout;
    }
    private boolean terrainWork;
    public boolean terrainWork(){return terrainWork;}
    public BuildingBlueprintTemplate asTerrainWork(){terrainWork=true;return this;}
    private final BuildingType type;
    private final BlockPos anchor;
    private final Direction facing;
    private final int width;
    private final int depth;
    private final int height;
    private final List<BlueprintPlacement> placements;
    private final List<BlueprintPlacement> gatePlacements;
    private final Map<BlockPos, BlueprintPlacement> placementsByPosition;
    private final Map<Item, Integer> requirements;
    private final BlockPos minCorner;
    private final BlockPos maxCorner;
    private final int transitionMargin;
    private final List<BlueprintEntrance> entrances;
    private final Map<BlockPos, Integer> constructionPhases;
    private final Map<BlockPos, ConstructionNode> constructionNodes;
    private final Map<ConstructionNode, List<BlockPos>> nodePrerequisites;

    /** Stable template-local nodes; their completion is always read from the world. */
    public record ConstructionNode(int phase, int layer, int sectionX, int sectionZ) { }

    public BuildingBlueprintTemplate(
            BuildingType type,
            BlockPos anchor,
            Direction facing,
            int width,
            int depth,
            int height,
            BlockPos minCorner,
            BlockPos maxCorner,
            int transitionMargin,
            List<BlueprintPlacement> placements
    ) {
        this(type, anchor, facing, width, depth, height, minCorner, maxCorner,
                transitionMargin, placements, List.of());
    }

    public BuildingBlueprintTemplate(
            BuildingType type,
            BlockPos anchor,
            Direction facing,
            int width,
            int depth,
            int height,
            BlockPos minCorner,
            BlockPos maxCorner,
            int transitionMargin,
            List<BlueprintPlacement> placements,
            List<BlueprintEntrance> entrances
    ) {
        this.type = type;
        this.anchor = anchor.immutable();
        this.facing = facing;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.minCorner = minCorner.immutable();
        this.maxCorner = maxCorner.immutable();
        this.transitionMargin = Math.max(0, transitionMargin);
        // Imported snapshots historically charged each half separately. Keep their
        // block states/coordinates unchanged; only the primary owns the single item.
        this.placements = BuildingPlacementRules.withEntranceClearances(type, placements.stream()
                .map(placement -> (type==BuildingType.FARM_FIELD || type==BuildingType.MELON_FIELD)
                        && placement.state().is(net.minecraft.world.level.block.Blocks.FARMLAND)
                        ? new BlueprintPlacement(placement.position(),placement.state(),null,BlueprintPlacementMode.FARM_TILL)
                        : placement).map(placement ->
                BlueprintPlacementGroups.isPaired(placement.state())
                        ? new BlueprintPlacement(placement.position(), placement.state(),
                        BlueprintPlacementGroups.isPrimary(placement.state())
                                ? placement.state().getBlock().asItem() : null, placement.mode())
                        : placement).toList(), entrances);
        // Only immutable template geometry is indexed; gate state is read from the world on every update.
        this.gatePlacements = this.placements.stream()
                .filter(placement -> placement.state().getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
                .toList();
        Map<BlockPos, BlueprintPlacement> indexedPlacements = new LinkedHashMap<>();
        for (BlueprintPlacement placement : this.placements) {
            BlueprintPlacement existing = indexedPlacements.get(placement.position());
            // A preparatory clear-to-air step does not describe the finished cell.
            // Keep both ordered actions, but index the final block for dependencies,
            // completed clearance checks and paired placement lookups.
            if (existing == null || existing.mode() == BlueprintPlacementMode.CLEARANCE
                    && placement.mode().isFinalStructure()) {
                indexedPlacements.put(placement.position().immutable(), placement);
            }
        }
        this.placementsByPosition = Collections.unmodifiableMap(indexedPlacements);
        this.entrances = List.copyOf(entrances);

        Map<Item, Integer> materialCounts = new LinkedHashMap<>();
        for (BlueprintPlacement placement : this.placements) {
            if (placement.material() != null) {
                materialCounts.merge(placement.material(), 1, Integer::sum);
            }
        }
        this.requirements = Collections.unmodifiableMap(materialCounts);
        this.constructionPhases = ConstructionSequence.compile(this);
        Map<BlockPos, ConstructionNode> nodes = new LinkedHashMap<>();
        for (BlueprintPlacement placement : this.placements) {
            BlockPos p = placement.position();
            nodes.put(p, new ConstructionNode(constructionPhase(placement), p.getY(),
                    Math.floorDiv(p.getX() - minCorner.getX(), 4),
                    Math.floorDiv(p.getZ() - minCorner.getZ(), 4)));
        }
        this.constructionNodes = Map.copyOf(nodes);
        Map<ConstructionNode, java.util.Set<BlockPos>> dependencies = new LinkedHashMap<>();
        for (BlueprintPlacement placement : this.placements) {
            if (!placement.mode().isFinalStructure()) continue;
            BlueprintPlacement below = placementAt(placement.position().below());
            if (below == null || below.mode() == BlueprintPlacementMode.CLEARANCE
                    || below.state().isAir()) continue;
            // Multi-cell placements consume one item as one action. A door's
            // upper half cannot depend on its own lower-half action.
            if (BlueprintPlacementGroups.isPaired(placement.state())
                    && BlueprintPlacementGroups.partnerPosition(placement).equals(below.position())) continue;
            dependencies.computeIfAbsent(nodes.get(placement.position()), key -> new java.util.LinkedHashSet<>())
                    .add(below.position());
        }
        Map<ConstructionNode, List<BlockPos>> frozen = new LinkedHashMap<>();
        dependencies.forEach((node, positions) -> frozen.put(node, List.copyOf(positions)));
        this.nodePrerequisites = Map.copyOf(frozen);
    }

    public BuildingType type() {
        return type;
    }

    public int constructionPhase(BlueprintPlacement placement) {
        return constructionPhases.getOrDefault(placement.position(), 0);
    }

    public ConstructionNode constructionNode(BlueprintPlacement placement) {
        return constructionNodes.get(placement.position());
    }

    public List<BlockPos> constructionPrerequisites(BlueprintPlacement placement) {
        return nodePrerequisites.getOrDefault(constructionNode(placement), List.of());
    }

    public BlockPos anchor() {
        return anchor;
    }

    public BlockPos registrationPosition() {
        return anchor;
    }

    public BlockPos minCorner() {
        return minCorner;
    }

    public BlockPos maxCorner() {
        return maxCorner;
    }

    public int transitionMargin() {
        return transitionMargin;
    }

    public Direction facing() {
        return facing;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int height() {
        return height;
    }

    public List<BlueprintPlacement> placements() {
        return placements;
    }

    public List<BlueprintPlacement> gatePlacements() {
        return gatePlacements;
    }

    public BlueprintPlacement placementAt(BlockPos position) {
        return placementsByPosition.get(position);
    }

    public List<BlueprintEntrance> entrances() {
        return entrances;
    }

    public Map<Item, Integer> requirements() {
        return requirements;
    }

    public Map<Item, Integer> requirements(LevelReader level) {
        Map<Item, Integer> materialCounts = new LinkedHashMap<>();
        for (BlueprintPlacement placement : placements) {
            if (placement.material() == null) {
                continue;
            }
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    && !level.getBlockState(placement.position()).isAir()) {
                continue;
            }
            materialCounts.merge(placement.material(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(materialCounts);
    }
}

package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.Collections;

/** Construction-only clearance. Completed buildings never maintain these empty cells. */
public final class ConstructionWorkArea {
    private static final Map<BuildingBlueprintTemplate,BuildingBlueprintTemplate> CACHE=new WeakHashMap<>();
    private static final Set<BuildingBlueprintTemplate> FULL=Collections.newSetFromMap(new WeakHashMap<>());
    private ConstructionWorkArea() { }

    public static synchronized BuildingBlueprintTemplate template(BuildingBlueprintTemplate base) {
        if(base.terrainWork() || FULL.contains(base))return base;
        return CACHE.computeIfAbsent(base,ConstructionWorkArea::build);
    }
    public static synchronized boolean full(BuildingBlueprintTemplate template) { return FULL.contains(template); }
    private static BuildingBlueprintTemplate build(BuildingBlueprintTemplate base) {
        int floor=base.entrances().stream().mapToInt(e->e.position().getY()-1).min().orElse(base.anchor().getY()-1);
        int top=Math.max(base.maxCorner().getY(),floor+6);
        var placements=new ArrayList<BlueprintPlacement>();
        // Visit top-down so loose natural obstacles are not left floating over the worksite.
        for(int y=top;y>floor;y--)for(int x=base.minCorner().getX();x<=base.maxCorner().getX();x++)
            for(int z=base.minCorner().getZ();z<=base.maxCorner().getZ();z++) {
                var at=new BlockPos(x,y,z);
                if(base.placementAt(at)==null)placements.add(new BlueprintPlacement(at,Blocks.AIR.defaultBlockState(),null,BlueprintPlacementMode.CLEARANCE));
            }
        placements.addAll(base.placements());
        var result=new BuildingBlueprintTemplate(base.type(),base.anchor(),base.facing(),base.width(),base.depth(),base.height(),
                base.minCorner(),base.maxCorner(),base.transitionMargin(),placements,base.entrances());
        FULL.add(result);return result;
    }
}

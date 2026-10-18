package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Snapshot-only identity. Public work keeps its existing saved blueprint host type. */
public enum WorksiteDisplayKind {
    BUILDING("building"), FILL("fill"), CLEARANCE("clearance"), TERRAIN("terrain"),
    FOUNDATION("foundation"), ROAD("road"), BRIDGE("bridge"), ACCESS("access");

    private final String key;

    WorksiteDisplayKind(String key) { this.key = "gui.exiledsettlement.worksite_kind." + key; }

    public String translationKey() { return key; }
    public boolean publicWorks() { return this != BUILDING; }

    public Item icon() {
        return switch (this) {
            case FILL -> Items.DIRT;
            case CLEARANCE -> Items.IRON_AXE;
            case TERRAIN -> Items.IRON_SHOVEL;
            case FOUNDATION -> Items.COBBLESTONE;
            case ROAD -> Items.DIRT_PATH;
            case BRIDGE -> Items.OAK_SLAB;
            case ACCESS -> Items.COBBLESTONE_STAIRS;
            case BUILDING -> Items.BRICKS;
        };
    }

    public static WorksiteDisplayKind of(SettlementRecord settlement, BlueprintRecord site) {
        var project = site.terrainProject();
        if (project == null) return BUILDING;
        if (project.bridge) return BRIDGE;
        if (project.road) return ROAD;
        if (project.areaClearance) return CLEARANCE;
        if (project.entranceAccess || project.escapeFor != null) return ACCESS;
        if (project.regionOwner != null) return FOUNDATION;
        if (!project.publicGround) {
            for (var dependency : project.dependencies) {
                var building = settlement.building(dependency);
                var blueprint = settlement.blueprint(dependency);
                if (building != null || blueprint != null && blueprint.terrainProject() == null)
                    return FOUNDATION;
            }
        }
        boolean fill = false, clear = false;
        for (int index = 0; index < project.steps.size(); index++) {
            if (project.abandoned.get(index)) continue;
            if (project.steps.get(index).fill()) fill = true;
            else clear = true;
            if (fill && clear) return TERRAIN;
        }
        return fill ? FILL : clear ? CLEARANCE : TERRAIN;
    }
}

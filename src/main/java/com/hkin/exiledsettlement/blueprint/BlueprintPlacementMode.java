package com.hkin.exiledsettlement.blueprint;

public enum BlueprintPlacementMode {
    STRUCTURE,
    CLEARANCE,
    OPTIONAL_SUPPORT,
    /** Replaces suitable natural ground directly, without excavating a fluid pit first. */
    FARM_WATER,
    /** Converts suitable natural ground directly to farmland instead of destroying and replacing it. */
    FARM_TILL;

    public boolean isFinalStructure() {
        return this == STRUCTURE || this == FARM_WATER || this == FARM_TILL;
    }
}

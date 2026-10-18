package com.hkin.exiledsettlement.citizen;

import java.util.Locale;

public enum CitizenWorkState {
    IDLE,
    TO_EQUIPMENT,
    TO_ROAD_TOOL,
    TO_ROAD,
    TO_OUTPUT_CONTAINER,
    TO_WAREHOUSE,
    TO_FARM_SUPPLY,
    TO_FARM,
    TO_CONSTRUCTION_SUPPLY,
    TO_BLUEPRINT,
    TO_REPAIR_SUPPLY,
    TO_REPAIR,
    TO_DEMOLITION,
    TO_HERD,
    TO_HUNT,
    TO_CAPTURE,
    TO_TOOL_WORKSHOP,
    TO_FOREST,
    TO_FISH,
    TO_FOOTING_SUPPLY,
    TO_SAWMILL_SUPPLY,
    TO_SAWMILL,
    TO_PUBLIC_ITEM,
    TO_MINE,
    TO_MINE_STORAGE;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "work_state.exiledsettlement." + serializedName();
    }

    public static CitizenWorkState fromSerializedName(String value) {
        for (CitizenWorkState state : values()) {
            if (state.serializedName().equals(value)) {
                return state;
            }
        }
        return IDLE;
    }
}

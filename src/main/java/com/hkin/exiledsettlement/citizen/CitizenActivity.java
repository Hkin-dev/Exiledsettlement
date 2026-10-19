package com.hkin.exiledsettlement.citizen;

import java.util.Locale;

public enum CitizenActivity {
    IDLE,
    FETCHING_EQUIPMENT,
    SELF_DEFENSE,
    RETURNING_TO_SETTLEMENT,
    RETURNING_HOME,
    RESTING_AT_HOME,
    TO_OUTPUT_CONTAINER,
    TO_WAREHOUSE,
    FETCHING_FARM_INPUT,
    FARMING,
    FETCHING_BUILD_MATERIAL,
    BUILDING,
    ROAD_BUILDING,
    FETCHING_REPAIR_MATERIAL,
    REPAIRING,
    DEMOLISHING,
    HERDING,
    HUNTING,
    CAPTURING,
    TOOLMAKING,
    FORESTRY,
    FISHING,
    BLOCKED,
    DEAD,
    FETCHING_SAWMILL_INPUT,
    SAWING,
    COLLECTING_PUBLIC_ITEM,
    FETCHING_TOOLMAKING_INPUT,
    FETCHING_MEAL,
    EATING,
    COOKING,
    WARMING,
    EVACUATING,
    RECOVERING,
    GROWING,
    MINING,
    FETCHING_COOKING_INPUT,
    FETCHING_COOKING_FUEL,
    TO_KITCHEN,
    DELIVERING_MEALS,
    KITCHEN_STANDBY,
    CLEARING_SITE,
    FETCHING_KILN_INPUT, FETCHING_KILN_FUEL, TO_KILN, FIRING_KILN,
    DELIVERING_BUILDING_MATERIALS, KILN_STANDBY,
    FORMING_BUILDING_MATERIALS,
    TEACHING;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "citizen_activity.exiledsettlement." + serializedName();
    }

    public static CitizenActivity fromSerializedName(String value) {
        for (CitizenActivity activity : values()) {
            if (activity.serializedName().equals(value)) {
                return activity;
            }
        }
        return IDLE;
    }
}

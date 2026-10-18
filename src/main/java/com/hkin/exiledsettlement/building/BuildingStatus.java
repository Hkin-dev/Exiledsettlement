package com.hkin.exiledsettlement.building;

import java.util.Locale;

public enum BuildingStatus {
    PENDING_VALIDATION,
    OPERATIONAL,
    MISSING_BED,
    MISSING_DOOR,
    MISSING_CONTAINER,
    MISSING_WATER,
    DEMOLITION_QUEUED,
    CONTROLLER_MISSING,
    REGISTERED_ONLY,
    UNDER_CONSTRUCTION,
    CONSTRUCTION_BLOCKED,
    CANCELLATION_QUEUED,
    ENTRANCE_BLOCKED;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "building_status.exiledsettlement." + serializedName();
    }

    public static BuildingStatus fromSerializedName(String value) {
        for (BuildingStatus status : values()) {
            if (status.serializedName().equals(value)) {
                return status;
            }
        }
        return PENDING_VALIDATION;
    }
}

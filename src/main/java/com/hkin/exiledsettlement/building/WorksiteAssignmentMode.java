package com.hkin.exiledsettlement.building;

/** Controls whether a construction or demolition target accepts automatically scheduled workers. */
public enum WorksiteAssignmentMode {
    AUTO,
    MANUAL;

    public String serializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static WorksiteAssignmentMode fromSerializedName(String value) {
        if (value == null) return AUTO;
        for (WorksiteAssignmentMode mode : values()) {
            if (mode.serializedName().equalsIgnoreCase(value)) return mode;
        }
        return AUTO;
    }
}

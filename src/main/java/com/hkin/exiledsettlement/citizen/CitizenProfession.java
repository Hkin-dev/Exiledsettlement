package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum CitizenProfession {
    FARMER(BuildingType.FARM_FIELD),
    WORKER(null),
    HERDSMAN(BuildingType.PASTURE_AREA),
    HUNTER(BuildingType.HUNTING_LODGE),
    TOOLSMITH(BuildingType.TOOL_WORKSHOP),
    // The single tree-farm profession: cutting and actual seedling replacement.
    LUMBERJACK(BuildingType.TREE_FARM),
    FISHER(BuildingType.FISHING_HUT),
    SAWYER(BuildingType.SAWMILL),
    WAREHOUSE_KEEPER(BuildingType.WAREHOUSE),
    MELON_FARMER(BuildingType.MELON_FIELD),
    TRAVELING_MERCHANT(BuildingType.MARKET),
    MINER(BuildingType.MINE),
    CHEF(BuildingType.TAVERN),
    KILN_WORKER(BuildingType.BRICK_KILN),
    TEACHER(BuildingType.SCHOOL);
    

    @Nullable
    private final BuildingType workplaceType;

    CitizenProfession(@Nullable BuildingType workplaceType) {
        this.workplaceType = workplaceType;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "profession.exiledsettlement." + serializedName();
    }

    @Nullable
    public BuildingType workplaceType() {
        return workplaceType;
    }

    public boolean specialist() {
        return this != WORKER;
    }

    public boolean acceptsWorkplace(BuildingType type) {
        return workplaceType == type || this == CHEF && type == BuildingType.RESTAURANT;
    }

    @Nullable
    public static CitizenProfession forWorkplace(BuildingType type) {
        for (CitizenProfession profession : values()) {
            if (profession.acceptsWorkplace(type)) return profession;
        }
        return null;
    }

    public static CitizenProfession fromSerializedName(String value) {
        for (CitizenProfession profession : values()) {
            if (profession.serializedName().equals(value)) {
                return profession;
            }
        }
        throw new IllegalArgumentException("Unknown citizen profession: " + value);
    }
}

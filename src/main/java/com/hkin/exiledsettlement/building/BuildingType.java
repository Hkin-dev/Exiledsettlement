package com.hkin.exiledsettlement.building;

import java.util.Locale;

public enum BuildingType {
    WAGON,
    HOUSE,
    HOUSE_2,
    HOUSE_3,
    ADVANCED_HOUSE_1,
    ADVANCED_HOUSE_2,
    ADVANCED_HOUSE_3,
    WAREHOUSE,
    FARM_FIELD,
    PASTURE_AREA,
    HUNTING_LODGE,
    TOOL_WORKSHOP,
    FORESTER_LODGE,
    TREE_FARM,
    TOWN_HALL,
    FISHING_HUT,
    SAWMILL,
    SPRUCE_HOME,
    GALLERY_HOME,
    COURTYARD_HOME,
    SCHOOL,
    STARTER_CAMP,
    MARKET,
    TAVERN,
    RESTAURANT,
    MELON_FIELD,
    LEISURE_GARDEN,
    LAMP_SMALL,
    LAMP_ARM,
    LAMP_DOUBLE,
    BENCH,
    FLOWERBED,
    SIGNPOST,
    WELL,
    WOODSHED,
    MINE,
    CHURCH,
    BRICK_KILN,
    APARTMENT;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "building.exiledsettlement." + serializedName();
    }

    public boolean isHouse() {
        return this == HOUSE || this == HOUSE_2 || this == HOUSE_3
                || this == ADVANCED_HOUSE_1 || this == ADVANCED_HOUSE_2 || this == ADVANCED_HOUSE_3
                || this == SPRUCE_HOME || this == GALLERY_HOME || this == COURTYARD_HOME || this == STARTER_CAMP || this == APARTMENT;
                
    }

    public int housingLimit() {
        return switch (this) {
            case HOUSE, SPRUCE_HOME -> 2;
            case GALLERY_HOME -> 3;
            case COURTYARD_HOME -> 4;
            case HOUSE_2 -> 3;
            case HOUSE_3 -> 4;
            case ADVANCED_HOUSE_1 -> 2;
            case ADVANCED_HOUSE_2 -> 3;
            case ADVANCED_HOUSE_3 -> 4;
            case STARTER_CAMP -> 7;
            case APARTMENT -> 20;
            default -> 0;
        };
    }

    public BuildingCategory category() {
        if (isHouse() || this == STARTER_CAMP) return BuildingCategory.HOUSING;
        return switch (this) {
            case WAREHOUSE, MARKET, WAGON -> BuildingCategory.STORAGE;
            case FARM_FIELD, MELON_FIELD, PASTURE_AREA, HUNTING_LODGE, TOOL_WORKSHOP,
                    FORESTER_LODGE, TREE_FARM, FISHING_HUT, SAWMILL, MINE, BRICK_KILN -> BuildingCategory.PRODUCTION;
            case TAVERN, RESTAURANT -> BuildingCategory.COMPOUND;
            case LEISURE_GARDEN, LAMP_SMALL, LAMP_ARM, LAMP_DOUBLE, BENCH, FLOWERBED,
                    SIGNPOST, WELL, WOODSHED -> BuildingCategory.RECREATION;
            case TOWN_HALL, SCHOOL -> BuildingCategory.CIVIC;
            default -> BuildingCategory.SERVICE;
        };
    }

    public boolean isStorage() {
        return this == WAREHOUSE || this == MARKET || this == WAGON;
    }

    /**
     * Buildings whose footprint is selected by dragging two corners instead of using a fixed template.
     */
    public boolean isDynamicArea() {
        return this == FARM_FIELD || this == MELON_FIELD || this == PASTURE_AREA || this == TREE_FARM;
    }

    public int minimumAreaSize() {
        return switch (this) {
            case FARM_FIELD -> 4;
            case MELON_FIELD -> 8;
            case PASTURE_AREA -> 5;
            case TREE_FARM -> 9;
            default -> 0;
        };
    }

    public static BuildingType fromSerializedName(String value) {
        if ("farmstead".equals(value)) return FARM_FIELD;
        if ("pasture".equals(value)) return PASTURE_AREA;
        for (BuildingType type : values()) {
            if (type.serializedName().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown building type: " + value);
    }
}

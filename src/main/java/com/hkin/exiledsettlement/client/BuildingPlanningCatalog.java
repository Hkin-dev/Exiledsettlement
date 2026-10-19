package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.building.BuildingType;
import java.util.*;

/** Presentation groups retain the original server catalog indices. */
public final class BuildingPlanningCatalog {
    public static final int CATEGORY_COUNT = 5;
    public static final int PAGE_SIZE = 6;
    private BuildingPlanningCatalog() { }
    public static int category(BuildingType type) {
        if (type.isHouse()) return 2;
        if (type.isStorage()) return 1;
        return switch (type) {
            case FARM_FIELD, MELON_FIELD, PASTURE_AREA, HUNTING_LODGE, FISHING_HUT,
                    TREE_FARM, FORESTER_LODGE, MINE, TOOL_WORKSHOP, SAWMILL, BRICK_KILN -> 0;
            case TOWN_HALL, SCHOOL, CHURCH, TAVERN, RESTAURANT, WELL, WOODSHED -> 1;
            default -> 4;
        };
    }
    public static List<Integer> indices(BuildingType[] choices, int category) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < choices.length; i++) if (category(choices[i]) == category) result.add(i);
        return List.copyOf(result);
    }
    public static int pages(int count) { return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE); }
}

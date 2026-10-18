package com.hkin.exiledsettlement.settlement;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum SettlementLevel {
    CAMP(6, 3, 32, 2),
    HAMLET(12, 6, 40, 3),
    VILLAGE(24, 10, 48, 4),
    TOWN(40, 14, 56, 5),
    CITY(64, Integer.MAX_VALUE, 64, 6);

    private final int populationCap;
    private final int buildingsForNextLevel;
    private final int migrantFoodCost;
    private final int boundaryRadiusChunks;

    SettlementLevel(int populationCap, int buildingsForNextLevel, int migrantFoodCost, int boundaryRadiusChunks) {
        this.populationCap = populationCap;
        this.buildingsForNextLevel = buildingsForNextLevel;
        this.migrantFoodCost = migrantFoodCost;
        this.boundaryRadiusChunks = boundaryRadiusChunks;
    }

    public int populationCap() {
        return populationCap;
    }

    public int buildingsForNextLevel() {
        return buildingsForNextLevel;
    }

    public int migrantFoodCost() {
        return migrantFoodCost;
    }

    public int boundaryRadiusChunks() {
        return boundaryRadiusChunks;
    }

    public int chunkLoadingLimit() {
        return switch (this) {
            case CAMP -> 9;
            case HAMLET -> 16;
            case VILLAGE -> 25;
            case TOWN -> 36;
            case CITY -> 49;
        };
    }

    @Nullable
    public SettlementLevel next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "settlement_level.exiledsettlement." + serializedName();
    }

    public static SettlementLevel fromSerializedName(String value) {
        for (SettlementLevel level : values()) {
            if (level.serializedName().equals(value)) return level;
        }
        return CAMP;
    }
}

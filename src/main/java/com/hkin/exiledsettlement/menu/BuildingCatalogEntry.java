package com.hkin.exiledsettlement.menu;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingType;

public record BuildingCatalogEntry(BuildingType type, int templateVersion, String translationKey) {
    public BuildingCatalogEntry {
        if (templateVersion < 1) throw new IllegalArgumentException("templateVersion must be positive");
    }

    public static BuildingCatalogEntry current(BuildingType type) {
        return new BuildingCatalogEntry(type, BlueprintTemplates.currentTemplateVersion(type), type.translationKey());
    }
}

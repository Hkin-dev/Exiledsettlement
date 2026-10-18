package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.Map;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedesignedBuildingGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void sevenRedesignsOperateInFourRotationsAndKeepLegacyVersions(GameTestHelper helper) {
        BuildingType[] types = {BuildingType.ADVANCED_HOUSE_1, BuildingType.ADVANCED_HOUSE_2,
                BuildingType.ADVANCED_HOUSE_3, BuildingType.HUNTING_LODGE, BuildingType.TOOL_WORKSHOP,
                BuildingType.SAWMILL, BuildingType.TOWN_HALL};
        int[] counts = {434, 690, 1078, 378, 531, 597, 1107};
        int[] redesignVersions = {3, 4, 3, 2, 2, 2, 4};
        int index = 0;
        for (BuildingType type : types) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockPos anchor = helper.absolutePos(new BlockPos(8, 12, 8)).offset(16384 + index * 48, 0, 16384 + facing.ordinal() * 48);
                var plan = BlueprintRecord.create(type, anchor, anchor, facing, UUID.randomUUID(), false,
                        redesignVersions[index], Map.of());
                var saved = plan.save();
                helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == redesignVersions[index],
                        "Approved redesign plan reload");
                int oldVersion = type == BuildingType.TOWN_HALL ? 3 : type == BuildingType.ADVANCED_HOUSE_2 ? 2 : 1;
                for (int v = 12; v <= 14; v++) {
                    saved.putInt("StructureVersion", v);
                    saved.remove("TemplateVersion");
                    helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == oldVersion, "Historical plan changed: " + type);
                }
                var template = BlueprintTemplates.create(plan);
                long actualCount = template.placements().stream().filter(p -> p.mode().isFinalStructure()).count();
                helper.assertTrue(actualCount == counts[index], "Snapshot count " + type + ": " + actualCount
                        + " expected " + counts[index] + " facing " + facing);
                for (var p : template.placements()) helper.getLevel().setBlock(p.position(), p.state(), 2);
                var settlement = new SettlementRecord(UUID.randomUUID(), "Redesign", anchor, plan.ownerId());
                var record = settlement.addBuilding(type, anchor, facing, template.minCorner(), template.maxCorner(), template.transitionMargin(), plan.templateVersion());
                BuildingFunctionService.validateSingle(helper.getLevel(), new SettlementSavedData(), settlement, record);
                helper.assertTrue(record.operational(), "Not operational " + type + " " + facing);
                if (type.isHouse()) helper.assertTrue(record.capacity() == type.housingLimit(), "Missing beds " + type);
                if (type == BuildingType.TOWN_HALL) {
                    helper.assertTrue(helper.getLevel().getBlockState(anchor).is(ModBlocks.TOWN_HALL_MARKER.get()), "Management desk not registered anchor");
                    helper.assertTrue(template.placementAt(anchor).material() == null, "Management marker wrongly requires special stock");
                }
                var restored = BuildingRecord.load(record.save());
                helper.assertTrue(BlueprintTemplates.createForBuilding(restored).placements().equals(template.placements()), "Completed snapshot changed on reload");
                var old = BuildingRecord.create(type, anchor, facing, template.minCorner(), template.maxCorner(), 2, oldVersion);
                helper.assertTrue(BuildingRecord.load(old.save()).templateVersion() == oldVersion, "Old completed record changed");
            }
            index++;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 300)
    public static void civicProductionRound2KeepsLegacySnapshotsOutsidePlayerCatalog(GameTestHelper helper) {
        BuildingType[] types = {BuildingType.TOWN_HALL, BuildingType.SAWMILL,
                BuildingType.SCHOOL, BuildingType.TOOL_WORKSHOP};
        int[] oldVersions = {4, 2, 2, 2};
        int[] newVersions = {5, 3, 3, 3};
        int[] oldCounts = {1107, 597, 622, 531};
        int[] newCounts = {2604, 1278, 2141, 1776};
        int[][] newSizes = {{31, 25, 22}, {27, 19, 14}, {27, 21, 16}, {25, 21, 16}};

        for (int index = 0; index < types.length; index++) {
            BuildingType type = types[index];
            int oldVersion = oldVersions[index];
            int newVersion = newVersions[index];
            int currentVersion = BlueprintTemplates.currentTemplateVersion(type);
            helper.assertTrue(currentVersion >= newVersion,
                    "Current version predates the preserved round-two snapshot for " + type);
            long catalogVariants = java.util.Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG)
                    .filter(entry -> entry.type() == type)
                    .count();
            helper.assertTrue(catalogVariants == 1, "Catalog must expose only the current version for " + type);
            helper.assertTrue(java.util.Arrays.stream(SettlementCoreMenu.BUILDING_CATALOG)
                    .filter(entry -> entry.type() == type)
                    .allMatch(entry -> entry.templateVersion() == currentVersion),
                    "Legacy candidate still appears in player catalog for " + type);

            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockPos anchor = helper.absolutePos(new BlockPos(
                        48000 + index * 64, 12, 48000 + facing.ordinal() * 64));
                var oldTemplate = BlueprintTemplates.create(type, anchor, facing, oldVersion);
                var newTemplate = BlueprintTemplates.create(type, anchor, facing, newVersion);
                long oldCount = oldTemplate.placements().stream().filter(p -> p.mode().isFinalStructure()).count();
                long newCount = newTemplate.placements().stream().filter(p -> p.mode().isFinalStructure()).count();
                helper.assertTrue(oldCount == oldCounts[index], "Old snapshot changed for " + type);
                helper.assertTrue(newCount == newCounts[index], "New snapshot count changed for " + type);
                helper.assertTrue(newTemplate.width() == newSizes[index][0]
                                && newTemplate.depth() == newSizes[index][1]
                                && newTemplate.height() == newSizes[index][2],
                        "New dimensions changed for " + type + " " + facing);
                helper.assertTrue(!newTemplate.placements().equals(oldTemplate.placements()),
                        "Old and new snapshots unexpectedly match for " + type);
                helper.assertTrue(!newTemplate.entrances().isEmpty(), "New entrances missing for " + type);

                var oldPlan = BlueprintRecord.create(type, anchor, anchor, facing, UUID.randomUUID(), false,
                        oldVersion, Map.of());
                var newPlan = BlueprintRecord.create(type, anchor, anchor, facing, UUID.randomUUID(), false,
                        newVersion, Map.of());
                helper.assertTrue(BlueprintRecord.load(oldPlan.save()).templateVersion() == oldVersion,
                        "Selected old version was not persisted for " + type);
                helper.assertTrue(BlueprintRecord.load(newPlan.save()).templateVersion() == newVersion,
                        "Selected new version was not persisted for " + type);

                var legacyTag = newPlan.save();
                legacyTag.putInt("StructureVersion", 17);
                legacyTag.remove("TemplateVersion");
                helper.assertTrue(BlueprintRecord.load(legacyTag).templateVersion() == oldVersion,
                        "0.15.6 pending plan silently upgraded for " + type);
                if (type == BuildingType.TOWN_HALL) {
                    helper.assertTrue(newTemplate.placementAt(anchor).state().is(ModBlocks.TOWN_HALL_MARKER.get()),
                            "Round-two town hall management marker is not the anchor");
                    helper.assertTrue(newTemplate.placementAt(anchor).material() == null,
                            "Round-two town hall marker requires unavailable material");
                }
                if (facing == Direction.NORTH) {
                    for (var placement : newTemplate.placements()) {
                        if (placement.mode().isFinalStructure()) {
                            helper.getLevel().setBlock(placement.position(), placement.state(), 2);
                        }
                    }
                    var settlement = new SettlementRecord(
                            UUID.randomUUID(), "Round Two", anchor, newPlan.ownerId());
                    var building = settlement.addBuilding(type, anchor, facing,
                            newTemplate.minCorner(), newTemplate.maxCorner(),
                            newTemplate.transitionMargin(), newVersion);
                    BuildingFunctionService.validateSingle(
                            helper.getLevel(), new SettlementSavedData(), settlement, building);
                    if (type == BuildingType.SCHOOL) {
                        helper.assertTrue(!building.operational()
                                        && building.status() == BuildingStatus.REGISTERED_ONLY,
                                "Appearance-only school unexpectedly became a workplace");
                    } else {
                        helper.assertTrue(building.operational(),
                                "Round-two workplace is not operational for " + type);
                    }
                }
            }
        }
        helper.succeed();
    }
}

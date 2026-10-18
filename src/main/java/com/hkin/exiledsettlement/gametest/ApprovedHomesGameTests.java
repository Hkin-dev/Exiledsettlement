package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApprovedHomesGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void approvedHomesAndLodgesRotateOperateAndPreserveOldPlans(GameTestHelper helper) {
        BuildingType[] types = {BuildingType.SPRUCE_HOME, BuildingType.GALLERY_HOME,
                BuildingType.COURTYARD_HOME, BuildingType.FORESTER_LODGE, BuildingType.FISHING_HUT};
        int[] counts = {273, 370, 437, 350, 300};
        for (int index = 0; index < types.length; index++) {
            BuildingType type = types[index];
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockPos anchor = helper.absolutePos(new BlockPos(8, 12, 8))
                        .offset(20480 + index * 48, 0, 20480 + facing.ordinal() * 48);
                var plan = BlueprintRecord.create(type, anchor, anchor, facing, UUID.randomUUID(), false);
                var template = BlueprintTemplates.create(plan);
                helper.assertTrue(template.placements().stream().filter(p -> p.mode().isFinalStructure()).count()
                        == counts[index], "Unexpected snapshot count: " + type);
                helper.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(plan.save())).placements()
                        .equals(template.placements()), "Blueprint changed after reload");
                for (var p : template.placements()) helper.getLevel().setBlock(p.position(), p.state(), 2);
                // Fishing requires real nearby water in addition to its container.
                if (type == BuildingType.FISHING_HUT) {
                    for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                        helper.getLevel().setBlock(anchor.offset(x, -1, z - 4), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
                var settlement = new SettlementRecord(UUID.randomUUID(), "Approved homes", anchor, plan.ownerId());
                var record = settlement.addBuilding(type, anchor, facing, template.minCorner(), template.maxCorner(),
                        template.transitionMargin(), plan.templateVersion());
                BuildingFunctionService.validateSingle(helper.getLevel(), new SettlementSavedData(), settlement, record);
                helper.assertTrue(record.operational(), "Not operational: " + type + " " + facing);
                if (type.isHouse()) helper.assertTrue(record.capacity() == type.housingLimit(), "Housing capacity");
                helper.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(record.save())).placements()
                        .equals(template.placements()), "Completed snapshot changed");
                if (type == BuildingType.FORESTER_LODGE || type == BuildingType.FISHING_HUT) {
                    var tag = plan.save();
                    tag.putInt("StructureVersion", 15);
                    tag.remove("TemplateVersion");
                    var legacy = BlueprintRecord.load(tag);
                    helper.assertTrue(legacy.templateVersion() == 1, "Old pending lodge silently upgraded");
                    var oldRecord = BuildingRecord.create(type, anchor, facing, template.minCorner(), template.maxCorner(), 2, 1);
                    helper.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(oldRecord.save())).placements()
                            .equals(BlueprintTemplates.create(type, anchor, facing, 1).placements()), "Old completed lodge changed");
                }
            }
        }
        helper.assertTrue(SettlementCoreMenu.ROAD_LEVEL_1_BUTTON >= SettlementCoreMenu.BUILDING_CHOICES.length,
                "Road buttons overlap building choices");
        helper.succeed();
    }
}

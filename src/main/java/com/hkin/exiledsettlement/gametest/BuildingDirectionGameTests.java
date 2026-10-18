package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingDirectionGameTests {
    private BuildingDirectionGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void townHallStairsRiseInwardInAllFourRotations(GameTestHelper helper) {
        BlockPos anchor = new BlockPos(100, 100, 100);
        var pending = BlueprintRecord.create(BuildingType.TOWN_HALL, anchor, anchor,
                Direction.NORTH, java.util.UUID.randomUUID(), false);
        var saved = pending.save();
        saved.putInt("StructureVersion", 11);
        saved.remove("TemplateVersion");
        helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == 2,
                "An existing pending plan changed its target states");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var old = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, facing, 2);
            var revised = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, facing, 3);
            helper.assertTrue(old.placements().size() == revised.placements().size(), "Geometry changed");
            int changes = 0;
            for (var placement : revised.placements()) {
                var previous = old.placements().stream()
                        .filter(p -> p.position().equals(placement.position())).findFirst().orElseThrow();
                if (!(placement.state().getBlock() instanceof StairBlock)) {
                    helper.assertTrue(placement.state().equals(previous.state()), "Non-stair changed");
                    continue;
                }
                BlockPos offset = placement.position().subtract(anchor);
                Direction right = facing.getClockWise();
                int x = offset.getX() * right.getStepX() + offset.getZ() * right.getStepZ() + 7;
                int y = offset.getY() + 1;
                Direction expected = y == 0 ? facing : x < 6 ? right : right.getOpposite();
                helper.assertTrue(placement.state().getValue(StairBlock.FACING) == expected,
                        "Stair points away from its rise at " + placement.position() + " for " + facing);
                helper.assertTrue(previous.state().getValue(StairBlock.FACING) == expected.getOpposite(),
                        "Legacy v2 was not retained");
                changes++;
            }
            helper.assertTrue(changes == 152, "Expected 140 main-roof + 10 lean-to + 2 entrance stairs");
        }
        helper.succeed();
    }
}

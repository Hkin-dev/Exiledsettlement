package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionClosureGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void mutuallySealedGapsReportCycleWithoutChangingWorld(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(2, 4, 2));
        BlockPos second = first.east();
        for (BlockPos p : BlockPos.betweenClosed(first.offset(-1, -1, -1), second.offset(1, 1, 1))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(first, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(second, Blocks.AIR.defaultBlockState(), 2);
        var a = new BlueprintPlacement(first, Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var b = new BlueprintPlacement(second, Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var template = new BuildingBlueprintTemplate(BuildingType.HOUSE, first, Direction.NORTH,
                2, 1, 1, first, second, 1, List.of(a, b));
        var result = ConstructionDependencies.resolve(level, template, a);
        helper.assertTrue(result.cycle() && result.target() == null, "Two sealed gaps restarted each other forever");
        helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                "Cycle handling filled blocks without a worker");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void upperNodeWaitsForAllLowerStepsAndRechecksWorld(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 4, 2));
        var lowerA = new BlueprintPlacement(base, Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var lowerB = new BlueprintPlacement(base.east(), Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var upperA = new BlueprintPlacement(base.above(), Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var upperB = new BlueprintPlacement(base.east().above(), Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
        var template = new BuildingBlueprintTemplate(BuildingType.HOUSE, base, Direction.NORTH,
                2, 1, 2, base, base.offset(1, 1, 0), 1, List.of(lowerA, lowerB, upperA, upperB));
        for (var p : template.placements()) level.setBlock(p.position(), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(base, lowerA.state(), 2);
        helper.assertTrue(base.east().equals(ConstructionDependencies.firstUnfinished(level, template, upperA)),
                "Upper node ignored an unfinished prerequisite of another step in its node");
        level.setBlock(base.east(), lowerB.state(), 2);
        helper.assertTrue(ConstructionDependencies.firstUnfinished(level, template, upperA) == null,
                "Finished lower nodes did not unlock the upper node");
        level.setBlock(base, Blocks.AIR.defaultBlockState(), 2);
        helper.assertTrue(base.equals(ConstructionDependencies.firstUnfinished(level, template, upperB)),
                "Player removal did not invalidate previously completed dependency");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void lastFaceWaitsForHiddenBlockInAllSixDirections(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hole = helper.absolutePos(new BlockPos(2, 3, 2));
        for (Direction opening : Direction.values()) {
            for (Direction face : Direction.values()) level.setBlock(hole.relative(face),
                    face == opening ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(hole, Blocks.AIR.defaultBlockState(), 2);
            var inner = new BlueprintPlacement(hole, Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
            var cap = new BlueprintPlacement(hole.relative(opening), Blocks.STONE.defaultBlockState(), Blocks.STONE.asItem());
            var template = new BuildingBlueprintTemplate(BuildingType.HOUSE, hole, Direction.NORTH,
                    3, 3, 3, hole.offset(-1, -1, -1), hole.offset(1, 1, 1), 1, List.of(inner, cap));
            helper.assertTrue(hole.equals(ConstructionClosureGuard.prerequisite(level, template, cap)),
                    "Last face was not protected: " + opening);
            helper.assertTrue(level.getBlockState(cap.position()).isAir(), "Planning changed the world");
            level.setBlock(hole, Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(ConstructionClosureGuard.prerequisite(level, template, cap) == null,
                    "Completed interior incorrectly prevents closing: " + opening);
            level.setBlock(hole, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(hole.relative(opening.getOpposite()), Blocks.AIR.defaultBlockState(), 2);
            helper.assertTrue(ConstructionClosureGuard.prerequisite(level, template, cap) == null,
                    "Another open face must not be treated as fully enclosed");
        }
        helper.succeed();
    }
}

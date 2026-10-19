package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.settlement.*;
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
public final class SchoolAppearanceGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", timeoutTicks=200)
    public static void schoolOffersTeacherJobAndPreservesItsSnapshot(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos anchor = helper.absolutePos(new BlockPos(36000 + facing.ordinal() * 48, 10, 36000));
            var plan = BlueprintRecord.create(BuildingType.SCHOOL, anchor, anchor, facing, UUID.randomUUID(), false,
                    2, Map.of());
            var template = BlueprintTemplates.create(plan);
            helper.assertTrue(template.placements().stream().filter(p -> p.mode().isFinalStructure()).count() == 622, "School block count");
            for (var p : template.placements()) helper.getLevel().setBlock(p.position(), p.state(), 2);
            var settlement = new SettlementRecord(UUID.randomUUID(), "School", anchor, plan.ownerId());
            var building = settlement.addBuilding(BuildingType.SCHOOL, anchor, facing, template.minCorner(), template.maxCorner(), 2, plan.templateVersion());
            BuildingFunctionService.validateSingle(helper.getLevel(), new SettlementSavedData(), settlement, building);
            helper.assertTrue(building.capacity() == 1 && building.operational(), "School lacks teacher capacity");
            helper.assertTrue(BlueprintTemplates.createForBuilding(BuildingRecord.load(building.save())).placements()
                    .equals(template.placements()), "School changed after load");
            var old = plan.save(); old.putInt("StructureVersion",16); old.remove("TemplateVersion");
            helper.assertTrue(BlueprintRecord.load(old).templateVersion()==1,"Old school plan changed geometry");
            helper.assertTrue(settlement.housingCapacity() == 0, "School counted as housing");
        }
        helper.succeed();
    }
}

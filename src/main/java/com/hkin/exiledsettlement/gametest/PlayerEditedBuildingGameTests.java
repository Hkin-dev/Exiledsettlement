package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_distinct")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement", bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class PlayerEditedBuildingGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        if ("distinct_eight".equals(System.getProperty("exiledsettlement.testSuite"))) event.register(PlayerEditedBuildingGameTests.class);
    }
    private static final BuildingType[] TYPES = {BuildingType.HUNTING_LODGE, BuildingType.TOOL_WORKSHOP,
            BuildingType.TOWN_HALL, BuildingType.FISHING_HUT, BuildingType.SAWMILL, BuildingType.SCHOOL,
            BuildingType.MARKET, BuildingType.TAVERN};
    private static final int[] COUNTS = {951,2188,4104,535,1064,1951,1709,2344};
    private static final int[][] SIZES = {{13,11,17},{19,13,21},{25,16,28},{9,9,17},{11,10,25},{19,14,17},{23,10,19},{21,14,23}};

    @GameTest(templateNamespace="exiledsettlement_distinct",template="empty",timeoutTicks=600)
    public static void playerEditedModelsLoadOperateAndPersistInFourRotations(GameTestHelper h) {
        var level = h.getLevel();
        for (int i=0;i<TYPES.length;i++) for (Direction facing : Direction.Plane.HORIZONTAL) {
            var type = TYPES[i]; int version = PlayerDesignedTemplates.playerEditedVersion(type);
            BlockPos anchor = h.absolutePos(new BlockPos(61000+i*48,12,61000+facing.ordinal()*48));
            var template = BlueprintTemplates.create(type,anchor,facing);
            h.assertTrue(BlueprintTemplates.currentTemplateVersion(type)==version,"Wrong current version "+type);
            h.assertTrue(template.width()==SIZES[i][0] && template.height()==SIZES[i][1] && template.depth()==SIZES[i][2],"Approved size changed "+type);
            h.assertTrue(template.placements().stream().filter(p->p.mode().isFinalStructure()).count()==COUNTS[i],"Approved blocks changed "+type);
            h.assertTrue(template.transitionMargin()==0,"Unapproved outside modules added "+type);
            for(var p:template.placements()) {
                level.setBlock(p.position(),p.state(),2);
                if(p.mode().isFinalStructure() && !p.state().is(ModBlocks.TOWN_HALL_MARKER.get())
                        && (!BlueprintPlacementGroups.isPaired(p.state()) || BlueprintPlacementGroups.isPrimary(p.state())))
                    h.assertTrue(p.material()!=null,"Unpaid block "+type+" "+p.state());
            }
            for(var entrance:template.entrances()) {
                var floor=entrance.position().below();
                h.assertTrue(level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP),"Entrance lacks floor "+type+" "+entrance);
                var state=level.getBlockState(entrance.position());
                h.assertTrue(state.getBlock() instanceof DoorBlock || state.getCollisionShape(level,entrance.position()).isEmpty(),"Entrance obstructed "+type);
                // Facing selects the hinge plane, not which side counts as outside;
                // a valid inward-opening door can face opposite the exit vector.
                if(state.getBlock() instanceof DoorBlock) h.assertTrue(state.getValue(DoorBlock.FACING).getAxis()==entrance.outward().getAxis(),"Door crosses exit direction "+type);
            }
            if(type==BuildingType.FISHING_HUT) level.setBlock(anchor.west(9).below(),Blocks.WATER.defaultBlockState(),2);
            var s=new SettlementRecord(UUID.randomUUID(),"Distinct rotation",anchor,UUID.randomUUID());
            var b=s.addBuilding(type,anchor,facing,template.minCorner(),template.maxCorner(),0,version);
            BuildingFunctionService.validateSingle(level,new SettlementSavedData(),s,b);
            h.assertTrue(type==BuildingType.SCHOOL ? !b.operational() && b.capacity()==0 : b.operational(),"Function missing "+type+" "+b.status());
            if(type==BuildingType.TOWN_HALL) h.assertTrue(level.getBlockState(anchor).is(ModBlocks.TOWN_HALL_MARKER.get()) && template.placementAt(anchor).material()==null,"Management anchor missing");
            var restored=BuildingRecord.load(b.save());
            h.assertTrue(BlueprintTemplates.createForBuilding(restored).placements().equals(template.placements()),"Building reload changed geometry "+type);
            var plan=BlueprintRecord.create(type,anchor,anchor,facing,UUID.randomUUID(),false,version,Map.of());
            h.assertTrue(BlueprintRecord.load(plan.save()).templateVersion()==version,"Plan version lost "+type);
            var legacy=plan.save(); legacy.putInt("StructureVersion",18); legacy.remove("TemplateVersion");
            h.assertTrue(BlueprintRecord.load(legacy).templateVersion()==LegacyTemplateFingerprintGameTests.VERSIONS.get(type),"Old pending plan upgraded "+type);
        }
        h.succeed();
    }

}

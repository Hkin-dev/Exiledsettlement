package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionAreaAndScheduleGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void personalSchedulesAreStableAndFarmersWorkLonger(GameTestHelper helper) {
        var farmer = new com.hkin.exiledsettlement.citizen.CitizenRecord(new java.util.UUID(0, 991), "Farmer", BlockPos.ZERO);
        farmer.assignProfession(com.hkin.exiledsettlement.citizen.CitizenProfession.MELON_FARMER, java.util.UUID.randomUUID());
        int offset = CitizenWorkService.scheduleOffset(farmer);
        helper.assertTrue(CitizenWorkService.schedulePhase(offset, farmer) == CitizenWorkService.SchedulePhase.WORK
                        && CitizenWorkService.schedulePhase(13500 + offset, farmer) == CitizenWorkService.SchedulePhase.WORK
                        && CitizenWorkService.schedulePhase(14000 + offset, farmer) == CitizenWorkService.SchedulePhase.SLEEP,
                "Farmer's 06:00 to 20:00 personal window was not respected");
        var loaded = com.hkin.exiledsettlement.citizen.CitizenRecord.load(farmer.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.assertTrue(CitizenWorkService.scheduleOffset(loaded) == offset, "Reload changed the resident's clock");
        java.util.Set<Integer> offsets = new java.util.HashSet<>();
        for (int i = 0; i < 24; i++) offsets.add(CitizenWorkService.scheduleOffset(
                new com.hkin.exiledsettlement.citizen.CitizenRecord(new java.util.UUID(1, i), "Resident", BlockPos.ZERO)));
        helper.assertTrue(offsets.size() > 1 && offsets.stream().allMatch(n -> Math.abs(n) <= 1000),
                "Residents share one clock or offset exceeds one hour");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void extraTerritorySnapshotAndUnionKeepNegativeCoordinates(GameTestHelper helper) {
        net.minecraft.world.level.ChunkPos core = new net.minecraft.world.level.ChunkPos(-4, -3);
        long extra = net.minecraft.world.level.ChunkPos.asLong(-2, -3);
        var chunks = com.hkin.exiledsettlement.client.TerritoryOutline.chunks(core, 1, java.util.List.of(extra));
        var edges = com.hkin.exiledsettlement.client.TerritoryOutline.edges(chunks);
        helper.assertTrue(chunks.size() == 10 && chunks.contains(extra) && edges.size() == 14,
                "Territory union lost the extra claim or drew an internal seam");
        var bounds = com.hkin.exiledsettlement.client.TerritoryOutline.bounds(chunks);
        helper.assertTrue(bounds.maxX() == -16 && bounds.minX() == -80, "Claim did not enlarge camera bounds");
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var payload = new com.hkin.exiledsettlement.network.OverviewSnapshotPayload(new BlockPos(-60, 70, -40), java.util.List.of(extra), java.util.List.of());
            com.hkin.exiledsettlement.network.OverviewSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = com.hkin.exiledsettlement.network.OverviewSnapshotPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(payload.equals(decoded) && buffer.readableBytes() == 0, "Territory snapshot changed claims or core identity");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void treeFarmFenceIsVersionedAndHasWorkingEntrances(GameTestHelper helper) {
        BlockPos first = new BlockPos(0, 70, 0), last = new BlockPos(16, 70, 16);
        var old = BlueprintTemplates.createArea(BuildingType.TREE_FARM, first, last, Direction.NORTH, 1);
        var current = BlueprintTemplates.createArea(BuildingType.TREE_FARM, first, last, Direction.NORTH, 2);
        helper.assertTrue(old.placements().size() == 4 && old.entrances().isEmpty(), "Legacy tree farm was silently changed");
        helper.assertTrue(current.placements().size() == 64 && current.entrances().size() == 4
                        && current.placements().stream().filter(p -> p.state().getBlock() instanceof FenceGateBlock).count() == 4,
                "New tree farm does not form a complete fence with four entrances");
        helper.succeed();
    }
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void fieldsAreSymmetricAndFollowAgriculturalOrder(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(50000, 4, 50000));
        BlockPos second = first.offset(20, 0, 16);
        for (BuildingType type : new BuildingType[]{BuildingType.FARM_FIELD, BuildingType.MELON_FIELD}) {
            var template = BlueprintTemplates.createArea(type, first, second, Direction.NORTH);
            helper.assertTrue(template.placements().stream().anyMatch(p -> p.state().is(Blocks.GRAVEL)),
                    type + " has no internal roads");
            assertMirrorBlocks(helper, template.placements(), first, second);
            int fence = firstIndex(template.placements(), p -> p.state().getBlock() instanceof FenceBlock
                    || p.state().getBlock() instanceof FenceGateBlock);
            int road = firstIndex(template.placements(), p -> p.state().is(Blocks.GRAVEL));
            int water = firstIndex(template.placements(), p -> p.state().hasProperty(BlockStateProperties.WATERLOGGED)
                    && p.state().getValue(BlockStateProperties.WATERLOGGED));
            int farmland = firstIndex(template.placements(), p -> p.state().is(Blocks.FARMLAND));
            int lastFence = lastIndex(template.placements(), p -> p.state().getBlock() instanceof FenceBlock
                    || p.state().getBlock() instanceof FenceGateBlock);
            int lastRoad = lastIndex(template.placements(), p -> p.state().is(Blocks.GRAVEL));
            int lastWater = lastIndex(template.placements(), p -> p.state().hasProperty(BlockStateProperties.WATERLOGGED)
                    && p.state().getValue(BlockStateProperties.WATERLOGGED));
            helper.assertTrue(fence >= 0 && road > lastFence && water > lastRoad && farmland > lastWater,
                    type + " order is not fence -> road -> water -> farmland");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void pastureKeepsRoadsAndCapturePointWithoutMandatoryDecorations(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(52000, 4, 52000));
        BlockPos second = first.offset(16, 0, 16);
        var template = BlueprintTemplates.createArea(BuildingType.PASTURE_AREA, first, second, Direction.NORTH);
        assertMirrorBlocks(helper, template.placements(), first, second);
        helper.assertTrue(template.placements().stream().anyMatch(p -> p.state().is(Blocks.GRAVEL)),
                "Pasture has no internal road");
        helper.assertTrue(template.placements().stream().noneMatch(p -> p.state().is(Blocks.COMPOSTER)
                        || p.state().is(Blocks.HAY_BLOCK)), "Pasture still requires optional decorations");
        helper.assertTrue(template.placements().stream().anyMatch(p -> p.state().is(Blocks.COARSE_DIRT)),
                "Pasture ground is still entirely grass");
        for (int x = first.getX() - 1; x <= second.getX() + 1; x++) {
            for (int z = first.getZ() - 1; z <= second.getZ() + 1; z++) {
                helper.getLevel().setBlock(new BlockPos(x, first.getY() - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
        BuildingRecord pasture = BuildingRecord.create(BuildingType.PASTURE_AREA, template.anchor(), Direction.NORTH,
                template.minCorner(), template.maxCorner(), template.transitionMargin(), 3);
        BlockPos destination = CitizenWorkService.pastureCaptureDestination(helper.getLevel(), pasture,
                first.offset(8, 0, -8));
        helper.assertTrue(destination != null
                        && destination.getX() > template.minCorner().getX()
                        && destination.getX() < template.maxCorner().getX()
                        && destination.getZ() > template.minCorner().getZ()
                        && destination.getZ() < template.maxCorner().getZ(),
                "Herdsman capture destination is not inside the fence");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void everyProfessionUsesSharedWorkRestSleepSchedule(GameTestHelper helper) {
        helper.assertTrue(CitizenWorkService.schedulePhase(1000) == CitizenWorkService.SchedulePhase.WORK,
                "Work does not begin at 1000");
        helper.assertTrue(CitizenWorkService.schedulePhase(11999) == CitizenWorkService.SchedulePhase.WORK,
                "Work ends too early");
        helper.assertTrue(CitizenWorkService.schedulePhase(12000) == CitizenWorkService.SchedulePhase.REST,
                "Midday rest missing");
        helper.assertTrue(CitizenWorkService.schedulePhase(14000) == CitizenWorkService.SchedulePhase.SLEEP,
                "Sleep does not begin at 14000");
        helper.assertTrue(CitizenWorkService.schedulePhase(22000) == CitizenWorkService.SchedulePhase.REST,
                "Residents should wake into pre-work rest");
        helper.assertTrue(CitizenWorkService.schedulePhase(0) == CitizenWorkService.SchedulePhase.REST,
                "Morning rest missing");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void approvedDecorationsAreResidentBuildableCatalogEntries(GameTestHelper helper) {
        BuildingType[] decorations = {BuildingType.LAMP_SMALL, BuildingType.LAMP_ARM, BuildingType.LAMP_DOUBLE,
                BuildingType.BENCH, BuildingType.FLOWERBED, BuildingType.SIGNPOST,
                BuildingType.WELL, BuildingType.WOODSHED};
        for (BuildingType type : decorations) {
            var template = BlueprintTemplates.create(type, helper.absolutePos(new BlockPos(54000, 5, 54000)),
                    Direction.NORTH);
            helper.assertTrue(template.placements().stream().anyMatch(p -> p.material() != null),
                    type + " has no paid construction materials");
            helper.assertTrue(java.util.List.of(SettlementCoreMenu.BUILDING_CHOICES).contains(type),
                    type + " is absent from planning catalog");
        }
        var well = BlueprintTemplates.create(BuildingType.WELL,
                helper.absolutePos(new BlockPos(54100, 5, 54100)), Direction.NORTH);
        helper.assertTrue(well.placements().stream().anyMatch(p -> p.state().is(Blocks.WATER)
                        && p.material() == Items.WATER_BUCKET),
                "Well water does not consume a water bucket");
        helper.succeed();
    }

    private static int firstIndex(java.util.List<BlueprintPlacement> placements,
            java.util.function.Predicate<BlueprintPlacement> predicate) {
        for (int i = 0; i < placements.size(); i++) if (predicate.test(placements.get(i))) return i;
        return -1;
    }

    private static int lastIndex(java.util.List<BlueprintPlacement> placements,
            java.util.function.Predicate<BlueprintPlacement> predicate) {
        for (int i = placements.size() - 1; i >= 0; i--) if (predicate.test(placements.get(i))) return i;
        return -1;
    }

    private static void assertMirrorBlocks(GameTestHelper helper, java.util.List<BlueprintPlacement> placements,
            BlockPos first, BlockPos second) {
        Map<BlockPos, Block> blocks = new HashMap<>();
        for (BlueprintPlacement placement : placements) blocks.put(placement.position(), placement.state().getBlock());
        for (Map.Entry<BlockPos, Block> entry : blocks.entrySet()) {
            BlockPos p = entry.getKey();
            BlockPos xMirror = new BlockPos(first.getX() + second.getX() - p.getX(), p.getY(), p.getZ());
            BlockPos zMirror = new BlockPos(p.getX(), p.getY(), first.getZ() + second.getZ() - p.getZ());
            helper.assertTrue(blocks.get(xMirror) == entry.getValue(), "X symmetry mismatch at " + p);
            helper.assertTrue(blocks.get(zMirror) == entry.getValue(), "Z symmetry mismatch at " + p);
        }
    }
}

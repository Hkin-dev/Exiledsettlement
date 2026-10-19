package com.hkin.exiledsettlement.registry;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.block.SettlementBuildingBlock;
import com.hkin.exiledsettlement.block.SettlementCoreBlock;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ExiledSettlementMod.MOD_ID);

    public static final DeferredBlock<SettlementCoreBlock> SETTLEMENT_CORE = BLOCKS.registerBlock(
            "settlement_core",
            SettlementCoreBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()
                    .strength(4.0F, 8.0F)
    );

    /** Visual-only design prop. It deliberately has no settlement-core behaviour. */
    public static final DeferredBlock<Block> USELESS_SETTLEMENT_CORE = BLOCKS.registerBlock(
            "useless_settlement_core",
            Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()
                    .strength(4.0F, 8.0F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> EXILE_WAGON = BLOCKS.registerBlock(
            "exile_wagon",
            properties -> new SettlementBuildingBlock(BuildingType.WAGON, properties),
            woodProperties(MapColor.WOOD).strength(2.5F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> HOUSE_MARKER = BLOCKS.registerBlock(
            "house_marker",
            properties -> new SettlementBuildingBlock(BuildingType.HOUSE, properties),
            woodProperties(MapColor.WOOD).strength(2.0F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> WAREHOUSE_MARKER = BLOCKS.registerBlock(
            "warehouse_marker",
            properties -> new SettlementBuildingBlock(BuildingType.WAREHOUSE, properties),
            woodProperties(MapColor.PODZOL).strength(2.5F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> FARMSTEAD_MARKER = BLOCKS.registerBlock(
            "farmstead_marker",
            properties -> new SettlementBuildingBlock(BuildingType.FARM_FIELD, properties),
            woodProperties(MapColor.WOOD).strength(2.0F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> PASTURE_MARKER = BLOCKS.registerBlock(
            "pasture_marker",
            properties -> new SettlementBuildingBlock(BuildingType.PASTURE_AREA, properties),
            woodProperties(MapColor.GRASS).strength(2.0F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> HUNTING_LODGE_MARKER = BLOCKS.registerBlock(
            "hunting_lodge_marker",
            properties -> new SettlementBuildingBlock(BuildingType.HUNTING_LODGE, properties),
            woodProperties(MapColor.PODZOL).strength(2.5F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> TOOL_WORKSHOP_MARKER = BLOCKS.registerBlock(
            "tool_workshop_marker",
            properties -> new SettlementBuildingBlock(BuildingType.TOOL_WORKSHOP, properties),
            woodProperties(MapColor.STONE).strength(2.5F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> FORESTER_LODGE_MARKER = BLOCKS.registerBlock(
            "forester_lodge_marker",
            properties -> new SettlementBuildingBlock(BuildingType.FORESTER_LODGE, properties),
            woodProperties(MapColor.PODZOL).strength(2.5F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> TOWN_HALL_MARKER = BLOCKS.registerBlock(
            "town_hall_marker",
            properties -> new com.hkin.exiledsettlement.block.FacingTownHallBlock(properties),
            woodProperties(MapColor.PODZOL).noOcclusion().strength(3.0F)
    );

    public static final DeferredBlock<SettlementBuildingBlock> FISHING_HUT_MARKER = BLOCKS.registerBlock(
            "fishing_hut_marker",
            properties -> new SettlementBuildingBlock(BuildingType.FISHING_HUT, properties),
            woodProperties(MapColor.WOOD).strength(2.0F)
    );

    // Compatibility-only registry entry. It is hidden, inert and removed from settlement data.
    public static final DeferredBlock<Block> LEGACY_STOCKPILE_MARKER = BLOCKS.registerBlock(
            "stockpile_marker",
            Block::new,
            woodProperties(MapColor.WOOD).strength(2.0F)
    );

    private static BlockBehaviour.Properties woodProperties(MapColor color) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .sound(SoundType.WOOD)
                .ignitedByLava();
    }

    private ModBlocks() {
    }
}

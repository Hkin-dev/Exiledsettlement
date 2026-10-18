package com.hkin.exiledsettlement.registry;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ExiledSettlementTags {
    public static final TagKey<Block> REPLACEABLE_FOUNDATION = TagKey.create(
            Registries.BLOCK,
            ExiledSettlementMod.id("replaceable_foundation")
    );
    public static final TagKey<Block> CONSTRUCTION_CLEARABLE_VEGETATION = TagKey.create(
            Registries.BLOCK,
            ExiledSettlementMod.id("construction_clearable_vegetation")
    );
    public static final TagKey<Block> CONSTRUCTION_CLEARABLE_TERRAIN = TagKey.create(
            Registries.BLOCK,
            ExiledSettlementMod.id("construction_clearable_terrain")
    );

    private ExiledSettlementTags() {
    }
}

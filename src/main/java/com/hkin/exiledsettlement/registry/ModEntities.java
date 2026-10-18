package com.hkin.exiledsettlement.registry;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(
            Registries.ENTITY_TYPE,
            ExiledSettlementMod.MOD_ID
    );

    public static final DeferredHolder<EntityType<?>, EntityType<SettlementCitizenEntity>> SETTLEMENT_CITIZEN =
            ENTITY_TYPES.register(
                    "settlement_citizen",
                    () -> EntityType.Builder.of(SettlementCitizenEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.95F)
                            .eyeHeight(1.62F)
                            .clientTrackingRange(10)
                            .build(ExiledSettlementMod.id("settlement_citizen").toString())
            );

    private ModEntities() {
    }
}

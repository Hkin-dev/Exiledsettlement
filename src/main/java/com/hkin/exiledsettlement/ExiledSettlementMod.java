package com.hkin.exiledsettlement;

import com.hkin.exiledsettlement.command.SettlementCommands;
import com.hkin.exiledsettlement.event.SettlementEvents;
import com.hkin.exiledsettlement.gametest.SettlementGameTests;
import com.hkin.exiledsettlement.gametest.LogisticsWorkGameTests;
import com.hkin.exiledsettlement.gametest.ProfessionWorkGameTests;
import com.hkin.exiledsettlement.gametest.OtherProfessionWorkGameTests;
import com.hkin.exiledsettlement.gametest.NavigationProbeGameTests;
import com.hkin.exiledsettlement.gametest.ToolsmithWorkGameTests;
import com.hkin.exiledsettlement.gametest.CompoundPlacementGameTests;
import com.hkin.exiledsettlement.gametest.EquipmentInventoryGameTests;
import com.hkin.exiledsettlement.gametest.WorksiteAssignmentGameTests;
import com.hkin.exiledsettlement.gametest.ConstructionPlanningBudgetGameTests;
import com.hkin.exiledsettlement.gametest.ExpansionGameTests;
import com.hkin.exiledsettlement.gametest.ProductionAreaAndScheduleGameTests;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.registry.ModMenus;
import com.hkin.exiledsettlement.network.PlacementNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

@Mod(ExiledSettlementMod.MOD_ID)
public final class ExiledSettlementMod {
    public static final String MOD_ID = "exiledsettlement";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExiledSettlementMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,com.hkin.exiledsettlement.terrain.TerrainConfig.SPEC,"exiledsettlement-terrain-server.toml");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                com.hkin.exiledsettlement.client.SeasonClientConfig.SPEC);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::addCreativeItems);
        modEventBus.addListener(this::registerGameTests);
        modEventBus.addListener(this::registerEntityAttributes);
        modEventBus.addListener(PlacementNetworking::registerPayloads);

        SettlementEvents events = new SettlementEvents();
        NeoForge.EVENT_BUS.addListener(com.hkin.exiledsettlement.building.WarehouseDisplayService::onInteract);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(events::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.hkin.exiledsettlement.debug.TickPhaseProfiler::before);
        NeoForge.EVENT_BUS.addListener(events::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) -> {
            if(event.getEntity() instanceof net.minecraft.world.entity.animal.Animal animal) {
                com.hkin.exiledsettlement.region.PastureGroundSafety.releaseAnimalAi(animal);
                com.hkin.exiledsettlement.region.PastureResidentService.release(animal);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
            if (com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled()
                    && Boolean.getBoolean("exiledsettlement.testStartFrozen")) event.getServer().tickRateManager().setFrozen(true);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.shutdown(event.getServer()));
        NeoForge.EVENT_BUS.addListener(events::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(events::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(events::onExplosion);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,events::onMobDestroy);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,events::onMobGrief);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,events::onRecoveryFallingBlock);
        NeoForge.EVENT_BUS.addListener(events::onCitizenDeath);
        NeoForge.EVENT_BUS.addListener(events::onCitizenConversion);
        NeoForge.EVENT_BUS.addListener(events::onCitizenBreeding);
        NeoForge.EVENT_BUS.addListener(events::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(com.hkin.exiledsettlement.citizen.CitizenNamingService::onInteract);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                com.hkin.exiledsettlement.citizen.CitizenNamingService.clearAll());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                com.hkin.exiledsettlement.citizen.CitizenInteractionService.clearAll());
        NeoForge.EVENT_BUS.addListener(events::onEntityTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddReloadListenerEvent event) ->
                event.addListener(new com.hkin.exiledsettlement.settlement.CommunityContent()));
        NeoForge.EVENT_BUS.addListener(com.hkin.exiledsettlement.settlement.FoundingService::onPlayerTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                com.hkin.exiledsettlement.debug.BuildingLabService.shutdown(event.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
                com.hkin.exiledsettlement.debug.BuildingEditBridge.shutdown(event.getServer()));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.COPPER_AXE.get());
            event.accept(ModItems.COPPER_PICKAXE.get());
            event.accept(ModItems.COPPER_SHOVEL.get());
            event.accept(ModItems.COPPER_HOE.get());
        }
        if (event.getTabKey() == CreativeModeTabs.COMBAT) event.accept(ModItems.COPPER_SWORD.get());
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SETTLEMENT_CORE.get());
            event.accept(ModItems.USELESS_SETTLEMENT_CORE.get());
            event.accept(ModItems.EXILE_WAGON.get());
            event.accept(ModItems.HOUSE_BLUEPRINT.get());
            event.accept(ModItems.WAREHOUSE_BLUEPRINT.get());
            event.accept(ModItems.CREATIVE_SUPPLY_CORE.get());
            event.accept(ModItems.CLAIM_DEED.get());
            event.accept(ModItems.APPOINTMENT_DOCUMENT.get());
            event.accept(ModItems.WOOL_COAT.get());
            event.accept(ModItems.LEATHER_COAT.get());
        }
    }

    private void registerCommands(RegisterCommandsEvent event) {
        SettlementCommands.register(event.getDispatcher());
        com.hkin.exiledsettlement.command.SaveAcceptanceCommands.register(event.getDispatcher());
        com.hkin.exiledsettlement.command.BuildingLabCommands.register(event.getDispatcher());
        com.hkin.exiledsettlement.command.BuildingEditCommands.register(event.getDispatcher());
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        if ("homes_camp".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.HomesCampGameTests.class); return;
        }
        if ("apartment".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.ApartmentGameTests.class); return;
        }
        if("mine_balance".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.MineGameTests.class);return;
        }
        if("wagon_egress".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.WagonEgressGameTests.class);return;
        }
        if("brick_kiln".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.BrickKilnGameTests.class);return;
        }
        if("clearance_phase".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.ClearancePhaseGameTests.class);return;
        }
        if("chef".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.ChefGameTests.class);return;
        }
        if("distinct_eight".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.DistinctBuildingGameTests.class);return;
        }
        if("distinct_baseline".equals(System.getProperty("exiledsettlement.testSuite"))) return;
        if("home_logistics".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.HomeLogisticsGameTests.class);return;
        }
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite"))) {
            event.register(com.hkin.exiledsettlement.gametest.TimedConstructionGameTests.class);return;
        }
        event.register(com.hkin.exiledsettlement.gametest.ResidentExperienceGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.CommunityLifeGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.SeasonPresentationGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.RealSeasonGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.AutoRoadGameTests.class);
        event.register(SettlementGameTests.class);
        event.register(LogisticsWorkGameTests.class);
        event.register(ProfessionWorkGameTests.class);
        event.register(OtherProfessionWorkGameTests.class);
        event.register(NavigationProbeGameTests.class);
        event.register(ToolsmithWorkGameTests.class);
        event.register(CompoundPlacementGameTests.class);
        event.register(EquipmentInventoryGameTests.class);
        event.register(WorksiteAssignmentGameTests.class);
        event.register(ConstructionPlanningBudgetGameTests.class);
        event.register(ExpansionGameTests.class);
        event.register(ProductionAreaAndScheduleGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.BuildingLabGameTests.class);
        event.register(com.hkin.exiledsettlement.gametest.BuildingEditGameTests.class);
    }

    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SETTLEMENT_CITIZEN.get(), Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .build());
    }
}

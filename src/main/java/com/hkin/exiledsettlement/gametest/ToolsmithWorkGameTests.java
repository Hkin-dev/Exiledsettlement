package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real server events and entity ticks run the complete finite-stock toolmaking chain. */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ToolsmithWorkGameTests {
    private static final Set<Item> TOOLS = Set.of(Items.IRON_AXE, Items.IRON_PICKAXE,
            Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_SWORD, Items.FISHING_ROD);

    private ToolsmithWorkGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "toolsmith_work_live", timeoutTicks = 6000)
    public static void toolsmithPhysicallyBatchesFiniteInputsAcrossWarehousesAndStoresSixTools(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper);
        int recipe = Math.floorMod(fixture.citizen.id().hashCode(), 5);
        fixture.materialPerTool = new int[]{3, 3, 1, 2, 2}[recipe];
        fixture.sticksPerTool = new int[]{2, 2, 2, 2, 1}[recipe];
        int materialTotal = fixture.materialPerTool * 6;
        int stickTotal = fixture.sticksPerTool * 6;
        int materialThird = Math.max(1, (materialTotal - 3) / 2);
        fixture.stock(0, 0, 0, new ItemStack(Items.IRON_PICKAXE));
        fixture.stock(0, 1, 0, new ItemStack(Items.IRON_INGOT, 1));
        fixture.stock(0, 1, 1, new ItemStack(Items.IRON_INGOT, 2));
        fixture.stock(0, 2, 0, new ItemStack(Items.IRON_INGOT, materialThird));
        fixture.stock(1, 1, 0, new ItemStack(Items.IRON_INGOT, materialTotal - 3 - materialThird));
        fixture.stock(1, 2, 0, new ItemStack(Items.STICK, 1));
        fixture.stock(1, 2, 1, new ItemStack(Items.STICK, stickTotal - 1));
        fixture.start();
        helper.runAtTickTime(5990, () -> {
            if (fixture.finished) return;
            String reason = fixture.diagnostics();
            fixture.finish();
            helper.fail("Toolsmith live chain timed out: " + reason);
        });
        helper.startSequence().thenWaitUntil(fixture::awaitProjection).thenWaitUntil(() -> {
            fixture.observe();
            helper.assertTrue(fixture.failure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(fixture.previousProduced == 6 && fixture.storedTools() == (recipe == 1 || recipe == 4 ? 6 : 5)
                            && java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE)
                               .allMatch(slot -> com.hkin.exiledsettlement.citizen.CitizenEquipmentService.deliverableCount(fixture.citizen,slot) == 0)
                            && fixture.countInBag(Items.IRON_INGOT) == 0
                            && fixture.countInBag(Items.STICK) == 0 && fixture.visitedWarehouses.size() == 2
                            && fixture.sawBatch && fixture.shortageObserved && fixture.shortageRestored
                            && fixture.sawWorkshop && fixture.visited.size() >= 12,
                    "Waiting for physical inputs, shortage recovery, workshop production and six stored tools: "
                            + fixture.diagnostics());
            ExiledSettlementMod.LOGGER.info("[toolsmith-gate] PASS {}", fixture.fullDiagnostics());
        }).thenExecute(fixture::finish).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final SettlementSavedData data;
        final SettlementRecord settlement;
        final CitizenRecord citizen;
        final BlockPos core;
        final List<ChunkPos> forced;
        final List<BuildingRecord> warehouses = new ArrayList<>();
        final Set<UUID> visitedWarehouses = new HashSet<>();
        final Set<BlockPos> visited = new HashSet<>();
        final Map<BlockPos, Integer> previousInputCounts = new HashMap<>();
        final List<HeldStock> withheldSticks = new ArrayList<>();
        BuildingRecord workshop;
        SettlementCitizenEntity entity;
        long started;
        long shortageStarted = -1;
        int ironDuringShortage;
        int previousProduced;
        int materialPerTool;
        int sticksPerTool;
        boolean shortageObserved;
        boolean shortageRestored;
        boolean sawBatch;
        boolean sawWorkshop;
        boolean finished;
        String failure = "";

        private Fixture(GameTestHelper helper, SettlementRecord settlement, CitizenRecord citizen,
                        BlockPos core, List<ChunkPos> forced) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.data = SettlementSavedData.get(level);
            this.settlement = settlement;
            this.citizen = citizen;
            this.core = core;
            this.forced = forced;
        }

        static Fixture create(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            SettlementSavedData data = SettlementSavedData.get(level);
            BlockPos core = null;
            BlockPos first = helper.absolutePos(new BlockPos(12296, 8, 4104));
            for (int attempt = 0; attempt < 32; attempt++) {
                BlockPos candidate = first.offset(0, 0, attempt * 256);
                if (data.settlements().stream().noneMatch(existing -> existing.overlapsBoundary(candidate, 4))) {
                    core = candidate;
                    break;
                }
            }
            helper.assertTrue(core != null, "No unused district for the toolsmith test; use a fresh test world");
            BlockPos min = core.offset(-48, -3, -32);
            BlockPos max = core.offset(48, 12, 56);
            List<ChunkPos> forced = new ArrayList<>();
            ChunkPos firstChunk = new ChunkPos(min);
            ChunkPos lastChunk = new ChunkPos(max);
            for (int x = firstChunk.x; x <= lastChunk.x; x++) for (int z = firstChunk.z; z <= lastChunk.z; z++) {
                level.setChunkForced(x, z, true);
                forced.add(new ChunkPos(x, z));
            }
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                level.setBlock(pos, pos.getY() < core.getY() - 1 ? Blocks.DIRT.defaultBlockState()
                        : pos.getY() == core.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
            level.setDayTime(6000L);
            UUID owner = level.players().size() == 1 ? level.players().getFirst().getUUID() : UUID.randomUUID();
            var created = data.createOrRecover(core, owner, "Live finite-input toolsmith gate");
            helper.assertTrue(created.success() && created.value() != null, "Could not create toolsmith fixture");
            SettlementRecord settlement = created.value();
            settlement.upgradeLevel();
            settlement.upgradeLevel();
            settlement.setCreativeCoreInstalled(false);
            CitizenRecord citizen = settlement.citizens().iterator().next();
            for (CitizenRecord other : settlement.citizens()) if (other != citizen) other.markDead(core);
            Fixture fixture = new Fixture(helper, settlement, citizen, core, forced);
            fixture.warehouses.add(fixture.place(BlueprintTemplates.create(BuildingType.WAREHOUSE,
                    core.offset(-18, 0, 0), Direction.EAST)));
            fixture.warehouses.add(fixture.place(BlueprintTemplates.create(BuildingType.WAREHOUSE,
                    core.offset(-18, 0, 26), Direction.EAST)));
            fixture.workshop = fixture.place(BlueprintTemplates.create(BuildingType.TOOL_WORKSHOP,
                    core.offset(16, 0, 0), Direction.NORTH));
            fixture.workshop.setWorkerTarget(1, fixture.workshop.configurationRevision());
            citizen.setManualAssignment(CitizenProfession.TOOLSMITH, fixture.workshop.id());
            return fixture;
        }

        BuildingRecord place(BuildingBlueprintTemplate template) {
            for (var placement : template.placements()) {
                if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                        && !level.getBlockState(placement.position()).isAir()) continue;
                level.setBlock(placement.position(), placement.mode() == BlueprintPlacementMode.CLEARANCE
                        ? Blocks.AIR.defaultBlockState() : placement.state(), 2);
            }
            for (var placement : template.placements()) BlueprintBlockStateResolver.refreshExisting(level, placement);
            BuildingRecord building = settlement.addBuilding(template.type(), template.registrationPosition(),
                    template.facing(), template.minCorner(), template.maxCorner(), template.transitionMargin(),
                    BlueprintTemplates.currentTemplateVersion(template.type()));
            BuildingFunctionService.validateSingle(level, data, settlement, building);
            helper.assertTrue(building.operational(), "Formal toolsmith fixture failed validation: "
                    + building.type() + "/" + building.status());
            return building;
        }

        List<WarehouseInventoryService.ContainerAt> containers(BuildingRecord warehouse) {
            return WarehouseInventoryService.loadedContainers(level, warehouse).stream()
                    .sorted(Comparator.comparingInt((WarehouseInventoryService.ContainerAt at) -> at.position().getY())
                            .thenComparingDouble(at -> at.position().distSqr(core)))
                    .toList();
        }

        void stock(int warehouseIndex, int containerIndex, int slot, ItemStack stack) {
            Container container = containers(warehouses.get(warehouseIndex)).get(containerIndex).container();
            helper.assertTrue(container.getItem(slot).isEmpty(), "Fixture would overwrite a supplied item");
            container.setItem(slot, stack.copy());
            container.setChanged();
        }

        void start() {
            helper.assertTrue(citizen.inventory().stream().allMatch(ItemStack::isEmpty), "Toolsmith backpack was preloaded");
            BlockPos start = core.offset(4, 0, 12);
            helper.assertTrue(warehouses.stream().flatMap(warehouse -> containers(warehouse).stream())
                            .allMatch(at -> at.position().distSqr(start) >= 144.0D),
                    "Toolsmith must start at least twelve blocks from all warehouse containers");
            citizen.updateProjection(null, start);
            for (BuildingRecord warehouse : warehouses) for (var at : containers(warehouse)) {
                previousInputCounts.put(at.position(), inputCount(at.container()));
            }
            started = level.getGameTime();
            data.touch();
            CitizenProjectionService.ensureSettlementCitizens(level, settlement);
        }

        void awaitProjection() {
            if (entity == null) {
                CitizenProjectionService.ensureSettlementCitizens(level, settlement);
                entity = CitizenProjectionService.findProjection(level, citizen);
            }
            helper.assertTrue(entity != null, "Waiting for toolsmith projection");
        }

        void observe() {
            level.setDayTime((previousProduced == 6 ? 23000L : 6000L) + CitizenWorkService.scheduleOffset(citizen));
            require(entity.isAlive(), "Toolsmith died");
            visited.add(entity.blockPosition().immutable());
            for (BuildingRecord warehouse : warehouses) {
                if (WarehouseInventoryService.canInteract(level, warehouse, entity)) visitedWarehouses.add(warehouse.id());
                for (var at : containers(warehouse)) {
                    int current = inputCount(at.container());
                    int previous = previousInputCounts.getOrDefault(at.position(), current);
                    // 0.15.5: loading points serve the whole registered warehouse.
                    if (current < previous) require(WarehouseInventoryService.canInteract(level, warehouse, entity),
                            "Warehouse material was withdrawn outside its building at " + at.position());
                    previousInputCounts.put(at.position(), current);
                }
            }
            // Simulate another consumer temporarily removing the second input.
            // The resident's real partial pickup must remain in their backpack.
            if (shortageStarted < 0 && countInBag(Items.IRON_INGOT) > 0 && countInBag(Items.STICK) == 0) {
                shortageStarted = level.getGameTime();
                ironDuringShortage = countInBag(Items.IRON_INGOT);
                for (BuildingRecord warehouse : warehouses) for (var at : containers(warehouse)) {
                    Container container = at.container();
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        if (!container.getItem(slot).is(Items.STICK)) continue;
                        withheldSticks.add(new HeldStock(container, slot, container.getItem(slot).copy()));
                        container.setItem(slot, ItemStack.EMPTY);
                    }
                    container.setChanged();
                    previousInputCounts.put(at.position(), inputCount(container));
                }
            }
            if (shortageStarted >= 0 && !shortageRestored) {
                require(countInBag(Items.IRON_INGOT) == ironDuringShortage,
                        "A temporary input shortage caused the partial material batch to be returned or consumed");
                shortageObserved |= "MISSING_INPUT".equals(citizen.workFailure());
                if (level.getGameTime() - shortageStarted >= 80L) {
                    for (HeldStock stock : withheldSticks) {
                        require(stock.container.getItem(stock.slot).isEmpty(), "Temporary stock slot changed unexpectedly");
                        stock.container.setItem(stock.slot, stock.stack.copy());
                        stock.container.setChanged();
                    }
                    shortageRestored = true;
                }
            }
            sawBatch |= countInBag(Items.IRON_INGOT) >= 6 && countInBag(Items.STICK) >= 4;
            int produced = storedTools() + citizen.inventory().stream()
                    .filter(stack -> TOOLS.contains(stack.getItem())).mapToInt(ItemStack::getCount).sum() - 1;
            if (produced > previousProduced) {
                require(WarehouseInventoryService.canInteract(level, workshop, entity), "Tool was produced outside the actual workshop");
                sawWorkshop = true;
            }
            previousProduced = produced;
            int heldSticks = shortageRestored ? 0 : withheldSticks.stream().mapToInt(stock -> stock.stack.getCount()).sum();
            require(stored(Items.IRON_INGOT) + countInBag(Items.IRON_INGOT)
                            + materialPerTool * produced == materialPerTool * 6,
                    "Iron conservation failed for the selected artisan recipe");
            require(stored(Items.STICK) + countInBag(Items.STICK) + heldSticks
                            + sticksPerTool * produced == sticksPerTool * 6,
                    "Stick conservation failed for the selected artisan recipe");
            require(produced >= 0 && produced <= 6, "Tool count escaped the finite six-recipe budget");
            if ((level.getGameTime() - started) % 400L == 0L) {
                ExiledSettlementMod.LOGGER.info("[toolsmith-gate] {}", fullDiagnostics());
            }
        }

        boolean visibleContainer(BlockPos container) {
            if (entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(container)) > 20.25D) return false;
            var hit = level.clip(new ClipContext(entity.getEyePosition(), Vec3.atCenterOf(container),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(container);
        }

        static boolean inside(BuildingRecord building, BlockPos position) {
            return position.getX() >= building.minCorner().getX() && position.getX() <= building.maxCorner().getX()
                    && position.getZ() >= building.minCorner().getZ() && position.getZ() <= building.maxCorner().getZ()
                    && position.getY() >= building.minCorner().getY() && position.getY() <= building.maxCorner().getY();
        }

        static int inputCount(Container container) {
            int count = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.IRON_INGOT) || stack.is(Items.STICK)) count += stack.getCount();
            }
            return count;
        }

        int countInBag(Item item) {
            return citizen.inventory().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        }

        int stored(Item item) {
            return warehouses.stream().mapToInt(warehouse -> WarehouseInventoryService.countItem(level, warehouse, item)).sum();
        }

        int storedTools() { return TOOLS.stream().mapToInt(this::stored).sum(); }

        void require(boolean condition, String message) { if (!condition && failure.isEmpty()) failure = message; }

        String diagnostics() {
            return "elapsed=" + (level.getGameTime() - started) + " state=" + citizen.workState()
                    + " activity=" + citizen.activity() + " reason=" + citizen.workFailure()
                    + " at=" + (entity == null ? null : entity.blockPosition()) + " invariant=" + failure;
        }

        String fullDiagnostics() {
            return diagnostics() + " stockTools=" + storedTools() + " produced=" + previousProduced
                    + " bag=" + citizen.inventory().stream().filter(stack -> !stack.isEmpty()).toList()
                    + " warehousesVisited=" + visitedWarehouses.size() + " shortage=" + shortageObserved
                    + "/" + shortageRestored + " batched=" + sawBatch + " workshop=" + sawWorkshop
                    + " nav=" + (entity == null ? null : entity.settlementNavigationDiagnostics());
        }

        void finish() {
            if (finished) return;
            finished = true;
            if (!shortageRestored) for (HeldStock stock : withheldSticks) {
                if (stock.container.getItem(stock.slot).isEmpty()) stock.container.setItem(stock.slot, stock.stack.copy());
            }
            settlement.deactivate();
            if (entity != null) entity.stopSettlementNavigation();
            data.touch();
            for (ChunkPos chunk : forced) level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    private record HeldStock(Container container, int slot, ItemStack stack) { }
}

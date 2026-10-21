package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.blueprint.PlacementSessionService;
import com.hkin.exiledsettlement.client.ClientPlacementController;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.building.WarehouseCategory;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.client.SettlementCoreScreen;
import com.hkin.exiledsettlement.client.CitizenAssignmentScreen;
import com.hkin.exiledsettlement.citizen.CitizenAssignmentService;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.WorksiteAssignmentService;
import com.hkin.exiledsettlement.citizen.WorkforceStatusService;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

public final class PlacementNetworking {
    private static final String NETWORK_VERSION = "30";
    private static final BuildingSnapshotRequestLimiter BUILDING_SNAPSHOT_LIMITER = new BuildingSnapshotRequestLimiter();

    private PlacementNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(WorkTargetsPayload.TYPE,WorkTargetsPayload.STREAM_CODEC,
                (payload,context)->com.hkin.exiledsettlement.client.ClientWorkTargets.receive(payload));
        registrar.playToServer(WarehouseRequestPayload.TYPE,WarehouseRequestPayload.STREAM_CODEC,
                (p,c)->{if(c.player() instanceof ServerPlayer player)com.hkin.exiledsettlement.building.WarehouseManagementService.request(player,p);});
        registrar.playToClient(WarehouseSnapshotPayload.TYPE,WarehouseSnapshotPayload.STREAM_CODEC,
                (p,c)->com.hkin.exiledsettlement.client.WarehouseManagementScreen.receive(p));
        registrar.playToServer(PlanBridgePayload.TYPE,PlanBridgePayload.STREAM_CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)com.hkin.exiledsettlement.terrain.PlayerWorksService.selectBridge(player,p);});
        registrar.playToClient(BridgePreviewPayload.TYPE,BridgePreviewPayload.STREAM_CODEC,(p,c)->ClientPlacementController.receiveBridgePreview(p));
        registrar.playToClient(WorksSelectionPayload.TYPE,WorksSelectionPayload.STREAM_CODEC,
                (p,c)->ClientPlacementController.beginWorks(p));
        registrar.playToClient(WorksPreviewPayload.TYPE,WorksPreviewPayload.STREAM_CODEC,
                (p,c)->ClientPlacementController.previewWorks(p));
        registrar.playToServer(ConfirmWorksPayload.TYPE,ConfirmWorksPayload.STREAM_CODEC,
                (p,c)->{if(c.player() instanceof ServerPlayer player)com.hkin.exiledsettlement.terrain.PlayerWorksService.confirm(player,p.token(),p.confirm());});
        registrar.playToClient(BuildingSummaryPagePayload.TYPE,BuildingSummaryPagePayload.STREAM_CODEC,(p,c)->ClientPlacementController.receiveBuildingPage(p));
        registrar.playToClient(RegionPagePayload.TYPE,RegionPagePayload.STREAM_CODEC,(payload,context)->com.hkin.exiledsettlement.client.ClientRegionCache.receive(payload));
        registrar.playToServer(RequestRegionPagePayload.TYPE,RequestRegionPagePayload.STREAM_CODEC,(payload,context)->{
            if(!(context.player() instanceof ServerPlayer player) || payload.offset()<0)return;
            var level=player.serverLevel();
            for(var settlement:SettlementSavedData.get(level).settlements())if(settlement.isMember(player.getUUID())) {
                var site=settlement.blueprint(payload.id());var building=settlement.building(payload.id());
                var region=site!=null?site.region():building!=null?building.region():null;
                if(region==null)continue;
                String regionStatus=region.plainSoil()?com.hkin.exiledsettlement.region.RegionGroundService.summary(player.serverLevel(),settlement,region):region.status;
                if(payload.offset()==0 && payload.revision()==region.revision){PacketDistributor.sendToPlayer(player,new RegionPagePayload(payload.id(),region.revision,-1,region.ordered().size(),region.type,region.first,region.second,region.facing,region.surveyed,region.prepared,regionStatus,List.of(),java.util.Map.of(),java.util.Set.of(),java.util.Set.of()));return;}
                int offset=payload.revision()==region.revision?payload.offset():0;if(offset>region.ordered().size())return;
                int end=Math.min(region.ordered().size(),offset+RegionPagePayload.PAGE_SIZE);
                PacketDistributor.sendToPlayer(player,new RegionPagePayload(payload.id(),region.revision,offset,region.ordered().size(),region.type,region.first,region.second,region.facing,region.surveyed,region.prepared,regionStatus,region.ordered().subList(offset,end),region.stairs,region.legacyWater,region.waterBarriers));return;
            }
        });
        registrar.playToClient(CitizenThermalPayload.TYPE,CitizenThermalPayload.STREAM_CODEC,
                (payload,context)->CitizenAssignmentScreen.receiveThermal(payload));
        registrar.playToServer(CitizenInteractionPayload.TYPE, CitizenInteractionPayload.STREAM_CODEC,
                (payload, context) -> { if (context.player() instanceof ServerPlayer player)
                    com.hkin.exiledsettlement.citizen.CitizenInteractionService.renew(player, payload.active()); });
        registrar.playToClient(OpenCitizenRenamePayload.TYPE, OpenCitizenRenamePayload.STREAM_CODEC,
                (payload, context) -> com.hkin.exiledsettlement.client.CitizenRenameScreen.open(payload));
        registrar.playToServer(RenameCitizenPayload.TYPE, RenameCitizenPayload.STREAM_CODEC,
                (payload, context) -> { if (context.player() instanceof ServerPlayer player)
                    com.hkin.exiledsettlement.citizen.CitizenNamingService.confirm(player, payload.token(), payload.name(), payload.cancel()); });
        registrar.playToServer(ProjectionUploadPayload.TYPE, ProjectionUploadPayload.STREAM_CODEC,
                (payload, context) -> { if (context.player() instanceof ServerPlayer player)
                    com.hkin.exiledsettlement.blueprint.CustomProjectionService.receive(player, payload); });
        registrar.playToClient(SeasonSnapshotPayload.TYPE, SeasonSnapshotPayload.STREAM_CODEC,
                (payload, context) -> com.hkin.exiledsettlement.client.ClientSeasonController.receive(payload));
        registrar.playToClient(CommunitySnapshotPayload.TYPE, CommunitySnapshotPayload.STREAM_CODEC,
                (payload, context) -> com.hkin.exiledsettlement.client.CommunityScreen.receive(payload));
        registrar.playToServer(CommunityActionPayload.TYPE, CommunityActionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player)
                        com.hkin.exiledsettlement.settlement.CommunityManagement.action(player, payload.core(), payload.action());
                });
        registrar.playToClient(StartPlacementPayload.TYPE, StartPlacementPayload.STREAM_CODEC,
                PlacementNetworking::handleStart);
        registrar.playToClient(ClearPlacementPayload.TYPE, ClearPlacementPayload.STREAM_CODEC,
                PlacementNetworking::handleClear);
        registrar.playToClient(StartRoadPlacementPayload.TYPE, StartRoadPlacementPayload.STREAM_CODEC,
                PlacementNetworking::handleStartRoad);
        registrar.playToClient(ContinuePlacementPayload.TYPE, ContinuePlacementPayload.STREAM_CODEC,
                PlacementNetworking::handleContinue);
        registrar.playToServer(PlaceBuildingPayload.TYPE, PlaceBuildingPayload.STREAM_CODEC,
                PlacementNetworking::handlePlace);
        registrar.playToServer(PlaceAreaPayload.TYPE, PlaceAreaPayload.STREAM_CODEC,
                PlacementNetworking::handlePlaceArea);
        registrar.playToServer(CancelPlacementPayload.TYPE, CancelPlacementPayload.STREAM_CODEC,
                PlacementNetworking::handleCancel);
        registrar.playToServer(PlaceRoadPayload.TYPE, PlaceRoadPayload.STREAM_CODEC,
                PlacementNetworking::handlePlaceRoad);
        registrar.playToServer(RequestSettlementDetailsPayload.TYPE, RequestSettlementDetailsPayload.STREAM_CODEC,
                PlacementNetworking::handleDetailsRequest);
        registrar.playToClient(SettlementDetailsPayload.TYPE, SettlementDetailsPayload.STREAM_CODEC,
                PlacementNetworking::handleDetails);
        registrar.playToServer(RenameBuildingPayload.TYPE, RenameBuildingPayload.STREAM_CODEC,
                PlacementNetworking::handleRenameBuilding);
        registrar.playToClient(OpenCitizenAssignmentPayload.TYPE, OpenCitizenAssignmentPayload.STREAM_CODEC,
                PlacementNetworking::handleOpenCitizen);
        registrar.playToServer(AssignCitizenPayload.TYPE, AssignCitizenPayload.STREAM_CODEC,
                PlacementNetworking::handleAssignCitizen);
        registrar.playToServer(SetCitizenYsmModelPayload.TYPE, SetCitizenYsmModelPayload.STREAM_CODEC,
                PlacementNetworking::handleSetCitizenYsmModel);
        registrar.playToClient(StartBuildingManagementPayload.TYPE, StartBuildingManagementPayload.STREAM_CODEC,
                PlacementNetworking::handleStartManagement);
        registrar.playToServer(RequestOverviewSnapshotPayload.TYPE, RequestOverviewSnapshotPayload.STREAM_CODEC,
                PlacementNetworking::handleOverviewSnapshotRequest);
        registrar.playToClient(OverviewSnapshotPayload.TYPE, OverviewSnapshotPayload.STREAM_CODEC,
                PlacementNetworking::handleOverviewSnapshot);
        registrar.playToServer(RequestBuildingManagementPayload.TYPE, RequestBuildingManagementPayload.STREAM_CODEC,
                PlacementNetworking::handleBuildingManagementRequest);
        registrar.playToClient(OpenBuildingManagementPayload.TYPE, OpenBuildingManagementPayload.STREAM_CODEC,
                PlacementNetworking::handleOpenBuildingManagement);
        registrar.playToServer(BuildingActionPayload.TYPE, BuildingActionPayload.STREAM_CODEC,
                PlacementNetworking::handleBuildingAction);
        registrar.playToServer(BuildingStaffPayload.TYPE, BuildingStaffPayload.STREAM_CODEC,
                PlacementNetworking::handleBuildingStaff);
    }

    private static void handleStartRoad(StartRoadPlacementPayload payload, IPayloadContext context) {
        ClientPlacementController.beginRoad(payload.corePosition(), payload.tier(), payload.boundaryRadiusChunks());
    }

    private static void handleContinue(ContinuePlacementPayload payload, IPayloadContext context) {
        ClientPlacementController.continueAfterPlacement(payload.road(), payload.blueprint());
    }

    private static void handleStart(StartPlacementPayload payload, IPayloadContext context) {
        ClientPlacementController.begin(payload);
    }

    private static void handleClear(ClearPlacementPayload payload, IPayloadContext context) {
        ClientPlacementController.clear();
    }

    private static void handlePlace(PlaceBuildingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        OperationResult<?> result = PlacementSessionService.place(player, payload.anchor(), payload.facing());
        if (!result.success()) {
            SettlementMessages.sendFailure(player, result.reason(), true);
        }
    }

    private static void handlePlaceArea(PlaceAreaPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        OperationResult<?> result = PlacementSessionService.placeArea(
                player, payload.first(), payload.second(), payload.facing());
        if (!result.success()) SettlementMessages.sendFailure(player, result.reason(), true);
    }

    private static void handleCancel(CancelPlacementPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            PlacementSessionService.cancel(player, false);
        }
    }

    private static void handlePlaceRoad(PlaceRoadPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        OperationResult<?> result = PlacementSessionService.placeRoad(player, payload.first(), payload.second());
        if (!result.success()) {
            SettlementMessages.sendFailure(player, result.reason(), true);
        }
    }

    private static void handleDetailsRequest(RequestSettlementDetailsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) sendDetails(player, payload.corePosition());
    }

    private static void handleDetails(SettlementDetailsPayload payload, IPayloadContext context) {
        SettlementCoreScreen.receiveDetails(payload);
    }

    private static void handleStartManagement(StartBuildingManagementPayload payload, IPayloadContext context) {
        ClientPlacementController.beginManagement(payload);
    }

    private static void handleOverviewSnapshot(OverviewSnapshotPayload payload, IPayloadContext context) {
        ClientPlacementController.receiveOverviewSnapshot(payload);
    }

    private static void handleOverviewSnapshotRequest(
            RequestOverviewSnapshotPayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementRecord settlement = SettlementSavedData.get(player.serverLevel())
                .findByCore(payload.corePosition()).orElse(null);
        if (settlement == null || !settlement.active() || !settlement.isMember(player.getUUID())) return;
        BuildingSnapshotPages.send(player,settlement.corePosition(),new OverviewSnapshotPayload(settlement.corePosition(),List.copyOf(settlement.claimedChunkKeys()),List.of()),overviewEntries(player,settlement));
    }

    private static void handleOpenBuildingManagement(OpenBuildingManagementPayload payload, IPayloadContext context) {
        com.hkin.exiledsettlement.client.BuildingManagementScreen.open(payload);
    }

    private static void handleRenameBuilding(RenameBuildingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        SettlementRecord settlement = data.findByCore(payload.corePosition()).orElse(null);
        if (settlement == null || !data.renameBuilding(settlement, player.getUUID(), payload.buildingId(), payload.name())) {
            SettlementMessages.sendFailure(player, settlement == null ? "CORE_INACTIVE" : "INVALID_BUILDING_NAME", true);
            return;
        }
        sendDetails(player, payload.corePosition());
        sendBuildingManagement(player, settlement, payload.buildingId());
    }

    public static void sendDetails(ServerPlayer player, net.minecraft.core.BlockPos corePosition) {
        SettlementRecord settlement = SettlementSavedData.get(player.serverLevel()).findByCore(corePosition).orElse(null);
        if (settlement == null || !settlement.active() || !settlement.isMember(player.getUUID())) return;
        java.util.List<SettlementDetailsPayload.BuildingEntry> entries = settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .map(building -> entry(player, settlement, building))
                .toList();
        BuildingSnapshotPages.send(player,corePosition,new SettlementDetailsPayload(corePosition,settlement.boundaryRadiusChunks(),List.of()),entries);
    }

    public static void startBuildingManagement(ServerPlayer player, SettlementRecord settlement) {
        if (!settlement.isMember(player.getUUID())) return;
        BuildingSnapshotPages.send(player,settlement.corePosition(),new StartBuildingManagementPayload(settlement.corePosition(),settlement.boundaryRadiusChunks(),List.of()),overviewEntries(player,settlement));
    }

    public static java.util.List<SettlementDetailsPayload.BuildingEntry> overviewEntries(
            ServerPlayer player, SettlementRecord settlement
    ) {
        java.util.List<SettlementDetailsPayload.BuildingEntry> entries = new java.util.ArrayList<>(settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .map(building -> entry(player, settlement, building))
                .toList());
        entries.addAll(settlement.blueprints().stream()
                .map(blueprint -> blueprintEntry(player.serverLevel(), settlement, blueprint))
                .toList());
        return List.copyOf(entries);
    }

    private static SettlementDetailsPayload.BuildingEntry entry(
            ServerPlayer player, SettlementRecord settlement, BuildingRecord building
    ) {
        boolean warehouse = building.type().isStorage() || building.type()==BuildingType.MINE;
        int productionCount = building.type() == BuildingType.CHURCH
                ? com.hkin.exiledsettlement.settlement.PriestProductionService.stockCount(player.serverLevel(), settlement,
                        com.hkin.exiledsettlement.building.BrewingRecipes.stack(building.productionId()))
                : building.type() == BuildingType.PASTURE_AREA
                ? player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(
                        building.minCorner().getX(), building.minCorner().getY(), building.minCorner().getZ(),
                        building.maxCorner().getX() + 1, building.maxCorner().getY() + 5,
                        building.maxCorner().getZ() + 1),
                animal -> (building.region()==null || building.region().contains(animal.blockPosition()))
                        && (building.productionSite()==null || building.productionSite().contains(animal.blockPosition()))
                        && com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(building,animal)).size() : 0;
        int productionCapacity = building.type() == BuildingType.CHURCH
                ? com.hkin.exiledsettlement.settlement.PriestProductionService.promised(settlement, building.productionId())
                : building.type() == BuildingType.PASTURE_AREA
                ? BuildingProductionService.animalCapacity(building) : 0;
        CitizenProfession staffingProfession = CitizenProfession.forWorkplace(building.type());
        boolean demolitionWorksite = building.demolitionQueued();
        int preferredWorkers = demolitionWorksite ? (int) settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> building.id().equals(citizen.preferredWorksiteId())).count() : 0;
        int assignedWorkers = (int) settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> demolitionWorksite
                        ? building.id().equals(citizen.targetBuildingId())
                        && citizen.workState() == CitizenWorkState.TO_DEMOLITION
                        : building.id().equals(citizen.workBuildingId()))
                .count();
        java.util.List<CitizenRecord> assignedCitizens = settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> demolitionWorksite
                        ? building.id().equals(citizen.targetBuildingId())
                        && citizen.workState() == CitizenWorkState.TO_DEMOLITION
                        : building.id().equals(citizen.workBuildingId()))
                .toList();
        var siteBounds = new net.minecraft.world.phys.AABB(
                net.minecraft.world.phys.Vec3.atLowerCornerOf(building.minCorner()),
                net.minecraft.world.phys.Vec3.atLowerCornerOf(building.maxCorner().offset(1, 1, 1)))
                .inflate(building.transitionMargin(), 1.0D, building.transitionMargin());
        int workingWorkers = (int) assignedCitizens.stream()
                .filter(citizen -> WorkforceStatusService.working(player.serverLevel(), settlement, citizen)).count();
        int arrivedWorkers = (int) assignedCitizens.stream()
                .filter(citizen -> WorkforceStatusService.arrived(player.serverLevel(), citizen, siteBounds)
                        || WorkforceStatusService.working(player.serverLevel(), settlement, citizen)).count();
        return new SettlementDetailsPayload.BuildingEntry(
                building.id(), building.type(), building.customName(), building.position(), building.facing(),
                building.minCorner(), building.maxCorner(), building.transitionMargin(),
                building.operational(), building.status(), demolitionWorksite
                        ? CitizenWorkService.worksiteWorkerCap() : building.workerCapacity(),
                building.templateVersion(), demolitionWorksite ? preferredWorkers : building.workerTarget(),
                demolitionWorksite ? preferredWorkers : settlement.lockedWorkers(building.id()),
                assignedWorkers, arrivedWorkers, workingWorkers, building.configurationRevision(),
                demolitionWorksite || staffingProfession == null ? -1 : staffingProfession.ordinal(),
                building.worksiteAssignmentMode(),
                Math.toIntExact(settlement.occupantsOf(building.id())),
                warehouse ? WarehouseInventoryService.usedSlots(player.serverLevel(), building) : 0,
                warehouse && (settlement.creativeCoreInstalled()
                        || WarehouseInventoryService.hasCreativeSupply(player.serverLevel(), building)),
                building.demolitionQueued(), building.warehouseCategoryMask(), building.productionId(),
                productionCount, productionCapacity, building.repairPositions().size(), false, 0, 0, building.customTemplate() != null || building.productionSite()!=null, building.region()!=null,0,0,0,0,
                WorksiteDisplayKind.BUILDING, building.type() == BuildingType.CHURCH ? building.brewStockTarget() : 0);
    }

    public static SettlementDetailsPayload.BuildingEntry blueprintEntry(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint
    ) {
        var workCounts=com.hkin.exiledsettlement.terrain.PublicWorkService.counts(level,settlement,blueprint);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        int total = blueprint.terrainProject()==null?template.placements().size():blueprint.terrainProject().steps.size();
        int completed = BlueprintService.completedPlacementCount(level, blueprint);
        if(blueprint.earthworkPlan()!=null) {
            total+=blueprint.earthworkPlan().size();completed+=blueprint.earthworkPlan().completed();
        }
        int preferredWorkers = (int) settlement.citizens().stream().filter(CitizenRecord::alive)
                .filter(citizen -> blueprint.id().equals(citizen.preferredWorksiteId())).count();
        int assignedWorkers = (int) settlement.citizens().stream().filter(CitizenRecord::alive)
                .filter(citizen -> blueprint.id().equals(citizen.targetBlueprintId())).count();
        var siteBounds = new net.minecraft.world.phys.AABB(
                net.minecraft.world.phys.Vec3.atLowerCornerOf(template.minCorner()),
                net.minecraft.world.phys.Vec3.atLowerCornerOf(template.maxCorner().offset(1, 1, 1)))
                .inflate(template.transitionMargin(), 1.0D, template.transitionMargin());
        int arrivedWorkers = (int) settlement.citizens().stream()
                .filter(citizen -> citizen.alive() && blueprint.id().equals(citizen.targetBlueprintId()))
                .filter(citizen -> WorkforceStatusService.arrived(level, citizen, siteBounds)).count();
        int workingWorkers = (int) settlement.citizens().stream()
                .filter(citizen -> citizen.alive() && blueprint.id().equals(citizen.targetBlueprintId()))
                .filter(citizen -> WorkforceStatusService.working(level, settlement, citizen)).count();
        boolean blocked = !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint) || settlement.citizens().stream().filter(CitizenRecord::alive)
                .filter(citizen -> blueprint.id().equals(citizen.targetBlueprintId()))
                .anyMatch(citizen -> !citizen.workFailure().isBlank());
        return new SettlementDetailsPayload.BuildingEntry(
                blueprint.id(), blueprint.type(), blueprint.terrainProject()!=null?blueprint.terrainProject().name:blueprint.customTemplate() == null ? "" : blueprint.customTemplate().name(), blueprint.anchor(), blueprint.facing(),
                template.minCorner(), template.maxCorner(), template.transitionMargin(),
                false, blueprint.cancellationQueued() ? BuildingStatus.CANCELLATION_QUEUED
                        : blocked ? BuildingStatus.CONSTRUCTION_BLOCKED : BuildingStatus.UNDER_CONSTRUCTION,
                blueprint.cancellationQueued() ? CitizenWorkService.worksiteWorkerCap()
                        : (int) settlement.citizens().stream().filter(CitizenRecord::alive)
                        .filter(c -> CitizenWorkService.constructionCapable(c.profession())).count(),
                blueprint.templateVersion(), preferredWorkers, preferredWorkers,
                assignedWorkers, arrivedWorkers, workingWorkers, blueprint.assignmentRevision(), -1,
                blueprint.assignmentMode(),
                0, 0, false, false, 0, BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.AIR),
                0, 0, blueprint.temporaryFootings().size(), true, Math.min(completed, total), total,
                blueprint.customTemplate() != null || blueprint.terrainProject() != null || blueprint.productionSite()!=null,
                blueprint.region()!=null,workCounts.placements(),workCounts.preparation(),workCounts.foundation(),workCounts.suggested(),
                WorksiteDisplayKind.of(settlement, blueprint));
    }

    private static void handleBuildingManagementRequest(
            RequestBuildingManagementPayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!BUILDING_SNAPSHOT_LIMITER.accept(player, player.server.getTickCount(), payload.refreshOnly())) return;
        SettlementRecord settlement = SettlementSavedData.get(player.serverLevel())
                .findByCore(payload.corePosition()).orElse(null);
        if (settlement == null || !settlement.active() || !settlement.isMember(player.getUUID())) {
            SettlementMessages.sendFailure(player, settlement == null || !settlement.active()
                    ? "CORE_INACTIVE" : "NO_PERMISSION", true);
            return;
        }
        sendBuildingManagement(player, settlement, payload.buildingId(), payload.refreshOnly());
    }

    private static void sendBuildingManagement(ServerPlayer player, SettlementRecord settlement, java.util.UUID buildingId) {
        // Action responses refresh an existing screen; they must not reopen a closed screen.
        sendBuildingManagement(player, settlement, buildingId, true);
    }

    private static void sendBuildingManagement(ServerPlayer player, SettlementRecord settlement,
                                               java.util.UUID buildingId, boolean refreshOnly) {
        if (!settlement.active() || !settlement.isMember(player.getUUID())) return;
        BuildingRecord building = settlement.building(buildingId);
        if (building == null || !building.active()) {
            BlueprintRecord blueprint = settlement.blueprint(buildingId);
            if (blueprint == null) return;
            PacketDistributor.sendToPlayer(player, new OpenBuildingManagementPayload(
                    settlement.id(), settlement.corePosition(), blueprintEntry(player.serverLevel(), settlement, blueprint),
                    settlement.toolStockLimit(), settlement.equipmentStockLimit(),
                    java.util.List.of(), worksitePersonnel(settlement, blueprint.id()), refreshOnly));
            return;
        }
        java.util.List<OpenBuildingManagementPayload.InventoryEntry> inventory = (building.type().isStorage() || building.type()==BuildingType.MINE)
                ? WarehouseInventoryService.summarize(player.serverLevel(), building).entrySet().stream()
                .map(item -> new OpenBuildingManagementPayload.InventoryEntry(
                        BuiltInRegistries.ITEM.getKey(item.getKey()), item.getValue(), 0, 0))
                .toList()
                : building.type() == BuildingType.TOWN_HALL
                ? com.hkin.exiledsettlement.settlement.SettlementInventoryLedgerService
                .snapshot(player.serverLevel(), settlement).entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(ResourceLocation::toString)))
                .map(item -> new OpenBuildingManagementPayload.InventoryEntry(item.getKey(), item.getValue(),
                        settlement.producedTotals().getOrDefault(item.getKey(), 0),
                        settlement.consumedTotals().getOrDefault(item.getKey(), 0)))
                .toList()
                : java.util.List.of();
        CitizenProfession staffingProfession = CitizenProfession.forWorkplace(building.type());
        java.util.List<OpenBuildingManagementPayload.PersonnelEntry> personnel = building.demolitionQueued()
                ? worksitePersonnel(settlement, building.id()) : staffingProfession == null
                ? java.util.List.of()
                : settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .map(citizen -> {
                    boolean assignedHere = building.id().equals(citizen.workBuildingId());
                    boolean lockedHere = citizen.hasManualAssignment()
                            && building.id().equals(citizen.manualWorkBuildingId());
                    boolean lockedElsewhere = citizen.hasManualAssignment()
                            && citizen.manualProfession() != CitizenProfession.WORKER && !lockedHere;
                    boolean blocked = lockedHere && (!building.active() || !building.operational()
                            || !citizen.workFailure().isBlank());
                    return new OpenBuildingManagementPayload.PersonnelEntry(
                            citizen.id(), citizen.name(), citizen.profession(), assignedHere,
                            lockedHere, lockedElsewhere, blocked, citizen.manualReleasePending());
                })
                .sorted(java.util.Comparator
                        .comparingInt((OpenBuildingManagementPayload.PersonnelEntry entry) ->
                                entry.lockedHere() ? 0 : entry.assignedHere() ? 1 : 2)
                        .thenComparing(OpenBuildingManagementPayload.PersonnelEntry::name))
                .toList();
        PacketDistributor.sendToPlayer(player, new OpenBuildingManagementPayload(
                settlement.id(), settlement.corePosition(), entry(player, settlement, building),
                settlement.toolStockLimit(), settlement.equipmentStockLimit(), inventory, personnel, refreshOnly));
    }

    private static java.util.List<OpenBuildingManagementPayload.PersonnelEntry> worksitePersonnel(
            SettlementRecord settlement, java.util.UUID worksiteId
    ) {
        return settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> CitizenWorkService.constructionCapable(citizen.profession()))
                .map(citizen -> {
                    boolean assignedHere = worksiteId.equals(citizen.targetBlueprintId())
                            || worksiteId.equals(citizen.targetBuildingId())
                            && citizen.workState() == CitizenWorkState.TO_DEMOLITION;
                    boolean preferredHere = worksiteId.equals(citizen.preferredWorksiteId());
                    return new OpenBuildingManagementPayload.PersonnelEntry(
                            citizen.id(), citizen.name(), citizen.profession(), assignedHere,
                            preferredHere, citizen.preferredWorksiteId() != null && !preferredHere,
                            preferredHere && !citizen.workFailure().isBlank(),
                            preferredHere && WorksiteAssignmentService.releasePending(settlement, citizen));
                })
                .sorted(java.util.Comparator
                        .comparingInt((OpenBuildingManagementPayload.PersonnelEntry entry) ->
                                entry.lockedHere() ? 0 : entry.assignedHere() ? 1 : 2)
                        .thenComparing(OpenBuildingManagementPayload.PersonnelEntry::name))
                .toList();
    }

    private static void handleBuildingAction(BuildingActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        SettlementRecord settlement = data.findByCore(payload.corePosition()).orElse(null);
        BuildingRecord building = settlement == null ? null : settlement.building(payload.buildingId());
        BlueprintRecord blueprint = settlement == null ? null : settlement.blueprint(payload.buildingId());
        boolean validTarget = building != null && building.active() || blueprint != null;
        if (settlement == null || !validTarget || !settlement.isMember(player.getUUID())) {
            SettlementMessages.sendFailure(player, settlement == null ? "CORE_INACTIVE" : "NO_PERMISSION", true);
            return;
        }
        if (payload.action() == BuildingActionPayload.Action.SET_WORKSITE_MODE) {
            if (payload.value() < 0 || payload.value() >= WorksiteAssignmentMode.values().length) {
                SettlementMessages.sendFailure(player, "WORKSITE_UNAVAILABLE", true);
                return;
            }
            var result = WorksiteAssignmentService.setMode(settlement, player.getUUID(), payload.buildingId(),
                    WorksiteAssignmentMode.values()[payload.value()], payload.expectedRevision());
            if (!result.success()) SettlementMessages.sendFailure(player, result.reason(), true);
            else if (Boolean.TRUE.equals(result.value())) data.touch();
            sendDetails(player, settlement.corePosition());
            sendBuildingManagement(player, settlement, payload.buildingId());
            return;
        }
        if (blueprint != null) {
            boolean changed = switch (payload.action()) {
                case CANCEL_CONSTRUCTION -> BlueprintService.cancelConstruction(settlement, blueprint);
                case TOGGLE_GROUND -> com.hkin.exiledsettlement.region.RegionGroundService.toggle(settlement,blueprint.id());
                case SET_WORKSITE_MODE -> false; // Handled transactionally above.
                default -> false;
            };
            if (changed) data.touch();
            startBuildingManagement(player, settlement);
            sendBuildingManagement(player, settlement, blueprint.id());
            return;
        }
        if (payload.action() == BuildingActionPayload.Action.SET_BREW_STOCK_TARGET
                || building.type() == BuildingType.CHURCH && payload.action() == BuildingActionPayload.Action.SET_PRODUCTION) {
            String failure = !building.configurationMatches(payload.expectedRevision()) ? "CONFIGURATION_CONFLICT"
                    : building.type() != BuildingType.CHURCH || building.demolitionQueued() ? "WORKSITE_UNAVAILABLE"
                    : payload.action() == BuildingActionPayload.Action.SET_BREW_STOCK_TARGET
                        ? (payload.value() < 0 || payload.value() > BuildingRecord.MAX_BREW_STOCK_TARGET ? "BREW_TARGET_INVALID" : null)
                        : (!com.hkin.exiledsettlement.building.BrewingRecipes.supported(payload.itemId()) ? "BREW_PRODUCT_INVALID" : null);
            if (failure != null) {
                SettlementMessages.sendFailure(player, failure, true);
                sendBuildingManagement(player, settlement, building.id());
                return;
            }
        }
        boolean changed = switch (payload.action()) {
            case QUEUE_DEMOLITION -> settlement.queueBuildingDemolition(building.id());
            case DEPOSIT_ALL -> depositAll(player, building);
            case WITHDRAW_STACK -> withdraw(player, building, payload.itemId());
            case SET_TOOL_LIMIT -> settlement.setToolStockLimit(payload.value());
            case SET_EQUIPMENT_LIMIT -> settlement.setEquipmentStockLimit(payload.value());
            case TOGGLE_CATEGORY -> payload.value() >= 0 && payload.value() < WarehouseCategory.values().length
                    && building.toggleWarehouseCategory(WarehouseCategory.values()[payload.value()]);
            case SET_PRODUCTION -> {
                yield building.type() == BuildingType.CHURCH
                        ? com.hkin.exiledsettlement.building.BuildingManagementActions.brewingProduct(
                                settlement, building, payload.itemId(), payload.expectedRevision())
                        : com.hkin.exiledsettlement.building.BuildingManagementActions.production(settlement, building, payload.itemId());
            }
            case SET_BREW_STOCK_TARGET -> com.hkin.exiledsettlement.building.BuildingManagementActions.brewingTarget(
                    building, payload.value(), payload.expectedRevision());
            case TOGGLE_GROUND -> com.hkin.exiledsettlement.region.RegionGroundService.toggle(settlement,building.id());
            case CANCEL_CONSTRUCTION -> false;
            case SET_WORKER_TARGET -> data.setBuildingWorkerTarget(
                    settlement, player.getUUID(), building.id(), payload.value(), payload.expectedRevision());
            case SET_WORKSITE_MODE -> false; // Handled transactionally above.
        };
        if (changed) {
            ProfessionAssignmentService.refresh(data, settlement);
            data.touch();
        }
        sendDetails(player, settlement.corePosition());
        sendBuildingManagement(player, settlement, building.id());
    }

    private static boolean depositAll(ServerPlayer player, BuildingRecord warehouse) {
        if ((!warehouse.type().isStorage() && warehouse.type()!=BuildingType.MINE) || !warehouse.operational()) return false;
        boolean changed = false;
        int mainInventorySlots = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < mainInventorySlots; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()
                    || (stack.is(com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get()) && !player.isCreative())) {
                continue;
            }
            ItemStack remaining = WarehouseInventoryService.insert(player.serverLevel(), warehouse, stack);
            if (!ItemStack.matches(stack, remaining)) {
                player.getInventory().setItem(slot, remaining);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean withdraw(ServerPlayer player, BuildingRecord warehouse, ResourceLocation itemId) {
        if ((!warehouse.type().isStorage() && warehouse.type()!=BuildingType.MINE) || !warehouse.operational()) return false;
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null || item == com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get()) return false;
        ItemStack extracted = WarehouseInventoryService.extractItem(
                player.serverLevel(), warehouse, item, item.getDefaultMaxStackSize());
        if (extracted.isEmpty()) return false;
        if (!player.getInventory().add(extracted)) player.drop(extracted, false);
        return true;
    }

    private static void handleBuildingStaff(BuildingStaffPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        SettlementRecord settlement = data.findByCore(payload.corePosition()).orElse(null);
        BuildingRecord building = settlement == null ? null : settlement.building(payload.buildingId());
        BlueprintRecord blueprint = settlement == null ? null : settlement.blueprint(payload.buildingId());
        CitizenRecord citizen = settlement == null ? null : settlement.citizen(payload.citizenId());
        if (settlement != null && (blueprint != null
                || building != null && building.demolitionQueued())) {
            var result = WorksiteAssignmentService.assign(settlement, player.getUUID(), payload.buildingId(),
                    payload.citizenId(), payload.unlock(), payload.expectedRevision());
            if (!result.success()) SettlementMessages.sendFailure(player, result.reason(), true);
            else if (Boolean.TRUE.equals(result.value())) data.touch();
            if (!settlement.isMember(player.getUUID())) return;
            sendDetails(player, settlement.corePosition());
            sendBuildingManagement(player, settlement, payload.buildingId());
            return;
        }
        if (settlement == null || building == null || citizen == null) {
            SettlementMessages.sendFailure(player, "WORKSITE_UNAVAILABLE", true);
            return;
        }
        String failure = com.hkin.exiledsettlement.building.BuildingManagementActions.staff(
                data, settlement, player.getUUID(), building, citizen.id(), payload.unlock(), payload.expectedRevision());
        if (failure != null) SettlementMessages.sendFailure(player, failure, true);
        if (!settlement.isMember(player.getUUID())) return;
        sendDetails(player, settlement.corePosition());
        sendBuildingManagement(player, settlement, building.id());
    }

    private static void handleOpenCitizen(OpenCitizenAssignmentPayload payload, IPayloadContext context) {
        CitizenAssignmentScreen.open(payload);
    }

    private static void handleSetCitizenYsmModel(
            SetCitizenYsmModelPayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        SettlementRecord settlement = data.findById(payload.settlementId()).orElse(null);
        CitizenRecord citizen = settlement == null ? null : settlement.citizen(payload.citizenId());
        if (settlement == null || citizen == null || !settlement.isMember(player.getUUID())) {
            SettlementMessages.sendFailure(player, settlement == null ? "NO_SETTLEMENT" : "NO_PERMISSION", true);
            return;
        }
        if (!CitizenRecord.isValidYsmSelection(payload.modelId(), payload.textureName())) {
            SettlementMessages.sendFailure(player, "INVALID_YSM_MODEL", true);
            CitizenAssignmentService.open(player, settlement, citizen);
            return;
        }
        if (citizen.setYsmModel(payload.modelId(), payload.textureName())) {
            var projection = CitizenProjectionService.findProjection(player.serverLevel(), citizen);
            if (projection != null) {
                projection.setYsmModelSelection(citizen.ysmModelId(), citizen.ysmTextureName());
            }
            data.touch();
        }
        CitizenAssignmentService.open(player, settlement, citizen);
    }

    private static void handleAssignCitizen(AssignCitizenPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        SettlementRecord settlement = data.findById(payload.settlementId()).orElse(null);
        CitizenRecord citizen = settlement == null ? null : settlement.citizen(payload.citizenId());
        if (settlement == null || citizen == null || !settlement.isMember(player.getUUID())) {
            SettlementMessages.sendFailure(player, settlement == null ? "NO_SETTLEMENT" : "NO_PERMISSION", true);
            return;
        }
        BuildingRecord currentLockedBuilding = citizen.manualWorkBuildingId() == null
                ? null : settlement.building(citizen.manualWorkBuildingId());
        BuildingRecord requestedBuilding = payload.workBuildingId() == null
                ? null : settlement.building(payload.workBuildingId());
        BuildingRecord revisionBuilding = payload.clear() ? currentLockedBuilding : requestedBuilding;
        if (revisionBuilding != null && !revisionBuilding.configurationMatches(payload.expectedRevision())) {
            SettlementMessages.sendFailure(player, "CONFIGURATION_CONFLICT", true);
            CitizenAssignmentService.open(player, settlement, citizen);
            return;
        }
        if (!payload.clear() && payload.profession().specialist() && requestedBuilding != null) {
            boolean alreadyLockedHere = requestedBuilding.id().equals(citizen.manualWorkBuildingId());
            if (!requestedBuilding.active() || !requestedBuilding.operational()
                    || !payload.profession().acceptsWorkplace(requestedBuilding.type())
                    || !alreadyLockedHere
                    && settlement.lockedWorkers(requestedBuilding.id()) >= requestedBuilding.capacity()) {
                SettlementMessages.sendFailure(player, "STAFFING_CAPACITY_BLOCKED", true);
                CitizenAssignmentService.open(player, settlement, citizen);
                return;
            }
            int prospectiveLocked = settlement.lockedWorkers(requestedBuilding.id()) + (alreadyLockedHere ? 0 : 1);
            if (requestedBuilding.workerTarget() < prospectiveLocked
                    && !data.setBuildingWorkerTarget(settlement, player.getUUID(), requestedBuilding.id(),
                    prospectiveLocked, payload.expectedRevision())) {
                SettlementMessages.sendFailure(player, "STAFFING_CAPACITY_BLOCKED", true);
                CitizenAssignmentService.open(player, settlement, citizen);
                return;
            }
        }
        boolean changed = payload.clear()
                ? data.requestCitizenManualRelease(settlement, player.getUUID(), citizen.id())
                : data.setCitizenManualAssignment(settlement, player.getUUID(), citizen.id(),
                payload.profession(), payload.workBuildingId());
        if (changed) {
            if (revisionBuilding != null) revisionBuilding.markConfigurationChanged();
            if (currentLockedBuilding != null && currentLockedBuilding != revisionBuilding) {
                currentLockedBuilding.markConfigurationChanged();
            }
        }
        ProfessionAssignmentService.refresh(data, settlement);
        CitizenAssignmentService.open(player, settlement, citizen);
    }
}

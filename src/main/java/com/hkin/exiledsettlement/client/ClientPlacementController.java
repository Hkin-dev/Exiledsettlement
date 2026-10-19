package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintEntrance;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.blueprint.BuildingPlacementRules;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.blueprint.PlacementSessionService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.network.CancelPlacementPayload;
import com.hkin.exiledsettlement.network.PlaceBuildingPayload;
import com.hkin.exiledsettlement.network.PlaceAreaPayload;
import com.hkin.exiledsettlement.network.PlaceRoadPayload;
import com.hkin.exiledsettlement.network.RequestBuildingManagementPayload;
import com.hkin.exiledsettlement.network.RequestOverviewSnapshotPayload;
import com.hkin.exiledsettlement.network.SettlementDetailsPayload;
import com.hkin.exiledsettlement.network.StartBuildingManagementPayload;
import com.hkin.exiledsettlement.network.StartPlacementPayload;
import com.hkin.exiledsettlement.registry.ExiledSettlementTags;
import com.hkin.exiledsettlement.road.RoadService;
import com.hkin.exiledsettlement.road.RoadTier;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ClientPlacementController {
    private static com.hkin.exiledsettlement.blueprint.SchematicDocument.Draft schematicDraft;
    // Shared by native and imported building placement; reset with each new selection.
    private static int schematicOffsetX;
    private static int schematicOffsetZ;
    public static void beginSchematic(com.hkin.exiledsettlement.blueprint.SchematicDocument.Draft draft, BlockPos core, int radius) {
        clear();
        schematicDraft = draft;
        selectedType = draft.type(); selectedTemplateVersion = 1; selectedDisplayTranslationKey = null;
        placementMode = PlacementMode.BUILDING; facing = Direction.NORTH; heightOffset = 0;
        selectTerritory(core); boundaryRadiusChunks = Math.max(1, radius); rebuildTerritory();
        Minecraft minecraft = Minecraft.getInstance();
        enterRtsView(minecraft);
        PacketDistributor.sendToServer(new RequestOverviewSnapshotPayload(core));
        refreshTarget(minecraft);
    }
    public static final KeyMapping ROTATE_KEY = new KeyMapping(
            "key.exiledsettlement.rotate_building",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.exiledsettlement"
    );
    public static final KeyMapping HEIGHT_UP_KEY = new KeyMapping(
            "key.exiledsettlement.height_up", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_UP, "key.categories.exiledsettlement");
    public static final KeyMapping HEIGHT_DOWN_KEY = new KeyMapping(
            "key.exiledsettlement.height_down", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_DOWN, "key.categories.exiledsettlement");
    private static BlockPos displayedTerritoryCore;
    private static net.minecraft.client.multiplayer.ClientLevel displayedTerritoryLevel;
    private static int boundaryRefreshTick;
    private static final int MIN_HEIGHT_OFFSET = -2;
    private static final int MAX_HEIGHT_OFFSET = 3;
    private static final double MAX_CAMERA_HEIGHT = 46.0D;
    private static final double CAMERA_MOVE_SPEED = 0.65D;
    private static final double CAMERA_BOUNDARY_PADDING = 8.0D;
    private static final int OVERVIEW_REFRESH_INTERVAL_TICKS = 40;
    private static final int PREVIEW_WORLD_REFRESH_INTERVAL_TICKS = 5;
    private static final double COMPLETE_PREVIEW_DISTANCE_SQUARED = 96.0D * 96.0D;

    @Nullable
    private static BuildingType selectedType;
    private static int selectedTemplateVersion = 1;
    @Nullable
    private static String selectedDisplayTranslationKey;
    @Nullable
    private static PlacementMode placementMode;
    @Nullable
    private static BlockPos corePosition;
    private static Direction facing = Direction.NORTH;
    @Nullable
    private static BlockPos targetAnchor;
    @Nullable
    private static BlockPos targetMarker;
    @Nullable
    private static BuildingBlueprintTemplate targetTemplate;
    private static boolean targetValid;
    private static Set<BlockPos> targetClearancePositions = Set.of();
    private static com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.Survey targetEarthwork;
    private static com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.Survey cachedEarthwork;
    private static BuildingBlueprintTemplate earthworkPreviewTemplate;
    private static long earthworkPreviewAt;
    private static long nextEarthworkSurveyTick;
    private static Set<BlockPos> targetFillPositions = Set.of();
    private static Set<BlockPos> targetConflictPositions = Set.of();
    @Nullable
    private static Component targetConflictLabel;
    private static Component targetPlacementFailure;
    private static final Map<UUID, BuildingBlueprintTemplate> PLACEMENT_TEMPLATE_CACHE = new LinkedHashMap<>();
    @Nullable
    private static BlockPos clearanceConfirmationAnchor;
    private static Direction clearanceConfirmationFacing = Direction.NORTH;
    private static int clearanceConfirmationCount;
    private static int heightOffset;
    @Nullable
    private static BlockPos roadStart;
    @Nullable
    private static BlockPos areaStart;
    private static RoadTier roadTier = RoadTier.LEVEL_1;
    private static String worksMode="";
    private static com.hkin.exiledsettlement.network.PlanBridgePayload bridgeRequest;
    private static final java.util.List<BlockPos> bridgeRows=new java.util.ArrayList<>(),bridgeCuts=new java.util.ArrayList<>(),bridgeSupports=new java.util.ArrayList<>(),bridgeConflicts=new java.util.ArrayList<>();
    private static boolean bridgeAxis;private static int bridgeWidth,bridgeSuggested;private static Integer bridgeHeight;
    public static void receiveBridgePreview(com.hkin.exiledsettlement.network.BridgePreviewPayload p){
        if(!worksMode.equals("bridge"))return;
        if(p.reset()){bridgeRows.clear();bridgeCuts.clear();bridgeSupports.clear();bridgeConflicts.clear();}bridgeAxis=p.alongX();bridgeWidth=p.width();bridgeRows.addAll(p.rows());bridgeCuts.addAll(p.cuts());bridgeSupports.addAll(p.supports());bridgeConflicts.addAll(p.conflicts());bridgeSuggested=p.suggestedSurface();
        if(bridgeRequest!=null && bridgeRequest.surface()==Integer.MIN_VALUE && bridgeSuggested!=Integer.MIN_VALUE)bridgeRequest=new com.hkin.exiledsettlement.network.PlanBridgePayload(bridgeRequest.first(),bridgeRequest.second(),bridgeRequest.alongX(),bridgeSuggested);
    }
    public static void changeBridge(int height,boolean rotate){
        if(bridgeRequest==null)return;
        bridgeRequest=new com.hkin.exiledsettlement.network.PlanBridgePayload(bridgeRequest.first(),bridgeRequest.second(),rotate?!bridgeRequest.alongX():bridgeRequest.alongX(),bridgeRequest.surface()+height);
        PacketDistributor.sendToServer(bridgeRequest);Minecraft.getInstance().setScreen(null);
    }
    public static void suggestedBridge(){if(bridgeRequest!=null){
        bridgeRequest=new com.hkin.exiledsettlement.network.PlanBridgePayload(bridgeRequest.first(),bridgeRequest.second(),bridgeRequest.alongX(),Integer.MIN_VALUE);
        PacketDistributor.sendToServer(bridgeRequest);Minecraft.getInstance().setScreen(null);
    }}
    public static void editBridge(){roadStart=null;bridgeHeight=null;bridgeRequest=null;bridgeRows.clear();bridgeCuts.clear();bridgeSupports.clear();bridgeConflicts.clear();}

    public static void beginWorks(com.hkin.exiledsettlement.network.WorksSelectionPayload p){
        Minecraft.getInstance().setScreen(null);
        beginRoad(p.core(),RoadTier.LEVEL_1,p.radius());worksMode=p.mode();
    }
    public static void previewWorks(com.hkin.exiledsettlement.network.WorksPreviewPayload p){
        var game=Minecraft.getInstance();
        if(worksMode.equals("bridge")){game.setScreen(new BridgePlanningScreen(p));return;}
        if(!p.valid()){
            game.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes->{
                PacketDistributor.sendToServer(new com.hkin.exiledsettlement.network.ConfirmWorksPayload(p.token(),false));
                game.setScreen(null);clear();
            },Component.literal("无法安排工程"),Component.literal(p.description()),Component.literal("返回"),Component.literal("取消")));return;
        }
        game.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes->{
            PacketDistributor.sendToServer(new com.hkin.exiledsettlement.network.ConfirmWorksPayload(p.token(),yes));
            game.setScreen(null); if(!yes)clear();
        },Component.literal("确认公共工程"),Component.literal(p.description())));
    }
    @Nullable
    private static ArmorStand rtsCamera;
    @Nullable
    private static Entity previousCamera;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static double cameraHeight = 26.0D;
    private static float cameraYaw;
    private static float cameraPitch = 68.0F;
    private static float preservedPlayerYaw;
    private static float preservedPlayerPitch;
    private static boolean pendingMouseGrab;
    private static Set<Long> claimedChunks = Set.of();
    private static Set<Long> ownedChunks = Set.of();
    private static List<TerritoryOutline.Edge> territoryEdges = List.of();
    private static int boundaryRadiusChunks = SettlementRecord.DEFAULT_BOUNDARY_RADIUS_CHUNKS;
    private static List<SettlementDetailsPayload.BuildingEntry> managementBuildings = List.of();
    private static final Map<UUID, FuturePreviewCache> FUTURE_PREVIEW_CACHE = new LinkedHashMap<>();
    private static FuturePreviewMode futurePreviewMode = FuturePreviewMode.COMPLETE;
    private static int overviewTick;
    private static int previewRefreshCursor;
    @Nullable
    private static SettlementDetailsPayload.BuildingEntry hoveredBuilding;

    private ClientPlacementController() {
    }

    public static void begin(StartPlacementPayload payload) {
        schematicDraft = null;
        schematicOffsetX = 0;
        schematicOffsetZ = 0;
        clearanceConfirmationAnchor = null;
        BuildingType type = payload.buildingType();
        BlockPos core = payload.corePosition();
        int boundaryRadius = payload.boundaryRadiusChunks();
        selectedType = type;
        selectedTemplateVersion = Math.max(1, payload.templateVersion());
        placementMode = BlueprintTemplates.flexibleProduction(type, selectedTemplateVersion)
                ? PlacementMode.AREA : PlacementMode.BUILDING;
        selectedDisplayTranslationKey = payload.displayTranslationKey();
        selectTerritory(core);
        boundaryRadiusChunks = Math.max(1, boundaryRadius);
        rebuildTerritory();
        replaceManagedBuildings(payload.blueprints());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            facing = minecraft.player.getDirection();
        }
        heightOffset = 0;
        areaStart = null;
        enterRtsView(minecraft);
        refreshTarget(minecraft);
    }

    public static void beginRoad(BlockPos core, RoadTier tier, int boundaryRadius) {
        schematicDraft = null;
        worksMode="";
        placementMode = PlacementMode.ROAD;
        selectedType = null;
        selectTerritory(core);
        boundaryRadiusChunks = Math.max(1, boundaryRadius);
        rebuildTerritory();
        managementBuildings = List.of();
        ClientRegionCache.clear();
        FUTURE_PREVIEW_CACHE.clear();
        roadTier = tier;
        roadStart = null;
        areaStart = null;
        heightOffset = 0;
        Minecraft minecraft = Minecraft.getInstance();
        enterRtsView(minecraft);
        PacketDistributor.sendToServer(new RequestOverviewSnapshotPayload(corePosition));
        refreshTarget(minecraft);
    }

    public static void beginManagement(StartBuildingManagementPayload payload) {
        schematicDraft = null;
        placementMode = PlacementMode.MANAGEMENT;
        selectedType = null;
        selectTerritory(payload.corePosition());
        boundaryRadiusChunks = Math.max(1, payload.boundaryRadiusChunks());
        rebuildTerritory();
        if (active()) replaceManagedBuildings(payload.buildings());
        hoveredBuilding = null;
        roadStart = null;
        areaStart = null;
        Minecraft minecraft = Minecraft.getInstance();
        enterRtsView(minecraft);
        refreshTarget(minecraft);
    }

    private static UUID summarySnapshot;
    private static final List<SettlementDetailsPayload.BuildingEntry> summaryBuffer=new java.util.ArrayList<>();
    public static void receiveBuildingPage(com.hkin.exiledsettlement.network.BuildingSummaryPagePayload p) {
        if(!p.core().equals(corePosition) && !p.core().equals(displayedTerritoryCore))return;
        if(p.offset()==0){summarySnapshot=p.snapshot();summaryBuffer.clear();}
        if(!p.snapshot().equals(summarySnapshot) || p.offset()!=summaryBuffer.size())return;
        summaryBuffer.addAll(p.entries());
        if(summaryBuffer.size()>=p.total()) {
            var buildings=List.copyOf(summaryBuffer);
            SettlementCoreScreen.receiveBuildings(p.core(),buildings);
            replaceManagedBuildings(buildings);
            summaryBuffer.clear();summarySnapshot=null;
        }
    }
    public static void updateManagedBuilding(SettlementDetailsPayload.BuildingEntry updated) {
        replaceManagedBuildings(managementBuildings.stream()
                .map(entry -> entry.id().equals(updated.id()) ? updated : entry)
                .toList());
        if (hoveredBuilding != null && hoveredBuilding.id().equals(updated.id())) hoveredBuilding = updated;
    }

    public static void replaceManagedBuildings(List<SettlementDetailsPayload.BuildingEntry> buildings) {
        managementBuildings = List.copyOf(buildings);
        PLACEMENT_TEMPLATE_CACHE.clear();
        for(var building:managementBuildings)if(!building.serverDefinedGeometry()) {
            try { PLACEMENT_TEMPLATE_CACHE.put(building.id(),managedBlueprintTemplate(building)); }
            catch(RuntimeException unavailable) { /* Special markers are reserved at their actual point below. */ }
        }
        Set<UUID> activeBlueprints = managementBuildings.stream()
                .filter(SettlementDetailsPayload.BuildingEntry::blueprint)
                .map(SettlementDetailsPayload.BuildingEntry::id)
                .collect(java.util.stream.Collectors.toSet());
        FUTURE_PREVIEW_CACHE.keySet().removeIf(id -> !activeBlueprints.contains(id));
        for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
            if (!building.blueprint()) continue;
            FuturePreviewCache cached = FUTURE_PREVIEW_CACHE.get(building.id());
            PreviewIdentity identity = PreviewIdentity.of(building);
            if (cached == null || !cached.identity.equals(identity)) {
                try {
                    BuildingBlueprintTemplate template = managedBlueprintTemplate(building);
                    List<BlueprintPlacement> finalPlacements = template.placements().stream()
                            .filter(placement -> placement.mode() != BlueprintPlacementMode.CLEARANCE)
                            .filter(placement -> !placement.state().isAir())
                            .toList();
                    FUTURE_PREVIEW_CACHE.put(building.id(), new FuturePreviewCache(
                            identity, template, finalPlacements, new ArrayList<>(finalPlacements)));
                } catch (RuntimeException exception) {
                    FUTURE_PREVIEW_CACHE.put(building.id(), new FuturePreviewCache(
                            identity, null, List.of(), new ArrayList<>()));
                }
            } else if (cached.knownProgress != building.constructionProgress()) {
                cached.knownProgress = building.constructionProgress();
                cached.dirty = true;
            }
        }
        if (hoveredBuilding != null) {
            UUID hoveredId = hoveredBuilding.id();
            hoveredBuilding = managementBuildings.stream()
                    .filter(entry -> entry.id().equals(hoveredId))
                    .findFirst().orElse(null);
        }
    }

    public static void clear() {
        schematicDraft = null;
        schematicOffsetX = 0;
        schematicOffsetZ = 0;
        bridgeRows.clear();bridgeCuts.clear();bridgeSupports.clear();bridgeConflicts.clear();bridgeRequest=null;bridgeHeight=null;worksMode="";
        targetEarthwork=null;cachedEarthwork=null;earthworkPreviewTemplate=null;targetFillPositions=Set.of();earthworkPreviewAt=0;nextEarthworkSurveyTick=0;
        selectedType = null;
        placementMode = null;
        corePosition = null;
        targetAnchor = null;
        targetMarker = null;
        targetTemplate = null;
        targetValid = false;
        targetClearancePositions = Set.of();
        targetConflictPositions = Set.of();
        targetConflictLabel = null;
        targetPlacementFailure = null;
        clearanceConfirmationAnchor = null;
        clearanceConfirmationCount = 0;
        roadStart = null;
        areaStart = null;
        managementBuildings = List.of();
        ClientRegionCache.clear();
        FUTURE_PREVIEW_CACHE.clear();
        overviewTick = 0;
        previewRefreshCursor = 0;
        hoveredBuilding = null;
        leaveRtsView(Minecraft.getInstance());
    }

    public static boolean active() {
        return placementMode != null && corePosition != null;
    }

    public static void continueAfterPlacement(
            boolean road, @Nullable SettlementDetailsPayload.BuildingEntry blueprint
    ) {
        if (!active()) return;
        if (blueprint != null && managementBuildings.stream().noneMatch(entry -> entry.id().equals(blueprint.id()))) {
            replaceManagedBuildings(java.util.stream.Stream.concat(
                    managementBuildings.stream(), java.util.stream.Stream.of(blueprint)).toList());
        }
        if (road && placementMode == PlacementMode.ROAD) roadStart = null;
        if (!road && placementMode == PlacementMode.AREA) areaStart = null;
        refreshTarget(Minecraft.getInstance());
    }

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (pendingMouseGrab && client.player != null && client.level != null && client.screen == null) {
            client.mouseHandler.grabMouse();
            pendingMouseGrab = false;
        }
        if (displayedTerritoryLevel != client.level) {
            if (active()) clear();
            displayedTerritoryLevel = null; displayedTerritoryCore = null;
            claimedChunks = Set.of(); ownedChunks = Set.of(); territoryEdges = List.of();
        }
        if (!active()) {
            if (displayedTerritoryCore != null && client.player != null
                    && SeasonClientConfig.BOUNDARY.get() && ++boundaryRefreshTick % 100 == 0) {
                PacketDistributor.sendToServer(new RequestOverviewSnapshotPayload(displayedTerritoryCore));
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        ensureRtsView(minecraft);
        updateMouseLook(minecraft);
        panCamera(minecraft);
        overviewTick++;
        if (overviewTick % OVERVIEW_REFRESH_INTERVAL_TICKS == 0 && corePosition != null) {
            PacketDistributor.sendToServer(new RequestOverviewSnapshotPayload(corePosition));
        }
        if (overviewTick % PREVIEW_WORLD_REFRESH_INTERVAL_TICKS == 0) {
            refreshOneFuturePreview(minecraft);
        }
        if (minecraft.screen == null) {
            while ((placementMode == PlacementMode.BUILDING || productionAreaSelection()) && ROTATE_KEY.consumeClick()) {
                facing = facing.getClockWise();
                minecraft.player.displayClientMessage(
                        Component.translatable("message.exiledsettlement.placement.rotated", facingName(facing)),
                        true
                );
            }
            while ((placementMode == PlacementMode.BUILDING || productionAreaSelection()) && HEIGHT_UP_KEY.consumeClick()) {
                adjustPlacementHeight(1);
            }
            while ((placementMode == PlacementMode.BUILDING || productionAreaSelection()) && HEIGHT_DOWN_KEY.consumeClick()) {
                adjustPlacementHeight(-1);
            }
            while (minecraft.options.keyDrop.consumeClick()) { }
            while (minecraft.options.keyInventory.consumeClick()) { }
        }
        refreshTarget(minecraft);
    }

    public static void freezePlayerInput(Input input) {
        if (!active()) {
            return;
        }
        input.leftImpulse = 0.0F;
        input.forwardImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    public static void zoom(double scrollDelta) {
        if (!active() || scrollDelta == 0.0D) {
            return;
        }
        if ((placementMode == PlacementMode.BUILDING || productionAreaSelection())
                && net.minecraft.client.gui.screens.Screen.hasAltDown()) {
            Direction direction = Direction.fromYRot(cameraYaw);
            int step = (int)Math.signum(scrollDelta);
            schematicOffsetX += direction.getStepX() * step;
            schematicOffsetZ += direction.getStepZ() * step;
            if (productionAreaSelection() && areaStart != null)
                areaStart = areaStart.relative(direction, step);
            clearanceConfirmationAnchor = null;
            refreshTarget(Minecraft.getInstance());
            return;
        }
        if (placementMode == PlacementMode.BUILDING || productionAreaSelection()) {
            adjustPlacementHeight((int)Math.signum(scrollDelta));
            return;
        }
        // Other overview modes intentionally consume the wheel without changing
        // camera height, camera speed, or any building state.
    }

    private static void adjustPlacementHeight(int delta) {
        int next=Math.clamp(heightOffset+delta,MIN_HEIGHT_OFFSET,MAX_HEIGHT_OFFSET);
        if(productionAreaSelection() && areaStart!=null)areaStart=areaStart.above(next-heightOffset);
        heightOffset=next;
    }

    public static void cycleFuturePreviewMode() {
        if (!active()) return;
        futurePreviewMode = futurePreviewMode.next();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) minecraft.player.displayClientMessage(
                Component.translatable("message.exiledsettlement.future_preview_mode",
                        Component.translatable(futurePreviewMode.translationKey())), true);
    }

    public static void confirm() {
        if (!active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        refreshTarget(minecraft);
        if (placementMode == PlacementMode.MANAGEMENT) {
            if (hoveredBuilding != null && corePosition != null) {
                PacketDistributor.sendToServer(new RequestBuildingManagementPayload(corePosition, hoveredBuilding.id()));
            } else if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("error.exiledsettlement.management_no_building"), true);
            }
            return;
        }
        if (targetAnchor == null || !targetValid) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(targetPlacementFailure != null ? targetPlacementFailure :
                        Component.translatable("error.exiledsettlement.placement_invalid_target"),
                        true
                );
            }
            return;
        }
        if ((placementMode == PlacementMode.BUILDING || placementMode == PlacementMode.AREA)
                && !foundingCampPlacement()
                && !targetClearancePositions.isEmpty()
                && (clearanceConfirmationAnchor == null
                || !clearanceConfirmationAnchor.equals(targetAnchor)
                || clearanceConfirmationFacing != facing
                || clearanceConfirmationCount != targetClearancePositions.size())) {
            clearanceConfirmationAnchor = targetAnchor.immutable();
            clearanceConfirmationFacing = facing;
            clearanceConfirmationCount = targetClearancePositions.size();
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.translatable("message.exiledsettlement.placement.confirm_clearance",
                            clearanceConfirmationCount), true);
            return;
        }
        if (placementMode == PlacementMode.ROAD) {
            if (roadStart == null) {
                roadStart = targetAnchor.immutable();
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable("message.exiledsettlement.road.start_selected"), true);
                }
            } else {
                if(worksMode.equals("bridge")){
                    boolean x=Math.abs(targetAnchor.getX()-roadStart.getX())>=Math.abs(targetAnchor.getZ()-roadStart.getZ());
                    bridgeRequest=new com.hkin.exiledsettlement.network.PlanBridgePayload(roadStart,targetAnchor,x,bridgeHeight==null?Integer.MIN_VALUE:bridgeHeight);
                    PacketDistributor.sendToServer(bridgeRequest);
                }else PacketDistributor.sendToServer(new PlaceRoadPayload(roadStart, targetAnchor));
            }
        } else if (placementMode == PlacementMode.AREA) {
            if (areaStart == null) {
                areaStart = targetAnchor.immutable();
                if (minecraft.player != null) minecraft.player.displayClientMessage(
                        Component.translatable("message.exiledsettlement.area.start_selected"), true);
            } else {
                PacketDistributor.sendToServer(new PlaceAreaPayload(areaStart, targetAnchor, facing));
                clearanceConfirmationAnchor = null;
            }
        } else if (schematicDraft != null) {
            try {
                ProjectionImportScreen.queueSnapshot(schematicDraft.snapshot(targetAnchor, facing));
                if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("gui.exiledsettlement.schematic.uploading"), false);
                clear();
            } catch (java.io.IOException | RuntimeException error) {
                if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(error.getMessage()), true);
            }
        } else {
            PacketDistributor.sendToServer(new PlaceBuildingPayload(targetAnchor, facing));
            clearanceConfirmationAnchor = null;
        }
    }

    public static void cancel() {
        if (!active()) {
            return;
        }
        PacketDistributor.sendToServer(CancelPlacementPayload.INSTANCE);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("message.exiledsettlement.placement.cancelled"),
                    true
            );
        }
        clear();
    }

    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        if (!active()) {
            if (displayedTerritoryLevel == minecraft.level && displayedTerritoryCore != null
                    && SeasonClientConfig.BOUNDARY.get()) {
                PoseStack poses = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
                poses.pushPose(); poses.translate(-camera.x, -camera.y, -camera.z);
                TerritoryBorderRenderer.render(poses, territoryEdges, camera);
                poses.popPose();
            }
            return;
        }
        float red = targetValid ? 0.20F : 1.00F;
        boolean clearanceRequired = targetValid && !targetClearancePositions.isEmpty();
        float green = targetValid ? clearanceRequired ? 0.72F : 1.00F : 0.20F;
        float blue = clearanceRequired ? 0.05F : 0.20F;
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        renderTerritory(poseStack);
        if (placementMode == PlacementMode.MANAGEMENT) {
            for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
                boolean hovered = hoveredBuilding != null && hoveredBuilding.id().equals(building.id());
                float buildingRed = building.demolitionQueued() ? 1.0F : hovered ? 1.0F : 0.20F;
                boolean blocked = building.status() == BuildingStatus.CONSTRUCTION_BLOCKED;
                if (blocked) buildingRed = 1.0F;
                float buildingGreen = building.demolitionQueued() ? 0.25F
                        : blocked ? 0.66F : hovered ? 0.85F : 0.75F;
                float buildingBlue = building.demolitionQueued() ? 0.20F
                        : blocked ? 0.12F : hovered ? 0.20F : 1.0F;
                AABB body = bounds(building);
                LevelRenderer.renderLineBox(poseStack, lines, body.inflate(0.02D),
                        buildingRed, buildingGreen, buildingBlue, hovered ? 1.0F : 0.75F);
                LevelRenderer.renderLineBox(poseStack, lines,
                        body.inflate(building.transitionMargin(), 0.05D, building.transitionMargin()),
                        0.20F, 0.85F, 1.0F, hovered ? 0.75F : 0.30F);
            }
            if(worksMode.equals("bridge")){
                for(var p:bridgeRows){
                    var box=new AABB(p.getX(),p.getY()-1,p.getZ(),p.getX()+(bridgeAxis?1:bridgeWidth),p.getY()+1,p.getZ()+(bridgeAxis?bridgeWidth:1));
                    boolean bank=bridgeRequest!=null && (bridgeAxis?p.getX()<Math.min(bridgeRequest.first().getX(),bridgeRequest.second().getX()) || p.getX()>Math.max(bridgeRequest.first().getX(),bridgeRequest.second().getX()):p.getZ()<Math.min(bridgeRequest.first().getZ(),bridgeRequest.second().getZ()) || p.getZ()>Math.max(bridgeRequest.first().getZ(),bridgeRequest.second().getZ()));
                    LevelRenderer.renderLineBox(poseStack,lines,box,bank?1F:.3F,bank?.8F:.8F,bank?.2F:1F,.9F);
                    for(int side:new int[]{0,bridgeWidth-1}){var rail=p.offset(bridgeAxis?0:side,1,bridgeAxis?side:0);LevelRenderer.renderLineBox(poseStack,lines,new AABB(rail),.3F,.8F,1F,.8F);}
                }
                for(var p:bridgeSupports)LevelRenderer.renderLineBox(poseStack,lines,new AABB(p),1F,.8F,.2F,.8F);
                for(var p:bridgeConflicts)LevelRenderer.renderLineBox(poseStack,lines,new AABB(p).inflate(.04),1F,.1F,.1F,1F);
                for(var p:bridgeCuts)LevelRenderer.renderLineBox(poseStack,lines,new AABB(p),1F,.5F,.1F,.8F);
            }
            buffers.endBatch(RenderType.lines());
            renderFutureBlueprints(minecraft, poseStack, buffers, true);
            for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
                renderBuildingIcon(minecraft, event, poseStack, buffers, building);
            }
            buffers.endBatch();
            poseStack.popPose();
            return;
        }
        if (placementMode == PlacementMode.ROAD) {
            if (targetAnchor != null) {
                BlockPos first = roadStart == null ? targetAnchor : roadStart;
                LevelRenderer.renderLineBox(poseStack, lines,
                        new AABB(Math.min(first.getX(), targetAnchor.getX()), worksMode.equals("fill_pits")?com.hkin.exiledsettlement.terrain.PitFillSurface.top(first):first.getY(),
                                Math.min(first.getZ(), targetAnchor.getZ()),
                                Math.max(first.getX(), targetAnchor.getX()) + 1D, worksMode.equals("fill_pits")?com.hkin.exiledsettlement.terrain.PitFillSurface.top(first):first.getY() + 1D,
                                Math.max(first.getZ(), targetAnchor.getZ()) + 1D).inflate(0.02D),
                        red, green, 0.25F, 0.95F);
            }
            buffers.endBatch(RenderType.lines());
            renderFutureBlueprints(minecraft, poseStack, buffers, true);
            poseStack.popPose();
            return;
        }
        buffers.endBatch(RenderType.lines());
        renderFutureBlueprints(minecraft, poseStack, buffers, true);
        if (placementMode == PlacementMode.AREA && targetTemplate == null) {
            poseStack.popPose();
            return;
        }
        if (targetTemplate == null) {
            poseStack.popPose();
            return;
        }
        renderPlacementAreas(poseStack, buffers, targetTemplate, targetValid);
        renderClearanceTargets(poseStack, buffers, targetClearancePositions);
        if(!targetFillPositions.isEmpty()) {
            var fillLines=buffers.getBuffer(RenderType.lines());
            for(var p:targetFillPositions) LevelRenderer.renderLineBox(poseStack,fillLines,new AABB(p).inflate(0.015D),0.25F,0.7F,1F,0.8F);
            buffers.endBatch(RenderType.lines());
        }
        renderConflictTargets(poseStack, buffers, targetConflictPositions);
        renderFinishedPreview(minecraft, poseStack, buffers, targetTemplate, targetValid);
        renderDoorMarker(poseStack, buffers, targetTemplate);
        poseStack.popPose();
    }

    public static void renderHud(RenderGuiEvent.Post event) {
        if (!active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || placementMode == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2;
        graphics.fill(centerX - 5, centerY, centerX + 6, centerY + 1, 0xFFFFFFFF);
        graphics.fill(centerX, centerY - 5, centerX + 1, centerY + 6, 0xFFFFFFFF);
        boolean showEntrance = placementMode == PlacementMode.BUILDING || placementMode == PlacementMode.AREA;
        int bottom = graphics.guiHeight() - (targetEarthwork!=null ? 110 : showEntrance ? 84 : 72);
        graphics.fill(centerX - 210, bottom - 5, centerX + 210, bottom + (targetEarthwork!=null ? 98 : showEntrance ? 72 : 59), 0xB0101710);
        Component title;
        if (placementMode == PlacementMode.MANAGEMENT) {
            title = hoveredBuilding == null
                    ? Component.translatable("gui.exiledsettlement.management.select_building")
                    : Component.translatable("gui.exiledsettlement.management.hovered",
                    hoveredBuilding.name().isBlank()
                            ? Component.translatable(hoveredBuilding.displayTypeKey())
                            : Component.literal(hoveredBuilding.name()),
                    Component.translatable(hoveredBuilding.status().translationKey()));
        } else if (placementMode == PlacementMode.ROAD) {
            title = Component.translatable(roadStart == null
                    ? "gui.exiledsettlement.road.select_start" : "gui.exiledsettlement.road.select_end", roadTier.level());
            if(!worksMode.isEmpty())title=Component.literal((worksMode.equals("bridge")?"框选桥面（含护栏至少3格宽，首点定高，下方支撑柱自动规划）":worksMode.equals("fill_pits")?"框选填坑（首点选岸边地面定高，再选另一角）":"区域清理（选择地面两角）")
                    +(roadStart==null?"：选择起点":"：选择终点，随后确认服务端清单"));
        } else if (placementMode == PlacementMode.AREA) {
            if (areaStart != null && targetAnchor != null) {
                long width = Math.abs((long)areaStart.getX() - targetAnchor.getX()) + 1;
                long depth = Math.abs((long)areaStart.getZ() - targetAnchor.getZ()) + 1;
                title=Component.translatable("gui.exiledsettlement.area.selection_size",
                        selectedDisplayName(),width,depth,width*depth,areaStart.getY()-1);
            } else {
                title = Component.translatable("gui.exiledsettlement.area.select_start",
                        selectedDisplayName());
                if(productionAreaSelection() && targetAnchor!=null)title=title.copy().append(
                        Component.translatable("gui.exiledsettlement.area.plane_height",targetAnchor.getY()-1));
            }
        } else {
            title = Component.translatable("gui.exiledsettlement.placement.active",
                    selectedDisplayName(), facingName(facing));
        }
        graphics.drawCenteredString(
                minecraft.font, title,
                centerX,
                bottom,
                targetValid ? targetClearancePositions.isEmpty() ? 0xFF66FF66 : 0xFFFFC23D : 0xFFFF6666
        );
        int controlsY = bottom + 14;
        if (showEntrance) {
            Direction entrance = entranceFacing(targetTemplate);
            graphics.drawCenteredString(minecraft.font,
                    Component.translatable("gui.exiledsettlement.placement.entrance_direction",
                            facingName(productionAreaSelection() || entrance == null ? facing : entrance)),
                    centerX, controlsY, 0xFFFFC23D);
            controlsY += 12;
        }
        graphics.drawCenteredString(
                minecraft.font,
                Component.translatable(placementMode == PlacementMode.MANAGEMENT
                        ? "gui.exiledsettlement.management.controls"
                        : placementMode == PlacementMode.ROAD
                        ? "gui.exiledsettlement.road.controls"
                        : placementMode == PlacementMode.AREA
                        ? "gui.exiledsettlement.area.controls" : "gui.exiledsettlement.placement.controls"),
                centerX,
                controlsY,
                0xFFFFFFFF
        );
        if (placementMode == PlacementMode.BUILDING) {
            graphics.drawString(minecraft.font,
                    Component.translatable("gui.exiledsettlement.placement.height", heightOffset),
                    centerX + 98, controlsY + 12, 0xFFFFD27A, false);
        }
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("gui.exiledsettlement.future_preview.mode",
                        Component.translatable(futurePreviewMode.translationKey())),
                centerX, controlsY + 12, futurePreviewMode == FuturePreviewMode.HIDDEN
                        ? 0xFFAAAAAA : 0xFF74E8FF);
        graphics.drawCenteredString(
                minecraft.font,
                productionAreaSelection() ? Component.translatable(targetValid
                        ? "gui.exiledsettlement.area.selection_ready" : "gui.exiledsettlement.area.selection_invalid",
                        selectedType.minimumAreaSize(), BlueprintService.MAX_DYNAMIC_AREA_BLOCKS)
                        : Component.translatable(targetValid && foundingCampPlacement() ? "gui.exiledsettlement.placement.camp_ready"
                        : targetValid ? targetClearancePositions.isEmpty()
                        ? "gui.exiledsettlement.placement.valid" : "gui.exiledsettlement.placement.clearance_required"
                        : "gui.exiledsettlement.placement.invalid",targetClearancePositions.size()),
                centerX,
                controlsY + 24,
                targetValid ? targetClearancePositions.isEmpty() ? 0xFF88FF88 : 0xFFFFC23D : 0xFFFF8888
        );
        if (targetPlacementFailure != null || targetConflictLabel != null) {
            graphics.drawCenteredString(minecraft.font,
                    targetPlacementFailure != null ? targetPlacementFailure : Component.translatable("gui.exiledsettlement.future_preview.conflict", targetConflictLabel),
                    centerX, controlsY + 36, 0xFFFF6666);
        }
        if(targetEarthwork!=null) {
            Component text=targetEarthwork.valid()
                    ? Component.translatable("gui.exiledsettlement.farm_earthwork.summary",targetEarthwork.plan().soilY(),
                        targetEarthwork.plan().digCount(),targetEarthwork.plan().fillCount())
                    : Component.translatable("message.exiledsettlement.farm_earthwork.blocked",targetEarthwork.position().toShortString(),
                        Component.translatable("work_failure.exiledsettlement."+targetEarthwork.reason().toLowerCase(java.util.Locale.ROOT)));
            graphics.drawCenteredString(minecraft.font,text,centerX,controlsY+48,targetEarthwork.valid()?0xFF74CFFF:0xFFFF8888);
            graphics.drawCenteredString(minecraft.font,Component.translatable("gui.exiledsettlement.farm_earthwork.tools",
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.DIG_HEIGHT.get(),
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.FILL_DEPTH.get()),centerX,controlsY+60,0xFFDDDDDD);
        }
    }

    private static void refreshTarget(Minecraft minecraft) {
        if (!active() || minecraft.player == null || minecraft.level == null || placementMode == null) {
            targetAnchor = null;
            targetMarker = null;
            targetTemplate = null;
            targetValid = false;
            return;
        }
        targetClearancePositions = Set.of();
        targetFillPositions=Set.of();
        targetEarthwork=null;
        targetConflictPositions = Set.of();
        targetConflictLabel = null;
        targetPlacementFailure = null;
        Entity camera = minecraft.getCameraEntity();
        CursorRay cursorRay = cursorRay(minecraft, camera);
        if (placementMode == PlacementMode.MANAGEMENT) {
            targetTemplate = null;
            targetMarker = null;
            hoveredBuilding = selectManagedBuilding(cursorRay);
            targetAnchor = hoveredBuilding == null ? null : hoveredBuilding.position();
            targetValid = hoveredBuilding != null;
            return;
        }
        if(worksMode.equals("fill_pits") && roadStart!=null) {
            targetAnchor=cursorRay==null?null:com.hkin.exiledsettlement.terrain.PitFillSurface.corner(cursorRay.start(),cursorRay.end(),roadStart);
            targetTemplate=null;targetMarker=null;
            targetValid=targetAnchor!=null && ownsChunk(new ChunkPos(targetAnchor))
                && ((long)Math.abs(targetAnchor.getX()-roadStart.getX())+1)*(Math.abs((long)targetAnchor.getZ()-roadStart.getZ())+1)<=1024;
            return;
        }
        if(productionAreaSelection() && areaStart!=null) {
            targetAnchor=cursorRay==null?null:com.hkin.exiledsettlement.blueprint.AreaSelectionPlane.corner(
                    cursorRay.start(),cursorRay.end(),areaStart.getY());
            if (targetAnchor != null) targetAnchor = targetAnchor.offset(schematicOffsetX, 0, schematicOffsetZ);
            refreshAreaTemplate(minecraft);
            return;
        }
        HitResult hit = cursorRay == null || minecraft.level == null ? null : (selectedType!=null || worksMode.isEmpty() || worksMode.equals("fill_pits"))
                ? com.hkin.exiledsettlement.terrain.PitFillSurface.pick(minecraft.level,cursorRay.start(),cursorRay.end(),camera) : minecraft.level.clip(new ClipContext(
                cursorRay.start(), cursorRay.end(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() == HitResult.Type.MISS) {
            targetAnchor = null;
            targetMarker = null;
            targetTemplate = null;
            targetValid = false;
            hoveredBuilding = null;
            return;
        }
        if (placementMode == PlacementMode.ROAD) {
            targetAnchor = blockHit.getBlockPos().immutable();
            targetTemplate = null;
            targetMarker = null;
            targetValid = validateRoadTarget(minecraft, targetAnchor)
                    && (!worksMode.equals("fill_pits") || com.hkin.exiledsettlement.terrain.PitFillSurface.ground(minecraft.level,targetAnchor));
        } else if (placementMode == PlacementMode.AREA && selectedType != null) {
            targetAnchor = blockHit.getBlockPos().above().offset(0, heightOffset, 0);
            if (productionAreaSelection()) targetAnchor = targetAnchor.offset(schematicOffsetX, 0, schematicOffsetZ);
            if (areaStart == null) {
                targetTemplate = null;
                targetMarker = null;
                targetValid = validateAreaAnchor(minecraft, targetAnchor);
            } else {
                targetAnchor = new BlockPos(targetAnchor.getX(), areaStart.getY(), targetAnchor.getZ());
                refreshAreaTemplate(minecraft);
            }
        } else if (selectedType != null) {
            targetAnchor = blockHit.getBlockPos().above().offset(0, heightOffset, 0);
            targetAnchor = targetAnchor.offset(schematicOffsetX, 0, schematicOffsetZ);
            targetTemplate = schematicDraft != null ? schematicDraft.template(targetAnchor, facing) : BlueprintTemplates.create(
                    selectedType, targetAnchor, facing, selectedTemplateVersion);
            if(schematicDraft == null && !foundingCampPlacement())targetTemplate=com.hkin.exiledsettlement.blueprint.ConstructionWorkArea.template(targetTemplate);
            targetValid = validateClientSite(minecraft, targetTemplate);
            targetMarker = null;
        }
    }

    private static void refreshAreaTemplate(Minecraft minecraft) {
        targetMarker=null;targetTemplate=null;targetValid=false;
        if(targetAnchor==null || areaStart==null || selectedType==null)return;
        long width=Math.abs((long)areaStart.getX()-targetAnchor.getX())+1;
        long depth=Math.abs((long)areaStart.getZ()-targetAnchor.getZ())+1;
        int minimum=selectedType.minimumAreaSize();
        int maximum=BlueprintService.MAX_DYNAMIC_AREA_BLOCKS;
        if(width<minimum || depth<minimum) {
            targetPlacementFailure=Component.translatable("gui.exiledsettlement.area.minimum_size",
                    selectedDisplayName(),minimum);
            return;
        }
        if(width>maximum || depth>maximum || width*depth>maximum) {
            targetPlacementFailure=Component.translatable("error.exiledsettlement.area_too_large");
            return;
        }
        targetTemplate=com.hkin.exiledsettlement.blueprint.ConstructionWorkArea.template(
                BlueprintTemplates.createArea(selectedType,areaStart,targetAnchor,facing,selectedTemplateVersion));
        targetValid=validateClientSite(minecraft,targetTemplate);
    }

    private static Component selectedDisplayName() {
        if (schematicDraft != null) return Component.literal(schematicDraft.name());
        if (selectedDisplayTranslationKey != null && !selectedDisplayTranslationKey.isBlank()) {
            return Component.translatable(selectedDisplayTranslationKey);
        }
        return selectedType == null ? Component.empty() : Component.translatable(selectedType.translationKey());
    }

    @Nullable
    private static SettlementDetailsPayload.BuildingEntry selectManagedBuilding(@Nullable CursorRay ray) {
        if (ray == null) return null;
        Vec3 start = ray.start();
        Vec3 end = ray.end();
        SettlementDetailsPayload.BuildingEntry selected = null;
        double selectedDistance = Double.MAX_VALUE;
        for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
            AABB buildingBounds = bounds(building).inflate(0.35D);
            Vec3 iconCenter = buildingIconPosition(building);
            AABB iconBounds = new AABB(iconCenter.x - 1.25D, iconCenter.y - 1.25D, iconCenter.z - 1.25D,
                    iconCenter.x + 1.25D, iconCenter.y + 1.25D, iconCenter.z + 1.25D);
            java.util.Optional<Vec3> hit = buildingBounds.clip(start, end);
            java.util.Optional<Vec3> iconHit = iconBounds.clip(start, end);
            double distance = hit.map(start::distanceToSqr).orElse(Double.MAX_VALUE);
            distance = Math.min(distance, iconHit.map(start::distanceToSqr).orElse(Double.MAX_VALUE));
            if (distance < selectedDistance) {
                selectedDistance = distance;
                selected = building;
            }
        }
        return selected;
    }

    @Nullable
    private static BlockPos findClientMarker(Minecraft minecraft, BuildingBlueprintTemplate template) {
        if (minecraft.level == null || corePosition == null) return null;
        int minX = template.minCorner().getX() - 2;
        int maxX = template.maxCorner().getX() + 2;
        int minZ = template.minCorner().getZ() - 2;
        int maxZ = template.maxCorner().getZ() + 2;
        java.util.ArrayList<BlockPos> perimeter = new java.util.ArrayList<>();
        int baseY = template.anchor().getY();
        for (int x = minX; x <= maxX; x++) {
            perimeter.add(new BlockPos(x, baseY, minZ));
            perimeter.add(new BlockPos(x, baseY, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            perimeter.add(new BlockPos(minX, baseY, z));
            perimeter.add(new BlockPos(maxX, baseY, z));
        }
        perimeter.sort(java.util.Comparator.comparingDouble(position -> position.distSqr(template.anchor())));
        ChunkPos coreChunk = new ChunkPos(corePosition);
        for (BlockPos base : perimeter) {
            for (int dy = -4; dy <= 6; dy++) {
                BlockPos candidate = base.offset(0, dy, 0);
                ChunkPos chunk = new ChunkPos(candidate);
                if (!ownsChunk(chunk)
                        || !minecraft.level.hasChunkAt(candidate)) continue;
                if (minecraft.level.getBlockState(candidate).isAir()
                        && minecraft.level.getBlockState(candidate.below())
                        .isFaceSturdy(minecraft.level, candidate.below(), Direction.UP)) return candidate;
            }
        }
        return null;
    }

    private static boolean foundingCampPlacement() {
        return selectedType==BuildingType.STARTER_CAMP && selectedTemplateVersion>=2;
    }

    private static boolean validateClientSite(Minecraft minecraft, BuildingBlueprintTemplate template) {
        if (minecraft.player == null || minecraft.level == null || corePosition == null) {
            return false;
        }
        if (template.type()==BuildingType.STARTER_CAMP && selectedTemplateVersion>=2) {
            var plan=com.hkin.exiledsettlement.blueprint.FoundingCampPlacementRules.plan(minecraft.level,template);
            if(!plan.valid()) return placementFailure(minecraft,plan.failurePosition(),plan.reason());
            targetClearancePositions=plan.clears();
            targetFillPositions=plan.fills();
            return true;
        }
        Set<BlockPos> clearable = new LinkedHashSet<>();
        var layout=template.placementLayout();
        for(var other:managementBuildings) {
            if(other.status()==BuildingStatus.CANCELLATION_QUEUED)continue;
            if(other.blueprint() && other.constructionProgress()==0
                    && com.hkin.exiledsettlement.decoration.DecorationCatalog.ENTRIES.values().stream().anyMatch(e -> e.type()==other.type()))continue;
            BuildingBlueprintTemplate existing=PLACEMENT_TEMPLATE_CACHE.get(other.id());
            BlockPos conflict=existing==null ? (other.serverDefinedGeometry()?null:
                    layout.reserves(other.position())?other.position():null) : layout.conflict(existing.placementLayout());
            if(other.position().equals(template.anchor()))conflict=template.anchor();
            if(conflict!=null) {
                targetConflictLabel=other.name().isBlank()?Component.translatable(other.displayTypeKey()):Component.literal(other.name());
                targetConflictPositions=Set.of(conflict.immutable());
                targetPlacementFailure=Component.translatable("gui.exiledsettlement.placement.structure_conflict",
                        targetConflictLabel,conflict.toShortString());
                return false;
            }
        }
        for (BlueprintPlacement placement : template.placements()) {
            BlockPos position=placement.position();
            if(placement.mode()==BlueprintPlacementMode.CLEARANCE && !BuildingPlacementRules.requiresClearance(template,position))continue;
            if(!ownsChunk(new ChunkPos(position)))return placementFailure(minecraft,position,"OUTSIDE_SETTLEMENT_BOUNDARY");
            BuildingPlacementRules.Cell result=BuildingPlacementRules.checkCell(minecraft.level,template,placement);
            if(!result.valid())return placementFailure(minecraft,position,result.reason());
            if(result.clearable())clearable.add(position.immutable());
        }
        targetClearancePositions=Set.copyOf(clearable);
        return true;
    }

    private static boolean placementFailure(Minecraft minecraft,BlockPos position,String reason) {
        targetConflictPositions=Set.of(position.immutable());
        Component block=minecraft.level!=null && minecraft.level.hasChunkAt(position)
                ? Component.translatable(minecraft.level.getBlockState(position).getBlock().getDescriptionId())
                : Component.translatable("gui.exiledsettlement.placement.unknown_block");
        targetPlacementFailure=Component.translatable("gui.exiledsettlement.placement.block_reason",
                Component.translatable("error.exiledsettlement."+reason.toLowerCase(java.util.Locale.ROOT)),
                position.toShortString(),block);
        return false;
    }

    @Nullable
    private static SettlementDetailsPayload.BuildingEntry conflictingFutureBlueprintFootprint(BlockPos p) {
        for(var b:managementBuildings) {
            if(b.status()==BuildingStatus.CANCELLATION_QUEUED) continue;
            if(p.getX()>=b.minCorner().getX() && p.getX()<=b.maxCorner().getX()
                    && p.getZ()>=b.minCorner().getZ() && p.getZ()<=b.maxCorner().getZ()) return b;
        }
        return null;
    }

    @Nullable
    private static SettlementDetailsPayload.BuildingEntry conflictingFutureBlueprint(BlockPos position) {
        for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
            if (!building.blueprint() || building.status() == BuildingStatus.CANCELLATION_QUEUED) continue;
            FuturePreviewCache cache = FUTURE_PREVIEW_CACHE.get(building.id());
            if (cache != null && cache.finalPositions.contains(position)) return building;
        }
        return null;
    }

    private static boolean validateRoadTarget(Minecraft minecraft, BlockPos position) {
        if (minecraft.level == null || corePosition == null || !minecraft.level.hasChunkAt(position)) {
            return false;
        }
        ChunkPos coreChunk = new ChunkPos(corePosition);
        ChunkPos targetChunk = new ChunkPos(position);
        return ownsChunk(targetChunk)
                && !minecraft.level.getBlockState(position).isAir();
    }

    private static boolean productionAreaSelection() {
        return placementMode==PlacementMode.AREA && selectedType!=null
                && BlueprintTemplates.flexibleProduction(selectedType,selectedTemplateVersion);
    }

    private static boolean validateAreaAnchor(Minecraft minecraft, BlockPos position) {
        if(productionAreaSelection())return minecraft.level!=null && minecraft.level.hasChunkAt(position)
                && ownsChunk(new ChunkPos(position))
                && position.getY()-1>=minecraft.level.getMinBuildHeight() && position.getY()+6<minecraft.level.getMaxBuildHeight();
        if (!validateRoadTarget(minecraft, position.below())) return false;
        return minecraft.level != null && minecraft.level.getFluidState(position.below()).isEmpty();
    }

    private static boolean contains(SettlementDetailsPayload.BuildingEntry entry, BlockPos position) {
        return position.getX() >= entry.minCorner().getX() && position.getX() <= entry.maxCorner().getX()
                && position.getZ() >= entry.minCorner().getZ() && position.getZ() <= entry.maxCorner().getZ()
                && position.getY() >= entry.minCorner().getY() - 2
                && position.getY() <= entry.maxCorner().getY() + 2;
    }

    private static AABB bounds(SettlementDetailsPayload.BuildingEntry entry) {
        return new AABB(entry.minCorner().getX(), entry.minCorner().getY(), entry.minCorner().getZ(),
                entry.maxCorner().getX() + 1.0D, entry.maxCorner().getY() + 1.0D,
                entry.maxCorner().getZ() + 1.0D);
    }

    private static Vec3 buildingIconPosition(SettlementDetailsPayload.BuildingEntry building) {
        return new Vec3((building.minCorner().getX() + building.maxCorner().getX() + 1.0D) * 0.5D,
                building.maxCorner().getY() + 2.5D,
                (building.minCorner().getZ() + building.maxCorner().getZ() + 1.0D) * 0.5D);
    }

    private static void renderBuildingIcon(Minecraft minecraft, RenderLevelStageEvent event, PoseStack poseStack,
                                           MultiBufferSource.BufferSource buffers,
                                           SettlementDetailsPayload.BuildingEntry building) {
        Vec3 position = buildingIconPosition(building);
        poseStack.pushPose();
        poseStack.translate(position.x, position.y, position.z);
        poseStack.mulPose(event.getCamera().rotation());
        float scale = hoveredBuilding != null && hoveredBuilding.id().equals(building.id()) ? 1.15F : 0.90F;
        poseStack.scale(scale, scale, scale);
        ItemStack icon = building.workKind().publicWorks()
                ? new ItemStack(building.workKind().icon()) : buildingIcon(building.type());
        minecraft.getItemRenderer().renderStatic(icon, ItemDisplayContext.GUI,
                0x00F000F0, OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level,
                building.id().hashCode());
        poseStack.popPose();
    }

    private static ItemStack buildingIcon(BuildingType type) {
        return switch (type) {
            case HOUSE, HOUSE_2, HOUSE_3, SPRUCE_HOME, GALLERY_HOME, COURTYARD_HOME, STARTER_CAMP -> new ItemStack(Blocks.RED_BED);
            case ADVANCED_HOUSE_1, ADVANCED_HOUSE_2, ADVANCED_HOUSE_3 -> new ItemStack(Blocks.BRICKS);
            case WAREHOUSE, MARKET -> new ItemStack(Blocks.BARREL);
            case FARM_FIELD -> new ItemStack(Items.WHEAT_SEEDS);
            case PASTURE_AREA -> new ItemStack(Blocks.OAK_FENCE);
            case HUNTING_LODGE -> new ItemStack(Blocks.SMOKER);
            case TOOL_WORKSHOP -> new ItemStack(Blocks.ANVIL);
            case FORESTER_LODGE -> new ItemStack(Blocks.OAK_SAPLING);
            case TREE_FARM -> new ItemStack(Blocks.OAK_LEAVES);
            case TOWN_HALL -> new ItemStack(Blocks.LECTERN);
            case FISHING_HUT -> new ItemStack(Items.COD);
            case MINE -> new ItemStack(Items.IRON_PICKAXE);
            case SAWMILL -> new ItemStack(Blocks.STONECUTTER);
            case SCHOOL -> new ItemStack(Blocks.BOOKSHELF);
            case APARTMENT -> new ItemStack(Blocks.WHITE_BED);
            case BRICK_KILN -> new ItemStack(Blocks.FURNACE);
            case CHURCH -> new ItemStack(Blocks.BREWING_STAND);
            case MELON_FIELD -> new ItemStack(Items.MELON_SEEDS);
            case TAVERN -> new ItemStack(Items.HONEY_BOTTLE);
            case RESTAURANT -> new ItemStack(Items.COOKED_BEEF);
            case LEISURE_GARDEN -> new ItemStack(Blocks.POPPY);
            case LAMP_SMALL, LAMP_ARM, LAMP_DOUBLE -> new ItemStack(Blocks.LANTERN);
            case BENCH -> new ItemStack(Blocks.SPRUCE_STAIRS);
            case FLOWERBED -> new ItemStack(Blocks.FLOWER_POT);
            case SIGNPOST -> new ItemStack(Items.SPRUCE_SIGN);
            case WELL -> new ItemStack(Items.WATER_BUCKET);
            case WOODSHED -> new ItemStack(Blocks.SPRUCE_LOG);
            case WAGON -> new ItemStack(Blocks.CHEST);
        };
    }

    private static Component facingName(Direction direction) {
        return Component.translatable("direction.exiledsettlement." + direction.getName());
    }

    private static void renderTerritory(PoseStack poses) {
        if (corePosition == null) return;
        // Overhead planning always shows its territory; the local preference controls ordinary play.
        TerritoryBorderRenderer.render(poses, territoryEdges, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
    private static void selectTerritory(BlockPos core) {
        if (!core.equals(displayedTerritoryCore) || displayedTerritoryLevel != Minecraft.getInstance().level) claimedChunks = Set.of();
        corePosition = core.immutable();
        displayedTerritoryCore = corePosition;
        displayedTerritoryLevel = Minecraft.getInstance().level;
    }

    private static void rebuildTerritory() {
        BlockPos core = corePosition != null ? corePosition : displayedTerritoryCore;
        if (core == null) return;
        ownedChunks = TerritoryOutline.chunks(new ChunkPos(core), boundaryRadiusChunks, claimedChunks);
        territoryEdges = TerritoryOutline.edges(ownedChunks);
    }

    private static boolean ownsChunk(ChunkPos chunk) { return ownedChunks.contains(chunk.toLong()); }

    public static void receiveOverviewSnapshot(com.hkin.exiledsettlement.network.OverviewSnapshotPayload payload) {
        if (displayedTerritoryLevel != Minecraft.getInstance().level
                || !payload.corePosition().equals(displayedTerritoryCore)) return;
        Set<Long> next = Set.copyOf(payload.claimedChunks());
        if (!next.equals(claimedChunks)) { claimedChunks = next; rebuildTerritory(); }
        replaceManagedBuildings(payload.buildings());
    }

    public static void observeTerritory(BlockPos core, int radius) {
        if (active()) return;
        if (!core.equals(displayedTerritoryCore) || displayedTerritoryLevel != Minecraft.getInstance().level) claimedChunks = Set.of();
        displayedTerritoryCore = core.immutable(); displayedTerritoryLevel = Minecraft.getInstance().level;
        boundaryRadiusChunks = Math.max(1, radius); rebuildTerritory();
        PacketDistributor.sendToServer(new RequestOverviewSnapshotPayload(core));
    }

    public static void toggleBoundary() {
        SeasonClientConfig.BOUNDARY.set(!SeasonClientConfig.BOUNDARY.get());
        SeasonClientConfig.SPEC.save();
    }

    private static void enterRtsView(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || corePosition == null) {
            return;
        }
        if (rtsCamera == null) {
            previousCamera = minecraft.getCameraEntity();
            pendingMouseGrab = false;
            rtsCamera = new ArmorStand(minecraft.level,
                    corePosition.getX() + 0.5D, corePosition.getY() + cameraHeight,
                    corePosition.getZ() - cameraHeight * 0.55D + 0.5D);
            rtsCamera.setInvisible(true);
            rtsCamera.setNoGravity(true);
            cameraX = corePosition.getX() + 0.5D;
            cameraY = corePosition.getY() + cameraHeight;
            cameraZ = corePosition.getZ() - cameraHeight * 0.55D + 0.5D;
            cameraYaw = minecraft.player.getYRot();
            cameraPitch = 68.0F;
            preservedPlayerYaw = minecraft.player.getYRot();
            preservedPlayerPitch = minecraft.player.getXRot();
        }
        updateCameraTransform();
        minecraft.setCameraEntity(rtsCamera);
        if (minecraft.screen == null) minecraft.mouseHandler.grabMouse();
    }

    private static void ensureRtsView(Minecraft minecraft) {
        if (rtsCamera == null) {
            enterRtsView(minecraft);
        } else if (minecraft.getCameraEntity() != rtsCamera) {
            minecraft.setCameraEntity(rtsCamera);
        }
        if (minecraft.screen == null && !minecraft.mouseHandler.isMouseGrabbed()) minecraft.mouseHandler.grabMouse();
    }

    private static void leaveRtsView(Minecraft minecraft) {
        if (rtsCamera == null) return;
        if (minecraft.getCameraEntity() == rtsCamera) {
            minecraft.setCameraEntity(previousCamera != null ? previousCamera : minecraft.player);
        }
        if (rtsCamera != null) {
            rtsCamera.discard();
        }
        rtsCamera = null;
        previousCamera = null;
        if (minecraft.player != null) {
            minecraft.player.setYRot(preservedPlayerYaw);
            minecraft.player.setXRot(preservedPlayerPitch);
            minecraft.player.setYHeadRot(preservedPlayerYaw);
            minecraft.player.setYBodyRot(preservedPlayerYaw);
        }
        pendingMouseGrab = minecraft.player != null && minecraft.level != null;
        if (pendingMouseGrab && minecraft.screen == null) {
            minecraft.mouseHandler.grabMouse();
            pendingMouseGrab = false;
        }
    }

    private static void panCamera(Minecraft minecraft) {
        if (rtsCamera == null || corePosition == null || minecraft.screen != null) {
            return;
        }
        recoverCameraIfObstructed(minecraft);
        double forward = (minecraft.options.keyUp.isDown() ? 1.0D : 0.0D)
                - (minecraft.options.keyDown.isDown() ? 1.0D : 0.0D);
        double right = (minecraft.options.keyRight.isDown() ? 1.0D : 0.0D)
                - (minecraft.options.keyLeft.isDown() ? 1.0D : 0.0D);
        double vertical = (minecraft.options.keyJump.isDown() ? CAMERA_MOVE_SPEED : 0.0D)
                - (minecraft.options.keyShift.isDown() ? CAMERA_MOVE_SPEED : 0.0D);
        OverviewMovementMath.Delta delta = OverviewMovementMath.horizontal(
                cameraYaw, forward, right, CAMERA_MOVE_SPEED);
        double dx = delta.x();
        double dz = delta.z();
        TerritoryOutline.Bounds territory = TerritoryOutline.bounds(ownedChunks);
        double nextX = Math.clamp(cameraX + dx, territory.minX() - CAMERA_BOUNDARY_PADDING,
                territory.maxX() + CAMERA_BOUNDARY_PADDING);
        double nextZ = Math.clamp(cameraZ + dz, territory.minZ() - CAMERA_BOUNDARY_PADDING,
                territory.maxZ() + CAMERA_BOUNDARY_PADDING);
        double maximumStepY = cameraY + 1.0D;
        tryMoveCameraHorizontal(minecraft, nextX, cameraZ, maximumStepY);
        tryMoveCameraHorizontal(minecraft, cameraX, nextZ, maximumStepY);
        double minY = minecraft.level == null ? cameraY
                : minecraft.level.getMinBuildHeight() + 1.0D;
        double maxY = Math.min(corePosition.getY() + MAX_CAMERA_HEIGHT,
                minecraft.level == null ? corePosition.getY() + MAX_CAMERA_HEIGHT
                        : minecraft.level.getMaxBuildHeight() - 2.0D);
        double nextY = Math.max(minY, Math.min(maxY, cameraY + vertical));
        if (cameraSpaceFree(minecraft, cameraX, nextY, cameraZ)) cameraY = nextY;
        updateCameraTransform();
    }

    private static boolean cameraSpaceFree(Minecraft minecraft, double x, double y, double z) {
        if (minecraft.level == null) return false;
        BlockPos center = BlockPos.containing(x, y + 0.9D, z);
        if (!minecraft.level.hasChunkAt(center) || minecraft.level.isOutsideBuildHeight(center)) return false;
        // The overview camera is not granted spectator permissions. A small local
        // collision body keeps downward flight useful near streets and roofs while
        // preventing the detached camera from clipping through solid structures.
        AABB body = new AABB(x - 0.24D, y + 0.05D, z - 0.24D,
                x + 0.24D, y + 1.72D, z + 0.24D);
        if (!minecraft.level.noCollision(rtsCamera, body)) return false;
        for (BlockPos position : BlockPos.betweenClosed(
                BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            if (!minecraft.level.getFluidState(position).isEmpty()) return false;
        }
        return true;
    }

    private static void tryMoveCameraHorizontal(
            Minecraft minecraft, double nextX, double nextZ, double maximumStepY
    ) {
        if (cameraSpaceFree(minecraft, nextX, cameraY, nextZ)) {
            cameraX = nextX;
            cameraZ = nextZ;
            return;
        }
        for (double rise = 0.25D; cameraY + rise <= maximumStepY + 0.001D; rise += 0.25D) {
            if (!cameraSpaceFree(minecraft, nextX, cameraY + rise, nextZ)) continue;
            cameraX = nextX;
            cameraY += rise;
            cameraZ = nextZ;
            return;
        }
    }

    private static void recoverCameraIfObstructed(Minecraft minecraft) {
        if (cameraSpaceFree(minecraft, cameraX, cameraY, cameraZ)) return;
        for (double rise = 0.25D; rise <= 4.0D; rise += 0.25D) {
            if (!cameraSpaceFree(minecraft, cameraX, cameraY + rise, cameraZ)) continue;
            cameraY += rise;
            return;
        }
        cameraX = corePosition.getX() + 0.5D;
        cameraY = corePosition.getY() + cameraHeight;
        cameraZ = corePosition.getZ() - cameraHeight * 0.55D + 0.5D;
    }

    private static void updateCameraTransform() {
        if (rtsCamera == null || corePosition == null) {
            return;
        }
        rtsCamera.setPos(cameraX, cameraY, cameraZ);
        rtsCamera.setYRot(cameraYaw);
        rtsCamera.setXRot(cameraPitch);
        rtsCamera.setYHeadRot(cameraYaw);
        rtsCamera.setYBodyRot(cameraYaw);
        rtsCamera.setOldPosAndRot();
    }

    private static void updateMouseLook(Minecraft minecraft) {
        if (rtsCamera == null || minecraft.player == null || minecraft.screen != null) return;
        float playerYaw = minecraft.player.getYRot();
        float playerPitch = minecraft.player.getXRot();
        cameraYaw += net.minecraft.util.Mth.wrapDegrees(playerYaw - preservedPlayerYaw);
        cameraPitch = net.minecraft.util.Mth.clamp(
                cameraPitch + net.minecraft.util.Mth.wrapDegrees(playerPitch - preservedPlayerPitch), -89.0F, 89.0F);
        minecraft.player.setYRot(preservedPlayerYaw);
        minecraft.player.setXRot(preservedPlayerPitch);
        minecraft.player.setYHeadRot(preservedPlayerYaw);
        minecraft.player.setYBodyRot(preservedPlayerYaw);
        updateCameraTransform();
    }

    @Nullable
    private static CursorRay cursorRay(Minecraft minecraft, @Nullable Entity camera) {
        if (camera == null) return null;
        Vec3 forward = camera.getViewVector(1.0F).normalize();
        Vec3 start = camera.getEyePosition();
        return new CursorRay(start, start.add(forward.scale(PlacementSessionService.MAX_CAMERA_RAY_DISTANCE)));
    }

    private static void renderPlacementAreas(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            BuildingBlueprintTemplate template, boolean valid
    ) {
        VertexConsumer filled = buffers.getBuffer(RenderType.debugFilledBox());
        int minX = template.minCorner().getX();
        int maxX = template.maxCorner().getX();
        int minZ = template.minCorner().getZ();
        int maxZ = template.maxCorner().getZ();
        double y = (productionAreaSelection()?template.anchor().getY():template.minCorner().getY()) + 0.025D;
        int margin = template.transitionMargin();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if(productionAreaSelection()) {
            LevelRenderer.addChainedFilledBoxVertices(poseStack,filled,
                    minX,y,minZ,maxX+1.0D,y+0.10D,maxZ+1.0D,
                    valid?0.08F:1.0F,valid?0.90F:0.15F,valid?0.16F:0.12F,0.48F);
        } else for (int x = minX - margin; x <= maxX + margin; x++) {
            for (int z = minZ - margin; z <= maxZ + margin; z++) {
                boolean body = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                float areaRed = valid ? (body ? 0.08F : 0.36F) : 1.0F;
                float areaGreen = valid ? (body ? 0.90F : 1.0F) : 0.15F;
                float areaBlue = valid ? (body ? 0.16F : 0.64F) : 0.12F;
                float alpha = body ? 0.48F : 0.30F;
                LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                        x, y, z, x + 1.0D, y + 0.10D, z + 1.0D,
                        areaRed, areaGreen, areaBlue, alpha);
            }
        }
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer outlines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, outlines,
                new AABB(minX, y, minZ, maxX + 1.0D, y + 0.14D, maxZ + 1.0D),
                valid ? 0.10F : 1.0F, valid ? 1.0F : 0.18F, 0.18F, 1.0F);
        LevelRenderer.renderLineBox(poseStack, outlines,
                new AABB(minX - margin, y - 0.01D, minZ - margin,
                        maxX + margin + 1.0D, y + 0.16D, maxZ + margin + 1.0D),
                valid ? 0.50F : 1.0F, valid ? 1.0F : 0.18F, valid ? 0.72F : 0.18F, 0.95F);
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderClearanceTargets(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers, Set<BlockPos> positions
    ) {
        if (positions.isEmpty()) return;
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        VertexConsumer filled = buffers.getBuffer(RenderType.debugFilledBox());
        for (BlockPos position : positions) {
            LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                    position.getX() + 0.03D, position.getY() + 0.03D, position.getZ() + 0.03D,
                    position.getX() + 0.97D, position.getY() + 0.97D, position.getZ() + 0.97D,
                    1.0F, 0.58F, 0.03F, 0.42F);
        }
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos position : positions) {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(position).inflate(0.025D),
                    1.0F, 0.66F, 0.05F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderConflictTargets(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers, Set<BlockPos> positions
    ) {
        if (positions.isEmpty()) return;
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        VertexConsumer filled = buffers.getBuffer(RenderType.debugFilledBox());
        for (BlockPos position : positions) {
            LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                    position.getX() + 0.02D, position.getY() + 0.02D, position.getZ() + 0.02D,
                    position.getX() + 0.98D, position.getY() + 0.98D, position.getZ() + 0.98D,
                    1.0F, 0.08F, 0.04F, 0.58F);
        }
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos position : positions) {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(position).inflate(0.035D),
                    1.0F, 0.12F, 0.05F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void refreshOneFuturePreview(Minecraft minecraft) {
        if (minecraft.level == null || FUTURE_PREVIEW_CACHE.isEmpty()) return;
        List<FuturePreviewCache> caches = FUTURE_PREVIEW_CACHE.values().stream()
                .filter(cache -> cache.template != null)
                .toList();
        if (caches.isEmpty()) return;
        FuturePreviewCache cache = caches.get(Math.floorMod(previewRefreshCursor++, caches.size()));
        List<BlueprintPlacement> missing = new ArrayList<>();
        for (BlueprintPlacement placement : cache.finalPlacements) {
            if (!minecraft.level.hasChunkAt(placement.position())) {
                missing.add(placement);
                continue;
            }
            BlockState current = minecraft.level.getBlockState(placement.position());
            boolean satisfied = placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    ? !current.isAir() : BlueprintService.matchesPlacedState(current, placement.state());
            if (!satisfied) missing.add(placement);
        }
        cache.missingPlacements = List.copyOf(missing);
        cache.dirty = false;
    }

    private static BuildingBlueprintTemplate managedBlueprintTemplate(
            SettlementDetailsPayload.BuildingEntry entry
    ) {
        if(entry.terrainRegion()) {
            var region=ClientRegionCache.get(entry.id());
            if(region!=null)return region.template();
            return new BuildingBlueprintTemplate(entry.type(),entry.position(),entry.facing(),entry.maxCorner().getX()-entry.minCorner().getX()+1,entry.maxCorner().getZ()-entry.minCorner().getZ()+1,entry.maxCorner().getY()-entry.minCorner().getY()+1,entry.minCorner(),entry.maxCorner(),0,java.util.List.of());
        }
        if (entry.serverDefinedGeometry()) {
            return new BuildingBlueprintTemplate(entry.type(), entry.position(), entry.facing(),
                    entry.maxCorner().getX() - entry.minCorner().getX() + 1,
                    entry.maxCorner().getZ() - entry.minCorner().getZ() + 1,
                    entry.maxCorner().getY() - entry.minCorner().getY() + 1,
                    entry.minCorner(), entry.maxCorner(), entry.transitionMargin(), java.util.List.of());
        }
        if(BlueprintTemplates.flexibleProduction(entry.type(),entry.templateVersion())) {
            int y=entry.position().getY();
            return BlueprintTemplates.createArea(entry.type(),
                    new BlockPos(entry.minCorner().getX(),y,entry.minCorner().getZ()),
                    new BlockPos(entry.maxCorner().getX(),y,entry.maxCorner().getZ()),
                    entry.facing(),entry.templateVersion());
        }
        if (!entry.type().isDynamicArea() || BlueprintTemplates.fixedProduction(entry.type(), entry.templateVersion())) {
            return BlueprintTemplates.create(entry.type(), entry.position(), entry.facing(), entry.templateVersion());
        }
        int y = entry.maxCorner().getY();
        return BlueprintTemplates.createArea(entry.type(),
                new BlockPos(entry.minCorner().getX(), y, entry.minCorner().getZ()),
                new BlockPos(entry.maxCorner().getX(), y, entry.maxCorner().getZ()), entry.facing(), entry.templateVersion());
    }

    private static void renderFutureBlueprints(
            Minecraft minecraft, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            boolean showAllEntrances
    ) {
        if (futurePreviewMode == FuturePreviewMode.HIDDEN) return;
        Vec3 camera = minecraft.getCameraEntity() == null
                ? Vec3.ZERO : minecraft.getCameraEntity().position();
        for (SettlementDetailsPayload.BuildingEntry building : managementBuildings) {
            if (!building.blueprint()) continue;
            if (building.serverDefinedGeometry()) {
                renderFutureOutline(poseStack, buffers, building, .22F, .88F, 1.0F);
                continue;
            }
            FuturePreviewCache cached = FUTURE_PREVIEW_CACHE.get(building.id());
            if (cached == null || cached.template == null) {
                renderFutureOutline(poseStack, buffers, building, 1.0F, 0.15F, 0.15F);
                continue;
            }
            if (building.status() == BuildingStatus.CANCELLATION_QUEUED) {
                renderFutureOutline(poseStack, buffers, building, 1.0F, 0.18F, 0.12F);
                continue;
            }
            boolean hovered = hoveredBuilding != null && hoveredBuilding.id().equals(building.id());
            Vec3 center = bounds(building).getCenter();
            boolean simplified = futurePreviewMode == FuturePreviewMode.SIMPLIFIED
                    || camera.distanceToSqr(center) > COMPLETE_PREVIEW_DISTANCE_SQUARED;
            boolean blocked = building.status() == BuildingStatus.CONSTRUCTION_BLOCKED;
            if (simplified) {
                renderFutureOutline(poseStack, buffers, building,
                        blocked ? 1.0F : 0.22F, blocked ? 0.68F : 0.88F, blocked ? 0.10F : 1.0F);
            } else {
                renderIncompletePreview(minecraft, poseStack, buffers, cached.missingPlacements,
                        blocked, hovered);
            }
            if (showAllEntrances || hoveredBuilding != null && hoveredBuilding.id().equals(building.id())) {
                renderDoorMarker(poseStack, buffers, cached.template);
            }
        }
    }

    private static void renderIncompletePreview(
            Minecraft minecraft, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            List<BlueprintPlacement> placements, boolean blocked, boolean selected
    ) {
        if (minecraft.level == null) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(blocked ? 1.0F : 0.56F,
                blocked ? 0.68F : 0.90F,
                blocked ? 0.10F : 1.0F, selected ? 0.78F : 0.58F);
        for (BlueprintPlacement placement : placements) {
            poseStack.pushPose();
            poseStack.translate(placement.position().getX(), placement.position().getY(), placement.position().getZ());
            minecraft.getBlockRenderer().renderSingleBlock(placement.state(), poseStack, buffers,
                    0x00F000F0, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.translucent());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static void renderFutureOutline(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            SettlementDetailsPayload.BuildingEntry building, float red, float green, float blue
    ) {
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, bounds(building).inflate(0.03D),
                red, green, blue, 0.90F);
        buffers.endBatch(RenderType.lines());
    }

    private static void renderFinishedPreview(
            Minecraft minecraft, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            BuildingBlueprintTemplate template, boolean valid
    ) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(valid ? 0.78F : 1.0F, valid ? 1.0F : 0.30F,
                valid ? 0.78F : 0.30F, 0.88F);
        for (BlueprintPlacement placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE || placement.state().isAir()) continue;
            poseStack.pushPose();
            poseStack.translate(placement.position().getX(), placement.position().getY(), placement.position().getZ());
            minecraft.getBlockRenderer().renderSingleBlock(placement.state(), poseStack, buffers,
                    0x00F000F0, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.translucent());
        VertexConsumer invisibleBlocks = buffers.getBuffer(RenderType.debugFilledBox());
        for (BlueprintPlacement placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE || placement.state().isAir()
                    || placement.state().getRenderShape() != RenderShape.INVISIBLE) continue;
            BlockPos pos = placement.position();
            LevelRenderer.addChainedFilledBoxVertices(poseStack, invisibleBlocks,
                    pos.getX() + 0.08D, pos.getY() + 0.08D, pos.getZ() + 0.08D,
                    pos.getX() + 0.92D, pos.getY() + 0.92D, pos.getZ() + 0.92D,
                    valid ? 0.20F : 1.0F, valid ? 0.72F : 0.25F, valid ? 1.0F : 0.25F, 0.62F);
        }
        buffers.endBatch(RenderType.debugFilledBox());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static void renderDoorMarker(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers, BuildingBlueprintTemplate template
    ) {
        if (!template.entrances().isEmpty()) {
            for (BlueprintEntrance entrance : template.entrances()) {
                renderEntranceMarker(poseStack, buffers, entrance.position(), entrance.outward());
            }
            return;
        }
        BlueprintPlacement door = template.placements().stream()
                .filter(placement -> entranceFacing(placement.state()) != null)
                .filter(placement -> !(placement.state().getBlock() instanceof DoorBlock)
                        || placement.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
                .findFirst().orElse(null);
        if (door == null) return;
        Direction entrance = entranceFacing(door.state());
        if (entrance == null) return;
        renderEntranceMarker(poseStack, buffers, door.position(), entrance);
    }

    private static void renderEntranceMarker(
            PoseStack poseStack, MultiBufferSource.BufferSource buffers, BlockPos pos, Direction entrance
    ) {
        VertexConsumer filled = buffers.getBuffer(RenderType.debugFilledBox());
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                pos.getX() + 0.12D, pos.getY() + 0.02D, pos.getZ() + 0.12D,
                pos.getX() + 0.88D, pos.getY() + 1.98D, pos.getZ() + 0.88D,
                1.0F, 0.62F, 0.02F, 0.70F);
        for (int distance = 1; distance <= 3; distance++) {
            renderDirectionCell(poseStack, filled, pos.relative(entrance, distance),
                    distance == 3 ? 0.92F : 0.62F);
        }
        BlockPos wings = pos.relative(entrance, 2);
        renderDirectionCell(poseStack, filled, wings.relative(entrance.getCounterClockWise()), 0.82F);
        renderDirectionCell(poseStack, filled, wings.relative(entrance.getClockWise()), 0.82F);
        buffers.endBatch(RenderType.debugFilledBox());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderDirectionCell(
            PoseStack poseStack, VertexConsumer filled, BlockPos position, float alpha
    ) {
        LevelRenderer.addChainedFilledBoxVertices(poseStack, filled,
                position.getX() + 0.16D, position.getY() + 0.03D, position.getZ() + 0.16D,
                position.getX() + 0.84D, position.getY() + 0.14D, position.getZ() + 0.84D,
                1.0F, 0.72F, 0.03F, alpha);
    }

    @Nullable
    private static Direction entranceFacing(@Nullable BuildingBlueprintTemplate template) {
        if (template == null) return null;
        if (!template.entrances().isEmpty()) return template.entrances().getFirst().outward();
        for (BlueprintPlacement placement : template.placements()) {
            Direction direction = entranceFacing(placement.state());
            if (direction != null && (!(placement.state().getBlock() instanceof DoorBlock)
                    || placement.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)) return direction;
        }
        return null;
    }

    @Nullable
    private static Direction entranceFacing(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.FACING);
        if (state.getBlock() instanceof FenceGateBlock) return state.getValue(FenceGateBlock.FACING);
        return null;
    }

    private record CursorRay(Vec3 start, Vec3 end) {
    }


    private enum FuturePreviewMode {
        COMPLETE("gui.exiledsettlement.future_preview.complete"),
        SIMPLIFIED("gui.exiledsettlement.future_preview.simplified"),
        HIDDEN("gui.exiledsettlement.future_preview.hidden");

        private final String translationKey;

        FuturePreviewMode(String translationKey) {
            this.translationKey = translationKey;
        }

        private String translationKey() {
            return translationKey;
        }

        private FuturePreviewMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private record PreviewIdentity(
            BuildingType type, BlockPos position, Direction facing, int templateVersion,
            BlockPos minCorner, BlockPos maxCorner, boolean serverDefinedGeometry, boolean terrainRegion
    ) {
        private static PreviewIdentity of(SettlementDetailsPayload.BuildingEntry entry) {
            return new PreviewIdentity(entry.type(), entry.position(), entry.facing(), entry.templateVersion(),
                    entry.minCorner(), entry.maxCorner(), entry.serverDefinedGeometry(), entry.terrainRegion());
        }
    }

    private static final class FuturePreviewCache {
        private final PreviewIdentity identity;
        @Nullable
        private final BuildingBlueprintTemplate template;
        private final List<BlueprintPlacement> finalPlacements;
        private final Set<BlockPos> finalPositions;
        private List<BlueprintPlacement> missingPlacements;
        private int knownProgress;
        private boolean dirty = true;

        private FuturePreviewCache(
                PreviewIdentity identity, @Nullable BuildingBlueprintTemplate template,
                List<BlueprintPlacement> finalPlacements, List<BlueprintPlacement> missingPlacements
        ) {
            this.identity = identity;
            this.template = template;
            this.finalPlacements = List.copyOf(finalPlacements);
            this.finalPositions = finalPlacements.stream()
                    .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                    .map(BlueprintPlacement::position)
                    .map(BlockPos::immutable)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            this.missingPlacements = List.copyOf(missingPlacements);
        }
    }

    private enum PlacementMode {
        BUILDING,
        AREA,
        ROAD,
        MANAGEMENT
    }
}

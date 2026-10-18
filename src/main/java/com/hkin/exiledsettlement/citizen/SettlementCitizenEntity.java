package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SettlementCitizenEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> VISIBLE_GARMENT =
            SynchedEntityData.defineId(SettlementCitizenEntity.class,EntityDataSerializers.INT);
    public void setVisibleGarment(int kind) {entityData.set(VISIBLE_GARMENT,kind);}
    public int visibleGarment() {return entityData.get(VISIBLE_GARMENT);}
    private static final String ACTIVITY_TAG = "exiledsettlement.activity";
    private static final EntityDataAccessor<Integer> VISIBLE_ACTIVITY =
            SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VISIBLE_SKIN =
            SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VISIBLE_NUTRITION =
            SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> VISIBLE_YSM_MODEL =
            SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> VISIBLE_YSM_TEXTURE =
            SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.STRING);
    private static final double TARGET_PROGRESS_DISTANCE = 0.25D;
    private static final long STUCK_AFTER_TICKS = 100L;
    private static final long ROUTE_NO_APPROACH_TIMEOUT_TICKS = 160L;
    private static final int CONSTRUCTION_ROUTE_FAILURE_LIMIT = 3;
    private static final long CONSTRUCTION_STEP_STUCK_AFTER_TICKS = 30L;
    private static final long CONSTRUCTION_STEP_MAX_DURATION_TICKS = 40L;
    private static final long CONSTRUCTION_TARGET_NO_OUTPUT_TIMEOUT_TICKS = 400L;
    private long bypassRoadUntil;
    private final java.util.ArrayDeque<BlockPos> recentRoadSegments = new java.util.ArrayDeque<>();
    private static final double DIRECT_NAVIGATION_DISTANCE = 24.0D;
    private static final double DIRECT_NAVIGATION_DISTANCE_SQUARED =
            DIRECT_NAVIGATION_DISTANCE * DIRECT_NAVIGATION_DISTANCE;
    private static final int[] SEGMENT_ADVANCES = {22, 18, 14, 10, 6, 3};
    private static final int[] SEGMENT_LATERAL_OFFSETS = {0, 4, -4, 8, -8};
    private static final int PASSAGE_LOOKAHEAD_NODES = 8;
    private static final int PASSAGE_MAX_LENGTH = 64;
    private static final long TRAFFIC_YIELD_TICKS = 40L;
    private static final long BED_RECOVERY_STEP_TICKS = 60L;
    private static final long BED_RECOVERY_RETRY_TICKS = 100L;

    @Nullable
    private BlockPos commandedTarget;
    private double lastProgressTargetDistance = Double.POSITIVE_INFINITY;
    @Nullable
    private Path lastProgressPath;
    private int lastProgressPathNodeIndex;
    private long lastProgressGameTime;
    private double routeBestTargetDistance = Double.POSITIVE_INFINITY;
    private long routeLastApproachGameTime;
    @Nullable private BlockPos personalHomeRouteBed;
    private boolean personalHomeEntered;
    private long personalHomeRouteLastTick = Long.MIN_VALUE;
    private final Set<BlockPos> openedDoors = new HashSet<>();
    private boolean defending;
    private int attackCooldown;
    @Nullable
    private BlockPos yieldPosition;
    private long yieldUntil;
    @Nullable
    private BlockPos constructionStepTarget;
    private BlockPos scaffoldRestCell;
    @Nullable
    private BlockPos pendingCenteredRiseTarget;
    @Nullable
    private BlockPos pendingDiagonalRiseTarget;
    private final ArrayDeque<BlockPos> pendingLocalConstructionRoute = new ArrayDeque<>();
    @Nullable
    private BlockPos verticalPillarSupport;
    @Nullable
    private BlockPos readyVerticalPillarSupport;
    @Nullable
    private BlockPos constructionClimbColumn;
    private int constructionClimbTopY;
    private boolean constructionClimbDescending;
    private boolean constructionCentering;
    private boolean constructionLeap;
    private double constructionLeapApexY;
    @Nullable
    private BlockPos preparedPathTarget;
    @Nullable
    private Path preparedPath;
    @Nullable
    private GroundPathNavigation pathProbeNavigation;
    @Nullable
    private BlockPos longDistanceWaypoint;
    private final Map<BlockPos, Long> deferredConstructionTargets = new HashMap<>();
    @Nullable
    private UUID constructionRouteTaskId;
    @Nullable
    private BlockPos constructionRouteTaskTarget;
    private long constructionRouteTaskStartedGameTime;
    private double constructionRouteTaskBestDistance = Double.POSITIVE_INFINITY;
    @Nullable
    private ConstructionRouteKey trackedConstructionRoute;
    @Nullable
    private BlockPos trackedConstructionRouteDestination;
    private final Map<ConstructionRouteKey, Integer> constructionRouteFailures = new LinkedHashMap<>();
    private double trackedConstructionRouteBestDistance = Double.POSITIVE_INFINITY;
    private long trackedConstructionRouteLastApproachGameTime;
    private double constructionMovementSpeed;
    private double constructionStepLastDistanceSqr;
    private long constructionStepLastProgressGameTime;
    private long constructionStepStartedGameTime;
    private boolean constructionMovementFailed;
    private boolean constructionAccessRetreating;
    private int constructionDriveTicks;
    private String constructionLastDriveTrace = "";
    private double constructionLastDriveAfterX = Double.NaN;
    private String constructionLastDriveEntryTrace = "";
    private String constructionTickTrace = "";
    private final Map<String, Integer> constructionAccessFailures = new LinkedHashMap<>();
    private String constructionLastAbort = "";
    private String constructionDecisionTrace = "";
    private final java.util.ArrayDeque<String> constructionDecisionHistory = new java.util.ArrayDeque<>();
    private int stuckRecoveryAttempts;
    private long trafficWaitUntil;
    @Nullable private BlockPos bedRecoveryGoal;
    @Nullable private BlockPos bedRecoveryWaypoint;
    @Nullable private BlockPos bedRecoveryPreviousOrigin;
    private long bedRecoveryStepUntil;
    private long bedRecoveryRetryAt;
    private int bedRecoveryAttempts;
    private long armorReadyUntil;
    private boolean armorReady;

    public boolean isArmorReady() { return armorReady; }

    private CitizenRecord residentRecord() {
        if (!(level() instanceof ServerLevel level)) return null;
        UUID settlementId = CitizenProjectionService.settlementId(this);
        UUID citizenId = CitizenProjectionService.citizenId(this);
        SettlementRecord settlement = settlementId == null ? null
                : SettlementSavedData.get(level).findById(settlementId).orElse(null);
        return settlement == null || citizenId == null ? null : settlement.citizen(citizenId);
    }

    /** Put real personal armor on before the first attack, not just after taking damage. */
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!level().isClientSide && amount > 0 && source.getEntity() != null) {
            armorReadyUntil = level().getGameTime() + 100L;
            armorReady = true;
            CitizenRecord citizen = residentRecord();
            if (citizen != null) CitizenEquipmentService.syncVisibleEquipment(citizen, this);
            ((com.hkin.exiledsettlement.mixin.LivingEquipmentUpdateAccessor) (Object) this).exiledSettlement$detectEquipmentUpdates();
        }
        boolean damaged = super.hurt(source, amount);
        if (damaged && !level().isClientSide) {
            CitizenRecord citizen = residentRecord();
            if (citizen != null) {
                citizen.life().lastDamageGameTime = level().getGameTime();
                SettlementSavedData.get((ServerLevel) level()).touch();
            }
        }
        return damaged;
    }

    private void updatePersonalEquipmentAndPickup() {
        if (!(level() instanceof ServerLevel level)) return;
        if (Math.floorMod(tickCount + getId(), 20) != 0) return;
        CitizenRecord citizen = residentRecord();
        if (citizen == null || !citizen.alive()) return;
        setVisibleNutrition(citizen.nutrition());
        boolean danger = defending
                || !level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, getBoundingBox().inflate(8),
                    mob -> mob instanceof Enemy && mob.isAlive()).isEmpty();
        UUID settlementId = CitizenProjectionService.settlementId(this);
        SettlementRecord settlement = settlementId == null ? null
                : SettlementSavedData.get(level).findById(settlementId).orElse(null);
        if (settlement != null && (settlement.community().evacuation || settlement.community().danger(blockPosition()))) danger = true;
        if (danger) armorReadyUntil = level.getGameTime() + 100L;
        boolean ready = danger || level.getGameTime() < armorReadyUntil;
        if (ready != armorReady) {
            armorReady = ready;
            CitizenEquipmentService.syncVisibleEquipment(citizen, this);
            ((com.hkin.exiledsettlement.mixin.LivingEquipmentUpdateAccessor) (Object) this).exiledSettlement$detectEquipmentUpdates();
        }
        if (settlement != null && !isSleeping() && !defending) {
            CitizenWorkService.pickupNearbyItems(level, SettlementSavedData.get(level), settlement, citizen, this);
        }
    }

    public SettlementCitizenEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setDropChance(slot, 0.0F);
        }
        if (getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
            navigation.setCanFloat(true);
        }
        if (getAttribute(Attributes.STEP_HEIGHT) != null) {
            getAttribute(Attributes.STEP_HEIGHT).setBaseValue(1.25D);
        }
    }

    @Override protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new CitizenGroundNavigation(this, level);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(this));
    }

    private void configureDestination(GroundPathNavigation navigation, BlockPos target) {
        if (navigation instanceof CitizenGroundNavigation residentNavigation && level() instanceof ServerLevel server) {
            UUID id = CitizenProjectionService.settlementId(this);
            SettlementRecord settlement = id == null ? null : SettlementSavedData.get(server).findById(id).orElse(null);
            residentNavigation.destination(settlement, navigationFeetPosition(), target);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VISIBLE_ACTIVITY, CitizenActivity.IDLE.ordinal());
        builder.define(VISIBLE_SKIN, 0);
        builder.define(VISIBLE_NUTRITION, 100);
        builder.define(VISIBLE_GARMENT,0);
        builder.define(VISIBLE_YSM_MODEL, "");
        builder.define(VISIBLE_YSM_TEXTURE, "");
    }

    @Override
    protected void hurtArmor(net.minecraft.world.damagesource.DamageSource source, float amount) {
        doHurtEquipment(source, amount, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
        persistWornEquipmentDamage();
    }

    @Override
    protected void hurtHelmet(net.minecraft.world.damagesource.DamageSource source, float amount) {
        doHurtEquipment(source, amount, EquipmentSlot.HEAD);
        persistWornEquipmentDamage();
    }

    @Override
    protected void hurtCurrentlyUsedShield(float amount) {
        if (amount >= 3.0F && getUseItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHIELD_BLOCK)) {
            EquipmentSlot slot = getUsedItemHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
            getUseItem().hurtAndBreak(1 + (int)Math.floor(amount), this, slot);
            persistWornEquipmentDamage();
            if (getUseItem().isEmpty()) stopUsingItem();
        }
    }

    /** Vanilla equipment is a render/combat projection; persist wear before any subsequent resync. */
    private void persistWornEquipmentDamage() {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        UUID settlementId = CitizenProjectionService.settlementId(this);
        UUID citizenId = CitizenProjectionService.citizenId(this);
        SettlementRecord settlement = settlementId == null ? null
                : SettlementSavedData.get(serverLevel).findById(settlementId).orElse(null);
        CitizenRecord citizen = settlement == null || citizenId == null ? null : settlement.citizen(citizenId);
        if (citizen == null) return;
        boolean changed = false;
        for (EquipmentSlot slot : CitizenRecord.EQUIPMENT_SLOTS) {
            if (slot.isArmor() && !armorReady) continue;
            changed |= citizen.updateEquipment(slot, getItemBySlot(slot));
        }
        if (changed) SettlementSavedData.get(serverLevel).touch();
    }

    @Override
    protected void customServerAiStep() {
        if (CitizenInteractionService.held(this)) return;
        // Deliberately do not tick Villager Brain. Navigation and movement controls are
        // still ticked by Mob.serverAiStep and are commanded by CitizenWorkService.
        manageNearbyDoors();
        animateVisibleWork();
        defendSelf();
        updatePersonalEquipmentAndPickup();
    }

    @Override
    public void tick() {
        int recoveryInterval = com.hkin.exiledsettlement.settlement.CommunityContent.recoveryIntervalTicks;
        if (level() instanceof ServerLevel server && recoveryInterval > 0
                && tickCount > 0 && tickCount % recoveryInterval == 0) {
            CitizenRecord citizen = residentRecord();
            if (citizen != null) com.hkin.exiledsettlement.settlement.CommunityLifeService.recoverLife(
                    server, SettlementSavedData.get(server), citizen, this);
        }
        if (!level().isClientSide && CitizenInteractionService.held(this)) {
            getNavigation().stop();
            getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
            setJumping(false);
            setXxa(0); setZza(0);
            setDeltaMovement(0, getDeltaMovement().y, 0);
            super.tick();
            setDeltaMovement(0, getDeltaMovement().y, 0);
            return;
        }
        // Reassert the bounded controller's gravity suspension before vanilla
        // movement runs. A completed sub-step may be followed immediately by a
        // new queued leg in the worker scheduler; relying only on the begin call
        // lets the intervening vanilla tick pull the resident 0.2-0.4 blocks
        // down the support edge, after which the same rise is replayed forever.
        boolean tracingConstruction = !level().isClientSide && constructionStepTarget != null;
        double preSuperX = getX();
        double preSuperY = getY();
        double preSuperZ = getZ();
        boolean restingOnScaffold = !level().isClientSide && scaffoldRestCell != null
                && constructionStepTarget == null && getNavigation().isDone() && !defending
                && isAlive() && BlockPos.containing(getX(), getY() + 0.05D, getZ()).equals(scaffoldRestCell)
                && level().getBlockState(scaffoldRestCell).is(Blocks.SCAFFOLDING);
        if (restingOnScaffold) {
            setNoGravity(true);
            setShiftKeyDown(true);
            setDeltaMovement(Vec3.ZERO);
        } else if (scaffoldRestCell != null) {
            scaffoldRestCell = null;
            if (!tracingConstruction) { setNoGravity(false); setShiftKeyDown(false); }
        }
        // Vanilla persists NoGravity, whereas the temporary step/grip controller
        // is transient. A resumed resident must settle on actual support.
        if (!level().isClientSide && !restingOnScaffold && !tracingConstruction) setNoGravity(false);
        if (tracingConstruction) {
            setNoGravity(true);
            setDeltaMovement(Vec3.ZERO);
            setJumping(false);
            getNavigation().stop();
            getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
        }
        super.tick();
        if(!level().isClientSide)observeJourneyProgress();
        if (restingOnScaffold && constructionStepTarget == null && getNavigation().isDone()
                && Math.abs(getY() - preSuperY) <= 0.5D
                && level().getBlockState(scaffoldRestCell).is(Blocks.SCAFFOLDING)
                && constructionScaffoldingBoundsClear(getBoundingBox().move(0, preSuperY - getY(), 0))) {
            // Retain a grip on this real climbable block between work cycles.
            // Only counter vanilla vertical drift; never snap back horizontal
            // displacement, bridge missing scaffolding, or suspend in air.
            setPos(getX(), preSuperY, getZ());
            setDeltaMovement(Vec3.ZERO);
            fallDistance = 0;
        }
        if (tracingConstruction) {
            constructionTickTrace = level().getGameTime() + ":pre="
                    + preSuperX + "/" + preSuperY + "/" + preSuperZ
                    + ",postSuper=" + getX() + "/" + getY() + "/" + getZ();
        }
        // Special construction movement must advance every entity tick. Mob AI can be
        // throttled when no player is close enough, which previously left a resident
        // suspended after the first 0.20-block lift even though the route stayed active.
        // Keeping the bounded, collision-checked motion here makes centering, one-block
        // rises and exterior-footing traversal independent of AI scheduling distance.
        if (!level().isClientSide && !defending && constructionStepTarget != null) {
            // Arrival is part of the movement controller, not of the worker's
            // ten-tick job refresh. Leaving completion to CitizenWorkService
            // froze a builder that had already reached the centre of a scaffold
            // cell: the entity tick stopped driving because it was near, while
            // the work state could be busy selecting or cleaning another block.
            // Reuse the same completion routine every entity tick so climb,
            // descent, centering and queued local routes all settle immediately.
            if (!nearConstructionStepTarget()) constructionDriveTicks++;
            continueConstructionStep(constructionMovementSpeed);
            constructionTickTrace += ",postDrive=" + getX() + "/" + getY() + "/" + getZ();
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(net.minecraft.world.item.Items.NAME_TAG)) {
            return CitizenNamingService.interact(player, this, hand);
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) openCitizenManagement(serverPlayer);
        return InteractionResult.CONSUME;
    }

    public boolean usesSettlementAi() {
        return true;
    }

    @Override public boolean isPushable() {
        return !CitizenInteractionService.held(this) && super.isPushable();
    }

    public boolean isDefending() { return defending; }

    public CitizenActivity activity() {
        int index = getEntityData().get(VISIBLE_ACTIVITY);
        return index >= 0 && index < CitizenActivity.values().length
                ? CitizenActivity.values()[index] : CitizenActivity.IDLE;
    }

    public void setActivity(CitizenActivity activity) {
        getPersistentData().putString(ACTIVITY_TAG, activity.serializedName());
        getEntityData().set(VISIBLE_ACTIVITY, activity.ordinal());
    }

    public int skinVariant() {
        return getEntityData().get(VISIBLE_SKIN);
    }

    public void setSkinVariant(UUID citizenId) {
        getEntityData().set(VISIBLE_SKIN, Math.floorMod(citizenId.hashCode(), 8));
    }

    public boolean usesYsmModel() {
        return !ysmModelId().isBlank();
    }

    public String ysmModelId() {
        return getEntityData().get(VISIBLE_YSM_MODEL);
    }

    public String ysmTextureName() {
        return getEntityData().get(VISIBLE_YSM_TEXTURE);
    }

    public void setYsmModelSelection(String modelId, String textureName) {
        getEntityData().set(VISIBLE_YSM_MODEL, modelId == null ? "" : modelId);
        getEntityData().set(VISIBLE_YSM_TEXTURE,
                modelId == null || modelId.isBlank() || textureName == null ? "" : textureName);
    }

    public boolean isPhysicallySleepingAt(BlockPos bed) {
        return getSleepingPos().filter(bed::equals).isPresent()
                && distanceToSqr(Vec3.atBottomCenterOf(bed).add(0, .6875D, 0)) <= 1.0D;
    }

    @Override
    public void stopSleeping() {
        // Old life tasks could move a sleeper away from its saved bed. Vanilla
        // waking would teleport that resident back to the bed's exit position.
        // Release that stale reservation first and stand up at the real position.
        var bed = getSleepingPos().orElse(null);
        if (bed != null && !isPhysicallySleepingAt(bed)) {
            if (!level().isClientSide && level().hasChunkAt(bed)) {
                var state = level().getBlockState(bed);
                if (state.isBed(level(), bed, this)) state.setBedOccupied(level(), bed, this, false);
            }
            clearSleepingPos();
        }
        super.stopSleeping();
        clearBedRecovery();
    }

    /** Air cell containing the feet on a 15/16-high path or farmland surface. */
    public BlockPos navigationFeetPosition() {
        return BlockPos.containing(getX(), getY() + 0.1D, getZ());
    }

    public int visibleNutrition() { return entityData.get(VISIBLE_NUTRITION); }
    public void setVisibleNutrition(int value) { entityData.set(VISIBLE_NUTRITION, Math.max(0, Math.min(100, value))); }

    /** Keep the doorway stage completed during one continuous return-home trip. */
    public boolean hasEnteredPersonalHome(BlockPos bed, @Nullable BlockPos entry) {
        long now = level().getGameTime();
        if (!bed.equals(personalHomeRouteBed) || now - personalHomeRouteLastTick > 40L) {
            personalHomeRouteBed = bed.immutable();
            personalHomeEntered = false;
        }
        personalHomeRouteLastTick = now;
        if (entry == null || distanceToSqr(entry.getX() + .5D, entry.getY(), entry.getZ() + .5D) <= 2.25D)
            personalHomeEntered = true;
        return personalHomeEntered;
    }

    private BlockPos physicalNavigationIntent;
    @Nullable public BlockPos currentNavigationDestination(){return commandedTarget!=null || navigationSearchPending || hasActiveConstructionMovement()?physicalNavigationIntent:null;}
    private double physicalIntentBestDistance;
    private long physicalIntentProgressAt;
    private final NavigationJourney navigationJourney=new NavigationJourney();
    private BlockPos segmentSearchTarget,segmentSearchOrigin;
    private List<BlockPos> segmentSearchCandidates=List.of();
    private int segmentSearchCursor,segmentSearchAllowance;
    private boolean navigationSearchPending;
    private long navigationSearchAfter,navigationSearches,navigationFailures;
    private BlockPos longDistanceOrigin;
    private NavigationSegment pendingNavigationSegment;
    private BlockPos failedProbeOrigin;
    private final Map<BlockPos,Long> failedNavigationProbes=new java.util.LinkedHashMap<>();

    private void observeJourneyProgress(){
        long now=level().getGameTime();
        // Computing or backing off a path does not prove physical progress.
        // Observe real movement even between path segments, without refreshing
        // the watchdog merely because another search is pending.
        if(physicalNavigationIntent==null)return;
        if(navigationJourney.observe(navigationFeetPosition(),Math.sqrt(distanceToSqr(physicalNavigationIntent.getX()+.5,physicalNavigationIntent.getY(),physicalNavigationIntent.getZ()+.5)),now)){
            physicalIntentProgressAt=now;routeLastApproachGameTime=now;lastProgressGameTime=now;
            if(constructionRouteTaskId!=null)constructionRouteTaskStartedGameTime=now;
            if(trackedConstructionRoute!=null)trackedConstructionRouteLastApproachGameTime=now;
        }
    }

    private String navigationFailed(String reason,long now){
        NavigationSearchBudget.watch(this);
        navigationJourney.fail(now,longDistanceWaypoint);navigationFailures++;
        boolean cutting=level() instanceof ServerLevel serverLevel && WorkerAccessService.request(serverLevel,this,physicalNavigationIntent,navigationJourney.failures());
        CitizenNavigationReservationService.releaseAll(level(),getUUID());
        var settlementId=CitizenProjectionService.settlementId(this);
        if(settlementId!=null && physicalNavigationIntent!=null)CitizenHabitualRouteService.invalidate(level(),settlementId,navigationFeetPosition(),physicalNavigationIntent);
        getNavigation().stop();commandedTarget=null;longDistanceWaypoint=null;
        pendingNavigationSegment=null;navigationSearchPending=false;
        segmentSearchTarget=null;segmentSearchCandidates=List.of();segmentSearchCursor=0;
        routeLastApproachGameTime=now;physicalIntentProgressAt=now;resetProgress(now);
        // Early retries retain the job. Exhausted attempts still reach existing
        // work diagnostics/reassignment; retry history is not cleared there.
        return cutting || navigationJourney.failures()<3?"":reason;
    }

    private boolean acquireNavigationSearch(){
        if(!NavigationSearchBudget.acquire(level())){navigationSearchPending=true;navigationSearchAfter=level().getGameTime()+1;return false;}
        navigationSearches++;return true;
    }
    public boolean navigationGeometryChanged(long now){
        if(failedNavigationProbes.isEmpty() && !navigationJourney.waiting(now))return false;
        failedNavigationProbes.clear();
        if(getNavigation().isDone()){navigationJourney.geometryChanged(now);segmentSearchTarget=null;navigationSearchAfter=now;}
        return true;
    }
    public long navigationSearchCount(){return navigationSearches;}
    public int navigationRetryStage(){return navigationJourney.failures();}

    @Nullable private Path probeNavigationPath(BlockPos destination){
        var origin=navigationFeetPosition();long now=level().getGameTime();
        if(!origin.equals(failedProbeOrigin)){failedProbeOrigin=origin.immutable();failedNavigationProbes.clear();}
        if(failedNavigationProbes.getOrDefault(destination,0L)>now)return null;
        if(!acquireNavigationSearch())return null;
        Path path=probePathTo(destination);
        if(path==null){NavigationSearchBudget.watch(this);failedNavigationProbes.put(destination.immutable(),now+1200);
            if(failedNavigationProbes.size()>256)failedNavigationProbes.remove(failedNavigationProbes.keySet().iterator().next());}
        return path;
    }

    public String navigateTo(BlockPos target, double speed) {
        long intentTime=level().getGameTime();
        double intentDistance=distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5);
        if(!target.equals(physicalNavigationIntent)){navigationSearchAfter=0;navigationSearchPending=false;segmentSearchTarget=null;pendingNavigationSegment=null;}
        navigationJourney.begin(target,navigationFeetPosition(),Math.sqrt(intentDistance),intentTime);
        if(target.equals(physicalNavigationIntent) && intentDistance>2.25
                && intentTime-navigationJourney.progressAt()>=600){
            FarmAccessService.failedWaypoint(this);return navigationFailed("STUCK",intentTime);
        }
        configurePathProbe();
        if(segmentSearchAllowance!=navigationJourney.allowance())failedNavigationProbes.clear();
        if(getNavigation() instanceof CitizenGroundNavigation live)live.detourAllowance(navigationJourney.allowance());
        if(pathProbeNavigation instanceof CitizenGroundNavigation probe)probe.detourAllowance(navigationJourney.allowance());
        if(intentDistance<=.81)navigationJourney.arrived(intentTime);
        if(getNavigation().isDone() && (navigationJourney.waiting(intentTime) || intentTime<navigationSearchAfter)){
            return "";
        }
        navigationSearchPending=false;
        if(navigationJourney.looping())return navigationFailed("STUCK",intentTime);
        if(!target.equals(physicalNavigationIntent)) {
            physicalNavigationIntent=target.immutable();physicalIntentBestDistance=Math.sqrt(intentDistance);physicalIntentProgressAt=intentTime;
        }else if(Math.sqrt(intentDistance)<physicalIntentBestDistance-.5) {
            physicalIntentBestDistance=Math.sqrt(intentDistance);physicalIntentProgressAt=intentTime;
        }else if(intentDistance>2.25 && intentTime-physicalIntentProgressAt>=600) {
            physicalIntentProgressAt=intentTime;physicalIntentBestDistance=Math.sqrt(intentDistance);
            FarmAccessService.failedWaypoint(this);getNavigation().stop();commandedTarget=null;
            bypassRoadUntil=intentTime+600;recentRoadSegments.clear();return navigationFailed("STUCK",intentTime);
        }
        target=FarmAccessService.waypoint(this,target);
        if (getNavigation() instanceof GroundPathNavigation ground) configureDestination(ground, target);
        // A navigation intent cannot retain a sleeping pose or bed reservation.
        if (isSleeping()) stopSleeping();
        clearSpecialConstructionMovement();
        long gameTime = level().getGameTime();
        CitizenNavigationReservationService.releasePassedPassages(
                level(), getUUID(), blockPosition(), gameTime);
        boolean newTarget = !target.equals(commandedTarget);
        if (newTarget) {
            CitizenNavigationReservationService.releasePassages(level(), getUUID());
            if (commandedTarget != null && !commandedTarget.equals(target)) recentRoadSegments.clear();
            commandedTarget = target.immutable();
            longDistanceWaypoint = null;
            pendingNavigationSegment=null;
            stuckRecoveryAttempts = 0;
            trafficWaitUntil = 0L;
            resetProgress(gameTime);
            routeBestTargetDistance = Math.sqrt(distanceToSqr(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D));
            routeLastApproachGameTime = gameTime;
        } else {
            Path activePath = getNavigation().getPath();
            int activePathNode = activePath == null ? -1 : activePath.getNextNodeIndex();
            boolean pathAdvanced = activePath != null && activePath == lastProgressPath
                    && activePathNode > lastProgressPathNodeIndex;
            if (activePath != lastProgressPath) {
                // A replacement path is not progress by itself. Record its
                // starting node without moving the stuck timer; otherwise an AI
                // that repeatedly rebuilds the same unusable path can remain
                // "active" forever while visibly shuffling in place.
                lastProgressPath = activePath;
                lastProgressPathNodeIndex = activePathNode;
            }
            double targetDistance = Math.sqrt(distanceToSqr(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D));
            if (targetDistance <= routeBestTargetDistance - TARGET_PROGRESS_DISTANCE) {
                routeBestTargetDistance = targetDistance;
                routeLastApproachGameTime = gameTime;
            } else if (gameTime - routeLastApproachGameTime
                    >= ROUTE_NO_APPROACH_TIMEOUT_TICKS) {
                // Path node churn is not physical progress. A navigator can keep
                // advancing and rebuilding nodes beside scaffolding while the
                // resident never gets closer to the requested station. End this
                // attempt so construction can count and eventually blacklist the
                // route instead of preserving it for the rest of the day.
                getNavigation().stop();
                CitizenNavigationReservationService.releaseAll(level(), getUUID());
                bypassRoadUntil = gameTime + 400L;
                recentRoadSegments.clear();
                commandedTarget = null;
                stuckRecoveryAttempts = 0;
                resetProgress(gameTime);
                FarmAccessService.failedWaypoint(this);return navigationFailed("STUCK",gameTime);
            }
            boolean approachedTarget = targetDistance
                    <= lastProgressTargetDistance - TARGET_PROGRESS_DISTANCE;
            if (pathAdvanced || approachedTarget) {
                stuckRecoveryAttempts = 0;
                resetProgress(gameTime);
            } else if (gameTime < trafficWaitUntil) {
                // Waiting for a one-wide passage token is intentional queueing, not a
                // navigation failure. Keep the original target without starting the
                // stuck-recovery loop or reselecting work every ten ticks.
                resetProgress(gameTime);
            } else if (gameTime - lastProgressGameTime >= STUCK_AFTER_TICKS) {
                if (stuckRecoveryAttempts < 3 && beginStuckRecovery(target, gameTime, speed)) return "";
                getNavigation().stop();
                CitizenNavigationReservationService.releaseAll(level(), getUUID());
                commandedTarget = null;
                stuckRecoveryAttempts = 0;
                resetProgress(gameTime);
                FarmAccessService.failedWaypoint(this);return navigationFailed("STUCK",gameTime);
            }
        }
        if (gameTime < yieldUntil && yieldPosition != null) {
            if (nearBlock(yieldPosition)) {
                if (!getNavigation().isDone()) getNavigation().stop();
                return "";
            }
            if (!getNavigation().isDone()) return "";
            if (getNavigation().moveTo(
                    yieldPosition.getX() + 0.5D,
                    yieldPosition.getY(),
                    yieldPosition.getZ() + 0.5D,
                    speed * 0.85D
            )) return "";
            yieldPosition = null;
            yieldUntil = 0L;
        }
        yieldPosition = null;
        yieldUntil = 0L;
        trafficWaitUntil = 0L;
        if (tryYieldForOncomingCitizen(target, gameTime, speed)) return "";
        // Work state is evaluated every ten ticks. Re-submitting an unchanged, active
        // path on every evaluation caused visible micro-stops and path oscillation.
        if (!newTarget && !getNavigation().isDone()) return "";

        boolean moving;
        if (target.equals(preparedPathTarget) && preparedPath != null) {
            moving = acquireNavigationSearch() && commitProbedDestination(target, speed);
        } else if (requiresSegmentedNavigation(target) || navigationJourney.allowance()>0) {
            moving = moveAlongLongDistanceRoute(target, speed);
        } else {
            longDistanceWaypoint = null;
            moving = acquireNavigationSearch() && getNavigation().moveTo(
                    target.getX() + 0.5D,
                    target.getY(),
                    target.getZ() + 0.5D,
                    speed
            );
        }
        preparedPathTarget = null;
        preparedPath = null;
        if(moving && getNavigation().isDone() && distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)>0.36
                && distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<6.25 && canStandAtPosition(target)) {
            getMoveControl().setWantedPosition(target.getX()+.5,target.getY(),target.getZ()+.5,speed);
            if(isInWater() || horizontalCollision && target.getY()>getY()+.1)getJumpControl().jump();
        }
        if(!moving && navigationSearchPending)return "";
        if(!moving){FarmAccessService.failedWaypoint(this);return navigationFailed("NO_PATH",gameTime);}
        return "";
    }

    private boolean beginStuckRecovery(BlockPos target, long gameTime, double speed) {
        CitizenNavigationReservationService.releaseAll(level(), getUUID());
        UUID settlementId = CitizenProjectionService.settlementId(this);
        if (settlementId != null) {
            CitizenHabitualRouteService.invalidate(level(), settlementId,
                    blockPosition(), target);
        }
        YieldRoute escape = findYieldPosition(target);
        if (escape == null) return false;
        stuckRecoveryAttempts++;
        yieldPosition = escape.position();
        yieldUntil = gameTime + 60L;
        longDistanceWaypoint = null;
        getNavigation().stop();
        boolean moving = escape.path() != null
                ? commitProbedDestination(escape.position(), speed * 0.85D)
                : getNavigation().moveTo(
                escape.position().getX() + 0.5D,
                escape.position().getY(),
                escape.position().getZ() + 0.5D,
                speed * 0.85D);
        resetProgress(gameTime);
        return moving;
    }

    private boolean requiresSegmentedNavigation(BlockPos target) {
        return distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D)
                > DIRECT_NAVIGATION_DISTANCE_SQUARED;
    }

    private boolean moveAlongLongDistanceRoute(BlockPos finalTarget, double speed) {
        if (longDistanceWaypoint != null && nearBlock(longDistanceWaypoint)) {
            navigationJourney.completedWaypoint(longDistanceWaypoint);
            var id=CitizenProjectionService.settlementId(this);
            if(id!=null && longDistanceOrigin!=null)CitizenHabitualRouteService.remember(level(),id,longDistanceOrigin,finalTarget,longDistanceWaypoint,level().getGameTime());
            longDistanceWaypoint = null;
            getNavigation().stop();
        }
        if (longDistanceWaypoint != null && !getNavigation().isDone()) return true;

        NavigationSegment segment = pendingNavigationSegment!=null?pendingNavigationSegment:findLongDistanceSegment(finalTarget);
        if (segment == null) {
            longDistanceWaypoint = null;
            return false;
        }
        longDistanceWaypoint = segment.position();
        pendingNavigationSegment=segment;
        if(!acquireNavigationSearch())return false;
        longDistanceOrigin=navigationFeetPosition().immutable();
        boolean moving=commitProbedDestination(segment.position(),speed);
        pendingNavigationSegment=null;return moving;
    }

    @Nullable
    private NavigationSegment findLongDistanceSegment(BlockPos finalTarget) {
        configurePathProbe();configureDestination(pathProbeNavigation, finalTarget);
        BlockPos current=navigationFeetPosition();long now=level().getGameTime();
        boolean fresh=segmentSearchAllowance!=navigationJourney.allowance() || !finalTarget.equals(segmentSearchTarget) || segmentSearchOrigin==null || current.distSqr(segmentSearchOrigin)>4;
        if(fresh){
            segmentSearchTarget=null;segmentSearchCursor=0;
            if(current.distSqr(finalTarget)<=Math.pow(32+navigationJourney.allowance(),2)){
                Path direct=probeNavigationPath(finalTarget);
                if(direct!=null)return new NavigationSegment(finalTarget,direct);
                if(navigationSearchPending)return null;
            }
            UUID settlementId=CitizenProjectionService.settlementId(this);
            if(settlementId!=null && now>=bypassRoadUntil){
                var hint=CitizenHabitualRouteService.nextWaypoint(level(),settlementId,current,finalTarget,now);
                if(hint!=null && navigationJourney.accepts(hint,current,finalTarget,now) && permitsTransitWaypoint(hint,finalTarget) && canStandAt(hint)){
                    Path path=probeNavigationPath(hint);if(path!=null)return new NavigationSegment(hint,path);
                    if(navigationSearchPending)return null;
                    CitizenHabitualRouteService.invalidate(level(),settlementId,current,finalTarget);
                }
            }
            NavigationSegment road=now>=bypassRoadUntil?findRoadNavigationSegment(finalTarget):null;
            if(road!=null)return road;if(navigationSearchPending)return null;
            double dx=finalTarget.getX()+.5-getX(),dz=finalTarget.getZ()+.5-getZ(),length=Math.hypot(dx,dz);
            if(length<1)return null;
            double fx=dx/length,fz=dz/length;
            var candidates=new LinkedHashSet<BlockPos>();
            int[] advances=navigationJourney.allowance()==0?SEGMENT_ADVANCES:new int[]{22,18,14,10,6,3,0,-6,-12,-20};
            int[] offsets=navigationJourney.allowance()==0?SEGMENT_LATERAL_OFFSETS:new int[]{0,4,-4,8,-8,16,-16,24,-24};
            for(int advance:advances)for(int lateral:offsets){
                int x=(int)Math.floor(getX()+fx*advance-fz*lateral),z=(int)Math.floor(getZ()+fz*advance+fx*lateral);
                addSegmentStandingCandidates(candidates,x,z);
            }
            candidates.removeIf(candidate->candidate.distSqr(current)<4 || !navigationJourney.accepts(candidate,current,finalTarget,now)
                    || !permitsTransitWaypoint(candidate,finalTarget));
            var ordered=new ArrayList<>(candidates);
            ordered.sort(Comparator.comparingDouble((BlockPos at)->at.distSqr(finalTarget)).thenComparingDouble(at->at.distSqr(current)));
            segmentSearchTarget=finalTarget.immutable();segmentSearchOrigin=current.immutable();segmentSearchCandidates=List.copyOf(ordered);segmentSearchAllowance=navigationJourney.allowance();
        }
        long searchStart=navigationSearches;
        for(int budget=0;budget<64 && navigationSearches-searchStart<3 && segmentSearchCursor<segmentSearchCandidates.size();budget++){
            var at=segmentSearchCandidates.get(segmentSearchCursor);
            Path path=probeNavigationPath(at);
            if(navigationSearchPending)return null;
            segmentSearchCursor++;
            if(path!=null){segmentSearchTarget=null;return new NavigationSegment(at,path);}
        }
        if(segmentSearchCursor<segmentSearchCandidates.size()){
            NavigationSearchBudget.watch(this);
            navigationSearchPending=true;navigationSearchAfter=now+5;return null;
        }
        if(!acquireNavigationSearch())return null;
        Path prefix=ConstructionPlanningBudget.probeMany(this,List.of(finalTarget),()->{
            configurePathProbe();configureDestination(pathProbeNavigation,finalTarget);return pathProbeNavigation.createPath(finalTarget,0);
        });
        segmentSearchTarget=null;
        if(prefix!=null && prefix.getNodeCount()>1 && prefix.getEndNode()!=null){
            var at=prefix.getEndNode().asBlockPos();
            if(at.distSqr(current)>=4 && navigationJourney.accepts(at,current,finalTarget,now)
                    && permitsTransitWaypoint(at,finalTarget) && canStandAt(at))return new NavigationSegment(at,prefix);
        }
        return null;
    }

    @Nullable
    private NavigationSegment findRoadNavigationSegment(BlockPos finalTarget) {
        if (!(level() instanceof ServerLevel serverLevel)) return null;
        RoadSpaceData roads = RoadSpaceData.get(serverLevel);
        BlockPos current = navigationFeetPosition();
        BlockPos roadEntry = nearestRoadStandingPosition(roads, current, 8);
        if (roadEntry == null) return null;
        boolean alreadyOnRoad = roads.contains(current.below())
                || current.distSqr(roadEntry) <= 2.0D;
        if (!alreadyOnRoad) {
            double currentDistance = Math.sqrt(current.distSqr(finalTarget));
            double entryDistance = Math.sqrt(roadEntry.distSqr(finalTarget));
            if (entryDistance > currentDistance + 10.0D + navigationJourney.allowance() || !navigationJourney.accepts(roadEntry,current,finalTarget,level().getGameTime())) return null;
            Path entryPath = probeNavigationPath(roadEntry);
            return entryPath != null && entryPath.canReach()
                    ? new NavigationSegment(roadEntry, entryPath) : null;
        }

        java.util.ArrayDeque<RoadSearchNode> queue = new java.util.ArrayDeque<>();
        Map<BlockPos, Integer> visited = new HashMap<>();
        queue.add(new RoadSearchNode(roadEntry, 0));
        visited.put(roadEntry, 0);
        List<BlockPos> candidates = new ArrayList<>();
        while (!queue.isEmpty() && visited.size() <= 256) {
            RoadSearchNode node = queue.removeFirst();
            if (node.steps() >= 8 && node.position().distSqr(current) >= 64.0D) {
                candidates.add(node.position());
            }
            if (node.steps() >= 24) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = node.position().relative(direction).offset(0, dy, 0);
                    if (visited.containsKey(next) || !roads.contains(next.below()) || !canStandAt(next)) continue;
                    visited.put(next.immutable(), node.steps() + 1);
                    queue.addLast(new RoadSearchNode(next.immutable(), node.steps() + 1));
                    break;
                }
            }
        }
        double currentDistance = Math.sqrt(current.distSqr(finalTarget));
        candidates.removeIf(candidate -> Math.sqrt(candidate.distSqr(finalTarget)) > currentDistance + 6.0D + navigationJourney.allowance()
                || !navigationJourney.accepts(candidate,current,finalTarget,level().getGameTime())
                || !permitsTransitWaypoint(candidate, finalTarget)
                || recentRoadSegments.stream().anyMatch(previous -> previous.distSqr(candidate) < 9));
        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> roadCandidateScore(candidate, finalTarget))
                .thenComparingInt(candidate -> -roads.levelAt(candidate.below()))
                .thenComparing((left, right) -> Double.compare(right.distSqr(current), left.distSqr(current))));
        for (BlockPos candidate : candidates.stream().limit(3).toList()) {
            Path path = probeNavigationPath(candidate);
            if(navigationSearchPending)return null;
            if (path != null && path.canReach()) {
                recentRoadSegments.addLast(current.immutable());
                recentRoadSegments.addLast(candidate.immutable());
                while (recentRoadSegments.size() > 12) recentRoadSegments.removeFirst();
                return new NavigationSegment(candidate, path);
            }
        }
        return null;
    }

    @Nullable
    private BlockPos nearestRoadStandingPosition(RoadSpaceData roads, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    double distance = candidate.distSqr(origin);
                    if (distance >= bestDistance || !roads.contains(candidate.below()) || !canStandAt(candidate)) {
                        continue;
                    }
                    best = candidate.immutable();
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    @Nullable
    public BlockPos idleRoadDestination(int phase) {
        if (!(level() instanceof ServerLevel server)) return null;
        RoadSpaceData roads = RoadSpaceData.get(server);
        BlockPos origin = navigationFeetPosition(), entry = nearestRoadStandingPosition(roads, origin, 8);
        if (entry == null) return null;
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> visited = new java.util.HashSet<>();
        List<BlockPos> candidates = new ArrayList<>();
        queue.add(entry); visited.add(entry);
        while (!queue.isEmpty() && visited.size() <= 128) {
            BlockPos at = queue.removeFirst();
            if (at.distSqr(origin) >= 16 && at.distSqr(origin) <= 256) candidates.add(at);
            for (Direction side : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                BlockPos next = at.relative(side).offset(0, dy, 0);
                if (next.distSqr(origin) > 256 || visited.contains(next) || !roads.contains(next.below()) || !canStandAt(next)) continue;
                visited.add(next); queue.addLast(next); break;
            }
        }
        candidates.sort(Comparator.comparingLong(BlockPos::asLong));
        if (candidates.isEmpty()) return null;
        int start = Math.floorMod(getUUID().hashCode() + phase, candidates.size());
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            BlockPos candidate = candidates.get((start + i) % candidates.size());
            if (hasReachablePathTo(candidate)) return candidate;
        }
        return null;
    }

    private static double roadCandidateScore(
            BlockPos candidate, BlockPos finalTarget
    ) {
        // A higher-tier surface must not buy several blocks of extra separation.
        // Tier is only a tie-breaker after remaining distance in the caller.
        return Math.sqrt(candidate.distSqr(finalTarget));
    }

    private boolean permitsTransitWaypoint(BlockPos waypoint, BlockPos finalTarget) {
        configurePathProbe();
        configureDestination(pathProbeNavigation, finalTarget);
        return !(pathProbeNavigation instanceof CitizenGroundNavigation resident) || resident.permitsWaypoint(waypoint);
    }

    private void addSegmentStandingCandidates(Set<BlockPos> candidates, int x, int z) {
        BlockPos column = new BlockPos(x, blockPosition().getY(), z);
        if (!level().hasChunkAt(column)) return;
        int surfaceY = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int currentY = blockPosition().getY();
        int[] bases = {surfaceY, currentY, currentY + 2, currentY - 2};
        for (int base : bases) {
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos candidate = new BlockPos(x, base + dy, z);
                if (canStandAt(candidate)) candidates.add(candidate.immutable());
            }
        }
    }

    public void stopSettlementNavigation() {
        if(getNavigation() instanceof CitizenGroundNavigation live)live.detourAllowance(0);
        if(pathProbeNavigation instanceof CitizenGroundNavigation probe)probe.detourAllowance(0);
        if(physicalNavigationIntent!=null && distanceToSqr(physicalNavigationIntent.getX()+.5,physicalNavigationIntent.getY(),physicalNavigationIntent.getZ()+.5)<=.81)navigationJourney.arrived(level().getGameTime());
        navigationSearchPending=false;segmentSearchTarget=null;pendingNavigationSegment=null;
        getNavigation().stop();
        CitizenNavigationReservationService.releaseAll(level(), getUUID());
        commandedTarget = null;
        yieldPosition = null;
        yieldUntil = 0L;
        clearSpecialConstructionMovement();
        preparedPathTarget = null;
        preparedPath = null;
        longDistanceWaypoint = null;
        constructionMovementSpeed = 0.0D;
        constructionMovementFailed = false;
        stuckRecoveryAttempts = 0;
        trafficWaitUntil = 0L;
        resetProgress(level().getGameTime());
    }

    public boolean canStandAtPosition(BlockPos position) {
        return canStandAt(position);
    }

    /** Bed approaches may use real carpet, slab or bed surfaces, not only empty full-floor cells. */
    public boolean canUseBedStation(BlockPos position) {
        if (!level().hasChunkAt(position) || !level().getFluidState(position).isEmpty()
                || !level().getFluidState(position.above()).isEmpty()) return false;
        double halfWidth = getBbWidth() / 2.0D;
        for (BlockPos support : List.of(position, position.below())) {
            BlockState state = level().getBlockState(support);
            if (state.getBlock() instanceof TrapDoorBlock
                    && state.getValue(BlockStateProperties.OPEN)) continue;
            for (AABB shape : state.getCollisionShape(level(), support).toAabbs()) {
                double floorY = support.getY() + shape.maxY;
                if (floorY < position.getY() - 0.55D || floorY > position.getY() + 0.6D
                        || shape.minX > 0.5D - halfWidth || shape.maxX < 0.5D + halfWidth
                        || shape.minZ > 0.5D - halfWidth || shape.maxZ < 0.5D + halfWidth) continue;
                AABB body = new AABB(position.getX() + 0.5D - halfWidth, floorY + 0.001D,
                        position.getZ() + 0.5D - halfWidth, position.getX() + 0.5D + halfWidth,
                        floorY + getBbHeight(), position.getZ() + 0.5D + halfWidth);
                if (level().noCollision(this, body)) return true;
            }
        }
        return false;
    }

    /**
     * Includes scaffold cells and stable slab/stair construction surfaces in
     * addition to ordinary full-block standing cells. Cleanup route discovery
     * must use the same player-like footing rules as route execution.
     */
    public boolean canOccupyConstructionPosition(BlockPos position) {
        return canOccupyConstructionCell(position);
    }

    /**
     * Construction may finish on a slab or stair roof. The vanilla navigator can
     * walk those surfaces, but the ordinary integer-grid standing predicate only
     * accepts nearly full-height support. Represent a partial surface by the air
     * cell above it so navigation can approach without treating the roof block as
     * occupied feet space.
     */
    public boolean canUseConstructionSurfaceAtPosition(BlockPos position) {
        return canStandAt(position) || canUsePartialConstructionSurface(position);
    }

    public boolean isPartialConstructionSurfacePosition(BlockPos position) {
        return !canStandAt(position) && canUsePartialConstructionSurface(position);
    }

    public boolean hasReachablePathTo(BlockPos position) {
        preparedPathTarget = null;
        preparedPath = null;
        if (blockPosition().equals(position) && nearBlock(position)) {
            return true;
        }
        // A final work position farther away than one bounded pathfinding window is
        // approached through intermediate waypoints. Exact reachability is checked
        // again after the resident enters the worksite neighborhood.
        if (requiresSegmentedNavigation(position)) {
            return true;
        }
        Path path = probePathTo(position);
        if (path == null) return false;
        preparedPathTarget = position.immutable();
        preparedPath = path;
        return true;
    }

    /** Query one route to any legal bed-side station instead of probing each side. */
    @Nullable
    public BlockPos findReachableBedStation(List<BlockPos> stations) {
        preparedPathTarget = null;
        preparedPath = null;
        if (stations.isEmpty()) return null;
        for (BlockPos station : stations) {
            if (navigationFeetPosition().equals(station) && nearBlock(station)) return station;
        }
        // The bed is checked repeatedly while walking. Keep a valid in-flight
        // approach instead of rebuilding an identical multi-target search.
        if (commandedTarget != null && stations.contains(commandedTarget)
                && !getNavigation().isDone()) return commandedTarget;
        if (stations.getFirst().distSqr(navigationFeetPosition()) > DIRECT_NAVIGATION_DISTANCE_SQUARED) {
            return stations.getFirst();
        }
        configurePathProbe();
        configureDestination(pathProbeNavigation, stations.getFirst());
        Path path = ConstructionPlanningBudget.probeMany(this,stations,()->pathProbeNavigation.createPath(new LinkedHashSet<>(stations), 0));
        if (path == null || !path.canReach() || !stations.contains(path.getTarget())) return null;
        preparedPathTarget = path.getTarget().immutable();
        preparedPath = path;
        return path.getTarget();
    }

    /** Keep a real short movement in flight while the bed route is recalculated. */
    public boolean continueBedRecovery(BlockPos bed) {
        if (bedRecoveryWaypoint == null) return false;
        if(bed.equals(bedRecoveryGoal) && hasActiveConstructionMovement())return true;
        if (!bed.equals(bedRecoveryGoal) || nearBlock(bedRecoveryWaypoint)
                || level().getGameTime() >= bedRecoveryStepUntil || getNavigation().isDone()) {
            bedRecoveryWaypoint = null;
            return false;
        }
        return true;
    }

    public void clearBedRecovery() {
        bedRecoveryGoal = null;
        bedRecoveryWaypoint = null;
        bedRecoveryPreviousOrigin = null;
        bedRecoveryAttempts = 0;
        bedRecoveryRetryAt = 0L;
    }

    /** A bounded real detour may initially move away from the bed to get around an obstacle. */
    public boolean beginBedRecovery(BlockPos bed, double speed) {
        if (!bed.equals(bedRecoveryGoal)) {
            clearBedRecovery();
            bedRecoveryGoal = bed.immutable();
        }
        long now = level().getGameTime();
        if (now < bedRecoveryRetryAt) return false;
        if (bedRecoveryAttempts >= 2) {
            bedRecoveryAttempts = 0;
            bedRecoveryRetryAt = now + BED_RECOVERY_RETRY_TICKS;
            return false;
        }
        BlockPos origin = navigationFeetPosition();
        double currentDistance = origin.distSqr(bed);
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= 2; distance++) {
                BlockPos horizontal = origin.relative(direction, distance);
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos candidate = horizontal.offset(0, dy, 0);
                    if (!candidate.equals(bedRecoveryPreviousOrigin)
                            && canStandAtPosition(candidate)) candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos candidate) -> candidate.distSqr(bed) < currentDistance - 0.5D ? 0 : 1)
                .thenComparingDouble(candidate -> candidate.distSqr(bed)));
        BlockPos candidate = findReachableBedStation(candidates.stream()
                .filter(position -> position.distSqr(bed) < currentDistance - 0.5D).toList());
        if (candidate == null) candidate = findReachableBedStation(candidates);
        if (candidate != null && (beginLocalConstructionRoute(candidate,speed) || navigateTo(candidate, speed).isEmpty())) {
            bedRecoveryWaypoint = candidate.immutable();
            bedRecoveryPreviousOrigin = origin.immutable();
            bedRecoveryStepUntil = now + BED_RECOVERY_STEP_TICKS;
            bedRecoveryAttempts++;
            return true;
        }
        bedRecoveryRetryAt = now + BED_RECOVERY_RETRY_TICKS;
        return false;
    }

    /**
     * Validates an ordinary construction approach without mistaking a vanilla
     * path through scaffolding for a climb the ground navigator can execute.
     * Stairs and other real walkable height changes remain ordinary paths; a
     * vertical scaffold/ladder segment must be handled by the explicit
     * construction climb controller instead.
     */
    public boolean hasReachableConstructionPathTo(BlockPos position) {
        preparedPathTarget = null;
        preparedPath = null;
        if (position.getY() <= blockPosition().getY() + 1) {
            return hasReachablePathTo(position);
        }
        Path path = probePathTo(position);
        if (path == null) return false;
        if (!ordinaryConstructionPath(path)) return false;
        preparedPathTarget = position.immutable();
        preparedPath = path;
        return true;
    }

    private boolean ordinaryConstructionPath(Path path) {
        if (path.getTarget().getY() <= getBlockY() + 1) return true;
        for (int index = 1; index < path.getNodeCount(); index++) {
            var previous = path.getNode(index - 1);
            var current = path.getNode(index);
            if (previous.y == current.y) continue;
            BlockPos previousPosition = new BlockPos(previous.x, previous.y, previous.z);
            BlockPos currentPosition = new BlockPos(current.x, current.y, current.z);
            boolean climbableTransition = isClimbableCell(previousPosition)
                    || isClimbableCell(previousPosition.below())
                    || isClimbableCell(currentPosition)
                    || isClimbableCell(currentPosition.below());
            if (climbableTransition) {
                preparedPathTarget = null;
                preparedPath = null;
                return false;
            }
        }
        return true;
    }

    /** One bounded vanilla search for a set of legal stations, not one flood per station. */
    @Nullable
    public BlockPos findReachableConstructionStation(List<BlockPos> candidates) {
        preparedPathTarget = null;
        preparedPath = null;
        for (BlockPos candidate : candidates) {
            if (candidate.getY() <= getBlockY() + 1
                    && (blockPosition().equals(candidate) && nearBlock(candidate)
                    || requiresSegmentedNavigation(candidate))) return candidate;
        }
        List<BlockPos> remaining = new ArrayList<>(candidates);
        while (!remaining.isEmpty()) {
            List<BlockPos> query = List.copyOf(remaining);
            Path path = ConstructionPlanningBudget.probeMany(this, query, () -> {
                configurePathProbe();
                Path result = pathProbeNavigation.createPath(new LinkedHashSet<>(query), 0);
                return result != null && result.canReach() && query.contains(result.getTarget()) ? result : null;
            });
            if (path == null) break;
            BlockPos selected = path.getTarget();
            if (ordinaryConstructionPath(path)) {
                preparedPathTarget = selected.immutable();
                preparedPath = path;
                return selected;
            }
            // A ground path can describe an unexecutable vertical scaffold leg.
            // Keep explicit climbing, then try another station if that fails.
            if (hasExistingClimbableRouteTo(selected)) return selected;
            remaining.remove(selected);
        }
        for (BlockPos candidate : candidates) {
            if (hasExistingClimbableRouteTo(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Vanilla createPath is not a read-only query: it changes targetPos and the
     * stuck timer, and can return the currently executing path for that target.
     * Candidate searches must therefore never use the live navigator. This
     * navigator only queries the same world/entity and is never moved or ticked.
     */
    @Nullable
    private Path probePathTo(BlockPos position) {
        return ConstructionPlanningBudget.probe(this, position, () -> computeProbePathTo(position));
    }

    /** Full read-only path for return proofs; no segmented reachability shortcut. */
    @Nullable
    public Path probeConstructionReturnPath(BlockPos position) { return probePathTo(position); }

    @Nullable
    private Path computeProbePathTo(BlockPos position) {
        configurePathProbe();
        configureDestination(pathProbeNavigation, position);
        Path path = pathProbeNavigation.createPath(position, 0);
        return path != null && path.canReach() && position.equals(path.getTarget()) ? path : null;
    }

    private void configurePathProbe() {
        if (pathProbeNavigation == null) {
            pathProbeNavigation = new CitizenGroundNavigation(this, level());
        }
        var liveEvaluator = getNavigation().getNodeEvaluator();
        var probeEvaluator = pathProbeNavigation.getNodeEvaluator();
        probeEvaluator.setCanOpenDoors(liveEvaluator.canOpenDoors());
        probeEvaluator.setCanPassDoors(liveEvaluator.canPassDoors());
        probeEvaluator.setCanFloat(liveEvaluator.canFloat());
        probeEvaluator.setCanWalkOverFences(liveEvaluator.canWalkOverFences());
    }

    /** Waiting for CPU, with no movement in flight, is not a failed physical route. */
    public void excludeConstructionPlanningWait(long ticks) {
        if (ticks <= 0L || !getNavigation().isDone() || constructionStepTarget != null) return;
        if (constructionRouteTaskId != null) constructionRouteTaskStartedGameTime += ticks;
        if (trackedConstructionRoute != null) trackedConstructionRouteLastApproachGameTime += ticks;
        lastProgressGameTime += ticks;
        routeLastApproachGameTime += ticks;
    }

    /**
     * A selected destination is the only place a query becomes a navigation
     * command. Recompute once on commitment: vanilla moveTo(Path) alone does not
     * update targetPos/reachRange for later obstacle-triggered recomputation.
     * Unchanged active paths still bypass this method in navigateTo.
     */
    private boolean commitProbedDestination(BlockPos position, double speed) {
        ConstructionPlanningBudget.beforeNavigationCommit(this);
        Path path = getNavigation().createPath(position, 0);
        return path != null && path.canReach() && position.equals(path.getTarget())
                && getNavigation().moveTo(path, speed);
    }

    public boolean hasLongDistanceWaypoint() {
        return longDistanceWaypoint != null;
    }

    @Nullable
    public BlockPos upcomingPassageReservationKey(BlockPos target) {
        PassageIntent intent = findUpcomingPassage(target, true);
        return intent == null ? null : intent.key();
    }

    public int upcomingPassageReservationSpan(BlockPos target) {
        PassageIntent intent = findUpcomingPassage(target, true);
        return intent == null ? 0 : intent.cells().size();
    }

    public boolean yieldingForTraffic() {
        return yieldPosition != null && level().getGameTime() < yieldUntil;
    }

    @Nullable
    public BlockPos trafficYieldPosition() {
        return yieldPosition;
    }

    public void deferConstructionTarget(BlockPos position, long durationTicks) {
        deferredConstructionTargets.put(position.immutable(), level().getGameTime() + durationTicks);
    }

    public boolean constructionTargetDeferred(BlockPos position) {
        long gameTime = level().getGameTime();
        deferredConstructionTargets.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
        return deferredConstructionTargets.getOrDefault(position, 0L) > gameTime;
    }

    /** Starts route accounting for one placement inside one blueprint work order. */
    public void beginConstructionRouteTask(UUID taskId, BlockPos target) {
        BlockPos immutable = target.immutable();
        if (taskId.equals(constructionRouteTaskId)
                && immutable.equals(constructionRouteTaskTarget)) return;
        constructionRouteTaskId = taskId;
        constructionRouteTaskTarget = immutable;
        constructionRouteTaskStartedGameTime = level().getGameTime();
        constructionRouteTaskBestDistance = constructionRouteDistance(immutable);
        trackedConstructionRoute = null;
        trackedConstructionRouteDestination = null;
        trackedConstructionRouteBestDistance = Double.POSITIVE_INFINITY;
        trackedConstructionRouteLastApproachGameTime = level().getGameTime();
        // Failure history is retained for the blueprint, target and physical
        // approach route. The same target cannot evade its three-attempt limit
        // by shifting the final station, while an unrelated block on an open
        // side of the same building is still allowed to plan its own route.
        if (constructionRouteFailures.size() > 256) constructionRouteFailures.clear();
    }

    /**
     * Bounds a complete, geometry-driven attempt for one blueprint block. Local
     * legs may each succeed while the worker still cycles between the same
     * scaffold, roof edge and ground approach forever, so per-leg stuck timers
     * cannot detect this higher-level lack of construction output.
     */
    public boolean constructionTargetAttemptTimedOut() {
        if (constructionRouteTaskId == null || constructionRouteTaskTarget == null) return false;
        long gameTime = level().getGameTime();
        double distance = constructionRouteDistance(constructionRouteTaskTarget);
        if (distance <= constructionRouteTaskBestDistance - TARGET_PROGRESS_DISTANCE) {
            // Walking a valid existing scaffold/stair route is meaningful
            // target progress even though it does not place another temporary
            // block. The old fixed 400-tick clock repeatedly sent a resident
            // back to the route root while it was visibly approaching a second
            // storey. Only a new best distance refreshes this watchdog, so
            // shuffling over the same cells still expires.
            constructionRouteTaskBestDistance = distance;
            constructionRouteTaskStartedGameTime = gameTime;
            return false;
        }
        boolean timedOut = constructionRouteTaskId != null
                && constructionRouteTaskTarget != null
                && gameTime - constructionRouteTaskStartedGameTime
                >= CONSTRUCTION_TARGET_NO_OUTPUT_TIMEOUT_TICKS;
        if (timedOut) {
            // Retiring an attempt starts a fresh bounded recovery window. If a
            // cleanup target has no alternative, leaving the old timestamp in
            // place makes every following tick time out before the resident can
            // descend or build a replacement access route.
            constructionRouteTaskStartedGameTime = gameTime;
        }
        return timedOut;
    }

    /**
     * Extending temporary access counts as target progress only when it also
     * establishes a new best physical distance to the requested block. Merely
     * replacing or growing scaffolding around the same roof pocket must not
     * refresh the watchdog forever; bounded detours remain below the target
     * timeout and ordinary movement refreshes it as soon as the resident truly
     * approaches the work cell.
     */
    public void noteConstructionAccessProgress() {
        if (constructionRouteTaskId != null && constructionRouteTaskTarget != null) {
            double distance = constructionRouteDistance(constructionRouteTaskTarget);
            if (distance <= constructionRouteTaskBestDistance - TARGET_PROGRESS_DISTANCE) {
                constructionRouteTaskBestDistance = distance;
                constructionRouteTaskStartedGameTime = level().getGameTime();
            }
        }
    }

    public boolean constructionRouteAllowed(BlockPos target, BlockPos route) {
        // Only prepareConstructionReach owns the lifetime of a blueprint-block
        // task. Helper planners may temporarily ask whether a descent entry or
        // scaffold node is reachable; treating that helper target as a new task
        // erased the failure counter before the third attempt and defeated the
        // blacklist entirely.
        if (constructionRouteTaskId == null) return true;
        ConstructionRouteKey key = new ConstructionRouteKey(
                constructionRouteTaskId, target.immutable(), route.immutable());
        return constructionRouteFailures.getOrDefault(key, 0)
                < CONSTRUCTION_ROUTE_FAILURE_LIMIT;
    }

    public void trackConstructionRoute(BlockPos route) {
        trackConstructionRoute(route, route);
    }

    public void trackConstructionRoute(BlockPos route, BlockPos destination) {
        if (constructionRouteTaskId == null || constructionRouteTaskTarget == null) return;
        ConstructionRouteKey key = new ConstructionRouteKey(
                constructionRouteTaskId, constructionRouteTaskTarget.immutable(),
                route.immutable());
        if (key.equals(trackedConstructionRoute)
                && destination.equals(trackedConstructionRouteDestination)) return;
        trackedConstructionRoute = key;
        trackedConstructionRouteDestination = destination.immutable();
        trackedConstructionRouteBestDistance = constructionRouteDistance(destination);
        trackedConstructionRouteLastApproachGameTime = level().getGameTime();
    }

    public int recordTrackedConstructionRouteFailure() {
        if (trackedConstructionRoute == null) return 0;
        int previous = constructionRouteFailures.getOrDefault(trackedConstructionRoute, 0);
        if (previous >= CONSTRUCTION_ROUTE_FAILURE_LIMIT) return previous;
        int failures = previous + 1;
        constructionRouteFailures.put(trackedConstructionRoute, failures);
        trackedConstructionRouteBestDistance = trackedConstructionRouteDestination == null
                ? Double.POSITIVE_INFINITY
                : constructionRouteDistance(trackedConstructionRouteDestination);
        trackedConstructionRouteLastApproachGameTime = level().getGameTime();
        recordConstructionAccessFailure("ROUTE_REJECTED_" + failures);
        return failures;
    }

    /**
     * Uses the construction route identity rather than commandedTarget. Failed
     * special-move probes can briefly clear or replace commandedTarget before an
     * ordinary path is resumed; they must not reset this route's no-progress
     * clock and let a motionless path survive for the rest of the work day.
     */
    public boolean constructionRouteApproachTimedOut() {
        if (trackedConstructionRoute == null
                || trackedConstructionRouteDestination == null) return false;
        double distance = constructionRouteDistance(trackedConstructionRouteDestination);
        long gameTime = level().getGameTime();
        if (distance <= trackedConstructionRouteBestDistance - TARGET_PROGRESS_DISTANCE) {
            trackedConstructionRouteBestDistance = distance;
            trackedConstructionRouteLastApproachGameTime = gameTime;
            return false;
        }
        if (gameTime - trackedConstructionRouteLastApproachGameTime
                < ROUTE_NO_APPROACH_TIMEOUT_TICKS) return false;
        recordTrackedConstructionRouteFailure();
        getNavigation().stop();
        commandedTarget = null;
        resetProgress(gameTime);
        return true;
    }

    public boolean trackedConstructionRouteExhausted() {
        return trackedConstructionRoute != null
                && constructionRouteFailures.getOrDefault(trackedConstructionRoute, 0)
                >= CONSTRUCTION_ROUTE_FAILURE_LIMIT;
    }

    public void completeTrackedConstructionRoute() {
        if (trackedConstructionRoute != null) {
            constructionRouteFailures.remove(trackedConstructionRoute);
        }
        releaseTrackedConstructionRoute();
    }

    /**
     * Ends the current attempt without forgiving its blueprint-wide failure
     * history. Reaching ordinary ground after a rejected scaffold route is a
     * safe retreat, not proof that the route can reach and place its target.
     */
    public void releaseTrackedConstructionRoute() {
        trackedConstructionRoute = null;
        trackedConstructionRouteDestination = null;
        trackedConstructionRouteBestDistance = Double.POSITIVE_INFINITY;
        trackedConstructionRouteLastApproachGameTime = level().getGameTime();
    }

    /**
     * A complete temporary-access teardown changes every physical scaffold route.
     * Final blueprint cells that were hidden underneath those supports are a new
     * construction phase and must not inherit blacklists for the dismantled
     * topology. This reset is intentionally unavailable while any old footing
     * remains, so one failing route still receives at most three attempts.
     */
    public void resetConstructionRoutesAfterAccessTeardown() {
        constructionRouteFailures.clear();
        releaseTrackedConstructionRoute();
        constructionRouteTaskId = null;
        constructionRouteTaskTarget = null;
        constructionRouteTaskBestDistance = Double.POSITIVE_INFINITY;
        constructionRouteTaskStartedGameTime = level().getGameTime();
    }

    private double constructionRouteDistance(BlockPos route) {
        return Math.sqrt(distanceToSqr(
                route.getX() + 0.5D, route.getY(), route.getZ() + 0.5D));
    }

    /**
     * Collapses candidate stations that share the same first approach corridor.
     * The complete destination is still used for movement and progress; only the
     * three-failure budget is shared by paths whose first three real steps are
     * identical. This prevents a blocked roof edge from masquerading as dozens
     * of unrelated routes merely because its final interaction cells differ.
     */
    public BlockPos preparedConstructionRouteIdentity(BlockPos destination) {
        if (!destination.equals(preparedPathTarget) || preparedPath == null
                || preparedPath.getNodeCount() == 0) return destination.immutable();
        BlockPos start = blockPosition();
        BlockPos identity = destination.immutable();
        for (int index = 0; index < preparedPath.getNodeCount(); index++) {
            var node = preparedPath.getNode(index);
            BlockPos position = new BlockPos(node.x, node.y, node.z);
            identity = position.immutable();
            if (position.distManhattan(start) >= 3) break;
        }
        return identity;
    }

    public boolean canStepOntoAdjacentConstructionBlock(BlockPos standingPosition) {
        BlockPos current = blockPosition();
        int dx = Math.abs(standingPosition.getX() - current.getX());
        int dz = Math.abs(standingPosition.getZ() - current.getZ());
        return dx + dz == 1
                && standingPosition.getY() - current.getY() == 1
                && canOccupyConstructionCell(standingPosition)
                && constructionStepRouteClear(standingPosition);
    }

    public boolean beginConstructionStep(BlockPos standingPosition, double speed) {
        if (!canStepOntoAdjacentConstructionBlock(standingPosition)) return false;
        return beginAdjacentConstructionMovement(standingPosition, speed);
    }

    public boolean beginAdjacentConstructionMovement(BlockPos standingPosition, double speed) {
        // Work polls are not new movement commands. Keep progress, centering
        // and the original stall deadline when the same local step is active.
        if (standingPosition.equals(constructionStepTarget)
                || standingPosition.equals(pendingCenteredRiseTarget)) return true;
        BlockPos current = normalizeEmbeddedConstructionStandingPosition();
        int dx = Math.abs(standingPosition.getX() - current.getX());
        int dz = Math.abs(standingPosition.getZ() - current.getZ());
        int dy = standingPosition.getY() - current.getY();
        boolean occupiable = canOccupyConstructionCell(standingPosition);
        boolean routeClear = constructionStepRouteClear(standingPosition);
        if (dx + dz != 1 || dy < -1 || dy > 1 || !occupiable || !routeClear) {
            constructionLastAbort = "BEGIN_ADJACENT_REJECT:current=" + current
                    + ",target=" + standingPosition + ",delta=" + dx + "/" + dy + "/" + dz
                    + ",occupiable=" + occupiable + ",routeClear=" + routeClear;
            return false;
        }
        // Crouch is reserved for moving down/inside a scaffold column. Carrying
        // it into an ordinary adjacent step makes vanilla immediately drop the
        // resident back through the scaffold top after the controller reaches
        // the destination, replaying the same rise forever.
        setShiftKeyDown(false);
        if (dy > 0 && !isCenteredOnConstructionBlock(current)) {
            BlockPos finalTarget = standingPosition.immutable();
            if (!beginConstructionCentering(current, speed)) return false;
            pendingCenteredRiseTarget = finalTarget;
            return true;
        }
        constructionStepTarget = standingPosition.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        constructionClimbColumn = null;
        constructionClimbTopY = 0;
        constructionClimbDescending = false;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = false;
        // Every bounded adjacent construction move is driven explicitly after
        // both standing cells and the swept route were validated. Gravity can
        // otherwise pull a resident a fraction below Y while crossing the seam
        // between two scaffold/cobblestone supports; the controller then spends
        // every following tick re-climbing that fraction and never crosses the
        // edge. Keep gravity suspended only for this one-cell move and restore it
        // as soon as the target is settled or the move aborts.
        setNoGravity(true);
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = constructionStepTarget;
        yieldPosition = null;
        yieldUntil = 0L;
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        // The first controlled slice can discover a real collision that the
        // block-grid precheck could not represent (fence rim, stair shape,
        // freshly changed scaffold). Do not report a route as started after its
        // very first slice already aborted; callers must immediately try the
        // next legal station instead of repeating an inactive local route.
        return !constructionMovementFailed;
    }

    /**
     * Begins the first, level leg of a diagonal one-block rise. Vanilla may
     * report the diagonal higher destination as a completed one-node path, so a
     * resident explicitly walks to one of the two cardinal intermediate cells,
     * remembers the final destination, then performs the normal centred rise.
     * Keeping both legs in one movement prevents the work scheduler from selecting
     * a different target between them and making the resident oscillate on a roof.
     */
    public boolean beginDiagonalConstructionApproach(BlockPos standingPosition, double speed) {
        BlockPos current = blockPosition();
        int dx = standingPosition.getX() - current.getX();
        int dz = standingPosition.getZ() - current.getZ();
        int dy = standingPosition.getY() - current.getY();
        if (Math.abs(dx) != 1 || Math.abs(dz) != 1 || dy != 1) return false;
        for (BlockPos intermediate : List.of(
                current.offset(Integer.signum(dx), 0, 0),
                current.offset(0, 0, Integer.signum(dz)))) {
            if (beginAdjacentConstructionMovement(intermediate, speed)) {
                pendingDiagonalRiseTarget = standingPosition.immutable();
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a short sequence of ordinary player-like construction steps around a
     * local obstruction. This is used when the direct rise is blocked by an eave:
     * the resident walks around on existing ground/footings and rises only from a
     * cell with two blocks of headroom. No template-specific route is encoded.
     */
    public boolean beginLocalConstructionRoute(BlockPos standingPosition, double speed) {
        BlockPos start = normalizeEmbeddedConstructionStandingPosition();
        if (start.equals(standingPosition)
                || start.distManhattan(standingPosition) > 18
                || Math.abs(start.getY() - standingPosition.getY()) > 8
                || !canOccupyConstructionCell(standingPosition)) return false;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Map<BlockPos, Integer> depths = new HashMap<>();
        queue.add(start.immutable());
        depths.put(start.immutable(), 0);
        BlockPos reached = null;
        int examined = 0;
        while (!queue.isEmpty() && examined++ < 256) {
            BlockPos current = queue.removeFirst();
            int depth = depths.get(current);
            if (current.equals(standingPosition)) {
                reached = current;
                break;
            }
            if (depth >= 16) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int rise = -1; rise <= 1; rise++) {
                    BlockPos next = current.relative(direction).above(rise).immutable();
                    if (depths.containsKey(next)
                            || next.distManhattan(start) > 18
                            || !canOccupyConstructionCell(next)
                            || rise > 0 && !constructionStepRouteClearFrom(current)) continue;
                    parents.put(next, current);
                    depths.put(next, depth + 1);
                    queue.addLast(next);
                }
            }
        }
        if (reached == null) return false;

        List<BlockPos> route = new ArrayList<>();
        for (BlockPos cursor = reached; !cursor.equals(start); cursor = parents.get(cursor)) {
            if (cursor == null) return false;
            route.add(cursor);
        }
        Collections.reverse(route);
        if (route.isEmpty()) return false;
        pendingLocalConstructionRoute.clear();
        BlockPos first = route.removeFirst();
        pendingLocalConstructionRoute.addAll(route);
        // beginAdjacentConstructionMovement drives once immediately. A very
        // short first leg can therefore complete before this method returns.
        // Publish the remaining route first so that same-cycle completion can
        // advance to step two instead of clearing the movement and orphaning
        // the rest of the route forever.
        if (!beginAdjacentConstructionMovement(first, speed)) {
            pendingLocalConstructionRoute.clear();
            return false;
        }
        return true;
    }

    /**
     * Follows a caller-provided, persisted construction-access chain without
     * searching across nearby roof or structure surfaces. Every cell and every
     * one-block transition is revalidated against the live world before the
     * route starts; completion still advances one visible local step at a time.
     */
    public boolean beginRecordedConstructionRoute(List<BlockPos> route, double speed) {
        if (route.isEmpty()) {
            constructionLastAbort = "RECORDED_ROUTE_EMPTY";
            return false;
        }
        BlockPos previous = normalizeEmbeddedConstructionStandingPosition();
        List<BlockPos> checked = new ArrayList<>();
        for (BlockPos raw : route) {
            BlockPos next = raw.immutable();
            if (next.equals(previous)) continue;
            int horizontal = Math.abs(next.getX() - previous.getX())
                    + Math.abs(next.getZ() - previous.getZ());
            int rise = next.getY() - previous.getY();
            boolean scaffoldColumnLeg = isRecordedScaffoldingColumnLeg(previous, next);
            if (!canTraverseRecordedConstructionEdge(previous, next)) {
                constructionLastAbort = "RECORDED_ROUTE_INVALID:" + previous + "->" + next
                        + ":horizontal=" + horizontal + ":rise=" + rise
                        + ":scaffold=" + scaffoldColumnLeg
                        + ":occupy=" + canOccupyConstructionCell(next);
                return false;
            }
            checked.add(next);
            previous = next;
        }
        if (checked.isEmpty()) {
            constructionLastAbort = "RECORDED_ROUTE_ALREADY_THERE";
            return false;
        }
        pendingLocalConstructionRoute.clear();
        BlockPos first = checked.removeFirst();
        pendingLocalConstructionRoute.addAll(checked);
        if (!beginRecordedConstructionRouteLeg(first, speed)) {
            constructionLastAbort = "RECORDED_ROUTE_FIRST_LEG:" + first;
            pendingLocalConstructionRoute.clear();
            return false;
        }
        return true;
    }

    /** Same edge contract used by the site graph and by recorded-route execution. */
    public boolean canTraverseRecordedConstructionEdge(BlockPos previous, BlockPos next) {
        int horizontal = Math.abs(next.getX() - previous.getX()) + Math.abs(next.getZ() - previous.getZ());
        int rise = next.getY() - previous.getY();
        boolean column = isRecordedScaffoldingColumnLeg(previous, next);
        if (horizontal != 0 && level().getBlockState(next).is(Blocks.SCAFFOLDING)
                && !level().getBlockState(next.above()).is(Blocks.SCAFFOLDING)
                && !level().getBlockState(next.below()).isFaceSturdy(level(), next.below(), Direction.UP)) {
            // A suspended horizontal scaffold is crossed on its top, not by
            // treating the open-looking body as a normal floor-height cell.
            // Vertical entry/exit and a ground-supported shaft remain legal.
            return false;
        }
        return (column || horizontal == 1) && rise >= -1 && rise <= 1
                && canOccupyConstructionCell(next)
                && (rise <= 0 || column || constructionStepRouteClearFrom(previous))
                && recordedConstructionSweepClear(previous, next);
    }

    // Match the executor: rise before crossing, descend only after crossing.
    // Checking just the destination misses eaves intersecting the swept body.
    private boolean recordedConstructionSweepClear(BlockPos from, BlockPos to) {
        AABB start = getBoundingBox().move(from.getX() + 0.5D - getX(),
                from.getY() - getY(), from.getZ() + 0.5D - getZ());
        double rise = Math.max(0, to.getY() - from.getY());
        AABB raised = start.move(0, rise, 0);
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        AABB crossed = raised.move(dx, 0, dz);
        return constructionScaffoldingBoundsClear(start.expandTowards(0, rise, 0))
                && constructionScaffoldingBoundsClear(raised.expandTowards(dx, 0, dz))
                && constructionScaffoldingBoundsClear(crossed.expandTowards(0,
                        Math.min(0, to.getY() - from.getY()), 0));
    }

    /**
     * Aligns the resident with the centre of its current footing before a vertical
     * construction rise. A player cannot safely pillar upward while hanging over
     * an edge, and the ordinary navigator deliberately considers any position in
     * the same block "arrived", so the final sub-block alignment is handled here.
     */
    public boolean beginConstructionCentering(BlockPos standingPosition, double speed) {
        if (!navigationFeetPosition().equals(standingPosition) || !canStandAt(standingPosition)) return false;
        setShiftKeyDown(false);
        constructionStepTarget = standingPosition.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionClimbColumn = null;
        constructionClimbTopY = 0;
        constructionCentering = true;
        constructionLeap = false;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = constructionStepTarget;
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(false);
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    public boolean isCenteredOnConstructionBlock(BlockPos position) {
        double dx = getX() - (position.getX() + 0.5D);
        double dz = getZ() - (position.getZ() + 0.5D);
        return navigationFeetPosition().equals(position) && dx * dx + dz * dz <= 0.0025D;
    }

    /** Begins the jump phase used before a resident places a support under their own feet. */
    public boolean beginVerticalPillarRise(BlockPos supportPosition, double speed) {
        BlockPos current = navigationFeetPosition();
        BlockPos standing = supportPosition.above();
        if (!supportPosition.equals(current)
                || !isCenteredOnConstructionBlock(supportPosition)
                || !level().getBlockState(supportPosition).isAir()
                || !level().getFluidState(supportPosition).isEmpty()
                || !level().getBlockState(standing).getCollisionShape(level(), standing).isEmpty()
                || !level().getFluidState(standing).isEmpty()
                || !level().getBlockState(standing.above()).getCollisionShape(level(), standing.above()).isEmpty()
                || !level().getFluidState(standing.above()).isEmpty()
                || !level().noCollision(this, getBoundingBox().move(0.0D, 1.0D, 0.0D))) return false;
        setShiftKeyDown(false);
        constructionStepTarget = standing.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        verticalPillarSupport = supportPosition.immutable();
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = false;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = standing.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /**
     * Uses a bounded player-like leap for a single missing floor cell. This is
     * only a final local construction move; long-distance travel still uses the
     * normal path navigator and passage reservations.
     */
    public boolean beginConstructionLeapMovement(BlockPos landingPosition, double speed) {
        BlockPos current = blockPosition();
        int dx = landingPosition.getX() - current.getX();
        int dz = landingPosition.getZ() - current.getZ();
        int dy = landingPosition.getY() - current.getY();
        int horizontal = Math.abs(dx) + Math.abs(dz);
        if (horizontal != 2 || Math.abs(dx) == 1 && Math.abs(dz) == 1
                || dy < -1 || dy > 1 || !canStandAt(landingPosition)) return false;
        BlockPos middle = current.offset(Integer.signum(dx), Math.max(0, dy), Integer.signum(dz));
        if (!level().getBlockState(middle).getCollisionShape(level(), middle).isEmpty()
                || !level().getBlockState(middle.above()).getCollisionShape(level(), middle.above()).isEmpty()
                || !level().getFluidState(middle).isEmpty()
                || !level().getFluidState(middle.above()).isEmpty()) return false;
        setShiftKeyDown(false);
        constructionStepTarget = landingPosition.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = true;
        constructionLeapApexY = Math.max(getY(), landingPosition.getY()) + 0.65D;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = landingPosition.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /**
     * Steps from a roof edge into one adjacent clear column and performs a
     * bounded, collision-checked descent. This is the construction equivalent
     * of a player walking off an eave after temporary access was reclaimed; it
     * is deliberately limited to a short drop and never teleports through a
     * wall, floor, fluid, or occupied landing cell.
     */
    public boolean beginConstructionDropMovement(BlockPos landingPosition, double speed) {
        BlockPos current = blockPosition();
        if (!canBeginConstructionDropFrom(current, landingPosition)) return false;

        // First prove that the resident can cross the current roof edge at its
        // present height. Then prove the complete destination column down to
        // the landing feet/head cells. The movement controller repeats live
        // collision checks while moving, so a concurrent block change still
        // aborts the attempt safely.
        AABB crossedEdge = getBoundingBox().move(
                landingPosition.getX() + 0.5D - getX(), 0.0D,
                landingPosition.getZ() + 0.5D - getZ());
        if (!level().noCollision(this, crossedEdge)) return false;
        for (int y = landingPosition.getY(); y <= current.getY() + 1; y++) {
            BlockPos columnCell = new BlockPos(landingPosition.getX(), y,
                    landingPosition.getZ());
            BlockState state = level().getBlockState(columnCell);
            if (!state.getCollisionShape(level(), columnCell).isEmpty()
                    || !state.getFluidState().isEmpty()) return false;
        }

        setShiftKeyDown(false);
        constructionStepTarget = landingPosition.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionClimbColumn = null;
        constructionClimbTopY = 0;
        constructionClimbDescending = false;
        constructionCentering = false;
        constructionLeap = true;
        // Unlike the two-block construction leap, an egress drop must never
        // rise into the roof above the resident before crossing the edge.
        constructionLeapApexY = getY();
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = landingPosition.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /** Geometry preflight used while selecting a reachable roof-edge launch. */
    public boolean canBeginConstructionDropFrom(BlockPos launch, BlockPos landingPosition) {
        int dx = landingPosition.getX() - launch.getX();
        int dz = landingPosition.getZ() - launch.getZ();
        int drop = launch.getY() - landingPosition.getY();
        if (Math.abs(dx) + Math.abs(dz) != 1 || drop < 2 || drop > 6
                || !canStandAt(landingPosition)) return false;

        for (BlockPos cell : List.of(launch, launch.above())) {
            BlockState state = level().getBlockState(cell);
            if (!state.getCollisionShape(level(), cell).isEmpty()
                    || !state.getFluidState().isEmpty()) return false;
        }
        for (int y = landingPosition.getY(); y <= launch.getY() + 1; y++) {
            BlockPos columnCell = new BlockPos(landingPosition.getX(), y,
                    landingPosition.getZ());
            BlockState state = level().getBlockState(columnCell);
            if (!state.getCollisionShape(level(), columnCell).isEmpty()
                    || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** Uses an existing ladder or scaffolding column as a local construction route. */
    public boolean beginExistingClimbableMovement(BlockPos landingPosition, double speed) {
        ExistingClimbRoute route = findExistingClimbRoute(landingPosition, false);
        if (route == null || !nearBlock(route.entry())) return false;
        constructionStepTarget = landingPosition.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        constructionClimbColumn = route.column().immutable();
        constructionClimbTopY = route.topY();
        constructionClimbDescending = false;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = false;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = landingPosition.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        getNavigation().stop();
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /**
     * Moves to a working height inside a vanilla scaffolding column. Unlike the
     * ordinary climb, this deliberately does not exit onto the top platform: a
     * player can stop halfway up scaffolding and interact with a nearby block,
     * and builders need the same ability to recover temporary supports without
     * creating another staircase beside the finished building.
     */
    public boolean beginScaffoldingWorkHeight(BlockPos scaffoldCell, double speed) {
        return beginScaffoldingWorkHeight(scaffoldCell, speed, true);
    }

    private boolean beginScaffoldingWorkHeight(
            BlockPos scaffoldCell, double speed, boolean clearPendingRoute
    ) {
        boolean exposedTop = !clearPendingRoute && isClimbableCell(scaffoldCell.below())
                && canOccupyConstructionCell(scaffoldCell);
        if (!level().getBlockState(scaffoldCell).is(Blocks.SCAFFOLDING) && !exposedTop) return false;
        if (scaffoldCell.equals(constructionStepTarget) && constructionClimbColumn != null) {
            constructionMovementSpeed = speed;
            return true;
        }
        int bottomY = scaffoldCell.getY();
        while (bottomY > level().getMinBuildHeight()
                && level().getBlockState(new BlockPos(
                scaffoldCell.getX(), bottomY - 1, scaffoldCell.getZ())).is(Blocks.SCAFFOLDING)) {
            bottomY--;
        }
        int topY = scaffoldCell.getY();
        while (topY < level().getMaxBuildHeight() - 1
                && level().getBlockState(new BlockPos(
                scaffoldCell.getX(), topY + 1, scaffoldCell.getZ())).is(Blocks.SCAFFOLDING)) {
            topY++;
        }
        BlockPos current = BlockPos.containing(getX(), getY() + 0.05D, getZ());
        int horizontal = Math.abs(current.getX() - scaffoldCell.getX())
                + Math.abs(current.getZ() - scaffoldCell.getZ());
        boolean alreadyInColumn = current.getX() == scaffoldCell.getX()
                && current.getZ() == scaffoldCell.getZ()
                && current.getY() >= bottomY - 1 && current.getY() <= topY + 1;
        if (!alreadyInColumn && (horizontal > 1
                || current.getY() < bottomY - 1 || current.getY() > topY + 8
                || !canStandAt(current))) return false;

        double targetX = scaffoldCell.getX() + 0.5D;
        double targetZ = scaffoldCell.getZ() + 0.5D;
        AABB targetBounds = getBoundingBox().move(
                targetX - getX(), scaffoldCell.getY() - getY(), targetZ - getZ());
        if (!constructionScaffoldingBoundsClear(targetBounds)) {
            constructionLastAbort = "SCAFFOLD_WORK_HEIGHT_BLOCKED:" + scaffoldCell
                    + ":blockers=" + constructionScaffoldingBlockers(targetBounds);
            return false;
        }
        boolean descending = getY() > scaffoldCell.getY() + 0.05D;
        constructionStepTarget = scaffoldCell.immutable();
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        if (clearPendingRoute) pendingLocalConstructionRoute.clear();
        constructionClimbColumn = descending
                ? scaffoldCell.immutable()
                : new BlockPos(scaffoldCell.getX(), bottomY, scaffoldCell.getZ());
        constructionClimbTopY = descending ? topY : scaffoldCell.getY() - (exposedTop ? 1 : 0);
        constructionClimbDescending = descending;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = false;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = scaffoldCell.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        // The target is a work height inside the scaffold body, not the exposed
        // top platform. Vanilla scaffolding lifts a non-crouching entity onto
        // its top collision face between controller ticks; that leaves the
        // worker exactly one block above the requested cell and restarts the
        // recorded route forever. Keep crouch held for both controlled ascent
        // and descent, then release it when the construction movement finishes.
        setShiftKeyDown(true);
        getNavigation().stop();
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /**
     * Descends through the scaffolding directly below the resident and exits onto
     * a real standing cell beside the bottom of the column. Unlike a solid
     * temporary pillar, scaffolding is intentionally traversable in both
     * directions, so a builder that has reached a roof is not trapped there.
     */
    public boolean beginExistingScaffoldingDescent(double speed) {
        BlockPos feet = BlockPos.containing(getX(), getY() + 0.05D, getZ());
        BlockPos scaffold = level().getBlockState(feet).is(Blocks.SCAFFOLDING)
                ? feet : feet.below();
        return beginScaffoldingDescentAt(scaffold, speed);
    }

    /** Enters a nearby owned scaffolding column from the side and descends it. */
    public boolean beginScaffoldingDescentAt(BlockPos scaffold, double speed) {
        normalizeEmbeddedConstructionStandingPosition();
        if (!level().getBlockState(scaffold).is(Blocks.SCAFFOLDING)
                || !canEnterScaffoldingDescentFrom(scaffold, blockPosition())) return false;
        int topY = scaffold.getY();
        while (topY < level().getMaxBuildHeight() - 1
                && level().getBlockState(new BlockPos(
                scaffold.getX(), topY + 1, scaffold.getZ())).is(Blocks.SCAFFOLDING)) {
            topY++;
        }
        int bottomY = scaffold.getY();
        while (bottomY > level().getMinBuildHeight()
                && level().getBlockState(new BlockPos(
                scaffold.getX(), bottomY - 1, scaffold.getZ())).is(Blocks.SCAFFOLDING)) {
            bottomY--;
        }
        BlockPos column = new BlockPos(scaffold.getX(), bottomY, scaffold.getZ());
        List<BlockPos> landings = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos landing = column.relative(direction);
            if (canStandAt(landing)) landings.add(landing.immutable());
        }
        BlockPos landing = landings.stream().min(Comparator
                .comparingDouble(candidate -> candidate.distSqr(blockPosition()))).orElse(null);
        return landing != null && beginScaffoldingDescentTo(scaffold, landing, speed);
    }

    /** Descends a scaffold column to a caller-validated, explicit landing. */
    public boolean beginScaffoldingDescentTo(
            BlockPos scaffold, BlockPos landing, double speed
    ) {
        normalizeEmbeddedConstructionStandingPosition();
        if (!level().getBlockState(scaffold).is(Blocks.SCAFFOLDING)
                || !canEnterScaffoldingDescentFrom(scaffold, blockPosition())) return false;
        int topY = scaffold.getY();
        while (topY < level().getMaxBuildHeight() - 1
                && level().getBlockState(new BlockPos(
                scaffold.getX(), topY + 1, scaffold.getZ())).is(Blocks.SCAFFOLDING)) {
            topY++;
        }
        int bottomY = scaffold.getY();
        while (bottomY > level().getMinBuildHeight()
                && level().getBlockState(new BlockPos(
                scaffold.getX(), bottomY - 1, scaffold.getZ())).is(Blocks.SCAFFOLDING)) {
            bottomY--;
        }
        BlockPos column = new BlockPos(scaffold.getX(), bottomY, scaffold.getZ());
        if (Math.abs(landing.getX() - column.getX())
                + Math.abs(landing.getZ() - column.getZ()) != 1
                || landing.getY() != column.getY()
                || !canStandAt(landing)) return false;
        if (landing.equals(blockPosition()) && canStandAt(landing)) {
            // Already standing on a real cell beside the column. Re-entering
            // the scaffold only to descend back to the same cell creates an
            // endless cleanup loop and can rebuild supports after they were
            // removed. Ordinary gravity is sufficient for sub-block settling.
            setNoGravity(false);
            setShiftKeyDown(false);
            return false;
        }
        constructionStepTarget = landing;
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        pendingLocalConstructionRoute.clear();
        constructionClimbColumn = column.immutable();
        constructionClimbTopY = topY;
        constructionClimbDescending = true;
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionCentering = false;
        constructionLeap = false;
        constructionMovementSpeed = speed;
        constructionStepLastDistanceSqr = distanceToConstructionStepSqr();
        constructionStepLastProgressGameTime = level().getGameTime();
        constructionStepStartedGameTime = constructionStepLastProgressGameTime;
        constructionMovementFailed = false;
        preparedPathTarget = null;
        preparedPath = null;
        commandedTarget = landing.immutable();
        yieldPosition = null;
        yieldUntil = 0L;
        setNoGravity(true);
        setShiftKeyDown(true);
        getNavigation().stop();
        resetProgress(level().getGameTime());
        driveConstructionStep(speed);
        return !constructionMovementFailed;
    }

    /**
     * Repairs the only illegal standing pose the bounded construction controller
     * can inherit after a scaffold cell is replaced by its final solid block.
     * The resident is moved vertically onto that exact block, never sideways or
     * through a ceiling, and only after the destination bounding box is proven
     * collision-free. This gives every following route a real player standing
     * cell instead of starting path search from inside the completed roof.
     */
    private BlockPos normalizeEmbeddedConstructionStandingPosition() {
        // Scaffold motion may finish a floating-point epsilon below an integer
        // floor. Use the same real feet cell as the route planner, without
        // moving the entity just to correct an integer cell classification.
        BlockPos current = navigationFeetPosition();
        if (isClimbableCell(current)
                || level().getBlockState(current).getCollisionShape(level(), current).isEmpty()) {
            return current;
        }
        BlockPos standing = current.above();
        if (!canStandAt(standing)) return current;
        var shape=level().getBlockState(current).getCollisionShape(level(),current);
        if(!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y)<1.0D
                && getY()>=current.getY()+shape.max(net.minecraft.core.Direction.Axis.Y)-.05D)return standing;
        double targetX = standing.getX() + 0.5D;
        double targetY = standing.getY();
        double targetZ = standing.getZ() + 0.5D;
        AABB lifted = getBoundingBox().move(
                targetX - getX(), targetY - getY(), targetZ - getZ());
        if (!level().noCollision(this, lifted)) return current;
        // Centre and lift as one collision-checked correction. Preserving a
        // fractional edge position can make the otherwise clear upward move
        // brush the neighbouring wall/eave, leaving the resident embedded in
        // the replaced support forever.
        setPos(targetX, targetY, targetZ);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
        hasImpulse = true;
        return standing;
    }

    /**
     * Revalidates the resident's live feet cell before the work scheduler chooses
     * another station. A temporary support can be replaced by its final structure
     * block between movement cycles, leaving the entity inside a solid cell even
     * though no special movement remains active.
     */
    public BlockPos normalizeConstructionStandingPosition() {
        return normalizeEmbeddedConstructionStandingPosition();
    }

    /**
     * A player can step from an eave into a scaffolding column whose top is a few
     * blocks below, provided the entire drop column is open. Keep the drop bounded
     * and require a horizontally adjacent real standing cell.
     */
    public boolean canEnterScaffoldingDescentFrom(BlockPos scaffold, BlockPos standing) {
        if (!level().getBlockState(scaffold).is(Blocks.SCAFFOLDING)
                || !canUseScaffoldingDescentLaunch(scaffold, standing)) return false;
        // Vanilla does not classify the air cell directly above scaffolding as
        // an ordinary sturdy standing position, and onGround can be false while
        // the resident is visibly settled on the scaffold top. The live current
        // cell is nevertheless a valid descent launch once the open column has
        // passed the geometry check above. Requiring canStandAt/onGround here
        // left builders permanently one block above their own completed access
        // column during cleanup.
        if (!standing.equals(blockPosition()) && !canStandAt(standing)) return false;
        return true;
    }

    /** Geometry-only half of a scaffold entry check, used before vanilla pathfinding. */
    public boolean canUseScaffoldingDescentLaunch(BlockPos scaffold, BlockPos standing) {
        if (!level().getBlockState(scaffold).is(Blocks.SCAFFOLDING)) return false;
        int horizontal = Math.abs(standing.getX() - scaffold.getX())
                + Math.abs(standing.getZ() - scaffold.getZ());
        int vertical = standing.getY() - scaffold.getY();
        if (horizontal > 1 || vertical < 0 || vertical > 8) return false;
        // When already vertically aligned above the column, the resident's live
        // bounding box proves that its current body/head space is occupiable.
        // Rechecking the whole current head cell rejects valid launches below a
        // sloped roof/eave. Only the gap down to the scaffold must be clear. A
        // side entry still validates body and head cells in the target column.
        int highestCheckedY = horizontal == 0 && standing.equals(blockPosition())
                ? standing.getY() - 1 : standing.getY() + 1;
        for (int y = scaffold.getY() + 1; y <= highestCheckedY; y++) {
            BlockPos columnCell = new BlockPos(scaffold.getX(), y, scaffold.getZ());
            BlockState state = level().getBlockState(columnCell);
            if (!state.is(Blocks.SCAFFOLDING)
                    && !state.getCollisionShape(level(), columnCell).isEmpty()) return false;
        }
        return true;
    }

    public boolean hasExistingClimbableRouteTo(BlockPos landingPosition) {
        return findExistingClimbRoute(landingPosition, true) != null;
    }

    @Nullable
    public BlockPos existingClimbableApproach(BlockPos landingPosition) {
        ExistingClimbRoute route = findExistingClimbRoute(landingPosition, true);
        return route == null ? null : route.entry();
    }

    public boolean isStandingAtConstructionPosition(BlockPos standingPosition) {
        boolean supportedByClimbable = isClimbableCell(standingPosition)
                || isClimbableCell(standingPosition.below());
        double centerX = standingPosition.getX() + 0.5D;
        double centerZ = standingPosition.getZ() + 0.5D;
        double centerDistance = (getX() - centerX) * (getX() - centerX)
                + (getZ() - centerZ) * (getZ() - centerZ);
        boolean controllerSettled = Math.abs(getY() - standingPosition.getY()) <= 0.05D
                && Math.abs(getDeltaMovement().y) <= 1.0E-4D
                && centerDistance <= 0.01D;
        boolean scaffoldTopSettled = isClimbableCell(standingPosition)
                && Math.abs(getY() - (standingPosition.getY() + 1.0D)) <= 0.05D
                && Math.abs(getDeltaMovement().y) <= 1.0E-4D
                && centerDistance <= 0.64D;
        boolean settledOnSupport = onGround()
                || Math.abs(getY() - standingPosition.getY()) <= 0.08D
                && getDeltaMovement().y <= 0.0D
                && (canStandAt(standingPosition) || supportedByClimbable)
                || controllerSettled;
        return (settledOnSupport && blockPosition().equals(standingPosition)
                && distanceToSqr(standingPosition.getX() + 0.5D,
                standingPosition.getY(), standingPosition.getZ() + 0.5D) <= 0.64D)
                || scaffoldTopSettled && blockPosition().equals(standingPosition.above());
    }

    public boolean hasActiveConstructionMovement() {
        return constructionStepTarget != null || readyVerticalPillarSupport != null;
    }

    public int constructionDriveTicks() {
        return constructionDriveTicks;
    }

    @Nullable
    public BlockPos activeConstructionMovementTarget() {
        return constructionStepTarget;
    }

    public String constructionMovementMode() {
        if (constructionCentering) return "CENTERING";
        if (constructionClimbColumn != null) {
            return constructionClimbDescending ? "SCAFFOLD_DESCENT" : "CLIMB";
        }
        if (verticalPillarSupport != null) return "VERTICAL_RISE";
        if (constructionLeap) return "LEAP";
        if (constructionStepTarget != null) return "LOCAL_STEP";
        if (readyVerticalPillarSupport != null) return "READY_TO_PLACE_FOOTING";
        return "NONE";
    }

    /** Compact failure evidence for the construction throughput regression. */
    public String constructionMovementDiagnostics() {
        if (constructionStepTarget == null) return "inactive,lastAbort=" + constructionLastAbort;
        double dx = constructionStepTarget.getX() + 0.5D - getX();
        double dz = constructionStepTarget.getZ() + 0.5D - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double assist = Math.min(0.14D, horizontal);
        double stepX = horizontal <= 1.0E-6D ? 0.0D : dx / horizontal * assist;
        double stepZ = horizontal <= 1.0E-6D ? 0.0D : dz / horizontal * assist;
        AABB shifted = getBoundingBox().move(stepX, 0.0D, stepZ);
        List<String> collisions = new ArrayList<>();
        int minX = (int) Math.floor(shifted.minX + 1.0E-7D);
        int minY = (int) Math.floor(shifted.minY + 1.0E-7D);
        int minZ = (int) Math.floor(shifted.minZ + 1.0E-7D);
        int maxX = (int) Math.floor(shifted.maxX - 1.0E-7D);
        int maxY = (int) Math.floor(shifted.maxY - 1.0E-7D);
        int maxZ = (int) Math.floor(shifted.maxZ - 1.0E-7D);
        for (BlockPos position : BlockPos.betweenClosed(
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ))) {
            BlockState state = level().getBlockState(position);
            for (AABB local : state.getCollisionShape(level(), position).toAabbs()) {
                if (local.move(position.getX(), position.getY(), position.getZ())
                        .intersects(shifted)) collisions.add(position + ":" + state);
            }
        }
        return "target=" + constructionStepTarget + ",driveTicks=" + constructionDriveTicks
                + ", xyz=" + getX() + "/" + getY() + "/" + getZ()
                + ", pendingLocal=" + pendingLocalConstructionRoute.size()
                + ", pendingRise=" + pendingCenteredRiseTarget
                + ", pendingDiagonal=" + pendingDiagonalRiseTarget
                + ", onGround=" + onGround()
                + ", targetStandable=" + canStandAt(constructionStepTarget)
                + ", near=" + nearConstructionStepTarget()
                + ", shiftedClear=" + level().noCollision(this, shifted)
                + ", collisions=" + collisions
                + ", lastDrive=" + constructionLastDriveTrace
                + ", driveEntry=" + constructionLastDriveEntryTrace
                + ", tick=" + constructionTickTrace;
    }

    public void traceConstructionDecision(String decision) {
        if (!decision.equals(constructionDecisionTrace)) {
            if (constructionDecisionHistory.size() == 20) constructionDecisionHistory.removeFirst();
            constructionDecisionHistory.addLast(level().getGameTime() + ":" + decision);
        }
        constructionDecisionTrace = decision;
    }

    public List<String> constructionDecisionHistory() {
        return List.copyOf(constructionDecisionHistory);
    }

    public String constructionDecisionTrace() {
        return constructionDecisionTrace;
    }

    public String settlementNavigationDiagnostics() {
        Path path = getNavigation().getPath();
        double currentDistance = commandedTarget == null ? -1.0D
                : Math.sqrt(distanceToSqr(commandedTarget.getX() + 0.5D,
                commandedTarget.getY(), commandedTarget.getZ() + 0.5D));
        return "command=" + commandedTarget + ",intent=" + physicalNavigationIntent
                + ",journey={"+navigationJourney.diagnostic(level().getGameTime())+"},searches="+navigationSearches+",navFailures="+navigationFailures+",waypoint="+longDistanceWaypoint+",segment="+segmentSearchCursor+"/"+segmentSearchCandidates.size()+",searchOrigin="+segmentSearchOrigin+",pending="+navigationSearchPending
                + ",bedRecovery=" + bedRecoveryWaypoint + ",bedAttempts=" + bedRecoveryAttempts
                + ",done=" + getNavigation().isDone()
                + ",path=" + (path == null ? "null"
                : path.getNextNodeIndex() + "/" + path.getNodeCount())
                + ",progressAge=" + (level().getGameTime() - lastProgressGameTime)
                + ",bestDistance=" + lastProgressTargetDistance
                + ",currentDistance=" + currentDistance
                + ",trafficWait=" + Math.max(0L, trafficWaitUntil - level().getGameTime());
    }

    public void recordConstructionAccessFailure(String failure) {
        if (failure != null && !failure.isBlank()) {
            constructionAccessFailures.merge(failure, 1, Integer::sum);
        }
    }

    public String constructionAccessFailureDiagnostics() {
        return constructionAccessFailures.toString();
    }

    public String constructionLastAbortReason() {
        return constructionLastAbort;
    }

    public boolean hasReadyVerticalPillarSupport() {
        return readyVerticalPillarSupport != null;
    }

    public boolean isConstructionAccessRetreating() {
        return constructionAccessRetreating;
    }

    public void beginConstructionAccessRetreat() {
        constructionAccessRetreating = true;
    }

    public void finishConstructionAccessRetreat() {
        constructionAccessRetreating = false;
    }

    @Nullable
    public BlockPos consumeReadyVerticalPillarSupport() {
        BlockPos ready = readyVerticalPillarSupport;
        readyVerticalPillarSupport = null;
        setNoGravity(false);
        return ready;
    }

    public void finishConstructionMovement() {
        clearSpecialConstructionMovement();
        constructionMovementSpeed = 0.0D;
        constructionMovementFailed = false;
        commandedTarget = null;
        preparedPathTarget = null;
        preparedPath = null;
        getNavigation().stop();
        resetProgress(level().getGameTime());
        retainScaffoldGrip();
    }

    private void retainScaffoldGrip() {
        BlockPos cell = BlockPos.containing(getX(), getY() + 0.05D, getZ());
        scaffoldRestCell = level().getBlockState(cell).is(Blocks.SCAFFOLDING)
                && constructionScaffoldingBoundsClear(getBoundingBox()) ? cell : null;
        if (scaffoldRestCell != null) {
            setNoGravity(true);
            setShiftKeyDown(true);
            setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Keeps a resident moving like a player jumping onto one adjacent full block.
     * GroundPathNavigation does not produce a reachable path to a newly placed
     * one-block construction step, so this tightly bounded movement is handled by
     * the normal move and jump controls instead of teleporting the entity.
     */
    public boolean continueConstructionStep(double speed) {
        if (constructionStepTarget == null) return false;
        constructionLastDriveEntryTrace = level().getGameTime() + ":entry=" + getX()
                + ":previousAfter=" + constructionLastDriveAfterX
                + ":drift=" + (Double.isNaN(constructionLastDriveAfterX)
                ? 0.0D : getX() - constructionLastDriveAfterX);
        // A scaffold work cell or descent landing can legitimately become its
        // final structure block after another cleanup/build transition. Do not
        // accept "arrived" while the resident is now embedded in that solid
        // cell; abort the stale route, stand on top of the finished block and
        // let the geometry planner choose a fresh access point.
        if (constructionClimbColumn != null
                && !canOccupyConstructionCell(constructionStepTarget)) {
            constructionLastAbort = "CLIMB_TARGET_REPLACED:" + constructionStepTarget;
            abortConstructionMovement(true);
            normalizeEmbeddedConstructionStandingPosition();
            return false;
        }
        if (!constructionCentering && verticalPillarSupport == null
                && !constructionLeap && constructionClimbColumn == null
                && navigationFeetPosition().equals(constructionStepTarget)
                && Math.abs(getY() - constructionStepTarget.getY()) <= 0.21D
                && canStandAt(constructionStepTarget)) {
            double targetX = constructionStepTarget.getX() + 0.5D;
            double targetY = constructionStepTarget.getY();
            double targetZ = constructionStepTarget.getZ() + 0.5D;
            AABB settledBounds = getBoundingBox().move(
                    targetX - getX(), targetY - getY(), targetZ - getZ());
            if (level().noCollision(this, settledBounds)) {
                // Crossing a block boundary can leave the entity visually
                // hovering inside the correct cell. Settle on the validated
                // cell centre before deciding whether the next rise is safe.
                setPos(targetX, targetY, targetZ);
                setDeltaMovement(Vec3.ZERO);
                fallDistance = 0.0F;
                setOnGround(true);
                hasImpulse = true;
            } else if (getY() > targetY + 0.05D && getY() < targetY + 1.0D) {
                // A controlled same-height/catwalk move can cross into the
                // destination cell while its feet are still a fraction above the
                // real supporting surface. Keeping no-gravity enabled here makes
                // horizontal movement a no-op (the target centre is already
                // reached), so cleanup waits forever and starts inventing a new
                // access route. Release only the vertical hold and let ordinary
                // collision settle the resident onto the already validated
                // support. Any following upward step still has to pass the exact
                // centering check in beginAdjacentConstructionMovement.
                setNoGravity(false);
                setDeltaMovement(0.0D, Math.min(0.0D, getDeltaMovement().y), 0.0D);
            }
        }
        if (nearConstructionStepTarget()) {
            settleCompletedConstructionStep();
            if (pendingCenteredRiseTarget != null) {
                return beginPendingCenteredRise(speed);
            }
            if (pendingDiagonalRiseTarget != null) {
                return beginPendingDiagonalRise(speed);
            }
            if (!pendingLocalConstructionRoute.isEmpty()) {
                return advancePendingLocalConstructionRoute(speed);
            }
            if (verticalPillarSupport != null) {
                readyVerticalPillarSupport = verticalPillarSupport;
                verticalPillarSupport = null;
            } else {
                setNoGravity(false);
            }
            constructionClimbColumn = null;
            constructionClimbTopY = 0;
            constructionClimbDescending = false;
            setShiftKeyDown(false);
            constructionStepTarget = null;
            constructionCentering = false;
            constructionLeap = false;
            constructionMovementSpeed = 0.0D;
            commandedTarget = null;
            getNavigation().stop();
            resetProgress(level().getGameTime());
            retainScaffoldGrip();
            return false;
        }
        long gameTime = level().getGameTime();
        double distance = distanceToConstructionStepSqr();
        if (gameTime - constructionStepStartedGameTime >= CONSTRUCTION_STEP_MAX_DURATION_TICKS) {
            // Tiny vertical oscillations inside a scaffold can look like fresh
            // distance progress forever. A one-cell player-like movement must
            // either finish within this hard window or retire the physical
            // route; the blueprint-wide three-failure rule then selects a
            // genuinely different route instead of letting one leg consume an
            // entire game day.
            constructionLastAbort = "LOCAL_STEP_DURATION_EXHAUSTED:target="
                    + constructionStepTarget + ",xyz=" + getX() + "/" + getY() + "/" + getZ()
                    + ",distance=" + distance + ",best=" + constructionStepLastDistanceSqr
                    + ",tick=" + constructionTickTrace + ",drive=" + constructionLastDriveTrace;
            abortConstructionMovement(true);
            return false;
        } else if (distance + 0.04D < constructionStepLastDistanceSqr) {
            constructionStepLastDistanceSqr = distance;
            constructionStepLastProgressGameTime = gameTime;
        } else if (gameTime - constructionStepLastProgressGameTime >= CONSTRUCTION_STEP_STUCK_AFTER_TICKS) {
            constructionLastAbort = "LOCAL_STEP_STUCK:target=" + constructionStepTarget
                    + ",xyz=" + getX() + "/" + getY() + "/" + getZ()
                    + ",distance=" + distance + ",best=" + constructionStepLastDistanceSqr;
            abortConstructionMovement(true);
            return false;
        }
        boolean verticalRise = verticalPillarSupport != null;
        boolean existingClimb = constructionClimbColumn != null;
        boolean validClimbTarget = existingClimb
                && canOccupyConstructionCell(constructionStepTarget);
        if (!verticalRise && (!existingClimb && !canOccupyConstructionCell(constructionStepTarget)
                || existingClimb && !validClimbTarget)
                || verticalRise && (!level().getBlockState(verticalPillarSupport).isAir()
                || !level().getFluidState(verticalPillarSupport).isEmpty())
                || existingClimb && !climbColumnStillUsable()
                || !existingClimb && !constructionLeap
                && distanceToSqr(constructionStepTarget.getX() + 0.5D,
                constructionStepTarget.getY(), constructionStepTarget.getZ() + 0.5D) > 6.25D) {
            constructionLastAbort = "LOCAL_STEP_INVALID:target=" + constructionStepTarget
                    + ",occupiable=" + canOccupyConstructionCell(constructionStepTarget)
                    + ",routeClear=" + constructionStepRouteClear(constructionStepTarget)
                    + ",vertical=" + verticalRise + ",climb=" + existingClimb;
            abortConstructionMovement(true);
            return false;
        }
        constructionMovementSpeed = speed;
        driveConstructionStep(speed);
        return true;
    }

    /**
     * A bounded step moves with setPos after proving every collision slice. The
     * vanilla ground flag is therefore not always refreshed before the worker
     * scheduler starts the next leg. Explicitly settle an ordinary supported
     * destination so the following entity tick cannot pull the resident back
     * into the edge it has just crossed. Scaffolding cells remain governed by
     * their climb controller and are intentionally not marked as solid ground.
     */
    private void settleCompletedConstructionStep() {
        if (constructionStepTarget != null && constructionClimbColumn != null
                && isClimbableCell(constructionStepTarget) && nearConstructionStepTarget()) {
            // A tolerated -0.04 Y remainder still floors to the previous node.
            // Settle the validated final slice before consuming the next leg;
            // otherwise a legal one-cell ascent becomes an apparent two-cell jump.
            AABB settled = getBoundingBox().move(
                    constructionStepTarget.getX() + 0.5D - getX(),
                    constructionStepTarget.getY() - getY(),
                    constructionStepTarget.getZ() + 0.5D - getZ());
            if (constructionScaffoldingBoundsClear(settled)) {
                setPos(constructionStepTarget.getX() + 0.5D,
                        constructionStepTarget.getY(), constructionStepTarget.getZ() + 0.5D);
                setDeltaMovement(Vec3.ZERO);
                fallDistance = 0.0F;
                hasImpulse = true;
            }
            return;
        }
        if (constructionStepTarget == null || !canStandAt(constructionStepTarget)) return;
        double targetX = constructionStepTarget.getX() + 0.5D;
        double targetY = constructionStepTarget.getY();
        double targetZ = constructionStepTarget.getZ() + 0.5D;
        AABB settledBounds = getBoundingBox().move(
                targetX - getX(), targetY - getY(), targetZ - getZ());
        if (!level().noCollision(this, settledBounds)) return;
        setPos(targetX, targetY, targetZ);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
        setOnGround(true);
        hasImpulse = true;
    }

    private boolean beginPendingCenteredRise(double speed) {
        BlockPos target = pendingCenteredRiseTarget;
        pendingCenteredRiseTarget = null;
        constructionStepTarget = null;
        constructionCentering = false;
        setNoGravity(false);
        if (target != null && beginAdjacentConstructionMovement(target, speed)) return true;
        abortConstructionMovement(true);
        return false;
    }

    private boolean advancePendingLocalConstructionRoute(double speed) {
        BlockPos current = normalizeEmbeddedConstructionStandingPosition();
        BlockPos target = pendingLocalConstructionRoute.pollFirst();
        // Scaffold collision and the standing-position normalizer can settle the
        // resident into the next recorded cell during the previous leg. Treat
        // every such node as completed instead of submitting a zero-length
        // adjacent move, which would reject the route and make cleanup replay
        // the same two cells forever.
        while (target != null && target.equals(current)) {
            target = pendingLocalConstructionRoute.pollFirst();
        }
        constructionStepTarget = null;
        constructionCentering = false;
        setNoGravity(false);
        if (target != null && beginRecordedConstructionRouteLeg(target, speed)) return true;
        if (target == null) {
            clearSpecialConstructionMovement();
            constructionMovementSpeed = 0.0D;
            commandedTarget = null;
            constructionMovementFailed = false;
            getNavigation().stop();
            resetProgress(level().getGameTime());
            return false;
        }
        abortConstructionMovement(true);
        return false;
    }

    private boolean beginRecordedConstructionRouteLeg(BlockPos target, double speed) {
        BlockPos current = normalizeEmbeddedConstructionStandingPosition();
        boolean scaffoldColumnLeg = isRecordedScaffoldingColumnLeg(current, target);
        return scaffoldColumnLeg
                ? beginScaffoldingWorkHeight(target, speed, false)
                : beginAdjacentConstructionMovement(target, speed);
    }

    private boolean isRecordedScaffoldingColumnLeg(BlockPos from, BlockPos to) {
        if (from.getX() != to.getX() || from.getZ() != to.getZ()
                || Math.abs(from.getY() - to.getY()) != 1) return false;
        // A completed upward scaffold leg may leave the resident in the air
        // cell immediately above the column. Reversing a recorded route must be
        // able to crouch back into the top scaffold from that legal pose; asking
        // the adjacent-step controller to handle a zero-horizontal move rejects
        // it forever and restarts the whole route from the root.
        return isClimbableCell(to) && (isClimbableCell(from) || from.equals(to.above()))
                || isClimbableCell(from) && to.equals(from.above()) && canOccupyConstructionCell(to);
    }

    private boolean beginPendingDiagonalRise(double speed) {
        BlockPos target = pendingDiagonalRiseTarget;
        pendingDiagonalRiseTarget = null;
        constructionStepTarget = null;
        constructionCentering = false;
        setNoGravity(false);
        if (target != null && beginAdjacentConstructionMovement(target, speed)) return true;
        abortConstructionMovement(true);
        return false;
    }

    public boolean consumeConstructionMovementFailure() {
        boolean failed = constructionMovementFailed;
        constructionMovementFailed = false;
        return failed;
    }

    private double distanceToConstructionStepSqr() {
        if (constructionStepTarget == null) return 0.0D;
        return distanceToSqr(constructionStepTarget.getX() + 0.5D,
                constructionStepTarget.getY(), constructionStepTarget.getZ() + 0.5D);
    }

    private void abortConstructionMovement(boolean failed) {
        if (failed) recordTrackedConstructionRouteFailure();
        clearSpecialConstructionMovement();
        constructionMovementSpeed = 0.0D;
        commandedTarget = null;
        constructionMovementFailed = failed;
        getNavigation().stop();
        resetProgress(level().getGameTime());
    }

    /** Validates the swept headroom used by the special one-block player-like climb. */
    private boolean constructionStepRouteClear(BlockPos standingPosition) {
        BlockPos current = navigationFeetPosition();
        if (standingPosition.getY() <= current.getY()) {
            // A same-height or descending destination can have valid feet/head
            // cells and still be impossible to enter because an eave or stair
            // intersects the resident before it reaches the edge and drops.
            // Validate the actual horizontal sweep used by driveConstructionStep
            // so that the planner rejects this station and tries another branch
            // instead of replaying the same collision forever.
            AABB sweptEnd = getBoundingBox().move(
                    standingPosition.getX() + 0.5D - getX(), 0.0D,
                    standingPosition.getZ() + 0.5D - getZ());
            boolean scaffoldTraversal = isClimbableCell(current)
                    || isClimbableCell(current.below())
                    || isClimbableCell(standingPosition)
                    || isClimbableCell(standingPosition.below());
            return scaffoldTraversal
                    ? constructionScaffoldingBoundsClear(sweptEnd)
                    : level().noCollision(this, sweptEnd);
        }
        return constructionStepRouteClearFrom(current)
                && level().noCollision(this, getBoundingBox().move(0.0D, 1.0D, 0.0D));
    }

    private boolean constructionStepRouteClearFrom(BlockPos current) {
        for (BlockPos position : List.of(current.above(), current.above(2))) {
            BlockState state = level().getBlockState(position);
            if ((!isClimbableCell(position)
                    && !state.getCollisionShape(level(), position).isEmpty())
                    || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private void driveConstructionStep(double speed) {
        if (constructionStepTarget == null) return;
        getNavigation().stop();
        double targetX = constructionStepTarget.getX() + 0.5D;
        double targetZ = constructionStepTarget.getZ() + 0.5D;
        double targetY = constructionStepTarget.getY();
        if (constructionLeap) {
            driveConstructionLeap(targetX, targetY, targetZ);
            return;
        }
        if (constructionClimbColumn != null) {
            driveExistingClimb(targetX, targetY, targetZ);
            return;
        }
        double verticalGap = targetY - getY();
        boolean climbing = verticalGap > 0.01D;

        if (constructionCentering) {
            double centerDx = targetX - getX();
            double centerDz = targetZ - getZ();
            AABB centeredBounds = getBoundingBox().move(centerDx, 0.0D, centerDz);
            if (!level().noCollision(this, centeredBounds)) {
                constructionLastAbort = "LOCAL_CENTER_COLLISION:target=" + constructionStepTarget;
                abortConstructionMovement(true);
                return;
            }
            // Centering never changes blocks or height. Resolve it in one
            // collision-checked alignment so an entity already brushing the
            // neighboring wall can move back into the safe middle of its cell.
            setPos(targetX, getY(), targetZ);
            setDeltaMovement(Vec3.ZERO);
            hasImpulse = true;
            return;
        }

        // Use bounded, collision-resolving movement for the one-block construction
        // climb. Normal path and jump controls can fight each other when a new
        // support block was placed during the same work cycle, leaving the resident
        // repeatedly jumping into the face. Raise through the already validated
        // current standing column, then cross the edge after the feet clear it.
        if (climbing) {
            getMoveControl().setWantedPosition(getX(), targetY, getZ(), 0.0D);
            // Repeated 0.20 additions can finish a few ulps below the bank.
            // The horizontal body then clips its top face forever. Resolve
            // only this final bounded lift to the validated exact target Y.
            double nextY = verticalGap <= 0.20D + 1.0E-7D ? targetY : getY() + 0.20D;
            double lift = nextY - getY();
            AABB liftedBounds = getBoundingBox().move(0.0D, lift, 0.0D);
            if (!level().noCollision(this, liftedBounds)) {
                constructionLastAbort = "LOCAL_LIFT_COLLISION:target=" + constructionStepTarget;
                abortConstructionMovement(true);
                return;
            }
            // Entity.move may cancel every lift after the first 0.20 block while
            // the mob is brushing the face of a freshly placed adjacent support.
            // The complete column and this exact next bounding box are already
            // collision-checked, so advance by one visible sub-block step without
            // bypassing any occupied headroom.
            setPos(getX(), nextY, getZ());
            setDeltaMovement(Vec3.ZERO);
            fallDistance = 0.0F;
            hasImpulse = true;
            if (targetY - getY() > 0.01D) return;
        }
        double dx = targetX - getX();
        double dz = targetZ - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.05D) {
            double beforeX = getX();
            double beforeY = getY();
            double beforeZ = getZ();
            double assist = Math.min(0.14D, horizontal);
            double stepX = dx / horizontal * assist;
            double stepZ = dz / horizontal * assist;
            // This controller is only entered for a previously validated,
            // adjacent construction step. Minecraft's ordinary step resolver
            // can pin a mob on the lip even after a one-block descent has
            // reached the correct Y. Move in visible, collision-checked slices
            // for both the crossing and the last same-height centering segment.
            AABB shiftedBounds = getBoundingBox().move(stepX, 0.0D, stepZ);
            boolean scaffoldTraversal = isClimbableCell(blockPosition())
                    || isClimbableCell(blockPosition().below())
                    || isClimbableCell(constructionStepTarget)
                    || isClimbableCell(constructionStepTarget.below());
            boolean shiftedClear = scaffoldTraversal
                    ? constructionScaffoldingBoundsClear(shiftedBounds)
                    : level().noCollision(this, shiftedBounds);
            if (!shiftedClear) {
                constructionLastAbort = "LOCAL_HORIZONTAL_COLLISION:target="
                        + constructionStepTarget + ",scaffold=" + scaffoldTraversal
                        + ",blockers=" + (scaffoldTraversal
                        ? constructionScaffoldingBlockers(shiftedBounds) : "solid");
                abortConstructionMovement(true);
                return;
            }
            // One collision-checked slice per drive; never snap across the cell.
            setPos(getX() + stepX, getY(), getZ() + stepZ);
            constructionLastDriveAfterX = getX();
            constructionLastDriveTrace = level().getGameTime() + ":"
                    + beforeX + "/" + beforeY + "/" + beforeZ + "->"
                    + getX() + "/" + getY() + "/" + getZ()
                    + ":target=" + constructionStepTarget;
            getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
            setDeltaMovement(Vec3.ZERO);
            hasImpulse = true;
            dx = targetX - getX();
            dz = targetZ - getZ();
            horizontal = Math.sqrt(dx * dx + dz * dz);
        }
        if (horizontal <= 0.05D && getY() > targetY + 0.01D) {
            AABB landingBounds = getBoundingBox().move(
                    targetX - getX(), targetY - getY(), targetZ - getZ());
            // Scaffolding deliberately lets a player descend through the upper
            // cell and settle on the next scaffold top while crouching. The
            // generic entity collision query still exposes the thin scaffold
            // rim as a blocker for this exact controlled one-cell descent,
            // which left builders hovering one block above a valid landing and
            // endlessly resubmitting the same local step. Only relax collision
            // against actual scaffolding below an already validated construction
            // landing; all other local steps retain vanilla collision handling.
            boolean scaffoldLanding = isClimbableCell(constructionStepTarget)
                    || isClimbableCell(constructionStepTarget.below());
            boolean landingClear = scaffoldLanding
                    ? constructionScaffoldingBoundsClear(landingBounds)
                    : level().noCollision(this, landingBounds);
            if (!landingClear) {
                constructionLastAbort = "LOCAL_DESCENT_COLLISION:target="
                        + constructionStepTarget + ",scaffold=" + scaffoldLanding;
                abortConstructionMovement(true);
                return;
            }
            // Descend continuously; a clear endpoint is not permission to snap
            // a whole block down in one entity tick.
            double nextY = Math.max(targetY, getY() - 0.20D);
            AABB descentSweep = getBoundingBox().expandTowards(0.0D, nextY - getY(), 0.0D);
            if (scaffoldLanding ? !constructionScaffoldingBoundsClear(descentSweep)
                    : !level().noCollision(this, descentSweep)) {
                abortConstructionMovement(true);
                return;
            }
            setPos(targetX, nextY, targetZ);
            setDeltaMovement(Vec3.ZERO);
            fallDistance = 0.0F;
            hasImpulse = true;
        }
    }

    private boolean nearConstructionStepTarget() {
        if (constructionStepTarget == null) return false;
        if (constructionCentering) {
            return isCenteredOnConstructionBlock(constructionStepTarget);
        }
        // Every bounded construction step must finish at the centre of its
        // standing cell. The ordinary "standing near" predicate intentionally
        // accepts most of a block for pathfinding, but using that loose 0.8-block
        // radius here ended a scaffold transfer after crossing only the cell
        // boundary. The next rise then started off-centre and could meet the
        // ceiling/eave before the resident had room to climb. Sub-block checks
        // also avoid negative-coordinate floor jitter at an exact landing Y.
        double dx = getX() - (constructionStepTarget.getX() + 0.5D);
        double dz = getZ() - (constructionStepTarget.getZ() + 0.5D);
        return dx * dx + dz * dz <= 0.01D
                && Math.abs(getY() - constructionStepTarget.getY()) <= 0.05D;
    }

    private void driveConstructionLeap(double targetX, double targetY, double targetZ) {
        double dx = targetX - getX();
        double dz = targetZ - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.18D) {
            if (getY() < constructionLeapApexY - 0.03D) {
                move(MoverType.SELF, new Vec3(0.0D,
                        Math.min(0.16D, constructionLeapApexY - getY()), 0.0D));
            }
            double assist = Math.min(0.18D, horizontal);
            move(MoverType.SELF, new Vec3(dx / horizontal * assist, 0.0D, dz / horizontal * assist));
        } else if (getY() > targetY + 0.03D) {
            move(MoverType.SELF, new Vec3(0.0D, -Math.min(0.18D, getY() - targetY), 0.0D));
        }
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
        hasImpulse = true;
    }

    private void driveExistingClimb(double targetX, double targetY, double targetZ) {
        if (constructionClimbColumn == null) return;
        if (!constructionClimbDescending && constructionStepTarget != null
                && !isClimbableCell(constructionStepTarget)
                && getY() > targetY + 0.05D
                && getY() < targetY + 1.0D) {
            // Leaving the bottom of a scaffold can put the resident in the
            // destination cell while its feet are still a fraction above the
            // real floor. Continuing to drive the old column then fights
            // gravity and produces an endless rise/drop oscillation. Accept the
            // exact outside landing only after its final bounding box is clear.
            AABB landingBounds = getBoundingBox().move(
                    targetX - getX(), targetY - getY(), targetZ - getZ());
            if (level().noCollision(this, landingBounds)) {
                setPos(targetX, targetY, targetZ);
                setDeltaMovement(Vec3.ZERO);
                fallDistance = 0.0F;
                hasImpulse = true;
                return;
            }
        }
        if (!constructionClimbDescending && constructionStepTarget != null
                && isClimbableCell(constructionStepTarget)
                && getY() > targetY + 0.05D) {
            // Scaffolding may resolve the resident onto the upper collision face
            // after an ascent even while the requested route node is inside the
            // column. Treat that one-block overshoot as a controlled crouching
            // descent instead of waiting forever for an upward controller to
            // approach a target now below its feet.
            constructionClimbDescending = true;
            setShiftKeyDown(true);
        }
        if (constructionClimbDescending) {
            driveExistingScaffoldingDescent(targetX, targetY, targetZ);
            return;
        }
        double columnX = constructionClimbColumn.getX() + 0.5D;
        double columnZ = constructionClimbColumn.getZ() + 0.5D;
        double climbTargetY = Math.max(targetY, constructionClimbTopY);
        double dx = columnX - getX();
        double dz = columnZ - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.01D && getY() < climbTargetY - 0.03D) {
            double assist = Math.min(0.16D, horizontal);
            double moveX = dx / horizontal * assist;
            double moveZ = dz / horizontal * assist;
            AABB shifted = getBoundingBox().move(moveX, 0.0D, moveZ);
            if (!constructionScaffoldingBoundsClear(shifted)) {
                constructionLastAbort = "CLIMB_ALIGN_COLLISION:" + constructionScaffoldingBlockers(shifted);
                abortConstructionMovement(true);
                return;
            }
            if (horizontal <= 0.16D) {
                // Finish horizontal alignment exactly before any upward slice.
                // A 0.12-block tolerance was enough for the resident's shoulder
                // or head to brush an eave, even though the scaffold column
                // itself was clear. Player-like vertical access starts from the
                // middle of the cell and therefore uses the same exact centre.
                setPos(columnX, getY(), columnZ);
            } else {
                setPos(getX() + moveX, getY(), getZ() + moveZ);
            }
        } else if (getY() < climbTargetY - 0.03D) {
            double lift = Math.min(0.16D, climbTargetY - getY());
            AABB lifted = getBoundingBox().move(0.0D, lift, 0.0D);
            if (!constructionScaffoldingBoundsClear(lifted)) {
                constructionLastAbort = "CLIMB_LIFT_COLLISION";
                abortConstructionMovement(true);
                return;
            }
            setPos(getX(), getY() + lift, getZ());
        } else {
            double exitX = targetX - getX();
            double exitZ = targetZ - getZ();
            double exitDistance = Math.sqrt(exitX * exitX + exitZ * exitZ);
            if (exitDistance > 0.05D) {
                AABB centeredBounds = getBoundingBox().move(
                        targetX - getX(), targetY - getY(), targetZ - getZ());
                BlockPos currentCell = BlockPos.containing(getX(), getY() + 0.05D, getZ());
                if (currentCell.equals(constructionStepTarget)
                        && constructionScaffoldingBoundsClear(centeredBounds)) {
                    // A completed wall can leave the resident brushing the edge
                    // of the scaffold cell. Every short intermediate slice still
                    // intersects that wall even though the cell centre is clear.
                    // Resolve only this same-cell, collision-checked alignment in
                    // one move so climbing and cleanup start from the same stable
                    // centre a player would use.
                    setPos(targetX, targetY, targetZ);
                    setDeltaMovement(Vec3.ZERO);
                    fallDistance = 0.0F;
                    hasImpulse = true;
                    return;
                }
                double assist = Math.min(0.14D, exitDistance);
                double moveX = exitX / exitDistance * assist;
                double moveZ = exitZ / exitDistance * assist;
                AABB shifted = getBoundingBox().move(moveX, 0.0D, moveZ);
                if (!constructionScaffoldingBoundsClear(shifted)) {
                    constructionLastAbort = "CLIMB_EXIT_COLLISION";
                    abortConstructionMovement(true);
                    return;
                }
                setPos(getX() + moveX, getY(), getZ() + moveZ);
            }
        }
        getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
        hasImpulse = true;
    }

    private void driveExistingScaffoldingDescent(double targetX, double targetY, double targetZ) {
        if (constructionClimbColumn == null) return;
        double columnX = constructionClimbColumn.getX() + 0.5D;
        double columnZ = constructionClimbColumn.getZ() + 0.5D;
        double dx = columnX - getX();
        double dz = columnZ - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        setShiftKeyDown(true);
        boolean stopsInsideColumn = constructionStepTarget != null
                && constructionStepTarget.getX() == constructionClimbColumn.getX()
                && constructionStepTarget.getZ() == constructionClimbColumn.getZ();
        if (stopsInsideColumn) {
            if (horizontal > 0.06D) {
                double assist = Math.min(0.12D, horizontal);
                double moveX = dx / horizontal * assist;
                double moveZ = dz / horizontal * assist;
                AABB shifted = getBoundingBox().move(moveX, 0.0D, moveZ);
                if (!constructionScaffoldingBoundsClear(shifted)) {
                    abortConstructionMovement(true);
                    return;
                }
                setPos(getX() + moveX, getY(), getZ() + moveZ);
            } else if (getY() > targetY + 0.03D) {
                double drop = Math.min(0.16D, getY() - targetY);
                AABB lowered = getBoundingBox().move(0.0D, -drop, 0.0D);
                if (!constructionScaffoldingBoundsClear(lowered)) {
                    abortConstructionMovement(true);
                    return;
                }
                setPos(getX(), getY() - drop, getZ());
            } else {
                AABB settled = getBoundingBox().move(
                        targetX - getX(), targetY - getY(), targetZ - getZ());
                if (!constructionScaffoldingBoundsClear(settled)) {
                    abortConstructionMovement(true);
                    return;
                }
                setPos(targetX, targetY, targetZ);
                setShiftKeyDown(false);
            }
            getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
            setDeltaMovement(Vec3.ZERO);
            fallDistance = 0.0F;
            hasImpulse = true;
            return;
        }
        if (horizontal > 0.06D && getY() > constructionClimbColumn.getY() + 0.05D) {
            double assist = Math.min(0.12D, horizontal);
            double moveX = dx / horizontal * assist;
            double moveZ = dz / horizontal * assist;
            AABB shifted = getBoundingBox().move(moveX, 0.0D, moveZ);
            if (!constructionScaffoldingBoundsClear(shifted)) {
                abortConstructionMovement(true);
                return;
            }
            setPos(getX() + moveX, getY(), getZ() + moveZ);
        } else if (getY() > constructionClimbColumn.getY() + 0.15D) {
            double drop = Math.min(0.16D, getY() - constructionClimbColumn.getY());
            AABB lowered = getBoundingBox().move(0.0D, -drop, 0.0D);
            if (!constructionScaffoldingBoundsClear(lowered)) {
                abortConstructionMovement(true);
                return;
            }
            setPos(getX(), getY() - drop, getZ());
        } else {
            setShiftKeyDown(false);
            double exitX = targetX - getX();
            double exitZ = targetZ - getZ();
            double exitDistance = Math.sqrt(exitX * exitX + exitZ * exitZ);
            if (exitDistance > 0.05D) {
                AABB landingBounds = getBoundingBox().move(
                        exitX, targetY - getY(), exitZ);
                if (level().noCollision(this, landingBounds)) {
                    // The landing was validated when descent began. Resolve the
                    // final one-cell exit exactly; repeated ordinary movement can
                    // brush the scaffold rim by tiny amounts and keep resetting
                    // stuck detection without ever leaving the column.
                    setPos(targetX, targetY, targetZ);
                } else {
                    double assist = Math.min(0.14D, exitDistance);
                    move(MoverType.SELF, new Vec3(
                            exitX / exitDistance * assist, 0.0D,
                            exitZ / exitDistance * assist));
                }
            }
        }
        getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
        hasImpulse = true;
    }

    /** Collision check for controlled movement inside owned vanilla scaffolding. */
    private boolean constructionScaffoldingBoundsClear(AABB bounds) {
        return constructionScaffoldingBlockers(bounds).isEmpty();
    }

    private List<String> constructionScaffoldingBlockers(AABB bounds) {
        List<String> blockers = new ArrayList<>();
        int minX = (int) Math.floor(bounds.minX + 1.0E-7D);
        int minY = (int) Math.floor(bounds.minY + 1.0E-7D);
        int minZ = (int) Math.floor(bounds.minZ + 1.0E-7D);
        int maxX = (int) Math.floor(bounds.maxX - 1.0E-7D);
        int maxY = (int) Math.floor(bounds.maxY - 1.0E-7D);
        int maxZ = (int) Math.floor(bounds.maxZ - 1.0E-7D);
        for (BlockPos position : BlockPos.betweenClosed(
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ))) {
            BlockState state = level().getBlockState(position);
            if (isClimbableCell(position)) continue;
            for (AABB local : state.getCollisionShape(level(), position).toAabbs()) {
                if (local.move(position.getX(), position.getY(), position.getZ())
                        .intersects(bounds)) blockers.add(position + ":" + state);
            }
        }
        return blockers;
    }

    private void clearSpecialConstructionMovement() {
        constructionStepTarget = null;
        pendingCenteredRiseTarget = null;
        pendingDiagonalRiseTarget = null;
        pendingLocalConstructionRoute.clear();
        verticalPillarSupport = null;
        readyVerticalPillarSupport = null;
        constructionClimbColumn = null;
        constructionClimbTopY = 0;
        constructionClimbDescending = false;
        constructionCentering = false;
        constructionLeap = false;
        constructionLeapApexY = 0.0D;
        constructionStepStartedGameTime = 0L;
        setNoGravity(false);
        setShiftKeyDown(false);
    }

    @Nullable
    private ExistingClimbRoute findExistingClimbRoute(
            BlockPos landingPosition, boolean requireReachableEntry
    ) {
        // A valid column beside the requested station is not enough: the station
        // itself must still be an occupiable player-like construction cell. In
        // particular, the air cell on top of scaffolding is legal even though it
        // is not a normal full-block standing surface. Using canStandAt() here
        // would reject that legitimate destination, while omitting the check let
        // a stale/roof-blocked station start and fail again every scheduler pass.
        if (!canOccupyConstructionCell(landingPosition)) return null;
        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>();
        columns.add(new BlockPos(landingPosition.getX(), landingPosition.getY(), landingPosition.getZ()));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            columns.add(landingPosition.relative(direction));
        }
        List<ExistingClimbRoute> routes = new ArrayList<>();
        for (BlockPos columnHint : columns) {
            int topY = Integer.MIN_VALUE;
            for (int y = landingPosition.getY(); y >= landingPosition.getY() - 1; y--) {
                BlockPos candidate = new BlockPos(columnHint.getX(), y, columnHint.getZ());
                if (isClimbableCell(candidate)) {
                    topY = y;
                    break;
                }
            }
            if (topY == Integer.MIN_VALUE) continue;
            int bottomY = topY;
            while (bottomY > level().getMinBuildHeight()
                    && isClimbableCell(new BlockPos(columnHint.getX(), bottomY - 1, columnHint.getZ()))) {
                bottomY--;
            }
            if (topY - bottomY > 16) continue;
            BlockPos column = new BlockPos(columnHint.getX(), bottomY, columnHint.getZ());
            List<BlockPos> entries = new ArrayList<>();
            BlockPos current = blockPosition();
            int currentHorizontal = Math.abs(current.getX() - column.getX())
                    + Math.abs(current.getZ() - column.getZ());
            if (currentHorizontal <= 1 && Math.abs(current.getY() - bottomY) <= 1) {
                entries.add(current.immutable());
            }
            if (canStandAt(column)) entries.add(column.immutable());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos entry = column.relative(direction);
                if (canStandAt(entry)) entries.add(entry.immutable());
            }
            entries.sort(Comparator.comparingDouble(entry -> entry.distSqr(blockPosition())));
            for (BlockPos entry : entries) {
                if (requireReachableEntry && entry.distManhattan(blockPosition()) > 2) {
                    Path entryPath = probePathTo(entry);
                    if (entryPath == null || !entryPath.canReach()) continue;
                }
                routes.add(new ExistingClimbRoute(entry,
                        new BlockPos(column.getX(), bottomY, column.getZ()), topY));
            }
        }
        return routes.stream().min(Comparator
                .comparingDouble((ExistingClimbRoute route) -> route.entry().distSqr(blockPosition()))
                .thenComparingInt(route -> -route.topY())).orElse(null);
    }

    private boolean climbColumnStillUsable() {
        if (constructionClimbColumn == null) return false;
        for (int y = constructionClimbColumn.getY(); y <= constructionClimbTopY; y++) {
            if (!isClimbableCell(new BlockPos(
                    constructionClimbColumn.getX(), y, constructionClimbColumn.getZ()))) return false;
        }
        return true;
    }

    private boolean isClimbableCell(BlockPos position) {
        BlockState state = level().getBlockState(position);
        return state.is(Blocks.LADDER) || state.is(Blocks.SCAFFOLDING)
                || state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof ScaffoldingBlock;
    }

    private boolean tryYieldForOncomingCitizen(BlockPos target, long gameTime, double speed) {
        if (commandedTarget == null) return false;
        SettlementCitizenEntity other = level().getEntitiesOfClass(
                        SettlementCitizenEntity.class,
                        getBoundingBox().inflate(1.15D, 0.5D, 1.15D),
                        candidate -> candidate != this && candidate.isAlive() && candidate.commandedTarget != null)
                .stream()
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
        if (other == null || !isHeadOn(target, other)) return false;

        // Stable UUID ordering keeps both citizens from yielding at once.
        if (getUUID().compareTo(other.getUUID()) < 0) return false;
        YieldRoute side = findYieldPosition(target);
        if (side == null) return false;
        yieldPosition = side.position();
        yieldUntil = gameTime + TRAFFIC_YIELD_TICKS;
        resetProgress(gameTime);
        if (side.path() != null) {
            commitProbedDestination(side.position(), speed * 0.85D);
        } else {
            getNavigation().moveTo(
                    side.position().getX() + 0.5D,
                    side.position().getY(),
                    side.position().getZ() + 0.5D,
                    speed * 0.85D
            );
        }
        return true;
    }

    private boolean isHeadOn(BlockPos target, SettlementCitizenEntity other) {
        double mineX = target.getX() + 0.5D - getX();
        double mineZ = target.getZ() + 0.5D - getZ();
        double otherX = other.commandedTarget.getX() + 0.5D - other.getX();
        double otherZ = other.commandedTarget.getZ() + 0.5D - other.getZ();
        double mineLength = Math.sqrt(mineX * mineX + mineZ * mineZ);
        double otherLength = Math.sqrt(otherX * otherX + otherZ * otherZ);
        if (mineLength < 0.01D || otherLength < 0.01D) return false;
        return (mineX * otherX + mineZ * otherZ) / (mineLength * otherLength) < -0.45D;
    }

    @Nullable
    private YieldRoute findYieldPosition(BlockPos target) {
        int dx = Integer.compare(target.getX(), getBlockX());
        int dz = Integer.compare(target.getZ(), getBlockZ());
        if (dx == 0 && dz == 0) return null;
        BlockPos base = blockPosition();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(base);
        visited.add(base);
        while (!queue.isEmpty()) {
            BlockPos candidate = queue.removeFirst();
            if (!candidate.equals(base) && isWideStandingArea(candidate)
                    && trafficYieldPositionFree(candidate)) {
                Path path = probePathTo(candidate);
                if (path != null && path.canReach()) {
                    return new YieldRoute(candidate.immutable(), path);
                }
                // Ground navigation may omit a path object for a one-block sidestep,
                // even though the adjacent position has valid support and clearance.
                // Accept only that directly verifiable step; farther retreats still
                // require a complete reachable path.
                if (candidate.distManhattan(base) == 1) {
                    return new YieldRoute(candidate.immutable(), null);
                }
            }
            if (candidate.distManhattan(base) >= 6) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = candidate.relative(direction);
                if (visited.add(next) && canStandAt(next)) queue.addLast(next);
            }
        }
        return null;
    }

    private boolean isWideStandingArea(BlockPos position) {
        if (!canStandAt(position)) return false;
        int exits = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (canStandAt(position.relative(direction))) exits++;
        }
        return exits >= 3;
    }

    private boolean trafficYieldPositionFree(BlockPos position) {
        return level().getEntitiesOfClass(
                SettlementCitizenEntity.class,
                new AABB(position).inflate(0.1D),
                citizen -> citizen != this && citizen.isAlive()
        ).isEmpty();
    }

    @Nullable
    private PassageIntent findUpcomingPassage(BlockPos target, boolean useActivePath) {
        Path activePath = useActivePath ? getNavigation().getPath() : null;
        if (activePath != null && !activePath.isDone()) {
            BlockPos previous = blockPosition();
            int first = activePath.getNextNodeIndex();
            int limit = Math.min(activePath.getNodeCount(), first + PASSAGE_LOOKAHEAD_NODES);
            for (int index = first; index < limit; index++) {
                BlockPos candidate = activePath.getNodePos(index);
                Direction movement = movementDirection(previous, candidate, movementDirection(target));
                PassageIntent intent = passageAt(candidate, movement);
                if (intent != null) return intent;
                previous = candidate;
            }
        }

        Direction movement = movementDirection(target);
        BlockPos cursor = blockPosition();
        for (int step = 0; step <= 3; step++) {
            BlockPos candidate = cursor.relative(movement, step);
            PassageIntent intent = passageAt(candidate, movement);
            if (intent != null) return intent;
        }
        return null;
    }

    @Nullable
    private PassageIntent passageAt(BlockPos position, Direction movement) {
        BlockState state = level().getBlockState(position);
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos lower = state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                    ? position.below() : position;
            return new PassageIntent(lower.immutable(), movement, passageApproachCells(lower, movement));
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return new PassageIntent(position.immutable(), movement, passageApproachCells(position, movement));
        }
        if (!isNarrowPassageCell(position, movement)) return null;

        Direction negative = movement.getAxis() == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
        Direction positive = negative.getOpposite();
        BlockPos first = position.immutable();
        for (int step = 0; step < PASSAGE_MAX_LENGTH; step++) {
            BlockPos previous = first.relative(negative);
            if (!isNarrowPassageCell(previous, movement)) break;
            first = previous.immutable();
        }
        List<BlockPos> cells = new ArrayList<>();
        BlockPos cursor = first;
        for (int step = 0; step < PASSAGE_MAX_LENGTH && isNarrowPassageCell(cursor, movement); step++) {
            cells.add(cursor.immutable());
            cursor = cursor.relative(positive);
        }
        BlockPos before = first.relative(negative);
        BlockPos after = cursor;
        if (canStandAt(before)) cells.addFirst(before.immutable());
        if (canStandAt(after)) cells.add(after.immutable());
        return new PassageIntent(first, movement, List.copyOf(cells));
    }

    private List<BlockPos> passageApproachCells(BlockPos passage, Direction movement) {
        List<BlockPos> cells = new ArrayList<>();
        for (int offset = -2; offset <= 2; offset++) {
            BlockPos candidate = passage.relative(movement, offset);
            if (offset == 0 || canStandAt(candidate)) cells.add(candidate.immutable());
        }
        return List.copyOf(cells);
    }

    private boolean isNarrowPassageCell(BlockPos position, Direction movement) {
        if (!canStandAt(position)) return false;
        Direction side = movement.getClockWise();
        return !canStandAt(position.relative(side)) && !canStandAt(position.relative(side.getOpposite()));
    }

    private Direction movementDirection(BlockPos target) {
        int dx = target.getX() - getBlockX();
        int dz = target.getZ() - getBlockZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction movementDirection(BlockPos from, BlockPos to, Direction fallback) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return fallback;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        ConstructionPlanningBudget.forget(level(), getUUID());
        CitizenNavigationReservationService.releaseAll(level(), getUUID());
        super.remove(reason);
    }

    private boolean canStandAt(BlockPos position) {
        BlockState feet = level().getBlockState(position);
        // Accumulated ordinary snow is a walkable raised floor. Check the full
        // resident body above its real collision surface instead of requiring
        // the entire feet cell to be empty. Powder snow remains excluded.
        if (feet.is(Blocks.SNOW)) return canUseBedStation(position);
        BlockState head = level().getBlockState(position.above());
        BlockPos supportPosition = position.below();
        BlockState support = level().getBlockState(supportPosition);
        if (support.getBlock() instanceof TrapDoorBlock
                && support.hasProperty(BlockStateProperties.OPEN)
                && support.getValue(BlockStateProperties.OPEN)) {
            return false;
        }
        boolean stair=support.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
        boolean stableTop = support.getCollisionShape(level(), supportPosition).toAabbs().stream()
                .anyMatch(box -> box.maxY >= 0.9D
                        && box.minX <= (stair?.5D:.25D) && box.maxX >= (stair?.5D:.75D)
                        && box.minZ <= (stair?.5D:.25D) && box.maxZ >= (stair?.5D:.75D));
        return feet.getCollisionShape(level(), position).isEmpty()
                && head.getCollisionShape(level(), position.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && stableTop;
    }

    /**
     * Vanilla scaffolding is occupied from inside its own block cell. It is not
     * an ordinary floor with a solid block below, so the normal standing test
     * deliberately rejects a horizontal suspended run. Construction movement
     * nevertheless has to cross such a supported scaffold catwalk exactly as a
     * player can. Keep this exception inside the bounded construction controller
     * so ordinary navigation never treats arbitrary scaffolding as open ground.
     */
    private boolean canOccupyConstructionCell(BlockPos position) {
        if (canStandAt(position)) return true;
        if (isClimbableCell(position)) {
            BlockState state = level().getBlockState(position);
            BlockState head = level().getBlockState(position.above());
            return state.getFluidState().isEmpty()
                    && head.getFluidState().isEmpty()
                    && (head.getCollisionShape(level(), position.above()).isEmpty()
                    || isClimbableCell(position.above()));
        }
        // Vanilla scaffolding has only a thin top collision surface, so the
        // ordinary full-block standing predicate rejects the air cell directly
        // above it. The bounded construction controller still needs that cell
        // for a real player-like horizontal catwalk step.
        if (isClimbableCell(position.below())) {
            BlockState feet = level().getBlockState(position);
            BlockState head = level().getBlockState(position.above());
            return feet.getCollisionShape(level(), position).isEmpty()
                    && head.getCollisionShape(level(), position.above()).isEmpty()
                    && feet.getFluidState().isEmpty()
                    && head.getFluidState().isEmpty();
        }
        return false;
    }

    private boolean canUsePartialConstructionSurface(BlockPos position) {
        BlockState feet = level().getBlockState(position);
        BlockState head = level().getBlockState(position.above());
        BlockPos supportPosition = position.below();
        BlockState support = level().getBlockState(supportPosition);
        if (!feet.getCollisionShape(level(), position).isEmpty()
                || !head.getCollisionShape(level(), position.above()).isEmpty()
                || !feet.getFluidState().isEmpty()
                || !head.getFluidState().isEmpty()) return false;
        return support.getCollisionShape(level(), supportPosition).toAabbs().stream()
                .anyMatch(box -> box.maxY >= 0.45D && box.maxY < 0.9D
                        && box.minX <= 0.25D && box.maxX >= 0.75D
                        && box.minZ <= 0.25D && box.maxZ >= 0.75D);
    }

    private record NavigationSegment(BlockPos position, Path path) {
    }

    private record RoadSearchNode(BlockPos position, int steps) {
    }

    private record YieldRoute(BlockPos position, @Nullable Path path) {
    }

    private record PassageIntent(BlockPos key, Direction direction, List<BlockPos> cells) {
    }

    private record ExistingClimbRoute(BlockPos entry, BlockPos column, int topY) {
    }

    private boolean nearBlock(BlockPos position) {
        return distanceToSqr(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D) <= 1.0D;
    }

    private void manageNearbyDoors() {
        if (level().isClientSide) {
            return;
        }
        BlockPos center = blockPosition();
        for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 2, 1))) {
            BlockState state = level().getBlockState(cursor);
            if (!(state.getBlock() instanceof DoorBlock door) || !state.is(BlockTags.WOODEN_DOORS)) {
                continue;
            }
            BlockPos lower = state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                    ? cursor.below() : cursor;
            BlockState lowerState = level().getBlockState(lower);
            if (lowerState.getBlock() instanceof DoorBlock lowerDoor
                    && lowerState.hasProperty(DoorBlock.OPEN)
                    && !lowerState.getValue(DoorBlock.OPEN)) {
                lowerDoor.setOpen(this, level(), lowerState, lower, true);
                openedDoors.add(lower.immutable());
            }
        }

        Iterator<BlockPos> iterator = openedDoors.iterator();
        while (iterator.hasNext()) {
            BlockPos doorPos = iterator.next();
            if (distanceToSqr(doorPos.getX() + 0.5D, doorPos.getY(), doorPos.getZ() + 0.5D) <= 4.0D) {
                continue;
            }
            BlockState state = level().getBlockState(doorPos);
            if (!(state.getBlock() instanceof DoorBlock door)) {
                iterator.remove();
                continue;
            }
            AABB doorway = new AABB(doorPos).expandTowards(0.0D, 1.0D, 0.0D).inflate(0.15D);
            if (!level().hasNeighborSignal(doorPos)
                    && level().getEntitiesOfClass(LivingEntity.class, doorway).isEmpty()) {
                door.setOpen(this, level(), state, doorPos, false);
                iterator.remove();
            }
        }
    }

    /** Gives every connected profession a visible hand action while actually stationed at work. */
    private void animateVisibleWork() {
        if (tickCount % 20 != Math.floorMod(getUUID().hashCode(), 20)
                || !getNavigation().isDone() || getDeltaMovement().horizontalDistanceSqr() > 0.0025D) return;
        CitizenActivity current = activity();
        boolean working = switch (current) {
            case FARMING, BUILDING, REPAIRING, DEMOLISHING, HERDING, HUNTING,
                    TOOLMAKING, FORESTRY, FISHING, SAWING, MINING -> true;
            case TO_OUTPUT_CONTAINER, TO_WAREHOUSE, COLLECTING_PUBLIC_ITEM ->
                    commandedTarget != null && nearBlock(commandedTarget);
            default -> false;
        };
        if (working) swing(InteractionHand.MAIN_HAND, true);
    }

    private void defendSelf() {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        LivingEntity attacker = getLastHurtByMob();
        if (!(attacker instanceof Enemy) || !attacker.isAlive() || distanceToSqr(attacker) > 196.0D) {
            defending = false;
            if (attackCooldown > 0) attackCooldown--;
            return;
        }
        UUID settlementId = CitizenProjectionService.settlementId(this);
        UUID citizenId = CitizenProjectionService.citizenId(this);
        SettlementRecord settlement = settlementId == null ? null
                : SettlementSavedData.get(serverLevel).findById(settlementId).orElse(null);
        CitizenRecord citizen = settlement == null || citizenId == null ? null : settlement.citizen(citizenId);
        if (citizen == null) return;
        if (settlement.community().evacuation) {
            defending = false;
            return;
        }
        defending = true;
        if (isSleeping()) stopSleeping();
        ItemStack weapon = CitizenEquipmentService.bestWeapon(citizen);
        if (!weapon.isEmpty()) setItemSlot(EquipmentSlot.MAINHAND, weapon);
        setActivity(CitizenActivity.SELF_DEFENSE);
        if (distanceToSqr(attacker) > 6.25D) {
            getNavigation().moveTo(attacker, 0.85D);
            return;
        }
        getNavigation().stop();
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        doHurtTarget(attacker);
        int weaponSlot = citizen.findInventorySlot(stack -> ItemStack.isSameItemSameComponents(stack, weapon));
        if (weaponSlot >= 0 && citizen.damageInventoryItem(weaponSlot, 1)) {
            SettlementSavedData.get(serverLevel).touch();
        }
        attackCooldown = 20;
    }

    private void resetProgress(long gameTime) {
        lastProgressTargetDistance = commandedTarget == null
                ? Double.POSITIVE_INFINITY
                : Math.sqrt(distanceToSqr(commandedTarget.getX() + 0.5D,
                commandedTarget.getY(), commandedTarget.getZ() + 0.5D));
        lastProgressPath = getNavigation().getPath();
        lastProgressPathNodeIndex = lastProgressPath == null
                ? -1 : lastProgressPath.getNextNodeIndex();
        lastProgressGameTime = gameTime;
    }

    /** Identifies one physical route to one target inside a blueprint work order. */
    private record ConstructionRouteKey(UUID taskId, BlockPos target, BlockPos route) {
    }

    private void openCitizenManagement(ServerPlayer player) {
        UUID settlementId = CitizenProjectionService.settlementId(this);
        UUID citizenId = CitizenProjectionService.citizenId(this);
        if (settlementId == null || citizenId == null) {
            return;
        }
        SettlementRecord settlement = SettlementSavedData.get(player.serverLevel())
                .findById(settlementId)
                .orElse(null);
        CitizenRecord citizen = settlement == null ? null : settlement.citizen(citizenId);
        if (citizen != null) CitizenAssignmentService.open(player, settlement, citizen);
    }
}

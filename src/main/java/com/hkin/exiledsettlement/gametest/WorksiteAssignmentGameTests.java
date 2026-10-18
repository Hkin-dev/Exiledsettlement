package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.citizen.WorksiteAssignmentService;
import com.hkin.exiledsettlement.network.PlacementNetworking;
import com.hkin.exiledsettlement.network.SettlementDetailsPayload;
import com.hkin.exiledsettlement.network.BuildingSnapshotRequestLimiter;
import com.hkin.exiledsettlement.network.OpenBuildingManagementPayload;
import com.hkin.exiledsettlement.network.RequestBuildingManagementPayload;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Request-boundary transactions and live-entity display counts, not construction efficiency acceptance. */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorksiteAssignmentGameTests {
    private WorksiteAssignmentGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void worksiteAssignmentRejectsCrossTargetDeadAndStaleRequests(GameTestHelper helper) {
        SettlementRecord settlement = settlement(helper);
        BlueprintRecord first = blueprint(settlement, settlement.corePosition());
        BlueprintRecord second = blueprint(settlement, settlement.corePosition().east(16));
        CitizenRecord citizen = settlement.citizens().iterator().next();
        var assigned = WorksiteAssignmentService.assign(settlement, settlement.ownerId(), first.id(),
                citizen.id(), false, 0);
        helper.assertTrue(assigned.success() && first.assignmentMode() == WorksiteAssignmentMode.MANUAL
                        && first.id().equals(citizen.preferredWorksiteId()),
                "Designating a worker did not atomically switch the target to manual mode");
        var stolen = WorksiteAssignmentService.assign(settlement, settlement.ownerId(), second.id(),
                citizen.id(), false, 0);
        helper.assertTrue(!stolen.success() && "WORKSITE_ASSIGNMENT_CONFLICT".equals(stolen.reason())
                        && first.id().equals(citizen.preferredWorksiteId())
                        && second.assignmentMode() == WorksiteAssignmentMode.AUTO && second.assignmentRevision() == 0,
                "A fresh revision on another worksite silently stole an assigned resident");
        var stale = WorksiteAssignmentService.setMode(settlement, settlement.ownerId(), first.id(),
                WorksiteAssignmentMode.AUTO, 0);
        helper.assertTrue(!stale.success() && "CONFIGURATION_CONFLICT".equals(stale.reason()),
                "Stale mode switch was not reported as a configuration conflict");
        var unauthorized = WorksiteAssignmentService.assign(settlement, UUID.randomUUID(), second.id(),
                citizen.id(), false, 0);
        helper.assertTrue(!unauthorized.success() && "NO_PERMISSION".equals(unauthorized.reason()),
                "An outsider could assign a resident");
        citizen.markDead(citizen.lastPosition());
        var dead = WorksiteAssignmentService.assign(settlement, settlement.ownerId(), second.id(),
                citizen.id(), false, 0);
        helper.assertTrue(!dead.success() && "WORKSITE_WORKER_INVALID".equals(dead.reason())
                        && citizen.preferredWorksiteId() == null && second.assignmentRevision() == 0,
                "A dead resident received an invisible worksite assignment");
        settlement.deactivate();
        var inactive = WorksiteAssignmentService.setMode(settlement, settlement.ownerId(), second.id(),
                WorksiteAssignmentMode.MANUAL, second.assignmentRevision());
        helper.assertTrue(!inactive.success() && "CORE_INACTIVE".equals(inactive.reason())
                        && second.assignmentMode() == WorksiteAssignmentMode.AUTO,
                "A stale request changed worksite mode after the settlement core was deactivated");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void worksiteCapacityAndSpecialistLocksRemainAuthoritative(GameTestHelper helper) {
        SettlementRecord settlement = settlement(helper);
        BlueprintRecord site = blueprint(settlement, settlement.corePosition());
        List<CitizenRecord> citizens = List.copyOf(settlement.citizens());
        int limit = CitizenWorkService.worksiteWorkerCap();
        helper.assertTrue(citizens.size() > limit, "Fixture must contain more workers than site capacity");
        for (int i = 0; i < limit; i++) {
            helper.assertTrue(WorksiteAssignmentService.assign(settlement, settlement.ownerId(), site.id(),
                            citizens.get(i).id(), false, site.assignmentRevision()).success(),
                    "Eligible resident was rejected below the worksite capacity");
        }
        CitizenRecord extra = citizens.get(limit);
        var full = WorksiteAssignmentService.assign(settlement, settlement.ownerId(), site.id(),
                extra.id(), false, site.assignmentRevision());
        helper.assertTrue(full.success() && site.id().equals(extra.preferredWorksiteId()),
                "Construction still has the historical two-worker limit");
        extra = citizens.get(limit + 1);
        BlueprintRecord other = blueprint(settlement, settlement.corePosition().east(20));
        extra.setManualAssignment(CitizenProfession.FARMER, UUID.randomUUID());
        var specialist = WorksiteAssignmentService.assign(settlement, settlement.ownerId(), other.id(),
                extra.id(), false, other.assignmentRevision());
        helper.assertTrue(!specialist.success() && extra.profession() == CitizenProfession.FARMER
                        && extra.hasManualAssignment() && extra.preferredWorksiteId() == null,
                "Worksite assignment silently converted a specialist into a general worker");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void automaticHandoffSurvivesReloadAndPreservesMaterialsAndProfessionLock(GameTestHelper helper) {
        SettlementRecord original = settlement(helper);
        BlueprintRecord site = blueprint(original, original.corePosition());
        CitizenRecord citizen = original.citizens().iterator().next();
        citizen.setManualAssignment(CitizenProfession.WORKER, null);
        citizen.insertIntoInventory(new ItemStack(Items.OAK_PLANKS, 37));
        citizen.setPreferredWorksite(site.id());
        helper.assertTrue(!WorksiteAssignmentService.releasePending(original, citizen),
                "An older AUTO worksite preference was mistaken for a requested release");
        var legacyTag = citizen.save(helper.getLevel().registryAccess());
        legacyTag.remove("WorksiteReleasePending");
        CitizenRecord legacyLoaded = CitizenRecord.load(legacyTag, helper.getLevel().registryAccess());
        helper.assertTrue(site.id().equals(legacyLoaded.preferredWorksiteId())
                        && !legacyLoaded.worksiteReleasePending()
                        && !WorksiteAssignmentService.releasePending(original, legacyLoaded),
                "A save without the new handoff field lost its pre-existing AUTO worksite preference");
        WorksiteAssignmentService.assign(original, original.ownerId(), site.id(), citizen.id(), false, 0);
        citizen.startTask(CitizenWorkState.TO_BLUEPRINT, site.anchor().east(), null, site.id());
        helper.assertTrue(WorksiteAssignmentService.setMode(original, original.ownerId(), site.id(),
                        WorksiteAssignmentMode.AUTO, site.assignmentRevision()).success()
                        && WorksiteAssignmentService.releasePending(original, citizen),
                "AUTO did not retain the current assignment until a safe handoff boundary");
        WorksiteAssignmentService.setMode(original, original.ownerId(), site.id(),
                WorksiteAssignmentMode.MANUAL, site.assignmentRevision());
        helper.assertTrue(!citizen.worksiteReleasePending() && site.id().equals(citizen.preferredWorksiteId()),
                "Switching back to MANUAL did not cancel an unfinished automatic handoff");
        WorksiteAssignmentService.setMode(original, original.ownerId(), site.id(),
                WorksiteAssignmentMode.AUTO, site.assignmentRevision());
        CitizenWorkService.releasePendingWorksiteAssignments(helper.getLevel(), new SettlementSavedData(), original);
        helper.assertTrue(site.id().equals(citizen.preferredWorksiteId()),
                "An unloaded worker lost the pending handoff before its movement could be released");
        SettlementRecord loaded = SettlementRecord.load(original.save(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        CitizenRecord resident = loaded.citizen(citizen.id());
        helper.assertTrue(resident != null && WorksiteAssignmentService.releasePending(loaded, resident),
                "Pending automatic handoff was lost across save/load");
        SettlementCitizenEntity entity = projection(helper, loaded, resident, site.anchor());
        try {
            entity.setOnGround(false);
            CitizenWorkService.releasePendingWorksiteAssignments(helper.getLevel(), new SettlementSavedData(), loaded);
            helper.assertTrue(resident.preferredWorksiteId() != null,
                    "An airborne worker's construction controller was interrupted");
            entity.setOnGround(true);
            CitizenWorkService.releasePendingWorksiteAssignments(helper.getLevel(), new SettlementSavedData(), loaded);
            helper.assertTrue(resident.preferredWorksiteId() == null
                            && resident.targetBlueprintId() == null
                            && resident.workState() == CitizenWorkState.IDLE
                            && resident.hasManualAssignment() && resident.manualProfession() == CitizenProfession.WORKER
                            && inventoryCount(resident, Items.OAK_PLANKS) == 37,
                    "Safe worksite handoff lost materials, retained a site claim, or removed the separate profession lock");
        } finally {
            entity.stopSettlementNavigation();
            entity.discard();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void demolitionManualEmptyAndAutomaticReleaseRemainDistinct(GameTestHelper helper) {
        SettlementRecord settlement = settlement(helper);
        BuildingRecord site = settlement.addBuilding(BuildingType.HOUSE, settlement.corePosition());
        site.queueDemolition();
        var mode = WorksiteAssignmentService.setMode(settlement, settlement.ownerId(), site.id(),
                WorksiteAssignmentMode.MANUAL, site.configurationRevision());
        helper.assertTrue(mode.success() && site.worksiteAssignmentMode() == WorksiteAssignmentMode.MANUAL
                        && settlement.citizens().stream().noneMatch(c -> site.id().equals(c.preferredWorksiteId())),
                "An empty manual demolition site silently acquired a worker");
        CitizenRecord citizen = settlement.citizens().iterator().next();
        helper.assertTrue(WorksiteAssignmentService.assign(settlement, settlement.ownerId(), site.id(),
                        citizen.id(), false, site.configurationRevision()).success(),
                "Demolition site rejected its explicit worker");
        WorksiteAssignmentService.setMode(settlement, settlement.ownerId(), site.id(),
                WorksiteAssignmentMode.AUTO, site.configurationRevision());
        CitizenWorkService.releasePendingWorksiteAssignments(helper.getLevel(), new SettlementSavedData(), settlement);
        helper.assertTrue(citizen.preferredWorksiteId() == null
                        && site.worksiteAssignmentMode() == WorksiteAssignmentMode.AUTO,
                "An idle demolition worker remained locked after switching to automatic");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void blueprintSnapshotSeparatesAssignedArrivedAndActuallyWorking(GameTestHelper helper) {
        SettlementRecord settlement = settlement(helper);
        BlueprintRecord site = blueprint(settlement, settlement.corePosition());
        CitizenRecord citizen = settlement.citizens().iterator().next();
        citizen.startTask(CitizenWorkState.TO_CONSTRUCTION_SUPPLY, site.anchor().east(), null, site.id());
        SettlementCitizenEntity entity = projection(helper, settlement, citizen, site.anchor());
        try {
            var fetching = PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site);
            helper.assertTrue(fetching.assignedWorkers() == 1 && fetching.arrivedWorkers() == 1
                            && fetching.workingWorkers() == 0,
                    "A worker fetching materials at the site was counted as constructing");
            citizen.clearWork();
            citizen.startTask(CitizenWorkState.TO_BLUEPRINT, site.anchor().east(), null, site.id());
            var working = PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site);
            helper.assertTrue(working.workingWorkers() == 0,
                    "A merely assigned worker without a paid site action was counted as productive");
            citizen.updateWorkFailure("CONSTRUCTION_POSITION_UNREACHABLE");
            helper.assertTrue(PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site)
                            .workingWorkers() == 0, "Blocked construction counted as working");
            citizen.updateWorkFailure("");
            citizen.updateTargetPosition(site.anchor().east(20));
            helper.assertTrue(PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site)
                            .workingWorkers() == 0, "Walking toward a distant target counted as working");
            citizen.updateTargetPosition(site.anchor().east());
            citizen.updateActivity(CitizenActivity.RESTING_AT_HOME);
            helper.assertTrue(PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site)
                            .workingWorkers() == 0, "Resting resident counted as working");
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            try {
                SettlementDetailsPayload.writeEntry(buffer, working);
                var decoded = SettlementDetailsPayload.readEntry(buffer);
                helper.assertTrue(decoded.equals(working) && buffer.readableBytes() == 0,
                        "Building snapshot codec lost or reordered the three workforce counts");
                var snapshot = new OpenBuildingManagementPayload(settlement.id(), settlement.corePosition(), working,
                        64, 32, List.of(), List.of(), true);
                OpenBuildingManagementPayload.STREAM_CODEC.encode(buffer, snapshot);
                helper.assertTrue(snapshot.equals(OpenBuildingManagementPayload.STREAM_CODEC.decode(buffer))
                                && buffer.readableBytes() == 0,
                        "Periodic snapshot response lost its refresh-only guard");
            } finally {
                buffer.release();
            }
        } finally {
            entity.stopSettlementNavigation();
            entity.discard();
        }
        helper.assertTrue(PlacementNetworking.blueprintEntry(helper.getLevel(), settlement, site)
                        .arrivedWorkers() == 0,
                "An unloaded projection was still counted physically present");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "worksite_assignments")
    public static void buildingSnapshotRefreshIsBoundedAndPreservesRequestIntent(GameTestHelper helper) {
        var limiter = new BuildingSnapshotRequestLimiter();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        helper.assertTrue(limiter.accept(firstConnection, 100, false)
                        && !limiter.accept(firstConnection, 100, false)
                        && !limiter.accept(firstConnection, 104, false)
                        && limiter.accept(firstConnection, 105, false),
                "Explicit building opens can trigger unbounded repeated snapshots");
        helper.assertTrue(!limiter.accept(firstConnection, 124, true)
                        && limiter.accept(firstConnection, 125, true)
                        && limiter.accept(secondConnection, 125, true),
                "Refresh frequency is not bounded per player or one player throttles another");
        helper.assertTrue(limiter.accept(firstConnection, 0, true),
                "A server tick reset permanently suppressed building snapshots");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        try {
            var request = new RequestBuildingManagementPayload(BlockPos.ZERO, UUID.randomUUID(), true);
            RequestBuildingManagementPayload.STREAM_CODEC.encode(buffer, request);
            helper.assertTrue(request.equals(RequestBuildingManagementPayload.STREAM_CODEC.decode(buffer))
                            && buffer.readableBytes() == 0,
                    "Periodic refresh request became an explicit open request during serialization");
            helper.assertTrue(!new RequestBuildingManagementPayload(BlockPos.ZERO, UUID.randomUUID()).refreshOnly(),
                    "An intentional building selection no longer opens the screen");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static SettlementRecord settlement(GameTestHelper helper) {
        return new SettlementRecord(UUID.randomUUID(), "Worksite assignment test",
                helper.absolutePos(new BlockPos(4, 3, 4)), UUID.randomUUID());
    }

    private static BlueprintRecord blueprint(SettlementRecord settlement, BlockPos anchor) {
        return settlement.addBlueprint(BuildingType.HOUSE, anchor, anchor, Direction.NORTH,
                settlement.ownerId(), false);
    }

    private static SettlementCitizenEntity projection(
            GameTestHelper helper, SettlementRecord settlement, CitizenRecord citizen, BlockPos position
    ) {
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create live resident projection");
        entity.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0, 0);
        entity.setNoGravity(true);
        entity.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        entity.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, citizen.id().toString());
        helper.getLevel().addFreshEntity(entity);
        citizen.updateProjection(entity.getUUID(), position);
        return entity;
    }

    private static int inventoryCount(CitizenRecord citizen, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (citizen.inventoryItem(slot).is(item)) total += citizen.inventoryItem(slot).getCount();
        }
        return total;
    }
}

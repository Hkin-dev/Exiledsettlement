package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementRecord;

import java.util.UUID;

/** Server-thread transactions for temporary worksite assignments, not profession locks. */
public final class WorksiteAssignmentService {
    private WorksiteAssignmentService() { }

    public static OperationResult<Boolean> setMode(
            SettlementRecord settlement, UUID actor, UUID siteId,
            WorksiteAssignmentMode mode, int expectedRevision
    ) {
        if (!settlement.isMember(actor)) return OperationResult.failure("NO_PERMISSION");
        if (!settlement.active()) return OperationResult.failure("CORE_INACTIVE");
        Target target = target(settlement, siteId);
        if (target == null) return OperationResult.failure("WORKSITE_UNAVAILABLE");
        if (!target.matches(expectedRevision)) return OperationResult.failure("CONFIGURATION_CONFLICT");
        boolean changed = target.setMode(mode, expectedRevision);
        if (changed) {
            for (CitizenRecord citizen : settlement.citizens()) {
                if (!siteId.equals(citizen.preferredWorksiteId())) continue;
                if (mode == WorksiteAssignmentMode.AUTO) citizen.requestWorksiteRelease();
                else citizen.cancelWorksiteRelease();
            }
        }
        // An explicit pending flag preserves older AUTO preferences unchanged.
        // The next safe work-cycle boundary releases only the requested handoff.
        return OperationResult.success(changed);
    }

    public static OperationResult<Boolean> assign(
            SettlementRecord settlement, UUID actor, UUID siteId, UUID citizenId,
            boolean unlock, int expectedRevision
    ) {
        if (!settlement.isMember(actor)) return OperationResult.failure("NO_PERMISSION");
        if (!settlement.active()) return OperationResult.failure("CORE_INACTIVE");
        Target target = target(settlement, siteId);
        if (target == null) return OperationResult.failure("WORKSITE_UNAVAILABLE");
        if (!target.matches(expectedRevision)) return OperationResult.failure("CONFIGURATION_CONFLICT");
        CitizenRecord citizen = settlement.citizen(citizenId);
        if (citizen == null || !citizen.canWork()
                || !CitizenWorkService.constructionCapable(citizen.profession())) {
            return OperationResult.failure("WORKSITE_WORKER_INVALID");
        }
        boolean here = siteId.equals(citizen.preferredWorksiteId());
        if (unlock) {
            if (!here) return OperationResult.failure("WORKSITE_ASSIGNMENT_CONFLICT");
            citizen.setPreferredWorksite(null);
            target.changed();
            return OperationResult.success(true);
        }
        if (citizen.preferredWorksiteId() != null && !here
                || citizen.hasManualAssignment() && citizen.manualProfession().specialist()) {
            return OperationResult.failure("WORKSITE_ASSIGNMENT_CONFLICT");
        }
        long assigned = settlement.citizens().stream().filter(CitizenRecord::alive)
                .filter(other -> siteId.equals(other.preferredWorksiteId())).count();
        BlueprintRecord activeBlueprint = settlement.blueprint(siteId);
        if (!here && (activeBlueprint == null || activeBlueprint.cancellationQueued())
                && assigned >= CitizenWorkService.worksiteWorkerCap()) {
            return OperationResult.failure("WORKSITE_ASSIGNMENT_FULL");
        }
        boolean modeChanged = target.setMode(WorksiteAssignmentMode.MANUAL, expectedRevision);
        if (modeChanged) {
            for (CitizenRecord other : settlement.citizens()) {
                if (siteId.equals(other.preferredWorksiteId())) other.cancelWorksiteRelease();
            }
        }
        boolean residentChanged = citizen.setPreferredWorksite(siteId);
        residentChanged |= citizen.cancelWorksiteRelease();
        if (residentChanged && !modeChanged) target.changed();
        return OperationResult.success(modeChanged || residentChanged);
    }

    public static boolean releasePending(SettlementRecord settlement, CitizenRecord citizen) {
        if (citizen.preferredWorksiteId() == null || !citizen.worksiteReleasePending()) return false;
        Target target = target(settlement, citizen.preferredWorksiteId());
        return target != null && target.mode() == WorksiteAssignmentMode.AUTO;
    }

    /** Called only between synchronous work actions, after active climbing has ended. */
    public static boolean finishRelease(SettlementRecord settlement, CitizenRecord citizen) {
        if (!releasePending(settlement, citizen)) return false;
        Target target = target(settlement, citizen.preferredWorksiteId());
        citizen.setPreferredWorksite(null);
        target.changed();
        return true;
    }

    public static boolean currentTaskBelongsTo(CitizenRecord citizen, UUID siteId) {
        return siteId.equals(citizen.targetBlueprintId())
                || citizen.workState() == CitizenWorkState.TO_DEMOLITION
                && siteId.equals(citizen.targetBuildingId());
    }

    private static Target target(SettlementRecord settlement, UUID siteId) {
        BlueprintRecord blueprint = settlement.blueprint(siteId);
        if (blueprint != null) return new Target(blueprint, null);
        BuildingRecord building = settlement.building(siteId);
        return building != null && building.active() && building.demolitionQueued()
                ? new Target(null, building) : null;
    }

    private record Target(BlueprintRecord blueprint, BuildingRecord building) {
        boolean matches(int revision) {
            return blueprint != null ? blueprint.assignmentRevisionMatches(revision)
                    : building.configurationMatches(revision);
        }
        WorksiteAssignmentMode mode() {
            return blueprint != null ? blueprint.assignmentMode() : building.worksiteAssignmentMode();
        }
        boolean setMode(WorksiteAssignmentMode mode, int revision) {
            return blueprint != null ? blueprint.setAssignmentMode(mode, revision)
                    : building.setWorksiteAssignmentMode(mode, revision);
        }
        void changed() {
            if (blueprint != null) blueprint.markAssignmentChanged();
            else building.markConfigurationChanged();
        }
    }
}

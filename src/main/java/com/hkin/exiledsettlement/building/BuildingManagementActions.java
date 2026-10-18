package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Shared by the player UI and the explicitly enabled save-test commands. */
public final class BuildingManagementActions {
    private BuildingManagementActions() { }

    public static boolean production(SettlementRecord settlement, BuildingRecord building, ResourceLocation item) {
        boolean changed = building.setProductionId(item);
        if (changed) for (var pending : settlement.blueprints()) {
            if (pending.region() == null || !building.id().equals(pending.region().workingBuilding)
                    || pending.region() == building.region()) continue;
            pending.region().cells.replaceAll((key, cell) -> new com.hkin.exiledsettlement.region.ProductionRegion.Cell(
                    cell.ground(), cell.use(), BuildingProductionService.supported(cell.type(), item)
                    ? item : cell.production(), cell.type()));
            pending.region().invalidate();
        }
        return changed;
    }

    /** Returns an explicit blocker, or null on success (including an already applied lock). */
    public static String staff(SettlementSavedData data, SettlementRecord settlement, UUID actor,
                               BuildingRecord building, UUID citizenId, boolean unlock, int revision) {
        var citizen = settlement.citizen(citizenId);
        var profession = CitizenProfession.forWorkplace(building.type());
        if (!settlement.isMember(actor)) return "NO_PERMISSION";
        if (citizen == null || profession == null) return "WORKSITE_UNAVAILABLE";
        if (!building.configurationMatches(revision)) return "CONFIGURATION_CONFLICT";
        boolean lockedHere = citizen.hasManualAssignment() && building.id().equals(citizen.manualWorkBuildingId());
        boolean changed = false;
        if (unlock) {
            if (lockedHere) changed = data.requestCitizenManualRelease(settlement, actor, citizenId);
        } else {
            if (!citizen.canWork() || !building.active() || !building.operational()
                    || citizen.hasManualAssignment() && !lockedHere) return "STAFFING_CAPACITY_BLOCKED";
            if (lockedHere && !citizen.manualReleasePending()) return null;
            if (settlement.lockedWorkers(building.id()) >= building.workerCapacity()) return "STAFFING_CAPACITY_BLOCKED";
            if (building.workerTarget() <= settlement.lockedWorkers(building.id())
                    && !data.setBuildingWorkerTarget(settlement, actor, building.id(), building.workerTarget() + 1, revision))
                return "STAFFING_CAPACITY_BLOCKED";
            changed = data.setCitizenManualAssignment(settlement, actor, citizenId, profession, building.id());
        }
        if (changed) {
            building.markConfigurationChanged();
            ProfessionAssignmentService.refresh(data, settlement);
            data.touch();
        }
        return !unlock && !changed ? "STAFFING_CAPACITY_BLOCKED" : null;
    }
}

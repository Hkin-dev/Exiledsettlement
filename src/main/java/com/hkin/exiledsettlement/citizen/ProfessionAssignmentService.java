package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProfessionAssignmentService {
    private ProfessionAssignmentService() {
    }

    public static void refresh(SettlementSavedData data, SettlementRecord settlement) {
        List<CitizenRecord> alive = settlement.citizens().stream()
                .filter(CitizenRecord::canWork)
                .sorted(Comparator.comparing(citizen -> citizen.id().toString()))
                .toList();
        Set<UUID> assigned = new HashSet<>();
        boolean changed = false;
        Map<UUID, Integer> lockedCounts = new HashMap<>();

        for (CitizenRecord citizen : alive) {
            changed |= citizen.completeManualReleaseIfSafe();
        }

        for (CitizenRecord citizen : alive) {
            if (!citizen.hasManualAssignment()) continue;
            CitizenProfession profession = citizen.manualProfession();
            UUID workplaceId = citizen.manualWorkBuildingId();
            if (profession == null) continue;
            if (!profession.specialist()) {
                changed |= citizen.assignProfession(profession, null);
                assigned.add(citizen.id());
                continue;
            }
            BuildingRecord workplace = settlement.building(workplaceId);
            // A lock is the player's durable intent. A damaged, disabled,
            // demolished or currently unreachable workplace remains visibly
            // locked-and-blocked instead of silently sending this citizen elsewhere.
            changed |= citizen.assignProfession(profession, workplaceId);
            assigned.add(citizen.id());
            if (workplace != null && (profession.acceptsWorkplace(workplace.type()) || workplace.region()!=null && com.hkin.exiledsettlement.region.ProductionRegion.acceptsWorkplace(workplace.type(),profession.workplaceType()))) {
                lockedCounts.merge(workplace.id(), 1, Integer::sum);
            }
        }

        // A kitchen batch owns real cargo even while its building is being repaired.
        // Keep its automatic chef until completion unless the player removes the slot.
        for (CitizenRecord citizen : alive) {
            boolean cooking = citizen.profession() == CitizenProfession.CHEF && citizen.life().task.startsWith("cook:");
            boolean firing = citizen.profession() == CitizenProfession.KILN_WORKER && citizen.life().task.startsWith("kiln:");
            boolean brewing = citizen.profession() == CitizenProfession.PRIEST && citizen.life().brewing != null;
            if (assigned.contains(citizen.id()) || !(cooking || firing || brewing)) continue;
            BuildingRecord kitchen = settlement.building(citizen.workBuildingId());
            if (kitchen == null || !kitchen.id().equals(brewing ? citizen.life().brewing.church : citizen.life().destination)
                    || !kitchen.active() || kitchen.demolitionQueued() || kitchen.workerTarget() <= 0
                    || !(cooking ? com.hkin.exiledsettlement.settlement.CommunityCookingService.offersMeals(kitchen)
                    : kitchen.type() == (brewing ? BuildingType.CHURCH : BuildingType.BRICK_KILN))
                    || lockedCounts.getOrDefault(kitchen.id(), 0) >= (firing || brewing ? kitchen.workerTarget() : 1)) continue;
            assigned.add(citizen.id());
            lockedCounts.merge(kitchen.id(), 1, Integer::sum);
        }

        for (CitizenProfession profession : CitizenProfession.values()) {
            if (!profession.specialist()) {
                continue;
            }
            List<BuildingRecord> workplaces = operationalWorkplaces(settlement, profession);
            Map<UUID, Integer> remainingSlots = new HashMap<>();
            workplaces.forEach(building -> {
                int effectiveTarget = Math.min(building.workerCapacity(), building.workerTarget());
                remainingSlots.put(building.id(), Math.max(0,
                        effectiveTarget - lockedCounts.getOrDefault(building.id(), 0)));
            });

            // Preserve an automatic assignment only while that exact building
            // still has an unfilled target slot. Global profession totals are
            // deliberately not used as a second source of truth.
            for (CitizenRecord citizen : alive) {
                UUID workplaceId = citizen.workBuildingId();
                if (assigned.contains(citizen.id())
                        || citizen.profession() != profession
                        || workplaceId == null
                        || remainingSlots.getOrDefault(workplaceId, 0) <= 0) {
                    continue;
                }
                assigned.add(citizen.id());
                remainingSlots.put(workplaceId, remainingSlots.get(workplaceId) - 1);
            }

            // Existing and locked assignments already occupy every available seat.
            // Sorting residents cannot produce another assignment in this case.
            if (remainingSlots.values().stream().noneMatch(slots -> slots > 0)) continue;

            List<CitizenRecord> candidates = alive.stream()
                    .filter(citizen -> !assigned.contains(citizen.id()))
                    .sorted(Comparator
                            .comparingInt((CitizenRecord citizen) -> candidatePriority(citizen, profession))
                            .thenComparing(citizen -> citizen.id().toString()))
                    .toList();
            for (CitizenRecord citizen : candidates) {
                BuildingRecord workplace = workplaces.stream()
                        .filter(building -> remainingSlots.getOrDefault(building.id(), 0) > 0)
                        .min(Comparator.comparingDouble(building -> building.position().distSqr(citizen.lastPosition())))
                        .orElse(null);
                if (workplace == null) {
                    break;
                }
                changed |= citizen.assignProfession(profession, workplace.id());
                assigned.add(citizen.id());
                remainingSlots.put(workplace.id(), remainingSlots.get(workplace.id()) - 1);
            }
        }

        // Seasonal helpers use the same allocator as regular jobs. A second scheduler
        // must not turn them back into workers every building validation cycle.
        if (settlement.community().policy.equals("harvest")
                && !com.hkin.exiledsettlement.settlement.CommunityLifeService.winter(settlement,
                        Math.max(0, settlement.community().lastDay))) {
            List<BuildingRecord> farms = operationalWorkplaces(settlement, CitizenProfession.FARMER).stream()
                    .filter(f -> !settlement.community().danger(f.position())).toList();
            Map<UUID, Integer> used = new HashMap<>();
            alive.stream().filter(c -> assigned.contains(c.id()) && c.workBuildingId() != null)
                    .forEach(c -> used.merge(c.workBuildingId(), 1, Integer::sum));
            int helpers = 0;
            for (CitizenRecord citizen : alive.stream().filter(c -> !assigned.contains(c.id())
                            && !c.hasManualAssignment() && c.preferredWorksiteId() == null)
                    .sorted(Comparator.comparingInt(c -> candidatePriority(c, CitizenProfession.FARMER))).toList()) {
                if (helpers >= 2) break;
                boolean continuing = citizen.profession() == CitizenProfession.FARMER
                        && farms.stream().anyMatch(f -> f.id().equals(citizen.workBuildingId()));
                if (!continuing && (citizen.profession() != CitizenProfession.WORKER
                        || citizen.workState() != CitizenWorkState.IDLE)) continue;
                BuildingRecord farm = farms.stream().filter(f -> used.getOrDefault(f.id(), 0) < f.workerCapacity())
                        .min(Comparator.comparingDouble(f -> f.id().equals(citizen.workBuildingId())
                                ? -1 : f.position().distSqr(citizen.lastPosition()))).orElse(null);
                if (farm == null) break;
                changed |= citizen.assignProfession(CitizenProfession.FARMER, farm.id());
                assigned.add(citizen.id()); used.merge(farm.id(), 1, Integer::sum); helpers++;
            }
        }
        for (CitizenRecord citizen : alive) {
            if (!assigned.contains(citizen.id())) {
                changed |= citizen.assignProfession(CitizenProfession.WORKER, null);
            }
        }
        if (changed) {
            data.touch();
        }
    }

    private static int candidatePriority(CitizenRecord citizen, CitizenProfession desired) {
        if (citizen.profession() == desired) {
            return 0;
        }
        return citizen.profession() == CitizenProfession.WORKER ? 1 : 2;
    }

    private static List<BuildingRecord> operationalWorkplaces(
            SettlementRecord settlement,
            CitizenProfession profession
    ) {
        if (profession.workplaceType() == null) {
            return List.of();
        }
        List<BuildingRecord> result = new ArrayList<>();
        for (BuildingRecord building : settlement.buildings()) {
            if (building.active()
                    && building.operational()
                    && profession.acceptsWorkplace(building.type())
                    && building.capacity() > 0) {
                result.add(building);
            }
        }
        return result;
    }
}

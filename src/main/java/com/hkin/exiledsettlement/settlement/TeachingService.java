package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Map;

/** Only the teacher travels. Recipients may be any living, non-maxed resident of this settlement. */
public final class TeachingService {
    private TeachingService() { }
    private static final Map<CitizenRecord, BlockPos> STATIONS = new WeakHashMap<>();
    private static final Map<CitizenRecord, Long> ROUTE_RETRY = new WeakHashMap<>();

    private static boolean standingSpace(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && level.hasChunkAt(feet.above())
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty();
    }

    /** Derive real covered indoor standing positions without altering any historical template. */
    public static boolean validateSchool(ServerLevel level, BuildingRecord school) {
        var placements = BlueprintTemplates.createForBuilding(school).placements();
        Map<Long, java.util.TreeSet<Integer>> ceilings = new java.util.HashMap<>();
        for (var placement : placements) {
            BlockPos p = placement.position();
            if (placement.mode().isFinalStructure() && level.hasChunkAt(p)
                    && !level.getBlockState(p).getCollisionShape(level, p).isEmpty())
                ceilings.computeIfAbsent(BlockPos.asLong(p.getX(), 0, p.getZ()), ignored -> new java.util.TreeSet<>()).add(p.getY());
        }
        var floors = placements.stream().filter(p -> p.mode().isFinalStructure())
                .map(p -> p.position().above()).distinct()
                .sorted(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingDouble(p -> p.distSqr(school.position())))
                .filter(p -> standingSpace(level, p))
                .filter(p -> {
                    var column = ceilings.get(BlockPos.asLong(p.getX(), 0, p.getZ()));
                    Integer roof = column == null ? null : column.ceiling(p.getY() + 2);
                    return roof != null && roof <= p.getY() + 10;
                })
                .limit(24).toList();
        return school.updateFunction(!floors.isEmpty(), floors.isEmpty() ? BuildingStatus.REGISTERED_ONLY : BuildingStatus.OPERATIONAL,
                floors.isEmpty() ? 0 : 1, floors);
    }

    public static boolean work(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                               CitizenRecord teacher, SettlementCitizenEntity entity) {
        if (teacher.profession() != CitizenProfession.TEACHER || !teacher.canWork()
                || CitizenWorkService.schedulePhase(level.getDayTime(), teacher) != CitizenWorkService.SchedulePhase.WORK
                || teacher.workState() != CitizenWorkState.IDLE || !teacher.life().task.isEmpty()
                || entity.isSleeping() || entity.isDefending() || entity.hasActiveConstructionMovement()
                || CitizenInteractionService.held(entity)) {
            teacher.professionExperience().interrupt(); STATIONS.remove(teacher); return false;
        }
        var school = settlement.building(teacher.workBuildingId());
        if (school == null || !school.active() || !school.operational() || school.demolitionQueued()
                || school.type() != BuildingType.SCHOOL || school.workerTarget() <= 0 || !school.repairPositions().isEmpty()
                || settlement.community().evacuation || settlement.community().danger(school.position())) {
            teacher.professionExperience().interrupt(); STATIONS.remove(teacher);
            teacher.setActivity(CitizenActivity.BLOCKED, "SCHOOL_UNAVAILABLE");
            entity.setActivity(CitizenActivity.BLOCKED); data.touch(); return true;
        }
        BlockPos station = STATIONS.get(teacher);
        if (station == null || !school.servicePositions().contains(station) || !standingSpace(level, station)
                || entity.navigationRetryStage() >= 3) {
            if (level.getGameTime() < ROUTE_RETRY.getOrDefault(teacher, -1L)) {
                teacher.professionExperience().interrupt(); return true;
            }
            BlockPos previous = station;
            station = school.servicePositions().stream().filter(p -> !p.equals(previous))
                    .filter(p -> standingSpace(level, p) && entity.canStandAtPosition(p))
                    .sorted(Comparator.comparingDouble(p -> entity.distanceToSqr(Vec3.atBottomCenterOf(p))))
                    .filter(p -> {
                        if (entity.blockPosition().equals(p)) return true;
                        var path = entity.getNavigation().createPath(p, 0);
                        return path != null && path.canReach();
                    }).findFirst().orElse(null);
            if (station != null) { STATIONS.put(teacher, station); ROUTE_RETRY.remove(teacher); }
            else { STATIONS.remove(teacher); ROUTE_RETRY.put(teacher, level.getGameTime() + 100); }
        }
        if (station == null) {
            teacher.professionExperience().interrupt();
            teacher.setActivity(CitizenActivity.BLOCKED, "SCHOOL_NO_ROUTE");
            entity.setActivity(CitizenActivity.BLOCKED); data.touch(); return true;
        }
        CommunityLifeService.move(teacher, entity, station, CitizenActivity.TEACHING, "");
        data.touch();
        if (!entity.onGround() || !entity.blockPosition().equals(station)
                || !level.noCollision(entity) || entity.distanceToSqr(Vec3.atBottomCenterOf(station)) > 0.64) {
            teacher.professionExperience().interrupt(); return true;
        }
        if (teacher.professionExperience().teach(CommunityLifeService.day(level), level.getGameTime())) {
            List<CitizenRecord> eligible = settlement.citizens().stream()
                    .filter(CitizenRecord::alive).filter(c -> !c.professionExperience().maxed(c.profession())).toList();
            boolean awarded = false;
            if (!eligible.isEmpty()) {
                CitizenRecord recipient = eligible.get(level.random.nextInt(eligible.size()));
                awarded = recipient.professionExperience().addPoint(recipient.profession());
            }
            teacher.professionExperience().finishPoint(awarded);
        }
        data.touch();
        return true;
    }

    /** Read-only school staffing presentation; do not reserve stations or submit paths here. */
    public static boolean isTeaching(ServerLevel level, SettlementRecord settlement, CitizenRecord teacher,
                                     SettlementCitizenEntity entity) {
        var school = settlement.building(teacher.workBuildingId());
        var station = STATIONS.get(teacher);
        return teacher.canWork() && teacher.profession() == CitizenProfession.TEACHER
                && teacher.activity() == CitizenActivity.TEACHING && teacher.workState() == CitizenWorkState.IDLE
                && teacher.professionExperience().pointsToday() < CitizenProfessionExperience.DAILY_POINTS
                && CitizenWorkService.schedulePhase(level.getDayTime(), teacher) == CitizenWorkService.SchedulePhase.WORK
                && school != null && school.active() && school.operational() && !school.demolitionQueued()
                && school.workerTarget() > 0 && school.repairPositions().isEmpty()
                && !settlement.community().evacuation && !settlement.community().danger(school.position())
                && station != null && school.servicePositions().contains(station)
                && entity.blockPosition().equals(station) && entity.onGround() && !entity.isSleeping()
                && !entity.isDefending() && !CitizenInteractionService.held(entity)
                && standingSpace(level, station) && level.noCollision(entity)
                && entity.distanceToSqr(Vec3.atBottomCenterOf(station)) <= 0.64;
    }
}

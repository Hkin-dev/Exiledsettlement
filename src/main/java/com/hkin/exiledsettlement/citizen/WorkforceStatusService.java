package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only presentation counts; never claims a station or submits a path. */
public final class WorkforceStatusService {
    private WorkforceStatusService() { }

    public static boolean arrived(ServerLevel level, CitizenRecord citizen, AABB site) {
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
        return citizen.alive() && entity != null && entity.isAlive()
                && site.contains(entity.position());
    }

    public static boolean working(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
        if (!citizen.alive() || entity == null || !entity.isAlive() || entity.isDefending()
                || !citizen.workFailure().isBlank() || entity.hasActiveConstructionMovement()
                || !entity.getNavigation().isDone()) return false;
        if (citizen.profession()==CitizenProfession.KILN_WORKER)
            return com.hkin.exiledsettlement.settlement.KilnProductionService.isFiring(level,settlement,citizen,entity);
        if (citizen.profession()==CitizenProfession.TEACHER)
            return com.hkin.exiledsettlement.settlement.TeachingService.isTeaching(level,settlement,citizen,entity);
        if (citizen.profession()==CitizenProfession.PRIEST)
            return com.hkin.exiledsettlement.settlement.PriestProductionService.isBrewing(level,settlement,citizen,entity);
        CitizenActivity expected = switch (citizen.workState()) {
            case TO_BLUEPRINT -> CitizenActivity.BUILDING;
            case TO_DEMOLITION -> CitizenActivity.DEMOLISHING;
            case TO_REPAIR -> CitizenActivity.REPAIRING;
            case TO_FARM -> CitizenActivity.FARMING;
            case TO_HERD, TO_PASTURE_FEED -> CitizenActivity.HERDING;
            case TO_HUNT -> CitizenActivity.HUNTING;
            case TO_CAPTURE -> CitizenActivity.CAPTURING;
            case TO_FOREST -> CitizenActivity.FORESTRY;
            case TO_FISH -> CitizenActivity.FISHING;
            case TO_MINE -> CitizenActivity.MINING;
            case TO_MINE_STORAGE -> CitizenActivity.TO_WAREHOUSE;
            case TO_SAWMILL -> CitizenActivity.SAWING;
            case TO_TOOL_WORKSHOP -> CitizenActivity.TOOLMAKING;
            default -> null;
        };
        if (expected == null || citizen.activity() != expected) return false;
        if (citizen.workState() == CitizenWorkState.TO_BLUEPRINT) {
            return CitizenWorkService.isGroundSiteWorking(level, citizen, entity);
        }
        if (citizen.workState() == CitizenWorkState.TO_SAWMILL
                || citizen.workState() == CitizenWorkState.TO_TOOL_WORKSHOP) {
            // Toolmaking reuses its work state while fetching inputs. Only the
            // actual workshop's legally visible station is productive work.
            BuildingRecord building = settlement.building(citizen.workBuildingId());
            return building != null && building.operational()
                    && WarehouseInventoryService.canInteract(level, building, entity);
        }
        Entity targetEntity = citizen.targetItemEntityId() == null
                ? null : level.getEntity(citizen.targetItemEntityId());
        if (targetEntity != null) {
            return targetEntity.isAlive() && entity.distanceToSqr(targetEntity) <= 6.25D
                    && entity.hasLineOfSight(targetEntity);
        }
        BlockPos target = citizen.targetPosition();
        if (target == null || !level.hasChunkAt(target)) return false;
        Vec3 eye = entity.getEyePosition();
        boolean construction = citizen.workState() == CitizenWorkState.TO_BLUEPRINT
                || citizen.workState() == CitizenWorkState.TO_DEMOLITION
                || citizen.workState() == CitizenWorkState.TO_REPAIR;
        double reach = construction ? 4.0D : 2.5D;
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > reach * reach) return false;
        var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }
}

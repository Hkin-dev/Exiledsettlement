package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Loaded-only, bounded life simulation. All resident transfers use the existing physical station API. */
public final class CommunityLifeService {
    private CommunityLifeService() { }
    private static final Set<Item> FUELS = Set.of(Items.COAL, Items.CHARCOAL);
    private static final List<String> FOOD_GROUPS = List.of("grain", "vegetable", "fruit", "protein");
    private static final Map<CitizenRecord, Boolean> OVERRIDES = new WeakHashMap<>();
    private static final Map<CitizenRecord, Long> RATION_RETRY = new WeakHashMap<>();
    private static final Map<CitizenRecord, RationTrip> RATION_TRIPS = new WeakHashMap<>();
    private record RationTrip(UUID warehouse, long started) { }
    public static boolean handles(CitizenRecord citizen) { return OVERRIDES.getOrDefault(citizen, false); }

    /** One loaded interval; elapsed world days never grant catch-up healing. */
    public static void recoverLife(ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
                                   SettlementCitizenEntity entity) {
        if(com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen))return;
        if (!citizen.alive() || !entity.isAlive() || entity.isDefending()
                || citizen.thermal().body < ThermalSettings.retreat
                || CommunityContent.recoveryIntervalTicks <= 0
                || citizen.nutrition() < CommunityContent.recoveryNutritionMinimum
                || citizen.life().lastDamageGameTime >= 0
                   && level.getGameTime() - citizen.life().lastDamageGameTime < 200
                || entity.getHealth() >= entity.getMaxHealth()) return;
        float before = entity.getHealth();
        entity.heal(Math.min(1.0F, entity.getMaxHealth() - before));
        double fee = citizen.life().recoveryNutritionRemainder
                + (entity.getHealth() - before) * CommunityContent.recoveryNutritionCost;
        int paid = (int) Math.floor(fee + 0.000001D);
        citizen.life().recoveryNutritionRemainder = Math.max(0, fee - paid);
        citizen.adjustNeeds(-paid, 0, 0);
        entity.setVisibleNutrition(citizen.nutrition());
        data.touch();
    }
    public static void prepareWork(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        com.hkin.exiledsettlement.debug.ForceWorkDebugService.sync(level,settlement);
        migrateCalendar(level, data, settlement);
        for (var citizen : settlement.citizens()) {
            var entity = CitizenProjectionService.findProjection(level, citizen);
            if(entity!=null && citizen.workerAccess.personalSource
                    && (citizen.workerAccess.block!=null || citizen.workerAccess.advance!=null)){
                boolean allowed=citizen.canWork() && citizen.profession()==CitizenProfession.WORKER && !entity.isDefending()
                        && !entity.isSleeping() && !CitizenInteractionService.held(entity) && !settlement.community().evacuation
                        && (CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.SLEEP
                            || citizen.bedPosition()==null || citizen.nutrition()<=20);
                if(allowed && WorkerAccessService.tick(level,data,settlement,citizen,entity)){
                    OVERRIDES.put(citizen,true);continue;
                }
                citizen.workerAccess.clear();
            }
            OVERRIDES.put(citizen, entity != null && (CitizenInteractionService.held(entity) || personal(level, data, settlement, citizen, entity)));
        }
    }
    public static long day(ServerLevel level) { return Math.max(0, level.getDayTime() / 24000L); }
    public static int season(long day) { return (int) ((day / CommunityContent.seasonDays) % 4); }
    public static boolean winter(long day) { return season(day) == 3; }
    public static int season(SettlementRecord settlement, long day) {
        return (int) ((settlement.community().calendarDay(day) / settlement.community().seasonLength) % 4);
    }
    public static void migrateCalendar(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        // Detached simulation records retain their own fixture calendar. Registered communities share world authority.
        if (data.findByCore(settlement.corePosition()).orElse(null) == settlement) {
            if (WorldSeasonData.get(level).bind(settlement.community(), day(level))) data.touch();
        } else if (settlement.community().migrateCalendar(day(level))) data.touch();
    }
    public static boolean winter(SettlementRecord settlement, long day) { return season(settlement, day) == 3; }
    public static boolean severeCold(CommunityState state, long day) {
        return state.coldFrom >= 0 && day >= state.coldFrom && day < state.coldUntil;
    }
    public static void tickLevel(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        HomeHeatData.get(level).clean(data.settlements().stream().flatMap(s->s.buildings().stream())
                .filter(b->b.active() && b.type().isHouse()).map(BuildingRecord::id).collect(java.util.stream.Collectors.toSet()),level.getGameTime());
        for (var settlement : data.settlements()) {
            if (!settlement.active() || !level.hasChunkAt(settlement.corePosition())) continue;
            tick(level, data, settlement);
        }
    }
    public static void tick(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        var state = settlement.community(); long day = day(level), now = level.getGameTime();
        migrateCalendar(level, data, settlement);
        CitizenTemperatureService.tick(level,data,settlement);
        if (state.lastDay < 0) {
            state.lastDay = day; state.lastNeedTick = now;
            state.lastProcessedYear = state.calendarDay(day) / (state.seasonLength * 4L);
            snapshot(settlement.producedTotals(), state.yearProduced);
            snapshot(settlement.consumedTotals(), state.yearConsumed);
            state.event(day, "聚落生活开始；成年居民不退休、不因年龄降效、不自然老死。生育默认关闭，可在生活管理中开启。");
            data.touch(); return;
        }
        // Rewinds and offline/sleep jumps never simulate unobserved years or silently kill residents.
        if (day != state.lastDay) {
            if (day > state.lastDay && state.calendarDay(day) / state.seasonLength != state.calendarDay(state.lastDay) / state.seasonLength) {
                String notice = SeasonCalendar.date(state.calendarTime(level.getDayTime()), state.seasonLength) + "：" + SeasonCalendar.hint(season(settlement, day)) + "。";
                state.event(day, notice); notifyMembers(level, settlement, notice);
            }
            int days = (int) Math.min(1, Math.max(0, day - state.lastDay));
            for (var citizen : settlement.citizens()) if (citizen.alive()) {
                boolean adult = citizen.canWork();
                citizen.life().ageDays = Math.min(Long.MAX_VALUE - days, citizen.life().ageDays) + days;
                if (!adult && citizen.canWork()) {
                    state.event(day, citizen.name() + "成年，可以承担工作。");
                    milestone(state, day, "grown", "第一名儿童成年");
                }
            }
            state.lastDay = day;
            daily(level, settlement, day);
            families(level, settlement, day);
            ProfessionAssignmentService.refresh(data, settlement);
            data.touch();
        }
        if (now < state.lastNeedTick) state.lastNeedTick = now;
        if (now - state.lastNeedTick >= CommunityContent.needIntervalTicks) {
            state.lastNeedTick = now;
            for (var citizen : settlement.citizens()) {
                if (!citizen.alive()) continue;
                if(com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen))continue;
                var entity = CitizenProjectionService.findProjection(level, citizen);
                if (entity == null) continue; // No fabricated consumption/path completion for unloaded entities.
                citizen.adjustNeeds(-CommunityContent.nutritionLoss, 0, 0);
                if (citizen.nutrition() == 0) {
                    citizen.adjustNeeds(0, 0, -2);
                    entity.hurt(level.damageSources().starve(), 1.0F);
                } else if (citizen.life().sickUntil <= day) {
                    int coverage = citizen.life().dietCoverage(day, CommunityContent.dietWindowDays);
                    int targetHealth = 70 + Math.min(3, coverage) * 8 + (coverage == 4 ? 6 : 0);
                    citizen.adjustNeeds(0, 0, citizen.health() < targetHealth ? 1 : citizen.health() > targetHealth ? -1 : 0);
                }
                if (entity.getLastHurtByMob() != null && entity.getLastHurtByMob().isAlive()
                        && entity.getLastHurtByMobTimestamp() != citizen.life().lastThreat) {
                    citizen.life().lastThreat = entity.getLastHurtByMobTimestamp();
                    state.event(day, citizen.name() + "遭到袭击；可开启集合避险，或标记危险区域。");
                    notifyMembers(level, settlement, citizen.name() + "遭到袭击，请检查居民安全。");
                }
            }
            if (settlement.citizens().stream().anyMatch(c -> c.alive() && (c.nutrition() < 30 || c.warmth() < 30))
                    && state.lastWarningDay != day) {
                state.lastWarningDay = day;
                state.event(day, "食物或保暖不足；查看个人阻塞及公共库存，优先补给。");
                notifyMembers(level, settlement, "食物或保暖不足，请补充真实公共库存。聚落生活页可开启紧急保供。");
            }
            data.touch();
        }
    }
    private static void daily(ServerLevel level, SettlementRecord settlement, long day) {
        var state = settlement.community(); int yearLength = state.seasonLength * 4;
        long calendarDay = state.calendarDay(day), year = calendarDay / yearLength; int within = (int) (calendarDay % yearLength);
        if (state.lastProcessedYear != year) {
            int produced = delta(settlement.producedTotals(), state.yearProduced);
            int consumed = delta(settlement.consumedTotals(), state.yearConsumed);
            state.reports.addLast("第" + year + "年已记录物品净增 " + produced + " / 净减 " + consumed
                    + "；熟食加工 " + state.mealsProduced + "，进食 " + state.mealsEaten
                    + "，保暖燃料 " + state.coldFuelBurned + "。首份报告只涵盖启用后的记录。");
            while (state.reports.size() > 8) state.reports.removeFirst();
            snapshot(settlement.producedTotals(), state.yearProduced);
            snapshot(settlement.consumedTotals(), state.yearConsumed);
            state.mealsProduced = state.mealsEaten = state.coldFuelBurned = 0;
            state.lastProcessedYear = year;
        }
        if (CommunityContent.crisesEnabled && within == state.seasonLength * 2 - 1) {
            boolean frost = Math.floorMod(settlement.id().hashCode() + (int) year, 3) == 0;
            state.frostDay = frost ? day + 2 : -1; state.frostApplied = false;
            state.coldFrom = year * yearLength + state.seasonLength * 3L - state.calendarOffsetDays;
            state.coldUntil = (year + 1) * yearLength - state.calendarOffsetDays;
            String notice = "冬季将增加保暖消耗，请提前备煤炭或木炭。" + (frost ? "两日后有早霜，提前收获可避免损失。" : "");
            state.event(day, notice); notifyMembers(level, settlement, notice);
        }
        if (within == state.seasonLength * 3) { state.winterStarted = true; state.winterLoss = false; }
        if (CommunityContent.crisesEnabled && state.frostDay == day) {
            state.event(day, "早霜到来；今日未提前收获的成熟作物产出减少25%，仍可正常收获和恢复生产。");
        }
        if (!CommunityContent.crisesEnabled) { state.frostDay = -1; state.coldUntil = -1; state.coldFrom = -1; }
        if (state.lastOfferYear != year && within == state.seasonLength) {
            state.lastOfferYear = year; state.offerExpires = day + 3;
            state.migrantAdults = 2; state.migrantChildren = 1;
            state.event(day, "一个家庭申请加入：2名成年居民和1名儿童；申请保留3日，接纳需住房、人口额度和食物。");
            notifyMembers(level, settlement, "流民家庭申请加入。可在聚落生活页查看和决定。");
        }
        if (state.offerExpires >= 0 && day >= state.offerExpires) {
            state.migrantAdults = state.migrantChildren = 0; state.offerExpires = -1;
            state.event(day, "未接纳的流民家庭已经离开。");
        }
        boolean recovered = false;
        for (var c : settlement.citizens()) if (c.alive() && c.life().sickUntil >= 0 && day >= c.life().sickUntil) {
            c.life().sickUntil = -1; recovered = true;
            state.event(day, c.name() + "已从疾病中恢复。");
        }
        if (recovered) milestone(state, day, "recovery", "居民康复");
        if (season(settlement, day) == 0 && within == 0) {
            if (state.winterStarted && !state.winterLoss && settlement.aliveCitizenCount() > 0
                    && settlement.citizens().stream().allMatch(c -> !c.alive() || (c.nutrition() > 20 && c.warmth() > 20)))
                milestone(state, day, "winter", "安全度过一个冬季");
            state.winterStarted = false;
        }
        if (state.mealsProduced >= 16) milestone(state, day, "food", "居民完成16份熟食加工");
    }
    private static int delta(Map<ResourceLocation, Integer> current, Map<String, Integer> previous) {
        long total = 0;
        for (var e : current.entrySet()) total += Math.max(0, e.getValue() - previous.getOrDefault(e.getKey().toString(), 0));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
    private static void snapshot(Map<ResourceLocation, Integer> current, Map<String, Integer> target) {
        target.clear(); current.forEach((id, count) -> target.put(id.toString(), count));
    }
    private static void families(ServerLevel level, SettlementRecord settlement, long day) {
        var state = settlement.community();
        if (!state.birthsEnabled) return;
        List<CitizenRecord> available = settlement.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c -> c.homeBuildingId() != null && c.life().partner == null).toList();
        // Only pair at most one family per day; do not scan all resident pairs every tick.
        for (var a : available) {
            var b = available.stream().filter(c -> c != a && a.homeBuildingId().equals(c.homeBuildingId())
                    && !related(a, c)).findFirst().orElse(null);
            if (b == null) continue;
            a.life().partner = b.id(); b.life().partner = a.id();
            a.life().lastBirthDay = b.life().lastBirthDay = day;
            state.event(day, a.name() + "与" + b.name() + "组成家庭。"); break;
        }
        if (settlement.aliveCitizenCount() >= settlement.level().populationCap()
                || settlement.housingCapacity() <= settlement.aliveCitizenCount()) return;
        int food = SettlementInventoryService.countCategory(level, settlement, WarehouseCategory.FOOD);
        if (food < state.foodReserve + 12) return;
        for (var a : List.copyOf(settlement.citizens())) {
            var b = settlement.citizen(a.life().partner);
            if (!a.canWork() || b == null || !b.canWork() || !a.id().equals(b.life().partner)
                    || a.id().compareTo(b.id()) >= 0 || a.homeBuildingId() == null
                    || !a.homeBuildingId().equals(b.homeBuildingId())
                    || a.nutrition() < 60 || b.nutrition() < 60 || a.warmth() < 40 || b.warmth() < 40
                    || day - a.life().lastBirthDay < state.birthInterval) continue;
            var home = settlement.building(a.homeBuildingId());
            if (home == null || !home.operational() || settlement.citizens().stream().filter(CitizenRecord::alive)
                    .filter(c -> home.id().equals(c.homeBuildingId())).count() >= home.capacity()) continue;
            var child = settlement.addChild(a, b);
            child.assignHome(home.id());
            a.life().lastBirthDay = b.life().lastBirthDay = day;
            settlement.refreshHousingAssignments();
            state.event(day, "家庭迎来儿童" + child.name() + "；儿童消耗物资，成长后才能工作。");
            milestone(state, day, "birth", "第一个新生居民"); break;
        }
    }
    private static boolean related(CitizenRecord a, CitizenRecord b) {
        return a.id().equals(b.life().parentA) || a.id().equals(b.life().parentB)
                || b.id().equals(a.life().parentA) || b.id().equals(a.life().parentB)
                || (a.life().parentA != null && (a.life().parentA.equals(b.life().parentA)
                    || a.life().parentA.equals(b.life().parentB)))
                || (a.life().parentB != null && (a.life().parentB.equals(b.life().parentA)
                    || a.life().parentB.equals(b.life().parentB)));
    }
    public static int frostLoss(SettlementRecord settlement, long day, int count) {
        return CommunityContent.crisesEnabled && settlement.community().frostDay == day ? count / 4 : 0;
    }
    public static int frostLoss(SettlementRecord settlement, long day, BlockPos position, int count) {
        if (!CommunityContent.crisesEnabled || settlement.community().frostDay != day) return 0;
        int loss = count / 4;
        long seed = position.getX() * 73428767L + position.getZ() * 912931L + position.getY() * 31L
                + settlement.id().getLeastSignificantBits() + day;
        if (Math.floorMod(seed, 4) < count % 4) loss++;
        return Math.min(count, loss);
    }
    public static void milestone(CommunityState state, long day, String id, String label) {
        if (state.milestones.add(id)) state.event(day, "达成目标：" + label);
    }
    public static void notifyMembers(ServerLevel level, SettlementRecord settlement, String message) {
        level.players().stream().filter(p -> settlement.isMember(p.getUUID()))
                .forEach(p -> p.displayClientMessage(net.minecraft.network.chat.Component.literal(message), false));
    }

    /** Called before normal work and idle movement. Never replaces an unsafe scaffold descent. */
    public static boolean personal(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                   CitizenRecord citizen, SettlementCitizenEntity entity) {
        if(citizen.alive() && !entity.hasActiveConstructionMovement() && com.hkin.exiledsettlement.citizen.ResidentEgressService.leaveForBed(level,settlement,citizen,entity))return true;
        if (!citizen.alive() || entity.hasActiveConstructionMovement() || !entity.onGround()) return false;
        if (entity.isDefending() && !settlement.community().evacuation) return false;
        var state = settlement.community(); var life = citizen.life(); long day = day(level);
        var workplace = settlement.building(citizen.workBuildingId());
        if (state.evacuation || state.danger(citizen.lastPosition())
                || (workplace != null && state.danger(workplace.position()))
                || (citizen.targetPosition() != null && state.danger(citizen.targetPosition()))) {
            if (entity.isSleeping()) entity.stopSleeping();
            citizen.clearWork();
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            BlockPos rally = state.rally == null ? settlement.corePosition().offset(2, 0, 0) : state.rally;
            if (state.danger(rally) || !entity.canStandAtPosition(rally)) {
                rally = settlement.buildings().stream().filter(b -> b.active() && b.operational() && b.type().isHouse()
                        && !state.danger(b.position())).flatMap(b -> b.servicePositions().stream())
                        .filter(p -> !state.danger(p) && settlement.contains(p) && entity.canStandAtPosition(p)).findFirst().orElse(null);
                if (rally == null) {
                    for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos candidate = settlement.corePosition().relative(direction, 16);
                        if (settlement.contains(candidate) && !state.danger(candidate) && entity.canStandAtPosition(candidate)) { rally = candidate; break; }
                    }
                }
                if (rally == null) { blocked(citizen, entity, "没有安全集合点，请设置可达集合点或解除相应危险标记"); return true; }
            }
            move(citizen, entity, rally, CitizenActivity.EVACUATING, "集合避险：生产暂停，携带物保留");
            data.touch(); return true;
        }
        boolean forced=com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen);
        if(!forced){
        if(com.hkin.exiledsettlement.citizen.ResidentEgressService.active(settlement,citizen))return false;
        // Bedtime outranks optional restocking, tailoring and routine warmth trips.
        // Critical hunger and evacuation still take priority, and the resident must walk home.
        if(CitizenWorkService.schedulePhase(level.getDayTime(),citizen)==CitizenWorkService.SchedulePhase.SLEEP
                && citizen.nutrition()>20 && citizen.bedPosition()!=null) {
            if(com.hkin.exiledsettlement.citizen.ResidentEgressService.resume(settlement,citizen))return false;
            citizen.clearWork();WarehouseInventoryService.releaseInteractionPosition(level,entity);
            CitizenWorkService.routeResidentToPersonalBed(level,data,settlement,citizen,entity);
            com.hkin.exiledsettlement.citizen.ResidentEgressService.plan(level,settlement,citizen,entity);
            data.touch();return true;
        }
        if (citizen.nutrition() <= 70) {
            if (entity.isSleeping()) entity.stopSleeping();
            citizen.clearWork();
            return eat(level, data, settlement, citizen, entity, day);
        }
        if (citizen.thermal().warming) {
            if (CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.SLEEP
                    && CitizenClothingService.canRecoverWhileSleeping(level, settlement, citizen, entity)) {
                // A warm bedroom keeps recovering temperature during real sleep.
                // Standing beside the bed must not monopolize the entire night.
                citizen.clearWork();
                WarehouseInventoryService.releaseInteractionPosition(level, entity);
                return false;
            }
            if (entity.isSleeping()) entity.stopSleeping();
            citizen.clearWork();
            return CitizenClothingService.warm(level,data,settlement,citizen,entity);
        }
        if (CitizenClothingService.personal(level,data,settlement,citizen,entity)) return true;
        if (life.sickUntil > day) {
            citizen.clearWork();
            BlockPos rest = citizen.bedPosition() == null ? settlement.corePosition().offset(2, 0, 0) : citizen.bedPosition();
            move(citizen, entity, rest, CitizenActivity.RECOVERING, "生病休养；保持进食和保暖，恢复后复工");
            data.touch(); return true;
        }
        if (citizen.workState() == CitizenWorkState.IDLE && life.task.isEmpty()
                && replenishRations(level, data, settlement, citizen, entity)) return true;
        } else if(life.task.startsWith("tailor:"))return CitizenClothingService.personal(level,data,settlement,citizen,entity);
        if (!citizen.canWork()) {
            citizen.clearWork();
            citizen.setActivity(CitizenActivity.GROWING, "儿童尚未成年"); entity.setActivity(CitizenActivity.GROWING);
            data.touch(); return false; // Idle/home movement remains available.
        }
        if (pausesConstruction(settlement, citizen)) {
            citizen.clearWork();
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            entity.stopSettlementNavigation();
            data.touch();
        }
        if (life.task.startsWith("kiln:")) {
            if (CitizenWorkService.schedulePhase(level.getDayTime(), citizen) != CitizenWorkService.SchedulePhase.WORK) return false;
            return KilnProductionService.work(level, data, settlement, citizen, entity);
        }
        if (!life.task.isEmpty() && life.task.startsWith("cook:")) {
            if (CitizenWorkService.schedulePhase(level.getDayTime(), citizen) != CitizenWorkService.SchedulePhase.WORK) return false;
            return CommunityCookingService.work(level, data, settlement, citizen, entity);
        }
        if (WarehouseStewardService.handles(citizen)) {
            if (CitizenWorkService.schedulePhase(level.getDayTime(), citizen) != CitizenWorkService.SchedulePhase.WORK) return false;
            return WarehouseStewardService.work(level,data,settlement,citizen,entity);
        }
        if ((citizen.workState()==CitizenWorkState.IDLE || citizen.targetBlueprintId()!=null) && citizen.life().task.isEmpty()
                && CitizenWorkService.schedulePhase(level.getDayTime(),citizen)==CitizenWorkService.SchedulePhase.WORK
                && WarehouseStewardService.assign(level,settlement,citizen)) {
            citizen.clearWork();WarehouseInventoryService.releaseInteractionPosition(level,entity);
            data.touch();return WarehouseStewardService.work(level,data,settlement,citizen,entity);
        }
        if (citizen.workState() == CitizenWorkState.IDLE && citizen.profession() == CitizenProfession.KILN_WORKER
                && CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.WORK) {
            return KilnProductionService.shift(level, data, settlement, citizen, entity);
        }
        if (citizen.workState() == CitizenWorkState.IDLE && citizen.profession() == CitizenProfession.CHEF
                && CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.WORK) {
            return CommunityCookingService.shift(level, data, settlement, citizen, entity);
        }
        return false;
    }
    public static boolean pausesConstruction(SettlementRecord settlement, CitizenRecord citizen) {
        if(com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen))return false;
        if (!settlement.community().policy.equals("emergency")) return false;
        if (citizen.targetBlueprintId() != null) return true;
        return switch (citizen.workState()) {
            case TO_CONSTRUCTION_SUPPLY, TO_FOOTING_SUPPLY, TO_BLUEPRINT,
                    TO_REPAIR_SUPPLY, TO_REPAIR, TO_DEMOLITION -> true;
            default -> false;
        };
    }
    private static boolean eat(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                               CitizenRecord citizen, SettlementCitizenEntity entity, long day) {
        var ownFood = citizen.extractInventoryItem(CommunityLifeService::food, 1);
        if (!ownFood.isEmpty()) {
            consumeMeal(level, data, settlement, citizen, entity, ownFood, day);
            return true;
        }
        BuildingRecord supply = nearestFood(level, settlement, citizen);
        if(settlement.creativeCoreInstalled()){
            if(com.hkin.exiledsettlement.citizen.CreativeResidentSupply.atCore(level,settlement,citizen,entity,CitizenActivity.FETCHING_MEAL)){
                if(citizen.inventoryCapacityFor(new ItemStack(Items.BREAD))<1){
                    consumeMeal(level,data,settlement,citizen,entity,new ItemStack(Items.BREAD),day);
                    com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"creative_core_meal",settlement.corePosition(),Items.BREAD.toString(),1);
                }else com.hkin.exiledsettlement.citizen.CreativeResidentSupply.give(level,settlement,citizen,entity,Items.BREAD,Math.max(1,CommunityContent.carriedFoodTarget));
            }
            data.touch();return true;
        }
        if (supply == null) {
            if(WarehouseStewardService.personalBread(level,data,settlement,citizen,entity))return true;
            blocked(citizen, entity, "没有可用食物或3份小麦：请补充公共库存，仓管可拆取干草块"); return true;
        }
        Item selected = CommunityStock.items(level, settlement, supply).keySet().stream()
                .filter(item -> food(new ItemStack(item)))
                .max(Comparator.comparingInt(item -> foodPriority(citizen, new ItemStack(item), day))).orElse(null);
        if (selected != null && atStorage(level, citizen, entity, supply, selected, CitizenActivity.FETCHING_MEAL)) {
            int capacity = citizen.inventoryCapacityFor(new ItemStack(selected));
            int amount = capacity == 0 ? 1 : Math.min(capacity, Math.max(1, CommunityContent.carriedFoodTarget));
            ItemStack taken = WarehouseInventoryService.extractItemForResident(level, supply, entity, selected, amount,
                    stack -> food(stack) ? Math.max(1, citizen.inventoryCapacityFor(stack)) : 0);
            if (!taken.isEmpty()) {
                if (citizen.inventoryCapacityFor(taken) < 1) {
                    // Eating at the physical station needs no backpack slot; carried work materials stay intact.
                    consumeMeal(level, data, settlement, citizen, entity, taken, day);
                } else citizen.insertIntoInventory(taken);
            }
        }
        data.touch(); return true;
    }
    static void consumeMeal(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                    CitizenRecord citizen, SettlementCitizenEntity entity, ItemStack meal, long day) {
        var nutrition = meal.get(DataComponents.FOOD);
        citizen.adjustNeeds(Math.max(15, nutrition.nutrition() * 8), 0, 0);
        for (int i = 0; i < FOOD_GROUPS.size(); i++) if (meal.is(TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("exiledsettlement", "food/" + FOOD_GROUPS.get(i))))) citizen.life().dietDays[i] = day;
        settlement.community().mealsEaten++;
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"resident_eat",entity.blockPosition(),BuiltInRegistries.ITEM.getKey(meal.getItem()).toString(),1);
        citizen.setActivity(CitizenActivity.EATING, ""); entity.setActivity(CitizenActivity.EATING);
        WarehouseInventoryService.releaseInteractionPosition(level, entity);
        data.touch();
    }
    public static boolean food(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD)
                && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.POISONOUS_POTATO)
                && !stack.is(Items.SPIDER_EYE) && !stack.is(Items.PUFFERFISH);
    }
    public static int carriedFood(CitizenRecord citizen) {
        return citizen.inventory().stream().filter(CommunityLifeService::food).mapToInt(ItemStack::getCount).sum();
    }
    /** One shared allowance across all food stacks, including edible farm produce. */
    public static int reservedFood(CitizenRecord citizen, int slot) {
        if (!food(citizen.inventoryItem(slot))) return 0;
        int remaining = CommunityContent.carriedFoodTarget;
        for (int i = 0; i < slot && remaining > 0; i++) {
            ItemStack stack = citizen.inventoryItem(i);
            if (food(stack)) remaining -= stack.getCount();
        }
        return Math.min(citizen.inventoryItem(slot).getCount(), Math.max(0, remaining));
    }
    /** Only use an already reached stock station; never grant food or move the resident here. */
    public static void topUpRations(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
                                    SettlementCitizenEntity entity, BuildingRecord source) {
        int missing = CommunityContent.carriedFoodTarget - carriedFood(citizen);
        if(settlement.creativeCoreInstalled() && missing>0){com.hkin.exiledsettlement.citizen.CreativeResidentSupply.give(level,settlement,citizen,entity,Items.BREAD,missing);return;}
        if (missing <= 0 || source == null || !source.active() || !source.operational()
                || settlement.community().danger(source.position())
                || !WarehouseInventoryService.canInteract(level, source, entity)) return;
        Item selected = rationItem(level, settlement, source, citizen);
        if (selected == null) return;
        int count = Math.min(missing, citizen.inventoryCapacityFor(new ItemStack(selected)));
        if (count > 0) citizen.insertIntoInventory(
                WarehouseInventoryService.extractItemForResident(level, source, entity, selected, count,
                        stack -> food(stack) ? citizen.inventoryCapacityFor(stack) : 0));
        CitizenEquipmentService.topUpPersonalTools(level, settlement, citizen, entity, source);
    }
    private static Item rationItem(ServerLevel level, SettlementRecord settlement, BuildingRecord source, CitizenRecord citizen) {
        return CommunityStock.items(level, settlement, source).keySet().stream()
                .filter(item -> food(new ItemStack(item)) && citizen.inventoryCapacityFor(new ItemStack(item)) > 0)
                .max(Comparator.comparingInt(item -> foodPriority(citizen, new ItemStack(item), day(level)))).orElse(null);
    }
    private static boolean replenishRations(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                            CitizenRecord citizen, SettlementCitizenEntity entity) {
        long now = level.getGameTime();
        RationTrip trip = RATION_TRIPS.get(citizen);
        if (CommunityContent.carriedFoodTarget <= 0 || carriedFood(citizen) >= CommunityContent.carriedFoodTarget
                || CitizenWorkService.schedulePhase(level.getDayTime(), citizen) != CitizenWorkService.SchedulePhase.REST) {
            if (RATION_TRIPS.remove(citizen) != null) WarehouseInventoryService.releaseInteractionPosition(level, entity);
            return false;
        }
        if(settlement.creativeCoreInstalled()){
            if(citizen.inventoryCapacityFor(new ItemStack(Items.BREAD))<=0)return false;
            if(com.hkin.exiledsettlement.citizen.CreativeResidentSupply.atCore(level,settlement,citizen,entity,CitizenActivity.FETCHING_MEAL))
                com.hkin.exiledsettlement.citizen.CreativeResidentSupply.give(level,settlement,citizen,entity,Items.BREAD,CommunityContent.carriedFoodTarget-carriedFood(citizen));
            data.touch();return true;
        }
        if (trip == null) {
            if (RATION_RETRY.getOrDefault(citizen, -1L) > now) return false;
            RATION_RETRY.put(citizen, now + 1200L);
            BuildingRecord source = nearestFood(level, settlement, citizen);
            if (source == null || rationItem(level, settlement, source, citizen) == null) return false;
            trip = new RationTrip(source.id(), now);
            RATION_TRIPS.put(citizen, trip);
        }
        BuildingRecord source = settlement.building(trip.warehouse());
        Item item = source == null ? null : rationItem(level, settlement, source, citizen);
        if (now - trip.started() > 400L || source == null || !source.active() || !source.operational()
                || settlement.community().danger(source.position()) || item == null) {
            RATION_TRIPS.remove(citizen);
            entity.stopSettlementNavigation();
            return false;
        }
        if (atStorage(level, citizen, entity, source, item, CitizenActivity.FETCHING_MEAL)) {
            topUpRations(level, settlement, citizen, entity, source);
            RATION_TRIPS.remove(citizen);
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
        }
        data.touch();
        return true;
    }
    private static int foodPriority(CitizenRecord c, ItemStack food, long day) {
        int score = food.get(DataComponents.FOOD).nutrition();
        for (int i = 0; i < FOOD_GROUPS.size(); i++) if (day - c.life().dietDays[i] >= CommunityContent.dietWindowDays
                && food.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("exiledsettlement", "food/" + FOOD_GROUPS.get(i))))) score += 20;
        return score;
    }
    private static BuildingRecord nearestFood(ServerLevel level, SettlementRecord settlement, CitizenRecord c) {
        var home=settlement.building(c.homeBuildingId());
        if(home!=null && home.active() && home.operational() && !settlement.community().danger(home.position())
                && CommunityStock.items(level,settlement,home).keySet().stream().anyMatch(i->food(new ItemStack(i))))return home;
        return SettlementInventoryService.warehouses(settlement).stream().filter(b -> !settlement.community().danger(b.position()))
                .filter(b -> CommunityStock.items(level, settlement, b).keySet().stream().anyMatch(i -> food(new ItemStack(i))))
                .min(Comparator.comparingDouble(b -> b.position().distSqr(c.lastPosition()))).orElse(null);
    }
    public static boolean atStorage(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity entity,
                                    BuildingRecord source, Item item, CitizenActivity activity) {
        BlockPos station = WarehouseInventoryService.approachPosition(level, source, entity);
        if (station == null) station = WarehouseInventoryService.reserveExtractionPosition(level, source, entity, item);
        if (station == null) { blocked(citizen, entity, "库存装卸点不可达或被占用"); return false; }
        move(citizen, entity, station, activity, "");
        return WarehouseInventoryService.canInteract(level, source, entity);
    }
    public static void move(CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos target,
                             CitizenActivity activity, String reason) {
        String failure = entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > 0.64 ? entity.navigateTo(target, 0.65) : "";
        // Stopping the entire settlement navigator releases the storage reservation.
        // Keep that claim until the physical transfer finishes.
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 0.64) entity.getNavigation().stop();
        citizen.setActivity(failure.isEmpty() ? activity : CitizenActivity.BLOCKED, failure.isEmpty() ? reason : failure);
        entity.setActivity(citizen.activity());
    }
    public static void blocked(CitizenRecord citizen, SettlementCitizenEntity entity, String reason) {
        entity.stopSettlementNavigation();
        citizen.setActivity(CitizenActivity.BLOCKED, reason); entity.setActivity(CitizenActivity.BLOCKED);
    }
}

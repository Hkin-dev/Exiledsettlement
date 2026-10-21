package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Server-owned companion records. Acquisition and feeding use the existing physical animal. */
public final class PetCareService {
    public static final String PET_ID = "exiledsettlement.petId";
    public static final String SETTLEMENT_ID = "exiledsettlement.petSettlement";
    private static final String ATTEMPT_DAY = "exiledsettlement.petAttemptDay";
    private static final String ATTEMPTS = "exiledsettlement.petAttempts";
    private static final Map<ServerLevel, Map<UUID, Long>> SEARCH = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Integer>> SEARCH_CURSOR = new WeakHashMap<>();
    private record CareNavigation(UUID pet, CitizenRecord citizen, BlockPos target, CitizenActivity activity, String purpose) {}
    private static final Map<SettlementCitizenEntity, CareNavigation> CARE_NAVIGATION = new WeakHashMap<>();
    private record Interaction(UUID resident, UUID animal) {}
    private static final ThreadLocal<Interaction> INTERACTION = new ThreadLocal<>();
    private PetCareService() {}
    record Binding(SettlementRecord settlement, PetState.Pet pet) {}

    static Binding binding(Animal animal) {
        if (!(animal.level() instanceof ServerLevel level)) return null;
        var tag = animal.getPersistentData();
        if (!tag.hasUUID(PET_ID) || !tag.hasUUID(SETTLEMENT_ID)) return null;
        SettlementRecord settlement = SettlementSavedData.get(level).findById(tag.getUUID(SETTLEMENT_ID)).orElse(null);
        PetState.Pet pet = settlement == null ? null : settlement.pets().entries.get(tag.getUUID(PET_ID));
        return pet != null && pet.entityId.equals(animal.getUUID()) ? new Binding(settlement, pet) : null;
    }
    public static boolean isProtected(Animal animal) {
        Binding binding = binding(animal);
        return binding != null && binding.pet().occupiesSlot();
    }
    public static boolean blocksVanillaInteraction(Animal animal) {
        Binding binding = binding(animal);
        return binding != null && binding.pet().status == PetState.Status.ACTIVE;
    }

    /** Allows our exact protection probe through our own vanilla-pet UI blocker, not other protection mods. */
    public static boolean residentInteraction(net.minecraft.world.entity.player.Player player, Animal animal) {
        Interaction active = INTERACTION.get();
        return active != null && active.resident().equals(player.getUUID()) && active.animal().equals(animal.getUUID());
    }

    public static boolean work(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                               CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (!CompanionMetrics.ENABLED) return workMeasured(level, data, settlement, citizen, entity);
        long started = CompanionMetrics.start();
        try { return workMeasured(level, data, settlement, citizen, entity); }
        finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.PET_WORK, started); }
    }

    private static boolean workMeasured(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                        CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (!settlement.active() || !citizen.canWork() || !entity.isAlive() || entity.isSleeping()
                || entity.isDefending() || entity.hasActiveConstructionMovement() || !entity.onGround()
                || CitizenInteractionService.held(entity)
                || citizen.workState() != CitizenWorkState.IDLE || !citizen.life().task.isEmpty()
                || settlement.community().evacuation || settlement.community().danger(entity.blockPosition())
                || CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.SLEEP)
            return false;
        long now = level.getGameTime(), day = day(level);
        PetState.Pet pet = settlement.pets().entries.values().stream()
                .filter(candidate -> candidate.occupiesSlot() && candidate.retryAt <= now)
                .filter(candidate -> candidate.worker == null || candidate.workerUntil <= now || candidate.worker.equals(citizen.id()))
                .filter(candidate -> candidate.status == PetState.Status.ADOPTING
                        ? settlement.pets().enabled && Objects.equals(candidate.caretaker, citizen.id()) && attemptAvailable(candidate, day)
                        : candidate.lastFedDay < day && mayCare(settlement, citizen, candidate))
                .filter(candidate -> level.getEntity(candidate.entityId) instanceof Animal animal && animal.isAlive())
                .sorted(Comparator.comparingInt(candidate -> candidate.status == PetState.Status.ACTIVE ? 0 : 1))
                .findFirst().orElse(null);
        if (pet == null) pet = reserve(level, data, settlement, citizen, entity);
        if (pet == null) return false;
        Entity found = level.getEntity(pet.entityId);
        if (!(found instanceof Animal animal) || !animal.isAlive()) return false;
        if (pet.status == PetState.Status.ADOPTING && !eligible(level, settlement, animal, pet, true)) {
            stopCareNavigation(level, entity);
            cancel(data, animal, pet, "动物已被玩家认领、命名、牵绳、圈养或另有归属");
            return false;
        }
        if (animal.isLeashed() || animal.isPassenger() || animal.isOnFire() || animal.isInWaterOrBubble()
                || animal.getTarget() != null || animal.hurtTime > 0) {
            pet.blocker = "宠物正被牵引、遇险或战斗，等待安全照料";
            pet.retryAt = now + 100;
            pet.worker = null;
            stopCareNavigation(level, entity);
            data.touch();
            return false;
        }
        pet.worker = citizen.id();
        pet.workerUntil = now + 80;
        if (!progress(level, citizen, entity, pet)) {
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            data.touch();
            return false;
        }
        if (!PetLogistics.availableInBag(citizen, pet)) {
            if (now < pet.nextMove) return true;
            pet.nextMove = now + 40;
            boolean fetching = PetLogistics.fetch(level, settlement, citizen, entity, pet);
            if (!fetching) {
                pet.worker = null;
                pet.retryAt = now + 200;
                stopCareNavigation(level, entity);
            }
            data.touch();
            return fetching;
        }
        pet.activity = pet.status == PetState.Status.ADOPTING ? "照料者接近领养" : "等待照料者喂食";
        if (entity.distanceToSqr(animal) > 6.25 || !entity.hasLineOfSight(animal)) {
            if (now >= pet.nextMove) {
                pet.nextMove = now + 40;
                pet.workerTarget = animal.blockPosition();
                moveCaregiver(level, citizen, entity, pet, pet.workerTarget, CitizenActivity.CAPTURING, pet.activity);
            }
            data.touch();
            return true;
        }
        if (pet.status == PetState.Status.ADOPTING && (!attemptAvailable(pet, day)
                || !eligible(level, settlement, animal, pet, true))) {
            stopCareNavigation(level, entity);
            if (!eligible(level, settlement, animal, pet, true)) cancel(data, animal, pet, "领养资格发生变化");
            else pet.worker = null;
            return false;
        }
        if (pet.status == PetState.Status.ACTIVE && pet.lastFedDay >= day) {
            pet.worker = null;
            stopCareNavigation(level, entity);
            return false;
        }
        ItemStack offered = PetLogistics.preview(citizen, pet);
        if (offered.isEmpty()) { pet.nextMove = 0; return true; }
        boolean allowed;
        INTERACTION.set(new Interaction(citizen.id(), animal.getUUID()));
        try { allowed = ResidentAnimalInteraction.allowed(level, settlement, citizen, entity, animal, offered); }
        finally { INTERACTION.remove(); }
        if (!allowed) {
            pet.blocker = "宠物互动被保护规则拒绝";
            pet.retryAt = now + 200;
            pet.worker = null;
            stopCareNavigation(level, entity);
            data.touch();
            return false;
        }
        if (pet.status == PetState.Status.ADOPTING && !eligible(level, settlement, animal, pet, true)
                || animal instanceof TamableAnimal tame && tame.getOwnerUUID() != null) {
            stopCareNavigation(level, entity);
            cancel(data, animal, pet, "保护事件后领养或照料资格改变");
            return false;
        }
        if (!animal.isAlive() || animal.isLeashed() || animal.isPassenger() || !settlement.contains(animal.blockPosition())) {
            pet.worker = null;
            stopCareNavigation(level, entity);
            data.touch();
            return false;
        }
        ItemStack consumed = PetLogistics.consume(citizen, pet);
        if (consumed.isEmpty()) {
            pet.blocker = "背包食物已变化，保留居民口粮";
            pet.nextMove = 0;
            data.touch();
            return true;
        }
        stopCareNavigation(level, entity);
        entity.swing(InteractionHand.MAIN_HAND);
        pet.worker = null;
        pet.progressAt = 0;
        pet.workerPosition = null;
        pet.blocker = "";
        if (pet.status == PetState.Status.ADOPTING) {
            if (day > pet.attemptDay) { pet.attemptDay = day; pet.attempts = 0; }
            pet.attempts++;
            pet.consumedLures++;
            animal.getPersistentData().putLong(ATTEMPT_DAY, pet.attemptDay);
            animal.getPersistentData().putInt(ATTEMPTS, pet.attempts);
            if (animal.getRandom().nextInt(3) == 0) {
                PetAi.release(animal);
                pet.status = PetState.Status.ACTIVE;
                pet.activity = "熟悉新家";
                if (pet.cat) pet.lastFedDay = Math.max(pet.lastFedDay, day);
                TamableAnimal tame = (TamableAnimal) animal;
                tame.setTame(true, false);
                tame.setOrderedToSit(false);
                animal.setPersistenceRequired();
                animal.setTarget(null);
                animal.resetLove();
                PetAi.attach(level, settlement, pet, animal);
                level.broadcastEntityEvent(animal, (byte) 7);
                settlement.community().event(day, citizen.name() + "领养了" + (pet.cat ? "一只猫" : "一只狗"));
            } else {
                pet.activity = "亲近尝试未成功";
                if (pet.attempts >= 3) pet.blocker = "今日已尝试3次，等待下一日";
                pet.retryAt = now + 100;
                level.broadcastEntityEvent(animal, (byte) 6);
            }
        } else {
            pet.lastFedDay = Math.max(day, pet.lastFedDay);
            pet.consumedMeals++;
            pet.activity = "已喂食";
            animal.heal(2.0F);
            level.broadcastEntityEvent(animal, (byte) 7);
        }
        data.touch();
        return true;
    }

    private static boolean progress(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity entity, PetState.Pet pet) {
        long now = level.getGameTime();
        if (pet.workerPosition == null || pet.workerPosition.distSqr(entity.blockPosition()) >= 1) {
            pet.workerPosition = entity.blockPosition();
            pet.progressAt = now;
            return true;
        }
        if (now - pet.progressAt < 240) return true;
        pet.blocker = "照料路线受阻，稍后重试";
        pet.retryAt = now + 200;
        pet.worker = null;
        pet.workerPosition = null;
        stopCareNavigation(level, entity);
        citizen.setActivity(CitizenActivity.BLOCKED, pet.blocker);
        return false;
    }

    static void moveCaregiver(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity entity,
                              PetState.Pet pet, BlockPos target, CitizenActivity activity, String purpose) {
        boolean navigating = entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > .64;
        CompanionMetrics.movePetCaretaker(level, citizen, entity, target, activity, purpose);
        if (navigating && target.equals(entity.currentNavigationDestination())
                && citizen.activity() == activity && purpose.equals(citizen.workFailure()))
            CARE_NAVIGATION.put(entity, new CareNavigation(pet.id, citizen, target.immutable(), activity, purpose));
    }

    private static void stopCareNavigation(ServerLevel level, SettlementCitizenEntity entity) {
        CARE_NAVIGATION.remove(entity);
        entity.stopSettlementNavigation();
        WarehouseInventoryService.releaseInteractionPosition(level, entity);
    }

    /** Lifecycle cancellation may run after another activity has taken over this resident. */
    private static void cancelCareNavigation(Animal animal, PetState.Pet pet) {
        if (!(animal.level() instanceof ServerLevel level)) return;
        var iterator = CARE_NAVIGATION.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            SettlementCitizenEntity entity = entry.getKey();
            CareNavigation intent = entry.getValue();
            if (entity.level() != level || !intent.pet().equals(pet.id)) continue;
            iterator.remove();
            CitizenRecord citizen = intent.citizen();
            if (citizen.workState() != CitizenWorkState.IDLE || !citizen.life().task.isEmpty()
                    || entity.isDefending() || entity.isSleeping() || entity.hasActiveConstructionMovement()
                    || CitizenInteractionService.held(entity)
                    || citizen.activity() != intent.activity() || !citizen.workFailure().equals(intent.purpose())
                    || !intent.target().equals(entity.currentNavigationDestination())) continue;
            entity.stopSettlementNavigation();
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
        }
    }

    private static PetState.Pet reserve(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                        CitizenRecord citizen, SettlementCitizenEntity entity) {
        PetState state = settlement.pets();
        BuildingRecord home = home(settlement, citizen);
        if (!state.enabled || home == null || state.count(true) >= state.catLimit && state.count(false) >= state.dogLimit)
            return null;
        long now = level.getGameTime();
        Map<UUID, Long> times = SEARCH.computeIfAbsent(level, ignored -> new HashMap<>());
        if (now < times.getOrDefault(settlement.id(), Long.MIN_VALUE)) return null;
        times.put(settlement.id(), now + 200);
        state.lastSearch = "等待周边可达的无主猫狗";
        List<Animal> candidates = level.getEntitiesOfClass(Animal.class, new AABB(home.position()).inflate(48, 12, 48),
                value -> value instanceof Cat || value instanceof Wolf);
        Map<UUID, Integer> cursors = SEARCH_CURSOR.computeIfAbsent(level, ignored -> new HashMap<>());
        int start = candidates.isEmpty() ? 0 : Math.floorMod(cursors.getOrDefault(settlement.id(), 0), candidates.size());
        cursors.put(settlement.id(), start + Math.min(16, candidates.size()));
        for (int index = 0; index < Math.min(16, candidates.size()); index++) {
            Animal animal = candidates.get((start + index) % candidates.size());
            boolean cat = animal instanceof Cat;
            if (state.count(cat) >= (cat ? state.catLimit : state.dogLimit)
                    || !eligible(level, settlement, animal, null, true)) continue;
            long priorDay = animal.getPersistentData().contains(ATTEMPT_DAY)
                    ? animal.getPersistentData().getLong(ATTEMPT_DAY) : -1;
            int priorAttempts = animal.getPersistentData().getInt(ATTEMPTS);
            for (PetState.Pet previous : state.entries.values()) {
                if (!previous.entityId.equals(animal.getUUID())) continue;
                if (previous.attemptDay > priorDay) { priorDay = previous.attemptDay; priorAttempts = previous.attempts; }
                else if (previous.attemptDay == priorDay) priorAttempts = Math.max(priorAttempts, previous.attempts);
            }
            if (day(level) < priorDay || day(level) == priorDay && priorAttempts >= 3) continue;
            PetState.Pet pet = new PetState.Pet(UUID.randomUUID(), animal.getUUID(), cat, citizen.id(), home.id(), animal.blockPosition());
            pet.attemptDay = priorDay;
            pet.attempts = Math.max(0, Math.min(3, priorAttempts));
            if (!PetLogistics.availableInBag(citizen, pet) && PetLogistics.find(level, settlement, citizen, pet) == null) {
                state.lastSearch = "等待领养食物余量";
                continue;
            }
            var path = CompanionMetrics.createPath(level, true, entity.getNavigation(), animal, 0);
            if (path == null || !path.canReach()) continue;
            // Reserve the actual identity on the server thread before any hunter can claim it.
            // A cancelled reservation may be retried later; never persist two records for one entity UUID.
            state.entries.values().removeIf(previous -> previous.entityId.equals(animal.getUUID())
                    && previous.status == PetState.Status.RELEASED);
            state.entries.put(pet.id, pet);
            animal.getPersistentData().putUUID(PET_ID, pet.id);
            animal.getPersistentData().putUUID(SETTLEMENT_ID, settlement.id());
            pet.worker = citizen.id();
            pet.workerUntil = now + 80;
            PetAi.attach(level, settlement, pet, animal);
            state.lastSearch = "";
            data.touch();
            return pet;
        }
        data.touch();
        return null;
    }

    private static boolean attemptAvailable(PetState.Pet pet, long day) {
        return day > pet.attemptDay || day == pet.attemptDay && pet.attempts < 3;
    }

    /** A reservation is not ownership: every player protection condition remains live. */
    static boolean eligible(ServerLevel level, SettlementRecord settlement, Animal animal, PetState.Pet own,
                            boolean checkPen) {
        if (!(animal instanceof Cat || animal instanceof Wolf) || !animal.isAlive() || animal.isRemoved()
                || !settlement.contains(animal.blockPosition())
                || animal.hasCustomName() || animal.isLeashed() || animal.isPassenger() || animal.isNoAi()
                || animal instanceof TamableAnimal tame && (tame.isTame() || tame.getOwnerUUID() != null)
                || animal instanceof Wolf wolf && wolf.isAngry()) return false;
        var tag = animal.getPersistentData();
        if (tag.hasUUID("exiledsettlement.pastureSettlement") || tag.hasUUID("exiledsettlement.pastureBuilding")) return false;
        if (tag.hasUUID(PET_ID) && (own == null || !tag.getUUID(PET_ID).equals(own.id)
                || !tag.hasUUID(SETTLEMENT_ID) || !tag.getUUID(SETTLEMENT_ID).equals(settlement.id()))) return false;
        for (SettlementRecord other : SettlementSavedData.get(level).settlements()) {
            for (PetState.Pet entry : other.pets().entries.values())
                if (entry.occupiesSlot() && entry.entityId.equals(animal.getUUID()) && entry != own) return false;
            for (BuildingRecord building : other.buildings()) {
                if (building.active() && building.type() == com.hkin.exiledsettlement.building.BuildingType.PASTURE_AREA
                        && PastureResidentService.inside(building, animal.blockPosition())) return false;
            }
        }
        return !checkPen || !enclosed(level, animal.blockPosition());
    }

    // Bounded enclosure check without treating our own reservation as a hunting exclusion.
    private static boolean enclosed(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        HashSet<BlockPos> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty() && seen.size() <= 256) {
            BlockPos current = open.removeFirst();
            if (Math.abs(current.getX() - start.getX()) >= 12 || Math.abs(current.getZ() - start.getZ()) >= 12) return false;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (!level.hasChunkAt(next)) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos feet = new BlockPos(next.getX(), current.getY() + dy, next.getZ());
                    if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                            || !level.getFluidState(feet).isEmpty()
                            || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                    if (seen.add(feet)) open.addLast(feet);
                    break;
                }
            }
        }
        return open.isEmpty();
    }

    private static boolean mayCare(SettlementRecord settlement, CitizenRecord citizen, PetState.Pet pet) {
        if (home(settlement, citizen) == null) return false;
        CitizenRecord owner = pet.caretaker == null ? null : settlement.citizen(pet.caretaker);
        return citizen.id().equals(pet.caretaker) || Objects.equals(citizen.homeBuildingId(), pet.home)
                || owner != null && Objects.equals(owner.life().partner, citizen.id());
    }

    static BuildingRecord home(SettlementRecord settlement, CitizenRecord citizen) {
        if (citizen == null || !citizen.canWork() || citizen.homeBuildingId() == null) return null;
        BuildingRecord home = settlement.building(citizen.homeBuildingId());
        return home != null && home.active() && home.operational() && home.type().isHouse() ? home : null;
    }

    static long day(ServerLevel level) { return Math.floorDiv(level.getDayTime(), 24000L); }

    public static void tickLevel(ServerLevel level) {
        if (!CompanionMetrics.ENABLED) { tickLevelMeasured(level); return; }
        long started = CompanionMetrics.start();
        try { tickLevelMeasured(level); }
        finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.PET_LIFECYCLE, started); }
    }

    private static void tickLevelMeasured(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            for (PetState.Pet pet : settlement.pets().entries.values()) {
                if (!pet.occupiesSlot()) continue;
                CitizenRecord caretaker = pet.caretaker == null ? null : settlement.citizen(pet.caretaker);
                if (pet.status == PetState.Status.ACTIVE) {
                    if (home(settlement, caretaker) == null) {
                        CitizenRecord replacement = settlement.citizens().stream()
                                .filter(citizen -> home(settlement, citizen) != null)
                                .min(Comparator.comparingInt(citizen -> Objects.equals(citizen.homeBuildingId(), pet.home) ? 0 : 1))
                                .orElse(null);
                        if (replacement != null) {
                            pet.caretaker = replacement.id();
                            pet.home = replacement.homeBuildingId();
                            pet.blocker = "";
                            caretaker = replacement;
                            data.touch();
                        } else pet.blocker = "待安置：没有有住所的成年照料者";
                    } else if (!Objects.equals(caretaker.homeBuildingId(), pet.home)) {
                        pet.home = caretaker.homeBuildingId();
                        pet.activity = "步行前往新家";
                        data.touch();
                    }
                }
                Entity entity = level.getEntity(pet.entityId);
                if (!(entity instanceof Animal animal)) {
                    pet.blocker = "实体尚未加载或待核对，保留登记与数量";
                    continue;
                }
                if (!animal.isAlive()) { onDeath(animal); continue; }
                if (pet.blocker.equals("实体尚未加载或待核对，保留登记与数量")) pet.blocker = "";
                if (animal instanceof TamableAnimal tame && tame.getOwnerUUID() != null) {
                    cancel(data, animal, pet, "已交由玩家照料");
                    continue;
                }
                if (pet.status == PetState.Status.ADOPTING && (!settlement.pets().enabled
                        || home(settlement, caretaker) == null || !eligible(level, settlement, animal, pet, true))) {
                    cancel(data, animal, pet, "领养已停止或动物资格发生变化");
                    continue;
                }
                if (!pet.lastPosition.equals(animal.blockPosition())) {
                    pet.lastPosition = animal.blockPosition();
                    data.touch();
                }
                animal.getPersistentData().putUUID(PET_ID, pet.id);
                animal.getPersistentData().putUUID(SETTLEMENT_ID, settlement.id());
                if (pet.status == PetState.Status.ACTIVE && animal instanceof TamableAnimal tame && !tame.isTame())
                    tame.setTame(true, false);
                if (settlement.active()) PetAi.attach(level, settlement, pet, animal);
                else PetAi.suspend(animal);
            }
        }
    }

    private static void cancel(SettlementSavedData data, Animal animal, PetState.Pet pet, String reason) {
        cancelCareNavigation(animal, pet);
        pet.status = PetState.Status.RELEASED;
        pet.worker = null;
        pet.blocker = reason;
        pet.activity = "已停止领养";
        PetAi.release(animal);
        if (animal.getPersistentData().hasUUID(PET_ID) && animal.getPersistentData().getUUID(PET_ID).equals(pet.id)) {
            animal.getPersistentData().remove(PET_ID);
            animal.getPersistentData().remove(SETTLEMENT_ID);
        }
        data.touch();
    }

    public static void onJoin(Entity entity) {
        if (!(entity instanceof Animal animal) || !(entity.level() instanceof ServerLevel level)) return;
        Binding binding = binding(animal);
        if (binding == null || !binding.pet().occupiesSlot()) return;
        if (binding.pet().blocker.equals("实体尚未加载或待核对，保留登记与数量")) binding.pet().blocker = "";
        if (binding.pet().status == PetState.Status.ADOPTING
                && !eligible(level, binding.settlement(), animal, binding.pet(), true)) {
            cancel(SettlementSavedData.get(level), animal, binding.pet(), "领养资格发生变化");
            return;
        }
        if (animal instanceof TamableAnimal tame && tame.getOwnerUUID() != null) {
            cancel(SettlementSavedData.get(level), animal, binding.pet(), "已交由玩家照料");
            return;
        }
        if (binding.pet().status == PetState.Status.ACTIVE && animal instanceof TamableAnimal tame && tame.getOwnerUUID() == null) {
            tame.setTame(true, false);
            tame.setOrderedToSit(false);
            animal.setPersistenceRequired();
        }
        if (binding.settlement().active()) PetAi.attach(level, binding.settlement(), binding.pet(), animal);
    }

    public static void tickEntity(LivingEntity entity) {
        if (!(entity instanceof Animal animal) || (entity.tickCount + entity.getId()) % 40 != 0) return;
        Binding binding = binding(animal);
        if (binding != null && binding.pet().occupiesSlot() && binding.settlement().active()
                && entity.level() instanceof ServerLevel level) PetAi.attach(level, binding.settlement(), binding.pet(), animal);
    }

    public static void onDeath(LivingEntity entity) {
        if (!(entity instanceof Animal animal)) return;
        Binding binding = binding(animal);
        if (binding == null || !binding.pet().occupiesSlot()) return;
        PetState.Pet pet = binding.pet();
        cancelCareNavigation(animal, pet);
        pet.status = PetState.Status.DEAD;
        pet.worker = null;
        pet.activity = "已死亡";
        pet.lastPosition = animal.blockPosition();
        pet.blocker = "";
        PetAi.release(animal);
        if (entity.level() instanceof ServerLevel level) {
            binding.settlement().community().event(day(level), (pet.cat ? "聚落猫" : "聚落狗") + "死亡");
            SettlementSavedData.get(level).touch();
        }
    }

    public static void release(Entity entity) { if (entity instanceof Animal animal) PetAi.release(animal); }
    public static void releaseAll() { PetAi.releaseAll(); SEARCH.clear(); SEARCH_CURSOR.clear(); CARE_NAVIGATION.clear(); }

    public static String detail(Animal animal) {
        Binding binding = binding(animal);
        if (binding == null) return "";
        PetState.Pet pet = binding.pet();
        CitizenRecord caretaker = pet.caretaker == null ? null : binding.settlement().citizen(pet.caretaker);
        boolean due = animal.level() instanceof ServerLevel level && pet.lastFedDay < day(level);
        return (pet.cat ? "聚落猫" : "聚落狗") + " · 照料者：" + (caretaker == null ? "待安置" : caretaker.name())
                + " · " + pet.activity + " · 生命：" + Math.round(animal.getHealth()) + "/" + Math.round(animal.getMaxHealth())
                + " · " + (due ? "待喂养" : "今天已喂养") + (pet.blocker.isEmpty() ? "" : " · " + pet.blocker);
    }

    public static List<String> report(SettlementRecord settlement) {
        List<String> lines = new ArrayList<>();
        PetState state = settlement.pets();
        lines.add("猫狗：猫 " + state.count(true) + "/" + state.catLimit + "，狗 " + state.count(false) + "/"
                + state.dogLimit + "；自动领养" + (state.enabled ? "开启" : "关闭"));
        if (!state.lastSearch.isEmpty()) lines.add("猫狗领养：" + state.lastSearch);
        state.entries.values().stream().filter(PetState.Pet::occupiesSlot).limit(8).forEach(pet -> {
            CitizenRecord owner = pet.caretaker == null ? null : settlement.citizen(pet.caretaker);
            lines.add((pet.cat ? "猫" : "狗") + " · " + (owner == null ? "待安置" : owner.name()) + " · "
                    + pet.activity + (pet.blocker.isEmpty() ? "" : "：" + pet.blocker));
        });
        return lines;
    }
}

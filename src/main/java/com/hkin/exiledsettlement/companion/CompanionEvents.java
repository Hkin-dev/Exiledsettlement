package com.hkin.exiledsettlement.companion;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Lifecycle hooks only affect explicitly registered colony companions. */
@EventBusSubscriber(modid = "exiledsettlement")
public final class CompanionEvents {
    private CompanionEvents() { }
    public static long lastServiceNanos, totalServiceNanos, serviceSamples, maxServiceNanos;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!event.getServer().tickRateManager().runsNormally()
                || event.getServer().getTickCount() % 20 != 7) return;
        long started = CompanionMetrics.start();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            GuardianService.tickLevel(level);
            PetCareService.tickLevel(level);
        }
        if (CompanionMetrics.ENABLED) {
            lastServiceNanos = CompanionMetrics.elapsed(started);
            totalServiceNanos += lastServiceNanos;
            maxServiceNanos = Math.max(maxServiceNanos, lastServiceNanos);
            serviceSamples++;
        }
    }

    /** Flush after work, AI and the ordinary lifecycle callback; log cost is outside every bucket. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void timings(ServerTickEvent.Post event) { CompanionMetrics.flush(event.getServer()); }

    @SubscribeEvent
    public static void entityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity)
                || !(entity.level() instanceof ServerLevel level)
                || !(entity instanceof IronGolem || entity instanceof Animal)) return;
        long started = CompanionMetrics.start();
        try {
            if (entity instanceof IronGolem) GuardianService.tickEntity(entity);
            if (entity instanceof Animal) PetCareService.tickEntity(entity);
        } finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.ENTITY_CALLBACK, started); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void join(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        long started = CompanionMetrics.start();
        try {
            GuardianService.onJoin(event.getEntity());
            PetCareService.onJoin(event.getEntity());
        } finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.LIFECYCLE_EVENT, started); }
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long started = CompanionMetrics.start();
        try {
            GuardianService.release(event.getEntity());
            PetCareService.release(event.getEntity());
        } finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.LIFECYCLE_EVENT, started); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        long started = CompanionMetrics.start();
        try {
            GuardianService.onDeath(event.getEntity());
            PetCareService.onDeath(event.getEntity());
        } finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.LIFECYCLE_EVENT, started); }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        long started = CompanionMetrics.start();
        try {
            GuardianService.releaseAll();
            PetCareService.releaseAll();
        } finally {
            CompanionMetrics.finish(event.getServer().overworld(), CompanionMetrics.Bucket.LIFECYCLE_EVENT, started);
            CompanionMetrics.stop(event.getServer());
        }
    }

    @SubscribeEvent
    public static void friendlyFire(LivingIncomingDamageEvent event) {
        var attacker = event.getSource().getEntity();
        if (attacker == null || !(attacker.level() instanceof ServerLevel)) return;
        var tags = attacker.getPersistentData();
        if (!tags.hasUUID("exiledsettlement.guardianId") && !tags.hasUUID("exiledsettlement.petId")) return;
        var victim = event.getEntity();
        if (!(victim instanceof Enemy) || victim instanceof Creeper
                || victim.getPersistentData().hasUUID("exiledsettlement.guardianId")
                || victim.getPersistentData().hasUUID("exiledsettlement.petId")) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        interactCompanion(event);
    }

    @SubscribeEvent
    public static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        interactCompanion(event);
    }

    private static void interactCompanion(PlayerInteractEvent event) {
        net.minecraft.world.entity.Entity target;
        if (event instanceof PlayerInteractEvent.EntityInteract interaction) target = interaction.getTarget();
        else if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) target = specific.getTarget();
        else return;
        if (target instanceof Animal animal && PetCareService.residentInteraction(event.getEntity(), animal)) return;
        var held = event.getItemStack();
        String detail = null;
        if (target instanceof Animal animal && PetCareService.blocksVanillaInteraction(animal)
                && (held.isEmpty() || animal.isFood(held) || held.is(Items.BONE))) {
            if (event.getLevel() instanceof ServerLevel) detail = PetCareService.detail(animal);
        } else if (target instanceof IronGolem golem
                && golem.getPersistentData().hasUUID("exiledsettlement.guardianId") && held.isEmpty()) {
            if (event.getLevel() instanceof ServerLevel) detail = GuardianService.detail(golem);
        } else return;
        if (event instanceof PlayerInteractEvent.EntityInteract interaction) {
            interaction.setCanceled(true);
            interaction.setCancellationResult(InteractionResult.SUCCESS);
        }
        if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) {
            specific.setCanceled(true);
            specific.setCancellationResult(InteractionResult.SUCCESS);
        }
        if (detail != null && event.getHand() == InteractionHand.MAIN_HAND)
            event.getEntity().displayClientMessage(Component.literal(detail), true);
    }
}

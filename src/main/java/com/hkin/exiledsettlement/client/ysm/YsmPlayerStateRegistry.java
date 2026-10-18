package com.hkin.exiledsettlement.client.ysm;

import net.minecraft.world.entity.player.Player;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Weak client-only association populated when YSM constructs a player-state holder. */
public final class YsmPlayerStateRegistry {
    private static final Map<WeakIdentityKey<Player>, WeakReference<Object>> STATES = new HashMap<>();
    private static final ReferenceQueue<Player> COLLECTED = new ReferenceQueue<>();

    private YsmPlayerStateRegistry() {
    }

    public static void register(Player player, Object state) {
        if (player == null || state == null || !player.level().isClientSide()) return;
        synchronized (STATES) {
            prune();
            STATES.put(new WeakIdentityKey<>(player, COLLECTED), new WeakReference<>(state));
        }
    }

    static Object state(Player player) {
        if (player == null) return null;
        synchronized (STATES) {
            prune();
            WeakReference<Object> reference = STATES.get(new WeakIdentityKey<>(player, null));
            return reference == null ? null : reference.get();
        }
    }

    private static void prune() {
        for (var collected = COLLECTED.poll(); collected != null; collected = COLLECTED.poll()) {
            STATES.remove(collected);
        }
    }

    private static final class WeakIdentityKey<T> extends WeakReference<T> {
        private final int identityHash;

        private WeakIdentityKey(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override public int hashCode() { return identityHash; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WeakIdentityKey<?> key)) return false;
            Object left = get();
            return left != null && left == key.get();
        }
    }
}

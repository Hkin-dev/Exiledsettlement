package com.hkin.exiledsettlement.client.ysm;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmSymbols;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/** Uses the mapped query owner and vanilla food call to locate the proxy-only cache.
 * No private YSM names are stored, and an ambiguous structure fails closed. */
final class YsmResidentFoodAccess {
    private static Access access;
    private static boolean resolved;
    private static boolean warned;

    private record Access(Method values, Field food) {}

    static void normalize(Player proxy) {
        Object state = YsmPlayerStateRegistry.state(proxy);
        if (state == null) return;
        try {
            if (!resolved) {
                resolved = true;
                access = resolve();
            }
            if (access == null || !access.values().getDeclaringClass().isInstance(state)) return;
            Object values = access.values().invoke(state);
            if (values != null) access.food().setInt(values, 20);
        } catch (Exception | LinkageError unavailable) {
            access = null;
            warn();
        }
    }

    private static Access resolve() throws Exception {
        var symbol = YsmMappingApi.resolve(ExiledSettlementMod.MOD_ID,
                Set.of(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY)).require(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY);
        ClassLoader loader = YsmResidentFoodAccess.class.getClassLoader();
        ClassNode queries = read(loader, symbol.binaryOwner().replace('.', '/'));
        MethodInsnNode valuesCall = null;
        FieldInsnNode foodField = null;
        for (MethodNode query : queries.methods) {
            boolean readsFood = false;
            for (var instruction : query.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/food/FoodData")
                        && call.name.equals("getFoodLevel") && call.desc.equals("()I")) readsFood = true;
            }
            if (!readsFood) continue;
            // The cached branch immediately precedes the Player/FoodData fallback.
            MethodInsnNode previousCall = null;
            for (var instruction : query.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.desc.equals("()I") && call.owner.startsWith("com/elfmcys/yesstevemodel/")) {
                    if (valuesCall != null || previousCall == null || !previousCall.desc.startsWith("()L")) {
                        throw new IllegalStateException("Ambiguous resident food query");
                    }
                    ClassNode cache = read(loader, call.owner);
                    MethodNode getter = cache.methods.stream().filter(method -> method.name.equals(call.name)
                            && method.desc.equals(call.desc)).findFirst().orElseThrow();
                    int operations = 0;
                    for (var op : getter.instructions) {
                        if (op.getOpcode() >= 0) operations++;
                        if (op instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                                && field.desc.equals("I")) foodField = field;
                    }
                    if (operations != 3 || foodField == null) throw new IllegalStateException("Unsupported food cache getter");
                    valuesCall = previousCall;
                }
                previousCall = call;
            }
        }
        if (valuesCall == null || foodField == null) throw new IllegalStateException("Missing resident food cache");
        Class<?> holder = Class.forName(valuesCall.owner.replace('/', '.'), false, loader);
        Method values = holder.getDeclaredMethod(valuesCall.name);
        Class<?> cache = Class.forName(foodField.owner.replace('/', '.'), false, loader);
        Field food = cache.getDeclaredField(foodField.name);
        if (values.getReturnType() != cache || !Modifier.isPublic(values.getModifiers())
                || Modifier.isStatic(food.getModifiers()) || Modifier.isFinal(food.getModifiers())
                || food.getType() != int.class || !values.trySetAccessible() || !food.trySetAccessible()) {
            throw new IllegalStateException("Unsupported resident cache access");
        }
        return new Access(values, food);
    }

    private static ClassNode read(ClassLoader loader, String owner) throws Exception {
        try (InputStream stream = loader.getResourceAsStream(owner + ".class")) {
            if (stream == null) throw new IllegalStateException("Missing query bytecode");
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void warn() {
        if (warned) return;
        warned = true;
        ExiledSettlementMod.LOGGER.warn("YSM resident food cache normalization unavailable; resident nutrition remains unchanged");
    }
}

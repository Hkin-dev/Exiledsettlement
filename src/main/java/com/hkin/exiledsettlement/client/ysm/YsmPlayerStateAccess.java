package com.hkin.exiledsettlement.client.ysm;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.compat.ysm.YsmPlayerStateAlias;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSourceAlias;
import net.okitsu.ysmmapping.api.YsmStructureConstraints;
import net.okitsu.ysmmapping.api.YsmStructurePattern;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Set;

/** Reflection boundary for the verified YSM player-selection state. */
public final class YsmPlayerStateAccess {
    private static final String EMPTY_CONSTANT_DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final YsmSymbolKey<YsmMethodSymbol> SELECTION_SETTER =
            YsmSymbolKey.consumerMethod(
                    "resident_player_state.selection_setter",
                    YsmSourceAlias.methodAlias(
                            YsmPlayerStateAlias.class.getName(),
                            "setModelAndTexture",
                            "(Ljava/lang/String;Ljava/lang/String;)V"),
                    YsmStructurePattern.common(YsmStructureConstraints.builder()
                            .requiredAccess(Modifier.PUBLIC)
                            .descriptorShape("(Ljava/lang/String;Ljava/lang/String;)V")
                            .opcodeDigest("9b42be13c17c36ae82bffdd32c8f3534e9ce9e6c32126068f2da57ef6df11bba")
                            .constantDigest(EMPTY_CONSTANT_DIGEST)
                            .build()));
    private static final Set<YsmSymbolKey<?>> SYMBOLS = Set.of(
            SELECTION_SETTER,
            YsmSymbols.PLAYER_STATE_MODEL_ID_GETTER,
            YsmSymbols.PLAYER_STATE_MODEL_DISABLED_GETTER);

    private static Access access;
    private static boolean resolved;
    private static boolean warned;

    private YsmPlayerStateAccess() {
    }

    public static ApplyResult apply(Player player, String modelId, String textureName) {
        Access current = access();
        if (current == null) return ApplyResult.MAPPING_UNAVAILABLE;
        Object state = YsmPlayerStateRegistry.state(player);
        if (state == null) return ApplyResult.STATE_UNAVAILABLE;
        return current.apply(state, modelId, textureName);
    }

    public static String currentModelId(Player player) {
        Object state = YsmPlayerStateRegistry.state(player);
        Access current = access();
        return state == null || current == null ? null : current.modelId(state);
    }

    private static synchronized Access access() {
        if (resolved) return access;
        resolved = true;
        try {
            MappingSnapshot mapping = YsmMappingApi.resolve(ExiledSettlementMod.MOD_ID, SYMBOLS);
            access = Access.resolve(
                    mapping.require(SELECTION_SETTER),
                    mapping.require(YsmSymbols.PLAYER_STATE_MODEL_ID_GETTER),
                    mapping.require(YsmSymbols.PLAYER_STATE_MODEL_DISABLED_GETTER),
                    YsmPlayerStateAccess.class.getClassLoader()).orElse(null);
        } catch (Exception | LinkageError unavailable) {
            // Mapping errors can contain private runtime identifiers; keep logs semantic only.
        }
        if (access == null && !warned) {
            warned = true;
            ExiledSettlementMod.LOGGER.warn(
                    "YSM resident rendering is unavailable because a required semantic mapping did not resolve");
        }
        return access;
    }

    private record Access(MethodHandle setter, MethodHandle modelId, MethodHandle disabled) {
        private static Optional<Access> resolve(
                YsmMethodSymbol setterSymbol,
                YsmMethodSymbol modelIdSymbol,
                YsmMethodSymbol disabledSymbol,
                ClassLoader loader
        ) {
            try {
                MethodHandle setter = method(setterSymbol, void.class,
                        new Class<?>[]{String.class, String.class}, loader);
                MethodHandle modelId = method(modelIdSymbol, String.class, new Class<?>[0], loader);
                MethodHandle disabled = method(disabledSymbol, boolean.class, new Class<?>[0], loader);
                return Optional.of(new Access(setter, modelId, disabled));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return Optional.empty();
            }
        }

        private ApplyResult apply(Object state, String model, String texture) {
            if (!setter.type().parameterType(0).isInstance(state)) {
                return ApplyResult.STATE_TYPE_MISMATCH;
            }
            try {
                setter.invoke(state, model, texture);
                return ApplyResult.APPLIED;
            } catch (Throwable unavailable) {
                return ApplyResult.WRITE_FAILED;
            }
        }

        private String modelId(Object state) {
            if (!modelId.type().parameterType(0).isInstance(state)
                    || !disabled.type().parameterType(0).isInstance(state)) return null;
            try {
                if ((boolean) disabled.invoke(state)) return null;
                String selected = (String) modelId.invoke(state);
                return selected == null || selected.isBlank() ? null : selected;
            } catch (Throwable unavailable) {
                return null;
            }
        }

        private static MethodHandle method(
                YsmMethodSymbol symbol,
                Class<?> result,
                Class<?>[] parameters,
                ClassLoader loader
        ) throws ReflectiveOperationException {
            MethodType type = MethodType.fromMethodDescriptorString(symbol.descriptor(), loader);
            if (type.returnType() != result || !java.util.Arrays.equals(type.parameterArray(), parameters)) {
                throw new ReflectiveOperationException("Unsupported YSM semantic method shape");
            }
            Class<?> owner = Class.forName(symbol.binaryOwner(), false, loader);
            Method method = owner.getDeclaredMethod(symbol.name(), parameters);
            if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())
                    || Modifier.isNative(method.getModifiers()) || !method.trySetAccessible()) {
                throw new ReflectiveOperationException("Unsupported YSM semantic method");
            }
            return MethodHandles.lookup().unreflect(method);
        }
    }

    public enum ApplyResult {
        APPLIED,
        STATE_UNAVAILABLE,
        MAPPING_UNAVAILABLE,
        STATE_TYPE_MISMATCH,
        WRITE_FAILED
    }
}

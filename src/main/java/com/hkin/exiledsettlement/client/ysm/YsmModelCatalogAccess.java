package com.hkin.exiledsettlement.client.ysm;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only bridge to YSM's loaded client model catalog. */
public final class YsmModelCatalogAccess {
    private static final Set<net.okitsu.ysmmapping.api.YsmSymbolKey<?>> SYMBOLS = Set.of(
            YsmSymbols.CLIENT_MODEL_MAP_GETTER);

    private static Access access;
    private static boolean resolved;
    private static boolean warned;

    private YsmModelCatalogAccess() {
    }

    public static List<ModelOption> models() {
        Access current = access();
        if (current == null) return List.of();
        try {
            Object raw = current.modelMap.invoke();
            if (!(raw instanceof Map<?, ?> models)) return List.of();
            Map<String, LocalMetadata> metadata = readLocalMetadata();
            List<ModelOption> result = new ArrayList<>(models.size());
            for (Map.Entry<?, ?> entry : models.entrySet()) {
                String id = String.valueOf(entry.getKey()).strip();
                if (id.isEmpty()) continue;
                LocalMetadata local = metadata.get(id);
                result.add(local == null
                        ? new ModelOption(id, readableFallback(id), List.of())
                        : new ModelOption(id, local.displayName(), local.textures()));
            }
            result.sort(Comparator.comparing(ModelOption::displayName, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(result);
        } catch (Throwable unavailable) {
            warnOnce();
            return List.of();
        }
    }

    private static synchronized Access access() {
        if (resolved) return access;
        resolved = true;
        try {
            MappingSnapshot mapping = YsmMappingApi.resolve(ExiledSettlementMod.MOD_ID, SYMBOLS);
            access = Access.resolve(
                    mapping.require(YsmSymbols.CLIENT_MODEL_MAP_GETTER),
                    YsmModelCatalogAccess.class.getClassLoader()).orElse(null);
        } catch (Exception | LinkageError unavailable) {
            // Mapping errors can contain private runtime identifiers; keep logs semantic only.
        }
        if (access == null) warnOnce();
        return access;
    }

    private static void warnOnce() {
        if (warned) return;
        warned = true;
        ExiledSettlementMod.LOGGER.warn(
                "YSM resident model browsing is unavailable because the loaded-model catalog could not be read");
    }

    private static Map<String, LocalMetadata> readLocalMetadata() {
        Map<String, LocalMetadata> result = new HashMap<>();
        Minecraft minecraft = Minecraft.getInstance();
        Path base = minecraft.gameDirectory.toPath().resolve("config").resolve("yes_steve_model");
        String selectedLanguage = minecraft.getLanguageManager().getSelected();
        for (String rootName : List.of("builtin", "custom", "auth")) {
            Path root = base.resolve(rootName);
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.find(root, 18,
                    (path, attributes) -> attributes.isRegularFile()
                            && path.getFileName().toString().equalsIgnoreCase("ysm.json"))) {
                files.forEach(path -> readMetadataFile(root, rootName, path, selectedLanguage, result));
            } catch (IOException ignored) {
                // A model with unreadable metadata keeps a readable ID-derived fallback name.
            }
        }
        return result;
    }

    private static void readMetadataFile(
            Path root,
            String rootName,
            Path manifest,
            String selectedLanguage,
            Map<String, LocalMetadata> result
    ) {
        try {
            JsonObject model = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject language = readLanguage(manifest.getParent(), selectedLanguage);
            String name = displayText(localizedString(language, "metadata.name",
                    nestedString(model, "metadata", "name", readableFallback(modelId(root, manifest.getParent())))));
            List<TextureOption> textures = readTextures(model, language);
            String id = modelId(root, manifest.getParent());
            if (id.isBlank()) return;
            LocalMetadata metadata = new LocalMetadata(name, textures);
            result.putIfAbsent(id, metadata);
            result.putIfAbsent(rootName + "/" + id, metadata);
            result.putIfAbsent(id.replace('/', '_'), metadata);
            result.putIfAbsent(rootName + "_" + id.replace('/', '_'), metadata);
        } catch (IOException | RuntimeException ignored) {
            // Ignore one broken model metadata file without hiding other loaded models.
        }
    }

    private static JsonObject readLanguage(Path modelDirectory, String selectedLanguage) {
        for (String language : List.of(selectedLanguage, "zh_cn", "en_us")) {
            Path path = modelDirectory.resolve("lang").resolve(language + ".json");
            if (!Files.isRegularFile(path)) continue;
            try {
                return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException | RuntimeException ignored) {
                // Try the next language fallback.
            }
        }
        return new JsonObject();
    }

    private static List<TextureOption> readTextures(JsonObject model, JsonObject language) {
        JsonElement textureElement = nested(model, "files", "player", "texture");
        if (textureElement == null || !textureElement.isJsonArray()) return List.of();
        JsonArray array = textureElement.getAsJsonArray();
        List<TextureOption> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
            String id = textureId(element.getAsString());
            if (id.isBlank()) continue;
            String displayName = displayText(
                    localizedString(language, "files.player.texture." + id, readableFallback(id)));
            result.add(new TextureOption(id, displayName));
        }
        return List.copyOf(result);
    }

    private static JsonElement nested(JsonObject object, String... path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(key);
        }
        return current;
    }

    private static String nestedString(JsonObject object, String first, String second, String fallback) {
        JsonElement value = nested(object, first, second);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : fallback;
    }

    private static String localizedString(JsonObject language, String key, String fallback) {
        JsonElement value = language.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : fallback;
    }

    private static String modelId(Path root, Path modelDirectory) {
        List<String> segments = new ArrayList<>();
        for (Path segment : root.relativize(modelDirectory)) {
            String value = segment.toString();
            if (!value.isBlank()) segments.add(value);
        }
        return String.join("/", segments);
    }

    private static String textureId(String path) {
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.endsWith(".png")) value = value.substring(0, value.length() - 4);
        return value;
    }

    private static String readableFallback(String id) {
        String value = id.replace('_', ' ').replace('-', ' ').strip();
        return value.isEmpty() ? id : value;
    }

    private static String displayText(String value) {
        return value.replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replace('\n', ' ').replace('\r', ' ').strip();
    }

    public record TextureOption(String id, String displayName) {
    }

    public record ModelOption(String id, String displayName, List<TextureOption> textures) {
        public ModelOption {
            textures = List.copyOf(textures);
        }
    }

    private record LocalMetadata(String displayName, List<TextureOption> textures) {
    }

    private record Access(MethodHandle modelMap) {
        private static Optional<Access> resolve(
                YsmMethodSymbol modelMapSymbol,
                ClassLoader loader
        ) {
            try {
                MethodHandle modelMap = method(modelMapSymbol, loader);
                if (modelMap.type().parameterCount() != 0) {
                    return Optional.empty();
                }
                return Optional.of(new Access(modelMap));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return Optional.empty();
            }
        }

        private static MethodHandle method(YsmMethodSymbol symbol, ClassLoader loader)
                throws ReflectiveOperationException {
            MethodType type = MethodType.fromMethodDescriptorString(symbol.descriptor(), loader);
            Class<?> owner = Class.forName(symbol.binaryOwner(), false, loader);
            Method method = owner.getDeclaredMethod(symbol.name(), type.parameterArray());
            if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())
                    || !method.trySetAccessible()) {
                throw new ReflectiveOperationException("Unsupported YSM semantic catalog method");
            }
            return MethodHandles.lookup().unreflect(method);
        }
    }
}

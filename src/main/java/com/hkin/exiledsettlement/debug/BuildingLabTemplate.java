package com.hkin.exiledsettlement.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Independent review data; never changes the settlement's versioned templates. */
public record BuildingLabTemplate(String id, String name, BlockPos size, Map<BlockPos, Entry> blocks) {
    public static final Set<String> IDS = Set.of(
            "town_hall_round3_v1", "town_hall_round3_v2", "sawmill_round3_v1", "sawmill_round3_v2",
            "school_round3_v1", "school_round3_v2", "tool_workshop_round3_v1", "tool_workshop_round3_v2");
    private static final long MAX_BYTES = 2_000_000;

    public static BuildingLabTemplate load(Path directory, String id) throws IOException {
        if (!IDS.contains(id)) throw new IllegalArgumentException("未知建筑候选：" + id);
        Path file = directory.resolve(id + ".json");
        if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("候选文件超过 2 MB");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(id, JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    public static BuildingLabTemplate parse(String id, JsonObject root) {
        if (!IDS.contains(id) || root.get("schema_version").getAsInt() != 1
                || !id.equals(root.get("id").getAsString())) {
            throw new IllegalArgumentException("候选 ID 或格式版本不匹配");
        }
        JsonObject dimensions = root.getAsJsonObject("size");
        BlockPos size = new BlockPos(dimensions.get("x").getAsInt(), dimensions.get("y").getAsInt(),
                dimensions.get("z").getAsInt());
        if (size.getX() < 1 || size.getX() > 48 || size.getY() < 1 || size.getY() > 32
                || size.getZ() < 1 || size.getZ() > 48) {
            throw new IllegalArgumentException("设计现场尺寸上限为 48×32×48");
        }
        JsonArray source = root.getAsJsonArray("blocks");
        if (source.size() > 12000) throw new IllegalArgumentException("候选方块超过 12000 个");
        Map<BlockPos, Entry> blocks = new LinkedHashMap<>();
        for (JsonElement element : source) {
            JsonObject entry = element.getAsJsonObject();
            JsonArray pos = entry.getAsJsonArray("pos");
            if (pos.size() != 3) throw new IllegalArgumentException("方块坐标必须有三个分量");
            BlockPos position = new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            if (position.getX() < 0 || position.getX() >= size.getX() || position.getY() < 0
                    || position.getY() >= size.getY() || position.getZ() < 0 || position.getZ() >= size.getZ()) {
                throw new IllegalArgumentException("候选坐标越界：" + position.toShortString());
            }
            ResourceLocation blockId = ResourceLocation.parse(entry.get("block").getAsString());
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (!blockId.getNamespace().equals("minecraft") || block == Blocks.AIR) {
                throw new IllegalArgumentException("设计现场仅接受原版非空气方块：" + blockId);
            }
            BlockState state = block.defaultBlockState();
            if (entry.has("state")) {
                for (var property : entry.getAsJsonObject("state").entrySet()) {
                    Property<?> key = block.getStateDefinition().getProperty(property.getKey());
                    if (key == null) throw new IllegalArgumentException("未知方块属性：" + property.getKey());
                    state = setProperty(state, key, property.getValue().getAsString());
                }
            }
            String role = entry.has("role") ? entry.get("role").getAsString() : "wall";
            if (blocks.put(position, new Entry(state, role)) != null) {
                throw new IllegalArgumentException("候选坐标重复：" + position.toShortString());
            }
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("候选不能为空");
        return new BuildingLabTemplate(id, root.get("display_name").getAsString(), size, Map.copyOf(blocks));
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> key, String value) {
        return state.setValue(key, key.getValue(value).orElseThrow(
                () -> new IllegalArgumentException("无效方块属性值：" + key.getName() + "=" + value)));
    }

    public Map<BlockPos, BlockState> at(BlockPos origin, boolean interior) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        blocks.forEach((offset, entry) -> {
            boolean shell = Set.of("wall", "frame", "roof", "chimney", "window", "porch", "floor")
                    .contains(entry.role());
            if (interior && offset.getY() > 1 && shell) return;
            result.put(origin.offset(offset), entry.state());
        });
        // Retain the real support directly above each hanging lantern in cutaway mode.
        if (interior) blocks.forEach((offset, entry) -> {
            if (!entry.role().equals("light")) return;
            Entry support = blocks.get(offset.above());
            if (support != null) result.put(origin.offset(offset.above()), support.state());
        });
        return result;
    }

    public record Entry(BlockState state, String role) { }
}

package com.hkin.exiledsettlement.blueprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Reads structure geometry only. Entity and block-entity payloads never enter a plan. */
public final class SchematicFileReader {
    private static final int MAX_VOLUME = 128 * 128 * 128;
    private SchematicFileReader() { }

    public static SchematicDocument read(Path file) throws IOException {
        String filename = file.getFileName().toString();
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("schem") && !extension.equals("litematic")) fail("请选择 .litematic 或 .schem 文件");
        if (!Files.isRegularFile(file) || Files.size(file) > 16L * 1024 * 1024) fail("投影文件不存在或超过16MiB");
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
        return decode(root, extension, filename.substring(0, filename.lastIndexOf('.')));
    }

    public static SchematicDocument decode(CompoundTag root, String extension, String fallbackName) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        String name;
        if (extension.equals("litematic")) {
            int version = root.getInt("Version");
            if (version < 4 || version > 7) fail("不支持的 Litematic 版本：" + version);
            CompoundTag regions = root.getCompound("Regions");
            if (regions.isEmpty() || regions.size() > 1024) fail("投影区域为空或过多");
            long scanned = 0;
            for (String key : new TreeSet<>(regions.getAllKeys())) {
                CompoundTag region = regions.getCompound(key);
                BlockPos size = vector(region.getCompound("Size")), pos = vector(region.getCompound("Position"));
                int sx = dimension(size.getX()), sy = dimension(size.getY()), sz = dimension(size.getZ());
                long volume = (long)sx * sy * sz;
                if ((scanned += volume) > MAX_VOLUME) fail("投影区域总体积超过128³格");
                ListTag tags = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
                if (tags.isEmpty() || tags.size() > MAX_VOLUME) fail("投影方块表无效");
                List<BlockState> palette = new ArrayList<>();
                for (int i = 0; i < tags.size(); i++) palette.add(state(tags.getCompound(i)));
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                long[] data = region.getLongArray("BlockStates");
                if (data.length != (volume * bits + 63) / 64) fail("投影方块数据长度不完整");
                int ox = Math.addExact(pos.getX(), Math.min(0, size.getX() + 1));
                int oy = Math.addExact(pos.getY(), Math.min(0, size.getY() + 1));
                int oz = Math.addExact(pos.getZ(), Math.min(0, size.getZ() + 1));
                for (int i = 0; i < volume; i++) {
                    long bit = (long)i * bits;
                    int word = (int)(bit >>> 6), shift = (int)(bit & 63);
                    long value = data[word] >>> shift;
                    if (shift + bits > 64) value |= data[word + 1] << (64 - shift);
                    int id = (int)(value & ((1L << bits) - 1));
                    if (id >= palette.size()) fail("投影含无效方块编号");
                    put(cells, Math.addExact(ox, i % sx), Math.addExact(oy, i / (sx * sz)),
                            Math.addExact(oz, i / sx % sz), palette.get(id));
                }
            }
            name = root.getCompound("Metadata").getString("Name");
        } else if (extension.equals("schem")) {
            CompoundTag schematic = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
            int version = schematic.getInt("Version");
            if (version < 1 || version > 3) fail("不支持的 Schematic 版本：" + version);
            int sx = dimension(Short.toUnsignedInt(schematic.getShort("Width")));
            int sy = dimension(Short.toUnsignedInt(schematic.getShort("Height")));
            int sz = dimension(Short.toUnsignedInt(schematic.getShort("Length")));
            int volume = sx * sy * sz;
            CompoundTag blocks = version == 3 ? schematic.getCompound("Blocks") : schematic;
            CompoundTag tags = blocks.getCompound("Palette");
            if (tags.isEmpty() || tags.size() > MAX_VOLUME) fail("投影方块表无效");
            Map<Integer, BlockState> palette = new HashMap<>();
            for (String text : tags.getAllKeys()) {
                int id = tags.getInt(text);
                if (id < 0 || !tags.contains(text, Tag.TAG_INT) || palette.put(id, state(text)) != null) fail("投影方块表编号重复或无效");
            }
            byte[] data = blocks.getByteArray(version == 3 ? "Data" : "BlockData");
            int cursor = 0;
            for (int i = 0; i < volume; i++) {
                int id = 0, shift = 0, b;
                do {
                    if (cursor >= data.length || shift > 28) throw new IllegalArgumentException("投影方块数据损坏或截断");
                    b = data[cursor++] & 255;
                    if (shift == 28 && (b & 248) != 0) fail("投影方块编号溢出");
                    id |= (b & 127) << shift;
                    shift += 7;
                } while ((b & 128) != 0);
                BlockState state = palette.get(id);
                if (state == null) fail("投影引用了不存在的方块编号：" + id);
                // A single schematic's paste offset is irrelevant to interactive placement.
                put(cells, i % sx, i / (sx * sz), i / sx % sz, state);
            }
            if (cursor != data.length) fail("投影包含多余方块数据");
            name = schematic.getCompound("Metadata").getString("Name");
        } else throw new IllegalArgumentException("请选择 .litematic 或 .schem 文件");
        return new SchematicDocument(name.isBlank() ? fallbackName : name, cells);
    }

    private static BlockPos vector(CompoundTag tag) {
        if (!tag.contains("x", Tag.TAG_INT) || !tag.contains("y", Tag.TAG_INT) || !tag.contains("z", Tag.TAG_INT)) fail("投影区域坐标不完整");
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }
    private static int dimension(int signed) {
        if (signed == 0 || signed < -128 || signed > 128) fail("投影每边必须在1至128格之间");
        return Math.abs(signed);
    }
    private static void put(Map<BlockPos, BlockState> cells, int x, int y, int z, BlockState state) {
        if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) return;
        // BlockPos packing is used by frozen snapshots; reject coordinates that would wrap.
        if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || Math.abs((long)y) > 1_000_000) fail("投影区域坐标过大");
        BlockState old = cells.putIfAbsent(new BlockPos(x, y, z), state);
        if (old != null && !old.equals(state)) fail("投影区域重叠且方块冲突");
        if (cells.size() > CustomTemplateSnapshot.MAX_BLOCKS) fail("投影超过32768个非空气方块");
    }
    private static BlockState state(CompoundTag tag) {
        BlockState state = baseState(tag.getString("Name"));
        CompoundTag props = tag.getCompound("Properties");
        for (String key : props.getAllKeys()) state = property(state, key, props.getString(key));
        return state;
    }
    private static BlockState state(String text) {
        int open = text.indexOf('[');
        BlockState state = baseState(open < 0 ? text : text.substring(0, open));
        if (open >= 0) {
            if (!text.endsWith("]")) fail("投影方块属性格式错误：" + text);
            Set<String> seen = new HashSet<>();
            String properties = text.substring(open + 1, text.length() - 1);
            if (!properties.isEmpty()) for (String pair : properties.split(",", -1)) {
                String[] parts = pair.split("=", -1);
                if (parts.length != 2 || !seen.add(parts[0])) fail("投影方块属性重复或无效：" + text);
                state = property(state, parts[0], parts[1]);
            }
        }
        return state;
    }
    private static BlockState baseState(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("缺少或不支持的方块：" + name);
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> prop, String value) {
        return state.setValue(prop, prop.getValue(value).orElseThrow(() -> new IllegalArgumentException("无效方块属性：" + prop.getName() + "=" + value)));
    }
    private static BlockState property(BlockState state, String key, String value) {
        Property<?> prop = state.getBlock().getStateDefinition().getProperty(key);
        if (prop == null) throw new IllegalArgumentException("不支持的方块属性：" + key);
        return set(state, prop, value);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}

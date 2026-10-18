package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Server-thread-only, bounded creative editing. No commands, inventory or settlement registration. */
public final class BuildingEditWorkspace {
    public static final int MAX_VOLUME = 65536;
    public static final int MAX_CHANGES = 8192;
    public static final int TICK_BUDGET = 128;
    private static final int MAX_JOBS = 64;
    private static final int FLAGS = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final Set<String> FORBIDDEN = Set.of("tnt", "fire", "soul_fire", "water", "lava",
            "bedrock", "barrier", "command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "structure_void", "jigsaw", "end_portal", "end_gateway", "nether_portal",
            "spawner", "trial_spawner", "vault", "respawn_anchor", "moving_piston", "piston", "sticky_piston",
            "redstone_wire", "redstone_torch", "redstone_wall_torch", "redstone_block", "repeater", "comparator",
            "observer", "dispenser", "dropper", "hopper", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst",
            "sculk_shrieker", "tripwire", "tripwire_hook", "lever", "daylight_detector", "bubble_column", "dragon_egg");
    public final ServerLevel level;
    public final UUID owner;
    public final String name;
    public final BlockPos min, max;
    private final Path journal;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Map<BlockPos, Cell> snapshot = Map.of();
    private String snapshotId = "";
    private long revision;
    private Job pending;

    public BuildingEditWorkspace(ServerLevel level, UUID owner, String name, BlockPos first, BlockPos second,
                                 Path journal) {
        this.level = level;
        this.owner = owner;
        if (!name.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("区域名称格式错误");
        this.name = name;
        min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
        max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
        long x = (long) max.getX() - min.getX() + 1, y = (long) max.getY() - min.getY() + 1, z = (long) max.getZ() - min.getZ() + 1;
        if (x > 96 || y > 64 || z > 96 || x * y * z > MAX_VOLUME) throw new IllegalArgumentException("区域超过65536格或96×64×96边长上限");
        this.journal = journal;
        loaded();
    }

    private void thread() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("世界操作必须在服务端主线程");
    }

    private void loaded() {
        thread();
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) throw new IllegalArgumentException("超出世界高度");
        if (!level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max)) throw new IllegalArgumentException("超出世界边界");
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, min.getY(), z << 4))) throw new IllegalArgumentException("区域区块未加载");
            }
        }
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    public boolean overlaps(BuildingEditWorkspace other) {
        return other.level == level && min.getX() <= other.max.getX() && max.getX() >= other.min.getX()
                && min.getY() <= other.max.getY() && max.getY() >= other.min.getY()
                && min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
    }

    public JsonObject status() {
        thread();
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("min", vector(min)); result.add("max", vector(max));
        result.addProperty("revision", revision);
        result.addProperty("busy", pending != null);
        if (pending != null) result.add("job", pending.json());
        return result;
    }

    public JsonObject read() {
        loaded(); idle();
        Map<BlockPos, Cell> cells = new LinkedHashMap<>();
        JsonArray blocks = new JsonArray();
        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            BlockPos p = cursor.immutable();
            Cell cell = capture(p);
            cells.put(p, cell);
            if (!cell.state.isAir()) {
                JsonObject entry = encode(cell.state);
                entry.add("pos", vector(p)); entry.addProperty("fingerprint", cell.fingerprint());
                entry.addProperty("block_entity_protected", !unused(p, cell));
                blocks.add(entry);
            }
        }
        snapshot = cells;
        snapshotId = UUID.randomUUID().toString();
        JsonObject result = status();
        result.addProperty("snapshot_id", snapshotId);
        result.addProperty("air_fingerprint", new Cell(Blocks.AIR.defaultBlockState(), null).fingerprint());
        result.addProperty("air_omitted", true);
        result.add("blocks", blocks);
        return result;
    }

    private void idle() {
        if (pending != null) throw new IllegalArgumentException("区域正在修改，先查询任务状态");
    }

    public JsonObject patch(JsonObject request, ServerPlayer actor) throws IOException {
        loaded();
        String id = requiredString(request, "request_id");
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("request_id格式错误");
        String requestHash = hash(request.toString());
        if (jobs.containsKey(id)) {
            Job old = jobs.get(id);
            if (!old.requestHash.equals(requestHash)) throw new IllegalArgumentException("同一request_id的内容不同");
            return old.json();
        }
        idle();
        if (jobs.size() >= MAX_JOBS) throw new IllegalArgumentException("本区域已达到64次修改，请关闭并重新授权区域");
        if (snapshot.isEmpty() || !snapshotId.equals(requiredString(request, "snapshot_id"))
                || request.get("expected_revision").getAsLong() != revision) throw new IllegalArgumentException("快照或修订过期，请重新读取");
        JsonArray operations = request.getAsJsonArray("operations");
        if (operations == null || operations.isEmpty() || operations.size() > MAX_CHANGES) throw new IllegalArgumentException("一次修改需要1至8192个方块");
        Map<BlockPos, Change> changes = new LinkedHashMap<>();
        for (JsonElement element : operations) {
            JsonObject op = element.getAsJsonObject();
            BlockPos p = position(op.getAsJsonArray("pos"));
            if (!contains(p)) throw new IllegalArgumentException("修改超出授权区域：" + p.toShortString());
            Cell before = snapshot.get(p);
            if (before == null || !before.fingerprint().equals(requiredString(op, "expected"))) throw new IllegalArgumentException("方块快照不匹配：" + p.toShortString());
            Cell current = capture(p);
            if (!before.equals(current)) throw new IllegalArgumentException("玩家或世界已改动：" + p.toShortString());
            BlockState next = parseState(op);
            if (changes.put(p, new Change(p, before, next)) != null) throw new IllegalArgumentException("重复方块坐标");
        }
        List<List<Change>> groups = group(changes);
        var protectedBounds = protectedBounds();
        for (Change change : changes.values()) preflight(change, null, protectedBounds);
        boolean dry = request.has("dry_run") && request.get("dry_run").getAsBoolean();
        Job job = new Job(id, requestHash, groups);
        if (dry) { job.state = "validated"; return job.json(); }
        // Write the complete before/after plan before the first mutation. Failure means no world writes.
        persist(job);
        jobs.put(id, job);
        if (job.groups.isEmpty()) job.state = "complete";
        else pending = job;
        snapshot = Map.of(); snapshotId = "";
        revision++;
        return job.json();
    }

    private List<Bounds> protectedBounds() {
        List<Bounds> result = new ArrayList<>();
        for (var settlement : SettlementSavedData.get(level).settlements()) {
            for (var building : settlement.buildings()) result.add(new Bounds(building.minCorner(), building.maxCorner()));
            for (var blueprint : settlement.blueprints()) {
                var template = BlueprintTemplates.create(blueprint);
                result.add(new Bounds(template.minCorner(), template.maxCorner()));
            }
        }
        return result;
    }

    private void preflight(Change change, ServerPlayer actor, List<Bounds> protectedBounds) {
        if (!contains(change.pos) || !level.hasChunkAt(change.pos)) throw new IllegalArgumentException("越界或区块未加载");
        if (!change.before.equals(capture(change.pos))) throw new IllegalArgumentException("玩家或世界已改动：" + change.pos.toShortString());
        if (change.before.state.equals(change.next)) return;
        String oldId = BuiltInRegistries.BLOCK.getKey(change.before.state.getBlock()).getNamespace();
        if (!oldId.equals("minecraft")) throw new IllegalArgumentException("模组功能方块不可修改");
        parseState(encode(change.before.state));
        if (!unused(change.pos, change.before)) throw new IllegalArgumentException("容器或方块实体含有玩家数据：" + change.pos.toShortString());
        // Formal settlement buildings must be cloned to a design site, not silently invalidated.
        for (Bounds bounds : protectedBounds) if (inside(change.pos, bounds.min, bounds.max))
            throw new IllegalArgumentException("正式建筑或施工蓝图受保护，请编辑空地设计副本");
        if (!change.next.isAir() && !level.isUnobstructed(change.next, change.pos,
                net.minecraft.world.phys.shapes.CollisionContext.empty())) throw new IllegalArgumentException("放置位置有实体：" + change.pos.toShortString());
        if (actor != null && !change.before.state.isAir()) {
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, change.pos, change.before.state, actor);
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) throw new IllegalArgumentException("保护事件拒绝破坏：" + change.pos.toShortString());
        }
    }

    private static boolean inside(BlockPos p, BlockPos a, BlockPos b) {
        return p.getX() >= a.getX() && p.getX() <= b.getX() && p.getY() >= a.getY() && p.getY() <= b.getY() && p.getZ() >= a.getZ() && p.getZ() <= b.getZ();
    }

    /** Group old and new paired blocks; no tick boundary can leave half a door or bed. */
    private List<List<Change>> group(Map<BlockPos, Change> changes) {
        Map<BlockPos, Set<BlockPos>> edges = new HashMap<>();
        for (Change change : changes.values()) {
            edges.computeIfAbsent(change.pos, p -> new HashSet<>());
            for (BlockState state : List.of(change.before.state, change.next)) {
                if (!BlueprintPlacementGroups.isPaired(state)) continue;
                BlockPos partner = BlueprintPlacementGroups.partnerPosition(new BlueprintPlacement(change.pos, state, null));
                Change other = changes.get(partner);
                if (other == null) throw new IllegalArgumentException("门或床需要成对修改");
                edges.get(change.pos).add(partner);
                edges.computeIfAbsent(partner, p -> new HashSet<>()).add(change.pos);
                if (state == change.next) {
                    BlockState counterpart = other.next;
                    if (counterpart.getBlock() != state.getBlock() || BlueprintPlacementGroups.isPrimary(state) == BlueprintPlacementGroups.isPrimary(counterpart)
                            || !BlueprintPlacementGroups.partnerPosition(new BlueprintPlacement(partner, counterpart, null)).equals(change.pos)) throw new IllegalArgumentException("门或床的成对状态无效");
                    if (state.getBlock() instanceof DoorBlock && (state.getValue(DoorBlock.FACING) != counterpart.getValue(DoorBlock.FACING)
                            || state.getValue(DoorBlock.HINGE) != counterpart.getValue(DoorBlock.HINGE)
                            || state.getValue(DoorBlock.OPEN) != counterpart.getValue(DoorBlock.OPEN)
                            || state.getValue(DoorBlock.POWERED) != counterpart.getValue(DoorBlock.POWERED))) throw new IllegalArgumentException("门的成对属性不一致");
                }
            }
        }
        Set<BlockPos> seen = new HashSet<>();
        List<List<Change>> groups = new ArrayList<>();
        for (BlockPos start : changes.keySet()) {
            if (!seen.add(start)) continue;
            List<Change> part = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                BlockPos p = queue.removeFirst(); part.add(changes.get(p));
                for (BlockPos neighbor : edges.get(p)) if (seen.add(neighbor)) queue.add(neighbor);
            }
            if (part.size() > TICK_BUDGET) throw new IllegalArgumentException("成对关联组过大");
            if (part.stream().allMatch(c -> c.before.state.equals(c.next))) continue;
            part.sort(Comparator.comparingInt(c -> c.pos.getY()));
            groups.add(part);
        }
        groups.sort(Comparator.comparingInt((List<Change> g) -> g.stream().allMatch(c -> c.next.isAir()) ? 0 : 1)
                .thenComparingInt(g -> g.stream().allMatch(c -> c.next.isAir()) ? -g.getFirst().pos.getY() : g.getFirst().pos.getY()));
        return groups;
    }

    public int advance(ServerPlayer actor, int budget) throws IOException {
        thread();
        if (pending == null) return 0;
        int used = 0;
        try {
            loaded();
            var protectedBounds = protectedBounds();
            while (pending != null && pending.cursor < pending.groups.size()) {
                List<Change> group = pending.groups.get(pending.cursor);
                if (used + group.size() > Math.min(TICK_BUDGET, budget)) break;
                for (Change change : group) preflight(change, actor, protectedBounds);
                List<BlockSnapshot> saved = new ArrayList<>();
                try {
                    for (Change change : group) {
                        saved.add(BlockSnapshot.create(level.dimension(), level, change.pos, FLAGS));
                        if (!change.before.state.equals(change.next) && !level.setBlock(change.pos, change.next, FLAGS)) throw new IllegalArgumentException("世界拒绝放置");
                    }
                    for (int i = 0; i < group.size(); i++) {
                        Change change = group.get(i);
                        if (actor != null && !change.next.isAir() && EventHooks.onBlockPlace(actor, saved.get(i), Direction.UP)) throw new IllegalArgumentException("保护事件拒绝放置");
                    }
                } catch (Exception failure) {
                    for (int i = saved.size() - 1; i >= 0; i--) saved.get(i).restore(FLAGS);
                    for (Change change : group) level.sendBlockUpdated(change.pos, change.next, level.getBlockState(change.pos), Block.UPDATE_CLIENTS);
                    throw failure;
                }
                for (Change change : group) {
                    level.sendBlockUpdated(change.pos, change.before.state, change.next, Block.UPDATE_CLIENTS);
                    pending.after.put(change.pos, capture(change.pos));
                }
                pending.cursor++; used += group.size();
                if (pending.cursor == pending.groups.size()) {
                    pending.state = "complete";
                    if (!pending.undoOf.isEmpty()) { Job original = jobs.get(pending.undoOf); original.undone = true; persist(original); }
                    persist(pending); pending = null;
                }
            }
            if (pending != null && used > 0) persist(pending);
        } catch (Exception failure) {
            if (pending != null) {
                pending.state = "conflict";
                pending.error = String.valueOf(failure.getMessage());
                persist(pending); pending = null;
            }
        }
        return used;
    }

    public JsonObject undo(String original, JsonObject request, ServerPlayer actor) throws IOException {
        loaded();
        String undoId = requiredString(request, "request_id");
        if (jobs.containsKey(undoId)) {
            Job retry = jobs.get(undoId);
            if (!retry.undoOf.equals(original)) throw new IllegalArgumentException("撤销request_id对应另一任务");
            return retry.json();
        }
        idle();
        Job previous = jobs.get(original);
        if (previous == null || previous.after.isEmpty()) throw new IllegalArgumentException("没有可撤销的已应用修改");
        if (previous.undone) throw new IllegalArgumentException("该修改已撤销");
        // Reuse patch validation and idempotency. The generated body is stable across retries.
        read();
        JsonObject body = new JsonObject(); body.addProperty("request_id", undoId);
        body.addProperty("snapshot_id", snapshotId); body.addProperty("expected_revision", revision);
        JsonArray ops = new JsonArray();
        for (List<Change> group : previous.groups.subList(0, previous.cursor)) {
            for (Change change : group) {
                Cell after = previous.after.get(change.pos);
                if (!after.equals(capture(change.pos))) throw new IllegalArgumentException("撤销将覆盖玩家改动，已拒绝");
                JsonObject op = encode(change.before.state); op.add("pos", vector(change.pos));
                op.addProperty("expected", after.fingerprint()); ops.add(op);
            }
        }
        body.add("operations", ops);
        JsonObject result = patch(body, actor);
        jobs.get(undoId).undoOf = original;
        persist(jobs.get(undoId));
        return result;
    }

    public JsonObject job(String id) {
        thread();
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("未知任务");
        return job.json();
    }

    public void cancel() throws IOException {
        thread();
        if (pending != null) { pending.state = "cancelled"; persist(pending); pending = null; }
    }

    private Cell capture(BlockPos p) {
        BlockEntity entity = level.getBlockEntity(p);
        return new Cell(level.getBlockState(p), entity == null ? null : entity.saveWithoutMetadata(level.registryAccess()).copy());
    }

    private boolean unused(BlockPos p, Cell cell) {
        if (cell.data == null) return true;
        if (!(cell.state.getBlock() instanceof EntityBlock block)) return false;
        BlockEntity fresh = block.newBlockEntity(p, cell.state);
        return fresh != null && fresh.saveWithoutMetadata(level.registryAccess()).equals(cell.data);
    }

    private void persist(Job job) throws IOException {
        Files.createDirectories(journal);
        JsonObject record = job.json(); record.add("region", status());
        JsonArray changes = new JsonArray();
        for (List<Change> group : job.groups) for (Change change : group) {
            JsonObject item = new JsonObject(); item.add("pos", vector(change.pos));
            item.add("before", encode(change.before.state)); item.add("after", encode(change.next));
            if (change.before.data != null) item.addProperty("before_nbt", change.before.data.toString());
            item.addProperty("applied", job.after.containsKey(change.pos)); changes.add(item);
        }
        record.add("changes", changes);
        Path target = journal.resolve(job.id + ".json"), temporary = journal.resolve(job.id + ".json.tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(record), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static JsonObject encode(BlockState state) {
        JsonObject result = new JsonObject(); result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getValues().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getName())).forEach(e -> properties.addProperty(e.getKey().getName(), propertyName(e.getKey(), e.getValue())));
        result.add("state", properties); return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property property, Comparable value) { return property.getName(value); }

    public static BlockState parseState(JsonObject entry) {
        ResourceLocation id = ResourceLocation.parse(requiredString(entry, "block"));
        if (!id.getNamespace().equals("minecraft") || !BuiltInRegistries.BLOCK.containsKey(id) || FORBIDDEN.contains(id.getPath())) throw new IllegalArgumentException("未知或非建筑用方块：" + id);
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        if (entry.has("state")) for (var property : entry.getAsJsonObject("state").entrySet()) {
            Property<?> key = state.getBlock().getStateDefinition().getProperty(property.getKey());
            if (key == null) throw new IllegalArgumentException("未知方块属性");
            state = set(state, key, property.getValue().getAsString());
        }
        if (!state.getFluidState().isEmpty() || state.getBlock() instanceof FallingBlock || state.getBlock() instanceof DoublePlantBlock
                || state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof BasePressurePlateBlock)
            throw new IllegalArgumentException("流体、重力方块或双格植物不允许直接编辑");
        return state;
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("无效方块属性值")));
    }

    public static String requiredString(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("缺少字符串：" + key);
        return o.get(key).getAsString();
    }

    public static BlockPos position(JsonArray p) {
        if (p == null || p.size() != 3) throw new IllegalArgumentException("坐标必须有三个整数");
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) { v[i] = p.get(i).getAsBigDecimal().intValueExact(); }
        return new BlockPos(v[0], v[1], v[2]);
    }

    public static JsonArray vector(BlockPos p) { JsonArray a = new JsonArray(); a.add(p.getX()); a.add(p.getY()); a.add(p.getZ()); return a; }
    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private record Cell(BlockState state, CompoundTag data) {
        String fingerprint() { return hash(encode(state).toString() + (data == null ? "" : data.toString())); }
    }
    private record Change(BlockPos pos, Cell before, BlockState next) { }
    private record Bounds(BlockPos min, BlockPos max) { }
    private static final class Job {
        final String id, requestHash;
        final List<List<Change>> groups;
        final Map<BlockPos, Cell> after = new LinkedHashMap<>();
        int cursor;
        String state = "queued", error = "", undoOf = "";
        boolean undone;
        Job(String id, String requestHash, List<List<Change>> groups) { this.id = id; this.requestHash = requestHash; this.groups = groups; }
        JsonObject json() {
            JsonObject result = new JsonObject(); result.addProperty("request_id", id); result.addProperty("state", state);
            result.addProperty("applied", after.size()); result.addProperty("total", groups.stream().mapToInt(List::size).sum());
            result.addProperty("error", error); result.addProperty("undone", undone); result.addProperty("undo_of", undoOf); return result;
        }
    }
}

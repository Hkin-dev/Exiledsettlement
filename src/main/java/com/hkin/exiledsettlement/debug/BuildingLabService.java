package com.hkin.exiledsettlement.debug;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Creative review sites explicitly armed by their owner, separate from settlement simulation. */
public final class BuildingLabService {
    public static final int BLOCKS_PER_TICK = 128;
    private static final Map<MinecraftServer, List<Session>> SESSIONS = new WeakHashMap<>();

    private BuildingLabService() { }

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("config/exiledsettlement/building_lab");
    }

    public static void seedFiles() throws IOException {
        Files.createDirectories(directory());
        for (String id : BuildingLabTemplate.IDS) {
            Path target = directory().resolve(id + ".json");
            if (Files.exists(target)) continue;
            try (InputStream stream = BuildingLabService.class.getResourceAsStream(
                    "/data/exiledsettlement/building_lab/" + id + ".json")) {
                if (stream == null) throw new IOException("缺少内置候选 " + id);
                Files.copy(stream, target);
            }
        }
    }

    public static String start(ServerLevel level, UUID owner, String id, BlockPos origin) throws IOException {
        seedFiles();
        BuildingLabTemplate template = BuildingLabTemplate.load(directory(), id);
        List<Session> sessions = SESSIONS.computeIfAbsent(level.getServer(), ignored -> new ArrayList<>());
        if (sessions.stream().anyMatch(s -> s.owner.equals(owner) && s.id.equals(id))) {
            throw new IllegalArgumentException("这栋候选已有现场；使用 view 或 stop 操作现有现场");
        }
        BlockPos max = origin.offset(template.size().getX() - 1, template.size().getY() - 1,
                template.size().getZ() - 1);
        if (origin.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) {
            throw new IllegalArgumentException("建筑超出世界高度");
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin, max)) {
            if (!level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) {
                throw new IllegalArgumentException("设计范围必须全部位于已加载区块与世界边界内");
            }
            if (!level.getBlockState(pos).isAir()) {
                throw new IllegalArgumentException("现场需要完整空地；占用坐标：" + pos.toShortString());
            }
        }
        if (sessions.stream().anyMatch(s -> s.dimension.equals(level.dimension())
                && intersects(origin, max, s.origin, s.maximum()))) {
            throw new IllegalArgumentException("与其他设计现场重叠");
        }
        Session session = new Session(owner, level.dimension(), id, origin, template.size());
        session.stamp = Files.getLastModifiedTime(directory().resolve(id + ".json")).toMillis();
        queue(level, session, template.at(origin, false));
        sessions.add(session);
        writeStatus(level.getServer());
        return template.name() + "，现场位于 " + origin.toShortString() + "；已开启自动刷新";
    }

    private static boolean intersects(BlockPos a, BlockPos b, BlockPos c, BlockPos d) {
        return a.getX() <= d.getX() && b.getX() >= c.getX() && a.getY() <= d.getY()
                && b.getY() >= c.getY() && a.getZ() <= d.getZ() && b.getZ() >= c.getZ();
    }

    public static String view(ServerLevel level, UUID owner, String id, boolean interior) throws IOException {
        Session session = find(level, owner, id);
        if (!session.pending.isEmpty()) throw new IllegalArgumentException("正在刷新，请稍后切换视图");
        BuildingLabTemplate template = BuildingLabTemplate.load(directory(), id);
        checkSize(session, template);
        queue(level, session, template.at(session.origin, interior));
        session.interior = interior;
        writeStatus(level.getServer());
        return interior ? "正在展示室内剖切" : "正在恢复完整外观";
    }

    public static String stop(ServerLevel level, UUID owner, String id) throws IOException {
        Session session = find(level, owner, id);
        SESSIONS.get(level.getServer()).remove(session);
        writeStatus(level.getServer());
        return "已停止自动刷新；现场建筑原样保留";
    }

    public static String status(ServerLevel level, UUID owner) {
        var entries = SESSIONS.getOrDefault(level.getServer(), List.of()).stream()
                .filter(s -> s.owner.equals(owner) && s.dimension.equals(level.dimension()))
                .map(s -> s.id + " @ " + s.origin.toShortString() + " · "
                        + (s.interior ? "室内" : "完整") + " · 修订 " + s.revision
                        + " · 待放置 " + s.pending.size() + (s.error.isEmpty() ? "" : " · " + s.error))
                .toList();
        return entries.isEmpty() ? "没有开启的建筑设计现场" : String.join("\n", entries);
    }

    private static Session find(ServerLevel level, UUID owner, String id) {
        return SESSIONS.getOrDefault(level.getServer(), List.of()).stream()
                .filter(s -> s.owner.equals(owner) && s.dimension.equals(level.dimension()) && s.id.equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("这栋候选没有开启的设计现场"));
    }

    private static void checkSize(Session session, BuildingLabTemplate template) {
        if (!session.size.equals(template.size())) {
            throw new IllegalArgumentException("尺寸已变化，需要在新空地重新 start，不能扩大已授权现场");
        }
    }

    public static boolean pollChangedCandidate(ServerLevel level, UUID owner, String id) throws IOException {
        Session session = find(level, owner, id);
        if (!session.pending.isEmpty()) return false;
        Path path = directory().resolve(session.id + ".json");
        long stamp = Files.getLastModifiedTime(path).toMillis();
        if (stamp == session.stamp) return false;
        session.stamp = stamp;
        BuildingLabTemplate template = BuildingLabTemplate.load(directory(), session.id);
        checkSize(session, template);
        queue(level, session, template.at(session.origin, session.interior));
        return true;
    }

    /** Parse first and preflight every changed cell before queueing any mutation. */
    private static void queue(ServerLevel level, Session session, Map<BlockPos, BlockState> rawDesired) {
        Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        rawDesired.forEach((pos, state) -> {
            BlockState resolved = state;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = pos.relative(direction);
                resolved = resolved.updateShape(direction,
                        rawDesired.getOrDefault(neighbor, Blocks.AIR.defaultBlockState()), level, pos, neighbor);
            }
            desired.put(pos, resolved);
        });
        Set<BlockPos> union = new java.util.HashSet<>(session.applied.keySet());
        union.addAll(desired.keySet());
        List<Change> changes = new ArrayList<>();
        for (BlockPos pos : union) {
            BlockState next = desired.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            BlockState previous = session.applied.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            if (previous.equals(next)) continue;
            String conflict = conflict(level, session, pos, previous);
            if (conflict != null) throw new IllegalArgumentException(conflict);
            changes.add(new Change(pos, previous, next));
        }
        changes.sort(Comparator.comparingInt((Change c) -> c.next.isAir() ? 0 : 1)
                .thenComparingInt(c -> c.next.isAir() ? -c.pos.getY() : c.pos.getY()));
        session.pending.addAll(changes);
        session.error = "";
        if (changes.isEmpty()) session.revision++;
    }

    private static String conflict(ServerLevel level, Session session, BlockPos pos, BlockState previous) {
        if (!level.hasChunkAt(pos)) return "区块未加载，刷新暂停：" + pos.toShortString();
        if (!sameEditableState(previous, level.getBlockState(pos))) {
            return "发现玩家改动，刷新暂停：" + pos.toShortString();
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            CompoundTag expected = session.entities.get(pos);
            if (expected == null || !expected.equals(entity.saveWithoutMetadata(level.registryAccess()))) {
                return "容器或方块数据已被使用，刷新暂停：" + pos.toShortString();
            }
        }
        return null;
    }

    private static boolean sameEditableState(BlockState expected, BlockState current) {
        if (expected.getBlock() != current.getBlock()) return false;
        for (var entry : expected.getValues().entrySet()) {
            if (Set.of("open", "powered", "lit").contains(entry.getKey().getName())) continue;
            if (!entry.getValue().equals(current.getValues().get(entry.getKey()))) return false;
        }
        return true;
    }

    public static void tick(MinecraftServer server) {
        List<Session> sessions = SESSIONS.get(server);
        if (sessions == null || sessions.isEmpty()) return;
        int budget = BLOCKS_PER_TICK;
        boolean changed = false;
        for (Session session : sessions) {
            ServerLevel level = server.getLevel(session.dimension);
            ServerPlayer owner = server.getPlayerList().getPlayer(session.owner);
            if (level == null || owner == null || !owner.isCreative() || !owner.hasPermissions(2)
                    || owner.serverLevel() != level) continue;
            if (session.pending.isEmpty() && server.getTickCount() % 20 == 0) {
                try {
                    changed |= pollChangedCandidate(level, session.owner, session.id);
                } catch (Exception failure) {
                    String error = String.valueOf(failure.getMessage());
                    if (!error.equals(session.error)) {
                        session.error = error;
                        owner.sendSystemMessage(Component.literal("建筑设计现场：" + error));
                        changed = true;
                    }
                }
            }
            int used = advancePending(level, session, budget);
            budget -= used;
            changed |= used > 0 && (session.pending.isEmpty() || !session.error.isEmpty());
        }
        if (changed) {
            try { writeStatus(server); } catch (IOException ignored) { }
        }
    }

    /** The same bounded mutation loop is used by runtime and isolated server regression tests. */
    public static int advancePending(ServerLevel level, UUID owner, String id, int requestedBudget) {
        return advancePending(level, find(level, owner, id), Math.max(0, Math.min(BLOCKS_PER_TICK, requestedBudget)));
    }

    private static int advancePending(ServerLevel level, Session session, int budget) {
        int used = 0;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.owner);
        while (used < budget && !session.pending.isEmpty()) {
                Change change = session.pending.peekFirst();
                String conflict = conflict(level, session, change.pos, change.previous);
                if (conflict != null) {
                    session.error = conflict;
                    session.pending.clear();
                    if (owner != null) owner.sendSystemMessage(Component.literal("建筑设计现场：" + conflict));
                    break;
                }
                if (!level.setBlock(change.pos, change.next, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                    session.error = "世界拒绝放置，刷新暂停：" + change.pos.toShortString();
                    session.pending.clear();
                    break;
                }
                if (change.next.isAir()) {
                    session.applied.remove(change.pos);
                    session.entities.remove(change.pos);
                } else {
                    session.applied.put(change.pos, change.next);
                    BlockEntity entity = level.getBlockEntity(change.pos);
                    if (entity != null) session.entities.put(change.pos,
                            entity.saveWithoutMetadata(level.registryAccess()));
                    else session.entities.remove(change.pos);
                }
                session.pending.removeFirst();
                used++;
                if (session.pending.isEmpty()) {
                    session.revision++;
                    if (owner != null) owner.sendSystemMessage(Component.literal("建筑设计现场已刷新：" + session.id
                            + " · 修订 " + session.revision));
                }
            }
        return used;
    }

    private static void writeStatus(MinecraftServer server) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("protocol_version", 1);
        var array = new com.google.gson.JsonArray();
        for (Session session : SESSIONS.getOrDefault(server, List.of())) {
            JsonObject item = new JsonObject();
            item.addProperty("id", session.id);
            item.addProperty("owner", session.owner.toString());
            item.addProperty("dimension", session.dimension.location().toString());
            item.addProperty("origin", session.origin.toShortString());
            item.addProperty("view", session.interior ? "interior" : "full");
            item.addProperty("revision", session.revision);
            item.addProperty("pending_blocks", session.pending.size());
            item.addProperty("applied_blocks", session.applied.size());
            item.addProperty("error", session.error);
            array.add(item);
        }
        root.add("sessions", array);
        Files.createDirectories(directory());
        Path temporary = directory().resolve("status.json.tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        Files.move(temporary, directory().resolve("status.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void shutdown(MinecraftServer server) {
        SESSIONS.remove(server);
        try { writeStatus(server); } catch (IOException ignored) { }
    }

    private static final class Session {
        final UUID owner;
        final ResourceKey<Level> dimension;
        final String id;
        final BlockPos origin;
        final BlockPos size;
        final Map<BlockPos, BlockState> applied = new HashMap<>();
        final Map<BlockPos, CompoundTag> entities = new HashMap<>();
        final Deque<Change> pending = new ArrayDeque<>();
        boolean interior;
        long stamp;
        int revision;
        String error = "";

        Session(UUID owner, ResourceKey<Level> dimension, String id, BlockPos origin, BlockPos size) {
            this.owner = owner;
            this.dimension = dimension;
            this.id = id;
            this.origin = origin.immutable();
            this.size = size;
        }

        BlockPos maximum() { return origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1); }
    }

    private record Change(BlockPos pos, BlockState previous, BlockState next) { }
}

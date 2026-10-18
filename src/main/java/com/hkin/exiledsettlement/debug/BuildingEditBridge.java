package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly enabled loopback-only HTTP bridge. World access is always scheduled on the server. */
public final class BuildingEditBridge implements AutoCloseable {
    private static final Map<MinecraftServer, BuildingEditBridge> ACTIVE = new IdentityHashMap<>();
    public static final int MAX_BODY = 2_000_000;
    private final MinecraftServer server;
    private final UUID owner;
    private final String token;
    private final String session = UUID.randomUUID().toString();
    private final HttpServer listener;
    private final ExecutorService workers;
    private final Map<String, BuildingEditWorkspace> regions = new LinkedHashMap<>();
    private volatile boolean closed;

    private BuildingEditBridge(ServerPlayer player) throws IOException {
        server = player.getServer(); owner = player.getUUID();
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "Exiled-building-api"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        listener.setExecutor(workers); listener.createContext("/", this::handle);
        try {
            writeConnection(); listener.start();
        } catch (IOException | RuntimeException failure) {
            listener.stop(0); workers.shutdownNow(); throw failure;
        }
    }

    public static Path directory() { return FMLPaths.GAMEDIR.get().resolve("config/exiledsettlement/building_api"); }

    public static BuildingEditBridge enable(ServerPlayer player) throws IOException {
        requirePlayer(player);
        BuildingEditBridge current = ACTIVE.get(player.getServer());
        if (current != null && !current.owner.equals(player.getUUID())) throw new IllegalArgumentException("接口已由另一位玩家开启");
        if (current == null) {
            current = new BuildingEditBridge(player); ACTIVE.put(player.getServer(), current);
        }
        return current;
    }

    public static void disable(ServerPlayer player) throws IOException {
        requirePlayer(player);
        BuildingEditBridge bridge = ACTIVE.get(player.getServer());
        if (bridge == null) return;
        if (!bridge.owner.equals(player.getUUID())) throw new IllegalArgumentException("只能关闭自己的编辑接口");
        bridge.close(); ACTIVE.remove(player.getServer());
    }

    public static void shutdown(MinecraftServer server) {
        BuildingEditBridge bridge = ACTIVE.remove(server);
        if (bridge != null) try { bridge.close(); } catch (IOException ignored) { }
    }

    public static void tick(MinecraftServer server) {
        BuildingEditBridge bridge = ACTIVE.get(server);
        if (bridge == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(bridge.owner);
        if (player == null || !player.isCreative() || !player.hasPermissions(2)) {
            shutdown(server); return;
        }
        int budget = BuildingEditWorkspace.TICK_BUDGET;
        for (BuildingEditWorkspace region : bridge.regions.values()) {
            if (region.level != player.serverLevel()) continue;
            try { budget -= region.advance(player, budget); }
            catch (IOException failure) { player.sendSystemMessage(net.minecraft.network.chat.Component.literal("建筑API记录失败，已关闭接口")); shutdown(server); return; }
            if (budget <= 0) break;
        }
    }

    public void authorize(ServerPlayer player, String name, BlockPos first, BlockPos second) throws IOException {
        if (!player.getUUID().equals(owner)) throw new IllegalArgumentException("区域所有者不匹配");
        requirePlayer(player);
        if (regions.size() >= 8 || regions.containsKey(name)) throw new IllegalArgumentException("区域已存在或超过8个，先revoke");
        BuildingEditWorkspace region = new BuildingEditWorkspace(player.serverLevel(), owner, name, first, second,
                directory().resolve("journal").resolve(session).resolve(name));
        if (regions.values().stream().anyMatch(region::overlaps)) throw new IllegalArgumentException("不能授权重叠区域");
        regions.put(name, region);
    }

    public void revoke(String name) throws IOException {
        BuildingEditWorkspace region = region(name); region.cancel(); regions.remove(name);
    }

    public String summary() { return "本机建筑API已开启；授权区域：" + String.join(", ", regions.keySet()) + "。密钥仅存本机连接文件，不在聊天显示。"; }

    private static void requirePlayer(ServerPlayer player) {
        if (!player.isCreative() || !player.hasPermissions(2)) throw new IllegalArgumentException("需要创造模式与作弊/管理员权限");
    }

    private ServerPlayer actor() {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) throw new IllegalArgumentException("区域所有者不在线");
        requirePlayer(player); return player;
    }

    private BuildingEditWorkspace region(String name) {
        BuildingEditWorkspace region = regions.get(name);
        if (region == null) throw new IllegalArgumentException("区域未授权");
        return region;
    }

    private void writeConnection() throws IOException {
        Files.createDirectories(directory());
        JsonObject descriptor = new JsonObject(); descriptor.addProperty("protocol_version", 1);
        descriptor.addProperty("url", "http://127.0.0.1:" + listener.getAddress().getPort());
        descriptor.addProperty("token", token); descriptor.addProperty("session", session); descriptor.addProperty("owner", owner.toString());
        Path temporary = directory().resolve("connection.json.tmp");
        Files.writeString(temporary, descriptor.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, directory().resolve("connection.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** No CORS and no unauthenticated endpoint; reject browser-origin and DNS-rebinding requests. */
    public static boolean authorized(String provided, String expected, String host, int port, String origin) {
        return origin == null && ("127.0.0.1:" + port).equals(host)
                && provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), ("Bearer " + expected).getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (closed || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !authorized(exchange.getRequestHeaders().getFirst("Authorization"), token,
                    exchange.getRequestHeaders().getFirst("Host"), listener.getAddress().getPort(), exchange.getRequestHeaders().getFirst("Origin"))) {
                reply(exchange, 403, error("接口未授权")); return;
            }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("POST")) { reply(exchange, 405, error("只支持GET/POST")); return; }
            JsonObject body = new JsonObject();
            if (method.equals("POST")) {
                String content = exchange.getRequestHeaders().getFirst("Content-Type");
                if (content == null || !content.toLowerCase(Locale.ROOT).startsWith("application/json")) { reply(exchange, 415, error("需要application/json")); return; }
                byte[] data = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
                if (data.length > MAX_BODY) { reply(exchange, 413, error("请求超过2MB")); return; }
                body = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            JsonObject request = body;
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            server.execute(() -> {
                // Paused-world timeout must not become a late, unseen write on resume.
                if (future.isCancelled()) return;
                try {
                    if (closed) throw new IllegalArgumentException("接口已关闭");
                    future.complete(dispatch(method, exchange.getRequestURI(), request));
                } catch (Exception failure) { future.completeExceptionally(failure); }
            });
            try { reply(exchange, 200, future.get(10, TimeUnit.SECONDS)); }
            catch (TimeoutException failure) { future.cancel(false); reply(exchange, 503, error("游戏暂停或无响应；请恢复游戏，用相同request_id查询/重试")); }
            catch (ExecutionException failure) { reply(exchange, 409, error(String.valueOf(failure.getCause().getMessage()))); }
            catch (InterruptedException failure) { future.cancel(false); Thread.currentThread().interrupt(); reply(exchange, 503, error("接口已中断")); }
        } catch (IllegalArgumentException | JsonParseException failure) {
            reply(exchange, 400, error("请求格式错误"));
        } finally { exchange.close(); }
    }

    private JsonObject dispatch(String method, URI uri, JsonObject request) throws IOException {
        ServerPlayer player = actor();
        String path = uri.getPath();
        if (method.equals("GET") && path.equals("/v1/status")) {
            JsonObject result = new JsonObject(); result.addProperty("protocol_version", 1); result.addProperty("session", session);
            result.add("player_position", BuildingEditWorkspace.vector(player.blockPosition()));
            result.addProperty("dimension", player.serverLevel().dimension().location().toString());
            var hit = player.pick(64, 0, false);
            if (hit instanceof BlockHitResult block) result.add("looked_at", BuildingEditWorkspace.vector(block.getBlockPos()));
            JsonArray scopes = new JsonArray(); regions.values().forEach(r -> scopes.add(r.status())); result.add("regions", scopes); return result;
        }
        if (method.equals("GET") && path.equals("/v1/catalog")) return catalog();
        if (method.equals("GET") && path.equals("/v1/template")) return template(uri);
        String[] segments = path.split("/");
        if (segments.length < 4 || !segments[1].equals("v1") || !segments[2].equals("regions")) throw new IllegalArgumentException("未知API路径");
        BuildingEditWorkspace region = region(segments[3]);
        if (region.level != player.serverLevel()) throw new IllegalArgumentException("玩家不在区域维度");
        if (method.equals("GET") && segments.length == 4) return region.status();
        if (method.equals("GET") && segments.length == 5 && segments[4].equals("snapshot")) return region.read();
        if (method.equals("POST") && segments.length == 5 && segments[4].equals("patch")) return region.patch(request, player);
        if (method.equals("GET") && segments.length == 6 && segments[4].equals("jobs")) return region.job(segments[5]);
        if (method.equals("POST") && segments.length == 7 && segments[4].equals("jobs") && segments[6].equals("undo")) return region.undo(segments[5], request, player);
        throw new IllegalArgumentException("未知API路径");
    }

    public static JsonObject catalog() {
        JsonObject result = new JsonObject(); JsonArray items = new JsonArray();
        for (BuildingType type : BuildingType.values()) {
            JsonObject item = new JsonObject(); item.addProperty("type", type.serializedName()); item.addProperty("translation_key", type.translationKey());
            item.addProperty("category", type.category().name().toLowerCase(Locale.ROOT)); item.addProperty("template_version", BlueprintTemplates.currentTemplateVersion(type));
            item.addProperty("dynamic_area", type.isDynamicArea()); item.addProperty("template_available", BlueprintTemplates.supports(type));
            item.addProperty("region_edit_supported", true); items.add(item);
        }
        result.add("buildings", items); result.addProperty("count", items.size()); return result;
    }

    public static JsonObject template(URI uri) {
        Map<String, String> query = new HashMap<>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            String[] item = pair.split("=", 2);
            if (item.length != 2) throw new IllegalArgumentException("查询参数错误");
            query.put(URLDecoder.decode(item[0], StandardCharsets.UTF_8), URLDecoder.decode(item[1], StandardCharsets.UTF_8));
        }
        BuildingType type = BuildingType.fromSerializedName(query.getOrDefault("type", ""));
        if (type == null || !BlueprintTemplates.supports(type)) throw new IllegalArgumentException("该类型没有方块模板，请读取现场副本");
        int version = Integer.parseInt(query.getOrDefault("version", String.valueOf(BlueprintTemplates.currentTemplateVersion(type))));
        if (version < 1 || version > BlueprintTemplates.currentTemplateVersion(type)) throw new IllegalArgumentException("模板版本无效");
        Direction facing = Direction.byName(query.getOrDefault("facing", "north"));
        if (facing == null || !facing.getAxis().isHorizontal()) throw new IllegalArgumentException("朝向无效");
        int width = Integer.parseInt(query.getOrDefault("width", String.valueOf(Math.max(9, type.minimumAreaSize()))));
        int depth = Integer.parseInt(query.getOrDefault("depth", String.valueOf(Math.max(9, type.minimumAreaSize()))));
        if (width < 1 || depth < 1 || width > 48 || depth > 48) throw new IllegalArgumentException("动态区域尺寸无效");
        var template = type.isDynamicArea() ? BlueprintTemplates.createArea(type, BlockPos.ZERO, new BlockPos(width - 1, 0, depth - 1), facing, version)
                : BlueprintTemplates.create(type, BlockPos.ZERO, facing, version);
        JsonObject result = new JsonObject(); result.addProperty("type", type.serializedName()); result.addProperty("template_version", version);
        result.addProperty("design_only", true); result.add("source_min", BuildingEditWorkspace.vector(template.minCorner()));
        result.add("source_max", BuildingEditWorkspace.vector(template.maxCorner()));
        Map<BlockPos, BlockStateHolder> finalStates = new LinkedHashMap<>();
        template.placements().forEach(p -> finalStates.put(p.position(), new BlockStateHolder(p.state())));
        JsonArray blocks = new JsonArray();
        finalStates.forEach((p, holder) -> {
            JsonObject entry = BuildingEditWorkspace.encode(holder.state); entry.add("pos", BuildingEditWorkspace.vector(p));
            entry.addProperty("editable", entry.get("block").getAsString().startsWith("minecraft:")); blocks.add(entry);
        });
        result.add("blocks", blocks); return result;
    }

    private record BlockStateHolder(net.minecraft.world.level.block.state.BlockState state) { }
    private static JsonObject error(String message) { JsonObject result = new JsonObject(); result.addProperty("error", message); return result; }
    private static void reply(HttpExchange exchange, int code, JsonObject result) throws IOException {
        byte[] data = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, data.length); exchange.getResponseBody().write(data);
    }

    @Override public void close() throws IOException {
        closed = true; listener.stop(0); workers.shutdownNow();
        IOException failed = null;
        for (BuildingEditWorkspace region : regions.values()) try { region.cancel(); } catch (IOException e) { failed = e; }
        regions.clear(); Files.deleteIfExists(directory().resolve("connection.json"));
        if (failed != null) throw failed;
    }
}

package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Observation only: never changes blocks, inventory, citizen tasks or world time. */
public final class SaveAcceptanceService {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String SESSION = UUID.randomUUID().toString();
    private static final Map<MinecraftServer, Watch> WATCHES = new IdentityHashMap<>();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("exiled-save-acceptance", Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<MinecraftServer, Map<ServerLevel, Set<ChunkPos>>> LOADS = new IdentityHashMap<>();
    private record Watch(ServerLevel level, UUID settlement, int until, int interval, Path file) { }
    private SaveAcceptanceService() { }
    public static boolean enabled() { return Boolean.getBoolean("exiledsettlement.testHarness"); }
    public static Path directory() { return FMLPaths.GAMEDIR.get().resolve("test-results"); }
    public static Path file(String name, String extension) {
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("INVALID_REPORT_NAME");
        return directory().resolve(name + extension);
    }
    public static Path export(ServerLevel level, SettlementRecord settlement, String name) throws IOException {
        Path file = file(name, ".json");
        Files.createDirectories(directory());
        var snapshot = snapshot(level, settlement);
        if(Files.exists(file))throw new FileAlreadyExistsException(file.toString());
        var partial=Files.createTempFile(directory(),name+"-",".partial");
        try{
            // Do not copy the entire historical NBT tree into a second JSON
            // tree and then one giant String. Stream every original field.
            try(var writer=new JsonWriter(Files.newBufferedWriter(partial,StandardCharsets.UTF_8))){
                writer.beginObject();
                for(var entry:snapshot.entrySet()){writer.name(entry.getKey());JSON.toJson(entry.getValue(),writer);}
                writer.name("savedState");writeTag(writer,settlement.save(level.registryAccess()));writer.endObject();
            }
            Files.move(partial,file);
        }finally{Files.deleteIfExists(partial);}
        return file;
    }
    private static void writeTag(JsonWriter writer,Tag tag)throws IOException{
        if(tag instanceof CompoundTag compound){
            writer.beginObject();
            for(var key:compound.getAllKeys()){
                if(compound.contains("ProjectsEncoding") && (key.equals("ProjectsEncoding") || key.equals("ProjectCount") || key.equals("ProjectPages")))continue;
                writer.name(key);writeTag(writer,compound.get(key));
            }
            if(compound.contains("ProjectsEncoding")){
                writer.name("Projects");writer.beginArray();
                // Keep existing evidence readers' exact uncompressed fields, but
                // expand only one small batch at a time into the output stream.
                var pages=compound.getList("ProjectPages",Tag.TAG_COMPOUND);
                if(compound.getInt("ProjectsEncoding")!=1)throw new IOException("Unsupported terrain history encoding");
                int count=0;
                for(int i=0;i<pages.size();i++){
                    var records=com.hkin.exiledsettlement.terrain.TerrainProjectPages.decode(pages.getCompound(i));count+=records.size();
                    for(var record:records)writeTag(writer,record);
                }
                if(count!=compound.getInt("ProjectCount"))throw new IOException("Terrain history count mismatch");
                writer.endArray();
            }
            writer.endObject();
        }else if(tag instanceof ListTag list){
            writer.beginArray();for(var item:list)writeTag(writer,item);writer.endArray();
        }else if(tag instanceof ByteArrayTag array){
            writer.beginArray();for(var value:array.getAsByteArray())writer.value(value);writer.endArray();
        }else if(tag instanceof IntArrayTag array){
            writer.beginArray();for(var value:array.getAsIntArray())writer.value(value);writer.endArray();
        }else if(tag instanceof LongArrayTag array){
            writer.beginArray();for(var value:array.getAsLongArray())writer.value(value);writer.endArray();
        }else if(tag instanceof NumericTag number)writer.value(number.getAsNumber());
        else if(tag instanceof StringTag text)writer.value(text.getAsString());
        else if(tag instanceof EndTag)writer.nullValue();
        else throw new IOException("Unsupported saved tag: "+tag.getId());
    }
    public static void watch(ServerLevel level, SettlementRecord settlement, String name, int ticks, int interval) throws IOException {
        if (WATCHES.containsKey(level.getServer())) throw new IllegalArgumentException("WATCH_ALREADY_RUNNING");
        Path file = file(name, ".jsonl");
        Files.createDirectories(directory());
        Files.writeString(file, JSON.toJson(snapshot(level, settlement)) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        WATCHES.put(level.getServer(), new Watch(level, settlement.id(), level.getServer().getTickCount() + ticks, interval, file));
    }
    public static void stop(MinecraftServer server) { WATCHES.remove(server); }
    public static int load(ServerLevel level, SettlementRecord settlement, int radius) {
        var chunks = LOADS.computeIfAbsent(level.getServer(), key -> new IdentityHashMap<>())
                .computeIfAbsent(level, key -> new HashSet<>());
        var core = new ChunkPos(settlement.corePosition());
        for (int x = core.x - radius; x <= core.x + radius; x++)
            for (int z = core.z - radius; z <= core.z + radius; z++) {
                var chunk = new ChunkPos(x, z);
                if (chunks.add(chunk)) level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk, true);
            }
        return chunks.size();
    }
    public static void unload(MinecraftServer server) {
        var levels = LOADS.remove(server);
        if (levels != null) levels.forEach((level, chunks) -> chunks.forEach(chunk ->
                level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk, true)));
    }
    public static void shutdown(MinecraftServer server) { stop(server); unload(server); }
    public static void action(ServerLevel level, CitizenRecord citizen, String action, BlockPos target, String item, int count) {
        if (!enabled()) return;
        var j = new JsonObject(); j.addProperty("gameTime", level.getGameTime());
        j.addProperty("session", SESSION);
        j.addProperty("citizen", citizen.id().toString()); j.addProperty("name", citizen.name());
        j.addProperty("workplace", string(citizen.workBuildingId())); j.addProperty("action", action);
        j.addProperty("blueprint", string(citizen.targetBlueprintId()));
        var entity = CitizenProjectionService.findProjection(level, citizen);
        j.add("position", pos(entity == null ? citizen.lastPosition() : entity.navigationFeetPosition())); j.add("target", pos(target));
        if (entity != null) { j.addProperty("x", entity.getX()); j.addProperty("y", entity.getY()); j.addProperty("z", entity.getZ()); }
        j.addProperty("item", item); j.addProperty("count", count);
        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve("actions.jsonl"), JSON.toJson(j) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("Acceptance action recording failed", failure); }
    }
    public static void tick(MinecraftServer server) {
        // Chunk tickets alone do not prevent vanilla's 300-tick empty-world entity pause.
        // Keep normal entity physics/AI running only while explicit test loads exist.
        var loaded = LOADS.get(server);
        if (loaded != null) loaded.forEach((level, chunks) -> { if (!chunks.isEmpty()) level.resetEmptyTime(); });
        Watch w = WATCHES.get(server);
        if (w == null) return;
        int tick = server.getTickCount();
        if (tick % w.interval != 0 && tick < w.until) return;
        var s = SettlementSavedData.get(w.level).findById(w.settlement).orElse(null);
        try {
            if (s != null) Files.writeString(w.file, JSON.toJson(snapshot(w.level, s)) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("Save acceptance recording failed", failure);
            WATCHES.remove(server);
        }
        if (s == null || tick >= w.until) WATCHES.remove(server);
    }
    public static JsonObject snapshot(ServerLevel level, SettlementRecord s) {
        var out = new JsonObject();
        out.addProperty("session", SESSION);
        out.addProperty("schema", 1); out.addProperty("settlement", s.id().toString());
        out.addProperty("name", s.name()); out.addProperty("dimension", level.dimension().location().toString());
        out.addProperty("tick", level.getServer().getTickCount()); out.addProperty("gameTime", level.getGameTime());
        out.addProperty("dayTime", level.getDayTime()); out.addProperty("creativeSupplyInstalled", s.creativeCoreInstalled());
        out.addProperty("onlinePlayers", level.getServer().getPlayerCount());
        var loads = LOADS.get(level.getServer());
        out.addProperty("randomTickChunks", loads == null ? 0 : loads.getOrDefault(level, Set.of()).size());
        out.add("settings", JSON.toJsonTree(CommunitySettings.values(s, s.corePosition())));
        var stock=new JsonObject();stock.addProperty("seedReserve",WarehouseStewardService.seedReserve(level,s));
        stock.addProperty("grain",WarehouseStewardService.grain(level,s));stock.addProperty("wheatPaused",s.community().wheatPaused);
        stock.addProperty("status",s.community().stewardStatus);out.add("steward",stock);
        var roads=new JsonObject();roads.addProperty("enabled",s.autoRoads().enabled);roads.addProperty("status",s.autoRoads().status);
        var routes=new JsonArray();
        for(var route:s.autoRoads().routes.values()) {
            var j=new JsonObject();j.addProperty("building",route.building.toString());j.add("entrance",pos(route.entrance));
            j.addProperty("cells",route.cells.size());j.addProperty("progress",route.progress);j.addProperty("completed",route.completed.cardinality());
            j.addProperty("suppressed",route.suppressed);j.addProperty("status",route.status);
            j.addProperty("id",route.id.toString());j.addProperty("adaptiveVersion",route.adaptiveVersion);j.addProperty("reviewAfter",route.reviewAfter);
            j.addProperty("roughness",com.hkin.exiledsettlement.road.AdaptiveRoadService.roughness(route.centerline));
            j.addProperty("crossfall",com.hkin.exiledsettlement.road.AdaptiveRoadService.crossfall(route.centerline,route.cells));
            var center=new JsonArray();route.centerline.forEach(p->center.add(pos(p)));j.add("centerline",center);
            var surfaces=new JsonArray();route.cells.forEach(p->{var cell=new JsonObject();cell.add("position",pos(p));cell.addProperty("loaded",level.hasChunkAt(p));
                if(level.hasChunkAt(p)){cell.addProperty("actual",level.getBlockState(p).toString());cell.addProperty("registered",com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(p));
                    cell.addProperty("surfaceReady",com.hkin.exiledsettlement.road.RoadEarthworkService.surfaceReady(level,s,route,p));
                    cell.addProperty("plannable",com.hkin.exiledsettlement.road.RoadEarthworkService.plannable(level,s,p));
                    cell.addProperty("surfaceTarget",String.valueOf(com.hkin.exiledsettlement.road.RoadEarthworkService.surfaceTarget(level,s,route,p)));
                    var targetOwner=s.terrain().projects.get(s.terrain().cells.get(p));
                    if(targetOwner!=null)cell.addProperty("targetOwner",targetOwner.name+" "+targetOwner.id+" done="+targetOwner.done());
                    cell.addProperty("paidPassage",com.hkin.exiledsettlement.road.AdaptiveRoadService.paidPassage(level,s,p));}surfaces.add(cell);});j.add("surfaces",surfaces);
            var retired=new JsonArray();route.retired.forEach(p->retired.add(pos(p)));j.add("retired",retired);
            var b=s.building(route.building);j.addProperty("entranceValid",b!=null && com.hkin.exiledsettlement.road.AutoRoadService.entranceMatches(level,s,b,route.entrance));
            j.addProperty("accessConnected",route.accessConnected);j.addProperty("accessBlocker",com.hkin.exiledsettlement.road.RoadEarthworkService.accessBlocker(level,s,route));routes.add(j);
        }
        roads.add("routes",routes);
        roads.add("blockers",JSON.toJsonTree(com.hkin.exiledsettlement.road.AutoRoadService.blockers(level,s)));
        roads.add("report",JSON.toJsonTree(com.hkin.exiledsettlement.road.AutoRoadService.report(level,s)));
        for(int i=0;i<routes.size();i++)routes.get(i).getAsJsonObject().addProperty("gradeMismatch",
                com.hkin.exiledsettlement.road.AdaptiveRoadService.gradeMismatch(s,s.autoRoads().routes.values().stream().toList().get(i).cells));
        out.add("roads",roads);
        out.add("publicGround",com.hkin.exiledsettlement.terrain.PublicGroundService.observation(level,s));
        out.addProperty("forceWorkDebug",ForceWorkDebugService.enabled(level,s));
        var residents = new JsonArray();
        for (var c : s.citizens()) {
            var j = new JsonObject(); j.addProperty("id", c.id().toString()); j.addProperty("name", c.name());
            j.addProperty("forceWorkDebug",ForceWorkDebugService.forced(c));j.addProperty("naturalSchedule",com.hkin.exiledsettlement.citizen.CitizenWorkService.naturalSchedulePhase(level.getDayTime(),c).name());
            j.addProperty("alive", c.alive()); j.addProperty("profession", c.profession().name());
            j.addProperty("workplace", string(c.workBuildingId())); j.addProperty("manualWorkplace", string(c.manualWorkBuildingId()));
            j.addProperty("state", c.workState().name()); j.addProperty("activity", c.activity().name());
            j.addProperty("failure", c.workFailure()); j.addProperty("nutrition", c.nutrition());
            j.addProperty("lifeTask",c.life().task);j.addProperty("lifeStage",c.life().cookingStage);
            j.addProperty("lifeDestination",string(c.life().destination));
            j.addProperty("health", c.health()); j.add("position", pos(c.lastPosition())); j.add("target", pos(c.targetPosition()));
            j.addProperty("blueprint", string(c.targetBlueprintId()));
            var task=com.hkin.exiledsettlement.citizen.WorkTaskService.task(s,c);
            j.addProperty("motherTask",string(c.motherTaskId()));
            if(task!=null){j.addProperty("taskMother",string(task.mother()));j.addProperty("taskChild",string(task.child()));j.addProperty("taskPriority",task.priority());j.addProperty("taskPhase",task.phase());}
            j.add("workerAccessBlock",pos(c.workerAccess.block));j.addProperty("workerAccessTicks",c.workerAccess.ticks);
            var entity = CitizenProjectionService.findProjection(level, c);
            j.addProperty("entityLoaded", entity != null);
            if (entity != null) {
                j.addProperty("movementMode",entity.constructionMovementMode());j.addProperty("movementDiagnostic",entity.constructionMovementDiagnostics());
                j.addProperty("sleeping",entity.isSleeping());j.addProperty("home",string(c.homeBuildingId()));j.add("bed",pos(c.bedPosition()));
                j.addProperty("sleepingAtAssignedBed",c.bedPosition()!=null && entity.isPhysicallySleepingAt(c.bedPosition()));
                j.addProperty("entityId", entity.getUUID().toString()); j.addProperty("entityHealth", entity.getHealth());
                j.addProperty("entityTick", entity.tickCount);
                j.addProperty("x", entity.getX()); j.addProperty("y", entity.getY()); j.addProperty("z", entity.getZ());
                j.addProperty("navigationDone", entity.getNavigation().isDone());
                var path = entity.getNavigation().getPath();
                if (path != null) { j.addProperty("pathLength", path.getNodeCount()); j.addProperty("pathIndex", path.getNextNodeIndex()); j.addProperty("pathCanReach", path.canReach()); if(!path.isDone()){var next=path.getNextNode();j.add("pathNext",pos(new net.minecraft.core.BlockPos(next.x,next.y,next.z)));} }
                j.addProperty("schedule", CitizenWorkService.schedulePhase(level.getDayTime(), c).name());
                j.add("feet", pos(entity.navigationFeetPosition())); j.addProperty("navigationDiagnostic",entity.settlementNavigationDiagnostics());
                j.addProperty("onGround", entity.onGround());
                if(c.workState()==com.hkin.exiledsettlement.citizen.CitizenWorkState.TO_BLUEPRINT)j.addProperty("constructionWork",CitizenWorkService.constructionWorkObservation(level,s,c,entity));
                if (c.targetPosition() != null) {
                    var target = c.targetPosition();
                    j.addProperty("targetBlock", BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString());
                    j.addProperty("targetAbove", BuiltInRegistries.BLOCK.getKey(level.getBlockState(target.above()).getBlock()).toString());
                    j.addProperty("targetReach", CitizenInteractionRange.contains(entity, target));
                    j.addProperty("targetVisible", CitizenInteractionRange.visible(entity, target));
                    j.addProperty("roundedEyeVisible", CitizenWorkService.farmInteractionVisible(level, entity.blockPosition(), target, entity));
                    var farm = s.building(c.workBuildingId());
                    if (farm != null && farm.region() != null) j.addProperty("insideRegion", farm.region().contains(entity.navigationFeetPosition()));
                }
            }
            var items = new JsonArray();
            for (int i = 0; i < c.inventory().size(); i++) {
                ItemStack stack = c.inventoryItem(i); if (stack.isEmpty()) continue;
                var item = new JsonObject(); item.addProperty("slot", i);
                item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount()); item.addProperty("damage", stack.getDamageValue());
                items.add(item);
            }
            j.add("inventory", items); residents.add(j);
        }
        out.add("residents", residents);
        var buildings = new JsonArray();
        for (var b : s.buildings()) {
            var j = new JsonObject(); j.addProperty("id", b.id().toString()); j.addProperty("type", b.type().name());
            j.addProperty("status", b.status().name()); j.addProperty("operational", b.operational());
            j.addProperty("target", b.workerTarget()); j.addProperty("capacity", b.workerCapacity());
            j.addProperty("locked", s.lockedWorkers(b.id())); j.addProperty("revision", b.configurationRevision());
            j.addProperty("production", b.productionId().toString()); j.add("min", pos(b.minCorner())); j.add("max", pos(b.maxCorner()));
            if (b.type().isStorage()) {
                var contents = new JsonObject();
                WarehouseInventoryService.summarize(level, b).forEach((item, count) -> contents.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(), count));
                j.add("inventory", contents); j.addProperty("creativeSupply", WarehouseInventoryService.hasCreativeSupply(level, b));
                j.addProperty("loadedContainers", WarehouseInventoryService.loadedContainers(level, b).size());
            }
            if (b.type() == BuildingType.FARM_FIELD || b.type() == BuildingType.MELON_FIELD) j.add("field", field(level, b));
            buildings.add(j);
        }
        out.add("buildings", buildings);
        var plans = new JsonArray();
        for (var b : s.blueprints()) {
            var j = new JsonObject(); j.addProperty("id", b.id().toString()); j.addProperty("type", b.type().name());
            j.addProperty("progressIndex", b.progressIndex()); j.add("anchor", pos(b.anchor()));
            j.addProperty("assignmentMode",b.assignmentMode().name());j.addProperty("assignmentRevision",b.assignmentRevision());
            j.addProperty("constructionAllowed",com.hkin.exiledsettlement.terrain.TerrainService.allowed(s,b));
            j.addProperty("remaining",com.hkin.exiledsettlement.terrain.PublicWorkService.remaining(level,b));
            j.addProperty("constructionMarkers",com.hkin.exiledsettlement.blueprint.BlueprintService.constructionMarkers(level,b).size());
            j.addProperty("foundationBlocker",s.terrain().blockers.getOrDefault(b.id(),""));
            if(b.terrainProject()!=null){var project=b.terrainProject();j.addProperty("terrainName",project.name);j.addProperty("road",project.road);
                j.addProperty("publicGround",project.publicGround);j.addProperty("roadReclaim",project.roadReclaim);j.addProperty("remaining",project.remaining());
                j.addProperty("stepsDone",project.done());j.addProperty("closureReady",com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(level,project));
                j.add("dependencies",JSON.toJsonTree(project.dependencies.stream().map(UUID::toString).toList()));}
            if (b.region() != null) {
                j.addProperty("regionStatus", b.region().status);j.addProperty("farmEntryStatus",com.hkin.exiledsettlement.region.FarmEntranceService.status(b.region())); j.addProperty("prepared", b.region().prepared);
                j.addProperty("farmReviewStatus",b.region().reviewStatus);j.addProperty("farmReviewCursor",b.region().reviewCursor);j.addProperty("farmNextReview",b.region().nextReview);
                j.addProperty("readyCrops", b.region().readyCrops.size()); j.addProperty("retries", b.region().preparationRetries.size());
                j.addProperty("remaining",com.hkin.exiledsettlement.terrain.PublicWorkService.remaining(level,b));
                j.addProperty("dependenciesReady",com.hkin.exiledsettlement.terrain.TerrainService.dependenciesReady(s,b.id()));
            }
            plans.add(j);
        }
        out.add("blueprints", plans); out.add("inventoryLedger", JSON.toJsonTree(s.inventorySample()));
        out.add("producedTotals", JSON.toJsonTree(s.producedTotals())); out.add("consumedTotals", JSON.toJsonTree(s.consumedTotals()));
        return out;
    }
    private static JsonObject field(ServerLevel level, BuildingRecord b) {
        var out = new JsonObject(); var cells = new JsonArray(); var counts = new TreeMap<String, Integer>();
        long volume = ((long)b.maxCorner().getX() - b.minCorner().getX() + 1)
                * ((long)b.maxCorner().getZ() - b.minCorner().getZ() + 1)
                * ((long)b.maxCorner().getY() - b.minCorner().getY() + 8);
        // Diagnostic budget only; explicitly expose truncation rather than reporting a false zero.
        out.addProperty("scanTruncated", volume > 65536); out.addProperty("scanVolume", volume);
        int visited = 0, unloaded = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.minCorner().below(2), b.maxCorner().above(5))) {
            if (++visited > 65536) break;
            if (!level.hasChunkAt(p)) { unloaded++; continue; }
            var state = level.getBlockState(p);
            if (state.isAir()) continue;
            var key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); counts.merge(key, 1, Integer::sum);
            if (state.getBlock() instanceof CropBlock crop || state.is(Blocks.FARMLAND) || state.is(Blocks.SCAFFOLDING)) {
                var j = new JsonObject(); j.add("position", pos(p)); j.addProperty("block", key);
                if (state.getBlock() instanceof CropBlock crop) { j.addProperty("age", crop.getAge(state)); j.addProperty("mature", crop.isMaxAge(state)); }
                if (state.is(Blocks.FARMLAND)) j.addProperty("moisture", state.getValue(FarmBlock.MOISTURE));
                cells.add(j);
            }
        }
        out.addProperty("unloadedPositions", unloaded); out.add("blocks", JSON.toJsonTree(counts)); out.add("cells", cells);
        if (b.region() != null) {
            out.addProperty("readyCrops", b.region().readyCrops.size()); out.addProperty("status", b.region().status);
            var layout=new JsonObject();layout.addProperty("version",b.region().layoutVersion);if(b.region().flatY!=null)layout.addProperty("flatY",b.region().flatY);
            layout.addProperty("gradedColumns",b.region().gradeOrigins.size());
            layout.addProperty("waterFillColumns",b.region().waterFill.size());
            int filledWater=0;for(long key:b.region().waterFill){var c=b.region().cells.get(key);if(c!=null && level.hasChunkAt(c.ground()) && (com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.soil(level.getBlockState(c.ground())) || level.getBlockState(c.ground()).is(Blocks.DIRT_PATH) || c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.WATER && level.getBlockState(c.ground()).is(Blocks.OAK_SLAB)))filledWater++;}
            layout.addProperty("waterFillSurfacePlaced",filledWater);
            var shape=new JsonArray();for(var c:b.region().ordered().stream().limit(65536).toList()) {
                var j=new JsonObject();j.add("position",pos(c.ground()));j.addProperty("use",c.use().name());j.addProperty("waterFill",b.region().waterFill.contains(com.hkin.exiledsettlement.region.ProductionRegion.key(c.ground().getX(),c.ground().getZ())));j.addProperty("actualAbove",level.getBlockState(c.ground().above()).toString());
                j.addProperty("actual",level.hasChunkAt(c.ground())?BuiltInRegistries.BLOCK.getKey(level.getBlockState(c.ground()).getBlock()).toString():"unloaded");shape.add(j);
            }
            layout.add("cells",shape);layout.addProperty("truncated",b.region().cells.size()>65536);
            var spine=new JsonArray();for(long key:b.region().farmPath){var c=b.region().cells.get(key);if(c!=null)spine.add(pos(c.ground()));}layout.add("spine",spine);
            var boundary=new JsonArray();int closed=0,expected=0,pathExpected=0,pathActual=0;
            for(var goal:b.region().template().placements()) {
                if(goal.state().is(Blocks.OAK_FENCE) || goal.state().is(Blocks.OAK_FENCE_GATE)) {
                    expected++;boolean placed=level.hasChunkAt(goal.position()) && com.hkin.exiledsettlement.blueprint.BlueprintService.matchesPlacedState(level.getBlockState(goal.position()),goal.state());if(placed)closed++;
                    var j=new JsonObject();j.add("position",pos(goal.position()));j.addProperty("placed",placed);boundary.add(j);
                }
                if(goal.state().is(Blocks.DIRT_PATH) || goal.state().is(Blocks.COBBLESTONE_STAIRS)) {
                    pathExpected++;if(level.hasChunkAt(goal.position()) && com.hkin.exiledsettlement.blueprint.BlueprintService.matchesPlacedState(level.getBlockState(goal.position()),goal.state()))pathActual++;
                }
            }
            layout.add("boundary",boundary);layout.addProperty("boundaryExpected",expected);layout.addProperty("boundaryPlaced",closed);
            layout.addProperty("pathExpected",pathExpected);layout.addProperty("pathPlaced",pathActual);out.add("layout",layout);
            var irrigation = new JsonArray(); int covered = 0, cropCells = 0;
            for (var cell : b.region().ordered()) {
                var p = cell.ground(); if (!level.hasChunkAt(p)) continue;
                if (cell.use() == com.hkin.exiledsettlement.region.ProductionRegion.Use.WATER) {
                    var j = new JsonObject(); j.add("position", pos(p)); j.addProperty("state", level.getBlockState(p).toString());
                    j.addProperty("wet", level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER));
                    j.addProperty("support", com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,p.below()));
                    var neighbors = new JsonArray(); boolean sealed = true;
                    for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        var n = new JsonObject(); n.add("position", pos(p.relative(d))); n.addProperty("state", level.getBlockState(p.relative(d)).toString()); neighbors.add(n);
                        sealed &= net.minecraft.world.phys.shapes.Shapes.mergedFaceOccludes(level.getBlockState(p).getCollisionShape(level,p),
                                level.getBlockState(p.relative(d)).getCollisionShape(level,p.relative(d)),d);
                    }
                    j.addProperty("sealed",sealed); j.add("neighbors", neighbors); irrigation.add(j);
                }
                if (cell.use() == com.hkin.exiledsettlement.region.ProductionRegion.Use.CROP) {
                    cropCells++; boolean wet = false;
                    for (var water : BlockPos.betweenClosed(p.offset(-4,0,-4),p.offset(4,1,4)))
                        if (level.hasChunkAt(water) && level.getBlockState(p).canBeHydrated(level,p,level.getFluidState(water),water)) { wet = true; break; }
                    if (wet) covered++;
                }
            }
            out.add("irrigation", irrigation); out.addProperty("cropCells", cropCells); out.addProperty("waterCoveredCropCells", covered);
        }
        return out;
    }
    private static String string(UUID id) { return id == null ? "" : id.toString(); }
    private static JsonElement pos(BlockPos p) {
        if (p == null) return JsonNull.INSTANCE;
        var j = new JsonArray(); j.add(p.getX()); j.add(p.getY()); j.add(p.getZ()); return j;
    }
}

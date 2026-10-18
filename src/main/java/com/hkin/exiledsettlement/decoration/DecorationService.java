package com.hkin.exiledsettlement.decoration;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Plans small blueprints only. Actual fetching, placement, protection hooks and repair remain resident work. */
public final class DecorationService {
    private static final Map<ServerLevel, Map<BlockPos, Long>> REMOVALS = new WeakHashMap<>();
    private DecorationService() { }
    public static boolean owned(SettlementRecord s, UUID site) { return s.decorations().owned(site) != null; }
    public static DecorationState.Feature forBuilding(SettlementRecord s, UUID building) {
        if (building == null) return null;
        return s.decorations().features.values().stream().filter(f -> building.equals(f.building)).findFirst().orElse(null);
    }
    public static boolean allowed(SettlementRecord s, DecorationState.Feature f) {
        var state = s.decorations();
        return f == null || state.enabled && state.workReady && (f.building != null || f.siteReady) && !f.suppressed && state.family(f.family) && !state.forbidden(f.anchor)
                && !s.community().evacuation && s.community().policy.equals("normal") && !s.community().danger(f.anchor);
    }
    public static void tickLevel(ServerLevel level) {
        for (var s : SettlementSavedData.get(level).settlements()) if (s.active() && level.hasChunkAt(s.corePosition())) tick(level, s);
    }
    public static void tick(ServerLevel level, SettlementRecord s) {
        var state = s.decorations(); var data = SettlementSavedData.get(level);
        state.newDay(level.getDayTime() / 24000L);
        boolean ordinaryConstruction = s.blueprints().stream().anyMatch(b -> !owned(s, b.id()) && !b.cancellationQueued());
        boolean resources = !state.enabled || SettlementInventoryService.hasCreativeSupply(level, s)
                || CommunityStock.food(level, s) > s.community().foodReserve
                && CommunityStock.count(level, s, Items.COAL) + CommunityStock.count(level, s, Items.CHARCOAL) > s.community().fuelReserve;
        state.workReady = !ordinaryConstruction && resources;
        reconcile(level, s);
        if (!state.enabled) return;
        if (!s.community().policy.equals("normal") || s.community().evacuation) { state.status = "季节用工或避险优先，暂停美化"; return; }
        if (state.features.size() >= state.limit()) { state.status = "已达到当前疏密的装饰性模块数量上限"; return; }
        long pending = state.features.values().stream().filter(f -> s.blueprint(f.blueprint) != null).count();
        if (pending >= DecorationCatalog.PENDING_LIMIT) { state.status = "等待工人完成现有装饰性模块施工"; return; }
        if (ordinaryConstruction) {
            state.status = "必要建设优先，等待普通工地完工"; return;
        }
        if (!resources) { state.status = "食物或燃料未超过储备线，暂停美化"; return; }
        if (DecorationSitePolicy.resume(level,s)) return;
        var roads = RoadSpaceData.get(level).decorationPositions();
        // Incremental cursors bound candidate checks; existing road coordinates are cached until edited.
        if (state.family(1) && !roads.isEmpty()) {
            for (int i = 0; i < DecorationCatalog.ATTEMPTS; i++) {
                var road = roads.get(Math.floorMod(state.roadCursor++, roads.size()));
                if (!s.contains(road) || !level.hasChunkAt(road)) continue;
                for (var side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                    if (RoadSpaceData.get(level).contains(road.relative(side))) continue;
                    if (Math.floorMod(side.getAxis() == Direction.Axis.X ? road.getZ() : road.getX(), state.spacing()) != 0) continue;
                    String key = "road:" + road.relative(side).asLong();
                    String kind = state.style == 0 ? "lamp_small" : "lamp_arm";
                    if (plan(level, s, key, kind, road.relative(side, 2).above(), side.getOpposite(), null, 1)) {
                        // Finish both eligible sides before moving to the next road coordinate.
                        state.roadCursor--; return;
                    }
                }
            }
        }
        if (state.family(8)) {
            // Public open space is independently decorated, even before a town hall exists.
            int radius = 12 + Math.floorMod(state.buildingCursor, 2) * 8;
            for (int n = 0; n < 4; n++) {
                Direction side = Direction.from2DDataValue(n);
                var seed = s.corePosition().relative(side, radius);
                if (plan(level, s, "public:" + n + ":" + radius, n % 2 == 0 ? "bench" : "natural_patch", seed, side, null, 8)) return;
            }
        }
        var buildings = s.buildings().stream().filter(BuildingRecord::active).filter(b -> !b.demolitionQueued())
                .filter(b -> forBuilding(s, b.id()) == null).toList();
        if (buildings.isEmpty()) { state.status = "等待可装饰建筑或合适道路边缘"; return; }
        for (int i = 0; i < Math.min(DecorationCatalog.ATTEMPTS, buildings.size()); i++) {
            var b = buildings.get(Math.floorMod(state.buildingCursor++, buildings.size()));
            if (!level.hasChunkAt(b.position())
                    || b.type().isDynamicArea() && b.type() != BuildingType.PASTURE_AREA) continue;
            int family = b.type().isHouse() ? 2 : b.type().category() == BuildingCategory.PRODUCTION || b.type().isStorage() ? 4 : 8;
            if (!state.family(family)) continue;
            List<String> kinds = b.type() == BuildingType.PASTURE_AREA ? List.of("composter", "hay")
                    : family == 2 ? List.of("laundry", "woodpile", "flowerbed")
                    : family == 4 ? List.of("woodpile", "workbench", "toolrack") : List.of("bench", "signpost", "natural_patch");
            for (int slot = 0; slot < kinds.size(); slot++) {
                String key = "building:" + b.id() + ":" + slot;
                // Three distinct edge positions, with four fallback sides to accommodate existing structures.
                for (int side = 0; side < 4; side++) {
                    Direction facing = Direction.from2DDataValue(side);
                    int x = (b.minCorner().getX() + b.maxCorner().getX()) / 2;
                    int z = (b.minCorner().getZ() + b.maxCorner().getZ()) / 2;
                    BlockPos seed = switch (facing) {
                        case NORTH -> new BlockPos(x + slot * 4 - 4, b.position().getY(), b.minCorner().getZ() - 4);
                        case SOUTH -> new BlockPos(x + slot * 4 - 4, b.position().getY(), b.maxCorner().getZ() + 4);
                        case WEST -> new BlockPos(b.minCorner().getX() - 4, b.position().getY(), z + slot * 4 - 4);
                        default -> new BlockPos(b.maxCorner().getX() + 4, b.position().getY(), z + slot * 4 - 4);
                    };
                    if (plan(level, s, key, kinds.get(slot), seed, facing, b.id(), family)) return;
                }
            }
        }
        state.status = "现有位置已布置，或空地、原料、预算不足"; data.touch();
    }
    public static boolean spacingAllows(DecorationState state,String kind,int family,BlockPos anchor){
        return state.features.values().stream().noneMatch(f->{
            double minimum=f.kind.equals(kind) || family==1 && f.family==1?Math.max(10,state.spacing()):5;
            long dx=(long)f.anchor.getX()-anchor.getX(),dz=(long)f.anchor.getZ()-anchor.getZ();
            return dx*dx+dz*dz<minimum*minimum;
        });
    }
    public static boolean plan(ServerLevel level, SettlementRecord s, String key, String kind, BlockPos seed, Direction facing, UUID parent, int family) {
        var state = s.decorations(); var entry = DecorationCatalog.ENTRIES.get(kind);
        if (!state.enabled || !state.family(family) || entry == null || state.features.containsKey(key) || state.features.size() >= state.limit()) return false;
        BlockPos anchor = surface(level, seed); if (anchor == null || state.forbidden(anchor) || s.community().danger(anchor)) return false;
        if (!spacingAllows(state,kind,family,anchor)) return false;
        var template = BlueprintTemplates.create(entry.type(), anchor, facing, entry.version());
        if (!safeSite(level, s, template)) return false;
        if (!DecorationSitePolicy.usable(level,s,template,entry.usageClearance())) {state.status="装饰性模块使用侧空间不足";return false;}
        if (!DecorationSitePolicy.allowed(level, s, template,key,kind,seed,facing,parent,family)) return false;
        if (state.spent + entry.cost() > state.dailyBudget) { state.status = "已达到今日美化预算"; return false; }
        var result = BlueprintService.placeBlueprint(level, s.ownerId(), entry.type(), anchor, facing, entry.version(), false, null);
        if (!result.success()) { state.status = "选址受限：" + result.reason(); return false; }
        state.reserve(entry.cost());
        state.features.put(key, new DecorationState.Feature(kind, entry.type(), entry.version(), anchor, facing, result.value().id(), parent, family));
        state.status = "已安排装饰性模块施工：" + entry.displayName(); SettlementSavedData.get(level).touch(); return true;
    }
    public static BlockPos surface(ServerLevel level, BlockPos seed) {
        for (int y = seed.getY() + 3; y >= seed.getY() - 5; y--) {
            var ground = new BlockPos(seed.getX(), y, seed.getZ());
            if (!level.hasChunkAt(ground)) return null;
            if (level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                    && level.getBlockState(ground.above()).isAir() && level.getBlockState(ground.above(2)).isAir()) return ground.above();
        } return null;
    }
    public static boolean safeSite(ServerLevel level, SettlementRecord s, BuildingBlueprintTemplate t) {
        return safeSite(level,s,t,null);
    }
    private static boolean safeSite(ServerLevel level, SettlementRecord s, BuildingBlueprintTemplate t,UUID ignored) {
        var roads = RoadSpaceData.get(level);
        // Automatic decoration selects an existing full ground plane; it never grants fill work.
        int floor=FoundationFootprint.floor(t);
        for(int x=t.minCorner().getX();x<=t.maxCorner().getX();x++)for(int z=t.minCorner().getZ();z<=t.maxCorner().getZ();z++) {
            var ground=new BlockPos(x,floor,z);
            if(!level.hasChunkAt(ground) || level.isOutsideBuildHeight(ground) || !s.contains(ground)
                    || !FoundationFootprint.usableSurface(level,ground,level.getBlockState(ground)))return false;
        }
        // Every occupied and access cell must be loaded, in bounds, outside existing building/blueprint reservations.
        for (var p : t.placements()) {
            var at = p.position();
            if (s.terrain().cells.containsKey(at) || s.terrain().cells.containsKey(at.below()))return false;
            if (!level.hasChunkAt(at) || !s.contains(at) || s.decorations().forbidden(at) || s.community().danger(at)
                    || !level.getFluidState(at).isEmpty() || roads.contains(at) || roads.contains(at.below())
                    || com.hkin.exiledsettlement.road.AutoRoadService.plannedAt(s, at)
                    || com.hkin.exiledsettlement.road.AutoRoadService.plannedAt(s, at.below())) return false;
            for (var b : s.buildings()) if (b.active() && insideXZ(at, b.minCorner(), b.maxCorner(), b.transitionMargin())) return false;
            for (var blueprint : s.blueprints()) {
                if(blueprint.id().equals(ignored))continue;
                var other = BlueprintTemplates.create(blueprint);
                if (insideXZ(at, other.minCorner(), other.maxCorner(), 1)) return false;
            }
            var current = level.getBlockState(at);
            if (!current.isAir()) {
                boolean ground = at.getY() < t.anchor().getY() && (current.is(BlockTags.DIRT) || current.is(Blocks.STONE)
                        || current.is(Blocks.GRAVEL) || current.is(Blocks.SAND));
                if (!ground) return false;
            }
        }
        for (var e : t.entrances()) {
            if (!s.contains(e.position()) || !level.hasChunkAt(e.position()) || !level.getBlockState(e.position()).isAir()
                    || !level.getBlockState(e.position().above()).isAir()) return false;
        }
        return true;
    }
    private static boolean insideXZ(BlockPos p, BlockPos min, BlockPos max, int margin) {
        return p.getX() >= min.getX() - margin && p.getX() <= max.getX() + margin && p.getZ() >= min.getZ() - margin && p.getZ() <= max.getZ() + margin;
    }
    private static void reconcile(ServerLevel level, SettlementRecord s) {
        var state = s.decorations(); if (state.features.isEmpty()) return;
        var features = new ArrayList<>(state.features.values());
        for (int i = 0; i < Math.min(4, features.size()); i++) {
            var f = features.get(Math.floorMod(state.reconcileCursor++, features.size()));
            var blueprint = s.blueprint(f.blueprint);
            if(blueprint != null && !blueprint.cancellationQueued()) {
                if(blueprint.progressIndex()>0 || !blueprint.temporaryFootings().isEmpty())f.siteReady=true;
                else {
                    var template=BlueprintTemplates.create(blueprint);
                    var catalog=DecorationCatalog.ENTRIES.get(f.kind);
                    int check=DecorationSitePolicy.recheck(level,s,blueprint);
                    f.siteReady=check==1;
                    if(check<0 || !safeSite(level,s,template,blueprint.id())
                            || catalog!=null && !DecorationSitePolicy.usable(level,s,template,catalog.usageClearance())) {
                        blueprint.queueCancellation();f.suppressed=true;
                        state.status="尚未开工的装饰性模块位置已失效，撤销并保留实际物资";
                    }
                }
            }
            if (blueprint != null && blueprint.cancellationQueued()) f.suppressed = true;
            if (f.building == null && blueprint == null) {
                var found = s.buildings().stream().filter(b -> b.position().equals(f.anchor) && b.type() == f.type && b.templateVersion() == f.version).findFirst().orElse(null);
                if (found != null) f.building = found.id(); else f.suppressed = true;
            }
            var b = f.building == null ? null : s.building(f.building);
            if (f.building != null && (b == null || !b.active() || b.demolitionQueued())) f.suppressed = true;
            if (f.parent != null) { var parent = s.building(f.parent); if (parent == null || !parent.active() || parent.demolitionQueued()) f.suppressed = true; }
            if (f.suppressed || state.forbidden(f.anchor)) {
                if (blueprint != null && !blueprint.cancellationQueued()) blueprint.queueCancellation();
                continue;
            }
            if (b != null && allowed(s, f) && level.hasChunkAt(f.anchor)) {
                var template = BlueprintTemplates.createForBuilding(b);
                for (var p : template.placements()) {
                    if (!level.hasChunkAt(p.position())) continue;
                    if (p.mode().isFinalStructure() && !BlueprintService.matchesPlacedState(level.getBlockState(p.position()), p.state())
                            && level.getBlockState(p.position()).isAir()) b.markDamaged(p.position());
                }
            }
        } SettlementSavedData.get(level).touch();
    }
    public static SettlementRecord protectedSettlementAt(ServerLevel level, BlockPos at) {
        for (var s : SettlementSavedData.get(level).settlements()) if (s.active() && s.contains(at))
            for (var f : s.decorations().features.values())
                if (BlueprintTemplates.create(f.type, f.anchor, f.facing, f.version).placementAt(at) != null) return s;
        return null;
    }
    /** Confirm the world change next tick, so later protection listeners can still cancel the break. */
    public static void notePlayerRemoval(ServerLevel level, BlockPos at) {
        if (protectedSettlementAt(level, at) == null) return;
        var pending = REMOVALS.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        if (pending.size() < 1024) pending.put(at.immutable(), level.getGameTime());
    }
    public static void flushPlayerRemovals(ServerLevel level) {
        var pending = REMOVALS.get(level); if (pending == null) return;
        var iterator = pending.entrySet().iterator(); int budget = 64;
        while (iterator.hasNext() && budget-- > 0) {
            var entry = iterator.next(); if (entry.getValue() >= level.getGameTime()) continue;
            if (level.hasChunkAt(entry.getKey()) && level.getBlockState(entry.getKey()).isAir()) playerRemoved(level, entry.getKey());
            iterator.remove();
        }
    }
    /** A player's explicit removal persists; ordinary explosion damage remains repairable. */
    public static void playerRemoved(ServerLevel level, BlockPos at) {
        for (var s : SettlementSavedData.get(level).settlements()) {
            for (var f : s.decorations().features.values()) {
                var template = BlueprintTemplates.create(f.type, f.anchor, f.facing, f.version);
                if (template.placementAt(at) != null) { f.suppressed = true; SettlementSavedData.get(level).touch(); }
            }
        }
    }
}

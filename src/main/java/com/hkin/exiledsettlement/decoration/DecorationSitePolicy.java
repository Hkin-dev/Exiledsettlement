package com.hkin.exiledsettlement.decoration;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Incremental local land survey. A 5x5 window cut out of a building plot is not a leftover gap. */
public final class DecorationSitePolicy {
    private DecorationSitePolicy() { }
    private record Footprint(int width, int depth) { }
    private static List<Footprint> footprints;
    private record Pending(String key,String kind,BlockPos seed,Direction facing,UUID parent,int family,Survey survey) { }
    private static final Map<ServerLevel, Map<UUID, Pending>> SURVEYS = new WeakHashMap<>();
    private static final Map<ServerLevel,Map<UUID,Survey>> RECHECKS=new WeakHashMap<>();
    private static List<Footprint> footprints() {
        if (footprints == null) {
            var result = new HashSet<Footprint>();
            for (var type : BuildingType.values()) {
                if (!(type.isHouse() || type == BuildingType.WAREHOUSE || type == BuildingType.TOOL_WORKSHOP
                        || type == BuildingType.FORESTER_LODGE || type == BuildingType.HUNTING_LODGE
                        || type == BuildingType.FISHING_HUT || type == BuildingType.SAWMILL
                        || type == BuildingType.MARKET || type == BuildingType.TAVERN || type == BuildingType.RESTAURANT)) continue;
                var t = BlueprintTemplates.create(type, BlockPos.ZERO, Direction.NORTH);
                int w = t.maxCorner().getX() - t.minCorner().getX() + 1 + t.transitionMargin() * 2;
                int d = t.maxCorner().getZ() - t.minCorner().getZ() + 1 + t.transitionMargin() * 2;
                result.add(new Footprint(w, d)); result.add(new Footprint(d, w));
            }
            footprints = result.stream().sorted(Comparator.comparingInt(f -> f.width * f.depth)).toList();
        }
        return footprints;
    }
    public static void invalidate(ServerLevel level) { SURVEYS.remove(level);RECHECKS.remove(level); }
    /** 0 waits for the bounded survey, 1 accepts, -1 releases an obsolete plan. */
    public static int recheck(ServerLevel level,SettlementRecord s,BlueprintRecord blueprint) {
        var map=RECHECKS.computeIfAbsent(level,l->new LinkedHashMap<>());var survey=map.get(blueprint.id());
        if(survey==null || survey.buildings!=s.buildings().size() || survey.blueprints!=s.blueprints().size()
                || level.getGameTime()-survey.created>400) {
            survey=new Survey(level,s,BlueprintTemplates.create(blueprint),blueprint.id());map.put(blueprint.id(),survey);
        }
        return !survey.done?0:survey.allowed?1:-1;
    }
    public static boolean usable(ServerLevel level,SettlementRecord s,BuildingBlueprintTemplate t,int clearance) {
        if(clearance==0)return true;
        var face=t.facing();
        int across=face.getAxis()==Direction.Axis.Z?t.maxCorner().getX()-t.minCorner().getX()+1:t.maxCorner().getZ()-t.minCorner().getZ()+1;
        for(int i=0;i<across;i++)for(int step=1;step<=clearance;step++) {
            BlockPos p=switch(face) {
                case NORTH->new BlockPos(t.minCorner().getX()+i,t.anchor().getY(),t.minCorner().getZ()-step);
                case SOUTH->new BlockPos(t.minCorner().getX()+i,t.anchor().getY(),t.maxCorner().getZ()+step);
                case WEST->new BlockPos(t.minCorner().getX()-step,t.anchor().getY(),t.minCorner().getZ()+i);
                default->new BlockPos(t.maxCorner().getX()+step,t.anchor().getY(),t.minCorner().getZ()+i);
            };
            if(!s.contains(p) || !level.hasChunkAt(p) || !level.getBlockState(p).getCollisionShape(level,p).isEmpty()
                    || !level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
                    || !level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                    || !level.getFluidState(p).isEmpty() || s.community().danger(p))return false;
        }return true;
    }
    public static void tick(ServerLevel level) {
        var surveys = SURVEYS.get(level);
        int budget = 256;
        long until = System.nanoTime() + 1_500_000L;
        for (var pending : surveys==null?List.<Pending>of():surveys.values()) {
            var s=pending.survey;
            while (!s.done && budget-- > 0 && System.nanoTime() < until) s.step(level);
            if (budget <= 0 || System.nanoTime() >= until) break;
        }
        var checks=RECHECKS.get(level);if(checks==null)return;
        checks.entrySet().removeIf(row->row.getValue().settlement.blueprint(row.getKey())==null);
        for(var s:checks.values()) {
            while(!s.done && budget-- > 0 && System.nanoTime()<until)s.step(level);
            if(budget<=0 || System.nanoTime()>=until)break;
        }
    }
    public static boolean resume(ServerLevel level,SettlementRecord settlement) {
        var map=SURVEYS.get(level);if(map==null)return false;var request=map.get(settlement.id());if(request==null)return false;
        if(!request.survey.done){settlement.decorations().status="正在检查剩余空地与建筑留白";return true;}
        if(!request.survey.allowed){map.remove(settlement.id());settlement.decorations().status="空地留作建筑，寻找其他边角";return false;}
        boolean placed=DecorationService.plan(level,settlement,request.key,request.kind,request.seed,request.facing,request.parent,request.family);
        if(map.get(settlement.id())==request)map.remove(settlement.id());return placed;
    }
    public static boolean allowed(ServerLevel level, SettlementRecord settlement, BuildingBlueprintTemplate t,
                                  String key,String kind,BlockPos seed,Direction facing,UUID parent,int family) {
        var map = SURVEYS.computeIfAbsent(level, l -> new LinkedHashMap<>());
        var request = map.get(settlement.id());
        if(request!=null && (!request.key.equals(key) || !request.seed.equals(seed) || request.facing!=facing))return false;
        var survey=request==null?null:request.survey;
        if (survey == null || survey.buildings != settlement.buildings().size()
                || survey.blueprints != settlement.blueprints().size() || level.getGameTime() - survey.created > 400) {
            survey = new Survey(level, settlement, t); map.put(settlement.id(),new Pending(key,kind,seed.immutable(),facing,parent,family,survey));
        }
        if (!survey.done) { settlement.decorations().status = "正在检查剩余空地与建筑留白"; return false; }
        if (!survey.allowed) settlement.decorations().status = "空地留作建筑，等待合适边角";
        return survey.allowed;
    }
    private static final class Survey {
        final SettlementRecord settlement;
        final int buildings, blueprints, minX, minZ, sizeX, sizeZ, y, decorMinX, decorMaxX, decorMinZ, decorMaxZ;
        final long created;
        final boolean[][] free;
        final List<int[]> occupied = new ArrayList<>();
        int cursor; boolean done, allowed;
        Survey(ServerLevel level, SettlementRecord s, BuildingBlueprintTemplate t) {
            this(level,s,t,null);
        }
        Survey(ServerLevel level, SettlementRecord s, BuildingBlueprintTemplate t,UUID ignored) {
            settlement = s; buildings = s.buildings().size(); blueprints = s.blueprints().size(); created = level.getGameTime();
            y = t.anchor().getY();
            decorMinX = t.minCorner().getX(); decorMaxX = t.maxCorner().getX();
            decorMinZ = t.minCorner().getZ(); decorMaxZ = t.maxCorner().getZ();
            int reach = footprints().stream().mapToInt(f -> Math.max(f.width, f.depth)).max().orElse(20);
            minX = decorMinX - reach; minZ = decorMinZ - reach;
            sizeX = decorMaxX - decorMinX + 1 + 2 * reach; sizeZ = decorMaxZ - decorMinZ + 1 + 2 * reach;
            free = new boolean[sizeX][sizeZ];
            for (var b : s.buildings()) if (b.active()) box(b.minCorner(), b.maxCorner(), b.transitionMargin());
            for (var b : s.blueprints()) {
                if(b.id().equals(ignored))continue;
                var template = BlueprintTemplates.create(b); box(template.minCorner(), template.maxCorner(), template.transitionMargin());
            }
        }
        void box(BlockPos min, BlockPos max, int margin) {
            occupied.add(new int[]{min.getX()-margin,max.getX()+margin,min.getZ()-margin,max.getZ()+margin});
        }
        void step(ServerLevel level) {
            int x = cursor / sizeZ, z = cursor % sizeZ;
            free[x][z] = freeGround(level, minX + x, minZ + z);
            if (++cursor >= sizeX * sizeZ) finish();
        }
        boolean freeGround(ServerLevel level, int x, int z) {
            BlockPos p = new BlockPos(x, y, z);
            if (!settlement.contains(p) || !level.hasChunkAt(p) || settlement.decorations().forbidden(p)
                    || settlement.community().danger(p)) return false;
            for (var b : occupied) if (x >= b[0] && x <= b[1] && z >= b[2] && z <= b[3]) return false;
            for (int dy = 2; dy >= -2; dy--) {
                var ground = p.offset(0, dy-1, 0); var top = ground.above();
                var state = level.getBlockState(ground);
                if (!(state.is(BlockTags.DIRT) || state.is(Blocks.STONE) || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL))) continue;
                if (!level.getFluidState(top).isEmpty() || !level.getFluidState(ground).isEmpty()
                        || RoadSpaceData.get(level).contains(ground) || RoadSpaceData.get(level).contains(top)
                        || AutoRoadService.plannedAt(settlement, ground) || AutoRoadService.plannedAt(settlement, top)) return false;
                boolean clear = true;
                for (int height = 0; height < 3; height++) {
                    var above = level.getBlockState(top.above(height));
                    if (!(above.isAir() || above.is(BlockTags.REPLACEABLE_BY_TREES) || above.is(Blocks.SNOW))) { clear = false; break; }
                }
                if (clear) return true;
            }
            return false;
        }
        void finish() {
            done = true;
            int[][] sum = new int[sizeX+1][sizeZ+1];
            for (int x = 1; x <= sizeX; x++) for (int z = 1; z <= sizeZ; z++)
                sum[x][z] = (free[x-1][z-1] ? 1 : 0) + sum[x-1][z] + sum[x][z-1] - sum[x-1][z-1];
            for (var f : footprints()) {
                for (int x = Math.max(0, decorMinX-minX-f.width+1); x <= decorMaxX-minX; x++)
                    for (int z = Math.max(0, decorMinZ-minZ-f.depth+1); z <= decorMaxZ-minZ; z++) {
                        if (x+f.width > sizeX || z+f.depth > sizeZ) continue;
                        int cells = sum[x+f.width][z+f.depth]-sum[x][z+f.depth]-sum[x+f.width][z]+sum[x][z];
                        if (cells == f.width*f.depth) { allowed = false; return; }
                    }
            }
            allowed = true;
        }
    }
}

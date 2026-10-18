package com.hkin.exiledsettlement.road;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/** Default-enabled road jobs; runtime reservations and work timers are never saved. */
public final class AutoRoadState {
    public boolean enabled=true, workReady;
    public int playerEditVersion=1;
    public int gradingReviewVersion;
    /** Legacy API field; no longer a construction quota. */
    @Deprecated public int dailyBudget;
    public int spent, cursor;
    public long budgetDay = -1;
    public String status = "等待居民自动修路";
    public final Map<String, Route> routes = new LinkedHashMap<>();
    public final Set<UUID> accessSurveyed=new LinkedHashSet<>();
    public final Map<UUID,Long> accessRetry=new LinkedHashMap<>();
    public final Map<UUID,String> accessFailure=new LinkedHashMap<>();
    public CompoundTag planning=new CompoundTag();
    public int earthworkRouteCursor;
    public int reviewCursor;

    public void newDay(long day) {
        if (budgetDay != day) { budgetDay = day; spent = 0; }
    }

    public Route routeAt(BlockPos position) {
        if (position == null) return null;
        return routes.values().stream().filter(r -> !r.suppressed && !r.p0Invalidated && r.progress < r.cells.size()
                && r.cellIndex(position)>=r.progress && !r.completed.get(r.cellIndex(position))).findFirst().orElse(null);
    }

    public int pending() {
        return (int) routes.values().stream().filter(r -> !r.suppressed && !r.p0Invalidated && r.progress < r.cells.size()).count();
    }

    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", 7); tag.putInt("PlayerEditVersion",playerEditVersion);tag.putInt("DefaultOnVersion",1); tag.putBoolean("Enabled", enabled);
        tag.put("ConstructionRoutePlan",AutoRoadPlanner.saved(this));
        tag.putInt("EarthworkRouteCursor",earthworkRouteCursor);
        tag.putInt("ReviewCursor",reviewCursor);
        tag.putInt("GradingReviewVersion",gradingReviewVersion);
        var access=new ListTag();for(var id:accessSurveyed){var t=new CompoundTag();t.putUUID("Id",id);access.add(t);}tag.put("AccessSurveyed",access);
        var retries=new ListTag();accessRetry.forEach((id,time)->{var t=new CompoundTag();t.putUUID("Id",id);t.putLong("Time",time);retries.add(t);});tag.put("AccessRetry",retries);
        var failures=new ListTag();accessFailure.forEach((id,reason)->{var t=new CompoundTag();t.putUUID("Id",id);t.putString("Reason",reason);failures.add(t);});tag.put("AccessFailure",failures);
        tag.putInt("DailyBudget", dailyBudget); tag.putInt("Spent", spent); tag.putLong("BudgetDay", budgetDay);
        tag.putInt("Cursor", cursor); tag.putString("Status", status);
        var list = new ListTag();
        routes.forEach((key, r) -> {
            var entry = new CompoundTag(); entry.putString("Key", key); entry.putUUID("Building", r.building);entry.putUUID("Id",r.id);entry.putInt("EarthworkCursor",r.earthworkCursor);entry.putInt("WorkCursor",r.workCursor);
            entry.putLong("Entrance", r.entrance.asLong());
            entry.putInt("Width", r.width);
            entry.putLongArray("Centerline", r.centerline.stream().mapToLong(BlockPos::asLong).toArray());
            entry.putLongArray("Cells", r.cells.stream().mapToLong(BlockPos::asLong).toArray());
            entry.putLongArray("Completed",r.completed.toLongArray());
            entry.putLongArray("Admitted",r.admitted.toLongArray());
            entry.putLongArray("Repairs",r.repairs.toLongArray());
            entry.putInt("Progress", r.progress); entry.putBoolean("Suppressed", r.suppressed);
            entry.putBoolean("AccessConnected",r.accessConnected);
            entry.putBoolean("ConstructionGradeFixed",r.constructionGradeFixed);
            entry.putBoolean("ManualBridge",r.manualBridge);
            entry.putBoolean("P0Invalidated",r.p0Invalidated);
            var providers=new ListTag();for(var id:r.sourceBridges){var t=new CompoundTag();t.putUUID("Id",id);providers.add(t);}entry.put("SourceBridges",providers);
            entry.putString("Status", r.status); list.add(entry);
            entry.putBoolean("AreaOptimized", r.areaOptimized);
            entry.putLong("ReviewAfter",r.reviewAfter);entry.putInt("AdaptiveVersion",r.adaptiveVersion);
            entry.putLongArray("Retired",r.retired.stream().mapToLong(BlockPos::asLong).toArray());
            if(r.mergedInto!=null)entry.putUUID("MergedInto",r.mergedInto);
        });
        tag.put("Routes", list); return tag;
    }

    public void load(CompoundTag tag) {
        if (!tag.contains("Version")) return;
        if (tag.getInt("Version") < 1 || tag.getInt("Version") > 7) { enabled=false; status = "道路记录版本不支持，已暂停"; return; }
        playerEditVersion=tag.getInt("PlayerEditVersion");
        planning=tag.getCompound("ConstructionRoutePlan").copy();
        earthworkRouteCursor=Math.max(0,tag.getInt("EarthworkRouteCursor"));
        reviewCursor=Math.max(0,tag.getInt("ReviewCursor"));
        gradingReviewVersion=tag.getInt("GradingReviewVersion");
        accessSurveyed.clear();var access=tag.getList("AccessSurveyed",Tag.TAG_COMPOUND);for(int i=0;i<access.size();i++)if(access.getCompound(i).hasUUID("Id"))accessSurveyed.add(access.getCompound(i).getUUID("Id"));
        accessRetry.clear();var retries=tag.getList("AccessRetry",Tag.TAG_COMPOUND);for(int i=0;i<retries.size();i++){var t=retries.getCompound(i);if(t.hasUUID("Id"))accessRetry.put(t.getUUID("Id"),t.getLong("Time"));}
        accessFailure.clear();var failures=tag.getList("AccessFailure",Tag.TAG_COMPOUND);for(int i=0;i<failures.size();i++){var t=failures.getCompound(i);if(t.hasUUID("Id"))accessFailure.put(t.getUUID("Id"),t.getString("Reason"));}
        enabled = !tag.contains("DefaultOnVersion") || tag.getBoolean("Enabled");
        dailyBudget = 0; // Old quotas are retired, including in existing villages.
        spent = Math.max(0, tag.getInt("Spent")); budgetDay = tag.getLong("BudgetDay");
        cursor = Math.max(0, tag.getInt("Cursor")); status = tag.getString("Status");
        var list = tag.getList("Routes", Tag.TAG_COMPOUND);
        routes.clear();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            var raw = entry.getLongArray("Cells");
            if (!entry.hasUUID("Building") || raw.length == 0) {
                enabled = false; status = "道路记录异常，已暂停"; continue;
            }
            var cells = Arrays.stream(raw).mapToObj(BlockPos::of).toList();
            var route = new Route(entry.getUUID("Building"), BlockPos.of(entry.getLong("Entrance")), cells);
            long[] center = entry.getLongArray("Centerline");
            route.centerline = center.length == 0 ? cells : Arrays.stream(center).mapToObj(BlockPos::of).toList();
            route.width = tag.getInt("Version") == 1 ? 1 : entry.getInt("Width");
            route.manualBridge=entry.getBoolean("ManualBridge");
            if (route.manualBridge ? route.width < 3 : route.width != 1 && route.width != 3) { enabled=false; status="道路宽度记录异常，已暂停"; continue; }
            route.progress = Math.clamp(entry.getInt("Progress"), 0, cells.size());
            route.completed.or(BitSet.valueOf(entry.getLongArray("Completed")));
            route.advance();
            if(entry.hasUUID("Id"))route.id=entry.getUUID("Id");route.earthworkCursor=entry.getInt("EarthworkCursor");route.workCursor=entry.getInt("WorkCursor");
            if(entry.contains("Admitted"))route.admitted.or(BitSet.valueOf(entry.getLongArray("Admitted")));
            else route.admitted.set(0,Math.clamp(route.earthworkCursor,0,cells.size()));
            route.repairs.or(BitSet.valueOf(entry.getLongArray("Repairs")));
            route.suppressed = entry.getBoolean("Suppressed"); route.status = entry.getString("Status");
            route.accessConnected=entry.getBoolean("AccessConnected");
            route.constructionGradeFixed=entry.getBoolean("ConstructionGradeFixed");
            route.p0Invalidated=entry.getBoolean("P0Invalidated");
            var providers=entry.getList("SourceBridges",Tag.TAG_COMPOUND);for(int providerIndex=0;providerIndex<providers.size();providerIndex++)route.sourceBridges.add(providers.getCompound(providerIndex).getUUID("Id"));
            route.areaOptimized = entry.getBoolean("AreaOptimized");
            route.reviewAfter=entry.getLong("ReviewAfter");route.adaptiveVersion=entry.getInt("AdaptiveVersion");
            for(long at:entry.getLongArray("Retired"))route.retired.add(BlockPos.of(at));
            if(entry.hasUUID("MergedInto"))route.mergedInto=entry.getUUID("MergedInto");
            routes.put(entry.getString("Key"), route);
        }
    }

    public static final class Route {
        public boolean manualBridge;
        public boolean accessConnected;
        public boolean constructionGradeFixed;
        public final Set<UUID> sourceBridges=new LinkedHashSet<>();
        public UUID building;
        public UUID id=UUID.randomUUID();
        public int earthworkCursor,workCursor;
        public long reviewAfter;
        public int adaptiveVersion;
        public UUID mergedInto;
        public final Set<BlockPos> retired=new LinkedHashSet<>();
        public final BlockPos entrance;
        public List<BlockPos> cells;
        public List<BlockPos> centerline;
        private List<BlockPos> indexedCells;
        private final Map<BlockPos,Integer> cellIndices=new HashMap<>();
        private final Map<Long,BlockPos> columns=new HashMap<>();
        private void indexCells(){
            if(indexedCells==cells)return;
            cellIndices.clear();columns.clear();
            for(int i=0;i<cells.size();i++){var p=cells.get(i);cellIndices.putIfAbsent(p,i);columns.putIfAbsent(BlockPos.asLong(p.getX(),0,p.getZ()),p);}
            indexedCells=cells;
        }
        public boolean containsCell(BlockPos p){return cellIndex(p)>=0;}
        public int cellIndex(BlockPos p){indexCells();return cellIndices.getOrDefault(p,-1);}
        public BlockPos cellInColumn(BlockPos p){indexCells();return columns.get(BlockPos.asLong(p.getX(),0,p.getZ()));}
        public int width = 1;
        public int progress;
        public final BitSet completed=new BitSet();
        public final BitSet admitted=new BitSet();
        public final BitSet repairs=new BitSet();
        public void advance() { progress=Math.min(cells.size(),completed.nextClearBit(0)); }
        public void mark(BlockPos at) { int i=cellIndex(at);if(i>=0)completed.set(i);advance(); }
        public boolean suppressed;
        public boolean p0Invalidated;
        public boolean areaOptimized;
        public String status = "等待空闲工人";
        public Route(UUID building, BlockPos entrance, List<BlockPos> cells) {
            this.building = building; this.entrance = entrance.immutable();
            this.cells = cells.stream().map(BlockPos::immutable).toList();
            this.centerline = this.cells;
        }
    }
}

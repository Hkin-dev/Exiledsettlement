package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.*;
import java.util.List;
import java.util.*;
import com.hkin.exiledsettlement.road.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;

/** Snapshot unrelated interiors once per route, rather than scanning buildings per node. */
public final class CitizenGroundNavigation extends GroundPathNavigation {
    private List<BuildingRecord> excludedInteriors = List.of();
    private List<BuildingRecord> mineInteriors = List.of();
    private SettlementRecord configuredSettlement;
    private BlockPos configuredOrigin, configuredTarget;
    private long configuredTick = Long.MIN_VALUE;
    private int detourAllowance;
    private boolean bridgeWork;
    private Set<BlockPos> roadTransit=Set.of();
    private final List<String> roadProbeTrace=new ArrayList<>();
    public com.google.gson.JsonObject roadProbeDiagnostic(){
        var out=new com.google.gson.JsonObject();out.addProperty("origin",String.valueOf(configuredOrigin));out.addProperty("target",String.valueOf(configuredTarget));
        out.add("allowed",new com.google.gson.Gson().toJsonTree(roadTransit.stream().map(BlockPos::toShortString).sorted().toList()));
        out.add("nativeNeighbors",new com.google.gson.Gson().toJsonTree(roadProbeTrace));return out;
    }
    public void detourAllowance(int value){detourAllowance=Math.clamp(value,0,64);setMaxVisitedNodesMultiplier(1F+detourAllowance/32F);}
    @Override protected Path createPath(java.util.Set<BlockPos> targets,int regionOffset,boolean offsetUpward,int accuracy,float followRange){
        int allowance=Math.max(detourAllowance,ApartmentHomeNavigation.allowance(mob,targets));
        setMaxVisitedNodesMultiplier(1F+allowance/32F);
        return super.createPath(targets,regionOffset,offsetUpward,accuracy,followRange+allowance);
    }
    public CitizenGroundNavigation(Mob mob, Level level) { super(mob, level); }

    public void destination(SettlementRecord settlement, BlockPos origin, BlockPos target) {
        // Hundreds of road candidates can share one destination in a single
        // route search. Reuse its snapshot instead of rescanning every building.
        long now = level.getGameTime();
        if (configuredTick == now && configuredSettlement == settlement
                && origin.equals(configuredOrigin) && target.equals(configuredTarget)) return;
        configuredTick = now;
        configuredSettlement = settlement;
        configuredOrigin = origin.immutable();
        configuredTarget = target.immutable();
        mineInteriors = settlement == null ? List.of() : settlement.buildings().stream()
                .filter(b -> b.active() && b.type()==BuildingType.MINE
                        && (contains(b,origin) || contains(b,target))).toList();
        bridgeWork=false;
        if(settlement!=null && !mob.isInWater()){
            String id=mob.getPersistentData().getString(CitizenProjectionService.CITIZEN_ID_TAG);
            for(var c:settlement.citizens())if(c.id().toString().equals(id)){
                var b=settlement.blueprint(c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId());
                bridgeWork=b!=null && b.terrainProject()!=null && b.terrainProject().playerBridge!=null;
                break;
            }
        }
        roadTransit=connectedRoadTransit(origin,target);
        roadProbeTrace.clear();
        excludedInteriors = settlement == null ? List.of() : settlement.buildings().stream()
                .filter(b -> b.active() && b.operational() && !b.type().isDynamicArea()
                        && b.type() != BuildingType.WAGON && b.type() != BuildingType.STARTER_CAMP
                        && b.type().category() != com.hkin.exiledsettlement.building.BuildingCategory.RECREATION)
                .filter(b -> !contains(b, origin) && !contains(b, target)).toList();
    }

    public boolean permitsWaypoint(BlockPos point) {
        return (roadTransit.isEmpty() || roadTransit.contains(point))
                && excludedInteriors.stream().noneMatch(b -> contains(b, point));
    }

    /** If both ends already stand on connected paid roads, keep the ordinary
     * path on that physical network instead of cutting across pits or roofs.
     * A bounded local miss leaves ordinary off-road approach/navigation intact. */
    private Set<BlockPos> connectedRoadTransit(BlockPos origin,BlockPos target){
        if(!(level instanceof ServerLevel server))return Set.of();
        var roads=RoadSpaceData.get(server);BlockPos start=null;var end=target.below();
        // Feet in the lower half of a stair/slab share its integer block Y;
        // a real jump can put the feet two cells over their road support.
        for(var candidate:List.of(origin.below(),origin,origin.below(2))){
            if(!roads.contains(candidate) || !AdaptiveRoadService.walkSurface(server,candidate))continue;
            var state=server.getBlockState(candidate);
            if(candidate.equals(origin) && !(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock))continue;
            if(candidate.equals(origin.below(2)) && mob.onGround())continue;
            start=candidate;break;
        }
        if(start==null || !roads.contains(end)
                || !AdaptiveRoadService.walkSurface(server,end))return Set.of();
        var settlement=configuredSettlement!=null?configuredSettlement:
                com.hkin.exiledsettlement.settlement.SettlementSavedData.get(server).findContaining(start).orElse(null);
        var actualStart=start;
        var preferred=settlement==null?null:settlement.autoRoads().routes.values().stream()
                .filter(r->!r.suppressed && r.accessConnected && r.progress==r.cells.size()
                        && r.containsCell(actualStart) && r.containsCell(end))
                .min(Comparator.comparingInt((AutoRoadState.Route r)->r.centerline.contains(end)?0:1)
                        .thenComparingInt(r->r.centerline.contains(actualStart)?0:1)
                        .thenComparingInt(r->r.cells.size()).thenComparing(r->r.id)).orElse(null);
        Set<BlockPos> corridor=preferred==null?Set.of():Set.copyOf(preferred.cells);
        var queue=new ArrayDeque<BlockPos>();var visited=new LinkedHashSet<BlockPos>();
        queue.add(start);visited.add(start);
        while(!queue.isEmpty() && visited.size()<=256){
            var at=queue.removeFirst();
            if(at.equals(end)){
                var nodes=new HashSet<BlockPos>();
                for(var ground:visited){nodes.add(ground.above());
                    // Vanilla evaluates a stair's lower half at the block's
                    // own integer Y before finding the higher walking node.
                    // Its collision evaluator still rejects solid body space.
                    if(server.getBlockState(ground).getBlock() instanceof net.minecraft.world.level.block.StairBlock
                            || server.getBlockState(ground).getBlock() instanceof net.minecraft.world.level.block.SlabBlock)nodes.add(ground);
                }
                return Set.copyOf(nodes);
            }
            for(var direction:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++){
                var next=at.relative(direction).offset(0,dy,0);
                if(visited.contains(next) || !roads.contains(next) || !corridor.isEmpty() && !corridor.contains(next) || !server.hasChunkAt(next)
                        || !AdaptiveRoadService.walkSurface(server,next)
                        || !RoadSurfaceProfile.joins(server,at,server.getBlockState(at),next,server.getBlockState(next),direction))continue;
                visited.add(next);queue.addLast(next);break;
            }
        }
        return Set.of();
    }
    @Override public boolean canCutCorner(PathType type){
        return roadTransit.isEmpty() && super.canCutCorner(type);
    }
    private boolean roadColumn(int x,int z){return roadTransit.stream().anyMatch(p->p.getX()==x && p.getZ()==z);}

    private static boolean contains(BuildingRecord b, BlockPos p) {
        return p.getX() >= b.minCorner().getX() && p.getX() <= b.maxCorner().getX()
                && p.getZ() >= b.minCorner().getZ() && p.getZ() <= b.maxCorner().getZ()
                && p.getY() >= b.minCorner().getY() && p.getY() <= b.maxCorner().getY() + 1;
    }

    @Override public void tick() {
        var current=getPath();
        // A swimming start node can remain one block below a resident standing
        // on the bank edge. It is already passed horizontally; keeping it as
        // the next node makes vanilla wait forever for the resident to sink.
        if(current!=null && !current.isDone() && mob.onGround()) {
            var node=current.getNextNode();
            double dy=mob.getY()-node.y;
            if(dy>=.8 && dy<=1.1 && Math.abs(mob.getX()-node.x-.5)<.8
                    && Math.abs(mob.getZ()-node.z-.5)<.8
                    && level.getFluidState(new BlockPos(node.x,node.y,node.z)).is(net.minecraft.tags.FluidTags.WATER))current.advance();
        }
        double normalSpeed=speedModifier;
        if(!roadTransit.isEmpty() && current!=null && !current.isDone()){
            var end=current.getEndNode();
            if(end!=null && mob.onGround() && mob.distanceToSqr(end.x+.5,mob.getY(),end.z+.5)<2.25D
                    && roadTerminalAhead(end))speedModifier*=.5D;
        }
        try{super.tick();}finally{speedModifier=normalSpeed;}
    }
    private boolean roadTerminalAhead(Node end){
        if(!(level instanceof ServerLevel server))return false;
        double dx=end.x+.5-mob.getX(),dz=end.z+.5-mob.getZ();
        var direction=Math.abs(dx)>Math.abs(dz)?(dx<0?Direction.WEST:Direction.EAST):(dz<0?Direction.NORTH:Direction.SOUTH);
        var ahead=end.asBlockPos().below().relative(direction);var roads=RoadSpaceData.get(server);
        for(int dy=-1;dy<=1;dy++){var at=ahead.offset(0,dy,0);
            if(roads.contains(at) && AdaptiveRoadService.walkSurface(server,at))return false;}
        return true;
    }
    @Override protected PathFinder createPathFinder(int maxVisitedNodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            @Override public int getNeighbors(Node[] neighbors,Node current){
                int count=super.getNeighbors(neighbors,current);
                if(roadTransit.isEmpty() && !bridgeWork)return count;
                int kept=0;
                for(int i=0;i<count;i++){
                    var n=neighbors[i];boolean allowed=roadTransit.isEmpty() || roadTransit.contains(n.asBlockPos());
                    if(bridgeWork && (n.type==PathType.WATER || n.type==PathType.WATER_BORDER || !level.getFluidState(n.asBlockPos()).isEmpty()))allowed=false;
                    // Native diagonal neighbours may cross a walkable natural
                    // inside corner that was never part of the paid corridor.
                    if(allowed && !roadTransit.isEmpty() && current.x!=n.x && current.z!=n.z)
                        allowed=roadColumn(current.x,n.z) && roadColumn(n.x,current.z);
                    if(com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled() && roadProbeTrace.size()<64)
                        roadProbeTrace.add(current.asBlockPos().toShortString()+" -> "+n.asBlockPos().toShortString()+" "+n.type+" allowed="+allowed);
                    if(allowed)neighbors[kept++]=n;
                }
                return kept;
            }
            @Override public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
                // A fence extends half a block into the nominal air cell above it.
                // The mine's stair exit must use its landing, never that false floor.
                if(!mineInteriors.isEmpty()) {
                    var feet=new BlockPos(x,y,z);var below=feet.below();
                    for(var mine:mineInteriors)if(contains(mine,feet)) {
                        var shape=level.getBlockState(below).getCollisionShape(level,below);
                        if(!shape.isEmpty() && shape.max(Direction.Axis.Y)>1.001D)return PathType.BLOCKED;
                    }
                }
                if (ApartmentHomeNavigation.lowTableBlocks(mob, new BlockPos(x,y,z))) return PathType.BLOCKED;
                for (BuildingRecord b : excludedInteriors == null ? List.<BuildingRecord>of() : excludedInteriors) {
                    if (x > b.minCorner().getX() && x < b.maxCorner().getX()
                            && z > b.minCorner().getZ() && z < b.maxCorner().getZ()
                            && y >= b.minCorner().getY() && y <= b.maxCorner().getY()) return PathType.BLOCKED;
                }
                return super.getPathTypeOfMob(context, x, y, z, mob);
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(nodeEvaluator, maxVisitedNodes);
    }
}

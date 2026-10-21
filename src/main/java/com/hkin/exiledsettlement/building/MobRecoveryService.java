package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.blueprint.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;
import static com.hkin.exiledsettlement.building.MobRecoveryState.Status.*;

/** Captures actual pre-damage states; never edits the world from an event handler. */
public final class MobRecoveryService {
    private MobRecoveryService(){}
    public static boolean mob(Entity e){return e instanceof Mob && !(e instanceof SettlementCitizenEntity);}
    public static void capture(ServerLevel l,Entity source,Collection<BlockPos> targets,String cause){
        if(!mob(source))return;
        String recordedCause=source instanceof EnderMan?"enderman":cause;
        var data=SettlementSavedData.get(l);var groups=new LinkedHashMap<SettlementRecord,MobRecoveryState.Incident>();
        var positions=new LinkedHashSet<BlockPos>();
        for(var p:targets)if(l.hasChunkAt(p)){
            positions.add(p.immutable());for(var d:Direction.values())positions.add(p.relative(d));
            // Falling columns and hanging decorations lose their supports after the blast.
            for(int n=1;n<=32;n++){var q=p.above(n);if(!l.hasChunkAt(q) || !(l.getBlockState(q).getBlock() instanceof FallingBlock))break;positions.add(q);}
        }
        for(var p:positions){
            if(!l.hasChunkAt(p) || l.isOutsideBuildHeight(p))continue;
            var before=l.getBlockState(p);if(before.isAir())continue;
            var s=data.findContaining(p).orElse(null);if(s==null)continue;
            if(!inRecoveryScope(l,s,recordedCause,p,before))continue;
            var state=s.mobRecovery();var prior=state.owner(p);
            if(prior!=null)continue;
            var event=groups.computeIfAbsent(s,key->{var e=new MobRecoveryState.Incident(UUID.randomUUID(),source.getUUID(),recordedCause,l.getGameTime());state.incidents.put(e.id,e);return e;});
            var c=new MobRecoveryState.Cell(p,before);c.blockEntity=l.getBlockEntity(p)!=null;c.scopeChecked=true;state.add(event,c);
        }
        if(!groups.isEmpty())data.touch();
    }
    public static void enderman(ServerLevel l,EnderMan mob){
        if(mob.getCarriedBlock()!=null)return;
        var at=mob.blockPosition();var p=new ArrayList<BlockPos>();
        for(var q:BlockPos.betweenClosed(at.offset(-2,0,-2),at.offset(2,3,2)))p.add(q.immutable());
        capture(l,mob,p,"enderman");
    }
    public static void tick(ServerLevel l){
        var data=SettlementSavedData.get(l);int budget=256,scopeBudget=64;
        for(var s:data.settlements()){
            var state=s.mobRecovery();int count=state.observations.size();
            int scopeCount=state.scopeReviews.size();
            while(scopeCount-->0 && scopeBudget-->0 && !state.scopeReviews.isEmpty()) {
                var entry=state.scopeReviews.removeFirst();var c=entry.getValue();
                if(c.status!=PENDING || c.scopeChecked)continue;
                if(!l.hasChunkAt(c.pos)){state.scopeReviews.addLast(entry);continue;}
                checkScope(l,s,entry.getKey(),c);
            }
            var reviewed=new HashSet<MobRecoveryState.Incident>();
            while(count-->0 && budget-->0 && !state.observations.isEmpty()){
                var entry=state.observations.removeFirst();var e=entry.getKey();var c=entry.getValue();
                if(c.status!=OBSERVING)continue;
                if(l.getGameTime()<e.time+5 || !l.hasChunkAt(c.pos)){state.observations.addLast(entry);continue;}
                if(!checkScope(l,s,e,c))continue;
                c.damaged=l.getBlockState(c.pos);
                if(matches(c.damaged,c.original)){c.status=UNCHANGED;}
                else if(c.damaged.is(c.original.getBlock())){c.status=UNCHANGED;}
                else if(!c.damaged.isAir() && !c.damaged.canBeReplaced()){c.status=CONFLICT;c.reason="RECOVERY_CHANGED";}
                else if(material(c.original)==null || c.original.getBlock() instanceof LiquidBlock){c.status=UNSUPPORTED;c.reason="RECOVERY_UNSUPPORTED";}
                else {c.status=PENDING;c.reason=c.blockEntity?"RECOVERY_BLOCK_ENTITY_DATA":"";}
                state.index(e,c);reviewed.add(e);data.touch();
            }
            // Unsuccessful destruction probes must not accumulate an unbounded history.
            for(var e:reviewed)if(e.cells.values().stream().allMatch(c->c.status==UNCHANGED))state.incidents.remove(e.id);
        }
    }
    /** A horizontal claim does not make a natural deep cave a resident worksite. */
    private static boolean inRecoveryScope(ServerLevel l,SettlementRecord s,String cause,BlockPos at,BlockState original) {
        if(!cause.equals("enderman") || !naturalTerrain(original))return true;
        int surface=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
        if(at.getY()>=surface-8)return true;
        // Registered underground buildings and their actual paid supports are independent of core height.
        for(var b:s.buildings())if(b.active() && !b.demolitionQueued() && b.contains(at))return true;
        if(com.hkin.exiledsettlement.road.RoadSpaceData.get(l).contains(at))return true;
        for(var p:s.terrain().projectsAt(at))for(int i:p.indices(at)) {
            var step=p.steps.get(i);
            if(step.position().equals(at) && step.fill() && matches(original,step.target())
                    && (p.retiredPaid.get(i) || !p.abandoned.get(i) && (i<p.cursor || p.finished.get(i))))return true;
        }
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.containsColumn(at)
                && (b.customTemplate()!=null || b.region()!=null || BlueprintTemplates.supports(b.type()))) {
            var goal=BlueprintTemplates.create(b).placementAt(at);
            if(goal!=null && goal.mode().isFinalStructure() && !goal.state().isAir() && matches(original,goal.state()))return true;
        }
        return false;
    }
    private static boolean naturalTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MYCELIUM);
    }
    private static boolean checkScope(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c) {
        if(c.status==SKIPPED)return false;
        if(c.status!=OBSERVING && c.status!=PENDING)return true;
        if(c.scopeChecked)return true;
        c.scopeChecked=true;
        if(!inRecoveryScope(l,s,e.cause,c.pos,c.original)) {
            c.status=SKIPPED;c.reason="RECOVERY_OUTSIDE_WORK_AREA";s.mobRecovery().index(e,c);
        }
        SettlementSavedData.get(l).touch();return c.status!=SKIPPED;
    }
    public static boolean owns(SettlementRecord s,BlockPos p){return p!=null && s.mobRecovery().owner(p)!=null;}
    public static boolean owns(ServerLevel l,BlockPos p){var s=SettlementSavedData.get(l).findContaining(p).orElse(null);return s!=null && owns(s,p);}
    public static MobRecoveryState.Incident task(SettlementRecord s,CitizenRecord c){
        if(c.workState()!=CitizenWorkState.TO_REPAIR && c.workState()!=CitizenWorkState.TO_REPAIR_SUPPLY)return null;
        return s.mobRecovery().incidents.get(c.workState()==CitizenWorkState.TO_REPAIR_SUPPLY?c.sourceBuildingId():c.targetBuildingId());
    }
    public static MobRecoveryState.Cell cell(SettlementRecord s,CitizenRecord c){var e=task(s,c);return e==null?null:e.cells.get(c.targetPosition());}
    public static void playerEdit(ServerLevel l,BlockPos p){
        var data=SettlementSavedData.get(l);var s=data.findContaining(p).orElse(null);if(s==null)return;
        var e=s.mobRecovery().owner(p);if(e==null)return;var c=e.cells.get(p);c.status=CONFLICT;c.reason="RECOVERY_PLAYER_EDIT";data.touch();
    }
    public static boolean matches(BlockState a,BlockState b){return BlueprintService.matchesPlacedState(a,b);}
    public static Item material(BlockState state){
        if(state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH))return Items.DIRT;
        var item=state.getBlock().asItem();return item==Items.AIR?null:item;
    }
    public static int available(CitizenRecord c,Item item){
        int count=0;for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++)
            if(!CitizenEquipmentService.isReservedEquipment(c,slot) && c.inventoryItem(slot).is(item))count+=c.inventoryItem(slot).getCount();
        return count;
    }
    public static int cost(BlockState state){
        if(state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE)return 2;
        if(state.hasProperty(BlockStateProperties.LAYERS))return state.getValue(BlockStateProperties.LAYERS);
        if(state.hasProperty(BlockStateProperties.CANDLES))return state.getValue(BlockStateProperties.CANDLES);
        if(state.hasProperty(BlockStateProperties.PICKLES))return state.getValue(BlockStateProperties.PICKLES);
        if(state.hasProperty(BlockStateProperties.EGGS))return state.getValue(BlockStateProperties.EGGS);
        return 1;
    }
    private static BlockPos partner(MobRecoveryState.Cell c){
        var state=c.original;
        if(state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock)
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER?c.pos.above():c.pos.below();
        if(state.getBlock() instanceof BedBlock)return c.pos.relative(state.getValue(BedBlock.FACING),state.getValue(BedBlock.PART)==BedPart.FOOT?1:-1);
        return null;
    }
    public static List<MobRecoveryState.Cell> group(MobRecoveryState.Incident e,MobRecoveryState.Cell c){
        var p=partner(c);if(p==null)return List.of(c);
        var other=e.cells.get(p);if(other==null)return List.of(c);
        return List.of(c,other).stream().sorted(Comparator.comparingInt((MobRecoveryState.Cell v)->v.pos.getY())
            .thenComparingInt(v->v.original.hasProperty(BedBlock.PART) && v.original.getValue(BedBlock.PART)==BedPart.HEAD?1:0)).toList();
    }
    public static String ready(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c){
        if(l.hasChunkAt(c.pos) && !checkScope(l,s,e,c))return c.reason;
        if(c.status!=PENDING)return c.reason.isEmpty()?"RECOVERY_WAITING":c.reason;
        if(c.retryAt>l.getGameTime())return "RECOVERY_WAITING";
        for(var v:group(e,c)){
            if(!l.hasChunkAt(v.pos))return "RECOVERY_UNLOADED";
            if(!s.contains(v.pos) || s.community().danger(v.pos) || s.decorations().forbidden(v.pos))return "PROTECTED_AREA";
            if(v.status==CONFLICT || v.status==UNSUPPORTED || v.status==OBSERVING)return "RECOVERY_CHANGED";
            var now=l.getBlockState(v.pos);
            if(matches(now,v.original))continue;
            if(!now.equals(v.damaged))return "RECOVERY_CHANGED";
            if(!now.isAir() && !now.canBeReplaced())return "REPAIR_POSITION_OCCUPIED";
            var p=partner(v);
            if(p==null && !v.original.canSurvive(l,v.pos))return "RECOVERY_SUPPORT";
            if(v.original.getBlock() instanceof FallingBlock && FallingBlock.isFree(l.getBlockState(v.pos.below())))return "RECOVERY_SUPPORT";
            if(!v.original.getCollisionShape(l,v.pos).isEmpty() && !l.getEntitiesOfClass(LivingEntity.class,new AABB(v.pos)).isEmpty())return "RECOVERY_OCCUPIED";
        }
        var pair=partner(c);
        if(pair!=null && !e.cells.containsKey(pair))return "RECOVERY_UNSUPPORTED";
        if((c.original.getBlock() instanceof DoorBlock || c.original.getBlock() instanceof BedBlock)
                && group(e,c).stream().anyMatch(v->(v.original.getBlock() instanceof BedBlock || v.original.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER)
                    && !l.getBlockState(v.pos.below()).isFaceSturdy(l,v.pos.below(),Direction.UP)))return "RECOVERY_SUPPORT";
        return "";
    }
    public static MobRecoveryState.Cell candidate(ServerLevel l,SettlementRecord s,CitizenRecord c,Set<BlockPos> claimed){
        if(com.hkin.exiledsettlement.debug.ConstructionDebugService.workEnabled(l,s))return null;
        int routeChecks=0;
        for(var e:s.mobRecovery().incidents.values()){
            if(WorkTaskService.assigned(s,e.id)>=2 && task(s,c)!=e)continue;
            for(var v:e.cells.values().stream().filter(v->v.status==PENDING).sorted(Comparator.comparingInt(v->v.pos.getY())).toList()){
                if(group(e,v).stream().anyMatch(part->claimed.contains(part.pos)))continue;
                String reason=ready(l,s,e,v);if(!reason.isEmpty()){if(!reason.equals("RECOVERY_WAITING") || v.reason.isEmpty())v.reason=reason;continue;}
                if(v.routeFailures>0) {
                    if(routeChecks++>=2)continue;
                    var actor=CitizenProjectionService.findProjection(l,c);
                    if(actor==null)continue;
                    if(!arrived(e,actor,v.pos)) {
                        var station=approach(l,e,actor,v.pos);
                        if(station==null || actor.probeConstructionReturnPath(station)==null){defer(l,s,v,"RECOVERY_UNREACHABLE");continue;}
                    }
                }
                var item=material(v.original);int count=cost(v.original);
                if(available(c,item)<count
                        && !s.creativeCoreInstalled() && !WarehouseConstructionSupply.available(l,s,item,count)){v.reason="MATERIALS_MISSING";continue;}
                return v;
            }
        }return null;
    }
    public static boolean assign(ServerLevel l,SettlementRecord s,CitizenRecord c,Set<BlockPos> claimed){
        if(com.hkin.exiledsettlement.debug.ConstructionDebugService.workEnabled(l,s))return false;
        if(c.profession()!=CitizenProfession.WORKER || s.community().evacuation || s.community().policy.equals("emergency"))return false;
        var v=candidate(l,s,c,claimed);if(v==null)return false;var e=s.mobRecovery().owner(v.pos);
        if(!c.startRepair(e.id,v.pos))return false;for(var part:group(e,v))claimed.add(part.pos);WorkTaskService.bind(s,c);return true;
    }
    private static List<BlockPos> workArea(MobRecoveryState.Incident e,BlockPos target) {
        var first=e.cells.get(target);if(first==null || first.status!=PENDING)return List.of();
        var result=new ArrayList<BlockPos>();var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();
        queue.add(target);seen.add(target);
        while(!queue.isEmpty() && result.size()<512) {
            var at=queue.removeFirst();result.add(at);
            for(var direction:Direction.values()) {
                var next=at.relative(direction);var cell=e.cells.get(next);
                if(cell!=null && cell.status==PENDING && seen.add(next))queue.addLast(next);
            }
        }
        return result;
    }
    private static boolean withinWorkArea(List<BlockPos> area,BlockPos feet) {
        return area.stream().anyMatch(at->Math.abs(at.getX()-feet.getX())<=4 && Math.abs(at.getZ()-feet.getZ())<=4
                && Math.abs(at.getY()-feet.getY())<=4);
    }
    public static boolean arrived(MobRecoveryState.Incident e,SettlementCitizenEntity actor,BlockPos target){
        return actor.onGround() && withinWorkArea(workArea(e,target),actor.navigationFeetPosition());
    }
    /** Kept for older callers; terminal records never establish a recovery worksite. */
    public static boolean arrived(MobRecoveryState.Incident e,SettlementCitizenEntity actor){
        return actor.onGround() && e.cells.values().stream().anyMatch(c->c.status==PENDING
                && withinWorkArea(List.of(c.pos),actor.navigationFeetPosition()));
    }
    public static BlockPos approach(ServerLevel l,MobRecoveryState.Incident e,SettlementCitizenEntity actor,BlockPos target){
        var area=workArea(e,target);if(area.isEmpty())return null;
        var feet=actor.navigationFeetPosition();
        if(withinWorkArea(area,feet) && actor.canStandAtPosition(feet))return feet;
        var base=area.stream().min(Comparator.comparingDouble(at->at.distSqr(feet))).orElse(target);
        var candidates=new LinkedHashSet<BlockPos>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
            var column=base.offset(dx,0,dz);if(!l.hasChunkAt(column))continue;
            int surface=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ());
            for(int y:new int[]{surface,feet.getY(),base.getY(),base.getY()+1}) {
                var at=column.atY(y);
                if(withinWorkArea(area,at) && actor.canStandAtPosition(at))candidates.add(at);
            }
        }
        return actor.findReachableBedStation(candidates.stream().sorted(Comparator.comparingDouble(at->at.distSqr(feet))).toList());
    }
    public static String place(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c,SettlementCitizenEntity actor){
        return place(l,s,e,c,actor,false);
    }
    public static String debugPlace(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c){
        if(!com.hkin.exiledsettlement.debug.ConstructionDebugService.workEnabled(l,s))return "RECOVERY_WAITING";
        if(group(e,c).stream().anyMatch(v->!com.hkin.exiledsettlement.debug.ConstructionForceWork.clearForEvolve(l,s,v.pos)))return "RECOVERY_OCCUPIED";
        return place(l,s,e,c,null,true);
    }
    private static String place(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c,SettlementCitizenEntity actor,boolean debug){
        var reason=ready(l,s,e,c);if(!reason.isEmpty())return reason;
        if(!debug && !arrived(e,actor,c.pos))return "RECOVERY_UNREACHABLE";
        var snapshots=new ArrayList<BlockSnapshot>();var changed=new ArrayList<MobRecoveryState.Cell>();
        for(var v:group(e,c))if(!matches(l.getBlockState(v.pos),v.original)){
            var snap=BlockSnapshot.create(l.dimension(),l,v.pos,Block.UPDATE_CLIENTS);snapshots.add(snap);changed.add(v);
            if(!l.setBlock(v.pos,v.original,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS)
                    || EventHooks.onBlockPlace(actor,snap,Direction.UP)){
                for(int i=snapshots.size()-1;i>=0;i--)snapshots.get(i).restore(Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
                return "PROTECTED_AREA";
            }
        }
        for(var v:changed){
            l.updateNeighborsAt(v.pos,v.original.getBlock());
            BlueprintBlockStateResolver.refreshConnections(l,v.pos);
            for(var direction:Direction.values())BlueprintBlockStateResolver.refreshConnections(l,v.pos.relative(direction));
        }
        for(var v:group(e,c))if(!matches(l.getBlockState(v.pos),v.original)){
            for(int i=snapshots.size()-1;i>=0;i--)snapshots.get(i).restore(Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
            return "RECOVERY_SUPPORT";
        }
        for(var v:group(e,c)){v.status=DONE;v.reason=v.blockEntity?"RECOVERY_BLOCK_ENTITY_DATA":"";v.routeFailures=0;v.retryAt=0;s.mobRecovery().index(e,v);}
        SettlementSavedData.get(l).touch();return "";
    }
    public static void defer(ServerLevel l,SettlementRecord s,MobRecoveryState.Cell c,String reason){
        c.reason=reason;
        if(reason.equals("RECOVERY_UNREACHABLE"))c.routeFailures=Math.min(6,c.routeFailures+1);
        c.retryAt=l.getGameTime()+(reason.equals("RECOVERY_UNREACHABLE")?Math.min(2400L,100L<<c.routeFailures):100);
        if(reason.equals("RECOVERY_CHANGED")){c.status=CONFLICT;}
        SettlementSavedData.get(l).touch();
    }
}

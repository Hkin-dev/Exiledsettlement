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
            var state=s.mobRecovery();var prior=state.owner(p);
            if(prior!=null)continue;
            var event=groups.computeIfAbsent(s,key->{var e=new MobRecoveryState.Incident(UUID.randomUUID(),source.getUUID(),cause,l.getGameTime());state.incidents.put(e.id,e);return e;});
            var c=new MobRecoveryState.Cell(p,before);c.blockEntity=l.getBlockEntity(p)!=null;state.add(event,c);
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
        var data=SettlementSavedData.get(l);int budget=256;
        for(var s:data.settlements()){
            var state=s.mobRecovery();int count=state.observations.size();
            var reviewed=new HashSet<MobRecoveryState.Incident>();
            while(count-->0 && budget-->0 && !state.observations.isEmpty()){
                var entry=state.observations.removeFirst();var e=entry.getKey();var c=entry.getValue();
                if(c.status!=OBSERVING)continue;
                if(l.getGameTime()<e.time+5 || !l.hasChunkAt(c.pos)){state.observations.addLast(entry);continue;}
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
        for(var e:s.mobRecovery().incidents.values()){
            if(WorkTaskService.assigned(s,e.id)>=2 && task(s,c)!=e)continue;
            for(var v:e.cells.values().stream().filter(v->v.status==PENDING).sorted(Comparator.comparingInt(v->v.pos.getY())).toList()){
                if(group(e,v).stream().anyMatch(part->claimed.contains(part.pos)))continue;
                String reason=ready(l,s,e,v);if(!reason.isEmpty()){v.reason=reason;continue;}
                var item=material(v.original);int count=cost(v.original);
                if(available(c,item)<count
                        && !s.creativeCoreInstalled() && !WarehouseConstructionSupply.available(l,s,item,count)){v.reason="MATERIALS_MISSING";continue;}
                return v;
            }
        }return null;
    }
    public static boolean assign(ServerLevel l,SettlementRecord s,CitizenRecord c,Set<BlockPos> claimed){
        if(c.profession()!=CitizenProfession.WORKER || s.community().evacuation || s.community().policy.equals("emergency"))return false;
        var v=candidate(l,s,c,claimed);if(v==null)return false;var e=s.mobRecovery().owner(v.pos);
        if(!c.startRepair(e.id,v.pos))return false;for(var part:group(e,v))claimed.add(part.pos);WorkTaskService.bind(s,c);return true;
    }
    public static boolean arrived(MobRecoveryState.Incident e,SettlementCitizenEntity actor){
        var feet=actor.navigationFeetPosition();
        return e.cells.values().stream().filter(c->c.status!=UNCHANGED && c.status!=OBSERVING)
                .anyMatch(c->Math.abs(c.pos.getX()-feet.getX())<=4 && Math.abs(c.pos.getZ()-feet.getZ())<=4
                    && Math.abs(c.pos.getY()-feet.getY())<=32)
                && actor.onGround();
    }
    public static BlockPos approach(ServerLevel l,MobRecoveryState.Incident e,SettlementCitizenEntity actor,BlockPos target){
        return TimedConstructionService.approach(l,null,actor,target);
    }
    public static String place(ServerLevel l,SettlementRecord s,MobRecoveryState.Incident e,MobRecoveryState.Cell c,SettlementCitizenEntity actor){
        var reason=ready(l,s,e,c);if(!reason.isEmpty())return reason;
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
        for(var v:group(e,c)){v.status=DONE;v.reason=v.blockEntity?"RECOVERY_BLOCK_ENTITY_DATA":"";s.mobRecovery().index(e,v);}
        SettlementSavedData.get(l).touch();return "";
    }
    public static void defer(ServerLevel l,SettlementRecord s,MobRecoveryState.Cell c,String reason){
        c.reason=reason;c.retryAt=l.getGameTime()+100;
        if(reason.equals("RECOVERY_CHANGED")){c.status=CONFLICT;}
        SettlementSavedData.get(l).touch();
    }
}

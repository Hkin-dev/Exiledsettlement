package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

/** A finite, visible excavation driven only by successful on-site mining cycles. */
public final class MineExpansionService {
    private MineExpansionService() {}
    public record Step(BlockPos local, BlockState state, BlockPos station, BlockPos secondStation, BlockPos floor) {}
    private static final List<Step> STEPS = LayeredMinePlan.steps();
    private static final Map<BlockPos,Integer> FIRST = new HashMap<>();
    private static final int[] LOWEST = new int[STEPS.size()+1];
    static { LOWEST[0]=-7; for(int i=0;i<STEPS.size();i++) {
        FIRST.putIfAbsent(STEPS.get(i).local(),i);
        LOWEST[i+1]=Math.min(LOWEST[i],STEPS.get(i).local().getY()-1);
    }}
    public static List<Step> steps() { return STEPS; }
    public static int lowestY(BuildingRecord mine) {return mine.position().getY()+LOWEST[Math.clamp(mine.mineExpansion().cursor,0,STEPS.size())];}
    /** Reuse completed landings for long vertical trips; never search surface-only waypoints underground. */
    public static BlockPos navigationWaypoint(SettlementCitizenEntity actor,BlockPos target) {
        if(!(actor.level() instanceof ServerLevel level))return target;
        var id=CitizenProjectionService.settlementId(actor);if(id==null)return target;
        var settlement=SettlementSavedData.get(level).findById(id).orElse(null);if(settlement==null)return target;
        var origin=actor.blockPosition();
        if(Math.abs(origin.getY()-target.getY())<=10)return target;
        for(var mine:settlement.buildings())if(mine.active() && enabled(mine)
                && (mine.contains(origin) || mine.contains(target))) {
            int y=origin.getY()-mine.position().getY();
            int next=target.getY()<origin.getY()?Math.floorDiv(y-1,6)*6:-Math.floorDiv(-y-1,6)*6;
            next=Math.min(-6,next);
            var at=world(mine,new BlockPos(-1,next,3));
            if(level.hasChunkAt(at) && level.getBlockState(at.below()).isFaceSturdy(level,at.below(),Direction.UP)
                    && level.noCollision(new AABB(at.getX()+.2,at.getY(),at.getZ()+.2,at.getX()+.8,at.getY()+1.95,at.getZ()+.8)))
                return at;
        }
        return target;
    }
    public static BlockPos world(BuildingRecord mine, BlockPos local) {
        return MineBlueprint.position(mine.position(),mine.facing(),local.getX(),local.getY(),local.getZ());
    }
    public static BlockState state(BuildingRecord mine, Step step) {
        return step.state().rotate(MineBlueprint.rotation(mine.facing()));
    }
    public static Step next(BuildingRecord mine) {
        return mine.mineExpansion().cursor >= 0 && mine.mineExpansion().cursor < STEPS.size() ? STEPS.get(mine.mineExpansion().cursor) : null;
    }
    public static boolean enabled(BuildingRecord mine) {
        return mine.type()==BuildingType.MINE && mine.customTemplate()==null;
    }
    public static List<BlockPos> frontStations(BuildingRecord mine) {
        Step step=next(mine); if(step==null)step=STEPS.getLast();
        return List.of(world(mine,step.station()),world(mine,step.secondStation()));
    }
    public static List<BlockPos> workPositions(BuildingRecord mine) {
        if(!enabled(mine))return MineBlueprint.workPositions(mine.position(),mine.facing());
        var result=new ArrayList<>(frontStations(mine));
        result.addAll(MineBlueprint.workPositions(mine.position(),mine.facing()));
        return result.stream().distinct().limit(10).toList();
    }
    public static boolean frontWorker(BuildingRecord mine,BlockPos target) {
        return enabled(mine) && target!=null && !MineBlueprint.workPositions(mine.position(),mine.facing()).contains(target);
    }
    public static Item needed(BuildingRecord mine) {
        var step=next(mine); if(step==null || step.state().isAir())return null;
        return step.state().is(Blocks.WALL_TORCH)?Items.TORCH:step.state().getBlock().asItem();
    }
    public static boolean owns(BuildingRecord mine,BlockPos pos) {
        if(!enabled(mine))return false;
        Integer index=FIRST.get(MineBlueprint.local(mine.position(),mine.facing(),pos));
        return index!=null && index<=mine.mineExpansion().cursor;
    }
    public static void playerEdit(ServerLevel level,BlockPos pos) {
        boolean changed=false;
        for(var settlement:SettlementSavedData.get(level).settlements())for(var mine:settlement.buildings()) {
            if(!mine.active() || !enabled(mine))continue;
            var local=MineBlueprint.local(mine.position(),mine.facing(),pos);
            boolean nearby=FIRST.containsKey(local);
            for(var d:Direction.values())nearby|=FIRST.containsKey(local.relative(d));
            if(nearby)changed|=mine.mineExpansion().playerEdits.add(pos.asLong());
        }
        if(changed)SettlementSavedData.get(level).touch();
    }
    private static String locationGuard(ServerLevel level,SettlementRecord s,BuildingRecord mine,BlockPos at) {
        if(!level.hasChunkAt(at) || level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at))return "矿道等待已加载的有效地形";
        if(!s.contains(at) || s.community().danger(at) || s.decorations().forbidden(at)
                || s.terrain().suppressed.contains(at.asLong()) || mine.mineExpansion().playerEdits.contains(at.asLong())
                || MobRecoveryService.owns(level,at)
                || com.hkin.exiledsettlement.terrain.PlayerWorksService.protectedForMineAdvance(s,mine,at)
                || com.hkin.exiledsettlement.road.RoadSpaceData.get(level).contains(at))return "矿道扩张遇到保护范围或玩家改动";
        for(var other:SettlementSavedData.get(level).settlements())if(other.active()) {
            if(other!=s && other.contains(at))return "矿道扩张遇到其他领地";
            for(var b:other.buildings())if(b!=mine && b.active() && b.containsColumn(at))return "矿道扩张避让其他建筑";
            for(var b:other.formalBlueprints())if(!b.cancellationQueued() && b.containsColumn(at))return "矿道扩张避让待建工程";
        }
        return "";
    }
    private static boolean rock(BlockState state) {
        return state.is(Blocks.STONE)||state.is(Blocks.DEEPSLATE)||state.is(Blocks.GRANITE)
                ||state.is(Blocks.DIORITE)||state.is(Blocks.ANDESITE)||state.is(Blocks.TUFF)
                ||state.is(Blocks.CALCITE)||state.is(Blocks.DRIPSTONE_BLOCK)
                ||state.is(net.minecraft.tags.BlockTags.COAL_ORES)||state.is(net.minecraft.tags.BlockTags.IRON_ORES)
                ||state.is(net.minecraft.tags.BlockTags.COPPER_ORES)||state.is(net.minecraft.tags.BlockTags.GOLD_ORES)
                ||state.is(net.minecraft.tags.BlockTags.REDSTONE_ORES)||state.is(net.minecraft.tags.BlockTags.LAPIS_ORES)
                ||state.is(net.minecraft.tags.BlockTags.DIAMOND_ORES)||state.is(net.minecraft.tags.BlockTags.EMERALD_ORES);
    }
    public static String guard(ServerLevel level,SettlementRecord s,BuildingRecord mine,Step step) {
        var at=world(mine,step.local());String reason=locationGuard(level,s,mine,at);if(!reason.isEmpty())return reason;
        var original=BlueprintTemplates.create(mine.type(),mine.position(),mine.facing(),mine.templateVersion()).placementAt(at);
        var current=level.getBlockState(at);
        boolean shell=original!=null && current.equals(original.state())
                && (current.is(Blocks.COBBLESTONE) || current.is(Blocks.ANDESITE));
        boolean completed=current.equals(state(mine,step));
        if(level.getBlockEntity(at)!=null || current.getDestroySpeed(level,at)<0 || !current.getFluidState().isEmpty())return "矿道遇到容器、液体或不可挖方块";
        if(!current.isAir() && !rock(current) && !shell && !completed)return "矿道保留非矿石地形与人工设施";
        for(var direction:Direction.values()) {
            var adjacent=at.relative(direction);
            if(!level.hasChunkAt(adjacent))return "矿道等待相邻区块加载";
            if(!level.getFluidState(adjacent).isEmpty())return "矿道临近水或岩浆，暂停扩张";
        }
        if(level.getBlockState(at.above()).getBlock() instanceof FallingBlock)return "矿道上方有松散方块，暂停扩张";
        // The approved operation carries its actual local landing/tread support.
        var floor=world(mine,step.floor());
        if(!level.hasChunkAt(floor) || !level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
                || level.getBlockState(floor).getBlock() instanceof FallingBlock
                || !level.getFluidState(floor).isEmpty())return "矿道前方缺少稳固地面";
        boolean covered=false;
        for(int dy=1;dy<=12;dy++) {
            var roof=at.above(dy);
            if(!level.hasChunkAt(roof) || roof.getY()>=mine.position().getY()-1)break;
            if(level.getBlockState(roof).isFaceSturdy(level,roof,Direction.DOWN)
                    && !(level.getBlockState(roof).getBlock() instanceof FallingBlock)){covered=true;break;}
        }
        if(!covered)return "矿道上方覆盖不足";
        return "";
    }
    public static void animate(ServerLevel level,BuildingRecord mine,SettlementCitizenEntity actor) {
        Step step=next(mine);if(step==null)return;
        var at=world(mine,step.local());
        actor.getLookControl().setLookAt(at.getX()+.5,at.getY()+.5,at.getZ()+.5);
        actor.swing(InteractionHand.MAIN_HAND);
        var before=level.getBlockState(at);
        if(!before.isAir())level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,before),at.getX()+.5,at.getY()+.5,at.getZ()+.5,3,.2,.2,.2,.02);
    }
    public static void completedCycle(ServerLevel level,SettlementSavedData data,SettlementRecord s,
            BuildingRecord mine,CitizenRecord citizen,SettlementCitizenEntity actor) {
        if(com.hkin.exiledsettlement.debug.ConstructionDebugService.workEnabled(level,s))return;
        var history=mine.mineExpansion();var step=next(mine);
        if(step==null){history.reason="地下扩张范围已完成，继续混合采掘";return;}
        if(!mine.operational() || !mine.repairPositions().isEmpty() || mine.demolitionQueued()
                || citizen.profession()!=CitizenProfession.MINER || !mine.id().equals(citizen.workBuildingId())
                || CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK
                || frontStations(mine).stream().noneMatch(p->actor.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)<2.25))return;
        history.work=Math.min(2,history.work+1);data.touch();
        if(history.work<2 || level.getGameTime()-history.lastAction<com.hkin.exiledsettlement.citizen.CitizenToolSpeed.interval(citizen,200))return;
        String reason=guard(level,s,mine,step);
        if(!reason.isEmpty()){history.reason=reason;return;}
        var at=world(mine,step.local());var before=level.getBlockState(at);var target=state(mine,step);
        if(before.equals(target)){history.cursor++;history.work=0;history.lastAction=level.getGameTime();history.reason="";data.touch();return;}
        Item material=needed(mine);
        if(material!=null && !citizen.hasInventoryItem(v->v.is(material))) {history.reason="矿道等待台阶、支护或照明材料";return;}
        int tool=CitizenEquipmentService.toolSlotForWork(citizen);

        if(!target.isAir() && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(at),e->e.isAlive()).isEmpty()) {history.reason="矿道支护位置有居民，稍后施工";return;}
        // Existing work cycle reserves ample free slots; real excavation loot is separate from random finds.
        if(!TimedConstructionService.put(level,at,target,actor)){history.reason="矿道扩张被保护事件阻止";return;}
        if(material!=null)citizen.extractInventoryItem(v->v.is(material),1);
        if(!before.isAir())citizen.damageInventoryItem(tool,1);
        level.levelEvent(2001,at,Block.getId(before));
        history.cursor++;history.work=0;history.lastAction=level.getGameTime();history.reason="";
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"mine_expand",at,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString(),1);
        data.touch();
    }
    /** Same registered excavation and safety checks, without a mining cycle or invented supplies. */
    public static boolean debugAdvance(ServerLevel level,SettlementRecord s,BuildingRecord mine) {
        if(!com.hkin.exiledsettlement.debug.ConstructionDebugService.workEnabled(level,s)
                || !enabled(mine) || !mine.active() || mine.demolitionQueued() || !mine.repairPositions().isEmpty())return false;
        var step=next(mine);if(step==null)return false;
        var history=mine.mineExpansion();String reason=guard(level,s,mine,step);
        if(!reason.isEmpty()){history.reason=reason;SettlementSavedData.get(level).touch();return false;}
        if(!com.hkin.exiledsettlement.debug.ConstructionForceWork.put(level,s,world(mine,step.local()),state(mine,step))) {
            history.reason="调试矿井扩建等待占位离开或保护许可";return false;
        }
        history.cursor++;history.work=0;history.lastAction=level.getGameTime();history.reason="";
        SettlementSavedData.get(level).touch();return true;
    }
    public static BuildingBlueprintTemplate template(BuildingRecord mine,BuildingBlueprintTemplate base) {
        var history=mine.mineExpansion();if(history.cursor==0)return base;
        if(history.cached!=null && history.cachedCursor==history.cursor)return history.cached;
        var cells=new LinkedHashMap<BlockPos,BlueprintPlacement>();base.placements().forEach(p->cells.put(p.position(),p));
        for(int i=0;i<Math.min(history.cursor,STEPS.size());i++) {
            var step=STEPS.get(i);var at=world(mine,step.local());var target=state(mine,step);
            cells.put(at,new BlueprintPlacement(at,target,target.isAir()?null:target.getBlock().asItem(),
                    target.isAir()?BlueprintPlacementMode.CLEARANCE:BlueprintPlacementMode.STRUCTURE));
        }
        int minX=base.minCorner().getX(),maxX=base.maxCorner().getX(),minZ=base.minCorner().getZ(),maxZ=base.maxCorner().getZ();
        int minY=base.minCorner().getY(),maxY=base.maxCorner().getY();
        for(var at:cells.keySet()){minX=Math.min(minX,at.getX());maxX=Math.max(maxX,at.getX());minZ=Math.min(minZ,at.getZ());maxZ=Math.max(maxZ,at.getZ());minY=Math.min(minY,at.getY());maxY=Math.max(maxY,at.getY());}
        history.cached=new BuildingBlueprintTemplate(base.type(),base.anchor(),base.facing(),maxX-minX+1,maxZ-minZ+1,maxY-minY+1,
                new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ),base.transitionMargin(),List.copyOf(cells.values()),base.entrances());
        history.cachedCursor=history.cursor;return history.cached;
    }
}

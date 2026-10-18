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
    public record Step(BlockPos local, BlockState state, BlockPos station, Direction direction) {}
    private static final List<Step> STEPS = plan();
    public static List<Step> steps() { return STEPS; }
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y - 10, z); }
    private static void add(List<Step> out, BlockPos p, BlockState state, BlockPos station, Direction direction) {
        out.add(new Step(p, state, station, direction));
    }
    private static List<Step> plan() {
        var out = new ArrayList<Step>();
        // Two-wide main passage, then a six-wide terminal working chamber.
        for (int z = 4; z <= 23; z++) {
            int left = z < 20 ? 0 : -2, right = z < 20 ? 1 : 3;
            var station = p(0, 0, z - 1);
            for (int y = 2; y >= 0; y--) for (int x = left; x <= right; x++)
                add(out, p(x,y,z), Blocks.AIR.defaultBlockState(), station, Direction.SOUTH);
            if (z % 4 == 0 || z == 23) {
                for (int x : new int[]{left-1, right+1}) for (int y=0;y<3;y++)
                    add(out,p(x,y,z),Blocks.SPRUCE_LOG.defaultBlockState(),station,Direction.SOUTH);
                for (int x=left-1;x<=right+1;x++)
                    add(out,p(x,3,z),Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X),station,Direction.SOUTH);
                add(out,p(left,1,z),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.EAST),station,Direction.SOUTH);
            }
        }
        // A real side drift connects to the completed main passage, not a detached room.
        for (int x=-1;x>=-8;x--) {
            var station=p(x+1,0,10);
            for(int y=2;y>=0;y--)for(int z=10;z<=11;z++)
                add(out,p(x,y,z),Blocks.AIR.defaultBlockState(),station,Direction.WEST);
            if(x % 4 == 0) {
                for(int z:new int[]{9,12})for(int y=0;y<3;y++)
                    add(out,p(x,y,z),Blocks.SPRUCE_LOG.defaultBlockState(),station,Direction.WEST);
                for(int z=9;z<=12;z++)
                    add(out,p(x,3,z),Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z),station,Direction.WEST);
                add(out,p(x,1,10),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.SOUTH),station,Direction.WEST);
            }
        }
        return List.copyOf(out);
    }
    public static BlockPos world(BuildingRecord mine, BlockPos local) {
        return MineBlueprint.position(mine.position(),mine.facing(),local.getX(),local.getY(),local.getZ());
    }
    public static BlockState state(BuildingRecord mine, Step step) {
        // Local SOUTH is the mine's facing; local EAST is its right.
        var rotation = switch(mine.facing()) {
            case SOUTH -> Rotation.NONE; case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180; default -> Rotation.COUNTERCLOCKWISE_90;
        };
        // MineBlueprint's local +x points to facing.clockwise (WEST for SOUTH),
        // so the local frame is mirrored relative to vanilla coordinates.
        return step.state().mirror(Mirror.FRONT_BACK).rotate(rotation);
    }
    public static Step next(BuildingRecord mine) {
        return mine.mineExpansion().cursor < STEPS.size() ? STEPS.get(mine.mineExpansion().cursor) : null;
    }
    public static boolean enabled(BuildingRecord mine) {
        return mine.type()==BuildingType.MINE && mine.customTemplate()==null;
    }
    public static List<BlockPos> frontStations(BuildingRecord mine) {
        Step step=next(mine); if(step==null)step=STEPS.getLast();
        var first=step.station();
        var second=step.direction()==Direction.SOUTH ? first.offset(1,0,0) : first.offset(0,0,1);
        return List.of(world(mine,first),world(mine,second));
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
        // Completed modifications plus the immediate work cell are reserved in 3D.
        int count=Math.min(STEPS.size(),mine.mineExpansion().cursor+1);
        for(int i=0;i<count;i++)if(world(mine,STEPS.get(i).local()).equals(pos))return true;
        return false;
    }
    public static void playerEdit(ServerLevel level,BlockPos pos) {
        boolean changed=false;
        for(var settlement:SettlementSavedData.get(level).settlements())for(var mine:settlement.buildings()) {
            if(!mine.active() || !enabled(mine))continue;
            for(var step:STEPS)if(world(mine,step.local()).distManhattan(pos)<=1) {
                changed|=mine.mineExpansion().playerEdits.add(pos.asLong());break;
            }
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
        boolean shell=original!=null && original.state().is(Blocks.COBBLESTONE) && current.equals(original.state());
        if(level.getBlockEntity(at)!=null || current.getDestroySpeed(level,at)<0 || !current.getFluidState().isEmpty())return "矿道遇到容器、液体或不可挖方块";
        if(!current.isAir() && !rock(current) && !shell)return "矿道保留非矿石地形与人工设施";
        for(var direction:Direction.values()) {
            var adjacent=at.relative(direction);
            if(!level.hasChunkAt(adjacent))return "矿道等待相邻区块加载";
            if(!level.getFluidState(adjacent).isEmpty())return "矿道临近水或岩浆，暂停扩张";
        }
        if(level.getBlockState(at.above()).getBlock() instanceof FallingBlock)return "矿道上方有松散方块，暂停扩张";
        // Never make an open floor, cross a cavern, or expose the surface.
        var floor=at.atY(mine.position().getY()-11);
        if(!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
                || level.getBlockState(floor).getBlock() instanceof FallingBlock
                || !level.getFluidState(floor).isEmpty())return "矿道前方缺少稳固地面";
        var roof=at.atY(mine.position().getY()-6);
        if(!level.hasChunkAt(roof) || !level.getBlockState(roof).isFaceSturdy(level,roof,Direction.DOWN))return "矿道上方覆盖不足";
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
        var history=mine.mineExpansion();var step=next(mine);
        if(step==null){history.reason="地下扩张范围已完成，继续混合采掘";return;}
        if(!mine.operational() || !mine.repairPositions().isEmpty() || mine.demolitionQueued()
                || citizen.profession()!=CitizenProfession.MINER || !mine.id().equals(citizen.workBuildingId())
                || CitizenWorkService.schedulePhase(level.getDayTime(),citizen)!=CitizenWorkService.SchedulePhase.WORK
                || frontStations(mine).stream().noneMatch(p->actor.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)<2.25))return;
        history.work=Math.min(2,history.work+1);data.touch();
        if(history.work<2 || level.getGameTime()-history.lastAction<200)return;
        String reason=guard(level,s,mine,step);
        if(!reason.isEmpty()){history.reason=reason;return;}
        Item material=needed(mine);
        if(material!=null && !citizen.hasInventoryItem(v->v.is(material))) {history.reason="矿道等待支护或照明材料";return;}
        var at=world(mine,step.local());var before=level.getBlockState(at);var target=state(mine,step);
        int tool=CitizenEquipmentService.toolSlotForWork(citizen);
        if(tool<0 || !before.isAir() && before.requiresCorrectToolForDrops() && !citizen.inventoryItem(tool).isCorrectToolForDrops(before)) {history.reason="矿道等待能开挖当前岩层的镐";return;}
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
    public static BuildingBlueprintTemplate template(BuildingRecord mine,BuildingBlueprintTemplate base) {
        var history=mine.mineExpansion();if(history.cursor==0)return base;
        if(history.cached!=null && history.cachedCursor==history.cursor)return history.cached;
        var cells=new LinkedHashMap<BlockPos,BlueprintPlacement>();base.placements().forEach(p->cells.put(p.position(),p));
        for(int i=0;i<history.cursor;i++) {
            var step=STEPS.get(i);var at=world(mine,step.local());var target=state(mine,step);
            cells.put(at,new BlueprintPlacement(at,target,target.isAir()?null:target.getBlock().asItem(),
                    target.isAir()?BlueprintPlacementMode.CLEARANCE:BlueprintPlacementMode.STRUCTURE));
        }
        int minX=base.minCorner().getX(),maxX=base.maxCorner().getX(),minZ=base.minCorner().getZ(),maxZ=base.maxCorner().getZ();
        for(var at:cells.keySet()){minX=Math.min(minX,at.getX());maxX=Math.max(maxX,at.getX());minZ=Math.min(minZ,at.getZ());maxZ=Math.max(maxZ,at.getZ());}
        history.cached=new BuildingBlueprintTemplate(base.type(),base.anchor(),base.facing(),maxX-minX+1,maxZ-minZ+1,base.height(),
                new BlockPos(minX,base.minCorner().getY(),minZ),new BlockPos(maxX,base.maxCorner().getY(),maxZ),base.transitionMargin(),List.copyOf(cells.values()),base.entrances());
        history.cachedCursor=history.cursor;return history.cached;
    }
}
